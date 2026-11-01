package ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import model.Member;
import model.Message;
import server.ServerConfig;
import util.JsonUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * AI 群友：@ 消息必须回复；未被 @ 的提及由 AI 自行决定是否回复。
 * 3 秒窗口内合并，单线程调用并发送短消息。
 */
public class DeepSeekAiResponder implements AiResponder {

    private static final String MANDATORY_FALLBACK = "我在，怎么啦？";

    private final ServerConfig config;
    private final DeepSeekClient client;
    private final AiReplySink sink;
    private final List<String> directMentions;
    private final List<String> optionalMentions;

    private final Object lock = new Object();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "cotalk-ai-timer");
                thread.setDaemon(true);
                return thread;
            });
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "cotalk-ai-worker");
        thread.setDaemon(true);
        return thread;
    });

    private ScheduledFuture<?> pendingTask;
    private List<Message> pendingContext = Collections.emptyList();
    private final List<String> pendingMessages = new ArrayList<>();
    private boolean pendingMandatory;
    private boolean inFlight;
    private volatile boolean closed;

    public DeepSeekAiResponder(ServerConfig config,
                               DeepSeekClient client,
                               AiReplySink sink) {
        this.config = config;
        this.client = client;
        this.sink = sink;
        List<String> configuredMentions = splitMentions(config.aiMentions);
        this.directMentions = directMentions(configuredMentions);
        this.optionalMentions = optionalMentions(
                config.aiName, configuredMentions);
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public void onMessage(Member sender,
                          Message message,
                          List<Message> recentContext) {
        if (closed || sender == null || message == null || sender.ai) {
            return;
        }
        boolean mandatory = containsAny(message.content, directMentions);
        boolean optional = containsAny(message.content, optionalMentions);

        synchronized (lock) {
            if (closed) {
                return;
            }
            // 防抖窗口内即使出现非触发消息，也应进入 AI 的最新上下文。
            pendingContext = recentContext == null
                    ? Collections.emptyList()
                    : new ArrayList<>(recentContext);
            if (!mandatory && !optional) {
                return;
            }
            String line = sender.nickname + "：" + message.content.trim();
            if (line.length() > 500) {
                line = line.substring(0, 500) + "…";
            }
            pendingMessages.add(line);
            if (mandatory) {
                pendingMandatory = true;
            }

            if (pendingTask != null) {
                pendingTask.cancel(false);
            }
            pendingTask = scheduler.schedule(
                    this::dispatch, config.aiCooldownMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void onMemberJoined(Member member) {
        // AI 不主动欢迎，也不主动插话。
    }

    @Override
    public void shutdown() {
        synchronized (lock) {
            closed = true;
            if (pendingTask != null) {
                pendingTask.cancel(false);
                pendingTask = null;
            }
            pendingMessages.clear();
            pendingMandatory = false;
            pendingContext = Collections.emptyList();
        }
        scheduler.shutdownNow();
        worker.shutdownNow();
    }

    private void dispatch() {
        List<Message> context;
        List<String> mergedMessages;
        boolean mandatory;
        synchronized (lock) {
            if (closed) {
                return;
            }
            pendingTask = null;
            if (inFlight || pendingMessages.isEmpty()) {
                // 请求进行中收到的消息先留在 pending，等本轮结束后合并再发。
                return;
            }
            inFlight = true;
            context = pendingContext;
            pendingContext = Collections.emptyList();
            mergedMessages = new ArrayList<>(pendingMessages);
            pendingMessages.clear();
            mandatory = pendingMandatory;
            pendingMandatory = false;
        }
        worker.execute(() -> respond(mergedMessages, context, mandatory));
    }

    private void respond(List<String> mergedMessages,
                         List<Message> context,
                         boolean mandatory) {
        try {
            String raw = client.chatCompletion(
                    buildRequestMessages(mergedMessages, context, mandatory));
            ParsedReply reply = parseReply(raw);
            if (reply == null || !reply.send || reply.messages.isEmpty()) {
                if (mandatory) {
                    sendMandatoryFallback();
                }
                return;
            }

            for (int i = 0; i < reply.messages.size(); i++) {
                if (closed) {
                    return;
                }
                if (i > 0 && config.aiReplyDelayMs > 0) {
                    Thread.sleep(config.aiReplyDelayMs);
                }
                sink.appendAiMessage(reply.messages.get(i));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IllegalArgumentException e) {
            sink.reportError("AI 输出格式无效：" + e.getMessage());
            if (mandatory) {
                sendMandatoryFallback();
            }
        } catch (Exception e) {
            sink.reportError("AI 调用失败：" + e.getMessage());
            if (mandatory) {
                sink.reportFailure(config.aiName + "暂时无法回复，请稍后再试");
            }
        } finally {
            finishRequest();
        }
    }

    private void sendMandatoryFallback() {
        sink.reportError("AI 未按强制回复要求输出，使用兜底回复");
        try {
            sink.appendAiMessage(MANDATORY_FALLBACK);
        } catch (Exception e) {
            sink.reportError("AI 兜底回复发送失败：" + e.getMessage());
            sink.reportFailure(config.aiName + "暂时无法回复，请稍后再试");
        }
    }

    private void finishRequest() {
        synchronized (lock) {
            inFlight = false;
            if (closed || pendingMessages.isEmpty() || pendingTask != null) {
                return;
            }
            pendingTask = scheduler.schedule(
                    this::dispatch, config.aiCooldownMs, TimeUnit.MILLISECONDS);
        }
    }

    private List<AiMessage> buildRequestMessages(List<String> mergedMessages,
                                                 List<Message> context,
                                                 boolean mandatory) {
        List<AiMessage> result = new ArrayList<>();
        result.add(new AiMessage("system", config.aiPrompt
                + "\n回复规则：被 @ 时必须回复至少一条；未被 @ 的提及可自主判断，"
                + "只有确实有必要参与时才回复，否则 send=false。"
                + "\n当前限制：每条最多" + config.aiMessageMaxChars
                + "个字符，最多" + config.aiMaxMessages
                + "条；只输出 json 对象，不要输出解释。"));

        int max = Math.max(1, config.aiMaxContext);
        int start = Math.max(0, context.size() - max);
        for (int i = start; i < context.size(); i++) {
            Message message = context.get(i);
            if (message == null || message.content == null) {
                continue;
            }
            if ("system".equals(message.type)) {
                continue;
            }
            if (AI_USER_ID.equals(message.senderId)) {
                result.add(new AiMessage("assistant", message.content));
            } else {
                result.add(new AiMessage("user",
                        message.senderName + "：" + message.content));
            }
        }

        if (!mergedMessages.isEmpty()) {
            StringBuilder prompt = new StringBuilder();
            if (mandatory) {
                prompt.append("以下消息包含对你的 @，必须合并回复一次：\n");
            } else {
                prompt.append("以下消息提到了你（未 @），请判断是否有必要参与；"
                        + "没有必要时输出 {\"send\":false,\"messages\":[]}：\n");
            }
            for (String message : mergedMessages) {
                prompt.append(message).append('\n');
            }
            result.add(new AiMessage("user", prompt.toString().trim()));
        }
        return result;
    }

    private ParsedReply parseReply(String raw) {
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
                if (messages.size() >= config.aiMaxMessages) {
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
        if (messages.size() >= config.aiMaxMessages) {
            return;
        }
        String normalized = value == null ? "" : value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (normalized.isEmpty()) {
            return;
        }
        messages.add(truncate(normalized, config.aiMessageMaxChars));
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

    private static boolean containsAny(String content, List<String> keywords) {
        if (content == null || content.isEmpty() || keywords.isEmpty()) {
            return false;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> directMentions(List<String> configured) {
        List<String> result = new ArrayList<>();
        for (String mention : configured) {
            if (mention.startsWith("@") && mention.length() > 1
                    && !result.contains(mention)) {
                result.add(mention);
            }
        }
        return result;
    }

    private static List<String> optionalMentions(String aiName,
                                                 List<String> configured) {
        List<String> result = new ArrayList<>();
        addOptionalMention(result, aiName);
        for (String mention : configured) {
            String name = mention.startsWith("@")
                    ? mention.substring(1).trim()
                    : mention.trim();
            addOptionalMention(result, name);
        }
        return result;
    }

    private static void addOptionalMention(List<String> result, String value) {
        if (value != null && !value.trim().isEmpty()
                && !result.contains(value.trim())) {
            result.add(value.trim());
        }
    }

    private static List<String> splitMentions(String value) {
        List<String> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        for (String part : value.split("[,，\\s]+")) {
            String mention = part.trim();
            if (!mention.isEmpty() && !result.contains(mention)) {
                result.add(mention);
            }
        }
        return result;
    }

    private static String truncate(String value, int maxChars) {
        if (maxChars <= 0) {
            return "";
        }
        if (value.codePointCount(0, value.length()) <= maxChars) {
            return value;
        }
        int end = value.offsetByCodePoints(0, maxChars);
        return value.substring(0, end);
    }

    private static final class ParsedReply {
        final boolean send;
        final List<String> messages;

        ParsedReply(boolean send, List<String> messages) {
            this.send = send;
            this.messages = messages;
        }
    }
}
