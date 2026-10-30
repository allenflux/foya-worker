package com.foya.worker;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Match p0's inclusive comparison of ISO timestamp strings, rather than echoing request timestamps. */
class P0IsoDailyStatisticsTest {
    @TempDir Path directory;

    @Test
    void allTwentyFourHoursRetainDistinctUuidRecordsAndExcludeAdjacentDays() throws Exception {
        LocalDate date = LocalDate.of(2024, 1, 2);
        DateTimeFormatter isoMicros = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
        List<Map<String, Object>> source = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            var start = date.atStartOfDay().plusHours(hour);
            source.add(row("hour-" + hour + "-start", isoMicros.format(start), 2));
            source.add(row("hour-" + hour + "-middle", isoMicros.format(start.plusMinutes(15)), 2));
            source.add(row("hour-" + hour + "-last-micro", isoMicros.format(start.plusHours(1).minusNanos(1000)), 2));
            source.add(row("hour-" + hour + "-failed", isoMicros.format(start.plusMinutes(30)), -1));
        }
        source.add(row("previous-day", isoMicros.format(date.atStartOfDay().minusNanos(1000)), 2));
        source.add(row("next-day", isoMicros.format(date.plusDays(1).atStartOfDay()), 2));
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean contract = new AtomicBoolean(true);
        var requestedHours = ConcurrentHashMap.<String>newKeySet();
        var threads = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(threads);
        upstream.createContext("/api/tasks/list", exchange -> {
            requests.incrementAndGet();
            Map<String, String> query = new HashMap<>();
            for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            String start = query.get("start_time"), end = query.get("end_time");
            requestedHours.add(start);
            if (!"2".equals(query.get("status")) || !"true".equals(exchange.getRequestHeaders().getFirst("canary"))
                    || !start.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:00:00\\.000000")
                    || !end.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:59:59\\.999999")) contract.set(false);
            // Deliberately use the source's lexicographic comparisons. A space-formatted request
            // returns no same-day hours, while its final cross-midnight window returns the whole day.
            List<Map<String, Object>> matches = source.stream().filter(row -> (int) row.get("status") == 2)
                    .filter(row -> row.get("created_at").toString().compareTo(start) >= 0
                            && row.get("created_at").toString().compareTo(end) <= 0).toList();
            int page = Integer.parseInt(query.get("page")), size = Integer.parseInt(query.get("page_size"));
            int offset = Math.min(matches.size(), (page - 1) * size);
            byte[] body = Json.MAPPER.writeValueAsBytes(Map.of("page", page, "page_size", size,
                    "total_count", matches.size(), "tasks", matches.subList(offset, Math.min(matches.size(), offset + size))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        upstream.start();
        try {
            AppConfig config = new AppConfig();
            config.p0Endpoint = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/api/tasks/list";
            config.p0MaxAttempts = 1;
            DailyStats result = new StatsService(new LocalTestDailyCacheStore(directory)).pullDay(config, date, false, null);
            assertEquals(72, result.totalCount, "must count the whole day, not only its final hour");
            assertEquals(72, result.buckets.getFirst().count(), "business task_id and id repeats are distinct UUID records");
            assertEquals(24, requests.get());
            assertEquals(24, requestedHours.size());
            assertTrue(contract.get(), "status=2, canary, ISO microseconds and inclusive upper bounds must reach p0");
        } finally {
            upstream.stop(0);
            threads.shutdownNow();
        }
    }

    private static Map<String, Object> row(String uuid, String created, int status) {
        return Map.of("uuid", uuid, "id", "repeated-id", "task_id", "same-business-task",
                "task_type", "image", "hash_key", "channel", "created_at", created, "status", status);
    }
}
