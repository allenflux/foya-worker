package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Read-only client. The only upstream operation implemented is GET /api/tasks/list. */
public final class P0Client {
    static final int PAGE_SIZE = 1000;
    private static final int MAX_PAGES = 10_000;
    private static final DateTimeFormatter LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS");
    private final HttpClient http;
    private final RetrySleeper retrySleeper;
    private final Duration requestTimeout;
    @FunctionalInterface interface RetrySleeper { void sleep(long milliseconds) throws InterruptedException; }

    public P0Client() { this(Thread::sleep); }

    P0Client(RetrySleeper retrySleeper) { this(retrySleeper, Duration.ofSeconds(45)); }

    P0Client(RetrySleeper retrySleeper, Duration requestTimeout) {
        this.retrySleeper = Objects.requireNonNull(retrySleeper);
        this.requestTimeout = Objects.requireNonNull(requestTimeout);
        if (requestTimeout.isZero() || requestTimeout.isNegative()) throw new IllegalArgumentException("p0 请求超时必须为正数");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public record Task(String id, String hashKey, String taskType, BigDecimal fee, Instant createdAt, String feeWarning) {
        public Task(String id, String hashKey, String taskType, BigDecimal fee, Instant createdAt) { this(id, hashKey, taskType, fee, createdAt, null); }
    }
    record Page(List<Task> tasks, Long total, Boolean hasMore) {}

    public void pullHour(AppConfig config, String apiKey, ZonedDateTime start, ZonedDateTime end,
                         Consumer<List<Task>> receive, Runnable requested) throws Exception {
        long seen = 0;
        Long expectedTotal = null;
        Set<String> pageSignatures = new HashSet<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Page response = request(config, apiKey, start, end, page, PAGE_SIZE, requested);
            List<Task> tasks = response.tasks();
            if (response.total() != null) {
                if (expectedTotal != null && !expectedTotal.equals(response.total()))
                    throw new IOException("p0 分页 total 在拉取过程中变化，请稍后重试");
                expectedTotal = response.total();
            }
            // Detect servers ignoring the page parameter instead of spinning or inflating totals.
            if (!tasks.isEmpty() && !pageSignatures.add(pageSignature(tasks)))
                throw new IOException("p0 分页返回重复页面，已停止，未写入当天缓存");
            receive.accept(tasks);
            seen += tasks.size();
            if (expectedTotal != null && seen > expectedTotal)
                throw new IOException("p0 分页记录数超过 total，数据可能变化，请稍后重试");
            if (Boolean.TRUE.equals(response.hasMore()) && tasks.isEmpty())
                throw new IOException("p0 返回 has_more=true 但页面为空");
            if (expectedTotal != null && seen == expectedTotal) {
                if (Boolean.TRUE.equals(response.hasMore()))
                    throw new IOException("p0 已达到 total 但 has_more=true，分页元数据冲突");
                return;
            }
            if (Boolean.FALSE.equals(response.hasMore())) {
                if (expectedTotal != null && seen != expectedTotal)
                    throw new IOException("p0 提前结束分页，记录数与 total 不一致");
                return;
            }
            // Without pagination metadata, continue to an empty page: some servers cap page_size.
            if (tasks.isEmpty()) {
                if (expectedTotal != null && seen < expectedTotal)
                    throw new IOException("p0 分页返回空页，记录数少于 total");
                return;
            }
        }
        throw new IOException("p0 单小时分页超出安全上限");
    }

