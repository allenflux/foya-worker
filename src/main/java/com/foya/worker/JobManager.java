package com.foya.worker;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class JobManager implements AutoCloseable {
    @FunctionalInterface public interface Action { void run() throws Exception; }
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().name("report-job").factory());
    private final ArrayDeque<Map<String,Object>> logs = new ArrayDeque<>();
    private Map<String,Object> job;
    private boolean running;
    private java.util.function.UnaryOperator<String> redactor = java.util.function.UnaryOperator.identity();

    public synchronized void setRedactor(java.util.function.UnaryOperator<String> redactor) { this.redactor = redactor; }

    public synchronized Map<String,Object> submit(String kind, Action action) {
        if (running) throw new IllegalStateException("已有任务正在运行，请等待完成。");
        running = true;
        job = new LinkedHashMap<>();
        job.put("id", java.util.UUID.randomUUID().toString());
        job.put("kind", kind); job.put("state", "running"); job.put("message", "任务已开始");
        job.put("startedAt", Instant.now().toString());
        log("info", kind + "任务开始");
        executor.submit(() -> {
            try { action.run(); finish("completed", "任务已完成", null); }
            catch (Exception e) { if (e instanceof InterruptedException) Thread.currentThread().interrupt(); finish("failed", "任务失败", safeError(e)); }
        });
        return new LinkedHashMap<>(job);
    }
    public synchronized void progress(String message, Object progress) {
        if (job != null) { job.put("message", message); job.put("progress", progress); }
    }
    private synchronized void finish(String state, String message, String error) {
        job.put("state", state); job.put("message", message); job.put("finishedAt", Instant.now().toString());
        if (error != null) {
            error = redactor.apply(error);
            job.put("error", error);
            job.put("errorDetails", Map.of("jobId", job.get("id"), "time", job.get("finishedAt"),
                    "step", job.get("kind"), "message", error));
        }
        running = false; log(error == null ? "info" : "error", error == null ? message : error);
    }
    public synchronized void log(String level, String message) {
        logs.addFirst(Map.of("time", Instant.now().toString(), "level", level, "message", redactor.apply(message)));
        while (logs.size() > 100) logs.removeLast();
    }
    public synchronized Map<String,Object> status() {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("running", running); result.put("job", job == null ? null : new LinkedHashMap<>(job));
        result.put("logs", new ArrayList<>(logs));
        return result;
    }
    public synchronized boolean isRunning() { return running; }
    public static String safeError(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return "操作失败，请检查配置或网络连接。";
        // Only application errors with Chinese explanations are exposed; underlying client URLs may contain tokens.
        if (!message.matches("(?s).*[\\p{IsHan}].*")) return "操作失败，请检查配置、接口响应或网络连接。";
        return message.replaceAll("https?://[^\\s]+", "[接口地址]").replaceAll("(?i)(apikey|token|authorization)\\s*[:=]\\s*[^\\s,]+", "$1=[已隐藏]");
    }
    @Override public void close() { executor.shutdownNow(); }
}
