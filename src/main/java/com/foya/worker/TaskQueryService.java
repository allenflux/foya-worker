package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Interactive, read-only P0 queries; its deadline and retries are independent of batch pulls. */
public final class TaskQueryService {
    static final List<Integer> STATUSES = List.of(-1, 0, 1, 2, 3);
    private static final Pattern SENSITIVE_FIELD = Pattern.compile(".*(apikey|api_key|token|authorization|password|secret|credential|cookie).*", Pattern.CASE_INSENSITIVE);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final Duration budget;

    public TaskQueryService() { this(Duration.ofSeconds(20)); }
    TaskQueryService(Duration budget) { this.budget = budget; }

    record Window(ZonedDateTime start, ZonedDateTime end, Map<String, String> filters) {}
    record Page(List<JsonNode> rows, Long total, Boolean hasMore, int pageSize) {}

    public Map<String, Object> list(AppConfig config, Map<String, String> params) throws Exception {
        long deadline = System.nanoTime() + budget.toNanos();
        Window window = window(config, params);
        Integer status = status(params.get("status"));
        int page = integer(params.get("page"), 1, 1, 100_000, "页码");
        int pageSize = integer(params.get("page_size"), 50, 1, 200, "每页条数");
        String apiKey = P0Client.resolveApiKey(config);
        Page result = request(config, apiKey, window, status, page, pageSize, deadline);
        long endOffset = (long) (page - 1) * result.pageSize() + result.rows().size();
        if (result.total() != null && (Boolean.FALSE.equals(result.hasMore()) && endOffset < result.total()
                || Boolean.TRUE.equals(result.hasMore()) && endOffset >= result.total()))
            throw new IOException("p0 返回的分页总数与后续页标识冲突。");
        List<String> secrets = secrets(config, apiKey);
        List<ObjectNode> rows = new ArrayList<>();
        for (JsonNode raw : result.rows()) {
            ObjectNode row = (ObjectNode) sanitize(raw, secrets);
            String hash = text(row, "hash_key", "hashKey");
            String type = text(row, "task_type", "taskType", "type");
            row.put("channelName", hash.isBlank() ? "未分類" : config.channelNames.getOrDefault(hash, hash));
            row.put("workflowName", config.workflowNames.getOrDefault(type,
                    text(row, "workflow_name").isBlank() ? type : text(row, "workflow_name")));
            rows.add(row);
        }
        Map<String, Object> response = base(config, window);
        response.put("items", rows);
        response.put("page", page);
        response.put("pageSize", result.pageSize());
        response.put("total", result.total());
        response.put("hasMore", result.hasMore() != null ? result.hasMore()
                : result.total() != null ? (long) (page - 1) * result.pageSize() + rows.size() < result.total() : !rows.isEmpty());
        return response;
    }

