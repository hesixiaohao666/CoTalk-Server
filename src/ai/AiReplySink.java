package ai;

/**
 * AI 回复的发送出口。由服务端实现，负责落库、生成 seq 并广播。
 */
public interface AiReplySink {

    void appendAiMessage(String content) throws Exception;

    /**
     * 用户可见的失败提示（持久化 system 消息）。
     */
    void reportFailure(String message);

    /**
     * 仅写服务端日志的失败详情。
     */
    void reportError(String detail);
}
