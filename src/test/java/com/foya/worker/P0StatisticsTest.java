package com.foya.worker;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class P0StatisticsTest {
    @TempDir Path dir;
    HttpServer server;
    AtomicInteger requests;
    AtomicBoolean headersValid;
    AppConfig config;

    void serve(Function<Map<String, String>, String> respond) throws Exception {
        requests = new AtomicInteger();
        headersValid = new AtomicBoolean(true);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tasks/list", exchange -> {
            requests.incrementAndGet();
            if (!"true".equals(exchange.getRequestHeaders().getFirst("canary"))) headersValid.set(false);
            Map<String, String> query = new HashMap<>();
            for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] pieces = part.split("=", 2);
                query.put(pieces[0], URLDecoder.decode(pieces[1], StandardCharsets.UTF_8));
            }
            if (!"2".equals(query.get("status"))) headersValid.set(false);
            byte[] bytes = respond.apply(query).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        config = new AppConfig();
        config.p0Endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/tasks/list";
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void aggregatesExactFeesWithHalfOpenBoundariesAndCachesCompleteDay() throws Exception {
        serve(q -> {
            String start = q.get("start_time");
            String end = q.get("end_time");
            return "{\"code\":0,\"data\":{\"total\":3,\"list\":["
                    + row(start + "a", "alpha", "1", "0.10", start) + ","
                    + row(start + "b", "alpha", "2", "0.20", start) + ","
                    + row(end + "a", "alpha", "1", "0.10", end) + "]}}";
        });
        StatsService stats = new StatsService(new LocalTestDailyCacheStore(dir));
        List<StatsService.Progress> progress = Collections.synchronizedList(new ArrayList<>());
        DailyStats result = stats.pullDay(config, LocalDate.of(2024, 1, 2), false, progress::add);
        assertEquals(48, result.totalCount);
        assertEquals(0, new BigDecimal("7.20").compareTo(result.totalFee));
        assertEquals(2, result.buckets.size());
        assertEquals(24, requests.get());
        assertTrue(headersValid.get());
        assertTrue(progress.stream().anyMatch(p -> p.completedHours() == 24));
        assertEquals(48, stats.pullDay(config, LocalDate.of(2024, 1, 2), false, null).totalCount);
        assertEquals(24, requests.get(), "second pull must read cache");
        assertEquals(1, stats.availableCachedDays(config, LocalDate.of(2024, 1, 2), 10).size());
        assertThrows(Exception.class, () -> stats.cachedDays(config, LocalDate.of(2024, 1, 2), 10));
        assertEquals(1, stats.cacheIndex(config).size());
        assertFalse(Files.readString(dir.resolve("cache/2024-01-02.json")).contains("created_at"));
        config.timezone = "UTC";
        assertTrue(stats.availableCachedDays(config, LocalDate.of(2024, 1, 2), 10).isEmpty(), "different timezone invalidates cached source");
    }

    @Test void paginatesByTotalEvenWhenServerCapsBelowRequestedPageSize() throws Exception {
        serve(q -> {
            String start = q.get("start_time");
            String page = q.get("page");
            return "{\"data\":{\"total\":2,\"items\":[" + row(start + page, "h", "w", "1.001", start) + "]}}";
        });
        DailyStats result = new StatsService(new LocalTestDailyCacheStore(dir)).pullDay(config, LocalDate.of(2024, 1, 2), false, null);
        assertEquals(48, result.totalCount);
        assertEquals(48, requests.get());
        assertEquals(new BigDecimal("48.048"), result.totalFee);
    }

    @Test void rejectsUnknownAndBusinessErrorResponsesWithoutWritingCache() throws Exception {
        serve(q -> "{\"code\":401,\"data\":{\"list\":[]}}");
        assertThrows(Exception.class, () -> new StatsService(new LocalTestDailyCacheStore(dir)).pullDay(config, LocalDate.of(2024, 1, 2), false, null));
        assertFalse(Files.exists(dir.resolve("cache/2024-01-02.json")));
        assertThrows(Exception.class, () -> P0Client.decode(Json.MAPPER.readTree("{\"data\":{}}"), ZoneId.of("UTC")));
        assertThrows(Exception.class, () -> P0Client.decode(Json.MAPPER.readTree("{\"data\":[{\"hash_key\":\"a\",\"task_type\":1}]}"), ZoneId.of("UTC")));
    }

    @Test void rejectsRepeatedPagesInsteadOfInflatingCounts() throws Exception {
        serve(q -> "{\"data\":{\"total\":2,\"list\":[" + row(q.get("start_time"), "h", "w", "1", q.get("start_time")) + "]}}");
        Exception error = assertThrows(Exception.class,
                () -> new StatsService(new LocalTestDailyCacheStore(dir)).pullDay(config, LocalDate.of(2024, 1, 2), false, null));
        assertTrue(error.getMessage().contains("重复页面"));
        assertFalse(Files.exists(dir.resolve("cache/2024-01-02.json")));
    }

    @Test void failedForcedRefreshPreservesPreviousCompleteCache() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        serve(q -> fail.get() ? "{\"success\":false}" : "{\"data\":{\"total\":0,\"list\":[]}}");
        StatsService stats = new StatsService(new LocalTestDailyCacheStore(dir));
        LocalDate date = LocalDate.of(2024, 1, 2);
        DailyStats good = stats.pullDay(config, date, false, null);
        byte[] original = Files.readAllBytes(dir.resolve("cache/2024-01-02.json"));
        fail.set(true);
        assertThrows(Exception.class, () -> stats.pullDay(config, date, true, null));
        assertArrayEquals(original, Files.readAllBytes(dir.resolve("cache/2024-01-02.json")));
        assertEquals(good.fetchedAt, stats.pullDay(config, date, false, null).fetchedAt);
    }

    @Test void unknownPageSizeWithoutTotalContinuesUntilEmpty() throws Exception {
        serve(q -> "1".equals(q.get("page")) ? "{\"data\":[" + row(q.get("start_time"), "h", "w", "1", q.get("start_time")) + "]}" : "{\"data\":[]}");
        DailyStats result = new StatsService(new LocalTestDailyCacheStore(dir)).pullDay(config, LocalDate.of(2024, 1, 2), false, null);
        assertEquals(24, result.totalCount);
        assertEquals(48, requests.get());
    }

    @Test void rejectsTotalHasMoreConflicts() throws Exception {
        serve(q -> "{\"data\":{\"total\":1,\"has_more\":true,\"list\":[" + row(q.get("start_time"), "h", "w", "1", q.get("start_time")) + "]}}");
        Exception error = assertThrows(Exception.class,
                () -> new StatsService(new LocalTestDailyCacheStore(dir)).pullDay(config, LocalDate.of(2024, 1, 2), false, null));
        assertTrue(error.getMessage().contains("冲突"));
        assertFalse(Files.exists(dir.resolve("cache/2024-01-02.json")));
        assertThrows(Exception.class, () -> P0Client.decode(Json.MAPPER.readTree("{\"total\":2,\"data\":{\"total\":1,\"list\":[]}}"), ZoneId.of("UTC")));
        assertThrows(Exception.class, () -> P0Client.decode(Json.MAPPER.readTree("{\"has_more\":false,\"data\":{\"has_more\":true,\"list\":[]}}"), ZoneId.of("UTC")));
    }

    @Test void remembersTotalWhenLaterPageOmitsIt() throws Exception {
        serve(q -> "{\"data\":{" + ("1".equals(q.get("page")) ? "\"total\":3,\"has_more\":true," : "\"has_more\":false,")
                + "\"list\":[" + row(q.get("start_time") + q.get("page"), "h", "w", "1", q.get("start_time")) + "]}}");
        Exception error = assertThrows(Exception.class,
                () -> new StatsService(new LocalTestDailyCacheStore(dir)).pullDay(config, LocalDate.of(2024, 1, 2), false, null));
        assertTrue(error.getMessage().contains("total 不一致"));
    }

    @Test void rejectsChangingTotalAndRepeatedPagesWithoutIds() throws Exception {
        AtomicBoolean repeated = new AtomicBoolean();
        serve(q -> "{\"data\":{\"total\":" + (repeated.get() ? "3" : ("1".equals(q.get("page")) ? "2" : "3"))
                + ",\"records\":[{\"hash_key\":\"h\",\"task_type\":\"w\",\"fee\":1,\"created_time\":\"" + q.get("start_time") + "\"}]}}");
        StatsService stats = new StatsService(new LocalTestDailyCacheStore(dir));
        Exception changed = assertThrows(Exception.class, () -> stats.pullDay(config, LocalDate.of(2024, 1, 2), false, null));
        assertTrue(changed.getMessage().contains("total 在拉取过程中变化"));
        repeated.set(true);
        Exception duplicate = assertThrows(Exception.class, () -> stats.pullDay(config, LocalDate.of(2024, 1, 2), false, null));
        assertTrue(duplicate.getMessage().contains("重复页面"));
    }

    @Test void handlesTaskAndCreationFieldAliasesWithoutGuessingFee() throws Exception {
        serve(q -> "{\"data\":{\"pagination\":{\"total\":2,\"hasMore\":false},\"rows\":["
                + "{\"hashKey\":\"a\",\"task\":{\"type\":17},\"fee\":\"0.01\",\"created_time\":\"" + q.get("start_time") + "\"},"
                + "{\"hash_key\":\"b\",\"task\":\"workflow-label\",\"fee\":\"0.02\",\"createdTime\":\"" + q.get("start_time") + "\"}]}}");
        DailyStats stats = new StatsService(new LocalTestDailyCacheStore(dir)).pullDay(config, LocalDate.of(2024, 1, 2), false, null);
        assertEquals(48, stats.totalCount);
        assertEquals(new BigDecimal("0.72"), stats.totalFee);
        assertEquals(Set.of("17", "workflow-label"), new HashSet<>(stats.buckets.stream().map(DailyStats.Bucket::taskType).toList()));
    }

    @Test void invalidJsonErrorNeverEchoesUpstreamContent() throws Exception {
        serve(q -> "sensitive-upstream-response-do-not-log");
        Exception error = assertThrows(Exception.class, () -> new P0Client().testConnection(config));
        assertTrue(error.getMessage().contains("无效 JSON"));
        assertFalse(error.getMessage().contains("sensitive-upstream"));
    }

    @Test void idOnlyResponseCanBeProbedButCannotProduceUnsafeDailyStatistics() throws Exception {
        serve(q -> "{\"data\":{\"total\":1,\"list\":[{\"id\":\"task-one\",\"hash_key\":\"h\",\"task_type\":\"w\",\"fee\":1}]}}");
        Map<String, Object> probe = new P0Client().testConnection(config);
        assertEquals(true, probe.get("ok"));
        assertTrue(probe.get("message").toString().contains("缺少创建时间"));
        Exception error = assertThrows(Exception.class,
                () -> new StatsService(new LocalTestDailyCacheStore(dir)).pullDay(config, LocalDate.of(2024, 1, 2), false, null));
        assertTrue(error.getMessage().contains("缺少创建时间"));
        assertFalse(Files.exists(dir.resolve("cache/2024-01-02.json")));
    }

    @Test void respectsDstAndCanReadExistingSecretFileWithoutCopyingSecret() throws Exception {
        serve(q -> "{\"data\":{\"total\":0,\"list\":[]}}");
        config.timezone = "America/New_York";
        Path secret = dir.resolve("existing.env");
        Files.writeString(secret, "export P0_API_KEY='existing-secret'\n");
        config.p0ApiKeyFile = secret.toString();
        DailyStats day = new StatsService(new LocalTestDailyCacheStore(dir)).pullDay(config, LocalDate.of(2024, 3, 10), false, null);
        assertEquals(23, day.requestCount);
        assertFalse(Files.readString(dir.resolve("cache/2024-03-10.json")).contains("existing-secret"));
        assertEquals("existing-secret", P0Client.resolveApiKey(config));
    }

    @Test void probesWithSingleRowLimitAndOmitsEmptyApiKey() throws Exception {
        AtomicReference<String> pageSize = new AtomicReference<>();
        serve(q -> { pageSize.set(q.get("page_size")); return "{\"data\":[]}"; });
        assertEquals(true, new P0Client().testConnection(config).get("ok"));
        assertEquals("1", pageSize.get());
        assertEquals(1, requests.get());
    }

    static String row(String id, String hash, String type, String fee, String created) {
        return "{\"id\":\"" + id + "\",\"status\":2,\"hash_key\":\"" + hash + "\",\"task_type\":\"" + type
                + "\",\"fee\":\"" + fee + "\",\"created_at\":\"" + created + "\"}";
    }
}
