package ai;

/**
 * AI 统一回复动作：文字或表情。联网搜索不在动作流内，仍由服务端预判。
 */
public final class AiReplyAction {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_EMOJI = "emoji";

    public final String type;
    public final String content;
    public final String emojiId;

    private AiReplyAction(String type, String content, String emojiId) {
        this.type = type;
        this.content = content;
        this.emojiId = emojiId;
    }

    public static AiReplyAction text(String content) {
        return new AiReplyAction(TYPE_TEXT,
                content == null ? "" : content, "");
    }

    public static AiReplyAction emoji(String emojiId) {
        return new AiReplyAction(TYPE_EMOJI, "",
                emojiId == null ? "" : emojiId);
    }

    public boolean isText() {
        return TYPE_TEXT.equals(type);
    }

    public boolean isEmoji() {
        return TYPE_EMOJI.equals(type);
    }
}
