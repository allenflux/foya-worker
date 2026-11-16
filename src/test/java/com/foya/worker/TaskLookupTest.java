package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class TaskLookupTest {
    private static final String KEY = "P0_TEST_LOOKUP_KEY";
    private static final String REQUEST_KEY = "P0_TEST_REQUEST_KEY";
    private static final String UUID = "77777777-0303-4040-9090-111111111111";
    private static final String LOOKUP = "/api/tasks/lookup";

    private record Reply(int status, String body) {}
    private record Request(String method, String target, String key, String accept) {}

    @TempDir Path directory;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile Reply reply = new Reply(400, "{}");
    private HttpServer upstream;
    private WorkerService worker;
    private WebServer server;
    private HttpClient client;

    @BeforeEach void start() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toASCIIString(),
                    exchange.getRequestHeaders().getFirst("apikey"), exchange.getRequestHeaders().getFirst("Accept")));
            Reply response = reply;
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            try {
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(response.status(), body.length);
                exchange.getResponseBody().write(body);
            } finally { exchange.close(); }
        });
        upstream.start();
        worker = TestWorkers.create(directory);
        worker.configs.update(Json.MAPPER.valueToTree(Map.of(
                "p0Endpoint", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/api/tasks/list",
                "p0ApiKey", KEY)));
        server = new WebServer(worker, "127.0.0.1", 0);
        server.start();
        client = HttpClient.newHttpClient();
    }

    @AfterEach void stop() {
        if (client != null) client.close();
        if (server != null) server.close();
        if (worker != null) worker.close();
        if (upstream != null) upstream.stop(0);
    }

    @Test void dispatchesOneExactPublicRequestPerIdentifierAndSanitizesTaskDetails() throws Exception {
        reply = new Reply(200, """
                {"code": 0, "data": {"task": {"uuid": "%s", "task_id": "task a+b?c&d", "status": 2,
                   "prompt": "sample prompt", "meta": {"api_key": "UPSTREAM_FIELD_SECRET", "note": "%s %s"}}}}
                """.formatted(UUID, KEY, REQUEST_KEY));
        HttpResponse<String> byTaskId = lookup(Map.of("task_id", "task a+b?c&d", "apiKey", REQUEST_KEY));
        JsonNode task = task(byTaskId);
        assertEquals("task a+b?c&d", task.path("task_id").asText());
        assertEquals("sample prompt", task.path("prompt").asText());
        assertFalse(byTaskId.body().contains("UPSTREAM_FIELD_SECRET"));
        assertFalse(byTaskId.body().contains(KEY));
        assertFalse(byTaskId.body().contains(REQUEST_KEY));
        assertEquals(KEY, worker.configs.snapshot().p0ApiKey);

        reply = new Reply(200, """
                {"uuid": "%s", "task_id": "task-a", "status": 0}
                """.formatted(UUID));
        JsonNode byUuid = task(lookup(Map.of("uuid", UUID)));
        assertEquals(UUID, byUuid.path("uuid").asText());
        assertEquals(0, byUuid.path("status").asInt(-1));
        assertEquals(List.of(
                new Request("GET", "/api/public/task?task_id=task+a%2Bb%3Fc%26d", REQUEST_KEY, "application/json"),
                new Request("GET", "/api/public/task/uuid?uuid=" + UUID, KEY, "application/json")), requests);
    }

    @Test void rejectsMissingBlankOrAmbiguousIdentifiersBeforeQueryingP0() throws Exception {
        for (Map<String, String> input : List.of(Map.<String, String>of(), Map.of("uuid", "  "),
                Map.of("task_id", ""), Map.of("uuid", UUID, "task_id", "task-a")))
            assertEquals(400, lookup(input).statusCode(), input.toString());
        assertTrue(requests.isEmpty());
    }

    @Test void requiresCredentialWhenNoBackgroundKeyIsConfigured() throws Exception {
        worker.configs.update(Json.MAPPER.valueToTree(Map.of("clearP0ApiKey", true)));
        assertLookupError(400, lookup(Map.of("uuid", UUID)));
        assertTrue(requests.isEmpty());
        reply = new Reply(200, "{\"uuid\":\"" + UUID + "\"}");
        assertEquals(UUID, task(lookup(Map.of("uuid", UUID, "apiKey", REQUEST_KEY))).path("uuid").asText());
        assertEquals(REQUEST_KEY, requests.getFirst().key());
        assertTrue(worker.configs.snapshot().p0ApiKey.isBlank());
    }

    @Test void distinguishesMissingTaskFromUpstreamPermissionErrorWithoutLeakingDetails() throws Exception {
        reply = new Reply(404, "{\"detail\":\"UPSTREAM_PRIVATE_DETAIL " + KEY + "\"}");
        assertLookupError(404, lookup(Map.of("uuid", UUID)));
        reply = new Reply(403, "{\"detail\":\"UPSTREAM_PRIVATE_DETAIL " + REQUEST_KEY + "\"}");
        assertLookupError(502, lookup(Map.of("uuid", UUID, "apiKey", REQUEST_KEY)));
        assertEquals(2, requests.size());
    }

    @Test void refusesAnUpstreamRecordForADifferentIdentifier() throws Exception {
        reply = new Reply(200, "{\"task_id\":\"different-task\",\"prompt\":\"UNRELATED_TASK_CONTENT\"}");
        HttpResponse<String> response = lookup(Map.of("task_id", "requested-task"));
        assertLookupError(502, response);
        assertFalse(response.body().contains("UNRELATED_TASK_CONTENT"));
        assertEquals(1, requests.size());
    }

    private HttpResponse<String> lookup(Map<String, String> input) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + LOOKUP))
                .timeout(Duration.ofSeconds(5)).header("X-Requested-With", "foya-admin")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(input))).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode task(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        JsonNode task = Json.MAPPER.readTree(response.body()).path("task");
        assertTrue(task.isObject());
        return task;
    }

    private static void assertLookupError(int expected, HttpResponse<String> response) throws Exception {
        assertEquals(expected, response.statusCode());
        JsonNode details = Json.MAPPER.readTree(response.body()).path("errorDetails");
        assertEquals(expected, details.path("status").asInt());
        assertEquals(LOOKUP, details.path("path").asText());
        for (String value : List.of(KEY, REQUEST_KEY, UUID, "UPSTREAM_PRIVATE_DETAIL")) assertFalse(response.body().contains(value));
    }
}