    private static String pageSignature(List<Task> tasks) throws Exception {
        List<String> rows = new ArrayList<>();
        for (Task task : tasks) {
            StringBuilder row = new StringBuilder();
            for (String value : List.of(task.id() == null ? "" : task.id(), task.hashKey(), task.taskType(),
                    task.fee() == null ? "<unknown-fee>" : task.fee().stripTrailingZeros().toPlainString(), task.createdAt() == null ? "" : task.createdAt().toString()))
                row.append(value.length()).append(':').append(value);
            rows.add(row.toString());
        }
        Collections.sort(rows); // A repeated page reordered by the server is still the same page.
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (String row : rows) {
            digest.update((row.length() + ":").getBytes(StandardCharsets.UTF_8));
            digest.update(row.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public Map<String, Object> testConnection(AppConfig config) throws Exception {
        ZonedDateTime start = LocalDate.now(ZoneId.of(config.timezone)).minusDays(1).atStartOfDay(ZoneId.of(config.timezone));
        Page page = request(config, resolveApiKey(config), start, start.plusHours(1), 1, 1, () -> {});
        long missingFee = page.tasks().stream().filter(task -> task.fee() == null).count();
        long unclassified = page.tasks().stream().filter(task -> task.hashKey().isBlank()).count();
        String message = page.tasks().isEmpty() ? "p0 接口连通，查询时段暂无记录；任务字段需在有数据时进一步验证"
                : page.tasks().stream().anyMatch(task -> task.createdAt() == null) ? "p0 接口连通，但任务缺少创建时间，当前响应无法安全进行按日汇总"
                : "p0 接口连通，任务数组及单数统计字段校验通过";
        if (missingFee > 0) message += "；fee 金额未提供或无效，单数统计可继续，金额显示为未提供";
        if (unclassified > 0) message += "；探测记录中有 " + unclassified + " 条缺少渠道标识，归入未分類，单数统计正常";
        return Map.of("ok", true, "message", message, "sampleCount", page.tasks().size(), "missingFeeCount", missingFee,
                "unclassifiedCount", unclassified);
    }

    private Page request(AppConfig config, String apiKey, ZonedDateTime start, ZonedDateTime end,
                         int page, int pageSize, Runnable requested) throws Exception {
        String endpoint = config.p0Endpoint;
        // The upstream compares ISO wall-clock strings and includes both endpoints.
        // Express our half-open hourly window with its inclusive microsecond end.
        ZonedDateTime queryEnd = "datetime".equals(config.p0TimeFormat) ? end.minusNanos(1_000) : end;
        String query = "status=2&start_time=" + encode(format(start, config.p0TimeFormat))
                + "&end_time=" + encode(format(queryEnd, config.p0TimeFormat))
                + "&page=" + page + "&page_size=" + pageSize;
        URI uri = URI.create(endpoint + (endpoint.contains("?") ? "&" : "?") + query);
        int maxAttempts = config.p0MaxAttempts;
        if (maxAttempts < 1 || maxAttempts > 50) throw new IllegalArgumentException("p0 每页尝试次数应在 1–50 次之间");
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            requested.run();
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).GET().timeout(requestTimeout)
                    .header("canary", "true").header("Accept", "application/json");
            if (apiKey != null && !apiKey.isBlank()) builder.header("apikey", apiKey);
            HttpRequest req = builder.build();
            HttpResponse<String> response;
            try {
                response = sendComplete(req);
            } catch (IOException e) {
                if (attempt + 1 == maxAttempts)
                    throw new IOException("p0 网络请求失败，已尝试 " + maxAttempts + " 次，尝试次数已耗尽；请检查地址、网络或超时");
                pause(retryDelayMillis(attempt, null, Instant.now()));
                continue;
            }
            int status = response.statusCode();
            if (status == 408 || status == 429 || (status >= 500 && status <= 599)) {
                if (attempt + 1 == maxAttempts)
                    throw new IOException("p0 请求失败：HTTP " + status + "，已尝试 " + maxAttempts + " 次，尝试次数已耗尽，未写入当天缓存");
                pause(retryDelayMillis(attempt,
                        response.headers().firstValue("Retry-After").orElse(null), Instant.now()));
                continue;
            }
            if (status < 200 || status >= 300)
                throw new IOException("p0 请求失败：HTTP " + status + "（请检查 apikey 和接口权限）");
            JsonNode document;
            try { document = Json.MAPPER.readTree(response.body()); }
            catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IOException("p0 返回无效 JSON，未写入缓存"); }
            try {
                return decode(document, start.getZone());
            } catch (IOException | IllegalArgumentException e) {
                throw new IOException("p0 响应解析失败：" + e.getMessage());
            }
        }
        throw new IOException("p0 重试次数耗尽");
    }

