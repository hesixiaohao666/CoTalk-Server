package ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import util.JsonUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 输出 JSON 解析与短消息规范化。旧引擎与 Pro 引擎共用。
 */
public final class AiReplyParser {

    public static final class ParsedReply {
        public final boolean send;
        public final List<String> messages;

        public ParsedReply(boolean send, List<String> messages) {
            this.send = send;
            this.messages = messages == null
                    ? new ArrayList<>() : messages;
        }
    }

    private final int maxMessages;
    private final int maxChars;

    public AiReplyParser(int maxMessages, int maxChars) {
        this.maxMessages = Math.max(1, maxMessages);
        this.maxChars = Math.max(1, maxChars);
    }

    public ParsedReply parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("空响应");
        }
        String json = stripCodeFence(raw.trim());

        JsonObject object = JsonUtil.parseObject(json);
        if (object != null) {
            List<String> messages = normalizeMessages(object.get("messages"));
            boolean hasSend = object.has("send")
                    && object.get("send").isJsonPrimitive();
            boolean send;
            if (hasSend) {
                try {
                    send = object.get("send").getAsBoolean();
                } catch (Exception e) {
                    throw new IllegalArgumentException("send 不是布尔值");
                }
            } else if (object.has("messages")) {
                // 模型偶尔省略 send：有短消息即视为要发送。
                send = !messages.isEmpty();
            } else {
                String single = firstString(object,
                        "content", "reply", "message", "text");
                if (single != null) {
                    send = true;
                    messages = new ArrayList<>();
                    addMessage(messages, single);
                } else {
                    throw new IllegalArgumentException("缺少 send/messages");
                }
            }

            if (send && messages.isEmpty()) {
                String single = firstString(object,
                        "content", "reply", "message", "text");
                if (single != null) {
                    addMessage(messages, single);
                }
            }
            return new ParsedReply(send, messages);
        }

        JsonElement element = parseElement(json);
        if (element != null && element.isJsonArray()) {
            List<String> messages = normalizeMessages(element);
            return new ParsedReply(!messages.isEmpty(), messages);
        }
        if (element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()) {
            List<String> messages = new ArrayList<>();
            addMessage(messages, element.getAsString());
            return new ParsedReply(!messages.isEmpty(), messages);
        }

        // JSON 模式未生效时的兜底：纯文本作为一条短消息发送。
        if (!looksLikeJson(json)) {
            List<String> messages = new ArrayList<>();
            addMessage(messages, json);
            return new ParsedReply(!messages.isEmpty(), messages);
        }
        throw new IllegalArgumentException("响应不是JSON对象");
    }

    private List<String> normalizeMessages(JsonElement element) {
        List<String> messages = new ArrayList<>();
        if (element == null || element.isJsonNull()) {
            return messages;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement item = array.get(i);
                if (item.isJsonPrimitive()
                        && item.getAsJsonPrimitive().isString()) {
                    addMessage(messages, item.getAsString());
                }
                if (messages.size() >= maxMessages) {
                    break;
                }
            }
        } else if (element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()) {
            addMessage(messages, element.getAsString());
        }
        return messages;
    }

    private void addMessage(List<String> messages, String value) {
        if (messages.size() >= maxMessages) {
            return;
        }
        String normalized = value == null ? "" : value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (normalized.isEmpty()) {
            return;
        }
        messages.add(truncate(normalized, maxChars));
    }

    private static String stripCodeFence(String value) {
        if (!value.startsWith("```")) {
            return value;
        }
        int firstLine = value.indexOf('\n');
        int lastFence = value.lastIndexOf("```");
        if (firstLine > 0 && lastFence > firstLine) {
            return value.substring(firstLine + 1, lastFence).trim();
        }
        return value;
    }

    private static String firstString(JsonObject object, String... keys) {
        for (String key : keys) {
            if (object.has(key) && object.get(key).isJsonPrimitive()
                    && object.get(key).getAsJsonPrimitive().isString()) {
                return object.get(key).getAsString();
            }
        }
        return null;
    }

    private static JsonElement parseElement(String json) {
        try {
            return JsonParser.parseString(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean looksLikeJson(String value) {
        if (value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        return first == '{' || first == '[';
    }

    private static String truncate(String value, int maxChars) {
        if (value.codePointCount(0, value.length()) <= maxChars) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxChars);
        return value.substring(0, end);
    }
}
