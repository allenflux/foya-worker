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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowPricePipelineTest {
    @TempDir Path directory;
    private static final String BOT_TOKEN = "123456:WORKFLOW_PRICE_TEST_BOT";

    @Test
    void pricesRevalueSameRawCacheWithoutPullingAndIncompletePricesRemainSendable() throws Exception {
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        AtomicInteger p0Calls = new AtomicInteger();
        ConcurrentLinkedQueue<JsonNode> sent = new ConcurrentLinkedQueue<>();
        var fixtureThreads = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.setExecutor(fixtureThreads);
        fixture.createContext("/api/tasks/list", exchange -> {
            p0Calls.incrementAndGet();
            LocalDateTime hour = LocalDateTime.parse(query(exchange).get("start_time"));
            // A huge upstream fee and a missing fee must both be ignored when applying configured unit prices.
            Map<String, Object> a = new HashMap<>(Map.of("id", hour + "-A", "hash_key", "channelA", "task_type", "typeA",
                    "created_at", hour.plusMinutes(10).toString(), "status", 2, "fee", "999"));
            Map<String, Object> b = new HashMap<>(Map.of("id", hour + "-B", "hash_key", "channelB", "task_type", "typeB",
                    "created_at", hour.plusMinutes(20).toString(), "status", 2));
            reply(exchange, Map.of("code", 0, "data", Map.of("list", List.of(a, b), "total", 2, "has_more", false)));
        });
        fixture.createContext("/bot" + BOT_TOKEN + "/sendMessage", exchange -> {
            sent.add(Json.MAPPER.readTree(exchange.getRequestBody()));
            reply(exchange, Map.of("ok", true, "result", Map.of("message_id", sent.size())));
        });
        fixture.start();
        URI fixtureBase = URI.create("http://127.0.0.1:" + fixture.getAddress().getPort());
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
             WorkerService worker = TestWorkers.create(directory, new TelegramClient(fixtureBase));
             WebServer web = new WebServer(worker, "127.0.0.1", 0)) {
            web.start();
            String base = "http://127.0.0.1:" + web.port();
            assertEquals(200, request(http, base, "PUT", "/api/config", Map.of(
                    "p0Endpoint", fixtureBase + "/api/tasks/list", "timezone", "Asia/Shanghai", "reportDays", 1,
                    "p0MaxAttempts", 1, "llmEnabled", false,
                    "channelNames", Map.of("channelA", "渠道甲", "channelB", "渠道乙"),
                    "workflowNames", Map.of("typeA", "工作流甲", "typeB", "工作流乙"),
                    "telegramBots", List.of(Map.of("id", "price-bot", "token", BOT_TOKEN,
                            "chatId", "-100123456789", "autoDiscover", false)))).statusCode());
            JsonNode defaults = json(request(http, base, "GET", "/api/mappings", null), 200);
            assertTrue(defaults.path("taskTypePrices").isObject());
            assertTrue(defaults.path("taskTypePrices").isEmpty());
            assertFalse(defaults.path("amountsEnabled").asBoolean());
            assertEquals(202, request(http, base, "POST", "/api/pull", Map.of("endDate", day.toString())).statusCode());
            awaitJob(worker);
            assertEquals(24, p0Calls.get());
            Path cacheFile = directory.resolve("cache").resolve(day + ".json");
            byte[] rawCache = Files.readAllBytes(cacheFile);
            JsonNode cached = Json.MAPPER.readTree(rawCache);
            assertEquals(48, cached.path("totalCount").asLong());
            assertEquals(24, cached.path("missingFeeCount").asLong());
            assertEquals(0, new BigDecimal("23976").compareTo(cached.path("totalFee").decimalValue()));
            String fingerprint = cached.path("sourceFingerprint").asText();
            assertTrue(report(http, base, day).path("latestFee").isNull());

            JsonNode prices = json(request(http, base, "PUT", "/api/mappings", Map.of("amountsEnabled", true,
                    "taskTypePrices", Map.of("typeA", new BigDecimal("2.50"), "typeB", BigDecimal.ZERO))), 200);
            assertTrue(prices.path("amountsEnabled").asBoolean());
            assertEquals(0, new BigDecimal("2.50").compareTo(new BigDecimal(prices.path("taskTypePrices").path("typeA").asText())));
            JsonNode first = report(http, base, day);
            assertCompleteAmount(first, "60.00");
            assertEquals("渠道甲", first.path("channels").get(0).path("name").asText());
            assertEquals(0, new BigDecimal("60.00").compareTo(first.path("channels").get(0).path("fee").decimalValue()));
            assertEquals(0, BigDecimal.ZERO.compareTo(first.path("channels").get(1).path("fee").decimalValue()), "zero is a valid configured price");
            assertEquals(0, first.path("missingFeeCount").asLong(), "raw missing fees are replaced by complete price coverage");
            assertTrue(first.path("text").asText().contains("单价映射"));

            json(request(http, base, "PUT", "/api/mappings", Map.of("taskTypePrices", Map.of("typeA", 1, "typeB", 4))), 200);
            JsonNode updated = report(http, base, day);
            assertCompleteAmount(updated, "120");
            assertEquals("渠道乙", updated.path("channels").get(0).path("name").asText());
            assertEquals(0, new BigDecimal("96").compareTo(updated.path("channels").get(0).path("fee").decimalValue()));
            assertEquals(0, new BigDecimal("80.0").compareTo(updated.path("channels").get(0).path("share").decimalValue()));
            AppConfig repriced = worker.configs.snapshot();
            assertEquals(fingerprint, StatsService.fingerprint(repriced, P0Client.resolveApiKey(repriced)), "prices must not invalidate the source cache");
            assertEquals(202, request(http, base, "POST", "/api/pull", Map.of("endDate", day.toString())).statusCode());
            awaitJob(worker);
            assertEquals(24, p0Calls.get(), "the complete raw day must be reused after a price edit");
            assertArrayEquals(rawCache, Files.readAllBytes(cacheFile), "pricing must not mutate the persisted source cache");

            json(request(http, base, "PUT", "/api/mappings", Map.of("taskTypePrices", Map.of("typeA", 1))), 200);
            JsonNode incomplete = report(http, base, day);
            assertEquals(48, incomplete.path("latestCount").asLong());
            assertEquals(24, incomplete.path("missingFeeCount").asLong());
            assertTrue(incomplete.path("latestFee").isNull());
            assertTrue(incomplete.path("totalFee").isNull());
            assertTrue(incomplete.path("days").get(0).path("fee").isNull());
            assertEquals("count", incomplete.path("channelsBy").asText());
            assertFalse(incomplete.path("feeComplete").asBoolean());
            assertTrue(incomplete.path("text").asText().contains("未配置单价"));
            assertEquals(202, request(http, base, "POST", "/api/send", Map.of("endDate", day.toString(), "botId", "price-bot")).statusCode());
            awaitJob(worker);
            assertEquals(1, sent.size());
            assertEquals(incomplete.path("text").asText(), sent.element().path("text").asText());
            assertFalse(sent.element().has("parse_mode"));

            json(request(http, base, "PUT", "/api/mappings", Map.of("amountsEnabled", false)), 200);
            JsonNode disabled = report(http, base, day);
            assertFalse(disabled.path("amountsEnabled").asBoolean());
            assertEquals("count", disabled.path("channelsBy").asText());
            assertTrue(disabled.path("latestFee").isNull());
            assertTrue(disabled.path("totalFee").isNull());
            assertTrue(disabled.path("days").get(0).path("fee").isNull());
            for (JsonNode row : disabled.path("channels")) assertTrue(row.path("fee").isNull());
            for (JsonNode row : disabled.path("workflows")) assertTrue(row.path("fee").isNull());
            assertArrayEquals(rawCache, Files.readAllBytes(cacheFile));
            assertEquals(24, p0Calls.get());
            assertEquals("渠道甲", worker.configs.snapshot().channelNames.get("channelA"), "partial price edits must preserve name mappings");
        } finally {
            fixture.stop(0);
            fixtureThreads.shutdownNow();
        }
    }

    @Test
    void invalidPriceInputsAreRejectedWithoutReplacingTheSavedMapping() throws Exception {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
             WorkerService worker = TestWorkers.create(directory);
             WebServer web = new WebServer(worker, "127.0.0.1", 0)) {
            web.start();
            String base = "http://127.0.0.1:" + web.port();
            JsonNode baseline = json(request(http, base, "PUT", "/api/mappings", Map.of("amountsEnabled", true,
                    "taskTypePrices", Map.of("typeA", new BigDecimal("2.50"), "typeB", BigDecimal.ZERO))), 200);
            byte[] saved = Files.readAllBytes(directory.resolve("config.json"));
            for (String invalid : List.of("{\"taskTypePrices\":{\"typeA\":-1}}", "{\"taskTypePrices\":{\"typeA\":\"NaN\"}}",
                    "{\"taskTypePrices\":{\"typeA\":null}}", "{\"taskTypePrices\":{\"typeA\":\"invalid\"}}",
                    "{\"taskTypePrices\":{\"typeA\":{}}}", "{\"taskTypePrices\":null}",
                    "{\"taskTypePrices\":{\"typeA\":true}}", "{\"taskTypePrices\":{\"typeA\":\"\"}}")) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/mappings")).timeout(Duration.ofSeconds(3))
                        .header("Content-Type", "application/json").header("X-Requested-With", "foya-admin")
                        .PUT(HttpRequest.BodyPublishers.ofString(invalid, StandardCharsets.UTF_8)).build();
                HttpResponse<String> failure = http.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(400, failure.statusCode(), invalid);
                assertTrue(Json.MAPPER.readTree(failure.body()).path("error").isTextual());
                assertArrayEquals(saved, Files.readAllBytes(directory.resolve("config.json")), "rejected price input must not alter saved configuration");
                assertEquals(baseline, json(request(http, base, "GET", "/api/mappings", null), 200));
            }
        }
    }

    private static void assertCompleteAmount(JsonNode report, String amount) {
        assertEquals(48, report.path("totalCount").asLong());
        assertEquals("fee", report.path("channelsBy").asText());
        assertTrue(report.path("feeComplete").asBoolean());
        assertTrue(report.path("latestFeeComplete").asBoolean());
        for (JsonNode fee : List.of(report.path("totalFee"), report.path("latestFee"), report.path("days").get(0).path("fee")))
            assertEquals(0, new BigDecimal(amount).compareTo(fee.decimalValue()));
    }
    private static JsonNode report(HttpClient http, String base, LocalDate day) throws Exception {
        return json(request(http, base, "GET", "/api/report?endDate=" + day, null), 200);
    }
    private static JsonNode json(HttpResponse<String> response, int expected) throws IOException {
        assertEquals(expected, response.statusCode(), response.body());
        return Json.MAPPER.readTree(response.body());
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
        assertFalse(worker.jobs.isRunning(), "bounded pipeline job did not complete");
        JsonNode job = Json.MAPPER.valueToTree(worker.jobs.status()).path("job");
        assertEquals("completed", job.path("state").asText(), job.toPrettyString());
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
}