    private HttpResponse<String> sendComplete(HttpRequest request) throws IOException, InterruptedException {
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try { return pending.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS); }
        catch (TimeoutException | ExecutionException e) { throw new IOException("p0 完整响应读取失败或超时"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
        finally { if (!pending.isDone()) pending.cancel(true); }
    }

    private void pause(long milliseconds) throws InterruptedException {
        try { retrySleeper.sleep(milliseconds); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
    }

    static long retryDelayMillis(int failedAttempt, String retryAfter, Instant now) throws IOException {
        long delay = Math.min(30_000L, 1000L << Math.min(failedAttempt, 5));
        if (retryAfter == null || retryAfter.isBlank()) return delay;
        String value = retryAfter.trim();
        long requestedDelay = 0;
        if (value.matches("\\d+")) {
            java.math.BigInteger seconds = new java.math.BigInteger(value);
            if (seconds.compareTo(java.math.BigInteger.valueOf(300)) > 0)
                throw new IOException("p0 Retry-After 要求等待超过 5 分钟，本次已停止，未提前重试");
            requestedDelay = seconds.longValue() * 1000L;
        } else {
            try {
                Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                if (retryAt.isAfter(now)) {
                    Duration remaining = Duration.between(now, retryAt);
                    if (remaining.compareTo(Duration.ofMinutes(5)) > 0)
                        throw new IOException("p0 Retry-After 要求等待超过 5 分钟，本次已停止，未提前重试");
                    requestedDelay = remaining.toMillis();
                    if (now.plusMillis(requestedDelay).isBefore(retryAt)) requestedDelay++;
                }
            } catch (DateTimeException ignored) { /* Invalid Retry-After falls back to exponential backoff. */ }
        }
        return Math.max(delay, requestedDelay);
    }

    static Page decode(JsonNode root, ZoneId zone) throws IOException {
        if (root == null || root.isNull()) throw new IOException("响应为空");
        if (root.isObject()) {
            JsonNode code = root.get("code");
            if (code != null && !code.isNull() && !Set.of("0", "200", "OK", "ok", "success").contains(code.asText()))
                throw new IOException("接口返回业务错误，未写入缓存");
            if (root.has("success") && !root.path("success").asBoolean()) throw new IOException("接口返回 success=false");
            if (root.has("status") && ((root.path("status").isBoolean() && !root.path("status").asBoolean())
                    || Set.of("error", "fail", "failed", "false").contains(root.path("status").asText().toLowerCase(Locale.ROOT))))
                throw new IOException("接口返回失败状态");
            if (root.hasNonNull("error")) {
                JsonNode error = root.path("error");
                if (error.isContainerNode() || !Set.of("", "false", "0").contains(error.asText())) throw new IOException("接口返回 error");
            }
        }
        JsonNode data = root.isArray() ? root : root.get("data");
        if (data == null) data = root;
        JsonNode items = data.isArray() ? data : null;
        String itemsPath = items == null ? null : data == root ? "$" : "data";
        if (items == null) for (String name : List.of("list", "tasks", "items", "records", "rows")) {
            if (data.path(name).isArray()) {
                items = data.path(name);
                itemsPath = data == root ? name : "data." + name;
                break;
            }
        }
        if (items == null) throw new IOException("找不到任务数组（支持 data.list/tasks/items/records/rows 或 data 数组）");
        Long total = longMeta(data, root, "total", "total_count", "totalCount");
        Boolean more = boolMeta(data, root, "has_more", "hasMore");
        List<Task> tasks = new ArrayList<>();
        int rowIndex = 0;
        for (JsonNode row : items) {
            rowIndex++;
            if (!row.isObject()) throw new IOException("任务记录必须为对象");
            if (row.hasNonNull("status") && !"2".equals(row.path("status").asText()))
                throw new IOException("status=2 查询中出现非成功任务，请确认接口筛选语义");
            String hash = channelHash(row);
            String type = workflow(row);
            if (type.isBlank()) throw new IOException("任务 task_type 不能为空");
            JsonNode feeValue = row.get("fee");
            BigDecimal fee = null;
            String feeWarning = null;
            if (feeValue == null) feeWarning = feeDiagnostic(itemsPath, rowIndex, row, "缺失").getMessage();
            else if (feeValue.isNull()) feeWarning = feeDiagnostic(itemsPath, rowIndex, row, "null").getMessage();
            else if (!(feeValue.isNumber() || feeValue.isTextual())) {
                String typeName = feeValue.isObject() ? "对象" : feeValue.isArray() ? "数组" : feeValue.isBoolean() ? "布尔值" : "其他类型";
                feeWarning = feeDiagnostic(itemsPath, rowIndex, row, "类型不对（" + typeName + "）").getMessage();
            } else {
                try { fee = new BigDecimal(feeValue.asText().trim()); }
                catch (NumberFormatException e) {
                    feeWarning = feeDiagnostic(itemsPath, rowIndex, row, feeValue.isTextual() ? "非数字字符串" : "非有效数值").getMessage();
                }
            }
            // uuid identifies a source record; caller-supplied business task IDs can repeat.
            String id = firstText(row, "uuid", "id", "task_id", "taskId");
            Instant createdAt = null;
            String rawTime = firstText(row, "create_time", "created_at", "created_time", "createdAt", "createTime", "createdTime", "created_on", "createdOn");
            if (rawTime != null) createdAt = parseTime(rawTime, zone);
            if (id == null && createdAt == null)
                throw new IOException("任务缺少 id/task_id 或创建时间，无法校验小时边界和重复记录");
            tasks.add(new Task(id, hash, type, fee, createdAt, feeWarning));
        }
        return new Page(tasks, total, more);
    }

    private static final Set<String> DIAGNOSTIC_FIELDS = Set.of("fee", "hash_key", "hashKey", "task_type", "taskType", "type", "task",
            "id", "task_id", "taskId", "uuid", "created_at", "create_time", "created_time", "createdAt", "createTime", "createdTime",
            "created_on", "createdOn", "status", "amount", "cost", "price", "total_fee", "task_fee", "billing", "payment", "meta", "metadata", "data", "result", "extra", "params");

    private static IOException feeDiagnostic(String itemsPath, int index, JsonNode row, String reason) {
        List<String> safeFields = new ArrayList<>();
        var fields = row.fieldNames();
        boolean omitted = false;
        while (fields.hasNext()) {
            String field = fields.next();
            // A structural allowlist also excludes secrets stored as dynamic JSON property names.
            String normalized = field.toLowerCase(Locale.ROOT);
            if (!DIAGNOSTIC_FIELDS.contains(field) || !field.matches("[A-Za-z_][A-Za-z0-9_]{0,39}")
                    || normalized.matches(".*(apikey|token|authorization|password|secret).*")) continue;
            if (safeFields.size() < 20) safeFields.add(field); else omitted = true;
        }
        return new IOException("任务缺少有效 fee：数组 " + itemsPath + "，第 " + index + " 条；fee 状态：" + reason
                + "；可识别字段：" + (safeFields.isEmpty() ? "无" : String.join(", ", safeFields)) + (omitted ? "（更多字段已省略）" : "")
                + "。仅列字段名，未包含字段值。");
    }

    private static String channelHash(JsonNode row) {
        for (String field : List.of("hash_key", "hashKey")) {
            JsonNode value = row.get(field);
            if (value != null && (value.isTextual() || value.isNumber()) && !value.asText().isBlank())
                return value.asText();
        }
        // An absent channel identifier does not invalidate a successful task.
        // Use the existing empty-key bucket so reports consistently display it as 未分類.
        return "";
    }

    private static String required(JsonNode row, String... fields) throws IOException {
        for (String field : fields) {
            JsonNode node = row.get(field);
            if (node != null && !node.isNull() && (node.isNumber() || node.isTextual())) return node.asText();
        }
        throw new IOException("任务缺少有效 " + fields[0]);
    }

    private static String workflow(JsonNode row) throws IOException {
        for (String field : List.of("task_type", "taskType")) if (row.hasNonNull(field)) return required(row, field);
        JsonNode task = row.get("task");
        if (task != null && !task.isNull()) {
            if (task.isObject()) return required(task, "task_type", "taskType", "type", "key", "name");
            if (task.isTextual() || task.isNumber()) return task.asText();
            throw new IOException("任务 task 字段格式无法识别");
        }
        return required(row, "type");
    }

    private static String firstText(JsonNode row, String... fields) {
        for (String field : fields) if (row.hasNonNull(field) && (row.path(field).isTextual() || row.path(field).isNumber())
                && !row.path(field).asText().isBlank()) return row.path(field).asText();
        return null;
    }

    private static Long longMeta(JsonNode data, JsonNode root, String... fields) throws IOException {
        Long result = null;
        for (JsonNode node : metadataNodes(data, root)) for (String field : fields) if (node.hasNonNull(field)) {
            try {
                long value = Long.parseLong(node.path(field).asText());
                if (value < 0) throw new NumberFormatException();
                if (result != null && result != value) throw new IOException("响应中的分页 total 互相冲突");
                result = value;
            } catch (NumberFormatException e) { throw new IOException("无效分页 total"); }
        }
        return result;
    }

    private static Boolean boolMeta(JsonNode data, JsonNode root, String... fields) throws IOException {
        Boolean result = null;
        for (JsonNode node : metadataNodes(data, root)) for (String field : fields) if (node.hasNonNull(field)) {
            String value = node.path(field).asText();
            boolean parsed;
            if ("true".equals(value) || "1".equals(value)) parsed = true;
            else if ("false".equals(value) || "0".equals(value)) parsed = false;
            else throw new IOException("无效分页 has_more");
            if (result != null && result != parsed) throw new IOException("响应中的分页 has_more 互相冲突");
            result = parsed;
        }
        return result;
    }

    private static List<JsonNode> metadataNodes(JsonNode data, JsonNode root) {
        List<JsonNode> nodes = new ArrayList<>();
        for (JsonNode node : List.of(data, root)) {
            nodes.add(node);
            for (String field : List.of("pagination", "page_info", "pageInfo")) if (node.path(field).isObject()) nodes.add(node.path(field));
        }
        return nodes;
    }

    static Instant parseTime(String text, ZoneId zone) throws IOException {
        try {
            if (text.matches("\\d{10,13}(\\.\\d+)?")) {
                BigDecimal n = new BigDecimal(text);
                if (n.compareTo(new BigDecimal("100000000000")) >= 0) return Instant.ofEpochMilli(n.longValueExact());
                long seconds = n.longValue();
                return Instant.ofEpochSecond(seconds, n.subtract(BigDecimal.valueOf(seconds)).movePointRight(9).longValueExact());
            }
            try { return Instant.parse(text); } catch (DateTimeException ignored) { }
            try { return OffsetDateTime.parse(text.replace(' ', 'T')).toInstant(); } catch (DateTimeException ignored) { }
            return LocalDateTime.parse(text.replace(' ', 'T')).atZone(zone).toInstant();
        } catch (Exception e) { throw new IOException("任务创建时间格式无法识别"); }
    }

    static String format(ZonedDateTime t, String mode) {
        return switch (mode) {
            case "unix_seconds" -> Long.toString(t.toEpochSecond());
            case "unix_millis" -> Long.toString(t.toInstant().toEpochMilli());
            case "datetime" -> t.format(LOCAL_TIME);
            default -> throw new IllegalArgumentException("未知 p0 时间参数格式");
        };
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    public static String resolveApiKey(AppConfig config) throws IOException {
        if (config.p0ApiKeyFile != null && !config.p0ApiKeyFile.isBlank()) {
            String file = config.p0ApiKeyFile.startsWith("~/")
                    ? System.getProperty("user.home") + config.p0ApiKeyFile.substring(1) : config.p0ApiKeyFile;
            String content = Files.readString(Path.of(file)).trim();
            if (content.startsWith("{")) {
                JsonNode json;
                try { json = Json.MAPPER.readTree(content); }
                catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IOException("密钥文件 JSON 格式无效"); }
                String key = firstText(json, "apikey", "api_key", "p0ApiKey", "P0_API_KEY", "APIKEY");
                if (key == null) throw new IOException("密钥 JSON 中未找到 apikey/api_key/p0ApiKey/P0_API_KEY");
                return key;
            }
            if (content.contains("\n") || content.contains("=")) {
                for (String line : content.split("\\R")) {
                    String trimmed = line.trim().replaceFirst("^export\\s+", "");
                    int equal = trimmed.indexOf('=');
                    if (equal > 0 && Set.of("apikey", "api_key", "p0ApiKey", "P0_API_KEY", "APIKEY").contains(trimmed.substring(0, equal).trim())) {
                        String key = trimmed.substring(equal + 1).trim();
                        if (key.length() >= 2 && ((key.startsWith("\"") && key.endsWith("\"")) || (key.startsWith("'") && key.endsWith("'"))))
                            key = key.substring(1, key.length() - 1);
                        if (!key.isBlank()) return key;
                    }
                }
                throw new IOException("密钥配置中未找到 apikey 或 P0_API_KEY");
            }
            if (content.isBlank()) throw new IOException("密钥文件为空");
            return content;
        }
        return config.p0ApiKey == null ? "" : config.p0ApiKey.trim();
    }
}
