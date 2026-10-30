package model;

/**
 * 消息模型。Step 3 先建骨架，Step 4 使用。
 */
public class Message {

    public String msgUuid;
    public long seq;
    public String senderId;
    public String senderName;
    public String senderTag;
    public int senderAvatarVersion;
    public String content;
    public String type = "text";
    public long createdAt;
}
