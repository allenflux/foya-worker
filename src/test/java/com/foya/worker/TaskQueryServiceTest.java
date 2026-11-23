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

    @Test void monthStatisticsAddEveryDayOnceIncludingBothPartialDays() throws Exception {
        Map<String, Set<String>> bounds = new ConcurrentHashMap<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        serve(q -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                assertEquals("flow", q.get("task_type"));
                assertEquals("channel", q.get("hash_key"));
                assertEquals("task", q.get("task_id"));
                String status = q.getOrDefault("status", "all");
                String interval = q.get("start_time") + "/" + q.get("end_time");
                bounds.computeIfAbsent(status, k -> ConcurrentHashMap.newKeySet()).add(interval);
                calls.incrementAndGet();
                int total = status.equals("all") ? 5 : 1;
                return "{\"total_count\":" + total + ",\"tasks\":[{\"status\":" + q.getOrDefault("status", "2") + "}]}";
            } finally { active.decrementAndGet(); }
        });
        Map<String, String> params = Map.of("start_time", "2026-10-23T02:03:04.123456", "end_time", "2026-11-22T15:36:10.999999",
                "task_type", "flow", "hash_key", "channel", "task_id", "task", "status", "-1");
        JsonNode result = Json.MAPPER.valueToTree(new TaskQueryService().statistics(config, params));
        assertTrue(result.path("complete").asBoolean());
        assertEquals(155, result.path("total").asLong());
        assertEquals(31, result.path("counts").path("-1").asLong());
        assertEquals(0, result.path("snapshotDifference").asLong());
        assertEquals(31 * 6, calls.get());
        assertTrue(peak.get() <= 4);
        Set<String> expected = new HashSet<>();
        for (var date = java.time.LocalDate.parse("2026-10-23"); !date.isAfter(java.time.LocalDate.parse("2026-11-22")); date = date.plusDays(1)) {
            String start = date.getDayOfMonth() == 23 ? params.get("start_time") : date + "T00:00:00.000000";
            String end = date.toString().equals("2026-11-22") ? params.get("end_time") : date + "T23:59:59.999999";
            expected.add(start + "/" + end);
        }
        assertEquals(Set.of("all", "-1", "0", "1", "2", "3"), bounds.keySet());
        bounds.values().forEach(actual -> assertEquals(expected, actual));
    }

    @Test void missingDailyCountNeverBecomesAnIntervalTotal() throws Exception {
        serve(q -> {
            if (q.get("start_time").startsWith("2026-11-05") && (q.get("status") == null || "-1".equals(q.get("status"))))
                return "{\"success\":false}";
            return "{\"total_count\":1,\"tasks\":[{\"status\":" + q.getOrDefault("status", "2") + "}]}";
        });
        JsonNode result = Json.MAPPER.valueToTree(new TaskQueryService().statistics(config, Map.of(
                "start_time", "2026-11-04T00:00:00", "end_time", "2026-11-06T00:00:00")));
        assertFalse(result.path("complete").asBoolean());
        assertTrue(result.path("total").isNull());
        assertFalse(result.path("counts").has("-1"));
        assertEquals(3, result.path("counts").path("0").asLong());
        assertTrue(result.path("errors").has("all"));
        assertTrue(result.path("errors").has("-1"));
    }

    @Test void officialMonthQueriesKeepSparseStatesTogetherAndSplitSuccessfulTasksWithoutGaps() {
        var window = TaskQueryService.window(new AppConfig(), Map.of(
                "start_time", "2026-10-23T10:15:16.123456", "end_time", "2026-11-22T15:36:10.999999"));
        var days = TaskQueryService.dayWindows(window);
        for (int status : List.of(0, 1, 3))
            assertEquals(List.of(window), TaskQueryService.countWindows(window, days, status, true));
        assertEquals(days, TaskQueryService.countWindows(window, days, null, true));
        assertEquals(days, TaskQueryService.countWindows(window, days, -1, true));
        var cursor = window.start();
        for (var part : TaskQueryService.countWindows(window, days, 2, true)) {
            assertEquals(cursor, part.start());
            assertFalse(part.end().isBefore(part.start()));
            assertFalse(part.end().isAfter(part.start().plusHours(3)));
            cursor = part.end().plusNanos(1_000);
        }
        assertEquals(window.end().plusNanos(1_000), cursor);
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

    @Test void monthlyListTraversesOnlyNeededDaysAndPagesInSourceOrder() throws Exception {
        // Include ties, exact midnight boundaries and empty days. The source silently caps requests at two rows.
        List<String> times = List.of("2026-11-22T15:36:10.999999", "2026-11-22T15:36:10.999999",
                "2026-11-22T00:00:00.000000", "2026-11-21T23:59:59.999999", "2026-11-21T01:00:00.000000",
                "2026-11-20T03:00:00.000000", "2026-11-15T01:00:00.000000", "2026-10-23T00:00:00.000000");
        List<Map<String,String>> requests = new CopyOnWriteArrayList<>();
        serve(q -> {
            requests.add(q);
            int size = Math.min(2, Integer.parseInt(q.get("page_size")));
            int offset = (Integer.parseInt(q.get("page")) - 1) * size;
            List<Map<String,Object>> matched = new ArrayList<>();
            for (int index = 0; index < times.size(); index++) {
                String timestamp = times.get(index);
                if (timestamp.compareTo(q.get("start_time")) >= 0 && timestamp.compareTo(q.get("end_time")) <= 0)
                    matched.add(Map.of("uuid", "row-" + index, "created_at", timestamp, "status", 0,
                            "task_type", "flow", "hash_key", "hash", "task_id", "bid"));
            }
            return Json.MAPPER.valueToTree(Map.of("total_count", matched.size(), "tasks",
                    matched.subList(Math.min(offset, matched.size()), Math.min(offset + size, matched.size())))).toString();
        });
        TaskQueryService service = new TaskQueryService();
        var window = TaskQueryService.window(config, Map.of("start_time", "2026-10-23T00:00:00.000000",
                "end_time", "2026-11-22T15:36:10.999999", "task_type", "flow", "hash_key", "hash", "task_id", "bid"));
        for (int number = 1; number <= 3; number++) {
            requests.clear();
            var result = service.listRecentFirst(config, "", window, 0, number, 4,
                    System.nanoTime() + Duration.ofSeconds(20).toNanos());
            List<String> ids = result.rows().stream().map(row -> row.path("uuid").asText()).toList();
            List<String> expected = switch (number) {
                case 1 -> List.of("row-0", "row-1", "row-2", "row-3");
                case 2 -> List.of("row-4", "row-5", "row-6", "row-7");
                default -> List.of();
            };
            assertEquals(expected, ids);
            assertEquals(4, result.pageSize());
            assertEquals(number == 1, result.hasMore());
            if (number == 1) {
                assertNull(result.total());
                assertTrue(requests.size() < 10, "page one must stop before scanning the whole month");
            } else assertEquals(8L, result.total());
            assertTrue(requests.stream().allMatch(q -> "flow".equals(q.get("task_type"))
                    && "hash".equals(q.get("hash_key")) && "bid".equals(q.get("task_id")) && "0".equals(q.get("status"))));
        }
    }

    @Test void longListFromCompatibleSourcePreservesItsOrderingAndSingleRange() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        serve(q -> {
            calls.incrementAndGet();
            return "{\"total_count\":2,\"tasks\":[{\"uuid\":\"old\",\"created_at\":\"2026-10-23T12:00:00\"},"
                    + "{\"uuid\":\"new\",\"created_at\":\"2026-11-22T12:00:00\"}]}";
        });
        var params = Map.of("start_time", "2026-10-23T00:00:00", "end_time", "2026-11-22T15:36:10");
        JsonNode result = Json.MAPPER.valueToTree(new TaskQueryService().list(config, params));
        assertEquals("old", result.at("/items/0/uuid").asText());
        assertEquals("new", result.at("/items/1/uuid").asText());
        assertEquals(2, result.path("total").asLong());
        assertEquals(1, calls.get());
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

    private static String diagnosticPage(Map<String,String> query, int total, int effectiveSize, boolean repeat) {
        int status = Integer.parseInt(query.get("status"));
        int page = Integer.parseInt(query.get("page"));
        int offset = (page - 1) * effectiveSize;
        List<Map<String,Object>> rows = new ArrayList<>();
        for (int index = offset; index < Math.min(offset + effectiveSize, total); index++) {
            int record = repeat ? index % effectiveSize : index;
            rows.add(Map.of("uuid", status + "-record-" + record, "status", status,
                    "task_type", query.getOrDefault("task_type", "flow"), "hash_key", query.getOrDefault("hash_key", "hash"),
                    "workflow_name", "实际工作流", "created_at", "2026-11-04T10:10:00",
                    "updated_at", "2026-11-04T10:11:00", "prompt", "RAW_PROMPT_MUST_NOT_RETURN",
                    "error", "RAW_ERROR_MUST_NOT_RETURN", "error_code", "GEN_TIMEOUT"));
        }
        return Json.MAPPER.valueToTree(Map.of("total_count", total, "page_size", effectiveSize, "tasks", rows)).toString();
    }

    @Test void diagnosticsTraverseAllNonSuccessStatusesAndReturnOnlyFilteredAggregates() throws Exception {
        Set<String> requested = ConcurrentHashMap.newKeySet();
        AtomicInteger calls = new AtomicInteger();
        serve(query -> {
            String status = query.get("status");
            requested.add(status); calls.incrementAndGet();
            assertNotEquals("2", status, "diagnostics must not bulk-fetch successful tasks");
            assertEquals("flow", query.get("task_type"));
            assertEquals("hash", query.get("hash_key"));
            int total = Map.of("-1", 2, "0", 3, "1", 0, "3", 1).get(status);
            return diagnosticPage(query, total, 2, false);
        });
        Map<String,String> params = params();
        params.putAll(Map.of("status", "2", "task_type", "flow", "hash_key", "hash"));
        config.channelNames.put("hash", "渠道甲");
        JsonNode result = Json.MAPPER.valueToTree(new TaskQueryService().diagnostics(config, params));
        JsonNode coverage = result.path("diagnostics").path("coverage");
        assertEquals(Set.of("-1", "0", "1", "3"), requested);
        assertEquals(5, calls.get(), "status zero needs a second page");
        assertTrue(coverage.path("complete").asBoolean());
        assertEquals(6, coverage.path("observedCount").asLong());
        assertEquals(6, coverage.path("expectedCount").asLong());
        assertEquals("flow", result.path("filters").path("taskType").asText());
        assertFalse(result.toString().contains("RAW_PROMPT_MUST_NOT_RETURN"));
        assertFalse(result.toString().contains("RAW_ERROR_MUST_NOT_RETURN"));
        assertFalse(result.has("items"));
    }

    @Test void diagnosticRecordCapIsExplicitAndNeverReportedAsCompleteCoverage() throws Exception {
        serve(query -> diagnosticPage(query, "0".equals(query.get("status")) ? 750 : 0, 200, false));
        JsonNode result = Json.MAPPER.valueToTree(new TaskQueryService().diagnostics(config, params()));
        JsonNode coverage = result.path("diagnostics").path("coverage");
        assertFalse(coverage.path("complete").asBoolean());
        assertTrue(coverage.path("truncated").asBoolean());
        assertEquals(500, coverage.path("observedCount").asLong());
        assertEquals(750, coverage.path("expectedCount").asLong());
        assertFalse(coverage.path("perStatus").path("0").path("complete").asBoolean());
    }

    @Test void failureOnlyDiagnosticsScanLargerPagesAndReportStatusSpecificCap() throws Exception {
        List<String> requests = new CopyOnWriteArrayList<>();
        serve(query -> {
            requests.add(query.get("status") + ":" + query.get("page_size"));
            return diagnosticPage(query, 2400, 800, false);
        });
        Map<String,String> params = params();
        params.put("status", "-1");
        JsonNode diagnostics = Json.MAPPER.valueToTree(new TaskQueryService().diagnostics(config, params)).path("diagnostics");
        assertEquals(List.of("-1:1000", "-1:800", "-1:800"), requests);
        assertEquals("failed_tasks", diagnostics.path("scope").asText());
        assertEquals(1, diagnostics.path("coverage").path("perStatus").size());
        assertEquals(2000, diagnostics.path("coverage").path("maxRecords").asInt());
        JsonNode failures = diagnostics.path("failureBreakdown");
        assertEquals(2400, failures.path("expectedCount").asInt());
        assertEquals(2000, failures.path("observedCount").asInt());
        assertFalse(failures.path("complete").asBoolean());
        assertTrue(failures.path("truncated").asBoolean());
        assertEquals(2000, failures.path("rows").get(0).path("count").asInt());
        assertFalse(diagnostics.toString().contains("RAW_PROMPT_MUST_NOT_RETURN"));
    }

    @Test void diagnosticDeadlinePreservesOtherStatusesAndLabelsMissingCoverage() throws Exception {
        serve(query -> {
            if ("3".equals(query.get("status"))) {
                try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return diagnosticPage(query, "0".equals(query.get("status")) ? 1 : 0, 200, false);
        });
        long started = System.nanoTime();
        JsonNode result = Json.MAPPER.valueToTree(new TaskQueryService(Duration.ofMillis(350)).diagnostics(config, params()));
        assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 1500);
        JsonNode coverage = result.path("diagnostics").path("coverage");
        assertFalse(coverage.path("complete").asBoolean());
        assertEquals(1, coverage.path("observedCount").asLong());
        assertTrue(coverage.path("perStatus").path("0").path("complete").asBoolean());
        assertFalse(coverage.path("perStatus").path("3").path("complete").asBoolean());
    }

    @Test void duplicateDiagnosticPagesCannotBecomeCompleteEvidence() throws Exception {
        serve(query -> diagnosticPage(query, "0".equals(query.get("status")) ? 5 : 0, 2, true));
        JsonNode coverage = Json.MAPPER.valueToTree(new TaskQueryService().diagnostics(config, params()))
                .path("diagnostics").path("coverage");
        assertFalse(coverage.path("complete").asBoolean());
        assertEquals(2, coverage.path("observedCount").asLong());
        assertFalse(coverage.path("perStatus").path("0").path("complete").asBoolean());
    }

    @Test void zeroNonSuccessTasksHaveCompleteEmptyDiagnosticCoverage() throws Exception {
        serve(query -> diagnosticPage(query, 0, 200, false));
        JsonNode coverage = Json.MAPPER.valueToTree(new TaskQueryService().diagnostics(config, params()))
                .path("diagnostics").path("coverage");
        assertTrue(coverage.path("complete").asBoolean());
        assertEquals(0, coverage.path("observedCount").asLong());
        assertEquals(0, coverage.path("expectedCount").asLong());
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
