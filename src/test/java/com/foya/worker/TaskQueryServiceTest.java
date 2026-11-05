package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

class TaskQueryServiceTest {
    @TempDir Path directory;
    HttpServer upstream;
    ExecutorService executor;
    AppConfig config;
    Map<String, String> params() {
        return new HashMap<>(Map.of("start_time", "2026-11-04T10:00:00.000000", "end_time", "2026-11-04T10:59:59.999999"));
    }
    void serve(Function<Map<String, String>, String> response) throws Exception {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.setExecutor(executor);
        upstream.createContext("/api/tasks/list", exchange -> {
            Map<String, String> query = new HashMap<>();
            for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            }
            assertEquals("true", exchange.getRequestHeaders().getFirst("canary"));
            byte[] body = response.apply(query).getBytes(StandardCharsets.UTF_8);
            try {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        upstream.start();
        config = new AppConfig();
        config.p0Endpoint = "http://127.0.0.1:" + upstream.getAddress().getPort() + "/api/tasks/list";
    }
    @AfterEach void stop() {
        if (upstream != null) upstream.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test void preservesStatusZeroAndInclusiveMicrosecondEndWithPreciseServerFilters() throws Exception {
        serve(q -> {
            assertEquals("0", q.get("status"));
            assertEquals("2026-11-04T10:59:59.999999", q.get("end_time"));
            assertEquals("flow & test", q.get("task_type"));
            assertEquals("hash", q.get("hash_key"));
            assertEquals("business-id", q.get("task_id"));
            return "{\"page_size\":25,\"total_count\":100,\"tasks\":[{\"uuid\":\"record-id\",\"task_id\":\"business-id\",\"status\":0,\"task_type\":\"flow & test\",\"hash_key\":\"hash\",\"prompt\":\"full content\",\"created_at\":\"2026-11-04T10:59:59.999999\"}]}";
        });
        config.channelNames.put("hash", "渠道名称");
        config.workflowNames.put("flow & test", "工作流名称");
        Map<String, String> params = params();
        params.putAll(Map.of("status", "0", "page", "2", "page_size", "25", "task_type", "flow & test", "hash_key", "hash", "task_id", "business-id"));
        JsonNode result = Json.MAPPER.valueToTree(new TaskQueryService().list(config, params));
        assertEquals(100, result.path("total").asLong());
        assertEquals(2, result.path("page").asInt());
        assertTrue(result.path("hasMore").asBoolean());
        assertEquals("渠道名称", result.path("items").get(0).path("channelName").asText());
        assertEquals("工作流名称", result.path("items").get(0).path("workflowName").asText());
        assertEquals("full content", result.path("items").get(0).path("prompt").asText());
    }

    @Test void actualWorkflowNamesTakePriorityAndMissingNamesDoNotRepeatTaskType() throws Exception {
        serve(q -> """
                {"total_count":4,"tasks":[
                  {"task_type":"configured_type","workflow_name":"实际工作流名称","workflowName":"别名工作流名称"},
                  {"task_type":"configured_type","workflowName":"接口别名工作流名称"},
                  {"task_type":"configured_type"},
                  {"task_type":"unmapped_type"}
                ]}
                """);
        config.workflowNames.put("configured_type", "配置映射名称");
        JsonNode rows = Json.MAPPER.valueToTree(new TaskQueryService().list(config, params())).path("items");
        assertEquals("实际工作流名称", rows.get(0).path("workflowName").asText());
        assertEquals("接口别名工作流名称", rows.get(1).path("workflowName").asText());
        assertEquals("配置映射名称", rows.get(2).path("workflowName").asText());
        assertEquals("", rows.get(3).path("workflowName").asText());
        for (int index = 0; index < 3; index++)
            assertEquals("configured_type", rows.get(index).path("task_type").asText());
        assertEquals("unmapped_type", rows.get(3).path("task_type").asText());
    }

    @Test void statisticsUseGlobalMetadataForEveryStatusAndIgnoreListStatusAndPagination() throws Exception {
        Set<String> requested = ConcurrentHashMap.newKeySet();
        serve(q -> {
            String status = q.getOrDefault("status", "all");
            requested.add(status);
            assertEquals("1", q.get("page_size"));
            assertEquals("1", q.get("page"));
            assertEquals("flow", q.get("task_type"));
            long count = status.equals("all") ? 1000 : status.equals("2") ? 900 : 25;
            return "{\"total_count\":" + count + ",\"tasks\":[{\"status\":" + (status.equals("all") ? "2" : status) + "}]}";
        });
        Map<String, String> params = params();
        params.putAll(Map.of("status", "0", "page", "9", "page_size", "50", "task_type", "flow"));
        JsonNode result = Json.MAPPER.valueToTree(new TaskQueryService().statistics(config, params));
        assertEquals(Set.of("all", "-1", "0", "1", "2", "3"), requested);
        assertEquals(1000, result.path("total").asLong());
        assertEquals(25, result.path("counts").path("0").asLong());
        assertEquals(900, result.path("counts").path("2").asLong());
        assertTrue(result.path("complete").asBoolean());
        assertTrue(result.path("consistent").asBoolean());
        assertEquals(0, result.path("snapshotDifference").asLong());
        assertEquals("interval", result.path("scope").asText());
        assertEquals("flow", result.path("filters").path("taskType").asText());
    }

    @Test void hidesSecretsRecursivelyIncludingResolvedKeyFileAndDynamicPropertyNames() throws Exception {
        serve(q -> "{\"tasks\":[{\"prompt\":\"secret-file-value and llm-private\",\"token\":\"unknown-token\",\"meta\":{\"api_key\":\"unknown-key\",\"secret-file-value\":\"llm-private\"},\"notify_url\":\"https://example.test?token=unknown-token\"}],\"total_count\":1}");
        Path keyFile = directory.resolve("p0.key");
        java.nio.file.Files.writeString(keyFile, "secret-file-value");
        config.p0ApiKeyFile = keyFile.toString();
        config.llmApiKey = "llm-private";
        String json = Json.MAPPER.writeValueAsString(new TaskQueryService().list(config, params()));
        for (String secret : List.of("secret-file-value", "llm-private", "unknown-token", "unknown-key")) assertFalse(json.contains(secret), secret);
        assertTrue(json.contains("已隐藏"));
    }

    @Test void missingTotalRequiresCompletePaginationAndDoesNotTreatPageLengthAsTotal() throws Exception {
        serve(q -> {
            int page = Integer.parseInt(q.get("page"));
            String status = q.get("status");
            if (page >= 3) return "{\"tasks\":[]}";
            return "{\"tasks\":[{\"uuid\":\"row-" + page + "\",\"status\":" + (status == null ? "2" : status) + "}]}";
        });
        // Every query completed, but their separate snapshots disagree. Surface the
        // signed difference rather than inventing an unknown task status bucket.
        JsonNode stats = Json.MAPPER.valueToTree(new TaskQueryService().statistics(config, params()));
        assertTrue(stats.path("complete").asBoolean());
        assertFalse(stats.path("consistent").asBoolean());
        assertEquals(-8, stats.path("snapshotDifference").asLong());
        assertFalse(stats.has("otherCount"));
        JsonNode list = Json.MAPPER.valueToTree(new TaskQueryService().list(config, params()));
        assertTrue(list.path("total").isNull());
        assertTrue(list.path("hasMore").asBoolean());
    }

    @Test void repeatedPagesWithoutMetadataAreRejected() throws Exception {
        serve(q -> "{\"tasks\":[{\"uuid\":\"same-record\",\"status\":" + q.getOrDefault("status", "2") + "}]}" );
        Exception error = assertThrows(Exception.class, () -> new TaskQueryService().statistics(config, params()));
        assertTrue(error.getMessage().contains("重复"));
    }

    @Test void metadataLessStatisticsReturnOnlyAfterEveryPageIsComplete() throws Exception {
        serve(q -> {
            String status = q.get("status");
            if (status != null && !status.equals("2")) return "{\"tasks\":[]}";
            int page = Integer.parseInt(q.get("page"));
            if (page == 3) return "{\"tasks\":[]}";
            return "{\"tasks\":[{\"uuid\":\"record-" + page + "\",\"status\":2}]}";
        });
        JsonNode result = Json.MAPPER.valueToTree(new TaskQueryService().statistics(config, params()));
        assertEquals(2, result.path("total").asLong());
        assertEquals(2, result.path("counts").path("2").asLong());
        assertEquals(0, result.path("counts").path("0").asLong());
    }

    @Test void rejectsInvalidTimeRangesStatusAndContradictoryUpstreamData() throws Exception {
        serve(q -> "{\"total_count\":1,\"tasks\":[{\"status\":2,\"created_at\":\"2026-11-03T10:30:00\"}]}" );
        TaskQueryService service = new TaskQueryService();
        Map<String, String> params = params();
        params.put("status", "4");
        assertThrows(IllegalArgumentException.class, () -> service.list(config, params));
        params.put("status", "0");
        assertTrue(assertThrows(Exception.class, () -> service.list(config, params)).getMessage().contains("状态"));
        params.remove("status");
        assertTrue(assertThrows(Exception.class, () -> service.list(config, params)).getMessage().contains("时间范围"));
        params.put("end_time", "2026-11-03T10:30:00");
        assertThrows(IllegalArgumentException.class, () -> service.list(config, params));
        assertThrows(Exception.class, () -> TaskQueryService.decode(Json.MAPPER.readTree("{\"total\":2,\"data\":{\"total_count\":1,\"tasks\":[]}}"), 50));
    }

    @Test void wholeOperationDeadlineBoundsSlowUpstreamIndependentlyOfBatchRetryCount() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        serve(q -> {
            requests.incrementAndGet();
            try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return "{\"total_count\":0,\"tasks\":[]}";
        });
        config.p0MaxAttempts = 50;
        long start = System.nanoTime();
        assertThrows(Exception.class, () -> new TaskQueryService(Duration.ofMillis(150)).statistics(config, params()));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1500);
        assertTrue(requests.get() <= 12);
    }

    @Test void silentPageSizeCapIsReturnedAndSubsequentPagesReachEveryRecord() throws Exception {
        serve(q -> {
            int page = Integer.parseInt(q.get("page"));
            int effectiveSize = Math.min(20, Integer.parseInt(q.get("page_size")));
            int first = (page - 1) * effectiveSize;
            List<String> rows = new ArrayList<>();
            for (int i = first; i < Math.min(first + effectiveSize, 100); i++) rows.add("{\"uuid\":\"row-" + i + "\"}");
            return "{\"total_count\":100,\"tasks\":[" + String.join(",", rows) + "]}";
        });
        TaskQueryService service = new TaskQueryService();
        Map<String, String> params = params();
        params.put("page_size", "50");
        Set<String> seen = new HashSet<>();
        for (int page = 1; page <= 5; page++) {
            params.put("page", Integer.toString(page));
            JsonNode result = Json.MAPPER.valueToTree(service.list(config, params));
            assertEquals(20, result.path("pageSize").asInt());
            params.put("page_size", result.path("pageSize").asText());
            result.path("items").forEach(row -> assertTrue(seen.add(row.path("uuid").asText())));
            assertEquals(page < 5, result.path("hasMore").asBoolean());
        }
        assertEquals(100, seen.size());
    }

    @Test void singleSlowStatusPreservesCompletedStatusZeroStatisticsAtSharedDeadline() throws Exception {
        serve(q -> {
            String status = q.get("status");
            if ("2".equals(status)) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            long total = status == null ? 100 : "0".equals(status) ? 7 : 0;
            return "{\"total_count\":" + total + ",\"tasks\":" + (total == 0 ? "[]" : "[{\"status\":" + (status == null ? "2" : status) + "}]") + "}";
        });
        long start = System.nanoTime();
        JsonNode stats = Json.MAPPER.valueToTree(new TaskQueryService(Duration.ofMillis(350)).statistics(config, params()));
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1500);
        assertEquals(100, stats.path("total").asLong());
        assertEquals(7, stats.path("counts").path("0").asLong());
        assertFalse(stats.path("counts").has("2"));
        assertTrue(stats.path("counts").has("3"), "a later completed future must survive an earlier timeout");
        assertFalse(stats.path("complete").asBoolean());
        assertFalse(stats.path("consistent").asBoolean());
        assertTrue(stats.path("snapshotDifference").isNull());
        assertTrue(stats.path("errors").has("2"));
    }

