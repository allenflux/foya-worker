package com.foya.worker;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

public final class StatsService {
    private final Path cacheDir;
    private final P0Client client;
    public record Progress(String date, int completedHours, int totalHours, int requests, long count) {}
    private record Key(String hash, String workflow) {}
    private static final class Aggregate {
        long count;
        BigDecimal fee = BigDecimal.ZERO;
        void add(BigDecimal amount) { count++; fee = fee.add(amount); }
    }

    public StatsService(Path dataDir) { this(dataDir, new P0Client()); }
    StatsService(Path dataDir, P0Client client) { this.cacheDir = dataDir.resolve("cache"); this.client = client; }

    public DailyStats pullDay(AppConfig config, LocalDate day, boolean force, Consumer<Progress> progress) throws Exception {
        ZoneId zone = ZoneId.of(config.timezone);
        if (!day.isBefore(LocalDate.now(zone))) throw new IllegalArgumentException("只能拉取已结束的完整日期");
        String apiKey = P0Client.resolveApiKey(config);
        String fingerprint = fingerprint(config, apiKey);
        if (!force) {
            DailyStats cached = readDay(day, fingerprint);
            if (cached != null) return cached;
        }
        ZonedDateTime start = day.atStartOfDay(zone);
        ZonedDateTime finish = day.plusDays(1).atStartOfDay(zone);
        List<ZonedDateTime[]> slices = new ArrayList<>();
        for (ZonedDateTime cursor = start; cursor.isBefore(finish); cursor = cursor.plusHours(1)) {
            ZonedDateTime end = cursor.plusHours(1);
            if ("datetime".equals(config.p0TimeFormat) && !end.toLocalDateTime().isAfter(cursor.toLocalDateTime()))
                throw new IllegalArgumentException("该日期包含夏令时回拨，请将 p0 时间参数格式改为 Unix 秒或毫秒以避免重复时段");
            slices.add(new ZonedDateTime[]{cursor, end.isAfter(finish) ? finish : end});
        }
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicLong count = new AtomicLong();
        Map<Key, Aggregate> totals = new HashMap<>();
        Set<String> seenIds = ConcurrentHashMap.newKeySet();
        Consumer<Progress> observer = progress == null ? p -> {} : progress;
        Runnable notifyProgress = () -> observer.accept(new Progress(day.toString(), completed.get(), slices.size(), requests.get(), count.get()));
        notifyProgress.run();
        ExecutorService pool = Executors.newFixedThreadPool(6, Thread.ofPlatform().name("p0-hour-", 0).factory());
        CompletionService<Void> completions = new ExecutorCompletionService<>(pool);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (ZonedDateTime[] slice : slices) futures.add(completions.submit(() -> {
                client.pullHour(config, apiKey, slice[0], slice[1], rows -> {
                    for (P0Client.Task task : rows) {
                        if (task.createdAt() == null)
                            throw new IllegalArgumentException("p0 任务缺少创建时间，无法安全校验统计日期和小时边界，未写入当天缓存");
                        // Upstreams often make both ends inclusive. Keep the exact half-open window locally.
                        if (task.createdAt().isBefore(slice[0].toInstant())
                                || !task.createdAt().isBefore(slice[1].toInstant())) continue;
                        if (task.id() != null && !seenIds.add(task.id())) continue;
                        synchronized (totals) {
                            totals.computeIfAbsent(new Key(task.hashKey(), task.taskType()), ignored -> new Aggregate()).add(task.fee());
                        }
                        count.incrementAndGet();
                    }
                }, () -> { requests.incrementAndGet(); notifyProgress.run(); });
                completed.incrementAndGet();
                notifyProgress.run();
                return null;
            }));
            for (int i = 0; i < slices.size(); i++) {
                try { completions.take().get(); }
                catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception exception) throw exception;
                    throw new IOException("p0 拉取失败，未更新当天缓存");
                }
            }
            DailyStats result = new DailyStats();
            result.date = day.toString();
            result.timezone = config.timezone;
            result.fetchedAt = Instant.now().toString();
            result.sourceFingerprint = fingerprint;
            result.requestCount = requests.get();
            totals.entrySet().stream().sorted(Comparator.comparing((Map.Entry<Key, Aggregate> e) -> e.getKey().hash)
                    .thenComparing(e -> e.getKey().workflow)).forEach(entry -> {
                Aggregate a = entry.getValue();
                result.buckets.add(new DailyStats.Bucket(entry.getKey().hash, entry.getKey().workflow, a.count, a.fee));
                result.totalCount += a.count;
                result.totalFee = result.totalFee.add(a.fee);
            });
            writeDay(result);
            return result;
        } finally {
            futures.forEach(f -> f.cancel(true));
            pool.shutdownNow();
            try { pool.awaitTermination(5, TimeUnit.SECONDS); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    /** Returns a complete contiguous range, refusing to label a partial report as complete. */
    public List<DailyStats> cachedDays(AppConfig config, LocalDate endDate, int days) throws IOException {
        List<DailyStats> result = availableCachedDays(config, endDate, days);
        if (result.size() != days) {
            Set<String> found = new HashSet<>();
            result.forEach(d -> found.add(d.date));
            List<String> missing = new ArrayList<>();
            for (int i = 0; i < days; i++) if (!found.contains(endDate.minusDays(i).toString())) missing.add(endDate.minusDays(i).toString());
            throw new IOException("报表缺少完整缓存：" + String.join("、", missing) + "。请先补拉统计数据。");
        }
        return result;
    }

    /** Available complete days for cache inventory; incompatible or missing days are omitted. */
    public List<DailyStats> availableCachedDays(AppConfig config, LocalDate endDate, int days) throws IOException {
        if (days < 1 || days > 366) throw new IllegalArgumentException("缓存查询天数必须在 1–366 之间");
        String fingerprint = fingerprint(config, P0Client.resolveApiKey(config));
        List<DailyStats> result = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            DailyStats stats = readDay(endDate.minusDays(i), fingerprint);
            if (stats != null) result.add(stats);
        }
        return result;
    }

    public List<Map<String, Object>> cacheIndex(AppConfig config) throws IOException {
        if (!Files.isDirectory(cacheDir)) return List.of();
        String fingerprint = fingerprint(config, P0Client.resolveApiKey(config));
        List<Map<String, Object>> result = new ArrayList<>();
        try (var files = Files.list(cacheDir)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}\\.json"))
                    .sorted(Comparator.reverseOrder()).toList()) {
                LocalDate date = LocalDate.parse(file.getFileName().toString().substring(0, 10));
                DailyStats stats = readDay(date, fingerprint);
                if (stats != null) result.add(Map.of("date", stats.date, "count", stats.totalCount, "fee", stats.totalFee,
                        "fetchedAt", stats.fetchedAt, "requests", stats.requestCount, "timezone", stats.timezone));
            }
        }
        return result;
    }

    private DailyStats readDay(LocalDate day, String fingerprint) throws IOException {
        Path file = cacheDir.resolve(day + ".json");
        if (!Files.exists(file)) return null;
        DailyStats result;
        try { result = Json.MAPPER.readValue(file.toFile(), DailyStats.class); }
        catch (IOException e) { return null; } // Corrupt/incomplete caches must be repulled.
        if (result.schemaVersion != 1 || !day.toString().equals(result.date) || !fingerprint.equals(result.sourceFingerprint)
                || result.fetchedAt == null || result.timezone == null || result.buckets == null || result.totalFee == null) return null;
        long totalCount = 0;
        BigDecimal totalFee = BigDecimal.ZERO;
        Set<Key> keys = new HashSet<>();
        for (DailyStats.Bucket bucket : result.buckets) {
            if (bucket == null || bucket.hashKey() == null || bucket.taskType() == null || bucket.count() < 1 || bucket.fee() == null
                    || !keys.add(new Key(bucket.hashKey(), bucket.taskType()))) return null;
            totalCount += bucket.count();
            totalFee = totalFee.add(bucket.fee());
        }
        if (totalCount != result.totalCount || totalFee.compareTo(result.totalFee) != 0) return null;
        return result;
    }

    private void writeDay(DailyStats result) throws IOException {
        FilesSupport.writeJson(cacheDir.resolve(result.date + ".json"), result);
    }

    static String fingerprint(AppConfig config, String apiKey) {
        try {
            String source = config.p0Endpoint + "\n" + config.timezone + "\n" + config.p0TimeFormat + "\nstatus=2\n" + apiKey;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("无法计算缓存数据源标识", e); }
    }
}
