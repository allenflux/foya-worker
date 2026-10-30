package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Read-only update polling. This class never sends messages or changes a Telegram webhook. */
public final class TelegramDirectory {
    public record Chat(String chatId, String title, String type) {}
    static final class DirectoryState {
        public int schemaVersion = 1;
        public Map<String, Long> offsets = new LinkedHashMap<>();
        public Map<String, Map<String, Chat>> chats = new LinkedHashMap<>();
        public Map<String, Set<String>> stopped = new LinkedHashMap<>();
    }
    private final Path file;
    private final URI base;
    private final Duration timeout;
    private final HttpClient http;
    private final ReentrantLock pollLock = new ReentrantLock();
    private volatile DirectoryState state = new DirectoryState();
    private volatile boolean unreadableCache;

    public TelegramDirectory(Path dataDir) { this(dataDir, URI.create("https://api.telegram.org")); }
    public TelegramDirectory(Path dataDir, URI base) { this(dataDir, base, Duration.ofSeconds(4)); }
    TelegramDirectory(Path dataDir, URI base, Duration timeout) {
        if (base == null || base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !("https".equals(base.getScheme()) || "http".equals(base.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(base.getHost())))
            throw new IllegalArgumentException("Telegram 自动登记接口地址无效");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("自动登记请求超时必须为正数");
        this.file = dataDir.resolve("telegram-directory.json");
        this.base = base;
        this.timeout = timeout;
        http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
        if (Files.exists(file)) {
            try {
                DirectoryState restored = Json.MAPPER.readValue(Files.readAllBytes(file), DirectoryState.class);
                if (restored.schemaVersion != 1 || restored.offsets == null || restored.chats == null || restored.stopped == null
                        || restored.offsets.values().stream().anyMatch(offset -> offset == null || offset < 0)
                        || restored.chats.values().stream().anyMatch(Objects::isNull)
                        || restored.stopped.values().stream().anyMatch(Objects::isNull)) throw new IOException();
                state = restored;
            } catch (Exception ignored) { unreadableCache = true; }
        }
    }

    public void poll(List<TelegramBotConfig> bots, Consumer<String> safeLog) {
        Consumer<String> log = safeLog == null ? ignored -> {} : safeLog;
        if (!pollLock.tryLock()) return;
        try {
            if (unreadableCache) { log.accept("Telegram 自动登记缓存无法读取，已跳过更新，原有报表发送不受影响。"); return; }
            Map<String, List<TelegramBotConfig>> byToken = new LinkedHashMap<>();
            for (TelegramBotConfig bot : bots == null ? List.<TelegramBotConfig>of() : bots) {
                if (bot == null || !bot.enabled || !bot.autoDiscover || bot.token == null || bot.token.isBlank()) continue;
                if (!bot.token.matches("[0-9]+:[A-Za-z0-9_-]+")) {
                    log.accept("Telegram 自动登记已跳过：机器人 Token 格式无效。"); continue;
                }
                byToken.computeIfAbsent(tokenHash(bot.token), ignored -> new ArrayList<>()).add(bot);
            }
            for (var entry : byToken.entrySet()) {
                if (Thread.currentThread().isInterrupted()) return;
                TelegramBotConfig representative = entry.getValue().getFirst();
                try { pollToken(entry.getKey(), entry.getValue(), log); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
                catch (Exception ignored) { log.accept("机器人「" + label(representative) + "」自动登记查询失败，将在下轮继续，原有报表发送不受影响。"); }
            }
        } finally { pollLock.unlock(); }
    }

    private void pollToken(String tokenHash, List<TelegramBotConfig> bots, Consumer<String> log) throws Exception {
        String token = bots.getFirst().token;
        JsonNode webhook = request(token, "getWebhookInfo", "").path("result");
        if (!webhook.isObject() || !webhook.path("url").isTextual()) throw new IOException("Webhook 查询响应无效");
        if (!webhook.path("url").asText().isBlank()) {
            log.accept("机器人「" + label(bots.getFirst()) + "」已有 Webhook，已跳过自动登记轮询，未修改 Webhook。");
            return;
        }
        long offset = state.offsets.getOrDefault(tokenHash, 0L);
        String allowed = URLEncoder.encode("[\"message\",\"my_chat_member\"]", StandardCharsets.UTF_8);
        JsonNode result = request(token, "getUpdates", "?offset=" + offset + "&limit=100&timeout=0&allowed_updates=" + allowed).path("result");
        if (!result.isArray()) throw new IOException("Updates 查询响应无效");
        if (result.isEmpty()) return;
        if (result.size() > 100) throw new IOException("Updates 返回超出请求上限");
        List<JsonNode> updates = new ArrayList<>();
        Set<Long> updateIds = new HashSet<>();
        for (JsonNode update : result) {
            JsonNode id = update.get("update_id");
            if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() < 0 || id.longValue() == Long.MAX_VALUE
                    || !updateIds.add(id.longValue())) throw new IOException("Update ID 无效或重复");
            if (id.longValue() >= offset) updates.add(update);
        }
        updates.sort(Comparator.comparingLong(update -> update.path("update_id").longValue()));
        if (updates.isEmpty()) return;
        DirectoryState next = copy(state);
        for (JsonNode update : updates) {
            for (TelegramBotConfig bot : bots) apply(update, next.chats.computeIfAbsent(scope(bot), ignored -> new LinkedHashMap<>()),
                    next.stopped.computeIfAbsent(scope(bot), ignored -> new LinkedHashSet<>()), token);
            next.offsets.put(tokenHash, update.path("update_id").longValue() + 1);
        }
        FilesSupport.writeJson(file, next);
        state = next;
    }

    public List<Chat> chatsFor(TelegramBotConfig bot) {
        if (bot == null || bot.id == null || bot.token == null || bot.token.isBlank()) return List.of();
        Map<String, Chat> chats = state.chats.get(scope(bot));
        return chats == null ? List.of() : chats.values().stream().sorted(Comparator.comparing(Chat::chatId)).toList();
    }

    public List<Map<String, Object>> subscriptions(List<TelegramBotConfig> bots) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TelegramBotConfig bot : bots == null ? List.<TelegramBotConfig>of() : bots) if (bot != null) {
            for (Chat chat : chatsFor(bot)) result.add(Map.of("botId", bot.id, "chatId", chat.chatId(), "title", chat.title(), "type", chat.type()));
        }
        return List.copyOf(result);
    }

    private static void apply(JsonNode update, Map<String, Chat> chats, Set<String> stopped, String token) throws IOException {
        JsonNode member = update.path("my_chat_member");
        if (member.isObject()) {
            Chat chat = chat(member.path("chat"), token);
            String status = member.path("new_chat_member").path("status").asText();
            if (Set.of("left", "kicked").contains(status)) chats.remove(chat.chatId());
            else if (Set.of("group", "supergroup").contains(chat.type()) && Set.of("member", "administrator").contains(status)) {
                String oldStatus = member.path("old_chat_member").path("status").asText();
                boolean newlyJoined = oldStatus.isBlank() || Set.of("left", "kicked").contains(oldStatus);
                if (newlyJoined) { stopped.remove(chat.chatId()); chats.put(chat.chatId(), chat); }
                else if (chats.containsKey(chat.chatId()) && !stopped.contains(chat.chatId())) chats.put(chat.chatId(), chat);
            }
        }
        JsonNode message = update.path("message");
        if (!message.isObject()) return;
        Chat chat = chat(message.path("chat"), token);
        if (message.hasNonNull("migrate_to_chat_id")) {
            String newId = chatId(message.get("migrate_to_chat_id"));
            if (stopped.remove(chat.chatId())) stopped.add(newId);
            if (chats.remove(chat.chatId()) != null) chats.put(newId, new Chat(newId, chat.title(), "supergroup"));
            return;
        }
        if (message.hasNonNull("migrate_from_chat_id")) {
            String oldId = chatId(message.get("migrate_from_chat_id"));
            if (stopped.remove(oldId)) stopped.add(chat.chatId());
            if (chats.remove(oldId) != null) chats.put(chat.chatId(), chat);
            return;
        }
        String text = message.path("text").asText("").trim();
        String command = text.isEmpty() ? "" : text.split("\\s+", 2)[0].split("@", 2)[0].toLowerCase(Locale.ROOT);
        if (Set.of("/stop", "/unsubscribe").contains(command)) { chats.remove(chat.chatId()); stopped.add(chat.chatId()); return; }
        boolean explicitStart = Set.of("/start", "/subscribe").contains(command);
        if (explicitStart && Set.of("private", "group", "supergroup").contains(chat.type())) {
            stopped.remove(chat.chatId()); chats.put(chat.chatId(), chat);
        } else if ("private".equals(chat.type()) && !stopped.contains(chat.chatId())) chats.put(chat.chatId(), chat);
    }

    private static Chat chat(JsonNode node, String token) throws IOException {
        if (!node.isObject()) throw new IOException("Chat 字段无效");
        String id = chatId(node.get("id"));
        String type = node.path("type").asText();
        if (!Set.of("private", "group", "supergroup", "channel").contains(type)) throw new IOException("Chat 类型无效");
        String title = node.path("title").asText("");
        if (title.isBlank()) title = (node.path("first_name").asText("") + " " + node.path("last_name").asText("")).trim();
        if (title.isBlank()) title = node.path("username").asText(id);
        return new Chat(id, title.replace(token, "[已隐藏]"), type);
    }

    private static String chatId(JsonNode node) throws IOException {
        if (node == null || !(node.isIntegralNumber() || node.isTextual()) || !node.asText().matches("-?\\d{1,20}")) throw new IOException("Chat ID 无效");
        return node.asText();
    }

    private JsonNode request(String token, String method, String query) throws Exception {
        URI uri = URI.create(base.toString().replaceAll("/+$", "") + "/bot" + token + "/" + method + query);
        HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(timeout).header("Accept", "application/json").build();
        var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        final HttpResponse<String> response;
        try { response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS); }
        finally { if (!pending.isDone()) pending.cancel(true); }
        if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length() > 5_242_880) throw new IOException("Telegram 自动登记查询失败");
        JsonNode json;
        try { json = Json.MAPPER.readTree(response.body()); }
        catch (Exception ignored) { throw new IOException("Telegram 自动登记响应格式异常"); }
        if (json == null || !json.isObject() || !json.path("ok").isBoolean() || !json.path("ok").booleanValue()) throw new IOException("Telegram 自动登记返回失败状态");
        return json;
    }

    private static DirectoryState copy(DirectoryState old) {
        DirectoryState copy = new DirectoryState();
        copy.offsets.putAll(old.offsets);
        old.chats.forEach((scope, chats) -> copy.chats.put(scope, new LinkedHashMap<>(chats)));
        old.stopped.forEach((scope, chats) -> copy.stopped.put(scope, new LinkedHashSet<>(chats)));
        return copy;
    }
    private static String label(TelegramBotConfig bot) {
        String name = bot.name == null || bot.name.isBlank() ? bot.id : bot.name;
        return Objects.toString(name, "机器人").replace(bot.token, "[已隐藏]").replaceAll("[\\r\\n]", " ");
    }
    private static String scope(TelegramBotConfig bot) { return bot.id + ":" + tokenHash(bot.token); }
    private static String tokenHash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("无法计算机器人目录标识"); }
    }
}
