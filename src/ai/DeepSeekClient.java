package ai;

import java.util.List;

/**
 * DeepSeek 调用抽象。本期不实现 HTTP；未来由 JDK HttpClient + Gson 实现。
 */
public interface DeepSeekClient {

    String chatCompletion(List<AiMessage> messages) throws Exception;

    /**
     * 指定 max_tokens 的调用；默认实现忽略该参数，便于测试桩复用。
     */
    default String chatCompletion(List<AiMessage> messages, int maxTokens)
            throws Exception {
        return chatCompletion(messages);
    }

    /**
     * 指定 max_tokens 与 thinking 的调用；抽取任务强制关闭 thinking。
     */
    default String chatCompletion(List<AiMessage> messages,
                                  int maxTokens,
                                  boolean thinking) throws Exception {
        return chatCompletion(messages, maxTokens);
    }

    /**
     * 带 finish_reason/requestId 等元信息的调用；旧实现通过 content 自动包装。
     */
    default DeepSeekResult chatCompletionResult(List<AiMessage> messages)
            throws Exception {
        return DeepSeekResult.from(chatCompletion(messages));
    }

    default DeepSeekResult chatCompletionResult(List<AiMessage> messages,
                                                int maxTokens)
            throws Exception {
        return DeepSeekResult.from(chatCompletion(messages, maxTokens));
    }

    default DeepSeekResult chatCompletionResult(List<AiMessage> messages,
                                                int maxTokens,
                                                boolean thinking)
            throws Exception {
        return DeepSeekResult.from(
                chatCompletion(messages, maxTokens, thinking));
    }
}
