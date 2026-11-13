package ai.pro;

import ai.AiImageResolver;
import ai.AiMessage;
import ai.AiReplySink;
import ai.DeepSeekAiResponder;
import ai.DeepSeekClient;
import model.Message;
import server.AttachmentService;
import server.MessageService;
import server.ServerConfig;
import store.Database;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI群友Pro 回复引擎（阶段 C/D）：核心记忆 + 长期记忆检索 + 记忆轮次管线。
 */
public class AiProResponder extends DeepSeekAiResponder {

    private final CoreMemoryStore coreMemoryStore;
    private final MemoryStore memoryStore;
    private final MemoryIndexManager indexManager;
    private final MemoryRetriever retriever;
    private final MemoryPipeline pipeline;
    private final EmotionEngine emotionEngine;
    private final AiEmojiService emojiService;
    private final AiTodoService todoService;
    private static final Pattern EMOJI_TOKEN = Pattern.compile(
            "\\[\\[emoji:([A-Za-z0-9_-]+)]]");

    public AiProResponder(ServerConfig config,
                          DeepSeekClient client,
                          AiReplySink sink,
                          AiImageResolver imageResolver,
                          CoreMemoryStore coreMemoryStore) {
        this(config, client, sink, imageResolver, coreMemoryStore,
                null, null);
    }

    public AiProResponder(ServerConfig config,
                          DeepSeekClient client,
                          AiReplySink sink,
                          AiImageResolver imageResolver,
                          CoreMemoryStore coreMemoryStore,
                          Database database,
                          MessageService messageService) {
        this(config, client, sink, imageResolver, coreMemoryStore,
                database, messageService, null);
    }

    public AiProResponder(ServerConfig config,
                          DeepSeekClient client,
                          AiReplySink sink,
                          AiImageResolver imageResolver,
                          CoreMemoryStore coreMemoryStore,
                          Database database,
                          MessageService messageService,
                          AttachmentService attachmentService) {
        super(config, client, sink, imageResolver);
        this.coreMemoryStore = coreMemoryStore;
        if (database != null && messageService != null) {
            this.memoryStore = new MemoryStore(database);
            this.indexManager = new MemoryIndexManager(memoryStore);
            this.retriever = new MemoryRetriever();
            this.emotionEngine = new EmotionEngine(
                    config.aiPro, new EmotionStore(database), sink);
            this.emotionEngine.start();
            AiTodoService todos = null;
            try {
                todos = new AiTodoService(
                        new AiTodoStore(database), config.aiPro, sink);
                todos.start();
            } catch (Exception e) {
                sink.reportError("AI Pro 待办初始化失败：" + e.getMessage());
            }
            this.todoService = todos;
            AiEmojiService emojis = null;
            if (attachmentService != null) {
                try {
                    AiEmojiStore emojiStore = new AiEmojiStore(
                            database, Paths.get("data", "ai-pro", "emoji"));
                    emojis = new AiEmojiService(
                            emojiStore, attachmentService, config.aiPro, sink);
                } catch (Exception e) {
                    sink.reportError("AI Pro 表情库初始化失败：" + e.getMessage());
                }
            }
            this.emojiService = emojis;
            this.pipeline = new MemoryPipeline(
                    database, messageService, client,
                    config.aiPro, sink, memoryStore, indexManager,
                    emotionEngine, todoService);
            this.pipeline.start();
        } else {
            this.memoryStore = null;
            this.indexManager = null;
            this.retriever = null;
            this.emotionEngine = null;
            this.todoService = null;
            this.emojiService = null;
            this.pipeline = null;
        }
    }

    @Override
    protected String buildSystemPrompt() {
        CoreMemory memory;
        try {
            memory = coreMemoryStore.load();
        } catch (Exception e) {
            sink.reportError("AI Pro 读取核心记忆失败：" + e.getMessage());
            memory = AiProPrompts.defaultCore(
                    config.aiName,
                    config.aiPromptCustom,
                    config.aiPro.ownerNames());
        }
        String emotion = emotionEngine == null
                ? "" : emotionEngine.promptSection();
        String mood = emotionEngine == null
                ? "" : emotionEngine.getState().mood;
        String emoji = emojiService == null
                ? "" : emojiService.promptSection(mood);
        String extra = emotion;
        if (!emoji.isEmpty()) {
            extra = extra.isEmpty() ? emoji : extra + "\n\n" + emoji;
        }
        return AiProPrompts.buildSystemPrompt(
                memory,
                config.aiMessageMaxChars,
                config.aiMaxMessages,
                extra);
    }

