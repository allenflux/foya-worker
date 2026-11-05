package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Aggregate-only observations of non-success task snapshots, never task payloads. */
public final class TaskDiagnostics {
    private static final List<String> STATUSES = List.of("-1", "0", "1", "3");
    private static final int MAX_RECORDS = 2000;
    private TaskDiagnostics() {}

    public static Map<String,Object> summarize(AppConfig config, List<JsonNode> rows, Instant queriedAt,
                                                Map<String,Object> sourceCoverage) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(queriedAt);
        List<JsonNode> input = rows == null ? List.of() : rows;
        ZoneId zone = ZoneId.of(config.timezone);
        List<String> secrets = secrets(config);
        Set<String> identities = new HashSet<>();
        Map<String,Group> statuses = new LinkedHashMap<>();
        STATUSES.forEach(status -> statuses.put(status, new Group()));
        Map<Workflow,Group> workflows = new LinkedHashMap<>();
        Map<String,Group> channels = new LinkedHashMap<>();
        Map<String,Long> codes = new HashMap<>();
        Ages createdAges = new Ages(), updatedAges = new Ages();
        long duplicateCount = 0, invalidStatusCount = 0, excludedSuccessCount = 0;
        long observed = 0, structuredErrorCount = 0, errorWithoutCodeCount = 0;
        for (JsonNode row : input.subList(0, Math.min(input.size(), MAX_RECORDS))) {
            if (row == null || !row.isObject()) { invalidStatusCount++; continue; }
            String status = text(row, "status", "task_status", "taskStatus");
            if (status.equals("2")) { excludedSuccessCount++; continue; }
            if (!STATUSES.contains(status)) { invalidStatusCount++; continue; }
            // A business task_id may repeat. Only a record UUID/id establishes identity.
            String identity = text(row, "uuid", "id");
            if (!identity.isEmpty() && !identities.add(identity)) { duplicateCount++; continue; }
            observed++;
            Instant created = time(row, zone, "created_at", "createdAt", "create_time", "createTime", "created_time");
            Instant updated = time(row, zone, "updated_at", "updatedAt", "update_time", "updateTime");
            Double createdMinutes = createdAges.add(created, queriedAt, false);
            boolean beforeCreation = created != null && updated != null && updated.isBefore(created);
            Double updatedMinutes = updatedAges.add(updated, queriedAt, beforeCreation);
            statuses.get(status).add(createdMinutes, updatedMinutes);
            String taskType = label(text(row, "task_type", "taskType", "type"), secrets, "未提供类型");
            String workflowName = text(row, "workflow_name", "workflowName");
            if (workflowName.isBlank()) workflowName = Objects.toString(config.workflowNames.get(text(row, "task_type", "taskType", "type")), "");
            Workflow workflow = new Workflow(label(workflowName, secrets, "未提供名称"), taskType);
            workflows.computeIfAbsent(workflow, ignored -> new Group()).add(createdMinutes, updatedMinutes);
            String hash = text(row, "hash_key", "hashKey");
            String mapped = Objects.toString(config.channelNames.get(hash), "");
            String channel = hash.isBlank() ? "未分類" : mapped.isBlank() || mapped.equals(hash)
                    ? "未映射渠道·" + fingerprint(hash) : label(mapped, secrets, "未分類");
            channels.computeIfAbsent(channel, ignored -> new Group()).add(createdMinutes, updatedMinutes);
            String code = errorCode(row, secrets);
            if (!code.isBlank()) { codes.merge(code, 1L, Long::sum); structuredErrorCount++; }
            else if (hasError(row)) errorWithoutCodeCount++;
        }
        JsonNode original = Json.MAPPER.valueToTree(sourceCoverage == null ? Map.of() : sourceCoverage);
        Map<String,Object> coverage = coverage(original, observed, statuses, duplicateCount, invalidStatusCount,
                excludedSuccessCount, input.size() > MAX_RECORDS);
        coverage.put("fetchedCount", observed);
        coverage.put("receivedCount", input.size());
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("asOf", queriedAt.toString());
        result.put("scope", "non_success_tasks");
        result.put("coverage", coverage);
        result.put("aging", Map.of("created", createdAges.json(), "updated", updatedAges.json()));
        List<Map<String,Object>> statusRows = new ArrayList<>();
        for (var entry : statuses.entrySet()) {
            Map<String,Object> item = entry.getValue().json(observed);
            item.put("status", entry.getKey()); statusRows.add(item);
        }
        result.put("statusBreakdown", statusRows);
        result.put("workflowsTop5", workflowRows(workflows, observed, false));
        result.put("workflowsByAgingTop5", workflowRows(workflows, observed, true));
        List<Map<String,Object>> channelRows = new ArrayList<>();
        channels.entrySet().stream().sorted(groupOrder(false)).limit(5).forEach(entry -> {
            Map<String,Object> item = entry.getValue().json((long) coverage.get("observedCount"));
            item.put("channelName", entry.getKey()); channelRows.add(item);
        });
        result.put("channelsTop5", channelRows);
        List<Map<String,Object>> errorRows = new ArrayList<>();
        long denominator = observed;
        codes.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(5).forEach(entry -> errorRows.add(Map.of("code", entry.getKey(), "count", entry.getValue(),
                        "shareOfObservedPercent", percent(entry.getValue(), denominator))));
        result.put("errorCodesTop5", errorRows);
        result.put("structuredErrorCount", structuredErrorCount);
        result.put("errorWithoutStructuredCodeCount", errorWithoutCodeCount);
        return result;
    }

    private static Map<String,Object> coverage(JsonNode original, long observed, Map<String,Group> statuses,
                                                long duplicates, long invalid, long excluded, boolean capped) {
        Map<String,Object> result = new LinkedHashMap<>();
        Long expected = nonNegative(original.path("expectedCount"));
        boolean complete = original.path("complete").asBoolean(false) && expected != null && observed == expected
                && duplicates == 0 && invalid == 0 && excluded == 0 && !capped && !original.path("truncated").asBoolean(false);
        Map<String,Object> perStatus = new LinkedHashMap<>();
        for (String status : STATUSES) {
            JsonNode raw = original.path("perStatus").path(status);
            Long statusExpected = nonNegative(raw.path("expectedCount"));
            long count = statuses.get(status).count;
            boolean failed = raw.hasNonNull("error") || raw.path("requestFailed").asBoolean(false);
            boolean done = raw.path("complete").asBoolean(false) && !failed && statusExpected != null && count == statusExpected;
            complete &= done;
            Map<String,Object> item = new LinkedHashMap<>();
            item.put("observedCount", count); item.put("expectedCount", statusExpected);
            item.put("complete", done); item.put("requestFailed", failed);
            item.put("truncated", raw.path("truncated").asBoolean(false));
            perStatus.put(status, item);
        }
        result.put("complete", complete);
        result.put("observedCount", observed);
        result.put("expectedCount", expected);
        result.put("maxRecords", Math.min(MAX_RECORDS, Optional.ofNullable(nonNegative(original.path("maxRecords"))).orElse((long) MAX_RECORDS)));
        result.put("truncated", capped || original.path("truncated").asBoolean(false));
        result.put("duplicateCount", duplicates); result.put("invalidStatusCount", invalid);
        result.put("excludedSuccessCount", excluded); result.put("perStatus", perStatus);
        return result;
    }

    private record Workflow(String name, String type) {}
    private static List<Map<String,Object>> workflowRows(Map<Workflow,Group> groups, long total, boolean aging) {
        List<Map<String,Object>> rows = new ArrayList<>();
        groups.entrySet().stream().sorted(groupOrder(aging)).limit(5).forEach(entry -> {
            Map<String,Object> item = entry.getValue().json(total);
            item.put("workflowName", entry.getKey().name()); item.put("taskType", entry.getKey().type()); rows.add(item);
        });
        return rows;
    }
    private static <T> Comparator<Map.Entry<T,Group>> groupOrder(boolean aging) {
        Comparator<Map.Entry<T,Group>> order = aging
                ? Comparator.<Map.Entry<T,Group>>comparingLong(entry -> entry.getValue().notUpdatedOver60).reversed()
                    .thenComparing(Comparator.comparingLong((Map.Entry<T,Group> entry) -> entry.getValue().createdOver60).reversed())
                : Comparator.<Map.Entry<T,Group>>comparingLong(entry -> entry.getValue().count).reversed();
        return order.thenComparing(Comparator.comparingLong((Map.Entry<T,Group> entry) -> entry.getValue().count).reversed())
                .thenComparing(entry -> entry.getKey().toString());
    }
    private static final class Group {
        long count, createdOver60, notUpdatedOver60;
        Double oldest;
        void add(Double created, Double updated) {
            count++;
            if (created != null) { if (created >= 60) createdOver60++; oldest = oldest == null ? created : Math.max(oldest, created); }
            if (updated != null && updated >= 60) notUpdatedOver60++;
        }
        Map<String,Object> json(long total) {
            Map<String,Object> value = new LinkedHashMap<>();
            value.put("count", count); value.put("shareOfObservedPercent", percent(count, total));
            value.put("createdOver60Minutes", createdOver60); value.put("notUpdatedOver60Minutes", notUpdatedOver60);
            value.put("oldestAgeMinutes", rounded(oldest));
            return value;
        }
    }
    private static final class Ages {
        long missing, future, beforeCreation;
        final List<Double> valid = new ArrayList<>();
        Double add(Instant value, Instant now, boolean before) {
            if (value == null) { missing++; return null; }
            if (value.isAfter(now)) { future++; return null; }
            if (before) { beforeCreation++; return null; }
            Duration duration = Duration.between(value, now);
            double minutes = duration.getSeconds() / 60d + duration.getNano() / 60_000_000_000d;
            valid.add(minutes); return minutes;
        }
        Map<String,Object> json() {
            valid.sort(Double::compareTo);
            Map<String,Object> value = new LinkedHashMap<>();
            value.put("validCount", valid.size()); value.put("missingCount", missing); value.put("futureCount", future);
            value.put("beforeCreationCount", beforeCreation);
            value.put("oldestMinutes", valid.isEmpty() ? null : rounded(valid.getLast()));
            value.put("medianMinutes", valid.isEmpty() ? null : rounded(valid.size() % 2 == 1 ? valid.get(valid.size()/2)
                    : (valid.get(valid.size()/2 - 1) + valid.get(valid.size()/2)) / 2));
            for (int threshold : List.of(15, 60, 360, 1440))
                value.put("atLeast" + threshold + "Minutes", valid.stream().filter(minutes -> minutes >= threshold).count());
            return value;
        }
    }
    private static BigDecimal percent(long count, long total) {
        return total == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(count).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }
    private static BigDecimal rounded(Double number) {
        return number == null ? null : BigDecimal.valueOf(number).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }
    private static Long nonNegative(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToLong() && value.longValue() >= 0 ? value.longValue() : null;
    }
    private static Instant time(JsonNode row, ZoneId zone, String... names) {
        String value = text(row, names);
        try { return value.isBlank() ? null : P0Client.parseTime(value, zone); }
        catch (Exception e) { return null; }
    }
    private static String text(JsonNode row, String... names) {
        for (String name : names) {
            JsonNode value = row.path(name);
            if (value.isValueNode() && !value.isNull() && !value.isBoolean() && !value.asText().isBlank()) return value.asText().trim();
        }
        return "";
    }
    private static List<String> secrets(AppConfig config) {
        List<String> values = new ArrayList<>();
        values.add(config.p0ApiKey); values.add(config.llmApiKey); values.add(config.telegramBotToken);
        if (config.telegramBots != null) config.telegramBots.forEach(bot -> { if (bot != null) values.add(bot.token); });
        try { values.add(P0Client.resolveApiKey(config)); } catch (Exception ignored) {}
        return values.stream().filter(value -> value != null && !value.isBlank()).toList();
    }
    private static String label(String value, List<String> secrets, String fallback) {
        String clean = value;
        for (String secret : secrets) clean = clean.replace(secret, "[已隐藏]");
        clean = clean.replaceAll("(?i)https?://\\S+", "[链接已隐藏]")
                .replaceAll("(?i)((?:apikey|api_key|token|password|secret|authorization)[\\s\"']*[:=][\\s\"']*)[^\\s,;&\"']+", "$1[已隐藏]")
                .replaceAll("[\\p{Cc}]", " ").strip();
        if (clean.isBlank()) return fallback;
        return clean.length() <= 180 ? clean : clean.substring(0, 179) + "…";
    }
    private static String errorCode(JsonNode row, List<String> secrets) {
        String code = text(row, "error_code", "errorCode");
        if (code.isBlank() && row.path("error").isObject()) code = text(row.path("error"), "code", "error_code");
        if (code.isBlank() || Set.of("0", "none", "null", "false").contains(code.toLowerCase(Locale.ROOT))) return "";
        for (String secret : secrets) if (code.contains(secret)) return "";
        // Only structured machine codes pass; provider messages, URLs and stack traces do not.
        return code.matches("[A-Za-z][A-Za-z0-9_.-]{0,63}|-?[0-9]{1,12}") ? code : "";
    }
    private static boolean hasError(JsonNode row) {
        for (String name : List.of("error", "error_message", "errorMessage", "error_code", "errorCode")) {
            JsonNode value = row.path(name);
            if (value.isMissingNode() || value.isNull() || value.isBoolean() && !value.booleanValue()) continue;
            if (value.isContainerNode() && value.isEmpty()) continue;
            if (value.isValueNode() && Set.of("", "0", "false", "null", "none").contains(value.asText().trim().toLowerCase(Locale.ROOT))) continue;
            return true;
        }
        return false;
    }
    private static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 12); }
        catch (Exception e) { throw new IllegalStateException("无法生成匿名渠道标识"); }
    }
}
