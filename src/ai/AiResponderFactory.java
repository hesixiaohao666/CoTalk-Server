package ai;

import ai.pro.AiProResponder;
import ai.pro.CoreMemoryStore;
import server.ServerConfig;

/**
 * AI 装配入口：开关开启且配置 API Key 时使用 DeepSeek，否则使用空实现。
 */
public final class AiResponderFactory {

    private AiResponderFactory() {
    }

    public static boolean isConfigured(ServerConfig config) {
        return config != null
                && config.aiEnabled
                && config.aiApiKey != null
                && !config.aiApiKey.trim().isEmpty()
                && config.aiModel != null
                && !config.aiModel.trim().isEmpty()
                && config.aiBaseUrl != null
                && !config.aiBaseUrl.trim().isEmpty();
    }

    public static AiResponder create(ServerConfig config, AiReplySink sink) {
        return create(config, sink, null);
    }

    public static AiResponder create(ServerConfig config,
                                     AiReplySink sink,
                                     AiImageResolver imageResolver) {
        if (!isConfigured(config) || sink == null) {
            return new NoopAiResponder();
        }
        return new DeepSeekAiResponder(
                config, createDeepSeekClient(config), sink, imageResolver);
    }

    /**
     * AI Pro 引擎。调用方需先完成 CoreMemoryStore.initialize。
     */
    public static AiResponder createPro(ServerConfig config,
                                        AiReplySink sink,
                                        AiImageResolver imageResolver,
                                        CoreMemoryStore coreMemoryStore) {
        if (!isConfigured(config) || sink == null) {
            return new NoopAiResponder();
        }
        if (coreMemoryStore == null) {
            throw new IllegalArgumentException("AI Pro 核心记忆未初始化");
        }
        return new AiProResponder(
                config, createDeepSeekClient(config), sink,
                imageResolver, coreMemoryStore);
    }

    public static DeepSeekClient createDeepSeekClient(ServerConfig config) {
        if (config == null || config.aiApiKey == null
                || config.aiApiKey.trim().isEmpty()) {
            return new NoopDeepSeekClient();
        }
        return new DeepSeekHttpClient(config);
    }
}
