package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TelegramClientTest {
    private HttpServer server;
    private static final String TOKEN = "123456:PRIVATE_TEST_TOKEN";

    @AfterEach
    void stopServer() { if (server != null) server.stop(0); }

    private AppConfig config() {
        AppConfig config = new AppConfig();
        config.telegramBotToken = TOKEN;
        config.telegramChatId = "-100123456789";
        config.telegramThreadId = "12";
        return config;
    }

    private TelegramClient start(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return new TelegramClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Test
    void testConnectionUsesReadOnlyGetMe() throws Exception {
        List<String> paths = new ArrayList<>();
        TelegramClient client = start(exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            reply(exchange, 200, "{\"ok\":true,\"result\":{\"id\":123456,\"is_bot\":true,\"username\":\"stats_bot\"}}");
        });
        var result = client.testConnection(config());
        assertEquals("stats_bot", result.get("username"));
        assertEquals(List.of("/bot" + TOKEN + "/getMe"), paths);
    }

    @Test
    void sendPlainTextIncludesTargetAndThreadWithoutParseMode() throws Exception {
        List<JsonNode> bodies = new ArrayList<>();
        TelegramClient client = start(exchange -> {
            bodies.add(Json.MAPPER.readTree(exchange.getRequestBody()));
            reply(exchange, 200, "{\"ok\":true,\"result\":{\"message_id\":78}}");
        });
        String text = "AIAPI 日统计｜10/20–10/29（10天）\n1. New_HappyAIAI｜1,900｜18,980｜2.0%";
        var result = client.sendReport(config(), text);
        assertEquals(1, result.get("count"));
        assertEquals(List.of(78L), result.get("messageIds"));
        assertEquals(text, bodies.getFirst().path("text").asText());
        assertEquals("-100123456789", bodies.getFirst().path("chat_id").asText());
        assertEquals(12, bodies.getFirst().path("message_thread_id").asInt());
        assertFalse(bodies.getFirst().has("parse_mode"));
    }

    @Test
    void splitRespectsNewlineAndNeverBreaksSurrogatePair() {
        String text = "首行\n" + "😀".repeat(4100) + "\n末行";
        List<String> parts = TelegramClient.splitMessage(text);
        assertEquals("首行", parts.getFirst());
        for (String part : parts) {
            assertTrue(part.length() <= 4096);
            assertFalse(Character.isLowSurrogate(part.charAt(0)));
            assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
        }
        assertEquals(text.replace("\n", ""), String.join("", parts).replace("\n", ""));
    }

    @Test
    void explicitRateLimitRetriesBoundedly() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        TelegramClient client = start(exchange -> {
            if (attempts.incrementAndGet() < 2) {
                reply(exchange, 429, "{\"ok\":false,\"error_code\":429,\"parameters\":{\"retry_after\":1}}");
            } else reply(exchange, 200, "{\"ok\":true,\"result\":{\"message_id\":99}}");
        });
        assertEquals(1, client.sendReport(config(), "日报").get("count"));
        assertEquals(2, attempts.get());
    }

    @Test
    void ambiguousServerFailureDoesNotRetryOrExposeSecret() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        TelegramClient client = start(exchange -> {
            attempts.incrementAndGet();
            reply(exchange, 503, "{\"ok\":false,\"error_code\":503,\"description\":\"secret " + TOKEN + "\"}");
        });
        var failure = assertThrows(TelegramClient.DeliveryException.class,
                () -> client.sendReport(config(), "日报"));
        assertTrue(failure.deliveryUncertain);
        assertEquals(0, failure.sentParts);
        assertEquals(1, attempts.get());
        assertFalse(failure.getMessage().contains(TOKEN));
        assertNull(failure.getCause());
    }

    @Test
    void partialDeliveryReportsConfirmedPartsAndNeverResends() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        TelegramClient client = start(exchange -> {
            if (attempts.incrementAndGet() == 1) reply(exchange, 200, "{\"ok\":true,\"result\":{\"message_id\":1}}");
            else reply(exchange, 403, "{\"ok\":false,\"error_code\":403,\"description\":\"" + TOKEN + "\"}");
        });
        var failure = assertThrows(TelegramClient.DeliveryException.class,
                () -> client.sendReport(config(), "首段\n" + "尾".repeat(4096)));
        assertEquals(1, failure.sentParts);
        assertEquals(2, failure.totalParts);
        assertFalse(failure.deliveryUncertain);
        assertEquals(2, attempts.get());
        assertFalse(failure.getMessage().contains(TOKEN));
    }

    @Test
    void transportFailureRedactsRequestUrl() throws Exception {
        TelegramClient client = start(exchange -> reply(exchange, 200, "{}"));
        server.stop(0);
        var failure = assertThrows(TelegramClient.DeliveryException.class,
                () -> client.sendReport(config(), "日报"));
        assertTrue(failure.deliveryUncertain);
        assertFalse(failure.getMessage().contains(TOKEN));
        assertNull(failure.getCause());
    }

    @Test
    void rejectsInvalidCredentialsBeforeNetwork() {
        AppConfig config = config();
        config.telegramBotToken = "https://malicious.example/key";
        assertThrows(IllegalArgumentException.class,
                () -> new TelegramClient().sendReport(config, "日报"));
        config.telegramBotToken = TOKEN;
        config.telegramThreadId = "-10";
        assertThrows(IllegalArgumentException.class,
                () -> new TelegramClient().sendReport(config, "日报"));
    }
}
