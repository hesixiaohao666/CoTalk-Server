package model;

/**
 * 聊天附件元数据。文件本体存放在 data/attachments/<fileId>。
 */
public class Attachment {

    public String fileId;
    public String messageUuid;
    public String fileName;
    public String mime;
    public long size;
    public long createdAt;
    public boolean expired;
}
