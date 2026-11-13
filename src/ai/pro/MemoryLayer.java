package ai.pro;

/**
 * 记忆层级。
 */
public enum MemoryLayer {
    DEEP("deep"),
    SHALLOW("shallow"),
    CACHE("cache");

    public final String dbValue;

    MemoryLayer(String dbValue) {
        this.dbValue = dbValue;
    }

    public static MemoryLayer fromDb(String value) {
        if (value != null) {
            for (MemoryLayer layer : values()) {
                if (layer.dbValue.equalsIgnoreCase(value.trim())) {
                    return layer;
                }
            }
        }
        return CACHE;
    }
}
