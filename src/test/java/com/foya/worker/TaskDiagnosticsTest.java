package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskDiagnosticsTest {
    @TempDir Path directory;
    private static final Instant NOW = Instant.parse("2026-11-05T04:00:00Z");

    private static ObjectNode row(String uuid, int status, long ageMinutes, long updateMinutes) {
        return Json.MAPPER.createObjectNode().put("uuid", uuid).put("status", status)
                .put("created_at", NOW.minusSeconds(ageMinutes * 60).toString())
                .put("updated_at", NOW.minusSeconds(updateMinutes * 60).toString());
    }

    private static Map<String,Object> coverage(long expected, boolean complete, long minusOne, long zero, long one, long three) {
        Map<String,Object> statuses = new LinkedHashMap<>();
        for (var entry : Map.of("-1", minusOne, "0", zero, "1", one, "3", three).entrySet())
            statuses.put(entry.getKey(), Map.of("expectedCount", entry.getValue(), "fetchedCount", entry.getValue(), "complete", complete));
        return Map.of("expectedCount", expected, "fetchedCount", expected, "maxRecords", 2000,
                "complete", complete, "truncated", !complete, "perStatus", statuses);
    }

    private static JsonNode summarize(AppConfig config, List<JsonNode> rows, Map<String,Object> coverage) {
        return Json.MAPPER.valueToTree(TaskDiagnostics.summarize(config, rows, NOW, coverage));
    }

    @Test void computesTaskAgeLastUpdateAgeAndConcentrationsUsingObservedNonSuccessDenominator() {
        AppConfig config = new AppConfig();
        config.channelNames.put("hash-a", "渠道 A");
        List<JsonNode> rows = List.of(
                row("a", 0, 120, 90).put("task_type", "image").put("workflow_name", "工作流 A").put("hash_key", "hash-a").put("error_code", "NETWORK_TIMEOUT"),
                row("b", 0, 61, 30).put("task_type", "image").put("workflow_name", "工作流 A").put("hash_key", "hash-a").put("error", "PRIVATE_STACK_TRACE"),
                row("c", -1, 1440, 1430).put("task_type", "video").put("workflow_name", "工作流 B").put("hash_key", "unmapped-private-hash").put("error_code", 42),
                row("d", 1, 10, 5).put("task_type", "image").put("workflow_name", "工作流 A"));
        JsonNode result = summarize(config, rows, coverage(4, true, 1, 2, 1, 0));
        assertTrue(result.path("coverage").path("complete").asBoolean());
        assertEquals(4, result.path("coverage").path("fetchedCount").asInt());
        JsonNode created = result.path("aging").path("created");
        assertEquals(4, created.path("validCount").asInt());
        assertEquals(1440, created.path("oldestMinutes").asDouble());
        assertEquals(90.5, created.path("medianMinutes").asDouble());
        assertEquals(3, created.path("atLeast60Minutes").asInt());
        assertEquals(1, created.path("atLeast1440Minutes").asInt());
        JsonNode updated = result.path("aging").path("updated");
        assertEquals(1430, updated.path("oldestMinutes").asDouble());
        assertEquals(2, updated.path("atLeast60Minutes").asInt());
        JsonNode workflow = result.path("workflowsTop5").get(0);
        assertEquals("工作流 A", workflow.path("workflowName").asText());
        assertEquals("image", workflow.path("taskType").asText());
        assertEquals(3, workflow.path("count").asInt());
        assertEquals(75, workflow.path("shareOfObservedPercent").asDouble());
        assertEquals(2, workflow.path("createdOver60Minutes").asInt());
        assertEquals(1, workflow.path("notUpdatedOver60Minutes").asInt());
        assertEquals("渠道 A", result.path("channelsTop5").get(0).path("channelName").asText());
        assertEquals(2, result.path("structuredErrorCount").asInt());
        assertEquals(1, result.path("errorWithoutStructuredCodeCount").asInt());
        assertFalse(result.toString().contains("PRIVATE_STACK_TRACE"));
        assertFalse(result.toString().contains("unmapped-private-hash"));
    }

    @Test void keepsDifferentRecordUuidsWithSameBusinessIdAndDowngradesDuplicateCoverage() {
        List<JsonNode> rows = List.of(row("a", 0, 30, 10).put("task_id", "business-id"),
                row("b", 0, 30, 10).put("task_id", "business-id"), row("a", 0, 30, 10));
        JsonNode result = summarize(new AppConfig(), rows, coverage(2, true, 0, 2, 0, 0));
        assertEquals(2, result.path("coverage").path("observedCount").asInt());
        assertEquals(3, result.path("coverage").path("receivedCount").asInt());
        assertEquals(1, result.path("coverage").path("duplicateCount").asInt());
        assertFalse(result.path("coverage").path("complete").asBoolean());
    }

    @Test void invalidMissingFutureAndReversedTimestampsNeverBecomeZeroMinuteObservations() {
        List<JsonNode> rows = List.of(
                Json.MAPPER.createObjectNode().put("uuid", "a").put("status", 0).put("created_at", "invalid-date"),
                row("b", 0, -1, -1),
                row("c", 0, 60, 90));
        JsonNode result = summarize(new AppConfig(), rows, coverage(3, true, 0, 3, 0, 0));
        JsonNode created = result.path("aging").path("created"), updated = result.path("aging").path("updated");
        assertEquals(1, created.path("validCount").asInt());
        assertEquals(1, created.path("missingCount").asInt());
        assertEquals(1, created.path("futureCount").asInt());
        assertEquals(60, created.path("oldestMinutes").asDouble());
        assertEquals(0, updated.path("validCount").asInt());
        assertEquals(1, updated.path("beforeCreationCount").asInt());
        assertTrue(updated.path("oldestMinutes").isNull());
        assertTrue(updated.path("medianMinutes").isNull());
    }

    @Test void onlyWhitelistedAggregatesLeaveTaskPayloadAndConfiguredSecretsBehind() throws Exception {
        AppConfig config = new AppConfig();
        Path key = directory.resolve("p0.key");
        Files.writeString(key, "PRIVATE_FILE_KEY");
        config.p0ApiKeyFile = key.toString(); config.llmApiKey = "PRIVATE_LLM_KEY";
        config.channelNames.put("PRIVATE_CHANNEL_HASH", "渠道 PRIVATE_LLM_KEY");
        ObjectNode row = row("PRIVATE_RECORD_UUID", 0, 120, 90).put("task_type", "type").put("workflow_name", "PRIVATE_FILE_KEY workflow https://private.example?token=PRIVATE_URL_TOKEN")
                .put("hash_key", "PRIVATE_CHANNEL_HASH").put("user_id", "PRIVATE_USER_ID").put("prompt", "PRIVATE_PROMPT")
                .put("error", "PRIVATE_ERROR_STACK").put("error_code", "https://private.example/PRIVATE_ERROR_CODE");
        JsonNode result = summarize(config, List.of(row), coverage(1, true, 0, 1, 0, 0));
        String json = result.toString();
        for (String privateValue : List.of("PRIVATE_FILE_KEY", "PRIVATE_LLM_KEY", "PRIVATE_CHANNEL_HASH", "PRIVATE_RECORD_UUID", "PRIVATE_URL_TOKEN", "private.example", "PRIVATE_USER_ID", "PRIVATE_PROMPT", "PRIVATE_ERROR_STACK", "PRIVATE_ERROR_CODE"))
            assertFalse(json.contains(privateValue), privateValue);
        assertEquals(0, result.path("structuredErrorCount").asInt());
        assertEquals(1, result.path("errorWithoutStructuredCodeCount").asInt());
    }

    @Test void successMissingAndUnknownStatusesAreExcludedAndNeverClaimFullCoverage() {
        List<JsonNode> rows = List.of(row("a", 2, 60, 10), row("b", 8, 60, 10),
                Json.MAPPER.createObjectNode().put("uuid", "c"), row("d", 0, 60, 10));
        JsonNode result = summarize(new AppConfig(), rows, coverage(1, true, 0, 1, 0, 0));
        assertEquals(1, result.path("coverage").path("observedCount").asInt());
        assertEquals(1, result.path("coverage").path("excludedSuccessCount").asInt());
        assertEquals(2, result.path("coverage").path("invalidStatusCount").asInt());
        assertFalse(result.path("coverage").path("complete").asBoolean());
    }

    @Test void boundedSamplePreservesItsOwnDenominatorAndNeverExtrapolatesToExpectedTotal() {
        List<JsonNode> rows = new ArrayList<>();
        for (int i = 0; i < 2001; i++) rows.add(row("row-" + i, 0, 30, 10).put("task_type", "sample-type"));
        JsonNode result = summarize(new AppConfig(), rows, coverage(10000, false, 0, 10000, 0, 0));
        assertEquals(2000, result.path("coverage").path("observedCount").asInt());
        assertEquals(10000, result.path("coverage").path("expectedCount").asInt());
        assertTrue(result.path("coverage").path("truncated").asBoolean());
        assertFalse(result.path("coverage").path("complete").asBoolean());
        assertEquals(2000, result.path("workflowsTop5").get(0).path("count").asInt());
        assertEquals(100, result.path("workflowsTop5").get(0).path("shareOfObservedPercent").asDouble());
    }

    @Test void zeroObservationsRemainExplicitlyEmptyAndAnonymousChannelLabelsAreStable() {
        JsonNode empty = summarize(new AppConfig(), List.of(), coverage(0, true, 0, 0, 0, 0));
        assertTrue(empty.path("coverage").path("complete").asBoolean());
        assertTrue(empty.path("aging").path("created").path("oldestMinutes").isNull());
        assertEquals(0, empty.path("workflowsTop5").size());
        List<JsonNode> rows = List.of(row("a", 0, 10, 5).put("hash_key", "raw-channel-identifier"));
        JsonNode first = summarize(new AppConfig(), rows, coverage(1, true, 0, 1, 0, 0));
        JsonNode second = summarize(new AppConfig(), rows, coverage(1, true, 0, 1, 0, 0));
        String label = first.path("channelsTop5").get(0).path("channelName").asText();
        assertTrue(label.startsWith("未映射渠道·"));
        assertEquals(label, second.path("channelsTop5").get(0).path("channelName").asText());
        assertFalse(first.toString().contains("raw-channel-identifier"));
    }

    @Test void absentErrorCodesAreCaseInsensitiveWithoutRemovingRealCodes() {
        List<JsonNode> rows = List.of(row("a", 0, 20, 10).put("error_code", "None"),
                row("b", 0, 20, 10).put("error_code", "False"),
                row("c", 0, 20, 10).put("error_code", "NULL"),
                row("d", 0, 20, 10).put("error_code", "NoNe").put("error", "PRIVATE_ERROR_TEXT"),
                row("e", 0, 20, 10).put("error_code", "None_node"));
        JsonNode result = summarize(new AppConfig(), rows, coverage(5, true, 0, 5, 0, 0));
        assertEquals(1, result.path("structuredErrorCount").asInt());
        assertEquals(1, result.path("errorWithoutStructuredCodeCount").asInt());
        assertEquals(1, result.path("errorCodesTop5").size());
        assertEquals("None_node", result.path("errorCodesTop5").get(0).path("code").asText());
        assertFalse(result.toString().contains("PRIVATE_ERROR_TEXT"));
    }
}
