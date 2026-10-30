package com.foya.worker;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

class RemoteDailyCacheStoreTest {
    @TempDir Path directory;
    private static final String FP = "a".repeat(64);
    private static final LocalDate DATE = LocalDate.of(2026, 10, 29);
    private record Stored(String id, String filename, byte[] bytes) {}
    private HttpServer server;
    private ExecutorService executor;
    private URI base;
    private AppConfig config;
    private final Map<String, Stored> files = new ConcurrentHashMap<>();
    private final Map<String, String> storage = new ConcurrentHashMap<>();
    private final AtomicInteger uploads = new AtomicInteger(), listings = new AtomicInteger(), downloads = new AtomicInteger();
    private final AtomicInteger restores = new AtomicInteger(), storageChecks = new AtomicInteger();
    private final List<Long> delays = new CopyOnWriteArrayList<>();
    private final List<String> uploadPaths = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> uploadMode = new AtomicReference<>("ok");
    private final AtomicReference<String> maliciousUrl = new AtomicReference<>();
    private final AtomicReference<String> redirect = new AtomicReference<>();
    private final AtomicInteger listStatus = new AtomicInteger(200), downloadStatus = new AtomicInteger(200);
    private volatile boolean slowList, failedRestore;
    private volatile String multipart;
    private final CountDownLatch release = new CountDownLatch(1);

