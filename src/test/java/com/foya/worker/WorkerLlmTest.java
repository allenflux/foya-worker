package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Integration coverage for preview -> optional LLM -> Telegram, using loopback endpoints only. */
class WorkerLlmTest {
    @TempDir Path directory;
    private static final LocalDate END_DATE = LocalDate.of(2026, 10, 29);
    private static final String BOT_TOKEN = "123456:WORKER_LLM_TEST_TOKEN";
    private static final String SUMMARY = "1. 当日共 4 单，金额 100.25。\n2. 渠道甲金额占比为 74.8%。";
    private final AtomicInteger llmCalls = new AtomicInteger();
    private final List<JsonNode> llmPayloads = Collections.synchronizedList(new ArrayList<>());
    private final List<JsonNode> sentMessages = Collections.synchronizedList(new ArrayList<>());
    private final List<String> telegramPaths = Collections.synchronizedList(new ArrayList<>());
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private HttpServer mock;

    private URI startMock(int llmStatus) throws IOException {
        mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mock.createContext("/v1/chat/completions", exchange -> {
            llmCalls.incrementAndGet();
            llmPayloads.add(Json.MAPPER.readTree(exchange.getRequestBody()));
            if (llmStatus == 200) reply(exchange, 200, Map.of("choices", List.of(Map.of("message", Map.of("content", SUMMARY)))));
            else reply(exchange, llmStatus, Map.of("error", Map.of("message", "upstream response must stay private")));
        });
        mock.createContext("/bot" + BOT_TOKEN + "/sendMessage", exchange -> {
            telegramPaths.add(exchange.getRequestURI().getPath());
            sentMessages.add(Json.MAPPER.readTree(exchange.getRequestBody()));
            reply(exchange, 200, Map.of("ok", true, "result", Map.of("message_id", 99)));
        });
        mock.start();
        return URI.create("http://127.0.0.1:" + mock.getAddress().getPort());
    }

    private static void reply(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private void configure(WorkerService worker, URI mockBase, boolean enabled, boolean withKey) throws IOException {
        worker.configs.update(Json.MAPPER.valueToTree(Map.ofEntries(
                Map.entry("reportDays", 1), Map.entry("amountsEnabled", true),
                Map.entry("taskTypePrices", Map.of("edit", new BigDecimal("25.00"), "video", new BigDecimal("25.25"))), Map.entry("telegramBotToken", BOT_TOKEN), Map.entry("telegramChatId", "-100123456789"),
                Map.entry("llmEnabled", enabled), Map.entry("llmBaseUrl", mockBase + "/v1"),
                Map.entry("llmApiKey", withKey ? "local-worker-llm-test-secret" : ""), Map.entry("llmModel", "local-summary-model"),
                Map.entry("llmTimeoutSeconds", 5), Map.entry("channelNames", Map.of("alpha", "渠道甲", "beta", "渠道乙")),
                Map.entry("workflowNames", Map.of("edit", "图像编辑", "video", "图生视频")))));
        AppConfig config = worker.configs.snapshot();
        DailyStats day = new DailyStats();
        day.date = END_DATE.toString();
        day.timezone = config.timezone;
        day.fetchedAt = Instant.now().toString();
        day.sourceFingerprint = StatsService.fingerprint(config, P0Client.resolveApiKey(config));
        day.totalCount = 4;
        day.totalFee = new BigDecimal("100.25");
        day.requestCount = 24;
        day.buckets.add(new DailyStats.Bucket("alpha", "edit", 3, new BigDecimal("75.25")));
        day.buckets.add(new DailyStats.Bucket("beta", "video", 1, new BigDecimal("25.00")));
        FilesSupport.writeJson(directory.resolve("cache").resolve(END_DATE + ".json"), day);
    }

    private JsonNode post(WebServer web, String path, int expectedStatus) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + web.port() + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .header("X-Requested-With", "foya-admin")
                .POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(Map.of("endDate", END_DATE.toString())), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(expectedStatus, response.statusCode());
        return Json.MAPPER.readTree(response.body());
    }

    private static void awaitCompleted(WorkerService worker) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (worker.jobs.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(worker.jobs.isRunning(), "Telegram delivery job exceeded bounded test deadline");
        Map<?, ?> job = (Map<?, ?>) worker.jobs.status().get("job");
        assertNotNull(job);
        assertEquals("completed", job.get("state"));
    }

    private void assertSent(String expectedText) {
        assertEquals(1, sentMessages.size());
        assertEquals(expectedText, sentMessages.getFirst().path("text").asText());
        assertEquals("-100123456789", sentMessages.getFirst().path("chat_id").asText());
        assertFalse(sentMessages.getFirst().has("parse_mode"));
        assertEquals(List.of("/bot" + BOT_TOKEN + "/sendMessage"), telegramPaths);
    }

