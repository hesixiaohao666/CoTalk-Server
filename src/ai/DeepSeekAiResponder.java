package ai;

import model.Member;
import model.Message;
import server.ServerConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * AI 群友：@ 必回；关键词大概率参与；其他消息小概率随机参与。
 * 3 秒窗口内合并，单线程调用并发送短消息。
 */
public class DeepSeekAiResponder implements AiResponder {

    private static final String MANDATORY_FALLBACK = "这个我暂时不能回答，换个话题吧。";
    private static final int MAX_VISION_IMAGES = 2;
    private static final String SEARCH_INJECTION_PREFIX = "【联网搜索结果】";
    private static final String MEMORY_INJECTION_PREFIX = "【长期记忆参考";

    protected final ServerConfig config;
    private final DeepSeekClient client;
    protected final AiReplySink sink;
    private final AiImageResolver imageResolver;
    private final AiReplyParser replyParser;
    private final List<String> directMentions;
    protected final List<String> keywords;

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
        this(config, client, sink, null);
    }

    public DeepSeekAiResponder(ServerConfig config,
                               DeepSeekClient client,
                               AiReplySink sink,
                               AiImageResolver imageResolver) {
        this.config = config;
        this.client = client;
        this.sink = sink;
        this.imageResolver = imageResolver;
        this.replyParser = new AiReplyParser(
                config.aiMaxMessages, config.aiMessageMaxChars);
        List<String> configuredMentions = splitMentions(config.aiMentions);
        this.directMentions = directMentions(configuredMentions);
        this.keywords = keywords(
                config.aiName, config.aiKeywords, configuredMentions);
    }

    @Override
    public boolean isActive() {
        return true;
    }

    protected boolean isDirectMention(String content) {
        return containsAny(content, directMentions);
    }

    /**
     * 未命中关键词时的随机参与概率；经典与 Pro 共用峰谷时判断。
     */
    protected int scheduleChance() {
        return AiSchedule.chance(config);
    }

    @Override
    public void onMessage(Member sender,
                          Message message,
                          List<Message> recentContext) {
        if (closed || sender == null || message == null || sender.ai) {
            return;
        }
        boolean mandatory = containsAny(message.content, directMentions);
        boolean keyword = !mandatory && containsAny(message.content, keywords);
        boolean triggered = mandatory
                || (keyword
                ? hit(config.aiKeywordChance)
                : hit(scheduleChance()));

        synchronized (lock) {
            if (closed) {
                return;
            }
            // 防抖窗口内即使未触发回复，也应进入 AI 的最新上下文。
            pendingContext = recentContext == null
                    ? Collections.emptyList()
                    : new ArrayList<>(recentContext);
            if (!triggered) {
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
            List<AiMessage> request = buildRequestMessages(
                    mergedMessages, context, mandatory);
            List<AiMessage> activeRequest = request;
            DeepSeekResult result;
            try {
                result = client.chatCompletionResult(activeRequest);
            } catch (Exception firstError) {
                if (!containsImages(activeRequest)) {
                    throw firstError;
                }
                sink.reportError("AI 识图请求失败，回退纯文本："
                        + firstError.getMessage());
                activeRequest = withoutImages(activeRequest);
                result = client.chatCompletionResult(activeRequest);
            }
            if (result == null) {
                result = DeepSeekResult.from("");
            }
            if (result.isEmptyContent()) {
                logEmpty("首次", result, activeRequest);
                result = retryCall(activeRequest, result);
                if (result.isEmptyContent()) {
                    logEmpty("重试", result, activeRequest);
                    List<AiMessage> plainRequest =
                            withoutInjectedReferences(activeRequest);
                    if (plainRequest.size() != activeRequest.size()) {
                        sink.reportError("AI 增强提示空响应，回退普通回答重试");
                        AiDebugLog.log("fallback=remove-injected-references");
                        activeRequest = plainRequest;
                        result = retryCall(activeRequest, result);
                        if (result.isEmptyContent()) {
                            logEmpty("回退", result, activeRequest);
                            if (mandatory) {
                                sendMandatoryFallback();
                            }
                            return;
                        }
                    } else {
                        if (mandatory) {
                            sendMandatoryFallback();
                        }
                        return;
                    }
                }
            }
            AiReplyParser.ParsedReply reply;
            try {
                reply = replyParser.parse(result.content);
            } catch (IllegalArgumentException firstError) {
                sink.reportError("AI 响应无效，重试一次："
                        + firstError.getMessage());
                List<AiMessage> repairBase =
                        withoutInjectedReferences(activeRequest);
                if (repairBase.size() != activeRequest.size()) {
                    sink.reportError("AI 移除增强提示后重试修复");
                    activeRequest = repairBase;
                }
                DeepSeekResult repaired = retryCall(
                        buildRetryMessages(activeRequest), result);
                if (repaired.isEmptyContent()) {
                    logEmpty("修复", repaired, activeRequest);
                    if (mandatory) {
                        sendMandatoryFallback();
                    }
                    return;
                }
                reply = replyParser.parse(repaired.content);
            }
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
                sendReplyMessage(reply.messages.get(i));
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

    private DeepSeekResult retryCall(List<AiMessage> request,
                                     DeepSeekResult previous)
            throws Exception {
        DeepSeekResult result = client.chatCompletionResult(
                request, retryMaxTokens(previous), false);
        return result == null ? DeepSeekResult.from("") : result;
    }

    private int retryMaxTokens(DeepSeekResult previous) {
        int base = previous != null && previous.maxTokens > 0
                ? previous.maxTokens : config.aiMaxTokens;
        long doubled = Math.max((long) base * 2, 800L);
        return (int) Math.min(Integer.MAX_VALUE, doubled);
    }

    private void logEmpty(String stage,
                          DeepSeekResult result,
                          List<AiMessage> request) {
        String message = "AI " + stage + "空响应：finish_reason="
                + safe(result.finishReason)
                + "，contentLength=" + result.contentLength()
                + "，trimmedLength=" + result.content.strip().length()
                + "，reasoningOnly=" + result.reasoningOnly
                + "，requestId=" + safe(result.requestId)
                + "，thinking=" + result.thinking
                + "，maxTokens=" + result.maxTokens;
        sink.reportError(message);
        AiDebugLog.log(message
                + "，requestMessages=" + request.size()
                + "，requestChars=" + requestChars(request)
                + "，searchInjection=" + hasInjection(
                request, SEARCH_INJECTION_PREFIX)
                + "，memoryInjection=" + hasInjection(
                request, MEMORY_INJECTION_PREFIX));
    }

    private static int requestChars(List<AiMessage> request) {
        int chars = 0;
        for (AiMessage message : request) {
            chars += message.content == null ? 0 : message.content.length();
        }
        return chars;
    }

    private static boolean hasInjection(List<AiMessage> request,
                                        String prefix) {
        for (AiMessage message : request) {
            if ("user".equals(message.role)
                    && message.content != null
                    && message.content.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "-" : value.trim();
    }

    private List<AiMessage> buildRetryMessages(List<AiMessage> request) {
        List<AiMessage> retry = new ArrayList<>(request);
        retry.add(new AiMessage("user", AiJsonContract.REPAIR_INSTRUCTION));
        return retry;
    }

    private static boolean containsImages(List<AiMessage> messages) {
        for (AiMessage message : messages) {
            if (message.images != null && !message.images.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static List<AiMessage> withoutImages(List<AiMessage> messages) {
        List<AiMessage> result = new ArrayList<>(messages.size());
        for (AiMessage message : messages) {
            result.add(new AiMessage(message.role, message.content));
        }
        return result;
    }

    /**
     * 搜索/记忆增强内容可能导致模型空响应或输出异常；
     * 重试时移除这些注入，保留系统提示、上下文和触发消息。
     */
    private static List<AiMessage> withoutInjectedReferences(
            List<AiMessage> messages) {
        List<AiMessage> result = new ArrayList<>(messages.size());
        for (AiMessage message : messages) {
            if ("user".equals(message.role)
                    && (message.content.startsWith(SEARCH_INJECTION_PREFIX)
                    || message.content.startsWith(MEMORY_INJECTION_PREFIX))) {
                continue;
            }
            result.add(message);
        }
        return result;
    }

    private void sendMandatoryFallback() {
        sink.reportError("AI 未按强制回复要求输出，使用兜底回复");
        AiDebugLog.log("fallback=mandatory-message");
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

    private static Set<String> selectRecentImageIds(List<Message> context,
                                                   int start) {
        Set<String> result = new HashSet<>();
        for (int i = context.size() - 1;
             i >= start && result.size() < MAX_VISION_IMAGES; i--) {
            Message message = context.get(i);
            if (message == null || message.fileExpired
                    || message.fileId == null || message.fileId.isEmpty()) {
                continue;
            }
            if ("image".equals(message.type) || "emoji".equals(message.type)) {
                result.add(message.fileId);
            }
        }
        return result;
    }

    /**
     * Pro 引擎通过覆写该方法替换核心记忆/红线提示词。
     */
    protected String buildSystemPrompt() {
        return AiPrompts.buildSystemPrompt(
                config.aiPromptCustom,
                config.aiMessageMaxChars,
                config.aiMaxMessages,
                "回复规则：被 @ 时必须回复至少一条；被关键词或随机触发时"
                        + "可自主判断，只有确实有必要参与时才回复，否则 send=false；"
                        + "遇到违法违规或违背道德的内容必须拒绝。");
    }

    /**
     * Pro 引擎可以单独配置回复上下文条数。
     */
    protected int contextMessageLimit() {
        return Math.max(1, config.aiMaxContext);
    }

    /**
     * Pro 引擎在系统消息之后、上下文之前插入长期记忆参考。
     */
    protected List<AiMessage> buildExtraUserMessages(
            List<String> mergedMessages,
            List<Message> context) {
        return Collections.emptyList();
    }

    /**
     * Pro 引擎可在此把 [[emoji:id]] 转为表情消息。
     */
    protected void sendReplyMessage(String content) throws Exception {
        sink.appendAiMessage(content);
    }

    private List<AiMessage> buildRequestMessages(List<String> mergedMessages,
                                                 List<Message> context,
                                                 boolean mandatory) {
        List<AiMessage> result = new ArrayList<>();
        result.add(new AiMessage("system", buildSystemPrompt()));
        result.addAll(buildExtraUserMessages(mergedMessages, context));

        int max = contextMessageLimit();
        int start = Math.max(0, context.size() - max);
        Set<String> selectedImageIds = config.aiVision
                ? selectRecentImageIds(context, start)
                : Collections.emptySet();
        int lastJoinLeave = config.aiSystemMessages
                ? lastJoinLeaveIndex(context, start)
                : -1;
        for (int i = start; i < context.size(); i++) {
            Message message = context.get(i);
            if (message == null) {
                continue;
            }
            if ("system".equals(message.type)) {
                if (!config.aiSystemMessages) {
                    continue;
                }
                if (isJoinLeave(message) && i != lastJoinLeave) {
                    continue;
                }
                String content = message.content == null
                        ? "" : message.content.trim();
                if (content.isEmpty()) {
                    continue;
                }
                result.add(new AiMessage("user", "[系统] " + content));
                continue;
            }

            if ("image".equals(message.type) || "emoji".equals(message.type)) {
                String label = "image".equals(message.type)
                        ? "[图片]" : "[表情]";
                String sender = message.senderName == null
                        ? "成员" : message.senderName;
                StringBuilder text = new StringBuilder(sender)
                        .append("：").append(label);
                if (message.content != null
                        && !message.content.trim().isEmpty()) {
                    text.append(" ").append(message.content.trim());
                }
                List<AiImagePart> images = new ArrayList<>();
                if (message.fileId != null
                        && selectedImageIds.contains(message.fileId)
                        && imageResolver != null) {
                    try {
                        AiImagePart image = imageResolver.resolve(message.fileId);
                        if (image != null) {
                            images.add(image);
                        }
                    } catch (Exception e) {
                        sink.reportError("AI 读取图片失败："
                                + e.getMessage());
                    }
                }
                result.add(new AiMessage("user", text.toString(), images));
                continue;
            }

            if ("voice".equals(message.type)) {
                String sender = message.senderName == null
                        ? "成员" : message.senderName;
                result.add(new AiMessage("user", sender + "：[语音]"));
                continue;
            }

            if (message.content == null
                    || message.content.trim().isEmpty()) {
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
                prompt.append("以下消息触发了你的参与判断，请判断是否有必要参与；"
                        + "没有必要时输出 {\"send\":false,\"messages\":[]}：\n");
            }
            for (String message : mergedMessages) {
                prompt.append(message).append('\n');
            }
            result.add(new AiMessage("user", prompt.toString().trim()));
        }
        return result;
    }

    /**
     * 频繁上下线只保留上下文窗口内最后一条加入/离开消息。
     */
    private static int lastJoinLeaveIndex(List<Message> context, int start) {
        int last = -1;
        for (int i = start; i < context.size(); i++) {
            if (isJoinLeave(context.get(i))) {
                last = i;
            }
        }
        return last;
    }

    private static boolean isJoinLeave(Message message) {
        if (message == null || !"system".equals(message.type)
                || message.content == null) {
            return false;
        }
        String content = message.content;
        return content.contains("加入了群聊")
                || content.contains("离开了群聊");
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

    private static List<String> keywords(String aiName,
                                          String configuredKeywords,
                                          List<String> mentions) {
        List<String> result = new ArrayList<>();
        addKeyword(result, aiName);
        for (String keyword : splitMentions(configuredKeywords)) {
            addKeyword(result, keyword);
        }
        for (String mention : mentions) {
            String name = mention.startsWith("@")
                    ? mention.substring(1).trim()
                    : mention.trim();
            addKeyword(result, name);
        }
        return result;
    }

    private static void addKeyword(List<String> result, String value) {
        if (value != null && !value.trim().isEmpty()
                && !result.contains(value.trim())) {
            result.add(value.trim());
        }
    }

    private static boolean hit(int percent) {
        if (percent <= 0) {
            return false;
        }
        if (percent >= 100) {
            return true;
        }
        return ThreadLocalRandom.current().nextInt(100) < percent;
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

}
