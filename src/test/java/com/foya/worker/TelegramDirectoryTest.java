package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TelegramDirectoryTest {
    @TempDir Path directory;
    private static final String ONE = "123456:DIRECTORY_ONE_TEST_SECRET";
    private static final String TWO = "654321:DIRECTORY_TWO_TEST_SECRET";
    private HttpServer server;
    private ExecutorService executor;
    private final Map<String, Queue<List<Object>>> pages = new ConcurrentHashMap<>();
    private final Set<String> webhooks = ConcurrentHashMap.newKeySet();
    private final Set<String> failures = ConcurrentHashMap.newKeySet();
    private final Set<String> slow = ConcurrentHashMap.newKeySet();
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, List<String>> offsets = new ConcurrentHashMap<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<String> sentChatIds = Collections.synchronizedList(new ArrayList<>());

    private URI mock() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            paths.add(path);
            String[] parts = path.substring(1).split("/");
            String token = parts[0].substring(3);
            String method = parts[1];
            if (slow.contains(token)) {
                exchange.sendResponseHeaders(200, 10_000);
                exchange.getResponseBody().write("{\"ok\":".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { exchange.close(); }
                return;
            }
            Object response;
            int status = failures.contains(token) ? 401 : 200;
            if (status == 401) response = Map.of("ok", false, "description", "raw private body " + token);
            else if (method.equals("getWebhookInfo")) response = Map.of("ok", true, "result", Map.of("url", webhooks.contains(token) ? "https://private.example/secret-webhook" : ""));
            else if (method.equals("getUpdates")) {
                Map<String, String> query = new HashMap<>();
                for (String item : exchange.getRequestURI().getRawQuery().split("&")) {
                    String[] pair = item.split("=", 2);
                    query.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
                }
                offsets.computeIfAbsent(token, ignored -> Collections.synchronizedList(new ArrayList<>())).add(query.get("offset"));
                List<Object> updates = pages.computeIfAbsent(token, ignored -> new ConcurrentLinkedQueue<>()).poll();
                response = Map.of("ok", true, "result", updates == null ? List.of() : updates);
            } else if (method.equals("sendMessage")) {
                sentChatIds.add(Json.MAPPER.readTree(exchange.getRequestBody()).path("chat_id").asText());
                response = Map.of("ok", true, "result", Map.of("message_id", sentChatIds.size()));
            } else response = Map.of("ok", false);
            byte[] body = Json.MAPPER.writeValueAsBytes(response);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static TelegramBotConfig bot(String id, String token) {
        TelegramBotConfig bot = new TelegramBotConfig();
        bot.id = id; bot.name = id; bot.token = token; bot.enabled = true; bot.autoDiscover = true;
        return bot;
    }
    private void updates(String token, Object... updates) { pages.computeIfAbsent(token, ignored -> new ConcurrentLinkedQueue<>()).add(List.of(updates)); }
    private static Map<String, Object> chat(long id, String type) { return Map.of("id", id, "type", type, "title", "会话" + id); }
    private static Object member(long update, long id, String status) {
        return Map.of("update_id", update, "my_chat_member", Map.of("chat", chat(id, "supergroup"), "new_chat_member", Map.of("status", status)));
    }
    private static Object message(long update, long id, String type, String text) {
        return Map.of("update_id", update, "message", Map.of("chat", chat(id, type), "text", text));
    }

    @Test void joiningLeavingAndPrivateOrExplicitGroupSubscriptionsAreRegisteredWithoutSending() throws Exception {
        URI base = mock();
        TelegramBotConfig bot = bot("one", ONE);
        updates(ONE, member(1, -100, "member"), member(2, -200, "administrator"),
                message(3, 42, "private", "你好"), message(4, -300, "group", "/start@our_bot"),
                message(5, -400, "supergroup", "/subscribe"), message(6, -999, "group", "普通群消息不订阅"));
        TelegramDirectory service = new TelegramDirectory(directory, base);
        service.poll(List.of(bot), ignored -> {});
        assertEquals(Set.of("-100", "-200", "42", "-300", "-400"), ids(service.chatsFor(bot)));
        assertEquals(5, service.subscriptions(List.of(bot)).size());
        assertTrue(service.subscriptions(List.of(bot)).stream().allMatch(row -> "one".equals(row.get("botId"))));
        updates(ONE, member(7, -100, "left"), member(8, -200, "kicked"),
                message(9, 42, "private", "/stop"), message(10, -300, "group", "/stop@our_bot"), message(11, -400, "supergroup", "/stop"));
        service.poll(List.of(bot), ignored -> {});
        assertTrue(service.chatsFor(bot).isEmpty());
        assertTrue(paths.stream().allMatch(path -> path.endsWith("/getWebhookInfo") || path.endsWith("/getUpdates")));
    }

    @Test void migrationReplacesSubscribedGroupWithNewSupergroup() throws Exception {
        URI base = mock();
        TelegramBotConfig bot = bot("one", ONE);
        updates(ONE, message(1, -100, "group", "/start"));
        TelegramDirectory service = new TelegramDirectory(directory, base);
        service.poll(List.of(bot), ignored -> {});
        updates(ONE, Map.of("update_id", 2, "message", Map.of("chat", chat(-100, "group"), "migrate_to_chat_id", -100999L)));
        service.poll(List.of(bot), ignored -> {});
        assertEquals(Set.of("-100999"), ids(service.chatsFor(bot)));
        assertEquals("supergroup", service.chatsFor(bot).getFirst().type());
    }

    @Test void stopRemainsEffectiveAcrossRestartAndOrdinaryMessagesOrGroupPromotions() throws Exception {
        URI base = mock();
        TelegramBotConfig bot = bot("one", ONE);
        updates(ONE, message(1, 42, "private", "你好"), member(2, -100, "member"));
        TelegramDirectory service = new TelegramDirectory(directory, base);
        service.poll(List.of(bot), ignored -> {});
        updates(ONE, message(3, 42, "private", "/stop"), message(4, -100, "supergroup", "/stop"));
        service.poll(List.of(bot), ignored -> {});
        TelegramDirectory restarted = new TelegramDirectory(directory, base);
        updates(ONE, message(5, 42, "private", "后续普通消息不恢复"),
                Map.of("update_id", 6, "my_chat_member", Map.of("chat", chat(-100, "supergroup"),
                        "old_chat_member", Map.of("status", "member"), "new_chat_member", Map.of("status", "administrator"))));
        restarted.poll(List.of(bot), ignored -> {});
        assertTrue(restarted.chatsFor(bot).isEmpty());
        updates(ONE, message(7, 42, "private", "/start"),
                Map.of("update_id", 8, "my_chat_member", Map.of("chat", chat(-100, "supergroup"),
                        "old_chat_member", Map.of("status", "left"), "new_chat_member", Map.of("status", "member"))));
        restarted.poll(List.of(bot), ignored -> {});
        assertEquals(Set.of("42", "-100"), ids(restarted.chatsFor(bot)));
    }

    @Test void offsetsAndSubscriptionsSurviveRestartAndTokenChangesCannotReuseOldChats() throws Exception {
        URI base = mock();
        TelegramBotConfig original = bot("one", ONE);
        updates(ONE, message(7, 42, "private", "/start"));
        TelegramDirectory first = new TelegramDirectory(directory, base);
        first.poll(List.of(original), ignored -> {});
        String persisted = Files.readString(directory.resolve("telegram-directory.json"));
        assertFalse(persisted.contains(ONE));
        JsonNode json = Json.MAPPER.readTree(persisted);
        String tokenHash = json.path("offsets").fieldNames().next();
        assertTrue(tokenHash.matches("[a-f0-9]{64}"));
        assertEquals(8, json.path("offsets").path(tokenHash).asLong());

        TelegramDirectory restarted = new TelegramDirectory(directory, base);
        assertEquals(Set.of("42"), ids(restarted.chatsFor(original)));
        restarted.poll(List.of(original), ignored -> {});
        assertEquals(List.of("0", "8"), offsets.get(ONE));
        assertTrue(restarted.chatsFor(bot("different-id", ONE)).isEmpty());
        TelegramBotConfig changed = bot("one", TWO);
        assertTrue(restarted.chatsFor(changed).isEmpty());
        updates(TWO, message(1, 99, "private", "新机器人"));
        restarted.poll(List.of(changed), ignored -> {});
        assertEquals(Set.of("99"), ids(restarted.chatsFor(changed)));
        assertEquals(Set.of("42"), ids(restarted.chatsFor(original)));
        assertFalse(Files.readString(directory.resolve("telegram-directory.json")).contains(TWO));
    }

    @Test void existingWebhookIsNeverDeletedAndGetUpdatesIsSkipped() throws Exception {
        URI base = mock();
        webhooks.add(ONE);
        List<String> logs = new ArrayList<>();
        TelegramDirectory service = new TelegramDirectory(directory, base);
        service.poll(List.of(bot("one", ONE)), logs::add);
        assertEquals(List.of("/bot" + ONE + "/getWebhookInfo"), paths);
        assertTrue(logs.toString().contains("Webhook"));
        assertFalse(logs.toString().contains("secret-webhook"));
        assertFalse(logs.toString().contains(ONE));
        assertTrue(service.subscriptions(List.of(bot("one", ONE))).isEmpty());
    }

    @Test void oneBotFailureDoesNotBlockOthersAndDisabledDiscoveryNeverPolls() throws Exception {
        URI base = mock();
        failures.add(ONE);
        updates(TWO, message(1, 99, "private", "/start"));
        TelegramBotConfig disabled = bot("disabled", "111111:DISABLED_TEST_TOKEN"); disabled.enabled = false;
        TelegramBotConfig manual = bot("manual", "222222:MANUAL_TEST_TOKEN"); manual.autoDiscover = false;
        List<String> logs = new ArrayList<>();
        TelegramDirectory service = new TelegramDirectory(directory, base);
        service.poll(List.of(bot("one", ONE), bot("two", TWO), disabled, manual), logs::add);
        assertEquals(Set.of("99"), ids(service.chatsFor(bot("two", TWO))));
        assertFalse(logs.toString().contains(ONE));
        assertFalse(logs.toString().contains("raw private body"));
        assertEquals(3, paths.size());
    }

    @Test void invalidPageLeavesOffsetAndSubscriptionsUnchanged() throws Exception {
        URI base = mock();
        TelegramBotConfig bot = bot("one", ONE);
        updates(ONE, message(1, 42, "private", "/start"));
        TelegramDirectory service = new TelegramDirectory(directory, base);
        service.poll(List.of(bot), ignored -> {});
        byte[] before = Files.readAllBytes(directory.resolve("telegram-directory.json"));
        updates(ONE, message(2, 42, "private", "/stop"), Map.of("message", Map.of("chat", chat(50, "private"), "text", "invalid update")));
        service.poll(List.of(bot), ignored -> {});
        assertEquals(Set.of("42"), ids(service.chatsFor(bot)));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("telegram-directory.json")));
    }

    @Test void stalledResponseBodyTimesOutAndOtherBotsAreStillPolled() throws Exception {
        URI base = mock();
        slow.add(ONE);
        updates(TWO, message(1, 99, "private", "/start"));
        TelegramDirectory service = new TelegramDirectory(directory, base, Duration.ofMillis(120));
        assertTimeout(Duration.ofSeconds(2), () -> service.poll(List.of(bot("one", ONE), bot("two", TWO)), ignored -> {}));
        assertEquals(Set.of("99"), ids(service.chatsFor(bot("two", TWO))));
    }

    @Test void autoDiscoveredGroupAndPrivateChatReceiveReportWithoutManualChatIdAndStopRemovesTargets() throws Exception {
        URI base = mock();
        TelegramDirectory discovery = new TelegramDirectory(directory, base);
        LocalDate date = LocalDate.of(2026, 10, 29);
        try (WorkerService worker = TestWorkers.create(directory, new TelegramClient(base), discovery)) {
            worker.configs.update(Json.MAPPER.valueToTree(Map.of("reportDays", 1, "llmEnabled", false,
                    "telegramBots", List.of(Map.of("id", "one", "name", "自动登记机器人", "token", ONE,
                            "chatId", "", "threadId", "", "enabled", true, "autoDiscover", true, "sendTime", "09:00")))));
            AppConfig config = worker.configs.snapshot();
            assertTrue(config.telegramBots.getFirst().chatId.isBlank());
            DailyStats stats = new DailyStats();
            stats.date = date.toString(); stats.timezone = config.timezone; stats.fetchedAt = Instant.now().toString();
            stats.sourceFingerprint = StatsService.fingerprint(config, P0Client.resolveApiKey(config));
            stats.totalCount = 1; stats.totalFee = new BigDecimal("10.25"); stats.requestCount = 24;
            stats.buckets.add(new DailyStats.Bucket("channel", "workflow", 1, stats.totalFee));
            FilesSupport.writeJson(directory.resolve("cache").resolve(date + ".json"), stats);

            updates(ONE, member(1, -100, "member"), message(2, 42, "private", "你好"));
            discovery.poll(config.telegramBots, ignored -> {});
            assertEquals(Set.of("-100", "42"), ids(discovery.chatsFor(config.telegramBots.getFirst())));
            worker.send(date);
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (worker.jobs.isRunning() && System.nanoTime() < deadline) Thread.sleep(10);
            assertFalse(worker.jobs.isRunning());
            assertEquals("completed", ((Map<?, ?>) worker.jobs.status().get("job")).get("state"));
            assertEquals(Set.of("-100", "42"), new HashSet<>(sentChatIds));
            assertEquals(2, sentChatIds.size());

            updates(ONE, message(3, -100, "supergroup", "/stop"), message(4, 42, "private", "/stop"));
            discovery.poll(config.telegramBots, ignored -> {});
            assertTrue(discovery.chatsFor(config.telegramBots.getFirst()).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> worker.send(date));
            assertEquals(2, sentChatIds.size(), "Stopped automatic subscriptions must never remain send targets");
        }
    }

    private static Set<String> ids(List<TelegramDirectory.Chat> chats) { return new HashSet<>(chats.stream().map(TelegramDirectory.Chat::chatId).toList()); }
    @AfterEach void stop() {
        release.countDown();
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }
}
