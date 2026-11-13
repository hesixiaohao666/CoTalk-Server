package ai.pro;

/**
 * 记忆重要度、新鲜度与淘汰优先级。
 */
public final class MemoryScoring {

    private static final double DAY_MS = 86_400_000.0;

    private MemoryScoring() {
    }

    public static double importance(Memory memory, AiProConfig config, long now) {
        double access = accessScore(memory.accessCount, config);
        double freshness = freshness(memory.updatedAt, now);
        double value = config.getDouble("ai.pro.score.w.explicit")
                * (memory.explicitImportant ? 1.0 : 0.0)
                + config.getDouble("ai.pro.score.w.access") * access
                + config.getDouble("ai.pro.score.w.trust") * memory.sourceTrust
                + config.getDouble("ai.pro.score.w.freshness") * freshness
                + config.getDouble("ai.pro.score.w.task") * memory.taskRelevance
                + config.getDouble("ai.pro.score.w.emotion")
                * memory.emotionalSalience;
        return round4(clamp(value));
    }

    public static double accessScore(int accessCount, AiProConfig config) {
        int max = Math.max(1, config.getInt("ai.pro.memory.maxAccessCount"));
        int count = Math.max(0, Math.min(accessCount, max));
        return Math.min(1.0,
                Math.log1p(count) / Math.log(21.0));
    }

    public static double freshness(long updatedAt, long now) {
        double idleDays = Math.max(0, now - updatedAt) / DAY_MS;
        return Math.exp(-idleDays / 90.0);
    }

    public static double recency(long lastAccessAt, long now) {
        double idleDays = Math.max(0, now - lastAccessAt) / DAY_MS;
        return Math.exp(-idleDays / 90.0);
    }

    public static double evictionPriority(Memory memory,
                                          AiProConfig config,
                                          long now) {
        double idleDays = Math.max(0, now - memory.lastAccessAt) / DAY_MS;
        double unaccessed = 1.0 - Math.exp(
                -idleDays / Math.max(1, config.getInt(
                        "ai.pro.evict.unaccessedDays")));
        double value = config.getDouble("ai.pro.evict.w.importance")
                * (1.0 - memory.importance)
                + config.getDouble("ai.pro.evict.w.unaccessed") * unaccessed
                + config.getDouble("ai.pro.evict.w.trust")
                * (1.0 - memory.sourceTrust)
                + config.getDouble("ai.pro.evict.w.reconstruct")
                * memory.reconstructability
                + config.getDouble("ai.pro.evict.w.redundancy")
                * memory.redundancy;
        return round4(clamp(value));
    }

    public static double redundancy(double similarity) {
        if (similarity > 0.85) {
            return 1.0;
        }
        if (similarity >= 0.6) {
            return 0.5;
        }
        return 0.0;
    }

    public static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    public static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
