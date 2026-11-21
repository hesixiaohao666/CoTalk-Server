package ai;

import model.Member;
import model.Message;

import java.util.List;

/**
 * 服务端 AI 群友接口。
 */
public interface AiResponder {

    String AI_USER_ID = "ai-90001";
    String AI_USERNAME = "AI";

    /**
     * AI 是否真正可用（开关开启且已配置 API Key）。
     */
    boolean isActive();

    void onMessage(Member sender, Message message, List<Message> recentContext);

    void onMemberJoined(Member member);

    /**
     * 用户发送表情后的旁路通知；默认不处理。
     */
    default void onEmojiMessage(Member sender, Message message) {
    }

    /**
     * 系统消息（加入/离开/提示等）写入后的通知；默认不触发回复。
     */
    default void onSystemMessage(Message message) {
    }

    /**
     * 用户拍一拍 AI 成员。AI Pro 视为一次 @AI 必回事件。
     */
    default void onPokeAi(Member sender, Member aiMember) {
    }

    void shutdown();
}
