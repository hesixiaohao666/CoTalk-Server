package ai;

import java.util.List;

/**
 * AI 未启用时的占位客户端：不发起任何网络请求。
 */
public class NoopDeepSeekClient implements DeepSeekClient {

    @Override
    public String chatCompletion(List<AiMessage> messages) {
        return null;
    }
}
