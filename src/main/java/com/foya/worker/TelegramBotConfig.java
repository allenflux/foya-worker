package com.foya.worker;

import java.time.LocalTime;

/** One independently configured recipient bot. Tokens are removed from all public projections. */
public class TelegramBotConfig {
    public String id = "";
    public String name = "机器人";
    public String token = "";
    public String chatId = "";
    public String threadId = "";
    public String sendTime = "";
    public boolean enabled = true;
    public boolean autoDiscover = true;

    public void validate() {
        if (id == null || name == null || token == null || chatId == null || threadId == null || sendTime == null)
            throw new IllegalArgumentException("机器人配置不能为 null。");
        if (!id.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("机器人 ID 格式无效。");
        if (name.isBlank() || name.length() > 100 || name.matches("(?s).*[\\p{Cc}].*"))
            throw new IllegalArgumentException("机器人名称不能为空，最多 100 字符且不能含控制字符。");
        if (!token.isBlank() && (token.length() > 512 || !token.matches("[0-9]+:[A-Za-z0-9_-]+")))
            throw new IllegalArgumentException("机器人 Token 格式无效。");
        if (!chatId.isBlank() && !chatId.matches("-?\\d+|@[A-Za-z0-9_]{5,}"))
            throw new IllegalArgumentException("机器人 Chat ID 应为数字或 @频道用户名。");
        if (!threadId.isBlank()) {
            try { if (!threadId.matches("[1-9]\\d*") || Integer.parseInt(threadId) < 1) throw new NumberFormatException(); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("机器人话题 ID 应为有效正整数。"); }
        }
        if (!sendTime.isBlank()) {
            try { if (!sendTime.matches("\\d{2}:\\d{2}")) throw new IllegalArgumentException(); LocalTime.parse(sendTime); }
            catch (Exception e) { throw new IllegalArgumentException("机器人发送时间格式应为 HH:mm。"); }
        }
    }

    public boolean ready() { return enabled && !token.isBlank() && (autoDiscover || !chatId.isBlank()); }
}
