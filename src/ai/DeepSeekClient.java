package ai;

import java.util.List;

/**
 * DeepSeek 调用抽象。本期不实现 HTTP；未来由 JDK HttpClient + Gson 实现。
 */
public interface DeepSeekClient {

    String chatCompletion(List<AiMessage> messages) throws Exception;
}
