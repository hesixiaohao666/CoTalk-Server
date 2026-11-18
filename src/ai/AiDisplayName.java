package ai;

import model.Member;
import model.Message;

/**
 * AI 上下文中的人员显示：昵称（@用户名）。
 * 昵称仍是主要称呼，用户名仅作为稳定身份辅助字段。
 */
public final class AiDisplayName {

    private AiDisplayName() {
    }

    public static String of(Member member) {
        if (member == null) {
            return "成员";
        }
        return format(member.nickname, member.username);
    }

    public static String of(Message message) {
        if (message == null) {
            return "成员";
        }
        return format(message.senderName, message.senderUsername);
    }

    public static String format(String nickname, String username) {
        String name = nickname == null || nickname.trim().isEmpty()
                ? "成员" : nickname.trim();
        String account = username == null ? "" : username.trim();
        if (account.isEmpty()) {
            return name;
        }
        return name + "（@" + account + "）";
    }
}
