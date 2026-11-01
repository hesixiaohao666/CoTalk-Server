package ai;

import model.Member;
import model.Message;

import java.util.List;

/**
 * AI 未启用时的空实现：不产生任何 AI 消息，也不阻塞聊天。
 */
public class NoopAiResponder implements AiResponder {

    @Override
    public boolean isActive() {
        return false;
    }

    @Override
    public void onMessage(Member sender,
                          Message message,
                          List<Message> recentContext) {
    }

    @Override
    public void onMemberJoined(Member member) {
    }

    @Override
    public void shutdown() {
    }
}