    @Test void positiveSnapshotDifferenceIsNotReportedAsUnknownStatus() throws Exception {
        serve(q -> "{\"total_count\":" + (q.get("status") == null ? 100 : 10)
                + ",\"tasks\":[{\"status\":" + q.getOrDefault("status", "2") + "}]}" );
        JsonNode stats = Json.MAPPER.valueToTree(new TaskQueryService().statistics(config, params()));
        assertTrue(stats.path("complete").asBoolean());
        assertFalse(stats.path("consistent").asBoolean());
        assertEquals(50, stats.path("snapshotDifference").asLong());
        assertFalse(stats.has("otherCount"));
    }

    @Test void routesAreReadOnlyAndReturnValidationErrorsWithRequestDetails() throws Exception {
        serve(q -> "{\"total_count\":0,\"tasks\":[]}");
        try (WorkerService worker = TestWorkers.create(directory);
             WebServer server = new WebServer(worker, "127.0.0.1", 0)) {
            worker.configs.update(Json.MAPPER.createObjectNode().put("p0Endpoint", config.p0Endpoint));
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> invalid = client.send(HttpRequest.newBuilder(URI.create(base + "/api/tasks")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalid.statusCode());
            assertTrue(Json.MAPPER.readTree(invalid.body()).has("errorDetails"));
            String query = "?start_time=2026-11-04T10:00:00&end_time=2026-11-04T10:59:59&status=0";
            HttpResponse<String> valid = client.send(HttpRequest.newBuilder(URI.create(base + "/api/tasks" + query)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, valid.statusCode());
            assertEquals(0, Json.MAPPER.readTree(valid.body()).path("total").asLong());
        }
    }
}
