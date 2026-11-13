package ai.pro;

import ai.AiImageResolver;
import ai.AiReplySink;
import ai.DeepSeekAiResponder;
import ai.DeepSeekClient;
import server.ServerConfig;

/**
 * AI群友Pro 回复引擎（阶段 B）：复用经典引擎的触发/防抖/JSON/视觉链路，
 * 仅替换系统提示词为核心记忆、代码红线与核心提示词组合。
 */
public class AiProResponder extends DeepSeekAiResponder {

    private final CoreMemoryStore coreMemoryStore;

    public AiProResponder(ServerConfig config,
                          DeepSeekClient client,
                          AiReplySink sink,
                          AiImageResolver imageResolver,
                          CoreMemoryStore coreMemoryStore) {
        super(config, client, sink, imageResolver);
        this.coreMemoryStore = coreMemoryStore;
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
}
