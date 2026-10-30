package com.foya.worker;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class UpstreamDeadlineTest {
    private HttpServer server;
    private ExecutorService executor;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger requests = new AtomicInteger();

    private URI slowBodyServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] partial = "{\"data\":".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, 10_000);
            exchange.getResponseBody().write(partial);
            exchange.getResponseBody().flush();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test void p0BodyTimeoutIsBoundedAndRetriesConfiguredNumberOfTimes() throws Exception {
        URI base = slowBodyServer();
        AppConfig config = new AppConfig();
        config.p0Endpoint = base + "/api/tasks/list";
        config.p0MaxAttempts = 2;
        List<Long> delays = new ArrayList<>();
        P0Client client = new P0Client(delays::add, Duration.ofMillis(150));
        Exception failure = assertTimeout(Duration.ofSeconds(2),
                () -> assertThrows(Exception.class, () -> client.testConnection(config)));
        assertTrue(failure.getMessage().contains("已尝试 2 次"));
        assertEquals(2, requests.get());
        assertEquals(List.of(1000L), delays);
    }

    @Test void telegramBodyTimeoutIsUncertainAndNeverRetriesDelivery() throws Exception {
        URI base = slowBodyServer();
        TelegramClient client = new TelegramClient(base, Duration.ofMillis(150));
        AppConfig config = new AppConfig();
        config.telegramBotToken = "123456:DEADLINE_TEST_TOKEN";
        config.telegramChatId = "-100123456789";
        TelegramClient.DeliveryException failure = assertTimeout(Duration.ofSeconds(2),
                () -> assertThrows(TelegramClient.DeliveryException.class, () -> client.sendReport(config, "日统计")));
        assertTrue(failure.deliveryUncertain);
        assertEquals(0, failure.sentParts);
        assertEquals(1, requests.get());
        assertFalse(failure.getMessage().contains(config.telegramBotToken));
    }

    @AfterEach void close() {
        release.countDown();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }
}
