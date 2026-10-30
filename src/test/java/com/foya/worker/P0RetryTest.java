package com.foya.worker;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;

class P0RetryTest {
    @TempDir Path dir;
    HttpServer server;
    final AtomicInteger requests = new AtomicInteger();
    final List<Long> delays = Collections.synchronizedList(new ArrayList<>());
    AppConfig config;
    record Reply(int status, String body, String retryAfter) {}
    static Reply success() { return new Reply(200, "{\"data\":[]}", null); }

    void serve(IntFunction<Reply> response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tasks/list", exchange -> {
            Reply reply = response.apply(requests.incrementAndGet());
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            if (reply.retryAfter() != null) exchange.getResponseHeaders().set("Retry-After", reply.retryAfter());
            exchange.sendResponseHeaders(reply.status(), body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        config = new AppConfig();
        config.p0Endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/tasks/list";
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void retriesTwo503ResponsesThenSucceedsUsingExponentialBackoff() throws Exception {
        serve(attempt -> attempt <= 2 ? new Reply(503, "sensitive upstream failure", null) : success());
        assertEquals(true, new P0Client(delays::add).testConnection(config).get("ok"));
        assertEquals(3, requests.get());
        assertEquals(List.of(1000L, 2000L), delays);
    }

    @Test void retries429AndRespectsZeroRetryAfterWithoutRemovingBackoff() throws Exception {
        serve(attempt -> attempt == 1 ? new Reply(429, "rate limited", "0") : success());
        config.p0MaxAttempts = 2;
        assertEquals(true, new P0Client(delays::add).testConnection(config).get("ok"));
        assertEquals(2, requests.get());
        assertEquals(List.of(1000L), delays);
    }

    @Test void retries408() throws Exception {
        serve(attempt -> attempt == 1 ? new Reply(408, "timeout", null) : success());
        new P0Client(delays::add).testConnection(config);
        assertEquals(2, requests.get());
        assertEquals(List.of(1000L), delays);
    }

    @Test void authenticationAndSchemaErrorsAreNotRetriedOrEchoed() throws Exception {
        AtomicInteger status = new AtomicInteger(401);
        serve(attempt -> new Reply(status.get(), "sensitive api key or response", null));
        P0Client client = new P0Client(delays::add);
        Exception unauthorized = assertThrows(Exception.class, () -> client.testConnection(config));
        assertTrue(unauthorized.getMessage().contains("HTTP 401"));
        assertEquals(1, requests.get());
        status.set(403);
        assertThrows(Exception.class, () -> client.testConnection(config));
        assertEquals(2, requests.get());
        status.set(200);
        Exception invalidJson = assertThrows(Exception.class, () -> client.testConnection(config));
        assertEquals(3, requests.get());
        assertFalse(invalidJson.getMessage().contains("sensitive"));
        assertTrue(delays.isEmpty());
    }

    @Test void final503FailureNeverWritesPartialDailyCache() throws Exception {
        serve(attempt -> new Reply(503, "sensitive failed upstream", null));
        config.p0MaxAttempts = 2;
        Exception failure = assertThrows(Exception.class, () -> new StatsService(dir, new P0Client(delays::add))
                .pullDay(config, LocalDate.of(2024, 1, 2), false, null));
        assertTrue(failure.getMessage().contains("已尝试 2 次"));
        assertFalse(failure.getMessage().contains("sensitive"));
        assertFalse(Files.exists(dir.resolve("cache/2024-01-02.json")));
        assertFalse(delays.isEmpty());
        assertTrue(delays.stream().allMatch(delay -> delay == 1000));
    }

    @Test void attemptLimitIncludesInitialRequest() throws Exception {
        serve(attempt -> new Reply(503, "unavailable", null));
        config.p0MaxAttempts = 1;
        Exception failure = assertThrows(Exception.class, () -> new P0Client(delays::add).testConnection(config));
        assertTrue(failure.getMessage().contains("已尝试 1 次"));
        assertEquals(1, requests.get());
        assertTrue(delays.isEmpty());
    }

    @Test void supportsFiftyAttemptsWithCappedBackoffAndExplicitExhaustion() throws Exception {
        serve(attempt -> new Reply(503, "sensitive unavailable", null));
        config.p0MaxAttempts = 50;
        Exception failure = assertThrows(Exception.class, () -> new P0Client(delays::add).testConnection(config));
        assertEquals(50, requests.get());
        assertEquals(49, delays.size());
        assertEquals(List.of(1000L, 2000L, 4000L, 8000L, 16_000L, 30_000L), delays.subList(0, 6));
        assertTrue(delays.subList(5, delays.size()).stream().allMatch(delay -> delay == 30_000L));
        assertTrue(failure.getMessage().contains("已尝试 50 次"));
        assertTrue(failure.getMessage().contains("尝试次数已耗尽"));
        assertFalse(failure.getMessage().contains("sensitive"));
    }

    @Test void cancellationDuringBackoffPreventsFurtherQueries() throws Exception {
        serve(attempt -> new Reply(503, "unavailable", null));
        P0Client client = new P0Client(delay -> { throw new InterruptedException("cancelled"); });
        try {
            assertThrows(InterruptedException.class, () -> client.testConnection(config));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals(1, requests.get());
    }

    @Test void networkFailuresUseConfiguredNumberOfAttempts() throws Exception {
        serve(attempt -> success());
        server.stop(0);
        config.p0MaxAttempts = 3;
        Exception failure = assertThrows(Exception.class, () -> new P0Client(delays::add).testConnection(config));
        assertTrue(failure.getMessage().contains("已尝试 3 次"));
        assertEquals(List.of(1000L, 2000L), delays);
    }

    @Test void retryAfterSupportsSecondsAndHttpDateWithoutPrematureCapping() throws Exception {
        Instant now = Instant.parse("2026-10-30T00:00:00Z");
        assertEquals(30_000L, P0Client.retryDelayMillis(0, "30", now));
        assertEquals(300_000L, P0Client.retryDelayMillis(0, "300", now));
        String future = now.plusSeconds(90).atZone(ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME);
        assertEquals(90_000L, P0Client.retryDelayMillis(0, future, now));
        assertEquals(30_000L, P0Client.retryDelayMillis(9, null, now));
        assertEquals(4000L, P0Client.retryDelayMillis(2, "invalid", now));
        assertThrows(Exception.class, () -> P0Client.retryDelayMillis(0, "301", now));
        assertThrows(Exception.class, () -> P0Client.retryDelayMillis(0,
                now.plusSeconds(3600).atZone(ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME), now));
    }

    @Test void excessivelyLongRetryAfterStopsWithoutSendingEarlyRetry() throws Exception {
        serve(attempt -> new Reply(429, "limited", "3600"));
        Exception failure = assertThrows(Exception.class, () -> new P0Client(delays::add).testConnection(config));
        assertTrue(failure.getMessage().contains("未提前重试"));
        assertEquals(1, requests.get());
        assertTrue(delays.isEmpty());
    }
}