    @Override
    protected int contextMessageLimit() {
        return Math.max(1, config.aiPro.getInt(
                "ai.pro.prompt.contextMessages"));
    }

    @Override
    public void onMessage(model.Member sender,
                          Message message,
                          List<Message> recentContext) {
        if (emotionEngine != null) {
            emotionEngine.onMessage(sender, message,
                    isDirectMention(message.content));
        }
        if (pipeline != null) {
            pipeline.onMessage(message);
        }
        super.onMessage(sender, message, recentContext);
    }

    @Override
    protected List<AiMessage> buildExtraUserMessages(
            List<String> mergedMessages,
            List<Message> context) {
        if (retriever == null || indexManager == null
                || memoryStore == null) {
            return Collections.emptyList();
        }
        String query = buildQuery(mergedMessages, context);
        if (query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> tags = queryTags(query);
        long now = System.currentTimeMillis();
        List<MemoryRetriever.Retrieved> retrieved = retriever.retrieve(
                query, tags, config.aiPro, indexManager.get(), now);
        if (retrieved.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> ids = new HashSet<>();
        for (MemoryRetriever.Retrieved item : retrieved) {
            ids.add(item.memory.memoryId);
        }
        try {
            memoryStore.recordHits(ids, now, config.aiPro);
        } catch (Exception e) {
            sink.reportError("AI Pro 更新记忆命中失败：" + e.getMessage());
        }
        String injection = MemoryRetriever.formatInjection(retrieved);
        if (injection.isEmpty()) {
            return Collections.emptyList();
        }
        List<AiMessage> result = new ArrayList<>();
        result.add(new AiMessage("user", injection));
        return result;
    }

    private String buildQuery(List<String> mergedMessages,
                              List<Message> context) {
        StringBuilder builder = new StringBuilder();
        if (mergedMessages != null) {
            for (String message : mergedMessages) {
                builder.append(message).append('\n');
            }
        }
        int limit = Math.max(1, config.aiPro.getInt(
                "ai.pro.retrieve.queryContextMessages"));
        if (context != null) {
            int start = Math.max(0, context.size() - limit);
            for (int i = start; i < context.size(); i++) {
                Message message = context.get(i);
                if (message == null || "system".equals(message.type)) {
                    continue;
                }
                String sender = message.senderName == null
                        ? "成员" : message.senderName;
                String content = message.content == null
                        ? "" : message.content;
                if ("image".equals(message.type)) {
                    content = "[图片] " + content;
                } else if ("emoji".equals(message.type)) {
                    content = "[表情] " + content;
                } else if ("voice".equals(message.type)) {
                    content = "[语音]";
                }
                builder.append(sender).append("：").append(content).append('\n');
            }
        }
        return builder.toString();
    }

    private Set<String> queryTags(String query) {
        Set<String> tags = new HashSet<>();
        String lower = query.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && !keyword.trim().isEmpty()
                    && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                tags.add(MemoryText.normalize(keyword)
                        .toLowerCase(Locale.ROOT));
            }
        }
        return tags;
    }

    @Override
    protected void sendReplyMessage(String content) throws Exception {
        if (emojiService != null && content != null) {
            Matcher matcher = EMOJI_TOKEN.matcher(content);
            if (matcher.find()) {
                String emojiId = matcher.group(1);
                if (emojiService.send(emojiId, null)) {
                    return;
                }
                String remaining = matcher.replaceFirst("").trim();
                if (!remaining.isEmpty()) {
                    super.sendReplyMessage(remaining);
                }
                return;
            }
        }
        super.sendReplyMessage(content);
    }

    public String getEmotionStatus() {
        return emotionEngine == null
                ? "未启用" : emotionEngine.statusText();
    }

    @Override
    public void shutdown() {
        if (pipeline != null) {
            pipeline.shutdown();
        }
        if (todoService != null) {
            todoService.shutdown();
        }
        super.shutdown();
    }
}
