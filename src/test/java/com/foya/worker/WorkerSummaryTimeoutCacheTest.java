package com.foya.worker;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkerSummaryTimeoutCacheTest {
    @TempDir Path directory;

    @Test
    void changingTimeoutImmediatelyRetriesAnOtherwiseIdenticalCachedFailure() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicBoolean available = new AtomicBoolean();
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            boolean success = available.get();
            byte[] body = Json.MAPPER.writeValueAsBytes(success
                    ? Map.of("choices", List.of(Map.of("message", Map.of("content", "当日成功 1 单。"))))
                    : Map.of("error", Map.of("message", "temporary test rejection")));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(success ? 200 : 401, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        upstream.start();
        try (WorkerService worker = TestWorkers.create(directory)) {
            worker.configs.update(Json.MAPPER.valueToTree(Map.of(
                    "reportDays", 1,
                    "llmEnabled", true,
                    "llmBaseUrl", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/v1",
                    "llmApiKey", "loopback-summary-cache-key",
                    "llmModel", "summary-cache-test-model",
                    "llmTimeoutSeconds", 5)));
            AppConfig config = worker.configs.snapshot();
            LocalDate date = LocalDate.of(2024, 1, 2);
            DailyStats day = new DailyStats();
            day.date = date.toString();
            day.timezone = config.timezone;
            day.fetchedAt = Instant.now().toString();
            day.sourceFingerprint = StatsService.fingerprint(config, P0Client.resolveApiKey(config));
            day.totalCount = 1;
            day.buckets.add(new DailyStats.Bucket("channel", "workflow", 1, BigDecimal.ZERO));
            new LocalTestDailyCacheStore(directory).write(config, day);
            String original = worker.reportResponse(date).get("text").toString();

            Map<String, Object> failure = worker.summaryResponse(date);
            assertEquals("failed", failure.get("aiStatus"));
            assertEquals(original, failure.get("text"), "LLM failure preserves the source report");
            assertEquals(1, requests.get());

            available.set(true);
            assertEquals(failure, worker.summaryResponse(date), "same settings reuse their short failure cache");
            assertEquals(1, requests.get());

            worker.configs.update(Json.MAPPER.valueToTree(Map.of("llmTimeoutSeconds", 6)));
            Map<String, Object> retry = worker.summaryResponse(date);
            assertEquals(2, requests.get(), "a changed timeout must retry without waiting for failure cache expiry");
            assertEquals("ok", retry.get("aiStatus"));
            assertEquals("当日成功 1 单。", retry.get("aiSummary"));
            assertEquals(original + "\n\n【AI 数据总结】\n当日成功 1 单。", retry.get("text"));
            assertEquals(retry, worker.summaryResponse(date), "successful retry remains reusable");
            assertEquals(2, requests.get());
            assertEquals(original, worker.reportResponse(date).get("text"));
        } finally {
            upstream.stop(0);
        }
    }
}
