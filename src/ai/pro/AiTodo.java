package ai.pro;

/**
 * AI 待办实体。
 */
public class AiTodo {
    public String todoId = "";
    public String userId = "";
    public String userTag = "";
    public String userName = "";
    public String content = "";
    public long remindAt;
    public String timezone = "";
    public String status = "pending";
    public int remindCount;
    public int retryCount;
    public long lastRemindAt;
    public long sourceSeq;
    public long createdAt;
    public long updatedAt;
}
