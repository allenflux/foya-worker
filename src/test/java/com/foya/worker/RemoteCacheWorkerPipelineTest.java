package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the production default remote cache against a loopback FluxDrop fixture, including process-style restart. */
class RemoteCacheWorkerPipelineTest {
    @TempDir Path directory;
    private record RemoteObject(String filename, String id, byte[] json) {}

    @Test
    void defaultWorkerUploadsDayAndRestartReadsRemoteWithoutLocalCacheOrIndex() throws Exception {
        Path runtime = directory.resolve("runtime");
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1);
        AtomicInteger p0Calls = new AtomicInteger();
        AtomicInteger downloads = new AtomicInteger();
        ConcurrentLinkedQueue<RemoteObject> objects = new ConcurrentLinkedQueue<>();
        var fixtureThreads = Executors.newVirtualThreadPerTaskExecutor();
        HttpServer fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.setExecutor(fixtureThreads);
        String fixtureBase = "http://127.0.0.1:" + fixture.getAddress().getPort();
        fixture.createContext("/api/tasks/list", exchange -> {
            p0Calls.incrementAndGet();
            LocalDateTime hour = LocalDateTime.parse(query(exchange).get("start_time"), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            reply(exchange, 200, Map.of("code", 0, "data", Map.of("total", 1, "has_more", false,
                    "list", List.of(Map.of("id", hour.toString(), "hash_key", "remote_hash", "task_type", "remote_type",
                            "created_at", hour.plusMinutes(15).toString(), "status", 2)))));
        });
        fixture.createContext("/upload", exchange -> {
            String multipart = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var filename = Pattern.compile("filename=\"([^\"]+)\"").matcher(multipart);
            int content = multipart.indexOf("\r\n\r\n");
            int ending = multipart.lastIndexOf("\r\n--");
            if (!"POST".equals(exchange.getRequestMethod()) || !filename.find() || content < 0 || ending <= content) {
                reply(exchange, 400, Map.of("ok", false)); return;
            }
            byte[] json = multipart.substring(content + 4, ending).getBytes(StandardCharsets.UTF_8);
            RemoteObject object = new RemoteObject(filename.group(1), Integer.toString(objects.size() + 1), json);
            objects.add(object);
            reply(exchange, 201, metadata(fixtureBase, object));
        });
        fixture.createContext("/api/files", exchange -> reply(exchange, 200,
                Map.of("ok", true, "files", objects.stream().map(object -> metadata(fixtureBase, object)).toList())));
        fixture.createContext("/f/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            for (RemoteObject object : objects) if (path.equals("/f/" + object.id() + "/" + object.filename())) {
                downloads.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, object.json().length);
                exchange.getResponseBody().write(object.json()); exchange.close(); return;
            }
            reply(exchange, 404, Map.of("ok", false));
        });
        fixture.start();
        Map<String, Object> startupConfig = Map.of("cacheUploadUrl", fixtureBase + "/upload",
                "p0Endpoint", fixtureBase + "/api/tasks/list", "timezone", "Asia/Shanghai",
                "reportDays", 1, "p0MaxAttempts", 1, "llmEnabled", false);
        // Install loopback endpoints before construction as well, so even future startup probes stay local.
        FilesSupport.writeJson(runtime.resolve("config.json"), startupConfig);
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            String original;
            try (WorkerService worker = new WorkerService(runtime);
                 WebServer web = new WebServer(worker, "127.0.0.1", 0)) {
                web.start();
                String base = "http://127.0.0.1:" + web.port();
                assertEquals(200, request(http, base, "PUT", "/api/config", startupConfig).statusCode());
                assertEquals(202, request(http, base, "POST", "/api/pull", Map.of("endDate", day.toString())).statusCode());
                awaitJob(worker);
                HttpResponse<String> response = request(http, base, "GET", "/api/report?endDate=" + day, null);
                assertEquals(200, response.statusCode(), response.body());
                JsonNode report = Json.MAPPER.readTree(response.body());
                assertEquals(24, report.path("latestCount").asLong());
                original = report.path("text").asText();
                assertEquals(24, p0Calls.get());
                assertEquals(1, objects.size());
                assertEquals(24, Json.MAPPER.readTree(objects.element().json()).path("totalCount").asLong());
                assertNoLocalCache(runtime);
            }
            int previousDownloads = downloads.get();
            // This constructor intentionally uses the production default store, not the local test-store helper.
            try (WorkerService restarted = new WorkerService(runtime);
                 WebServer web = new WebServer(restarted, "127.0.0.1", 0)) {
                web.start();
                String base = "http://127.0.0.1:" + web.port();
                HttpResponse<String> response = request(http, base, "GET", "/api/report?endDate=" + day, null);
                assertEquals(200, response.statusCode(), response.body());
                JsonNode report = Json.MAPPER.readTree(response.body());
                assertEquals(24, report.path("latestCount").asLong());
                assertEquals(original, report.path("text").asText());
                assertTrue(downloads.get() > previousDownloads, "restart must fetch the day from remote storage");
                HttpResponse<String> statusResponse = request(http, base, "GET", "/api/status", null);
                assertEquals(200, statusResponse.statusCode(), statusResponse.body());
                JsonNode status = Json.MAPPER.readTree(statusResponse.body());
                assertEquals(day.toString(), status.path("cacheDays").get(0).asText());
                assertEquals(24, p0Calls.get(), "restarted reporting must reuse the uploaded day");
                assertEquals(1, objects.size(), "reporting must not create duplicate uploads");
                assertNoLocalCache(runtime);
            }
        } finally {
            fixture.stop(0);
            fixtureThreads.shutdownNow();
        }
    }

    private static Map<String, Object> metadata(String base, RemoteObject object) {
        return Map.of("ok", true, "filename", object.filename(), "file_id", object.id(),
                "download_url", base + "/f/" + object.id() + "/" + object.filename());
    }
    private static void assertNoLocalCache(Path runtime) throws IOException {
        assertFalse(Files.exists(runtime.resolve("cache")));
        try (var files = Files.walk(runtime)) {
            assertTrue(files.filter(Files::isRegularFile).noneMatch(path -> {
                String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                return name.contains("cache") || name.contains("index");
            }), "production must not write a local daily cache or cache index");
        }
    }
    private static HttpResponse<String> request(HttpClient client, String base, String method, String path, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(3))
                .header("X-Requested-With", "foya-admin");
        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());
        else request.header("Content-Type", "application/json").method(method,
                HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body), StandardCharsets.UTF_8));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void awaitJob(WorkerService worker) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (worker.jobs.isRunning() && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(worker.jobs.isRunning(), "bounded remote-cache job did not complete");
        JsonNode job = Json.MAPPER.valueToTree(worker.jobs.status()).path("job");
        assertEquals("completed", job.path("state").asText(), job.toPrettyString());
    }
    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new HashMap<>();
        for (String part : exchange.getRequestURI().getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            result.put(pair[0], pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }
    private static void reply(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = Json.MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
