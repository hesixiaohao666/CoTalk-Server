package ai;

import server.ServerConfig;

/**
 * AI 装配入口。本期始终返回 Noop；未来 ai.enabled=true 时在这里注入
 * DeepSeekAiResponder（内部使用 DeepSeekClient）。
 */
public final class AiResponderFactory {

    private AiResponderFactory() {
    }

    public static AiResponder create(ServerConfig config) {
        return new NoopAiResponder();
    }

    public static DeepSeekClient createDeepSeekClient(ServerConfig config) {
        return new NoopDeepSeekClient();
    }
}
