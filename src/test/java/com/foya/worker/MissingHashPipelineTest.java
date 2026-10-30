package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class MissingHashPipelineTest {
    @TempDir Path directory;
    private static final String TOKEN = "123456:MISSING_HASH_TEST_TOKEN";
    private static final LocalDate DATE = LocalDate.of(2024, 1, 2);

    @Test void missingOrInvalidHashKeepsAllCountsCachesUnknownBucketPricesAndSendsThroughAnonymousHttp() throws Exception {
        AtomicInteger p0Requests = new AtomicInteger();
        List<JsonNode> telegramBodies = new CopyOnWriteArrayList<>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/api/tasks/list", exchange -> {
            p0Requests.incrementAndGet();
            Map<String, String> query = new HashMap<>();
            for (String item : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] pair = item.split("=", 2); query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            List<ObjectNode> rows = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                ObjectNode row = record(query.get("start_time") + i, query.get("start_time"));
                switch (i) {
                    case 0 -> { /* missing */ }
                    case 1 -> row.putNull("hash_key");
                    case 2 -> row.put("hash_key", " \t ");
                    case 3 -> row.put("hash_key", false);
                    case 4 -> row.set("hash_key", Json.MAPPER.createObjectNode());
                    case 5 -> row.set("hash_key", Json.MAPPER.createArrayNode());
                    case 6 -> row.put("hashKey", "known");
                    case 7 -> { row.set("hash_key", Json.MAPPER.createObjectNode()); row.put("hashKey", "known"); }
                    default -> row.put("hash_key", 0);
                }
                rows.add(row);
            }
            byte[] body = Json.MAPPER.writeValueAsBytes(Map.of("data", Map.of("tasks", rows, "total", 9)));
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        upstream.createContext("/bot" + TOKEN + "/sendMessage", exchange -> {
            telegramBodies.add(Json.MAPPER.readTree(exchange.getRequestBody()));
            byte[] body = Json.MAPPER.writeValueAsBytes(Map.of("ok", true, "result", Map.of("message_id", 99)));
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        upstream.start();
        URI base = URI.create("http://127.0.0.1:" + upstream.getAddress().getPort());
        try (WorkerService worker = TestWorkers.create(directory, new TelegramClient(base));
             WebServer web = new WebServer(worker, "127.0.0.1", 0);
             HttpClient http = HttpClient.newHttpClient()) {
            worker.configs.update(Json.MAPPER.valueToTree(Map.of("reportDays", 1, "llmEnabled", false, "amountsEnabled", true,
                    "p0Endpoint", base + "/api/tasks/list", "taskTypePrices", Map.of("17", new BigDecimal("2.50")),
                    "workflowNames", Map.of("17", "标准工作流"), "channelNames", Map.of("known", "已知渠道", "0", "零号渠道"),
                    "telegramBots", List.of(Map.of("id", "test-bot", "name", "测试机器人", "token", TOKEN,
                            "chatId", "-100123456789", "autoDiscover", false, "enabled", true)))));
            AppConfig config = worker.configs.snapshot();
            StatsService stats = new StatsService(new LocalTestDailyCacheStore(directory));
            DailyStats day = stats.pullDay(config, DATE, false, null);
            assertEquals(216, day.totalCount);
            assertEquals(24, p0Requests.get());
            assertEquals(Map.of("", 144L, "known", 48L, "0", 24L), day.buckets.stream()
                    .collect(Collectors.toMap(DailyStats.Bucket::hashKey, DailyStats.Bucket::count)));
            assertEquals(216, stats.cachedDays(config, DATE, 1).getFirst().totalCount);
            assertEquals(216, stats.pullDay(config, DATE, false, null).totalCount);
            assertEquals(24, p0Requests.get(), "Read the completed cache without querying the source again");

            Map<String, Object> report = worker.reportResponse(DATE);
            assertEquals(216L, report.get("totalCount"));
            assertEquals(0, new BigDecimal("540.00").compareTo((BigDecimal) report.get("totalFee")));
            assertEquals("fee", report.get("channelsBy"));
            String text = report.get("text").toString();
            assertTrue(text.contains("未分類｜144｜360｜66.7%"));
            assertTrue(text.contains("已知渠道｜48｜120｜22.2%"));
            assertTrue(text.contains("零号渠道｜24｜60｜11.1%"));
            assertTrue(text.contains("标准工作流｜216｜100.0%"));
            web.start();
            HttpRequest send = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + "/api/send"))
                    .timeout(Duration.ofSeconds(3)).header("Content-Type", "application/json").header("X-Requested-With", "foya-admin")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(Map.of("endDate", DATE.toString())))).build();
            assertEquals(202, http.send(send, HttpResponse.BodyHandlers.ofString()).statusCode());
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (worker.jobs.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(worker.jobs.isRunning());
            assertEquals("completed", ((Map<?, ?>) worker.jobs.status().get("job")).get("state"));
            assertEquals(1, telegramBodies.size());
            assertEquals(text, telegramBodies.getFirst().path("text").asText());
            assertEquals("-100123456789", telegramBodies.getFirst().path("chat_id").asText());
        } finally { upstream.stop(0); }
    }

    @Test void invalidPrimaryFallsBackToAliasWhileNumericZeroAndValidPrimaryRemainValid() throws Exception {
        List<JsonNode> invalidPrimaries = List.of(Json.MAPPER.nullNode(), Json.MAPPER.getNodeFactory().textNode(" \t "),
                Json.MAPPER.getNodeFactory().booleanNode(false), Json.MAPPER.createObjectNode(), Json.MAPPER.createArrayNode());
        for (JsonNode primary : invalidPrimaries) {
            ObjectNode row = record("one", "2024-01-02 00:00:00"); row.set("hash_key", primary); row.put("hashKey", "alias");
            assertEquals("alias", decode(row).hashKey());
            row.put("hashKey", 0);
            assertEquals("0", decode(row).hashKey());
        }
        ObjectNode row = record("two", "2024-01-02 00:00:00"); row.put("hash_key", 0); row.put("hashKey", "alias");
        assertEquals("0", decode(row).hashKey());
        row.put("hash_key", "primary");
        assertEquals("primary", decode(row).hashKey());
    }

    private static ObjectNode record(String id, String time) {
        ObjectNode row = Json.MAPPER.createObjectNode(); row.put("id", id); row.put("created_at", time);
        row.put("status", 2); row.put("task_type", 17); return row;
    }
    private static P0Client.Task decode(ObjectNode row) throws Exception {
        return P0Client.decode(Json.MAPPER.valueToTree(Map.of("data", List.of(row))), ZoneId.of("Asia/Shanghai")).tasks().getFirst();
    }
}
