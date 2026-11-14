package ai.pro;

/**
 * 服务端 AI 表情素材元数据。
 */
public class AiEmoji {
    public String emojiId = "";
    public String name = "";
    public String fileName = "";
    public String mime = "";
    public long size;
    public String path = "";
    public String tags = "[]";
    public boolean enabled = true;
    public String reviewStatus = "approved";
    public String source = "admin";
    public String sourceUserId = "";
    public String contentHash = "";
    public long createdAt;
    public long updatedAt;
}
