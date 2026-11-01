package ai;

import model.Member;
import model.Message;

import java.util.List;

/**
 * 服务端 AI 群友接口。本期使用 NoopAiResponder。
 * 未来实现：3 秒防抖/冷却 + 选择性回复 + 合并窗口内消息。
 */
public interface AiResponder {

    void onMessage(Member sender, Message message, List<Message> recentContext);

    void onMemberJoined(Member member);

    void shutdown();
}
