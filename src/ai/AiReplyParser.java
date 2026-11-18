package ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import util.JsonUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 输出 JSON 解析与短消息规范化。旧引擎与 Pro 引擎共用。
 *
 * 新格式：{"actions":[{"type":"text","content":"..."},
 * {"type":"emoji","id":"emo-..."}]}；
 * 旧格式 {"send":true,"messages":["..."]} 与 [[emoji:id]] 继续兼容。
 */
public final class AiReplyParser {

    private static final Pattern EMOJI_TOKEN = Pattern.compile(
            "\\[\\[emoji:([A-Za-z0-9_-]+)]]");
    private static final Pattern EMOJI_ID = Pattern.compile(
            "[A-Za-z0-9_-]+");
    public static final int DEFAULT_MAX_CONSECUTIVE_EMOJI = 3;

    public static final class ParsedReply {
        public final boolean send;
        public final List<AiReplyAction> actions;
        /**
         * 兼容旧调用：文字动作内容。
         */
        public final List<String> messages;

        public ParsedReply(boolean send, List<AiReplyAction> actions) {
            this.send = send;
            this.actions = actions == null
                    ? new ArrayList<>() : actions;
            this.messages = new ArrayList<>();
            for (AiReplyAction action : this.actions) {
                if (action.isText()) {
                    this.messages.add(action.content);
                }
            }
        }
    }

    private final int maxMessages;
    private final int maxChars;
    private final int maxConsecutiveEmoji;

    public AiReplyParser(int maxMessages, int maxChars) {
        this(maxMessages, maxChars, DEFAULT_MAX_CONSECUTIVE_EMOJI);
    }

    public AiReplyParser(int maxMessages,
                         int maxChars,
                         int maxConsecutiveEmoji) {
        this.maxMessages = Math.max(1, maxMessages);
        this.maxChars = Math.max(1, maxChars);
        this.maxConsecutiveEmoji = Math.max(1, maxConsecutiveEmoji);
    }

    public ParsedReply parse(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new IllegalArgumentException("空响应");
        }
        String json = stripCodeFence(raw.trim());

        JsonObject object = JsonUtil.parseObject(json);
        if (object != null) {
            if (object.has("actions")) {
                return parseActionsObject(object);
            }
            return parseLegacyObject(object);
        }

        JsonElement element = parseElement(json);
        if (element != null && element.isJsonArray()) {
            List<AiReplyAction> actions = new ArrayList<>();
            addLegacyMessages(actions, element);
            return new ParsedReply(!actions.isEmpty(), actions);
        }
        if (element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()) {
            List<AiReplyAction> actions = new ArrayList<>();
            addText(actions, element.getAsString());
            return new ParsedReply(!actions.isEmpty(), actions);
        }

