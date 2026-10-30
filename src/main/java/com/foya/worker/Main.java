package com.foya.worker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

public final class Main {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(env("PORT", "8080"));
        if (args.length > 0 && args[0].equals("--healthcheck")) {
            try {
                var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health")).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.discarding());
                System.exit(response.statusCode() == 200 ? 0 : 1);
            } catch (Exception e) { System.exit(1); }
            return;
        }
        String bind = env("FOYA_BIND", "127.0.0.1");
        WorkerService worker = new WorkerService(Path.of(env("FOYA_DATA_DIR", "data")).toAbsolutePath());
        WebServer web = new WebServer(worker, bind, port);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { web.close(); worker.close(); }));
        web.start(); worker.startScheduler();
        System.out.println("AIAPI 统计后台已启动：http://" + (bind.equals("0.0.0.0") ? "localhost" : bind) + ":" + web.port());
        System.out.println("统计时区默认 Asia/Shanghai；请在网页配置 p0 和 Telegram。");
    }
    private static String env(String name, String fallback) { String value = System.getenv(name); return value == null || value.isBlank() ? fallback : value; }
}
