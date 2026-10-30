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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual HTTP/config/jobs/p0/cache/report/Telegram pipeline using loopback fixtures only. */
class WorkerPipelineTest {
    @TempDir Path directory;
    private static final String TOKEN = "123456:PIPELINE_PRIVATE_BOT_TOKEN";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    void pullCachePreviewSendAndRestartRemainConsistentAndIncompleteReportsNeverSend() throws Exception {
        LocalDate latest = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        AtomicInteger p0Requests = new AtomicInteger();
        ConcurrentLinkedQueue<JsonNode> deliveries = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> invalidRequests = new ConcurrentLinkedQueue<>();
        var upstreamThreads = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(upstreamThreads);
        upstream.createContext("/api/tasks/list", exchange -> {
            p0Requests.incrementAndGet();
            if (!"GET".equals(exchange.getRequestMethod())) invalidRequests.add("p0 must be GET");
            if (!"true".equals(exchange.getRequestHeaders().getFirst("canary"))) invalidRequests.add("canary header missing");
            if (exchange.getRequestHeaders().getFirst("apikey") != null) invalidRequests.add("anonymous request unexpectedly included apikey");
            Map<String, String> query = query(exchange);
            if (!"2".equals(query.get("status")) || !"1000".equals(query.get("page_size")) || !"1".equals(query.get("page")))
                invalidRequests.add("unexpected status/page parameters");
            try {
                LocalDateTime hour = LocalDateTime.parse(query.get("start_time"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                String createdAt = hour.plusMinutes(15).toString();
                List<Map<String, Object>> rows = new ArrayList<>();
                if (hour.toLocalDate().equals(latest)) {
                    rows.add(task(hour + "-a", "hash_a", "workflow_a", "2.50", createdAt));
                    rows.add(task(hour + "-b", "hash_b", "workflow_b", "1.25", createdAt));
                } else {
                    rows.add(task(hour + "-old", "hash_old", "workflow_old", "1.00", createdAt));
                }
                reply(exchange, 200, Map.of("code", 0, "data", Map.of("list", rows, "total", rows.size(), "has_more", false)));
            } catch (Exception e) {
                invalidRequests.add("fixture could not parse requested interval");
                reply(exchange, 400, Map.of("code", 400));
            }
        });
        upstream.createContext("/bot" + TOKEN + "/sendMessage", exchange -> {
            deliveries.add(Json.MAPPER.readTree(exchange.getRequestBody()));
            reply(exchange, 200, Map.of("ok", true, "result", Map.of("message_id", deliveries.size())));
        });
        upstream.start();
        URI upstreamBase = URI.create("http://127.0.0.1:" + upstream.getAddress().getPort());
        try {
            try (WorkerService worker = new WorkerService(directory, new TelegramClient(upstreamBase));
                 WebServer web = new WebServer(worker, "127.0.0.1", 0)) {
                web.start();
                String base = "http://127.0.0.1:" + web.port();
                Map<String, Object> config = Map.of("p0Endpoint", upstreamBase + "/api/tasks/list", "p0TimeFormat", "datetime",
                        "timezone", "Asia/Shanghai", "p0ApiKey", "", "telegramBotToken", TOKEN,
                        "telegramChatId", "-100987654321", "reportDays", 2, "amountsEnabled", true,
                        "channelNames", Map.of("hash_a", "渠道甲", "hash_b", "渠道乙", "hash_old", "前日渠道"),
                        "workflowNames", Map.of("workflow_a", "图像编辑", "workflow_b", "视频处理", "workflow_old", "前日工作流"));
                assertEquals(200, request(base, "PUT", "/api/config", config).statusCode());
                assertEquals(202, request(base, "POST", "/api/pull", Map.of("endDate", latest.toString())).statusCode());
                awaitCompletedJob(base);
                assertEquals(48, p0Requests.get(), "two complete days should each request 24 hourly intervals");
                assertTrue(invalidRequests.isEmpty(), invalidRequests.toString());
                HttpResponse<String> previewResponse = request(base, "GET", "/api/report?endDate=" + latest, null);
                assertEquals(200, previewResponse.statusCode());
                JsonNode preview = Json.MAPPER.readTree(previewResponse.body());
                assertEquals(2, preview.path("days").size());
                assertEquals(latest.toString(), preview.path("days").get(0).path("date").asText());
                assertEquals(48, preview.path("latestCount").asLong());
                assertEquals(72, preview.path("totalCount").asLong());
                assertEquals(0, new BigDecimal("90.00").compareTo(preview.path("latestFee").decimalValue()));
                assertEquals(0, new BigDecimal("114.00").compareTo(preview.path("totalFee").decimalValue()));
                assertEquals(2, preview.path("channels").size(), "rankings must include the latest day only");
                assertEquals("渠道甲", preview.path("channels").get(0).path("name").asText());
                assertEquals(24, preview.path("channels").get(0).path("count").asInt());
                assertEquals(0, new BigDecimal("66.7").compareTo(preview.path("channels").get(0).path("share").decimalValue()));
                assertEquals(2, preview.path("workflows").size());
                assertEquals("图像编辑", preview.path("workflows").get(0).path("name").asText());
                assertEquals(0, new BigDecimal("50.0").compareTo(preview.path("workflows").get(0).path("share").decimalValue()));
                String text = preview.path("text").asText();
                assertTrue(text.startsWith("AIAPI 日统计｜"));
                assertTrue(text.contains("（2天）\n【每日】日期｜单数｜金额"));
                assertTrue(text.contains("按金额降序：渠道｜单数｜金额｜占比"));
                assertTrue(text.contains("1. 渠道甲｜24｜60｜66.7%"));
                assertTrue(text.contains("1. 图像编辑｜24｜50.0%"));
                assertFalse(text.contains("前日渠道"));
                assertFalse(text.contains("前日工作流"));
                assertEquals(202, request(base, "POST", "/api/pull", Map.of("endDate", latest.toString())).statusCode());
                awaitCompletedJob(base);
                assertEquals(48, p0Requests.get(), "a second pull must reuse both complete day caches");
                assertEquals(202, request(base, "POST", "/api/send", Map.of("endDate", latest.toString())).statusCode());
                awaitCompletedJob(base);
                assertEquals(1, deliveries.size());
                JsonNode message = deliveries.element();
                assertEquals(text, message.path("text").asText(), "the delivered report must equal the preview");
                assertEquals("-100987654321", message.path("chat_id").asText());
                assertFalse(message.has("parse_mode"));
                String ledgerText = Files.readString(directory.resolve("deliveries.json"));
                assertFalse(ledgerText.contains(TOKEN));
                JsonNode ledger = Json.MAPPER.readTree(ledgerText);
                assertEquals(1, ledger.size());
                assertEquals("sent", ledger.elements().next().path("status").asText());
            }
            // Restart from the same persisted cache/config/ledger, then deliberately remove a required day.
            try (WorkerService restarted = new WorkerService(directory, new TelegramClient(upstreamBase));
                 WebServer web = new WebServer(restarted, "127.0.0.1", 0)) {
                web.start();
                String base = "http://127.0.0.1:" + web.port();
                JsonNode status = Json.MAPPER.readTree(request(base, "GET", "/api/status", null).body());
                assertEquals(latest.toString(), status.path("lastSentDate").asText());
                Files.delete(directory.resolve("cache").resolve(latest.minusDays(1) + ".json"));
                assertEquals(409, request(base, "GET", "/api/report?endDate=" + latest, null).statusCode());
                assertEquals(409, request(base, "POST", "/api/send", Map.of("endDate", latest.toString())).statusCode());
                assertFalse(restarted.jobs.isRunning());
                assertEquals(1, deliveries.size(), "missing a day must never send a partial report");
                assertEquals(48, p0Requests.get(), "preview/send must never silently pull data");
            }
        } finally {
            upstream.stop(0);
            upstreamThreads.shutdownNow();
        }
    }

    private static Map<String, Object> task(String id, String hash, String workflow, String fee, String createdAt) {
        return Map.of("id", id, "hash_key", hash, "task_type", workflow, "fee", fee, "created_at", createdAt, "status", 2);
    }

    private HttpResponse<String> request(String base, String method, String path, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(3))
                .header("X-Requested-With", "foya-admin");
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method,
                HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void awaitCompletedJob(String base) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        do {
            JsonNode status = Json.MAPPER.readTree(request(base, "GET", "/api/status", null).body());
            if (!status.path("running").asBoolean()) {
                assertEquals("completed", status.path("job").path("state").asText(), status.toPrettyString());
                return;
            }
            Thread.sleep(40);
        } while (System.nanoTime() < deadline);
        fail("background pipeline job did not finish within 8 seconds");
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> values = new HashMap<>();
        for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            values.put(pair[0], pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private static void reply(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] body = Json.MAPPER.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
