package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WebServerTest {
    @TempDir Path directory;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private WorkerService worker;
    private WebServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        worker = TestWorkers.create(directory);
        server = new WebServer(worker, "0.0.0.0", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void close() {
        if (server != null) server.close();
        if (worker != null) worker.close();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder putConfig(String json) {
        return request("/api/config").header("X-Requested-With", "foya-admin")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json));
    }

    @Test
    void backendApisAndPageAreAvailableWithoutPassword() throws Exception {
        for (String path : new String[]{"/api/config", "/api/status", "/api/mappings"}) {
            assertEquals(200, send(request(path).GET()).statusCode(), path + " should allow direct access");
        }
        HttpResponse<String> page = send(request("/").GET());
        assertEquals(200, page.statusCode());
        assertFalse(page.body().contains("auth-dialog"), "the page must not include a login dialog");
    }

    @Test
    void validConfigurationPutAllowsAnonymousP0AndNeverReturnsSecrets() throws Exception {
        HttpResponse<String> anonymous = send(putConfig("{\"p0ApiKey\":\"\",\"reportDays\":7,\"sendTime\":\"08:30\"}"));
        assertEquals(200, anonymous.statusCode());
        JsonNode saved = Json.MAPPER.readTree(anonymous.body());
        assertEquals(7, saved.path("reportDays").asInt());
        assertEquals("08:30", saved.path("sendTime").asText());
        HttpResponse<String> credentialSave = send(putConfig("{\"p0ApiKey\":\"PRIVATE_P0_SECRET\",\"telegramBotToken\":\"123456:PRIVATE_BOT_SECRET\"}"));
        assertEquals(200, credentialSave.statusCode());
        HttpResponse<String> publicConfig = send(request("/api/config").GET());
        assertEquals(200, publicConfig.statusCode());
        for (String body : new String[]{credentialSave.body(), publicConfig.body()}) {
            JsonNode response = Json.MAPPER.readTree(body);
            assertFalse(response.has("p0ApiKey"));
            assertFalse(response.has("telegramBotToken"));
            assertTrue(response.path("p0ApiKeyConfigured").asBoolean());
            assertTrue(response.path("telegramBotTokenConfigured").asBoolean());
            assertFalse(body.contains("PRIVATE_P0_SECRET"));
            assertFalse(body.contains("PRIVATE_BOT_SECRET"));
        }
    }

    @Test
    void mutationRequiresCustomRequestHeader() throws Exception {
        HttpResponse<String> response = send(request("/api/config")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"reportDays\":7}")));
        assertEquals(403, response.statusCode());
        assertEquals(10, worker.configs.snapshot().reportDays);
    }

    @Test
    void crossOriginMutationIsRejectedAndMatchingOriginCanSave() throws Exception {
        assertEquals(403, send(putConfig("{\"reportDays\":7}").header("Origin", "https://evil.example")).statusCode());
        assertEquals(10, worker.configs.snapshot().reportDays);
        assertEquals(200, send(putConfig("{\"reportDays\":7}").header("Origin", base)).statusCode());
        assertEquals(7, worker.configs.snapshot().reportDays);
    }

    @Test
    void missingCacheIsConflictAndUnknownApiIsNotFound() throws Exception {
        HttpResponse<String> report = send(request("/api/report").GET());
        assertEquals(409, report.statusCode());
        JsonNode emptyReport = Json.MAPPER.readTree(report.body());
        assertTrue(emptyReport.path("error").asText().contains("缓存"));
        assertEquals(409, emptyReport.path("errorDetails").path("status").asInt());
        assertEquals("/api/report", emptyReport.path("errorDetails").path("path").asText());
        HttpResponse<String> status = send(request("/api/status").GET());
        assertEquals(200, status.statusCode());
        JsonNode emptyState = Json.MAPPER.readTree(status.body());
        assertTrue(emptyState.path("cacheDays").isEmpty());
        assertFalse(emptyState.path("running").asBoolean());
        assertEquals(404, send(request("/api/does-not-exist").GET()).statusCode());
    }

    @Test
    void staticEntryIsPublicWithNoStoreHeaders() throws Exception {
        HttpResponse<String> response = send(request("/").GET());
        assertEquals(200, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
        assertTrue(response.body().toLowerCase().contains("<!doctype html>"));
    }

    @Test
    void missingContentTypeIsBadRequestInsteadOfServerError() throws Exception {
        HttpResponse<String> response = send(request("/api/config")
                .header("X-Requested-With", "foya-admin")
                .PUT(HttpRequest.BodyPublishers.ofString("{}")));
        assertEquals(400, response.statusCode());
        assertTrue(Json.MAPPER.readTree(response.body()).path("error").asText().contains("JSON"));
    }

    @Test
    void invalidConfigReturnsCopyableErrorDetailsWithoutQueryOrRawBody() throws Exception {
        String rawMarker = "RAW_BODY_MUST_NOT_ECHO";
        HttpResponse<String> response = send(request("/api/config?trace=PRIVATE_QUERY_MUST_NOT_ECHO")
                .header("X-Requested-With", "foya-admin")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"reportDays\":99,\"bodyMarker\":\"" + rawMarker + "\"}")));
        assertEquals(400, response.statusCode());
        JsonNode body = Json.MAPPER.readTree(response.body());
        JsonNode details = body.path("errorDetails");
        assertTrue(details.isObject());
        String requestId = response.headers().firstValue("X-Request-ID").orElseThrow();
        assertDoesNotThrow(() -> UUID.fromString(requestId));
        assertEquals(requestId, details.path("requestId").asText());
        assertDoesNotThrow(() -> Instant.parse(details.path("time").asText()));
        assertEquals("PUT", details.path("method").asText());
        assertEquals("/api/config", details.path("path").asText());
        assertEquals(400, details.path("status").asInt());
        assertEquals(body.path("error").asText(), details.path("message").asText());
        assertTrue(details.path("message").asText().contains("天数"));
        assertFalse(response.body().contains(rawMarker));
        assertFalse(response.body().contains("bodyMarker"));
        assertFalse(response.body().contains("PRIVATE_QUERY_MUST_NOT_ECHO"));
        assertFalse(details.has("requestBody"));
        assertEquals(10, worker.configs.snapshot().reportDays);
    }

    @Test
    void asynchronousErrorDetailsAndLogsRedactUnlabelledConfiguredSecrets() throws Exception {
        String llmSecret = "sk-unlabelled-private-value";
        String botSecret = "123456:BOT_PRIVATE_EXACT";
        String p0Secret = "bare-private-p0-value";
        worker.configs.update(Json.MAPPER.valueToTree(Map.of("llmApiKey", llmSecret, "p0ApiKey", p0Secret,
                "telegramBots", List.of(Map.of("id", "error-bot", "token", botSecret)))));
        String privateMessage = "模拟异常的原始说明 " + llmSecret + " " + botSecret + " " + p0Secret;
        worker.jobs.submit("模拟失败", () -> {
            worker.jobs.log("error", privateMessage);
            throw new IOException(privateMessage);
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (worker.jobs.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(worker.jobs.isRunning());
        HttpResponse<String> response = send(request("/api/status").GET());
        assertEquals(200, response.statusCode());
        JsonNode status = Json.MAPPER.readTree(response.body());
        JsonNode job = status.path("job");
        JsonNode details = job.path("errorDetails");
        assertEquals("failed", job.path("state").asText());
        assertDoesNotThrow(() -> UUID.fromString(job.path("id").asText()));
        assertEquals(job.path("id").asText(), details.path("jobId").asText());
        assertEquals("模拟失败", details.path("step").asText());
        assertDoesNotThrow(() -> Instant.parse(details.path("time").asText()));
        assertEquals(job.path("error").asText(), details.path("message").asText());
        assertTrue(details.path("message").asText().contains("模拟异常"));
        assertTrue(details.path("message").asText().contains("[已隐藏]"));
        assertFalse(status.path("logs").isEmpty());
        for (String secret : List.of(llmSecret, botSecret, p0Secret)) {
            assertFalse(response.body().contains(secret), "status, job details and logs must not expose configured secrets");
        }
    }
}
