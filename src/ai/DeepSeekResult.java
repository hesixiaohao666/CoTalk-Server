package ai;

/**
 * 一次聊天补全的响应元信息。completion 内容为空时仍保留 finish_reason 等信息。
 */
public final class DeepSeekResult {

    public final String content;
    public final String finishReason;
    public final String requestId;
    public final boolean reasoningOnly;
    public final int maxTokens;
    public final boolean thinking;

    public DeepSeekResult(String content,
                          String finishReason,
                          String requestId,
                          boolean reasoningOnly,
                          int maxTokens,
                          boolean thinking) {
        this.content = content == null ? "" : content;
        this.finishReason = finishReason == null ? "" : finishReason;
        this.requestId = requestId == null ? "" : requestId;
        this.reasoningOnly = reasoningOnly;
        this.maxTokens = maxTokens;
        this.thinking = thinking;
    }

    public boolean isEmptyContent() {
        return content.trim().isEmpty();
    }

    public int contentLength() {
        return content.length();
    }

    public static DeepSeekResult from(String content) {
        return new DeepSeekResult(content, "", "", false, 0, false);
    }
}
