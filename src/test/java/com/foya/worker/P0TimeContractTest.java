package com.foya.worker;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class P0TimeContractTest {
    @TempDir Path directory;
    private HttpServer server;
    private final List<Map<String, String>> queries = new CopyOnWriteArrayList<>();
    private final List<String> canaryHeaders = new CopyOnWriteArrayList<>();
    private AppConfig config;

    private void serve(Function<Map<String, String>, List<ObjectNode>> response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tasks/list", exchange -> {
            Map<String, String> query = new HashMap<>();
            for (String entry : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] pair = entry.split("=", 2);
                query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            queries.add(query);
            canaryHeaders.add(Objects.toString(exchange.getRequestHeaders().getFirst("canary"), ""));
            List<ObjectNode> rows = response.apply(query);
            byte[] body = Json.MAPPER.writeValueAsBytes(Map.of("tasks", rows, "total_count", rows.size()));
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        config = new AppConfig();
        config.reportDays = 1;
        config.p0Endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/tasks/list";
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void datetimeUsesIsoWallClockAndExactlySixFractionalDigits() {
        ZonedDateTime value = ZonedDateTime.of(2026, 10, 29, 5, 6, 7, 123_456_789, ZoneId.of("Asia/Shanghai"));
        assertEquals("2026-10-29T05:06:07.123456", P0Client.format(value, "datetime"));
        assertEquals("2026-10-29T00:00:00.000000", P0Client.format(value.toLocalDate().atStartOfDay(value.getZone()), "datetime"));
        assertEquals(Long.toString(value.toEpochSecond()), P0Client.format(value, "unix_seconds"));
        assertEquals(Long.toString(value.toInstant().toEpochMilli()), P0Client.format(value, "unix_millis"));
    }

    @Test void finalHourHasInclusiveMicrosecondEndWithoutEnteringNextDay() throws Exception {
        serve(query -> List.of());
        ZonedDateTime start = LocalDate.of(2026, 10, 29).atTime(23, 0).atZone(ZoneId.of(config.timezone));
        new P0Client().pullHour(config, "", start, start.plusHours(1), rows -> {}, () -> {});
        assertEquals(1, queries.size());
        Map<String, String> query = queries.getFirst();
        assertEquals("2026-10-29T23:00:00.000000", query.get("start_time"));
        assertEquals("2026-10-29T23:59:59.999999", query.get("end_time"));
        assertEquals("2", query.get("status"));
        assertEquals("1", query.get("page"));
        assertEquals("1000", query.get("page_size"));
        assertEquals(List.of("true"), canaryHeaders);
    }

    @Test void isoStringFilteredSourceCountsFullDayAndPreservesRepeatedBusinessIds() throws Exception {
        List<ObjectNode> source = List.of(
                row("previous", "2026-10-28T23:59:59.999999"),
                row("first", "2026-10-29T00:00:00.000000"),
                row("second", "2026-10-29T00:00:00.000001"),
                row("last", "2026-10-29T23:59:59.999999"),
                row("following", "2026-10-30T00:00:00.000000"));
        // Match the upstream's inclusive ISO string comparison. Space-formatted
        // boundaries would miss records instead of being treated as ISO dates.
        serve(query -> source.stream().filter(row -> row.path("created_at").asText().compareTo(query.get("start_time")) >= 0
                && row.path("created_at").asText().compareTo(query.get("end_time")) <= 0).toList());
        StatsService service = new StatsService(new LocalTestDailyCacheStore(directory));
        DailyStats day = service.pullDay(config, LocalDate.of(2026, 10, 29), false, null);
        assertEquals(3, day.totalCount);
        assertEquals(3, day.buckets.getFirst().count());
        assertEquals(24, queries.size());
        List<Map<String, String>> ordered = queries.stream().sorted(Comparator.comparing(q -> q.get("start_time"))).toList();
        for (int hour = 0; hour < 24; hour++) {
            String prefix = "2026-10-29T" + String.format(Locale.ROOT, "%02d", hour);
            assertEquals(prefix + ":00:00.000000", ordered.get(hour).get("start_time"));
            assertEquals(prefix + ":59:59.999999", ordered.get(hour).get("end_time"));
        }
    }

    @Test void uuidTakesPrecedenceOverNumericIdAndBusinessIdWithLegacyFallbacks() throws Exception {
        ObjectNode row = row("record-uuid", "2026-10-29T00:00:00.000000");
        assertEquals("record-uuid", decode(row).id());
        row.remove("uuid");
        assertEquals("shared-non-record-id", decode(row).id());
        row.remove("id");
        assertEquals("reused-business-id", decode(row).id());
        row.remove("task_id"); row.put("taskId", "camel-business-id");
        assertEquals("camel-business-id", decode(row).id());
    }

    @Test void unixTimeModesKeepTheirExistingEndpointContract() throws Exception {
        serve(query -> List.of());
        ZonedDateTime start = LocalDate.of(2026, 10, 29).atTime(23, 0).atZone(ZoneId.of(config.timezone));
        for (String mode : List.of("unix_seconds", "unix_millis")) {
            config.p0TimeFormat = mode;
            new P0Client().pullHour(config, "", start, start.plusHours(1), rows -> {}, () -> {});
            assertEquals(P0Client.format(start.plusHours(1), mode), queries.getLast().get("end_time"));
        }
    }

    private static P0Client.Task decode(ObjectNode row) throws Exception {
        return P0Client.decode(Json.MAPPER.valueToTree(Map.of("tasks", List.of(row))), ZoneId.of("Asia/Shanghai")).tasks().getFirst();
    }

    private static ObjectNode row(String record, String created) {
        ObjectNode row = Json.MAPPER.createObjectNode();
        row.put("uuid", record); row.put("id", "shared-non-record-id"); row.put("task_id", "reused-business-id");
        row.put("task_type", "workflow"); row.put("hash_key", "channel"); row.put("status", 2);
        row.put("created_at", created);
        return row;
    }
}
