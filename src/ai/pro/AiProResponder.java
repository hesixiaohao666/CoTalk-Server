package ai.pro;

import ai.AiImageResolver;
import ai.AiMessage;
import ai.AiReplySink;
import ai.DeepSeekAiResponder;
import ai.DeepSeekClient;
import model.Message;
import server.MessageService;
import server.ServerConfig;
import store.Database;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AI群友Pro 回复引擎（阶段 C/D）：核心记忆 + 长期记忆检索 + 记忆轮次管线。
 */
public class AiProResponder extends DeepSeekAiResponder {

    private final CoreMemoryStore coreMemoryStore;
    private final MemoryStore memoryStore;
    private final MemoryIndexManager indexManager;
    private final MemoryRetriever retriever;
    private final MemoryPipeline pipeline;

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
        super(config, client, sink, imageResolver);
        this.coreMemoryStore = coreMemoryStore;
        if (database != null && messageService != null) {
            this.memoryStore = new MemoryStore(database);
            this.indexManager = new MemoryIndexManager(memoryStore);
            this.retriever = new MemoryRetriever();
            this.pipeline = new MemoryPipeline(
                    database, messageService, client,
                    config.aiPro, sink, memoryStore, indexManager);
            this.pipeline.start();
        } else {
            this.memoryStore = null;
            this.indexManager = null;
            this.retriever = null;
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
        return AiProPrompts.buildSystemPrompt(
                memory,
                config.aiMessageMaxChars,
                config.aiMaxMessages);
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
    public void shutdown() {
        if (pipeline != null) {
            pipeline.shutdown();
        }
        super.shutdown();
    }
}