        // JSON 模式未生效时的兜底：纯文本作为一条短消息发送。
        if (!looksLikeJson(json)) {
            List<AiReplyAction> actions = new ArrayList<>();
            addText(actions, json);
            return new ParsedReply(!actions.isEmpty(), actions);
        }
        throw new IllegalArgumentException("响应不是JSON对象");
    }

    private ParsedReply parseActionsObject(JsonObject object) {
        JsonElement element = object.get("actions");
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("actions 不是数组");
        }
        List<AiReplyAction> actions = parseActions(element.getAsJsonArray());
        boolean send = !actions.isEmpty();
        if (object.has("send") && object.get("send").isJsonPrimitive()) {
            try {
                send = object.get("send").getAsBoolean() && send;
            } catch (Exception e) {
                throw new IllegalArgumentException("send 不是布尔值");
            }
        }
        return new ParsedReply(send, actions);
    }

    private ParsedReply parseLegacyObject(JsonObject object) {
        List<AiReplyAction> actions = new ArrayList<>();
        List<String> messages = new ArrayList<>();
        messages = normalizeMessages(object.get("messages"), messages);
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
                addText(actions, single);
            } else {
                throw new IllegalArgumentException("缺少 send/messages");
            }
        }

        if (send && messages.isEmpty() && actions.isEmpty()) {
            String single = firstString(object,
                    "content", "reply", "message", "text");
            if (single != null) {
                addText(actions, single);
            }
        }
        for (String message : messages) {
            addText(actions, message);
        }
        return new ParsedReply(send, actions);
    }

    private List<AiReplyAction> parseActions(JsonArray array) {
        List<AiReplyAction> actions = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonElement item = array.get(i);
            if (item == null || item.isJsonNull()) {
                continue;
            }
            if (item.isJsonPrimitive()
                    && item.getAsJsonPrimitive().isString()) {
                addText(actions, item.getAsString());
                continue;
            }
            if (!item.isJsonObject()) {
                continue;
            }
            JsonObject object = item.getAsJsonObject();
            String type = firstString(object, "type");
            if (type == null) {
                String text = firstString(object, "content", "text");
                if (text != null) {
                    addText(actions, text);
                }
                continue;
            }
            String normalized = type.trim().toLowerCase(
                    java.util.Locale.ROOT);
            if (AiReplyAction.TYPE_EMOJI.equals(normalized)) {
                String id = firstString(object, "id");
                if (id == null) {
                    id = firstString(object, "emojiId", "content");
                }
                addEmoji(actions, id);
            } else {
                String text = firstString(object, "content", "text");
                if (text != null) {
                    addText(actions, text);
                }
            }
        }
        return actions;
    }

    private void addLegacyMessages(List<AiReplyAction> actions,
                                   JsonElement element) {
        List<String> messages = normalizeMessages(element, new ArrayList<>());
        for (String message : messages) {
            addText(actions, message);
        }
    }

    private List<String> normalizeMessages(JsonElement element,
                                           List<String> messages) {
        if (element == null || element.isJsonNull()) {
            return messages;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (int i = 0; i < array.size(); i++) {
                JsonElement item = array.get(i);
                if (item.isJsonPrimitive()
                        && item.getAsJsonPrimitive().isString()) {
                    addRawMessage(messages, item.getAsString());
                }
                if (messages.size() >= maxMessages) {
                    break;
                }
            }
        } else if (element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString()) {
            addRawMessage(messages, element.getAsString());
        }
        return messages;
    }

    private void addRawMessage(List<String> messages, String value) {
        if (messages.size() >= maxMessages) {
            return;
        }
        String normalized = normalizeText(value);
        if (!normalized.isEmpty()) {
            messages.add(normalized);
        }
    }

    /**
     * 文字动作计入单次文字条数；[[emoji:id]] 转为表情动作。
     */
    private void addText(List<AiReplyAction> actions, String value) {
        String text = value == null ? "" : value;
        Matcher matcher = EMOJI_TOKEN.matcher(text);
        int cursor = 0;
        while (matcher.find()) {
            addPlainText(actions, text.substring(cursor, matcher.start()));
            addEmoji(actions, matcher.group(1));
            cursor = matcher.end();
        }
        addPlainText(actions, text.substring(cursor));
    }

    private void addPlainText(List<AiReplyAction> actions, String value) {
        if (textCount(actions) >= maxMessages) {
            return;
        }
        String normalized = normalizeText(value);
        if (normalized.isEmpty()) {
            return;
        }
        actions.add(AiReplyAction.text(truncate(normalized, maxChars)));
    }

    private void addEmoji(List<AiReplyAction> actions, String emojiId) {
        String id = emojiId == null ? "" : emojiId.trim();
        if (id.isEmpty() || !EMOJI_ID.matcher(id).matches()) {
            return;
        }
        if (consecutiveEmoji(actions) >= maxConsecutiveEmoji) {
            return;
        }
        actions.add(AiReplyAction.emoji(id));
    }

    private static int textCount(List<AiReplyAction> actions) {
        int count = 0;
        for (AiReplyAction action : actions) {
            if (action.isText()) {
                count++;
            }
        }
        return count;
    }

    private static int consecutiveEmoji(List<AiReplyAction> actions) {
        int count = 0;
        for (int i = actions.size() - 1; i >= 0; i--) {
            if (!actions.get(i).isEmoji()) {
                break;
            }
            count++;
        }
        return count;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
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
