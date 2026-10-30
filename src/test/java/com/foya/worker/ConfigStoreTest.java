package com.foya.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigStoreTest {
    @TempDir Path directory;

    @Test
    void blankSecretUpdatesPreserveStoredCredentialsAndResponsesRedactThem() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.update(Json.MAPPER.valueToTree(Map.of("p0ApiKey", "P0_PRIVATE_VALUE", "telegramBotToken", "123456:BOT_PRIVATE_VALUE")));
        var response = store.update(Json.MAPPER.valueToTree(Map.of("p0ApiKey", "", "telegramBotToken", "", "sendTime", "11:30")));
        var persisted = Json.MAPPER.readTree(Files.readAllBytes(directory.resolve("config.json")));
        assertEquals("P0_PRIVATE_VALUE", persisted.path("p0ApiKey").asText());
        assertEquals("123456:BOT_PRIVATE_VALUE", persisted.path("telegramBotToken").asText());
        assertEquals("11:30", response.get("sendTime"));
        assertFalse(response.containsKey("p0ApiKey"));
        assertFalse(response.containsKey("telegramBotToken"));
        assertEquals(true, response.get("p0ApiKeyConfigured"));
        assertEquals(true, response.get("telegramBotTokenConfigured"));
        String serialized = Json.MAPPER.writeValueAsString(response);
        assertFalse(serialized.contains("P0_PRIVATE_VALUE"));
        assertFalse(serialized.contains("BOT_PRIVATE_VALUE"));
        assertEquals("11:30", new ConfigStore(directory).snapshot().sendTime);
    }

    @Test
    void explicitSecretClearClearsPersistedCredential() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.update(Json.MAPPER.valueToTree(Map.of("p0ApiKey", "private", "telegramBotToken", "123:test")));
        store.update(Json.MAPPER.valueToTree(Map.of("clearP0ApiKey", true, "clearTelegramBotToken", true)));
        var persisted = Json.MAPPER.readTree(Files.readAllBytes(directory.resolve("config.json")));
        assertEquals("", persisted.path("p0ApiKey").asText());
        assertEquals("", persisted.path("telegramBotToken").asText());
    }

    @Test
    void acceptsAnonymousP0AndKeepsMappingsSeparateFromPublicConfiguration() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.update(Json.MAPPER.valueToTree(Map.of("p0ApiKey", "", "channelNames", Map.of("hash_a", "渠道甲"),
                "workflowNames", Map.of("image", "图像编辑"))));
        assertFalse(store.publicConfig().containsKey("channelNames"));
        assertEquals(Map.of("hash_a", "渠道甲"), store.mappings().get("channelNames"));
        assertEquals(Map.of("image", "图像编辑"), store.mappings().get("workflowNames"));
        // The default endpoint and optional credential are valid even before obtaining an API key.
        store.snapshot().validate();
    }

    @Test
    void invalidMappingDoesNotReplaceLastValidSavedConfiguration() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.update(Json.MAPPER.valueToTree(Map.of("channelNames", Map.of("hash", "有效名称"))));
        byte[] before = Files.readAllBytes(directory.resolve("config.json"));
        assertThrows(IllegalArgumentException.class,
                () -> store.update(Json.MAPPER.valueToTree(Map.of("channelNames", Map.of("hash", "   ")))));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("config.json")));
        assertEquals(Map.of("hash", "有效名称"), store.mappings().get("channelNames"));
    }

    @Test
    void snapshotsCannotMutateSavedConfiguration() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.update(Json.MAPPER.valueToTree(Map.of("channelNames", Map.of("hash", "渠道甲"))));
        AppConfig snapshot = store.snapshot();
        snapshot.channelNames.put("hash", "意外修改");
        snapshot.timezone = "UTC";
        assertEquals("渠道甲", store.snapshot().channelNames.get("hash"));
        assertEquals("Asia/Shanghai", store.snapshot().timezone);
    }

    @Test
    void llmSecretIsPreservedRedactedAndClearableAndFiftyAttemptsAreConfigurable() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        assertEquals(50, store.snapshot().p0MaxAttempts);
        store.update(Json.MAPPER.valueToTree(Map.of("llmApiKey", "LLM_PRIVATE_VALUE", "sendTime", "18:45", "p0MaxAttempts", 50)));
        var safe = store.update(Json.MAPPER.valueToTree(Map.of("llmApiKey", "")));
        assertEquals("LLM_PRIVATE_VALUE", store.snapshot().llmApiKey);
        assertEquals(true, safe.get("llmApiKeyConfigured"));
        assertFalse(safe.containsKey("llmApiKey"));
        assertFalse(Json.MAPPER.writeValueAsString(safe).contains("LLM_PRIVATE_VALUE"));
        assertEquals("18:45", new ConfigStore(directory).snapshot().sendTime);
        assertThrows(IllegalArgumentException.class, () -> store.update(Json.MAPPER.valueToTree(Map.of("p0MaxAttempts", 51))));
        store.update(Json.MAPPER.valueToTree(Map.of("clearLlmApiKey", true)));
        assertEquals("", store.snapshot().llmApiKey);
    }

    @Test
    void generatedBotIdsAreStableAndBlankTokensArePreservedByIdAcrossReordering() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        var response = store.update(Json.MAPPER.valueToTree(Map.of("telegramBots", List.of(
                Map.of("name", "机器人甲", "token", "123:PRIVATE_BOT_A"),
                Map.of("name", "机器人乙", "token", "456:PRIVATE_BOT_B", "sendTime", "18:30")))));
        var original = store.snapshot().telegramBots;
        String firstId = original.get(0).id;
        String secondId = original.get(1).id;
        assertFalse(firstId.isBlank());
        assertNotEquals(firstId, secondId);
        JsonNodeAssertions.assertPublicBotsSafe(response, 2);
        assertFalse(Json.MAPPER.writeValueAsString(response).contains("PRIVATE_BOT_"));
        store.update(Json.MAPPER.valueToTree(Map.of("telegramBots", List.of(
                Map.of("id", secondId, "token", "", "name", "机器人乙更名"),
                Map.of("id", firstId, "token", "")))));
        var reordered = store.snapshot().telegramBots;
        assertEquals(secondId, reordered.get(0).id);
        assertEquals("456:PRIVATE_BOT_B", reordered.get(0).token);
        assertEquals("18:30", reordered.get(0).sendTime);
        assertEquals("机器人乙更名", reordered.get(0).name);
        assertEquals(firstId, reordered.get(1).id);
        assertEquals("123:PRIVATE_BOT_A", reordered.get(1).token);
        assertEquals(firstId, new ConfigStore(directory).snapshot().telegramBots.get(1).id);
    }

    @Test
    void botTokenCanBeExplicitlyClearedWithoutAffectingOtherBots() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.update(Json.MAPPER.valueToTree(Map.of("telegramBots", List.of(
                Map.of("id", "bot-a", "token", "123:PRIVATE_A"),
                Map.of("id", "bot-b", "token", "456:PRIVATE_B")))));
        store.update(Json.MAPPER.valueToTree(Map.of("telegramBots", List.of(
                Map.of("id", "bot-a", "clearToken", true), Map.of("id", "bot-b", "token", "")))));
        assertEquals("", store.snapshot().telegramBots.get(0).token);
        assertEquals("456:PRIVATE_B", store.snapshot().telegramBots.get(1).token);
        var safeBots = Json.MAPPER.valueToTree(store.publicConfig()).path("telegramBots");
        assertFalse(safeBots.get(0).path("tokenConfigured").asBoolean());
        assertTrue(safeBots.get(1).path("tokenConfigured").asBoolean());
    }

    @Test
    void legacyConfigurationMigratesInMemoryAndExplicitRemovalCannotResurrectIt() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.update(Json.MAPPER.valueToTree(Map.of("telegramBotToken", "123:LEGACY_SECRET", "telegramChatId", "-10012345")));
        assertEquals(1, store.snapshot().telegramBots.size());
        TelegramBotConfig legacy = store.snapshot().telegramBots.getFirst();
        assertEquals("legacy-bot", legacy.id);
        assertEquals("123:LEGACY_SECRET", legacy.token);
        assertEquals("-10012345", legacy.chatId);
        assertTrue(legacy.autoDiscover);
        assertEquals(0, Json.MAPPER.readTree(Files.readAllBytes(directory.resolve("config.json"))).path("telegramBots").size());
        assertFalse(Json.MAPPER.writeValueAsString(store.publicConfig()).contains("LEGACY_SECRET"));
        store.update(Json.MAPPER.valueToTree(Map.of("telegramBots", List.of())));
        assertTrue(store.snapshot().telegramBots.isEmpty());
        var persisted = Json.MAPPER.readTree(Files.readAllBytes(directory.resolve("config.json")));
        assertEquals("", persisted.path("telegramBotToken").asText());
        assertEquals("", persisted.path("telegramChatId").asText());
        assertTrue(persisted.path("telegramBotsExplicitlyConfigured").asBoolean());
        assertTrue(new ConfigStore(directory).snapshot().telegramBots.isEmpty());
        assertFalse(store.publicConfig().containsKey("telegramBotsExplicitlyConfigured"));
    }

    @Test
    void migrationToExplicitBotListRetainsLegacyTokenWithoutLegacyDuplicateStorage() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.update(Json.MAPPER.valueToTree(Map.of("telegramBotToken", "123:LEGACY_SECRET", "telegramChatId", "-10012345")));
        store.update(Json.MAPPER.valueToTree(Map.of("telegramBots", List.of(Map.of("id", "legacy-bot", "token", "", "name", "迁移后的机器人")))));
        assertEquals("123:LEGACY_SECRET", store.snapshot().telegramBots.getFirst().token);
        assertEquals("-10012345", store.snapshot().telegramBots.getFirst().chatId);
        assertEquals("", Json.MAPPER.readTree(Files.readAllBytes(directory.resolve("config.json"))).path("telegramBotToken").asText());
    }

    @Test
    void previewBotSelectsSavedTokenAndDoesNotPersistDraftOrMutateSavedBot() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.update(Json.MAPPER.valueToTree(Map.of("telegramBots", List.of(Map.of("id", "bot-a", "token", "123:SAVED_TOKEN", "name", "已保存")))));
        byte[] before = Files.readAllBytes(directory.resolve("config.json"));
        TelegramBotConfig draft = store.previewBot(Json.MAPPER.valueToTree(Map.of("bot", Map.of("id", "bot-a", "name", "草稿", "token", ""))));
        assertEquals("123:SAVED_TOKEN", draft.token);
        assertEquals("草稿", draft.name);
        draft.token = "mutated";
        assertEquals("123:SAVED_TOKEN", store.previewBot(Json.MAPPER.valueToTree(Map.of("botId", "bot-a"))).token);
        assertEquals("已保存", store.snapshot().telegramBots.getFirst().name);
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("config.json")));
        TelegramBotConfig fresh = store.previewBot(Json.MAPPER.valueToTree(Map.of("bot", Map.of("token", "456:DRAFT_TOKEN"))));
        assertFalse(fresh.id.isBlank());
        assertTrue(fresh.chatId.isEmpty());
        assertTrue(fresh.autoDiscover);
        assertThrows(IllegalArgumentException.class, () -> store.previewBot(Json.MAPPER.valueToTree(Map.of("botId", "missing"))));
    }

    @Test
    void automaticDiscoveryAllowsScheduledBotWithoutChatIdAndManualModeRequiresTarget() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.update(Json.MAPPER.valueToTree(Map.of("scheduleEnabled", true, "telegramBots", List.of(
                Map.of("id", "auto-bot", "token", "123:DISCOVERY_TOKEN", "chatId", "")))));
        assertTrue(store.snapshot().telegramBots.getFirst().ready());
        byte[] before = Files.readAllBytes(directory.resolve("config.json"));
        assertThrows(IllegalArgumentException.class, () -> store.update(Json.MAPPER.valueToTree(Map.of("telegramBots", List.of(
                Map.of("id", "auto-bot", "autoDiscover", false))))));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("config.json")));
        store.update(Json.MAPPER.valueToTree(Map.of("telegramBots", List.of(
                Map.of("id", "auto-bot", "autoDiscover", false, "chatId", "-10012345")))));
        assertTrue(store.snapshot().telegramBots.getFirst().ready());
        assertFalse(store.snapshot().telegramBots.getFirst().autoDiscover);
    }

    @Test
    void invalidBotListNeverOverwritesLastValidConfiguration() throws Exception {
        ConfigStore store = new ConfigStore(directory);
        store.update(Json.MAPPER.valueToTree(Map.of("telegramBots", List.of(Map.of("id", "bot-a", "token", "123:TOKEN")))));
        byte[] before = Files.readAllBytes(directory.resolve("config.json"));
        assertThrows(IllegalArgumentException.class, () -> store.update(Json.MAPPER.valueToTree(Map.of("telegramBots", List.of(
                Map.of("id", "bot-a"), Map.of("id", "bot-a"))))));
        assertThrows(IllegalArgumentException.class, () -> store.update(Json.MAPPER.valueToTree(Map.of("telegramBots", List.of(
                Map.of("id", "bot-a", "sendTime", "25:90"))))));
        assertThrows(IllegalArgumentException.class, () -> store.update(Json.MAPPER.valueToTree(Map.of("telegramBots", List.of(
                Map.of("id", "bot-a", "name", " "))))));
        assertThrows(IllegalArgumentException.class, () -> store.update(Json.MAPPER.valueToTree(Map.of("telegramBots", List.of(
                Map.of("id", "bot-a", "threadId", "9999999999"))))));
        assertThrows(IllegalArgumentException.class, () -> store.update(Json.MAPPER.valueToTree(Map.of("telegramBots",
                java.util.stream.IntStream.range(0, 51).mapToObj(i -> Map.of("id", "bot-" + i)).toList()))));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("config.json")));
    }

    private static final class JsonNodeAssertions {
        static void assertPublicBotsSafe(Map<String, Object> response, int count) {
            var bots = Json.MAPPER.valueToTree(response).path("telegramBots");
            assertEquals(count, bots.size());
            for (var bot : bots) {
                assertFalse(bot.has("token"));
                assertTrue(bot.path("tokenConfigured").asBoolean());
                assertTrue(bot.path("autoDiscover").asBoolean());
            }
        }
    }
}
