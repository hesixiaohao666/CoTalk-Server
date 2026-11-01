package ai;

/**
 * 未来 DeepSeek 请求消息：role = system/user/assistant。
 */
public class AiMessage {

    public final String role;
    public final String content;

    public AiMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
