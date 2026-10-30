package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ConfigStore {
    private final Path file;
    private AppConfig stored;
    public ConfigStore(Path dataDirectory) throws IOException {
        FilesSupport.privateDirectory(dataDirectory);
        file = dataDirectory.resolve("config.json");
        stored = Files.exists(file) ? Json.MAPPER.readValue(Files.readAllBytes(file), AppConfig.class) : new AppConfig();
        snapshot().validate();
    }
    public synchronized AppConfig snapshot() { return effective(stored); }
    private static AppConfig effective(AppConfig source) {
        AppConfig config = Json.MAPPER.convertValue(source, AppConfig.class);
        applyEnv(config, "P0_API_KEY", "p0ApiKey");
        applyEnv(config, "P0_API_KEY_FILE", "p0ApiKeyFile");
        applyEnv(config, "TELEGRAM_BOT_TOKEN", "telegramBotToken");
        applyEnv(config, "LLM_API_KEY", "llmApiKey");
        if (config.telegramBots != null) {
            if (config.telegramBots.isEmpty() && !config.telegramBotsExplicitlyConfigured
                    && (!config.telegramBotToken.isBlank() || !config.telegramChatId.isBlank()))
                config.telegramBots.add(legacyBot(config));
            // The legacy environment variable only overrides the migrated legacy bot, never every bot.
            if (hasEnv("TELEGRAM_BOT_TOKEN")) for (TelegramBotConfig bot : config.telegramBots)
                if (bot != null && "legacy-bot".equals(bot.id)) bot.token = config.telegramBotToken;
        }
        return config;
    }
    private static TelegramBotConfig legacyBot(AppConfig config) {
        TelegramBotConfig bot = new TelegramBotConfig();
        bot.id = "legacy-bot"; bot.name = "默认机器人"; bot.token = config.telegramBotToken;
        bot.chatId = config.telegramChatId; bot.threadId = config.telegramThreadId;
        return bot;
    }
    private static void applyEnv(AppConfig config, String env, String field) {
        String value = System.getenv(env);
        if (value != null && !value.isBlank()) {
            try { AppConfig.class.getField(field).set(config, value); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    }
    public synchronized Map<String,Object> publicConfig() {
        AppConfig effective = snapshot();
        ObjectNode safe = Json.MAPPER.valueToTree(effective);
        safe.remove("p0ApiKey"); safe.remove("telegramBotToken"); safe.remove("llmApiKey");
        safe.remove("channelNames"); safe.remove("workflowNames"); safe.remove("taskTypePrices"); safe.remove("telegramBotsExplicitlyConfigured");
        ArrayNode bots = Json.MAPPER.createArrayNode();
        for (TelegramBotConfig bot : effective.telegramBots) {
            ObjectNode item = Json.MAPPER.valueToTree(bot);
            item.remove("token"); item.put("tokenConfigured", !bot.token.isBlank()); bots.add(item);
        }
        safe.set("telegramBots", bots);
        safe.put("p0ApiKeyConfigured", !effective.p0ApiKey.isBlank() || !effective.p0ApiKeyFile.isBlank());
        safe.put("telegramBotTokenConfigured", !effective.telegramBotToken.isBlank()
                || effective.telegramBots.stream().anyMatch(bot -> !bot.token.isBlank()));
        safe.put("llmApiKeyConfigured", !effective.llmApiKey.isBlank());
        safe.put("p0ApiKeyFromEnvironment", hasEnv("P0_API_KEY") || hasEnv("P0_API_KEY_FILE"));
        safe.put("telegramBotTokenFromEnvironment", hasEnv("TELEGRAM_BOT_TOKEN"));
        safe.put("llmApiKeyFromEnvironment", hasEnv("LLM_API_KEY"));
        return Json.MAPPER.convertValue(safe, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    }
    private static boolean hasEnv(String name) { return System.getenv(name) != null && !System.getenv(name).isBlank(); }
    private AppConfig merged(JsonNode input) throws IOException {
        if (input == null || !input.isObject()) throw new IllegalArgumentException("配置应为 JSON 对象。");
        ObjectNode merged = Json.MAPPER.valueToTree(stored);
        var fields = input.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            if (!merged.has(field.getKey()) || field.getKey().equals("telegramBots") || field.getKey().equals("telegramBotsExplicitlyConfigured")) continue;
            if (field.getKey().equals("taskTypePrices")) {
                merged.set("taskTypePrices", Json.MAPPER.valueToTree(parsePrices(field.getValue())));
                continue;
            }
            if (field.getKey().equals("amountsEnabled") && !field.getValue().isBoolean())
                throw new IllegalArgumentException("金额统计开关应为布尔值。");
            if (List.of("p0ApiKey", "telegramBotToken", "llmApiKey").contains(field.getKey())) {
                if (!field.getValue().isTextual()) throw new IllegalArgumentException("密钥配置应为字符串。");
                if (field.getValue().asText().isBlank()) continue;
            }
            merged.set(field.getKey(), field.getValue());
        }
        if (input.path("clearP0ApiKey").asBoolean(false)) merged.put("p0ApiKey", "");
        if (input.path("clearTelegramBotToken").asBoolean(false)) merged.put("telegramBotToken", "");
        if (input.path("clearLlmApiKey").asBoolean(false)) merged.put("llmApiKey", "");
        if (input.has("telegramBots")) {
            JsonNode supplied = input.path("telegramBots");
            if (!supplied.isArray() || supplied.size() > 50) throw new IllegalArgumentException("机器人列表应为 JSON 数组，最多 50 项。");
            Map<String, TelegramBotConfig> oldBots = storedBotsById();
            ArrayNode nextBots = Json.MAPPER.createArrayNode();
            for (JsonNode candidate : supplied) nextBots.add(Json.MAPPER.valueToTree(mergeBot(candidate, oldBots)));
            merged.set("telegramBots", nextBots);
            merged.put("telegramBotsExplicitlyConfigured", true);
            // Once a list is explicit, legacy fields must never recreate a deleted bot.
            merged.put("telegramBotToken", ""); merged.put("telegramChatId", ""); merged.put("telegramThreadId", "");
        }
        return Json.MAPPER.treeToValue(merged, AppConfig.class);
    }
    private static Map<String, java.math.BigDecimal> parsePrices(JsonNode input) {
        if (!input.isObject() || input.size() > 10000) throw new IllegalArgumentException("单价映射应为 JSON 对象，最多 10000 项。");
        Map<String, java.math.BigDecimal> prices = new java.util.LinkedHashMap<>();
        var entries = input.fields();
        while (entries.hasNext()) {
            var entry = entries.next();
            JsonNode value = entry.getValue();
            if (!(value.isNumber() || value.isTextual()) || value.asText().isBlank())
                throw new IllegalArgumentException("单价必须为非负数字或十进制字符串，不能为 null、空串、布尔值或对象。");
            try { prices.put(entry.getKey(), new java.math.BigDecimal(value.asText().trim())); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("单价必须为有效的非负数字或十进制字符串。"); }
        }
        AppConfig.validatePrices(prices);
        return prices;
    }
    private Map<String, TelegramBotConfig> storedBotsById() {
        Map<String, TelegramBotConfig> bots = new HashMap<>();
        for (TelegramBotConfig bot : stored.telegramBots) bots.put(bot.id, bot);
        if (bots.isEmpty() && !stored.telegramBotsExplicitlyConfigured
                && (!stored.telegramBotToken.isBlank() || !stored.telegramChatId.isBlank() || hasEnv("TELEGRAM_BOT_TOKEN"))) {
            // Preserve stored credentials only. Environment secrets are reapplied at runtime, never copied to disk.
            TelegramBotConfig legacy = legacyBot(stored); bots.put(legacy.id, legacy);
        }
        return bots;
    }
    private static TelegramBotConfig mergeBot(JsonNode input, Map<String, TelegramBotConfig> oldBots) throws IOException {
        if (input == null || !input.isObject()) throw new IllegalArgumentException("每个机器人配置应为 JSON 对象。");
        String requestedId = text(input, "id", "").trim();
        TelegramBotConfig previous = oldBots.get(requestedId);
        TelegramBotConfig bot = previous == null ? new TelegramBotConfig() : Json.MAPPER.convertValue(previous, TelegramBotConfig.class);
        bot.id = requestedId.isEmpty() ? UUID.randomUUID().toString() : requestedId;
        bot.name = text(input, "name", bot.name).trim();
        bot.chatId = text(input, "chatId", bot.chatId).trim();
        bot.threadId = text(input, "threadId", bot.threadId).trim();
        bot.sendTime = text(input, "sendTime", bot.sendTime).trim();
        String token = text(input, "token", "").trim();
        if (!token.isBlank()) bot.token = token;
        if (input.path("clearToken").asBoolean(false)) bot.token = "";
        if (input.has("enabled")) {
            if (!input.path("enabled").isBoolean()) throw new IllegalArgumentException("机器人 enabled 应为布尔值。");
            bot.enabled = input.path("enabled").asBoolean();
        }
        if (input.has("autoDiscover")) {
            if (!input.path("autoDiscover").isBoolean()) throw new IllegalArgumentException("机器人 autoDiscover 应为布尔值。");
            bot.autoDiscover = input.path("autoDiscover").asBoolean();
        }
        bot.validate();
        return bot;
    }
    private static String text(JsonNode input, String name, String fallback) {
        if (!input.has(name)) return fallback;
        if (!input.path(name).isTextual()) throw new IllegalArgumentException("机器人字段 " + name + " 应为字符串。");
        return input.path(name).textValue();
    }
    public synchronized AppConfig preview(JsonNode input) throws IOException {
        AppConfig effective = effective(merged(input));
        effective.validate();
        return effective;
    }
    /** Read-only probe selection; blank draft tokens retain the matching saved bot's credential. */
    public synchronized TelegramBotConfig previewBot(JsonNode input) throws IOException {
        if (input == null || !input.isObject()) throw new IllegalArgumentException("机器人连接检查参数应为 JSON 对象。");
        Map<String, TelegramBotConfig> bots = new HashMap<>();
        for (TelegramBotConfig bot : snapshot().telegramBots) bots.put(bot.id, bot);
        if (input.has("botId")) {
            TelegramBotConfig bot = bots.get(text(input, "botId", ""));
            if (bot == null) throw new IllegalArgumentException("未找到指定机器人，请先保存配置。");
            return Json.MAPPER.convertValue(bot, TelegramBotConfig.class);
        }
        JsonNode draft = input.has("bot") ? input.path("bot") : input;
        return mergeBot(draft, bots);
    }
    public synchronized Map<String,Object> update(JsonNode input) throws IOException {
        AppConfig next = merged(input);
        effective(next).validate();
        FilesSupport.writeJson(file, next);
        stored = next;
        return publicConfig();
    }
    public synchronized Map<String,Object> mappings() {
        Map<String,String> prices = new java.util.LinkedHashMap<>();
        stored.taskTypePrices.forEach((key, price) -> prices.put(key, price.stripTrailingZeros().toPlainString()));
        return Map.of("channelNames", Map.copyOf(stored.channelNames), "workflowNames", Map.copyOf(stored.workflowNames),
                "taskTypePrices", prices, "amountsEnabled", stored.amountsEnabled);
    }
}
