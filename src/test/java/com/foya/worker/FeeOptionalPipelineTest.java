package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Optional fee must not discard successful tasks or prevent a counts-only daily report from sending. */
class FeeOptionalPipelineTest {
    @TempDir Path directory;
    private static final String BOT_TOKEN = "123456:FEE_OPTIONAL_MOCK_TOKEN";
    private record Result(JsonNode report, JsonNode cache, String deliveredText, int p0Requests, LocalDate day) {}

    @Test
    void mixedKnownMissingNullAndInvalidFeesPreserveCountsAndSendUnavailableAmountsAsNull() throws Exception {
        Result result = exercise(true, false);
        JsonNode report = result.report();
        assertEquals(48, result.cache().path("totalCount").asLong());
        assertEquals(24, result.cache().path("missingFeeCount").asLong());
        assertEquals(0, new BigDecimal("60.00").compareTo(result.cache().path("totalFee").decimalValue()),
                "the cache stores only the known subtotal, never a fabricated complete total");
        assertEquals(24, result.p0Requests());
        assertEquals(48, report.path("latestCount").asLong());
        assertEquals(48, report.path("totalCount").asLong());
        assertEquals(48, report.path("days").get(0).path("count").asLong());
        assertEquals(24, report.path("missingFeeCount").asLong());
        assertFalse(report.path("feeComplete").asBoolean(true));
        assertFalse(report.path("latestFeeComplete").asBoolean(true));
        for (JsonNode fee : List.of(report.path("totalFee"), report.path("latestFee"), report.path("days").get(0).path("fee"))) {
            assertTrue(fee.isNull(), "incomplete amounts must be explicit JSON null, not zero or a known subtotal");
        }
        assertEquals("count", report.path("channelsBy").asText());
        String text = report.path("text").asText();
        assertTrue(text.contains("未统计"));
        assertTrue(text.contains("｜48｜"));
        assertEquals(text, result.deliveredText());
        assertTrue(result.deliveredText().contains("未统计"));
    }

    @Test
    void explicitZeroFeesAreCompleteNumericZeroAndRetainAmountRanking() throws Exception {
        Result result = exercise(false, true);
        JsonNode report = result.report();
        assertEquals(48, result.cache().path("totalCount").asLong());
        assertEquals(0, result.cache().path("missingFeeCount").asLong());
        assertEquals(0, report.path("missingFeeCount").asLong());
        assertTrue(report.path("feeComplete").asBoolean());
        assertTrue(report.path("latestFeeComplete").asBoolean());
        for (JsonNode fee : List.of(report.path("totalFee"), report.path("latestFee"), report.path("days").get(0).path("fee"))) {
            assertTrue(fee.isNumber(), "a known zero amount must remain numeric");
            assertEquals(0, BigDecimal.ZERO.compareTo(fee.decimalValue()));
        }
        assertEquals("fee", report.path("channelsBy").asText());
        String text = report.path("text").asText();
        assertTrue(text.contains("按金额降序"));
        assertTrue(text.contains(result.day().format(DateTimeFormatter.ofPattern("MM/dd")) + "｜48｜0"));
        assertFalse(text.contains("未提供"));
        assertEquals(text, result.deliveredText());
    }

    @Test
    void providedFeesRemainHiddenUntilAmountsAreExplicitlyEnabled() throws Exception {
        Result result = exercise(false, false);
        JsonNode report = result.report();
        assertEquals(48, report.path("latestCount").asLong());
        assertEquals(0, result.cache().path("missingFeeCount").asLong());
        assertEquals(0, new BigDecimal("90.00").compareTo(result.cache().path("totalFee").decimalValue()),
                "the fixture provides valid positive fees, while reporting amounts remains disabled");
        assertFalse(report.path("amountsEnabled").asBoolean());
        for (JsonNode fee : List.of(report.path("totalFee"), report.path("latestFee"), report.path("days").get(0).path("fee"))) {
            assertTrue(fee.isNull(), "provided fees must remain hidden when amounts are disabled");
        }
        for (JsonNode row : report.path("channels")) assertTrue(row.path("fee").isNull());
        for (JsonNode row : report.path("workflows")) assertTrue(row.path("fee").isNull());
        assertEquals("count", report.path("channelsBy").asText());
        assertTrue(report.path("text").asText().contains("未统计"));
        assertEquals(report.path("text").asText(), result.deliveredText());
    }

