package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class WebServer implements AutoCloseable {
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private final WorkerService worker;
    private final HttpServer server;
    private final ExecutorService httpThreads = Executors.newVirtualThreadPerTaskExecutor();
    private final boolean loopbackOnly;

    public WebServer(WorkerService worker, String bind, int port) throws IOException {
        this.worker = worker;
        InetSocketAddress address = new InetSocketAddress(bind, port);
        loopbackOnly = address.getAddress() != null && address.getAddress().isLoopbackAddress();
        server = HttpServer.create(address, 64);
        server.setExecutor(httpThreads);
        server.createContext("/", this::handle);
    }
    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            var headers = exchange.getResponseHeaders();
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("Cache-Control", "no-store");
            headers.set("Content-Security-Policy", "default-src 'self'; script-src 'self' https://cdn.jsdelivr.net; style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'");
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (loopbackOnly && !localHost(exchange.getRequestHeaders().getFirst("Host"))) { json(exchange, 403, Map.of("error", "无效的后台访问地址。")); return; }
            if (path.equals("/health") && method.equals("GET")) { json(exchange, 200, Map.of("status", "ok")); return; }
            if (!path.startsWith("/api/")) { staticFile(exchange, path, method); return; }
            if (!method.equals("GET")) {
                if (!"foya-admin".equals(exchange.getRequestHeaders().getFirst("X-Requested-With"))) { json(exchange, 403, Map.of("error", "缺少后台请求标识。")); return; }
                String origin = exchange.getRequestHeaders().getFirst("Origin");
                String host = exchange.getRequestHeaders().getFirst("Host");
                if (origin != null && !originMatches(origin, host)) { json(exchange, 403, Map.of("error", "不允许跨站修改配置。")); return; }
            }
            switch (method + " " + path) {
                case "GET /api/config" -> json(exchange, 200, worker.configs.publicConfig());
                case "PUT /api/config" -> {
                    if (worker.jobs.isRunning()) throw new IllegalStateException("任务运行期间请等待完成后修改配置。");
                    var config = worker.configs.update(body(exchange));
                    worker.jobs.log("info", "后台配置已保存"); json(exchange, 200, config);
                }
                case "GET /api/mappings" -> json(exchange, 200, worker.configs.mappings());
                case "PUT /api/mappings" -> {
                    JsonNode input = body(exchange);
                    if (!input.isObject() || !input.path("channelNames").isObject() || !input.path("workflowNames").isObject()) throw new IllegalArgumentException("渠道和工作流映射应为 JSON 对象。");
                    worker.configs.update(Json.MAPPER.createObjectNode().setAll(Map.of("channelNames", input.get("channelNames"), "workflowNames", input.get("workflowNames"))));
                    worker.jobs.log("info", "渠道和工作流映射已更新"); json(exchange, 200, worker.configs.mappings());
                }
                case "GET /api/status" -> json(exchange, 200, worker.status());
                case "GET /api/report" -> json(exchange, 200, worker.reportResponse(worker.endDate(query(exchange, "endDate"))));
                case "POST /api/pull" -> {
                    JsonNode input = body(exchange);
                    json(exchange, 202, worker.pull(worker.endDate(input.path("endDate").asText("")), input.path("force").asBoolean(false)));
                }
                case "POST /api/send" -> {
                    JsonNode input = body(exchange);
                    json(exchange, 202, worker.send(worker.endDate(input.path("endDate").asText("")), input.path("botId").asText("")));
                }
                case "POST /api/test/p0" -> json(exchange, 200, worker.testP0(body(exchange)));
                case "POST /api/test/telegram" -> json(exchange, 200, worker.testTelegram(body(exchange)));
                case "POST /api/test/llm" -> json(exchange, 200, worker.testLlm(body(exchange)));
                case "POST /api/summary" -> {
                    JsonNode input = body(exchange);
                    json(exchange, 200, worker.summaryResponse(worker.endDate(input.path("endDate").asText(""))));
                }
                default -> json(exchange, 404, Map.of("error", "接口不存在或请求方式不正确。"));
            }
        } catch (WorkerService.MissingCacheException e) { json(exchange, 409, Map.of("error", e.getMessage())); }
        catch (IllegalStateException e) { json(exchange, 409, Map.of("error", JobManager.safeError(e))); }
        catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException e) { json(exchange, 400, Map.of("error", JobManager.safeError(e))); }
        catch (Exception e) { json(exchange, 502, Map.of("error", JobManager.safeError(e))); }
        finally { exchange.close(); }
    }
    private static boolean localHost(String host) {
        try { return host != null && java.util.Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(URI.create("http://" + host).getHost()); }
        catch (Exception e) { return false; }
    }
    private static boolean originMatches(String origin, String host) {
        try { return host != null && URI.create(origin).getRawAuthority().equalsIgnoreCase(host); }
        catch (Exception e) { return false; }
    }
    private static String query(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) return "";
        for (String part : raw.split("&")) { String[] pair = part.split("=", 2); if (pair[0].equals(name)) return pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : ""; }
        return "";
    }
    private static JsonNode body(HttpExchange exchange) throws IOException {
        if (!java.util.Objects.toString(exchange.getRequestHeaders().getFirst("Content-Type"), "").toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) throw new IllegalArgumentException("请求内容应为 JSON。");
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) throw new IllegalArgumentException("配置文件过大，最多 2 MB。");
        return bytes.length == 0 ? Json.MAPPER.createObjectNode() : Json.MAPPER.readTree(bytes);
    }
    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] content = Json.MAPPER.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, content.length); exchange.getResponseBody().write(content);
    }
    private static void staticFile(HttpExchange exchange, String path, String method) throws IOException {
        if (!method.equals("GET") && !method.equals("HEAD")) { json(exchange, 405, Map.of("error", "请求方式不允许。")); return; }
        String resource = switch (path) { case "/", "/index.html" -> "index.html"; case "/app.js" -> "app.js"; case "/styles.css" -> "styles.css"; default -> null; };
        if (resource == null) { json(exchange, 404, Map.of("error", "页面不存在。")); return; }
        try (var input = WebServer.class.getResourceAsStream("/web/" + resource)) {
            if (input == null) { json(exchange, 404, Map.of("error", "页面资源未找到。")); return; }
            byte[] content = input.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", resource.endsWith("js") ? "text/javascript; charset=utf-8" : resource.endsWith("css") ? "text/css; charset=utf-8" : "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, method.equals("HEAD") ? -1 : content.length);
            if (!method.equals("HEAD")) exchange.getResponseBody().write(content);
        }
    }
    @Override public void close() { server.stop(1); httpThreads.shutdownNow(); }
}
