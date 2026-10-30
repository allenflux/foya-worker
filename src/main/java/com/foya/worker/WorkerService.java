package com.foya.worker;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class WorkerService implements AutoCloseable {
    public final ConfigStore configs;
    public final JobManager jobs = new JobManager();
    private final StatsService stats;
    private final ReportService reports = new ReportService();
    private final TelegramClient telegram;
    private final TelegramDirectory directory;
    private record DeliveryTarget(TelegramBotConfig bot, AppConfig config) {}
    private final LlmClient llm = new LlmClient();
    private record CachedSummary(Instant created, LlmClient.Summary value) {}
    private final java.util.concurrent.ConcurrentHashMap<String, CachedSummary> summaryCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Path deliveriesFile;
    private final Map<String,Map<String,Object>> deliveries;
    private final Map<String,Integer> failedPullAttempts = new java.util.concurrent.ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("daily-scheduler").factory());
    private final ScheduledExecutorService discoveryScheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("telegram-discovery").factory());
    private final Map<String,Instant> nextScheduleAttempts = new java.util.concurrent.ConcurrentHashMap<>();

    public WorkerService(Path dataDir) throws IOException { this(dataDir, new TelegramClient()); }
    WorkerService(Path dataDir, TelegramClient telegram) throws IOException {
        this(dataDir, telegram, new TelegramDirectory(dataDir));
    }
    WorkerService(Path dataDir, TelegramClient telegram, TelegramDirectory directory) throws IOException {
        this(dataDir, telegram, directory, new RemoteDailyCacheStore());
    }
    WorkerService(Path dataDir, TelegramClient telegram, TelegramDirectory directory, DailyCacheStore cacheStore) throws IOException {
        configs = new ConfigStore(dataDir); stats = new StatsService(cacheStore); this.telegram = telegram;
        jobs.setRedactor(this::redactSecrets);
        this.directory = directory;
        deliveriesFile = dataDir.resolve("deliveries.json");
        deliveries = Files.exists(deliveriesFile) ? Json.MAPPER.readValue(Files.readAllBytes(deliveriesFile),
                new com.fasterxml.jackson.core.type.TypeReference<>() {}) : new LinkedHashMap<>();
    }
    public void startScheduler() {
        scheduler.scheduleWithFixedDelay(this::scheduledTick, 5, 30, TimeUnit.SECONDS);
        discoveryScheduler.scheduleWithFixedDelay(() -> {
            try { directory.poll(configs.snapshot().telegramBots, message -> jobs.log("info", message)); }
            catch (Exception e) { jobs.log("warn", "Telegram 接收会话登记失败：" + JobManager.safeError(e)); }
        }, 1, 15, TimeUnit.SECONDS);
    }
    public LocalDate endDate(String value) {
        AppConfig config = configs.snapshot();
        LocalDate yesterday = LocalDate.now(ZoneId.of(config.timezone)).minusDays(1);
        if (value == null || value.isBlank()) return yesterday;
        try {
            LocalDate result = LocalDate.parse(value);
            if (result.isAfter(yesterday)) throw new IllegalArgumentException("只能统计已结束的完整日期，最晚为昨天。");
            return result;
        } catch (DateTimeException e) { throw new IllegalArgumentException("日期格式应为 YYYY-MM-DD。"); }
    }
    private ReportService.Report report(AppConfig config, LocalDate endDate) throws IOException {
        List<DailyStats> cached = stats.availableCachedDays(config, endDate, config.reportDays);
        if (cached.size() != config.reportDays || !cached.getFirst().date.equals(endDate.toString()))
            throw new MissingCacheException("缺少统计缓存，请先点击“刷新统计”补齐最近 " + config.reportDays + " 天。");
        return reports.build(config, cached);
    }
    public Map<String,Object> reportResponse(LocalDate endDate) throws IOException {
        return response(report(configs.snapshot(), endDate));
    }
    static Map<String,Object> response(ReportService.Report report) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("text", report.text()); result.put("startDate", report.startDate()); result.put("endDate", report.endDate());
        result.put("days", report.daily().stream().map(d -> {
            Map<String,Object> day = new LinkedHashMap<>();
            day.put("date", d.date); day.put("count", d.totalCount);
            day.put("fee", report.amountsEnabled() && d.missingFeeCount == 0 ? d.totalFee : null);
            day.put("missingFeeCount", d.missingFeeCount);
            return day;
        }).toList());
        result.put("channels", rankings(report.channels(), report.amountsEnabled())); result.put("workflows", rankings(report.workflows(), report.amountsEnabled()));
        result.put("totalCount", report.daily().stream().mapToLong(d -> d.totalCount).sum());
        result.put("totalFee", report.amountsEnabled() && report.missingFeeCount() == 0 ? report.daily().stream().map(d -> d.totalFee).reduce(BigDecimal.ZERO, BigDecimal::add) : null);
        result.put("latestCount", report.daily().getFirst().totalCount);
        result.put("latestFee", report.amountsEnabled() && report.daily().getFirst().missingFeeCount == 0 ? report.daily().getFirst().totalFee : null);
        result.put("feeComplete", report.amountsEnabled() && report.missingFeeCount() == 0);
        result.put("latestFeeComplete", report.amountsEnabled() && report.daily().getFirst().missingFeeCount == 0);
        result.put("amountsEnabled", report.amountsEnabled());
        result.put("amountSource", report.amountsEnabled() ? "task_type_price" : "disabled");
        result.put("missingFeeCount", report.missingFeeCount());
        result.put("channelsBy", report.channelsBy());
        return result;
    }
    private static List<Map<String,Object>> rankings(List<ReportService.Ranking> rows, boolean amountsEnabled) {
        return rows.stream().map(r -> {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("key", r.name()); row.put("name", r.name()); row.put("count", r.count());
            row.put("fee", amountsEnabled && r.missingFeeCount() == 0 ? r.fee() : null);
            row.put("share", r.percent()); row.put("missingFeeCount", r.missingFeeCount());
            return row;
        }).toList();
    }
    public Map<String,Object> pull(LocalDate endDate, boolean force) {
        AppConfig config = configs.snapshot(); config.validate();
        return jobs.submit("拉取统计", () -> pullRange(config, endDate, force));
    }
    private void pullRange(AppConfig config, LocalDate endDate, boolean force) throws Exception {
        for (int i = 0; i < config.reportDays; i++) {
            LocalDate day = endDate.minusDays(i);
            jobs.progress("正在检查 " + day + " 缓存（" + (i + 1) + "/" + config.reportDays + "）", Map.of("date", day.toString()));
            stats.pullDay(config, day, force && i == 0, p -> jobs.progress(
                    p.date() + " 已完成 " + p.completedHours() + "/" + p.totalHours() + " 小时，" + p.requests() + " 次请求", p));
        }
        report(config, endDate);
        jobs.log("info", "统计缓存已就绪：" + endDate.minusDays(config.reportDays - 1L) + " 至 " + endDate);
    }
    public Map<String,Object> send(LocalDate endDate) throws IOException {
        return send(endDate, "");
    }
    public Map<String,Object> send(LocalDate endDate, String botId) throws IOException {
        AppConfig config = configs.snapshot(); config.validate();
        ReportService.Report report = report(config, endDate);
        if (botId != null && !botId.isBlank() && config.telegramBots.stream().noneMatch(bot -> bot.id.equals(botId) && bot.enabled))
            throw new IllegalArgumentException("所选机器人不存在或未启用。");
        List<DeliveryTarget> targets = targets(config).stream().filter(t -> botId == null || botId.isBlank() || t.bot().id.equals(botId)).toList();
        if (targets.isEmpty()) throw new IllegalArgumentException("尚无可发送的接收会话。请配置并启用机器人，将其加入群组或私聊发送 /start；也可填写手动 Chat ID。");
        return jobs.submit("发送报表", () -> deliverAll(config, endDate, report, targets, "manual"));
    }
    private List<DeliveryTarget> targets(AppConfig config) {
        Map<String,DeliveryTarget> targets = new LinkedHashMap<>();
        for (TelegramBotConfig bot : config.telegramBots) {
            if (!bot.enabled || bot.token.isBlank()) continue;
            Set<String> chats = new LinkedHashSet<>();
            if (!bot.chatId.isBlank()) chats.add(bot.chatId);
            if (bot.autoDiscover) directory.chatsFor(bot).forEach(chat -> chats.add(chat.chatId()));
            for (String chat : chats) {
                AppConfig target = Json.MAPPER.convertValue(config, AppConfig.class);
                target.telegramBotToken = bot.token; target.telegramChatId = chat; target.telegramThreadId = bot.threadId;
                targets.putIfAbsent(deliveryKey(target, LocalDate.of(2000, 1, 1)), new DeliveryTarget(bot, target));
            }
        }
        return List.copyOf(targets.values());
    }
    private void deliverAll(AppConfig config, LocalDate endDate, ReportService.Report report, List<DeliveryTarget> targets, String source) throws IOException {
        if (config.llmEnabled && !config.llmApiKey.isBlank()) jobs.progress("正在生成 AI 附加总结，失败仍发送原始日报", Map.of());
        Map<String,Object> enriched = summaryResponse(config, report);
        List<String> failures = new ArrayList<>();
        for (DeliveryTarget target : targets) {
            try { deliver(target, endDate, enriched.get("text").toString(), source); }
            catch (Exception e) {
                String message = target.bot().name + "（" + target.config().telegramChatId + "）：" + JobManager.safeError(e);
                failures.add(message); jobs.log("error", message);
            }
        }
        if (!failures.isEmpty()) throw new IOException("共有 " + failures.size() + "/" + targets.size() + " 个接收目标发送失败，其他目标已继续处理。" + String.join("；", failures));
    }
    private Map<String,Object> deliveryState(DeliveryTarget target, LocalDate endDate, String status, String source) {
        return Map.of("date", endDate.toString(), "status", status, "time", Instant.now().toString(), "source", source,
                "botId", target.bot().id, "botName", target.bot().name, "chatId", target.config().telegramChatId);
    }
    private void deliver(DeliveryTarget target, LocalDate endDate, String text, String source) throws IOException {
        AppConfig config = target.config();
        String key = deliveryKey(config, endDate);
        saveDelivery(key, deliveryState(target, endDate, "sending", source));
        jobs.progress("正在发送：" + target.bot().name, Map.of("botId", target.bot().id, "chatId", config.telegramChatId));
        try {
            Map<String,Object> result = telegram.sendReport(config, text);
            saveDelivery(key, deliveryState(target, endDate, "sent", source));
            jobs.log("info", target.bot().name + " → " + config.telegramChatId + "：" + Objects.toString(result.get("message"), "Telegram 报表发送成功"));
        } catch (Exception e) {
            saveDelivery(key, deliveryState(target, endDate, "needs_review", source));
            throw new IOException("Telegram 发送失败或投递状态待确认，定时任务不会自动重发；请核对聊天记录后手动发送。" + JobManager.safeError(e));
        }
    }
    private synchronized void saveDelivery(String key, Map<String,Object> state) throws IOException {
        deliveries.put(key, state);
        FilesSupport.writeJson(deliveriesFile, deliveries);
    }
    private synchronized boolean alreadyAttempted(String key) { return deliveries.containsKey(key); }
    private static String deliveryKey(AppConfig config, LocalDate endDate) {
        try {
            String scope = config.timezone + "\n" + config.telegramChatId + "\n" + config.telegramThreadId + "\n" + config.telegramBotToken;
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8)));
            return endDate + ":" + hash;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private void scheduledTick() {
        try {
            AppConfig config = configs.snapshot();
            if (!config.scheduleEnabled || jobs.isRunning()) return;
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(config.timezone));
            LocalDate endDate = now.toLocalDate().minusDays(1);
            List<DeliveryTarget> due = targets(config).stream().filter(target -> {
                String time = target.bot().sendTime.isBlank() ? config.sendTime : target.bot().sendTime;
                String key = deliveryKey(target.config(), endDate);
                return !now.toLocalTime().isBefore(LocalTime.parse(time)) && !alreadyAttempted(key)
                        && failedPullAttempts.getOrDefault(key, 0) < 3 && !Instant.now().isBefore(nextScheduleAttempts.getOrDefault(key, Instant.MIN));
            }).toList();
            if (due.isEmpty()) return;
            due.forEach(t -> nextScheduleAttempts.put(deliveryKey(t.config(), endDate), Instant.now().plusSeconds(300)));
            jobs.submit("定时报表", () -> {
                try {
                    pullRange(config, endDate, false);
                } catch (Exception e) {
                    due.forEach(t -> failedPullAttempts.merge(deliveryKey(t.config(), endDate), 1, Integer::sum));
                    throw e;
                }
                deliverAll(config, endDate, report(config, endDate), due, "scheduled");
            });
        } catch (Exception e) { jobs.log("error", "定时任务检查失败：" + JobManager.safeError(e)); }
    }
    public Map<String,Object> status() {
        Map<String,Object> result = jobs.status();
        AppConfig config = configs.snapshot();
        try {
            var index = stats.cacheIndex(config);
            result.put("cacheDays", index.stream().map(r -> r.get("date")).toList());
        } catch (IOException e) { result.put("cacheDays", List.of()); result.put("cacheError", JobManager.safeError(e)); }
        synchronized (this) {
            result.put("lastSentDate", deliveries.values().stream().filter(d -> "sent".equals(d.get("status")))
                    .map(d -> d.get("date").toString()).max(String::compareTo).orElse(null));
            result.put("deliveries", deliveries.values().stream().sorted(Comparator.comparing(d -> d.get("time").toString(), Comparator.reverseOrder())).limit(20).toList());
        }
        result.put("subscriptions", directory.subscriptions(config.telegramBots));
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(config.timezone));
        List<ZonedDateTime> nextRuns = new ArrayList<>();
        if (config.scheduleEnabled) for (DeliveryTarget target : targets(config)) {
            String time = target.bot().sendTime.isBlank() ? config.sendTime : target.bot().sendTime;
            ZonedDateTime next = now.toLocalDate().atTime(LocalTime.parse(time)).atZone(now.getZone());
            String key = deliveryKey(target.config(), now.toLocalDate().minusDays(1));
            if (!next.isAfter(now) && (alreadyAttempted(key) || failedPullAttempts.getOrDefault(key, 0) >= 3)) next = next.plusDays(1);
            else if (!next.isAfter(now)) {
                Instant retryAt = nextScheduleAttempts.getOrDefault(key, Instant.now());
                next = (retryAt.isAfter(Instant.now()) ? retryAt : Instant.now().plusSeconds(30)).atZone(now.getZone());
            }
            nextRuns.add(next);
        }
        result.put("nextRun", nextRuns.stream().min(Comparator.naturalOrder()).map(t -> t.toOffsetDateTime().toString()).orElse(null));
        return result;
    }
    private Map<String,Object> connectionTest(String name, java.util.concurrent.Callable<Map<String,Object>> check) throws Exception {
        try {
            Map<String,Object> result = check.call();
            jobs.log("info", name + "连接测试完成：" + Objects.toString(result.get("message"), "成功"));
            return result;
        } catch (Exception e) { jobs.log("error", name + "连接测试失败：" + JobManager.safeError(e)); throw e; }
    }
    public Map<String,Object> testP0() throws Exception { return connectionTest("p0 ", () -> new P0Client().testConnection(configs.snapshot())); }
    public Map<String,Object> testTelegram() throws Exception { return connectionTest("Telegram ", () -> telegram.testConnection(configs.snapshot())); }
    public Map<String,Object> testP0(com.fasterxml.jackson.databind.JsonNode draft) throws Exception { return connectionTest("p0 ", () -> new P0Client().testConnection(configs.preview(draft))); }
    public Map<String,Object> testTelegram(com.fasterxml.jackson.databind.JsonNode draft) throws Exception {
        if (!draft.has("bot") && !draft.has("botId")) return connectionTest("Telegram ", () -> telegram.testConnection(configs.preview(draft)));
        TelegramBotConfig bot = configs.previewBot(draft);
        AppConfig config = configs.snapshot();
        config.telegramBotToken = bot.token; config.telegramChatId = bot.chatId; config.telegramThreadId = bot.threadId;
        return connectionTest("Telegram ", () -> telegram.testConnection(config));
    }
    public Map<String,Object> testLlm(com.fasterxml.jackson.databind.JsonNode draft) throws Exception {
        Map<String,Object> result = llm.testConnection(configs.preview(draft));
        jobs.log("success".equals(result.get("status")) ? "info" : "warn", "LLM 连接测试：" + result.get("message"));
        return result;
    }

    public String redactSecrets(String message) {
        AppConfig config = configs.snapshot();
        List<String> secrets = new ArrayList<>(List.of(config.p0ApiKey, config.llmApiKey, config.telegramBotToken));
        config.telegramBots.forEach(bot -> secrets.add(bot.token));
        for (String secret : secrets) if (secret != null && !secret.isBlank()) message = message.replace(secret, "[已隐藏]");
        return message;
    }

    public Map<String,Object> summaryResponse(LocalDate endDate) throws IOException {
        AppConfig config = configs.snapshot();
        return summaryResponse(config, report(config, endDate));
    }
    private Map<String,Object> summaryResponse(AppConfig config, ReportService.Report report) {
        LlmClient.Summary summary;
        try {
            String cacheKey = digest(report.text() + "\n" + config.llmEnabled + "\n" + config.llmBaseUrl + "\n" + config.llmModel
                    + "\n" + config.llmApiKey + "\n" + config.llmTimeoutSeconds);
            if (summaryCache.size() >= 100) summaryCache.clear();
            CachedSummary cached = summaryCache.compute(cacheKey, (ignored, previous) -> {
                long ttl = previous != null && "success".equals(previous.value().status()) ? 86400 : 60;
                if (previous != null && previous.created().plusSeconds(ttl).isAfter(Instant.now())) return previous;
                Map<String,Object> metrics = new LinkedHashMap<>();
                Map<String,Object> base = response(report);
                for (String field : List.of("startDate", "endDate", "days", "totalCount", "totalFee", "latestCount", "latestFee", "amountsEnabled", "amountSource", "feeComplete", "latestFeeComplete", "missingFeeCount", "channelsBy")) metrics.put(field, base.get(field));
                metrics.put("timezone", config.timezone);
                metrics.put("channelsTop10", rankings(report.channels().stream().limit(10).toList(), report.amountsEnabled()));
                metrics.put("workflowsTop10", rankings(report.workflows().stream().limit(10).toList(), report.amountsEnabled()));
                return new CachedSummary(Instant.now(), llm.summarize(config, metrics));
            });
            summary = cached.value();
        } catch (Exception e) {
            summary = new LlmClient.Summary("failed", "", "AI 总结暂不可用，原始报表保持可发送。");
        }
        boolean success = "success".equals(summary.status()) && !summary.text().isBlank();
        if (!success && !"disabled".equals(summary.status())) jobs.log("warn", "AI 总结不可用，继续使用原始报表：" + summary.message());
        return Map.of("text", success ? report.text() + "\n\n【AI 数据总结】\n" + summary.text() : report.text(),
                "aiSummary", success ? summary.text() : "", "aiStatus", success ? "ok" : summary.status(), "aiMessage", summary.message());
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @Override public void close() { discoveryScheduler.shutdownNow(); scheduler.shutdownNow(); jobs.close(); }
    public static final class MissingCacheException extends IOException { public MissingCacheException(String message) { super(message); } }
}