    @BeforeEach void mock() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/", this::handle); server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        config = new AppConfig(); config.cacheUploadUrl = base + "/upload";
        config.p0ApiKey = "CONFIG_PRIVATE_API_KEY"; config.telegramBotToken = "123456:CONFIG_PRIVATE_BOT_TOKEN";
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/upload") || path.equals("/custom/upload")) {
            uploads.incrementAndGet(); uploadPaths.add(path);
            multipart = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String boundary = exchange.getRequestHeaders().getFirst("Content-Type").split("boundary=", 2)[1];
            Matcher filename = Pattern.compile("filename=\"([^\"]+)\"").matcher(multipart);
            if (!filename.find()) { reply(exchange, 400, Map.of("ok", false)); return; }
            int start = multipart.indexOf("\r\n\r\n") + 4;
            int finish = multipart.lastIndexOf("\r\n--" + boundary + "--");
            byte[] bytes = multipart.substring(start, finish).getBytes(StandardCharsets.UTF_8);
            String mode = uploadMode.get();
            Stored uploaded = null;
            if (mode.equals("ok") || mode.equals("commit503")) {
                uploaded = new Stored(UUID.randomUUID().toString(), filename.group(1), bytes);
                files.put(uploaded.id, uploaded); storage.put(uploaded.id, "hot");
            }
            if (mode.equals("fail503") || mode.equals("commit503")) { reply(exchange, 503, Map.of("ok", false, "error", "PRIVATE_UPSTREAM_BODY")); return; }
            if (mode.equals("false")) { reply(exchange, 200, Map.of("ok", false, "error", "PRIVATE_UPSTREAM_BODY")); return; }
            reply(exchange, 201, metadata(uploaded)); return;
        }
        if (path.equals("/api/files")) {
            listings.incrementAndGet();
            if (slowList) {
                exchange.sendResponseHeaders(200, 10_000); exchange.getResponseBody().write("{\"ok\":".getBytes(StandardCharsets.UTF_8)); exchange.getResponseBody().flush();
                try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); } return;
            }
            reply(exchange, listStatus.get(), Map.of("ok", listStatus.get() == 200, "files", files.values().stream().map(this::metadata).toList())); return;
        }
        if (path.startsWith("/api/files/") && path.endsWith("/restore")) {
            restores.incrementAndGet(); reply(exchange, 200, Map.of("ok", true, "storage_state", "restoring", "storage_job", Map.of("status", "queued"))); return;
        }
        if (path.startsWith("/api/files/") && path.endsWith("/storage")) {
            storageChecks.incrementAndGet(); String id = path.split("/")[3];
            if (!failedRestore) storage.put(id, "hot_backed");
            reply(exchange, 200, Map.of("ok", true, "storage_state", failedRestore ? "cold" : "hot_backed",
                    "storage_job", Map.of("status", failedRestore ? "failed" : "completed"))); return;
        }
        if (path.startsWith("/f/")) {
            downloads.incrementAndGet();
            if (redirect.get() != null) { exchange.getResponseHeaders().set("Location", redirect.get()); exchange.sendResponseHeaders(302, -1); exchange.close(); return; }
            Stored stored = files.get(path.split("/")[2]);
            if (stored == null || downloadStatus.get() != 200) { reply(exchange, downloadStatus.get() == 200 ? 404 : downloadStatus.get(), Map.of("ok", false)); return; }
            exchange.sendResponseHeaders(200, stored.bytes.length); try (var output = exchange.getResponseBody()) { output.write(stored.bytes); } return;
        }
        reply(exchange, 404, Map.of("ok", false));
    }

    private Map<String, Object> metadata(Stored file) {
        return Map.of("ok", true, "file_id", file.id, "filename", file.filename,
                "download_url", maliciousUrl.get() == null ? base + "/f/" + file.id + "/" + file.filename : maliciousUrl.get(),
                "storage_state", storage.getOrDefault(file.id, "hot"));
    }
    private static void reply(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(value); exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
    private RemoteDailyCacheStore store() { return new RemoteDailyCacheStore(Duration.ofSeconds(2), Duration.ofSeconds(5), delays::add); }
    private static DailyStats day(LocalDate date, long count, String fingerprint) {
        DailyStats day = new DailyStats(); day.date = date.toString(); day.timezone = "Asia/Shanghai";
        day.fetchedAt = "2026-10-30T00:00:00Z"; day.sourceFingerprint = fingerprint; day.requestCount = 24;
        day.totalCount = count; day.totalFee = BigDecimal.valueOf(count).multiply(new BigDecimal("0.125"));
        day.buckets.add(new DailyStats.Bucket("channel", "17", count, day.totalFee)); return day;
    }
    private Stored seed(DailyStats day) throws Exception {
        String filename = "aiapi-p0-cache-v1-" + day.sourceFingerprint + "-" + day.date + "-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".json";
        Stored file = new Stored(UUID.randomUUID().toString(), filename, Json.MAPPER.writeValueAsBytes(day)); files.put(file.id, file); return file;
    }

    @Test void multipartPersistsOnlyAggregatesAndFreshInstanceRecoversWithoutLocalFiles() throws Exception {
        DailyStats day = day(DATE, 8, FP);
        RemoteDailyCacheStore first = store(); first.write(config, day);
        assertEquals(1, uploads.get()); assertTrue(multipart.contains("name=\"file\""));
        assertTrue(multipart.contains("filename=\"aiapi-p0-cache-v1-" + FP + "-" + DATE));
        assertFalse(multipart.contains(config.p0ApiKey)); assertFalse(multipart.contains(config.telegramBotToken));
        assertFalse(multipart.contains("p0Endpoint")); assertFalse(multipart.contains("rawTasks"));
        RemoteDailyCacheStore restarted = store();
        DailyStats restored = restarted.read(config, DATE, FP);
        assertEquals(8, restored.totalCount); assertEquals(new BigDecimal("1.000"), restored.totalFee);
        assertEquals(1, restarted.list(config, FP).size()); assertEquals(1, downloads.get());
        restored.totalCount = 999;
        assertEquals(8, restarted.read(config, DATE, FP).totalCount, "Caller mutation cannot corrupt the in-memory source cache");
        try (var local = Files.list(directory)) { assertEquals(0, local.count()); }
    }

    @Test void newestVersionsWinAndDatesUsesOnlyScopedMetadataWithShortLivedInventoryCache() throws Exception {
        RemoteDailyCacheStore writer = store();
        writer.write(config, day(DATE, 1, FP)); writer.write(config, day(DATE, 3, FP));
        writer.write(config, day(DATE.minusDays(1), 2, FP)); seed(day(DATE, 77, "b".repeat(64)));
        RemoteDailyCacheStore reader = store(); int beforeLists = listings.get();
        assertEquals(List.of(DATE, DATE.minusDays(1)), reader.dates(config, FP));
        assertEquals(0, downloads.get()); reader.dates(config, FP);
        assertEquals(beforeLists + 1, listings.get());
        assertEquals(3, reader.read(config, DATE, FP).totalCount);
        assertEquals(List.of(3L, 2L), reader.list(config, FP).stream().map(day -> day.totalCount).toList());
        assertEquals(2, downloads.get());
    }

    @Test void failedUploadDoesNotReplaceOldVersionAndExplicitFalseIsNeverSuccess() throws Exception {
        store().write(config, day(DATE, 1, FP));
        uploadMode.set("fail503");
        IOException failure = assertThrows(IOException.class, () -> store().write(config, day(DATE, 2, FP)));
        assertTrue(failure.getMessage().contains("上传失败")); assertFalse(failure.getMessage().contains("PRIVATE_UPSTREAM_BODY"));
        assertEquals(4, uploads.get()); assertEquals(1, files.size());
        assertEquals(1, store().read(config, DATE, FP).totalCount);
        uploadMode.set("false"); int before = uploads.get();
        assertThrows(IOException.class, () -> store().write(config, day(DATE, 3, FP)));
        assertEquals(before + 1, uploads.get()); assertEquals(1, files.size());
    }

    @Test void uncertainUploadIsRecoveredByItsUniqueFilenameWithoutDuplicatePosting() throws Exception {
        uploadMode.set("commit503");
        store().write(config, day(DATE, 5, FP));
        assertEquals(1, uploads.get()); assertEquals(1, files.size());
        assertEquals(5, store().read(config, DATE, FP).totalCount);
    }

    @Test void crossOriginMetadataAndRedirectsAreRejectedWithoutFollowingThem() throws Exception {
        seed(day(DATE, 1, FP)); maliciousUrl.set("https://elsewhere.invalid/f/private-token");
        IOException crossOrigin = assertThrows(IOException.class, () -> store().read(config, DATE, FP));
        assertTrue(crossOrigin.getMessage().contains("不安全")); assertFalse(crossOrigin.getMessage().contains("private-token"));
        assertEquals(0, downloads.get());
        maliciousUrl.set(null); redirect.set("https://elsewhere.invalid/f/private-token");
        IOException redirected = assertThrows(IOException.class, () -> store().read(config, DATE, FP));
        assertTrue(redirected.getMessage().contains("HTTP 302")); assertEquals(1, downloads.get());
    }

    @Test void coldFilesAreRestoredBeforeDownloadingAndFailedRestoresAreNotCacheMisses() throws Exception {
        Stored file = seed(day(DATE, 4, FP)); storage.put(file.id, "cold");
        assertEquals(4, store().read(config, DATE, FP).totalCount);
        assertEquals(1, restores.get()); assertEquals(1, storageChecks.get()); assertEquals(1, downloads.get());
        storage.put(file.id, "cold"); failedRestore = true;
        assertThrows(IOException.class, () -> store().read(config, DATE, FP));
        assertEquals(1, downloads.get());
    }

    @Test void missingFilesAreDistinctFromServiceOutagesAndBodyTimeoutIsBounded() throws Exception {
        assertNull(store().read(config, DATE, FP));
        listStatus.set(404);
        assertThrows(IOException.class, () -> store().read(config, DATE, FP));
        listStatus.set(200); Stored file = seed(day(DATE, 1, FP)); downloadStatus.set(404);
        assertNull(store().read(config, DATE, FP));
        downloadStatus.set(200); slowList = true;
        RemoteDailyCacheStore shortDeadline = new RemoteDailyCacheStore(Duration.ofMillis(80), Duration.ofMillis(150), delays::add);
        IOException timedOut = assertTimeout(Duration.ofSeconds(2), () -> assertThrows(IOException.class, () -> shortDeadline.read(config, DATE, FP)));
        assertTrue(timedOut.getMessage().contains("超时")); assertFalse(timedOut.getMessage().contains(file.filename));
    }

    @Test void uploadUsesConfiguredPathAndBadDownloadedMetadataNeverLeaksRawBody() throws Exception {
        config.cacheUploadUrl = base + "/custom/upload";
        store().write(config, day(DATE, 1, FP)); assertEquals(List.of("/custom/upload"), uploadPaths);
        Stored original = files.values().iterator().next();
        files.put(original.id, new Stored(original.id, original.filename, "{\"raw_secret\":\"PRIVATE_RAW_BODY\"}".getBytes(StandardCharsets.UTF_8)));
        IOException failure = assertThrows(IOException.class, () -> store().read(config, DATE, FP));
        assertFalse(failure.getMessage().contains("PRIVATE_RAW_BODY")); assertTrue(failure.getMessage().contains("校验失败"));
    }

    @AfterEach void close() { release.countDown(); if (server != null) server.stop(0); if (executor != null) executor.shutdownNow(); }
}