    private Result exercise(boolean missingFees, boolean enableAmounts) throws Exception {
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        AtomicInteger p0Requests = new AtomicInteger();
        ConcurrentLinkedQueue<JsonNode> messages = new ConcurrentLinkedQueue<>();
        var fixtureThreads = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.setExecutor(fixtureThreads);
        fixture.createContext("/api/tasks/list", exchange -> {
            p0Requests.incrementAndGet();
            LocalDateTime hour = LocalDateTime.parse(query(exchange).get("start_time"));
            Map<String, Object> known = task(hour + "-known", "known_channel", "workflow_known", hour.plusMinutes(10));
            known.put("fee", missingFees || !enableAmounts ? "2.50" : 0);
            Map<String, Object> optional = task(hour + "-optional", "optional_channel", "workflow_optional", hour.plusMinutes(20));
            if (!missingFees) optional.put("fee", enableAmounts ? 0 : "1.25");
            else if (hour.getHour() % 3 == 1) optional.put("fee", null);
            else if (hour.getHour() % 3 == 2) optional.put("fee", "unavailable");
            // Each day has examples of absent, explicit null, and invalid fee while all task identity/time fields are valid.
            reply(exchange, Map.of("code", 0, "data", Map.of("list", List.of(known, optional), "total", 2, "has_more", false)));
        });
        fixture.createContext("/bot" + BOT_TOKEN + "/sendMessage", exchange -> {
            messages.add(Json.MAPPER.readTree(exchange.getRequestBody()));
            reply(exchange, Map.of("ok", true, "result", Map.of("message_id", messages.size())));
        });
        fixture.start();
        URI fixtureBase = URI.create("http://127.0.0.1:" + fixture.getAddress().getPort());
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
             WorkerService worker = TestWorkers.create(directory, new TelegramClient(fixtureBase));
             WebServer web = new WebServer(worker, "127.0.0.1", 0)) {
            web.start();
            String base = "http://127.0.0.1:" + web.port();
            Map<String, Object> config = new HashMap<>(Map.of("p0Endpoint", fixtureBase + "/api/tasks/list", "p0TimeFormat", "datetime",
                    "timezone", "Asia/Shanghai", "p0MaxAttempts", 1, "reportDays", 1, "llmEnabled", false,
                    "telegramBots", List.of(Map.of("id", "fee-bot", "name", "金额可选测试", "token", BOT_TOKEN,
                            "chatId", "-100123456789", "autoDiscover", false)),
                    "channelNames", Map.of("known_channel", "已知金额渠道", "optional_channel", "金额可选渠道"),
                    "workflowNames", Map.of("workflow_known", "已知工作流", "workflow_optional", "可选工作流")));
            // The counts-only branches deliberately omit the flag to exercise the default.
            if (enableAmounts) {
                config.put("amountsEnabled", true);
                config.put("taskTypePrices", Map.of("workflow_known", BigDecimal.ZERO, "workflow_optional", BigDecimal.ZERO));
            }
            assertEquals(200, request(http, base, "PUT", "/api/config", config).statusCode());
            assertEquals(202, request(http, base, "POST", "/api/pull", Map.of("endDate", day.toString())).statusCode());
            awaitJob(worker);
            HttpResponse<String> response = request(http, base, "GET", "/api/report?endDate=" + day, null);
            assertEquals(200, response.statusCode(), response.body());
            JsonNode report = Json.MAPPER.readTree(response.body());
            JsonNode cache = Json.MAPPER.readTree(Files.readAllBytes(directory.resolve("cache").resolve(day + ".json")));
            assertEquals(202, request(http, base, "POST", "/api/send", Map.of("endDate", day.toString(), "botId", "fee-bot")).statusCode());
            awaitJob(worker);
            assertEquals(1, messages.size());
            assertFalse(messages.element().has("parse_mode"));
            assertEquals("-100123456789", messages.element().path("chat_id").asText());
            return new Result(report, cache, messages.element().path("text").asText(), p0Requests.get(), day);
        } finally {
            fixture.stop(0);
            fixtureThreads.shutdownNow();
        }
    }

    private static Map<String, Object> task(String id, String channel, String workflow, LocalDateTime createdAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id); row.put("hash_key", channel); row.put("task_type", workflow);
        row.put("created_at", createdAt.toString()); row.put("status", 2);
        return row;
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new HashMap<>();
        for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            result.put(pair[0], pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    private static void reply(HttpExchange exchange, Object body) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static HttpResponse<String> request(HttpClient client, String base, String method, String path, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(3))
                .header("X-Requested-With", "foya-admin");
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method,
                HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void awaitJob(WorkerService worker) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (worker.jobs.isRunning() && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(worker.jobs.isRunning(), "bounded fixture job did not complete");
        JsonNode job = Json.MAPPER.valueToTree(worker.jobs.status()).path("job");
        assertEquals("completed", job.path("state").asText(), job.toPrettyString());
    }
}
