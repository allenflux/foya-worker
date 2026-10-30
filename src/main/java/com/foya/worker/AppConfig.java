package com.foya.worker;

import java.net.URI;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class AppConfig {
    public String p0Endpoint = "https://p0-function.inaiai.com/api/tasks/list";
    public String p0ApiKey = "";
    public String p0ApiKeyFile = "";
    public String p0TimeFormat = "datetime";
    public int p0MaxAttempts = 50;
    public String timezone = "Asia/Shanghai";
    public String telegramBotToken = "";
    public String telegramChatId = "";
    public String telegramThreadId = "";
    public List<TelegramBotConfig> telegramBots = new ArrayList<>();
    // Internal migration marker: an explicitly empty list must remain empty after restart/env overrides.
    public boolean telegramBotsExplicitlyConfigured = false;
    public int reportDays = 10;
    public boolean amountsEnabled = false;
    public String sendTime = "09:00";
    public boolean scheduleEnabled = false;
    public boolean llmEnabled = true;
    public String llmBaseUrl = "https://llm.aiaipool.com/v1";
    public String llmApiKey = "";
    public String llmModel = "deepseek-v41-flash";
    public int llmTimeoutSeconds = 20;
    public Map<String, String> channelNames = new LinkedHashMap<>();
    public Map<String, String> workflowNames = new LinkedHashMap<>();
    public Map<String, java.math.BigDecimal> taskTypePrices = new LinkedHashMap<>();

    public void validate() {
        try {
            URI uri = URI.create(p0Endpoint);
            boolean local = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost() == null ? "" : uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null || uri.getQuery() != null
                    || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
        } catch (Exception e) { throw new IllegalArgumentException("p0 接口必须是 HTTPS 地址（本机测试可用 HTTP），不含查询参数或账号。"); }
        try { ZoneId.of(timezone); } catch (Exception e) { throw new IllegalArgumentException("统计时区无效。"); }
        try { LocalTime.parse(sendTime); if (!sendTime.matches("\\d{2}:\\d{2}")) throw new IllegalArgumentException(); }
        catch (Exception e) { throw new IllegalArgumentException("发送时间格式应为 HH:mm。"); }
        if (!Set.of("datetime", "unix_seconds", "unix_millis").contains(p0TimeFormat)) throw new IllegalArgumentException("p0 时间参数格式无效。");
        if (p0MaxAttempts < 1 || p0MaxAttempts > 50) throw new IllegalArgumentException("每页最多尝试次数应在 1–50 次之间（含首次请求）。");
        if (llmApiKey == null || llmModel == null || llmBaseUrl == null || llmApiKey.contains("\n") || llmApiKey.contains("\r")) throw new IllegalArgumentException("LLM 配置格式无效。");
        try {
            URI uri = URI.create(llmBaseUrl);
            boolean local = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost() == null ? "" : uri.getHost());
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !("https".equals(uri.getScheme()) || local && "http".equals(uri.getScheme()))) throw new IllegalArgumentException();
        } catch (Exception e) { throw new IllegalArgumentException("LLM Base URL 必须是 HTTPS 地址（本机测试可用 HTTP）。"); }
        if (llmModel.isBlank() || llmModel.length() > 200) throw new IllegalArgumentException("LLM 模型名不能为空，最多 200 字符。");
        if (llmTimeoutSeconds < 5 || llmTimeoutSeconds > 90) throw new IllegalArgumentException("LLM 总结超时应在 5–90 秒之间。");
        if (reportDays < 1 || reportDays > 31) throw new IllegalArgumentException("报表天数应在 1–31 天之间。");
        if (p0ApiKey == null || p0ApiKeyFile == null || telegramBotToken == null || telegramChatId == null || telegramThreadId == null)
            throw new IllegalArgumentException("配置项不能为 null。");
        if (p0ApiKey.contains("\n") || p0ApiKey.contains("\r")) throw new IllegalArgumentException("apikey 格式无效。");
        if (channelNames == null || workflowNames == null || channelNames.size() > 10000 || workflowNames.size() > 10000)
            throw new IllegalArgumentException("映射表应为 JSON 对象，最多 10000 项。");
        for (Map<String, String> mapping : java.util.List.of(channelNames, workflowNames)) {
            for (var entry : mapping.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getKey().length() > 256 || entry.getValue().isBlank() || entry.getValue().length() > 256)
                    throw new IllegalArgumentException("映射名称不能为空，键和值最多 256 个字符。");
            }
        }
        validatePrices(taskTypePrices);
        if (!telegramChatId.isBlank() && !telegramChatId.matches("-?\\d+|@[A-Za-z0-9_]{5,}")) throw new IllegalArgumentException("Chat ID 应为数字或 @频道用户名。");
        if (!telegramThreadId.isBlank() && !telegramThreadId.matches("[1-9]\\d{0,9}")) throw new IllegalArgumentException("话题 ID 应为正整数。");
        if (telegramBots == null || telegramBots.size() > 50) throw new IllegalArgumentException("最多可配置 50 个 Telegram 机器人。");
        Set<String> botIds = new HashSet<>();
        for (TelegramBotConfig bot : telegramBots) {
            if (bot == null) throw new IllegalArgumentException("机器人配置不能为 null。");
            bot.validate();
            if (!botIds.add(bot.id)) throw new IllegalArgumentException("机器人 ID 不能重复。");
        }
        boolean readyBot = telegramBots.stream().anyMatch(TelegramBotConfig::ready);
        boolean legacyReady = !telegramBotsExplicitlyConfigured && telegramBots.isEmpty()
                && telegramBotToken.matches("[0-9]+:[A-Za-z0-9_-]+");
        if (scheduleEnabled && !readyBot && !legacyReady)
            throw new IllegalArgumentException("启用定时发送前，请配置至少一个已启用机器人的有效 Token，并启用自动发现聊天或填写 Chat ID。");
    }

    public static void validatePrices(Map<String, java.math.BigDecimal> prices) {
        if (prices == null || prices.size() > 10000) throw new IllegalArgumentException("单价映射应为 JSON 对象，最多 10000 项。");
        for (var entry : prices.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getKey().length() > 256 || entry.getKey().matches("(?s).*[\\p{Cc}].*"))
                throw new IllegalArgumentException("单价映射的 task_type 不能为空，最多 256 字符且不能含控制字符。");
            java.math.BigDecimal price = entry.getValue();
            if (price == null || price.signum() < 0 || price.scale() > 12 || price.precision() > 30 || (long) price.precision() - price.scale() > 18)
                throw new IllegalArgumentException("单价必须为非负数字，最多 18 位整数和 12 位小数；免费类型可填 0。");
        }
    }
}