    public Map<String, Object> statistics(AppConfig config, Map<String, String> params) throws Exception {
        long deadline = System.nanoTime() + budget.toNanos();
        Window window = window(config, params);
        String apiKey = P0Client.resolveApiKey(config);
        Map<Integer, Future<Long>> pending = new LinkedHashMap<>();
        Future<Long> totalFuture = null;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                totalFuture = pool.submit(() -> count(config, apiKey, window, null, deadline));
                for (int value : STATUSES) pending.put(value, pool.submit(() -> count(config, apiKey, window, value, deadline)));
                long total = await(totalFuture, deadline);
                Map<String, Long> counts = new LinkedHashMap<>();
                Map<String, String> errors = new LinkedHashMap<>();
                long classified = 0;
                for (var entry : pending.entrySet()) {
                    try {
                        long value = await(entry.getValue(), deadline);
                        counts.put(entry.getKey().toString(), value);
                        classified = Math.addExact(classified, value);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw e;
                    } catch (Exception e) {
                        errors.put(entry.getKey().toString(), "该状态统计未完成，请重试或缩短查询时间段。");
                    }
                }
                boolean complete = errors.isEmpty();
                Map<String, Object> response = base(config, window);
                response.put("scope", "interval");
                response.put("total", total);
                response.put("counts", counts);
                response.put("complete", complete);
                response.put("consistent", complete && classified == total);
                // The six queries are not an atomic snapshot. A difference can reflect
                // status transitions while reading; it never denotes an unknown status.
                response.put("snapshotDifference", complete ? total - classified : null);
                response.put("errors", errors);
                return response;
            } finally {
                if (totalFuture != null) totalFuture.cancel(true);
                pending.values().forEach(f -> f.cancel(true));
            }
        }
    }

    private long count(AppConfig config, String key, Window window, Integer status, long deadline) throws Exception {
        Page first = request(config, key, window, status, 1, 1, deadline);
        if (first.total() != null) return first.total();
        if (first.rows().isEmpty() || Boolean.FALSE.equals(first.hasMore())) return first.rows().size();
        // A page length is never presented as the interval total. Without total metadata,
        // make a bounded, complete traversal and reject repeated pages or unknown completion.
        long count = 0;
        Set<String> signatures = new HashSet<>();
        Set<String> recordIds = new HashSet<>();
        for (int page = 1; page <= 100; page++) {
            Page result = request(config, key, window, status, page, 1000, deadline);
            List<String> signature = result.rows().stream().map(JsonNode::toString).sorted().toList();
            if (!result.rows().isEmpty() && !signatures.add(signature.toString()))
                throw new IOException("p0 返回重复任务页，无法准确统计。");
            for (JsonNode row : result.rows()) {
                String id = text(row, "uuid", "id");
                if (!id.isBlank() && !recordIds.add(id)) throw new IOException("p0 分页返回重复任务记录，无法准确统计。");
            }
            count += result.rows().size();
            if (result.total() != null) {
                if (count > result.total()) throw new IOException("p0 分页记录数超过总数，无法准确统计。");
                return result.total();
            }
            if (Boolean.FALSE.equals(result.hasMore()) || result.rows().isEmpty()) {
                if (Boolean.TRUE.equals(result.hasMore())) throw new IOException("p0 分页为空但仍声明有后续记录。");
                return count;
            }
        }
        throw new IOException("p0 未提供总数且完整统计超过安全上限，请缩短查询时间段。");
    }

    private Page request(AppConfig config, String key, Window window, Integer status, int page, int size, long deadline) throws Exception {
        String query = "start_time=" + encode(P0Client.format(window.start(), config.p0TimeFormat))
                + "&end_time=" + encode(P0Client.format(window.end(), config.p0TimeFormat))
                + "&page=" + page + "&page_size=" + size + (status == null ? "" : "&status=" + status);
        for (var filter : window.filters().entrySet()) query += "&" + filter.getKey() + "=" + encode(filter.getValue());
        URI uri = URI.create(config.p0Endpoint + "?" + query);
        for (int attempt = 0; attempt < 2; attempt++) {
            Duration remaining = remaining(deadline);
            Duration timeout = remaining.compareTo(Duration.ofSeconds(10)) < 0 ? remaining : Duration.ofSeconds(10);
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET().timeout(timeout)
                    .header("canary", "true").header("Accept", "application/json");
            if (!key.isBlank()) builder.header("apikey", key);
            CompletableFuture<HttpResponse<String>> future = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            HttpResponse<String> response;
            try { response = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
            catch (TimeoutException | ExecutionException e) {
                if (attempt == 0) continue;
                throw new IOException("P0 查询超时或网络异常（总时限 " + budget.toSeconds() + " 秒），请缩短时间段后重试。");
            } finally { if (!future.isDone()) future.cancel(true); }
            int code = response.statusCode();
            if ((code == 408 || code == 429 || code >= 500) && attempt == 0) {
                long delay = P0Client.retryDelayMillis(0, response.headers().firstValue("Retry-After").orElse(null), Instant.now());
                if (delay >= remaining(deadline).toMillis()) throw new IOException("P0 请求限流或暂时不可用，请稍后重试。");
                Thread.sleep(delay);
                continue;
            }
            if (code < 200 || code >= 300) throw new IOException("P0 查询失败：HTTP " + code + "，请检查数据源配置或稍后重试。");
            JsonNode root;
            try { root = Json.MAPPER.readTree(response.body()); }
            catch (com.fasterxml.jackson.core.JacksonException e) { throw new IOException("p0 返回无效 JSON。"); }
            Page decoded = decode(root, size);
            JsonNode data = root.hasNonNull("data") ? root.path("data") : root;
            if (page == 1 && !data.hasNonNull("page_size") && !root.hasNonNull("page_size")
                    && !decoded.rows().isEmpty() && decoded.rows().size() < size
                    && decoded.total() != null && decoded.total() > decoded.rows().size()) {
                // Some compatible APIs cap page_size without echoing the effective limit.
                // Return the observed first-page size so subsequent requests use that size
                // for both offsets and navigation rather than stopping after too few pages.
                decoded = new Page(decoded.rows(), decoded.total(), decoded.hasMore(), decoded.rows().size());
            }
            if (page == 1 && decoded.total() != null && decoded.total() > 0 && decoded.rows().isEmpty())
                throw new IOException("p0 首页面为空但总数非零，无法核实统计范围。");
            for (JsonNode row : decoded.rows()) {
                if (status != null && row.hasNonNull("status") && !status.toString().equals(row.path("status").asText()))
                    throw new IOException("p0 返回任务的状态与筛选条件不一致，已停止查询。");
                for (var filter : window.filters().entrySet())
                    if (row.hasNonNull(filter.getKey()) && !filter.getValue().equals(row.path(filter.getKey()).asText()))
                        throw new IOException("p0 返回任务与筛选条件不一致，已停止查询。");
                String created = text(row, "created_at", "create_time", "created_time", "createdAt", "createTime", "createdTime");
                if (!created.isBlank()) {
                    Instant time = P0Client.parseTime(created, window.start().getZone());
                    if (time.isBefore(window.start().toInstant()) || time.isAfter(window.end().toInstant()))
                        throw new IOException("p0 返回任务超出查询时间范围，请检查数据源时间格式。");
                }
            }
            return decoded;
        }
        throw new IOException("P0 查询失败，请稍后重试。");
    }

    static Page decode(JsonNode root, int requestedSize) throws IOException {
        if (root == null || root.isNull()) throw new IOException("p0 响应为空。");
        if (root.isObject()) {
            if (root.hasNonNull("code") && !Set.of("0", "200", "OK", "ok", "success").contains(root.path("code").asText())
                    || root.has("success") && !root.path("success").asBoolean()
                    || root.hasNonNull("detail")) throw new IOException("p0 返回业务错误。");
            if (root.has("status") && (root.path("status").isBoolean() && !root.path("status").asBoolean()
                    || Set.of("error", "fail", "failed", "false").contains(root.path("status").asText().toLowerCase(Locale.ROOT))))
                throw new IOException("p0 返回失败状态。");
            if (root.hasNonNull("error") && (root.path("error").isContainerNode()
                    || !Set.of("", "false", "0").contains(root.path("error").asText()))) throw new IOException("p0 返回错误。");
        }
        JsonNode data = root.hasNonNull("data") ? root.path("data") : root;
        JsonNode array = data.isArray() ? data : null;
        if (array == null) for (String name : List.of("list", "tasks", "items", "records", "rows"))
            if (data.path(name).isArray()) { array = data.path(name); break; }
        if (array == null) throw new IOException("p0 响应中找不到任务数组。");
        List<JsonNode> rows = new ArrayList<>();
        for (JsonNode row : array) {
            if (!row.isObject()) throw new IOException("p0 任务记录应为对象。");
            rows.add(row);
        }
        Long total = P0Client.longMeta(data, root, "total", "total_count", "totalCount");
        Boolean more = P0Client.boolMeta(data, root, "has_more", "hasMore");
        if (total != null && total < rows.size()) throw new IOException("p0 分页数量大于总数。");
        if (Boolean.TRUE.equals(more) && rows.isEmpty()) throw new IOException("p0 分页为空但声明有后续记录。");
        int size = requestedSize;
        for (JsonNode node : List.of(data, root)) if (node.hasNonNull("page_size")) {
            try { size = Integer.parseInt(node.path("page_size").asText()); }
            catch (NumberFormatException e) { throw new IOException("p0 返回无效每页条数。"); }
            if (size < 1 || size > 1000) throw new IOException("p0 返回无效每页条数。");
        }
        return new Page(rows, total, more, size);
    }

    static Window window(AppConfig config, Map<String, String> params) {
        ZoneId zone = ZoneId.of(config.timezone);
        try {
            LocalDateTime start = LocalDateTime.parse(Objects.toString(params.get("start_time"), ""));
            LocalDateTime end = LocalDateTime.parse(Objects.toString(params.get("end_time"), ""));
            if (start.getNano() % 1000 != 0 || end.getNano() % 1000 != 0) throw new IllegalArgumentException("查询时间最多支持 6 位小数秒。");
            ZonedDateTime from = strictTime(start, zone), to = strictTime(end, zone);
            if (to.isBefore(from)) throw new IllegalArgumentException("结束时间不能早于开始时间。");
            if (Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0) throw new IllegalArgumentException("单次查询时间段最多 31 天。");
            Map<String, String> filters = new LinkedHashMap<>();
            for (String name : List.of("task_type", "hash_key", "task_id")) {
                String value = Objects.toString(params.get(name), "").trim();
                if (value.length() > 256 || value.matches("(?s).*[\\p{Cc}].*")) throw new IllegalArgumentException("任务筛选条件最多 256 字符且不能含控制字符。");
                if (!value.isBlank()) filters.put(name, value);
            }
            return new Window(from, to, filters);
        } catch (DateTimeException e) { throw new IllegalArgumentException("请提供有效的起止时间，格式为 yyyy-MM-ddTHH:mm:ss。"); }
    }

    private static ZonedDateTime strictTime(LocalDateTime local, ZoneId zone) {
        var offsets = zone.getRules().getValidOffsets(local);
        if (offsets.size() != 1) throw new IllegalArgumentException("该时区的查询时间存在夏令时歧义，请选择其他时间。");
        return ZonedDateTime.ofStrict(local, offsets.get(0), zone);
    }
    private static Integer status(String value) {
        if (value == null || value.isBlank()) return null;
        int status = integer(value, 0, -1, 3, "任务状态");
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("任务状态应为 -1、0、1、2 或 3。");
        return status;
    }
    private static int integer(String value, int fallback, int min, int max, String label) {
        if (value == null || value.isBlank()) return fallback;
        try { int number = Integer.parseInt(value); if (number < min || number > max) throw new NumberFormatException(); return number; }
        catch (NumberFormatException e) { throw new IllegalArgumentException(label + "范围应为 " + min + "–" + max + "。"); }
    }
    private static Map<String, Object> base(AppConfig config, Window window) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timezone", config.timezone);
        data.put("startTime", P0Client.format(window.start(), "datetime"));
        data.put("endTime", P0Client.format(window.end(), "datetime"));
        data.put("queriedAt", Instant.now().toString());
        data.put("filters", Map.of("taskType", window.filters().getOrDefault("task_type", ""),
                "hashKey", window.filters().getOrDefault("hash_key", ""), "taskId", window.filters().getOrDefault("task_id", "")));
        return data;
    }
    private static Duration remaining(long deadline) throws IOException {
        long nanos = deadline - System.nanoTime();
        if (nanos <= 0) throw new IOException("P0 查询超过总时限，请缩短时间段后重试。");
        return Duration.ofNanos(nanos);
    }
    private static <T> T await(Future<T> future, long deadline) throws Exception {
        try {
            // Completed status counts remain usable even when another status used up
            // the shared deadline. Never convert an unfinished count to zero.
            return future.isDone() ? future.get() : future.get(remaining(deadline).toNanos(), TimeUnit.NANOSECONDS);
        }
        catch (TimeoutException e) { throw new IOException("P0 统计超过总时限，请缩短时间段后重试。"); }
        catch (ExecutionException e) { if (e.getCause() instanceof Exception cause) throw cause; throw new IOException("P0 统计失败。"); }
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String text(JsonNode row, String... names) {
        for (String name : names) if (row.hasNonNull(name) && row.path(name).isValueNode() && !row.path(name).asText().isBlank()) return row.path(name).asText();
        return "";
    }
    private static List<String> secrets(AppConfig config, String apiKey) {
        List<String> values = new ArrayList<>(List.of(apiKey, config.p0ApiKey, config.telegramBotToken, config.llmApiKey));
        config.telegramBots.forEach(bot -> values.add(bot.token));
        return values.stream().filter(s -> s != null && !s.isBlank()).toList();
    }
    private static JsonNode sanitize(JsonNode node, List<String> secrets) {
        if (node.isObject()) {
            ObjectNode result = Json.MAPPER.createObjectNode();
            node.fields().forEachRemaining(field -> result.set(redact(field.getKey(), secrets),
                    SENSITIVE_FIELD.matcher(field.getKey()).matches() ? TextNode.valueOf("[已隐藏]") : sanitize(field.getValue(), secrets)));
            return result;
        }
        if (node.isArray()) { ArrayNode array = Json.MAPPER.createArrayNode(); node.forEach(value -> array.add(sanitize(value, secrets))); return array; }
        return node.isTextual() ? TextNode.valueOf(redact(node.asText(), secrets)) : node.deepCopy();
    }
    private static String redact(String text, List<String> secrets) {
        for (String secret : secrets) text = text.replace(secret, "[已隐藏]");
        return text.replaceAll("(?i)((?:apikey|api_key|token|password|secret|authorization)[\\s\"']*[:=][\\s\"']*)[^\\s,;&\"']+", "$1[已隐藏]");
    }
}
