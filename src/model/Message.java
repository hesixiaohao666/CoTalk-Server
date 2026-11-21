package model;

/**
 * 服务端消息模型。
 */
public class Message {

    public String msgUuid;
    public long seq;
    public String senderId;
    public String senderName;
    public String senderUsername;
    public String senderTag;
    public int senderAvatarVersion;
    public String content;
    public String type = "text";
    public String fileId;
    public String fileName;
    public String fileMime;
    public long fileSize;
    public boolean fileExpired;
    public boolean recalled;
    public String recallNotice;
    public boolean senderAdmin;
    public String replyToMsgId;
    public long replyToSeq;
    public String replySenderName;
    public String replySenderUsername;
    public String replyPreview;
    public String replyType;
    public boolean replyRecalled;
    public long createdAt;
}
