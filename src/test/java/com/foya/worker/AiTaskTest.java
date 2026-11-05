package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiTaskTest {
    @TempDir Path directory;

    private static Map<String,Object> statistics() {
        return Map.of("startTime", "2026-11-01T00:00:00", "endTime", "2026-11-02T00:00:00", "total", 10,
                "counts", Map.of("-1", 1, "0", 2, "1", 1, "2", 6, "3", 0), "otherCount", 0,
                "tasks", List.of(Map.of("prompt", "RAW_TASK_MUST_NOT_SEND")), "apiKey", "SECRET_MUST_NOT_SEND",
                "filters", Map.of("task_type", "type-a", "hash_key", "channel-a", "task_id", "task-1"));
    }

    private static Map<String,Object> await(WorkerService worker, String id) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(7).toNanos();
        Map<String,Object> status;
        do {
            status = worker.aiTaskStatus(id);
            if (!status.get("state").equals("running")) return status;
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        fail("AI job did not reach a terminal state");
        return status;
    }

    @Test
    void taskSummaryUsesWhitelistedFullIntervalAggregatesAndHonorsPerRequestTimeout() throws Exception {
        AtomicReference<JsonNode> received = new AtomicReference<>();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            received.set(Json.MAPPER.readTree(exchange.getRequestBody()));
            byte[] body = Json.MAPPER.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("content", "区间共 10 条任务，状态 0 为 2 条。")))));
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        upstream.start();
        try (WorkerService worker = TestWorkers.create(directory)) {
            worker.configs.update(Json.MAPPER.valueToTree(Map.of("llmBaseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1", "llmApiKey", "test-key", "llmTimeoutSeconds", 5)));
            Map<String,Object> started = worker.startAiTask(Json.MAPPER.valueToTree(Map.of("kind", "tasks", "timeoutSeconds", 300, "statistics", statistics())));
            assertEquals(300, started.get("timeoutSeconds"));
            Map<String,Object> result = await(worker, started.get("id").toString());
            assertEquals("complete", result.get("state"));
            JsonNode data = Json.MAPPER.valueToTree(result.get("result"));
            assertEquals("ok", data.path("aiStatus").asText());
            assertTrue(data.path("aiText").asText().contains("10"));
            JsonNode metrics = Json.MAPPER.readTree(received.get().path("messages").path(1).path("content").asText());
            assertEquals(2, metrics.path("counts").path("0").asInt());
            assertEquals("interval", metrics.path("scope").asText());
            assertTrue(metrics.path("consistent").asBoolean());
            assertEquals(0, metrics.path("snapshotDifference").asLong());
            assertTrue(metrics.path("filtered").asBoolean());
            assertEquals("type-a", metrics.path("filters").path("task_type").asText());
            assertEquals("channel-a", metrics.path("filters").path("hash_key").asText());
            assertEquals("task-1", metrics.path("filters").path("task_id").asText());
            assertFalse(received.get().toString().contains("RAW_TASK_MUST_NOT_SEND"));
            assertFalse(received.get().toString().contains("SECRET_MUST_NOT_SEND"));
        } finally { upstream.stop(0); }
    }

    @Test
    void cancelStopsGenerationAndCompletedResultCannotOverwriteCancellation() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var threads = Executors.newVirtualThreadPerTaskExecutor();
        upstream.setExecutor(threads);
        upstream.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes(); entered.countDown();
            try { release.await(3, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try { exchange.sendResponseHeaders(200, -1); } catch (IOException ignored) {} finally { exchange.close(); }
        });
        upstream.start();
        try (WorkerService worker = TestWorkers.create(directory)) {
            worker.configs.update(Json.MAPPER.valueToTree(Map.of("llmBaseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1", "llmApiKey", "test-key")));
            String id = worker.startAiTask(Json.MAPPER.valueToTree(Map.of("kind", "tasks", "timeoutSeconds", 300, "statistics", statistics()))).get("id").toString();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();
            assertEquals("cancelled", worker.cancelAiTask(Json.MAPPER.valueToTree(Map.of("id", id))).get("state"));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 500);
            release.countDown();
            assertEquals("cancelled", await(worker, id).get("state"));
            assertEquals("cancelled", worker.cancelAiTask(Json.MAPPER.valueToTree(Map.of("id", id))).get("state"), "cancel is idempotent");
        } finally { release.countDown(); upstream.stop(0); threads.shutdownNow(); }
    }

    @Test
    void totalDeadlineAlsoInterruptsSlowReportCacheBeforeCallingModel() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        DailyCacheStore slowCache = new DailyCacheStore() {
            public DailyStats read(AppConfig config, LocalDate day, String fingerprint) throws IOException {
                try { Thread.sleep(30_000); }
                catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); throw new IOException("cache interrupted"); }
                return null;
            }
            public void write(AppConfig config, DailyStats day) {}
            public List<DailyStats> list(AppConfig config, String fingerprint) { return List.of(); }
        };
        try (WorkerService worker = new WorkerService(directory, new TelegramClient(), new TelegramDirectory(directory), slowCache)) {
            long started = System.nanoTime();
            String id = worker.startAiTask(Json.MAPPER.valueToTree(Map.of("kind", "report", "timeoutSeconds", 5, "endDate", "2024-01-01"))).get("id").toString();
            Map<String,Object> state = await(worker, id);
            assertEquals("timed_out", state.get("state"));
            assertTrue(interrupted.await(1, TimeUnit.SECONDS), "deadline must cancel the report-reading thread");
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 6500);
        }
    }

    @Test
    void invalidTimeoutAndCountsAreRejectedBeforeCreatingJobs() throws Exception {
        try (WorkerService worker = TestWorkers.create(directory)) {
            assertThrows(IllegalArgumentException.class, () -> worker.startAiTask(Json.MAPPER.valueToTree(Map.of("kind", "tasks", "timeoutSeconds", 301, "statistics", statistics()))));
            var partial = Json.MAPPER.valueToTree(statistics());
            ((com.fasterxml.jackson.databind.node.ObjectNode) partial).put("complete", false);
            assertThrows(IllegalArgumentException.class, () -> worker.startAiTask(Json.MAPPER.valueToTree(Map.of("kind", "tasks", "statistics", partial))));
            assertThrows(IllegalArgumentException.class, () -> worker.startAiTask(Json.MAPPER.valueToTree(Map.of("kind", "tasks", "statistics", Map.of("startTime", "2026-11-01T00:00", "endTime", "2026-11-02T00:00", "total", -1, "counts", Map.of("0", 0))))));
        }
    }
}
