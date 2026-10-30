package com.foya.worker;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Telegram API adapter. Credentials and upstream response bodies never enter errors. */
public final class TelegramClient {
    private static final int MAX_TEXT_LENGTH = 4096;
    private final URI base;
    private final HttpClient client;
    private final Duration requestTimeout;

    public TelegramClient() {
        this(URI.create("https://api.telegram.org"));
    }

    public TelegramClient(URI base) { this(base, Duration.ofSeconds(30)); }

    TelegramClient(URI base, Duration requestTimeout) {
        if (base == null || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null
                || !("https".equals(base.getScheme()) || ("http".equals(base.getScheme())
                && List.of("localhost", "127.0.0.1", "::1", "[::1]").contains(base.getHost())))) {
            throw new IllegalArgumentException("Telegram API 地址无效");
        }
        this.base = base;
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) throw new IllegalArgumentException("Telegram 请求超时必须为正数");
        this.requestTimeout = requestTimeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Read-only credential check; this does not send a message. */
    public Map<String, Object> testConnection(AppConfig config) throws IOException {
        String token = token(config);
        JsonNode response = request(token, "getMe", Map.of(), false);
        JsonNode result = response.path("result");
        if (!result.isObject() || !result.path("is_bot").asBoolean(false)) {
            throw new IOException("Telegram 返回了无效的机器人信息");
        }
        String username = result.path("username").asText("");
        return Map.of("message", username.isBlank() ? "Telegram 机器人连接成功" : "Telegram 机器人连接成功：@" + username,
                "username", username);
    }

    /** Explicit delivery only. Ambiguous transport failures are never automatically retried. */
    public Map<String, Object> sendReport(AppConfig config, String text) throws IOException {
        String token = token(config);
        String chatId = value(config.telegramChatId);
        if (!chatId.matches("-?[0-9]+|@[A-Za-z0-9_]{5,}")) {
            throw new IllegalArgumentException("请填写有效的 Telegram Chat ID 或 @频道用户名");
        }
        String threadId = value(config.telegramThreadId);
        Integer thread = null;
        if (!threadId.isEmpty()) {
            try {
                thread = Integer.valueOf(threadId);
                if (thread <= 0) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Telegram 话题 ID 必须为正整数");
            }
        }
        List<String> parts = splitMessage(text);
        List<Long> messageIds = new ArrayList<>();
        for (String part : parts) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chat_id", chatId);
            body.put("text", part);
            body.put("disable_web_page_preview", true);
            if (thread != null) body.put("message_thread_id", thread);
            try {
                JsonNode response = request(token, "sendMessage", body, true);
                JsonNode id = response.path("result").path("message_id");
                if (!id.canConvertToLong() || !id.isIntegralNumber()) {
                    throw new ApiException("Telegram 未返回消息确认，可能已送达，请先核对聊天记录", true);
                }
                messageIds.add(id.longValue());
            } catch (IOException e) {
                boolean uncertain = e instanceof ApiException ae && ae.uncertain;
                throw new DeliveryException("Telegram 发送中断，已确认发送 " + messageIds.size() + "/" + parts.size()
                        + " 段。" + e.getMessage(), messageIds.size(), parts.size(), uncertain);
            }
        }
        return Map.of("message", "报表已发送至 Telegram，共 " + parts.size() + " 段",
                "count", parts.size(), "messageIds", List.copyOf(messageIds));
    }

    static List<String> splitMessage(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("报表内容为空，无法发送");
        List<String> parts = new ArrayList<>();
        int position = 0;
        while (text.length() - position > MAX_TEXT_LENGTH) {
            int limit = position + MAX_TEXT_LENGTH;
            if (Character.isHighSurrogate(text.charAt(limit - 1)) && Character.isLowSurrogate(text.charAt(limit))) limit--;
            int newline = text.lastIndexOf('\n', limit - 1);
            int cut = newline > position ? newline : limit;
            parts.add(text.substring(position, cut));
            position = newline > position ? cut + 1 : cut;
        }
        if (position < text.length()) parts.add(text.substring(position));
        return List.copyOf(parts);
    }

    private JsonNode request(String token, String method, Map<String, Object> body, boolean sending) throws IOException {
        final String payload;
        try {
            payload = Json.MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new IOException("Telegram 请求编码失败");
        }
        for (int attempt = 0; ; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base.toString().replaceAll("/+$", "") + "/bot" + token + "/" + method))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
            final HttpResponse<String> response;
            CompletableFuture<HttpResponse<String>> pending = null;
            try {
                pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                response = pending.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(sending ? "发送已中断，投递状态未知，请先核对聊天记录" : "Telegram 连接检查已中断", sending);
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                throw new ApiException(sending ? "Telegram 网络异常，投递状态未知，请先核对聊天记录" : "Telegram 网络连接失败", sending);
            } finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
            final JsonNode json;
            try {
                json = Json.MAPPER.readTree(response.body());
                if (json == null || !json.isObject()) throw new IOException();
            } catch (Exception e) {
                throw new ApiException("Telegram 返回格式异常" + (sending ? "，投递状态未知，请先核对聊天记录" : ""), sending);
            }
            int code = response.statusCode();
            int errorCode = json.path("error_code").asInt(code);
            if (code >= 200 && code < 300 && json.path("ok").isBoolean() && json.path("ok").booleanValue()) return json;
            if ((code == 429 || errorCode == 429) && !json.path("ok").asBoolean(false)) {
                int retryAfter = json.path("parameters").path("retry_after").asInt(1);
                if (attempt < 2 && retryAfter >= 0 && retryAfter <= 30) {
                    try {
                        Thread.sleep(Math.max(1, retryAfter) * 1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new ApiException("Telegram 限流等待已中断", false);
                    }
                    continue;
                }
                throw new ApiException("Telegram 请求受限，请稍后重试", false);
            }
            if (code >= 500 || errorCode >= 500) {
                throw new ApiException("Telegram 服务暂时不可用" + (sending ? "，投递状态未知，请先核对聊天记录" : ""), sending);
            }
            String message = switch (errorCode) {
                case 400 -> "Telegram 拒绝请求，请检查 Chat ID、话题 ID 和机器人权限";
                case 401, 404 -> "Telegram Bot Token 无效或机器人不存在";
                case 403 -> "Telegram 机器人无权访问目标聊天，请检查成员身份及发言权限";
                default -> "Telegram 请求失败（HTTP " + code + "）";
            };
            throw new ApiException(message, false);
        }
    }

    private static String token(AppConfig config) {
        String token = value(config.telegramBotToken);
        if (!token.matches("[0-9]+:[A-Za-z0-9_-]+")) throw new IllegalArgumentException("请先配置有效的 Telegram Bot Token");
        return token;
    }

    private static String value(String value) { return value == null ? "" : value.trim(); }

    private static final class ApiException extends IOException {
        private final boolean uncertain;
        ApiException(String message, boolean uncertain) { super(message); this.uncertain = uncertain; }
    }

    public static final class DeliveryException extends IOException {
        public final int sentParts;
        public final int totalParts;
        public final boolean deliveryUncertain;
        DeliveryException(String message, int sentParts, int totalParts, boolean deliveryUncertain) {
            super(message);
            this.sentParts = sentParts;
            this.totalParts = totalParts;
            this.deliveryUncertain = deliveryUncertain;
        }
    }
}
