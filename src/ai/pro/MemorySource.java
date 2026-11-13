package ai.pro;

/**
 * 记忆来源与可信度映射。
 */
public enum MemorySource {
    ADMIN("admin", 1.0),
    USER_CONFIRMED("user_confirmed", 0.95),
    USER_STATEMENT("user_statement", 0.85),
    GROUP_MEMBER("group_member", 0.7),
    AI_INFERENCE("ai_inference", 0.5);

    public final String dbValue;
    public final double trust;

    MemorySource(String dbValue, double trust) {
        this.dbValue = dbValue;
        this.trust = trust;
    }

    public static MemorySource fromDb(String value) {
        if (value != null) {
            for (MemorySource source : values()) {
                if (source.dbValue.equalsIgnoreCase(value.trim())) {
                    return source;
                }
            }
        }
        return AI_INFERENCE;
    }
}
