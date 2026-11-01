package ai;

import java.util.List;

/**
 * 本期占位：不发起任何网络请求。
 */
public class NoopDeepSeekClient implements DeepSeekClient {

    @Override
    public String chatCompletion(List<AiMessage> messages) {
        return null;
    }
}
