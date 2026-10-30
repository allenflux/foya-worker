package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** FluxDrop-backed aggregate storage. Every payload and index stays in memory; no local files are used. */
public final class RemoteDailyCacheStore implements DailyCacheStore {
    private static final long TTL_NANOS = Duration.ofSeconds(30).toNanos();
    private static final Pattern NAME = Pattern.compile("^aiapi-p0-cache-v1-([a-f0-9]{64})-(\\d{4}-\\d{2}-\\d{2})-(\\d{1,19})-([a-f0-9-]{36})\\.json$");
    private final HttpClient http;
    private final Duration requestTimeout;
    private final Duration restoreTimeout;
    private final Sleeper sleeper;
    private final Map<String, Inventory> inventories = new ConcurrentHashMap<>();
    private final Map<String, CachedDay> days = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, CachedDay> eldest) { return size() > 64; }
    });
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }
    private record RemoteFile(String filename, LocalDate date, long version, URI download, String fileId, String storageState) {}
    private record Inventory(long expires, List<RemoteFile> files) {}
    private record CachedDay(String filename, byte[] bytes) {}
    private record Response(int status, byte[] body) {}
    private static final class Failure extends IOException {
        final int status;
        final boolean retryable;
        Failure(String message, int status, boolean retryable) { super(message); this.status = status; this.retryable = retryable; }
    }

    public RemoteDailyCacheStore() { this(Duration.ofSeconds(20), Duration.ofSeconds(60), Thread::sleep); }
    RemoteDailyCacheStore(Duration requestTimeout, Duration restoreTimeout, Sleeper sleeper) {
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                || restoreTimeout == null || restoreTimeout.isNegative() || restoreTimeout.isZero())
            throw new IllegalArgumentException("远程每日缓存超时必须为正数");
        this.requestTimeout = requestTimeout; this.restoreTimeout = restoreTimeout; this.sleeper = Objects.requireNonNull(sleeper);
        http = HttpClient.newBuilder().connectTimeout(requestTimeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public DailyStats read(AppConfig config, LocalDate day, String fingerprint) throws IOException {
        URI origin = origin(config);
        requireFingerprint(fingerprint);
        String key = dayKey(origin, fingerprint, day);
        List<RemoteFile> candidates = inventory(origin, fingerprint, false).stream().filter(file -> file.date.equals(day)).toList();
        for (RemoteFile file : candidates) {
            CachedDay cached = days.get(key);
            if (cached != null && cached.filename.equals(file.filename)) return decode(cached.bytes, day, fingerprint);
            restoreIfNeeded(origin, file);
            try {
                byte[] bytes = bytesWithRetries("GET", file.download, null, null, Long.MAX_VALUE);
                DailyStats result = decode(bytes, day, fingerprint);
                days.put(key, new CachedDay(file.filename, bytes));
                return result;
            } catch (Failure e) { if (e.status != 404) throw e; }
        }
        return null;
    }

    @Override public void write(AppConfig config, DailyStats day) throws IOException {
        URI origin = origin(config);
        requireFingerprint(day.sourceFingerprint);
        LocalDate date;
        try { date = LocalDate.parse(day.date); } catch (Exception e) { throw new IOException("远程每日缓存日期无效"); }
        byte[] json;
        try { json = Json.MAPPER.writeValueAsBytes(day); }
        catch (Exception e) { throw new IOException("远程每日缓存汇总序列化失败"); }
        decode(json, date, day.sourceFingerprint);
        List<RemoteFile> existing = inventory(origin, day.sourceFingerprint, false);
        long newest = existing.stream().filter(file -> file.date.equals(date)).mapToLong(RemoteFile::version).max().orElse(0);
        long version = Math.max(System.currentTimeMillis(), newest == Long.MAX_VALUE ? newest : newest + 1);
        String filename = "aiapi-p0-cache-v1-" + day.sourceFingerprint + "-" + date + "-" + version + "-" + UUID.randomUUID() + ".json";
        String boundary = "aiapi-" + UUID.randomUUID();
        byte[] multipart = multipart(filename, boundary, json);
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Response response = request("POST", URI.create(config.cacheUploadUrl.replaceAll("/+$", "")), multipart,
                        "multipart/form-data; boundary=" + boundary, Long.MAX_VALUE);
                checkStatus(response.status);
                JsonNode metadata = okJson(response.body);
                RemoteFile uploaded = metadata(metadata, origin, day.sourceFingerprint);
                if (uploaded == null || !filename.equals(uploaded.filename)) throw new Failure("远程每日缓存上传返回的文件元数据不一致", 0, false);
                remember(origin, day.sourceFingerprint, uploaded, json);
                return;
            } catch (Failure e) {
                if (e.retryable) {
                    RemoteFile committed = findUploaded(origin, day.sourceFingerprint, filename);
                    if (committed != null) { remember(origin, day.sourceFingerprint, committed, json); return; }
                }
                if (!e.retryable || attempt == 2) throw new IOException("远程每日缓存上传失败，旧缓存已保留；" + e.getMessage());
                pause(1000L << attempt, Long.MAX_VALUE);
            }
        }
        throw new IOException("远程每日缓存上传失败，未使用本地缓存");
    }

    @Override public List<DailyStats> list(AppConfig config, String fingerprint) throws IOException {
        URI origin = origin(config); requireFingerprint(fingerprint);
        Set<LocalDate> dates = new TreeSet<>(Comparator.reverseOrder());
        inventory(origin, fingerprint, false).forEach(file -> dates.add(file.date));
        List<DailyStats> result = new ArrayList<>();
        for (LocalDate date : dates) {
            DailyStats day = read(config, date, fingerprint);
            if (day != null) result.add(day);
        }
        return result;
    }

    public List<LocalDate> dates(AppConfig config, String fingerprint) throws IOException {
        URI origin = origin(config); requireFingerprint(fingerprint);
        return inventory(origin, fingerprint, false).stream().map(RemoteFile::date).distinct().sorted(Comparator.reverseOrder()).toList();
    }

    private List<RemoteFile> inventory(URI origin, String fingerprint, boolean force) throws IOException {
        String key = origin + "|" + fingerprint;
        synchronized (locks.computeIfAbsent(key, ignored -> new Object())) {
            Inventory cached = inventories.get(key);
            if (!force && cached != null && cached.expires > System.nanoTime()) return cached.files;
            JsonNode json = okJson(bytesWithRetries("GET", origin.resolve("/api/files"), null, null, Long.MAX_VALUE));
            if (!json.path("files").isArray()) throw new Failure("远程每日缓存文件目录格式无效", 0, false);
            List<RemoteFile> files = new ArrayList<>();
            for (JsonNode node : json.path("files")) {
                RemoteFile file = metadata(node, origin, fingerprint);
                if (file != null) files.add(file);
            }
            files.sort(Comparator.comparing(RemoteFile::date).reversed().thenComparing(Comparator.comparingLong(RemoteFile::version).reversed())
                    .thenComparing(RemoteFile::filename, Comparator.reverseOrder()));
            List<RemoteFile> frozen = List.copyOf(files);
            inventories.put(key, new Inventory(System.nanoTime() + TTL_NANOS, frozen));
            return frozen;
        }
    }

    private RemoteFile findUploaded(URI origin, String fingerprint, String filename) {
        try { return inventory(origin, fingerprint, true).stream().filter(file -> file.filename.equals(filename)).findFirst().orElse(null); }
        catch (IOException ignored) { return null; }
    }

    private void remember(URI origin, String fingerprint, RemoteFile uploaded, byte[] bytes) {
        String key = origin + "|" + fingerprint;
        synchronized (locks.computeIfAbsent(key, ignored -> new Object())) {
            Inventory old = inventories.get(key);
            if (old != null) {
                List<RemoteFile> files = new ArrayList<>(old.files);
                files.removeIf(file -> file.filename.equals(uploaded.filename)); files.add(uploaded);
                files.sort(Comparator.comparing(RemoteFile::date).reversed().thenComparing(Comparator.comparingLong(RemoteFile::version).reversed())
                        .thenComparing(RemoteFile::filename, Comparator.reverseOrder()));
                inventories.put(key, new Inventory(System.nanoTime() + TTL_NANOS, List.copyOf(files)));
            }
        }
        days.put(dayKey(origin, fingerprint, uploaded.date), new CachedDay(uploaded.filename, bytes.clone()));
    }

    private RemoteFile metadata(JsonNode node, URI origin, String fingerprint) throws IOException {
        if (!node.path("filename").isTextual()) return null;
        String filename = node.path("filename").textValue();
        if (!filename.startsWith("aiapi-p0-cache-v1-" + fingerprint + "-")) return null;
        Matcher match = NAME.matcher(filename);
        if (!match.matches()) throw new Failure("远程每日缓存文件名元数据无效", 0, false);
        try {
            LocalDate date = LocalDate.parse(match.group(2));
            long version = Long.parseLong(match.group(3));
            if (!node.path("download_url").isTextual()) throw new IllegalArgumentException();
            URI download = origin.resolve(URI.create(node.path("download_url").textValue()));
            if (!sameOrigin(origin, download) || download.getUserInfo() != null || download.getFragment() != null
                    || download.getPath() == null || !download.getPath().startsWith("/f/"))
                throw new Failure("远程每日缓存下载地址不安全，已拒绝跨域或非文件地址", 0, false);
            String id = node.path("file_id").asText("");
            if (!id.isBlank() && !id.matches("[A-Za-z0-9_-]{1,200}")) throw new IllegalArgumentException();
            String storage = node.path("storage_state").asText("");
            return new RemoteFile(filename, date, version, download, id, storage);
        } catch (Failure e) { throw e; }
        catch (Exception e) { throw new Failure("远程每日缓存文件元数据格式无效", 0, false); }
    }

    private void restoreIfNeeded(URI origin, RemoteFile file) throws IOException {
        if (hot(file.storageState) || file.storageState.isBlank()) return;
        if (file.fileId.isBlank()) throw new IOException("远程每日缓存冷文件缺少恢复标识");
        long deadline = System.nanoTime() + restoreTimeout.toNanos();
        URI restore = origin.resolve("/api/files/" + file.fileId + "/restore");
        JsonNode status = okJson(bytesWithRetries("POST", restore, new byte[0], "application/json", deadline));
        while (true) {
            String storage = status.path("storage_state").asText("");
            String job = status.path("storage_job").path("status").asText(status.path("storage_job").path("state").asText(""));
            if (hot(storage)) return;
            if (Set.of("failed", "cancelled", "canceled").contains(job) || Set.of("failed", "cancelled", "canceled").contains(storage))
                throw new IOException("远程每日缓存冷文件恢复失败，请稍后重试");
            pause(1200, deadline);
            status = okJson(bytesWithRetries("GET", origin.resolve("/api/files/" + file.fileId + "/storage"), null, null, deadline));
        }
    }
    private static boolean hot(String storage) { return Set.of("hot", "hot_backed").contains(storage); }

    private byte[] bytesWithRetries(String method, URI uri, byte[] body, String contentType, long deadline) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Response response = request(method, uri, body, contentType, deadline);
                checkStatus(response.status);
                return response.body;
            } catch (Failure e) {
                if (!e.retryable || attempt == 2) throw e;
                pause(1000L << attempt, deadline);
            }
        }
        throw new IOException("远程每日缓存重试次数已耗尽");
    }

    private Response request(String method, URI uri, byte[] body, String contentType, long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new Failure("远程每日缓存请求已中断", 0, false);
        long remaining = deadline == Long.MAX_VALUE ? requestTimeout.toNanos() : Math.min(requestTimeout.toNanos(), deadline - System.nanoTime());
        if (remaining <= 0) throw new Failure("远程每日缓存冷文件恢复超时，请稍后重试", 0, false);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(remaining)).header("Accept", "application/json");
        if (contentType != null) builder.header("Content-Type", contentType);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        var pending = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        try {
            HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.NANOSECONDS);
            if (response.body().length > 16_777_216) throw new Failure("远程每日缓存响应超出大小上限", 0, false);
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new Failure("远程每日缓存请求已中断", 0, false);
        } catch (ExecutionException | TimeoutException e) { throw new Failure("远程每日缓存网络失败或完整响应超时", 0, true); }
        catch (RuntimeException e) { throw new Failure("远程每日缓存请求无法完成", 0, false); }
        finally { if (!pending.isDone()) pending.cancel(true); }
    }

    private void pause(long millis, long deadline) throws IOException {
        if (deadline != Long.MAX_VALUE && deadline - System.nanoTime() <= TimeUnit.MILLISECONDS.toNanos(millis))
            throw new Failure("远程每日缓存冷文件恢复超时，请稍后重试", 0, false);
        try { sleeper.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure("远程每日缓存等待已中断", 0, false); }
    }

    private static void checkStatus(int status) throws Failure {
        if (status < 200 || status >= 300)
            throw new Failure("远程每日缓存服务请求失败（HTTP " + status + "）", status,
                    status == 408 || status == 429 || status >= 500 && status <= 599);
    }
    private static JsonNode okJson(byte[] bytes) throws Failure {
        try {
            JsonNode node = Json.MAPPER.readTree(bytes);
            if (node == null || !node.isObject() || !node.path("ok").isBoolean() || !node.path("ok").booleanValue())
                throw new IllegalArgumentException();
            return node;
        } catch (Exception e) { throw new Failure("远程每日缓存服务返回无效的成功状态或 JSON", 0, false); }
    }

    private static DailyStats decode(byte[] bytes, LocalDate date, String fingerprint) throws IOException {
        try {
            DailyStats day = Json.MAPPER.readValue(bytes, DailyStats.class);
            if (day == null || day.schemaVersion != 1 || !date.toString().equals(day.date) || !fingerprint.equals(day.sourceFingerprint)
                    || day.timezone == null || day.fetchedAt == null || day.totalFee == null || day.buckets == null) throw new IllegalArgumentException();
            long count = 0, missing = 0;
            BigDecimal fee = BigDecimal.ZERO;
            Set<List<String>> keys = new HashSet<>();
            for (DailyStats.Bucket bucket : day.buckets) {
                if (bucket == null || bucket.hashKey() == null || bucket.taskType() == null || bucket.count() < 1 || bucket.fee() == null
                        || bucket.missingFeeCount() < 0 || bucket.missingFeeCount() > bucket.count()
                        || !keys.add(List.of(bucket.hashKey(), bucket.taskType()))) throw new IllegalArgumentException();
                count = Math.addExact(count, bucket.count()); missing = Math.addExact(missing, bucket.missingFeeCount()); fee = fee.add(bucket.fee());
            }
            if (count != day.totalCount || missing != day.missingFeeCount || fee.compareTo(day.totalFee) != 0) throw new IllegalArgumentException();
            return day;
        } catch (Exception e) { throw new IOException("远程每日缓存内容格式、日期、数据源或汇总校验失败"); }
    }

    private static URI origin(AppConfig config) throws IOException {
        try {
            URI url = URI.create(config.cacheUploadUrl);
            if (url.getHost() == null || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
                    || !Set.of("http", "https").contains(url.getScheme())) throw new IllegalArgumentException();
            return new URI(url.getScheme(), null, url.getHost(), url.getPort(), "/", null, null);
        } catch (Exception e) { throw new IOException("远程每日缓存上传地址配置无效"); }
    }
    private static boolean sameOrigin(URI origin, URI target) {
        return Objects.equals(origin.getScheme(), target.getScheme()) && target.getHost() != null
                && origin.getHost().equalsIgnoreCase(target.getHost()) && port(origin) == port(target);
    }
    private static int port(URI uri) { return uri.getPort() >= 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80; }
    private static String dayKey(URI origin, String fingerprint, LocalDate day) { return origin + "|" + fingerprint + "|" + day; }
    private static void requireFingerprint(String fingerprint) throws IOException {
        if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) throw new IOException("远程每日缓存数据源标识无效");
    }
    private static byte[] multipart(String filename, String boundary, byte[] json) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(json); out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }
}