    @Test
    void summaryEndpointAppendsAiToOriginalAndManualSendReusesIdenticalPreview() throws Exception {
        URI mockBase = startMock(200);
        try (WorkerService worker = TestWorkers.create(directory, new TelegramClient(mockBase));
             WebServer web = new WebServer(worker, "127.0.0.1", 0)) {
            configure(worker, mockBase, true, true);
            web.start();
            String original = worker.reportResponse(END_DATE).get("text").toString();
            JsonNode preview = post(web, "/api/summary", 200);
            String expected = original + "\n\n【AI 数据总结】\n" + SUMMARY;
            assertEquals(expected, preview.path("text").asText());
            assertEquals(SUMMARY, preview.path("aiSummary").asText());
            assertEquals("ok", preview.path("aiStatus").asText());
            assertEquals(1, llmCalls.get());
            assertTrue(sentMessages.isEmpty(), "Preview must not send Telegram messages");

            post(web, "/api/send", 202);
            awaitCompleted(worker);
            assertSent(expected);
            assertEquals(1, llmCalls.get(), "Manual send should reuse the cached successful preview");
            assertEquals(expected, post(web, "/api/summary", 200).path("text").asText());
            assertEquals(1, llmCalls.get());
            assertEquals(original, worker.reportResponse(END_DATE).get("text"), "Source statistics remain unchanged");

            JsonNode aggregates = Json.MAPPER.readTree(llmPayloads.getFirst().path("messages").path(1).path("content").asText());
            assertEquals(END_DATE.toString(), aggregates.path("endDate").asText());
            assertEquals(4, aggregates.path("latestCount").asInt());
            assertTrue(aggregates.path("days").isArray());
            assertTrue(aggregates.path("channelsTop10").isArray());
            assertTrue(aggregates.path("workflowsTop10").isArray());
            assertFalse(aggregates.has("buckets"));
            assertFalse(aggregates.has("text"));
            String payload = llmPayloads.getFirst().toString();
            assertFalse(payload.contains(BOT_TOKEN));
            assertFalse(payload.contains(worker.configs.snapshot().llmApiKey));
        }
    }

    @Test
    void unauthorizedLlmStillCompletesManualDeliveryOfOriginalAndLogsFallback() throws Exception {
        URI mockBase = startMock(401);
        try (WorkerService worker = TestWorkers.create(directory, new TelegramClient(mockBase));
             WebServer web = new WebServer(worker, "127.0.0.1", 0)) {
            configure(worker, mockBase, true, true);
            web.start();
            String original = worker.reportResponse(END_DATE).get("text").toString();
            post(web, "/api/send", 202);
            awaitCompleted(worker);
            assertSent(original);
            assertEquals(1, llmCalls.get(), "401 must not cause repeated LLM requests");
            String logs = worker.jobs.status().get("logs").toString();
            assertTrue(logs.contains("AI 总结不可用"));
            assertFalse(logs.contains("upstream response must stay private"));
            JsonNode preview = post(web, "/api/summary", 200);
            assertEquals(original, preview.path("text").asText());
            assertEquals("failed", preview.path("aiStatus").asText());
            assertEquals("", preview.path("aiSummary").asText());
            assertEquals(1, llmCalls.get(), "A recent failed summary is briefly cached");
        }
    }

    @Test
    void disabledLlmNeverCallsModelAndSendsOriginal() throws Exception {
        URI mockBase = startMock(200);
        try (WorkerService worker = TestWorkers.create(directory, new TelegramClient(mockBase));
             WebServer web = new WebServer(worker, "127.0.0.1", 0)) {
            configure(worker, mockBase, false, true);
            web.start();
            String original = worker.reportResponse(END_DATE).get("text").toString();
            JsonNode preview = post(web, "/api/summary", 200);
            assertEquals("disabled", preview.path("aiStatus").asText());
            assertEquals(original, preview.path("text").asText());
            post(web, "/api/send", 202);
            awaitCompleted(worker);
            assertSent(original);
            assertEquals(0, llmCalls.get());
            assertFalse(worker.jobs.status().get("logs").toString().contains("AI 总结不可用"));
        }
    }

    @Test
    void enabledWithoutKeySkipsModelAndStillSendsOriginal() throws Exception {
        assumeTrue(System.getenv("LLM_API_KEY") == null || System.getenv("LLM_API_KEY").isBlank(),
                "An environment-provided key intentionally overrides the missing-key test configuration");
        URI mockBase = startMock(200);
        try (WorkerService worker = TestWorkers.create(directory, new TelegramClient(mockBase));
             WebServer web = new WebServer(worker, "127.0.0.1", 0)) {
            configure(worker, mockBase, true, false);
            web.start();
            String original = worker.reportResponse(END_DATE).get("text").toString();
            post(web, "/api/send", 202);
            awaitCompleted(worker);
            assertSent(original);
            assertEquals(0, llmCalls.get());
            JsonNode preview = post(web, "/api/summary", 200);
            assertEquals("failed", preview.path("aiStatus").asText());
            assertTrue(preview.path("aiMessage").asText().contains("未配置 LLM API Key"));
            assertEquals(original, preview.path("text").asText());
            assertTrue(worker.jobs.status().get("logs").toString().contains("AI 总结不可用"));
        }
    }

    @AfterEach
    void closeClients() {
        http.close();
        if (mock != null) mock.stop(0);
    }
}
