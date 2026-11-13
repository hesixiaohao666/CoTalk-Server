package ai.pro;

/**
 * AI Pro 记忆实体，字段与 ai_memory 表对应。
 */
public class Memory {
    public long id;
    public String memoryId = "";
    public MemoryLayer layer = MemoryLayer.CACHE;
    public String status = "active";
    public String category = "fact";
    public String content = "";
    public String contentHash = "";
    public String keywords = "[]";
    public String tags = "[]";
    public String subjectId = "";
    public String subjectName = "";
    public MemorySource source = MemorySource.AI_INFERENCE;
    public double sourceTrust = 0.5;
    public boolean explicitImportant;
    public int accessCount;
    public long lastAccessAt;
    public long createdAt;
    public long updatedAt;
    public long stableSince;
    public double taskRelevance;
    public double emotionalSalience;
    public double reconstructability;
    public double redundancy;
    public double importance;
    public double confidence = 0.6;
    public Long expiresAt;
    public String mergedInto = "";
    public String auditNote = "";
}
