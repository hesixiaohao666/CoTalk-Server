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

    /**
     * AI 自身消息识别：优先 senderUsername，旧数据回退到固定 senderId。
     */
    public static boolean isAi(Message message) {
        if (message == null) {
            return false;
        }
        if (AiResponder.AI_USER_ID.equals(message.senderId)) {
            return true;
        }
        return message.senderUsername != null
                && AiResponder.AI_USERNAME.equalsIgnoreCase(
                message.senderUsername.trim());
    }

    /**
     * 引用快照在 AI 上下文中的标记：[引用 发送者：内容]。
     * 引用是旧消息，当前消息正文才是用户此刻表达的内容。
     */
    public static String replyTag(Message message) {
        if (message == null || message.replyToMsgId == null
                || message.replyToMsgId.trim().isEmpty()) {
            return "";
        }
        String name = message.replySenderName == null
                || message.replySenderName.trim().isEmpty()
                ? "成员" : message.replySenderName.trim();
        String preview;
        if (message.replyRecalled) {
            preview = "原消息已撤回";
        } else {
            String type = message.replyType == null
                    ? "text" : message.replyType;
            if ("image".equals(type)) {
                preview = "[图片]";
            } else if ("emoji".equals(type)) {
                preview = "[表情]";
            } else if ("voice".equals(type)) {
                preview = "[语音]";
            } else {
                preview = message.replyPreview == null
                        ? "" : message.replyPreview.trim();
            }
        }
        if (preview.isEmpty()) {
            preview = "消息";
        }
        return "[引用 " + name + "：" + preview + "] ";
    }
}
