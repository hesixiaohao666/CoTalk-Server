package ai;

import model.Member;
import model.Message;

import java.util.List;

/**
 * 服务端 AI 群友接口。
 */
public interface AiResponder {

    String AI_USER_ID = "ai-90001";

    /**
     * AI 是否真正可用（开关开启且已配置 API Key）。
     */
    boolean isActive();

    void onMessage(Member sender, Message message, List<Message> recentContext);

    void onMemberJoined(Member member);

    void shutdown();
}
