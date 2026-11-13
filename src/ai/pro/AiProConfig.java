package ai.pro;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * AI Pro 配置值对象。所有 ai.pro.* 键在此统一补齐、读取与校验。
 */
public final class AiProConfig {

    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

    static {
        put("ai.pro.enabled", "false");
        put("ai.pro.model", "");
        put("ai.pro.maxTokens", "0");
        put("ai.pro.timeoutMs", "0");
        put("ai.pro.prompt.contextMessages", "10");
        put("ai.pro.prompt.maxChars", "6000");
        put("ai.pro.prompt.maxMemoryChars", "1200");
        put("ai.pro.owner.names", "");
        put("ai.pro.migrate.importHistory", "false");
        put("ai.pro.migrate.backup", "true");

        put("ai.pro.round.intervalMs", "1200000");
        put("ai.pro.round.messageCount", "10");
        put("ai.pro.round.maxMessages", "200");
        put("ai.pro.round.minMessages", "1");
        put("ai.pro.round.pendingMax", "1");

        put("ai.pro.deep.max", "200");
        put("ai.pro.shallow.max", "1000");
        put("ai.pro.cache.max", "5000");
        put("ai.pro.cache.cleanupEveryRounds", "5");
        put("ai.pro.cache.cleanupTargetRatio", "0.8");
        put("ai.pro.cache.ttlDays", "90");
        put("ai.pro.cache.ttlMinDays", "30");
        put("ai.pro.compress.keepRatio", "0.5");
        put("ai.pro.layer.cooldownMs", "1800000");
        put("ai.pro.layer.maxMovesPerRound", "100");
        put("ai.pro.memory.maxAccessCount", "10000");

        put("ai.pro.extract.maxPerRound", "3");
        put("ai.pro.extract.maxTokens", "800");
        put("ai.pro.extract.maxChars", "60");
        put("ai.pro.extract.minChars", "4");
        put("ai.pro.extract.minConfidence", "0.5");
        put("ai.pro.extract.maxKeywords", "8");
        put("ai.pro.extract.maxTags", "8");
        put("ai.pro.review.enabled", "true");
        put("ai.pro.review.max", "10");
        put("ai.pro.merge.similarity", "0.85");
        put("ai.pro.merge.maxKeywords", "16");
        put("ai.pro.merge.maxTags", "16");

        put("ai.pro.retrieve.deep", "5");
        put("ai.pro.retrieve.shallow", "5");
        put("ai.pro.retrieve.cache", "3");
        put("ai.pro.retrieve.maxTotal", "12");
        put("ai.pro.retrieve.maxChars", "1200");
        put("ai.pro.retrieve.queryContextMessages", "3");
        put("ai.pro.retrieve.minTokenScore", "0.05");
        put("ai.pro.retrieve.dedupeSimilarity", "0.85");
        put("ai.pro.retrieve.w.token", "0.55");
        put("ai.pro.retrieve.w.importance", "0.25");
        put("ai.pro.retrieve.w.tag", "0.12");
        put("ai.pro.retrieve.w.recency", "0.08");

        put("ai.pro.cacheToShallow.importance", "0.65");
        put("ai.pro.cacheToShallow.hits", "2");
        put("ai.pro.shallowToDeep.importance", "0.75");
        put("ai.pro.shallowToDeep.stableDays", "7");
        put("ai.pro.shallowToDeep.hits", "3");
        put("ai.pro.deepToShallow.importance", "0.45");
        put("ai.pro.deepToShallow.idleDays", "30");
        put("ai.pro.score.w.explicit", "0.30");
        put("ai.pro.score.w.access", "0.20");
        put("ai.pro.score.w.trust", "0.20");
        put("ai.pro.score.w.freshness", "0.15");
        put("ai.pro.score.w.task", "0.10");
        put("ai.pro.score.w.emotion", "0.05");
        put("ai.pro.evict.w.importance", "0.35");
        put("ai.pro.evict.w.unaccessed", "0.25");
        put("ai.pro.evict.w.trust", "0.15");
        put("ai.pro.evict.w.reconstruct", "0.15");
        put("ai.pro.evict.w.redundancy", "0.10");
        put("ai.pro.evict.unaccessedDays", "60");

        put("ai.pro.emotion.enabled", "true");
        put("ai.pro.emotion.decayRounds", "3");
        put("ai.pro.emotion.halfLifeMinutes", "30");
        put("ai.pro.emotion.aiWeight", "0.5");
        put("ai.pro.emotion.memoryBaselineWeight", "0.2");
        put("ai.pro.emotion.promptMaxIntensity", "0.6");
        put("ai.pro.emotion.lexicon.positive",
                "开心,高兴,喜欢,谢谢,太好了,哈哈");
        put("ai.pro.emotion.lexicon.negative",
                "难过,生气,讨厌,烦,伤心,失望");
        put("ai.pro.emotion.lexicon.surprise",
                "惊讶,居然,没想到,突然");
        put("ai.pro.emotion.lexicon.intimate",
                "宝贝,亲爱的,想你,抱抱");

        put("ai.pro.emoji.enabled", "true");
        put("ai.pro.emoji.autoImport", "false");
        put("ai.pro.emoji.cooldownRounds", "5");
        put("ai.pro.emoji.topCandidates", "3");
        put("ai.pro.emoji.candidatePool", "50");
        put("ai.pro.emoji.maxBytes", "524288");
        put("ai.pro.emoji.maxGifBytes", "2097152");
        put("ai.pro.emoji.sensitiveTags", "政治,色情,暴力,广告,隐私");

        put("ai.pro.todo.enabled", "true");
        put("ai.pro.todo.maxReminders", "2");
        put("ai.pro.todo.maxRetries", "1");
        put("ai.pro.todo.maxCreatePerRound", "2");
        put("ai.pro.todo.maxActionsPerRound", "5");
        put("ai.pro.todo.maxRemindersPerRound", "5");
        put("ai.pro.todo.maxPendingPerUser", "10");
        put("ai.pro.todo.maxChars", "100");
        put("ai.pro.todo.timezone", ZoneId.systemDefault().getId());
        put("ai.pro.todo.confirmDraft", "true");
        put("ai.pro.todo.draftTtlMinutes", "10");
        put("ai.pro.todo.missWindowHours", "24");
        put("ai.pro.todo.includeContentInGroup", "true");
        put("ai.pro.todo.triggers", "提醒我,记得提醒,别忘了,帮我记");

        put("ai.pro.privacy.mode", "drop");
        put("ai.pro.privacy.patterns", "");
        put("ai.pro.privacy.allowPhone", "false");
    }

    private final Properties values = new Properties();

    public AiProConfig(Properties source) {
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            String key = entry.getKey();
            String value = source == null ? null : source.getProperty(key);
            if (value == null || value.trim().isEmpty()) {
                value = entry.getValue();
            } else {
                value = value.trim();
            }
            values.setProperty(key, value);
        }
    }

    public static Properties defaultProperties() {
        Properties properties = new Properties();
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            properties.setProperty(entry.getKey(), entry.getValue());
        }
        return properties;
    }

    public static List<String> keys() {
        return new ArrayList<>(DEFAULTS.keySet());
    }

    public static List<String> missingKeys(Properties source) {
        List<String> missing = new ArrayList<>();
        if (source == null) {
            return keys();
        }
        for (String key : DEFAULTS.keySet()) {
            if (!source.containsKey(key)) {
                missing.add(key);
            }
        }
        return missing;
    }

    public boolean enabled() {
        return getBoolean("ai.pro.enabled");
    }

    public String model() {
        return getString("ai.pro.model");
    }

    public String ownerNames() {
        return getString("ai.pro.owner.names");
    }

    public String getString(String key) {
        return values.getProperty(key, DEFAULTS.getOrDefault(key, ""));
    }

    public int getInt(String key) {
        try {
            return Integer.parseInt(getString(key).trim());
        } catch (NumberFormatException e) {
            return Integer.parseInt(DEFAULTS.getOrDefault(key, "0"));
        }
    }

    public double getDouble(String key) {
        try {
            return Double.parseDouble(getString(key).trim());
        } catch (NumberFormatException e) {
            return Double.parseDouble(DEFAULTS.getOrDefault(key, "0"));
        }
    }

    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(getString(key));
    }

    public Properties toProperties() {
        Properties result = new Properties();
        result.putAll(values);
        return result;
    }

    public void validate() {
        requireInt("ai.pro.maxTokens", 0, Integer.MAX_VALUE);
        requireInt("ai.pro.timeoutMs", 0, Integer.MAX_VALUE);
        requireInt("ai.pro.prompt.contextMessages", 1, 200);
        requireInt("ai.pro.prompt.maxChars", 500, 100000);
        requireInt("ai.pro.prompt.maxMemoryChars", 0, 100000);

        requireInt("ai.pro.round.intervalMs", 1000, Integer.MAX_VALUE);
        requireInt("ai.pro.round.messageCount", 1, 100000);
        requireInt("ai.pro.round.maxMessages", 1, 10000);
        requireInt("ai.pro.round.minMessages", 1, 10000);
        requireInt("ai.pro.round.pendingMax", 0, 100);

        requireInt("ai.pro.deep.max", 1, 100000);
        requireInt("ai.pro.shallow.max", 1, 1000000);
        requireInt("ai.pro.cache.max", 1, 10000000);
        requireInt("ai.pro.cache.cleanupEveryRounds", 1, 10000);
        requireDouble("ai.pro.cache.cleanupTargetRatio", 0.1, 1.0);
        requireInt("ai.pro.cache.ttlDays", 1, 36500);
        requireInt("ai.pro.cache.ttlMinDays", 1, 36500);
        requireDouble("ai.pro.compress.keepRatio", 0.1, 1.0);
        requireInt("ai.pro.layer.cooldownMs", 0, Integer.MAX_VALUE);
        requireInt("ai.pro.layer.maxMovesPerRound", 0, 100000);
        requireInt("ai.pro.memory.maxAccessCount", 1, Integer.MAX_VALUE);

        requireInt("ai.pro.extract.maxPerRound", 0, 100);
        requireInt("ai.pro.extract.maxTokens", 1, Integer.MAX_VALUE);
        requireInt("ai.pro.extract.maxChars", 1, 10000);
        requireInt("ai.pro.extract.minChars", 1, 10000);
        requireDouble("ai.pro.extract.minConfidence", 0.0, 1.0);
        requireInt("ai.pro.extract.maxKeywords", 0, 100);
        requireInt("ai.pro.extract.maxTags", 0, 100);
        requireInt("ai.pro.review.max", 0, 1000);
        requireDouble("ai.pro.merge.similarity", 0.0, 1.0);
        requireInt("ai.pro.merge.maxKeywords", 0, 1000);
        requireInt("ai.pro.merge.maxTags", 0, 1000);

        requireInt("ai.pro.retrieve.deep", 0, 1000);
        requireInt("ai.pro.retrieve.shallow", 0, 1000);
        requireInt("ai.pro.retrieve.cache", 0, 1000);
        requireInt("ai.pro.retrieve.maxTotal", 1, 1000);
        requireInt("ai.pro.retrieve.maxChars", 0, 100000);
        requireInt("ai.pro.retrieve.queryContextMessages", 1, 100);
        requireDouble("ai.pro.retrieve.minTokenScore", 0.0, 1.0);
        requireDouble("ai.pro.retrieve.dedupeSimilarity", 0.0, 1.0);
        requireWeightSum(1.0,
                "ai.pro.retrieve.w.token",
                "ai.pro.retrieve.w.importance",
                "ai.pro.retrieve.w.tag",
                "ai.pro.retrieve.w.recency");

        requireDouble("ai.pro.cacheToShallow.importance", 0.0, 1.0);
        requireInt("ai.pro.cacheToShallow.hits", 0, 100000);
        requireDouble("ai.pro.shallowToDeep.importance", 0.0, 1.0);
        requireInt("ai.pro.shallowToDeep.stableDays", 0, 36500);
        requireInt("ai.pro.shallowToDeep.hits", 0, 100000);
        requireDouble("ai.pro.deepToShallow.importance", 0.0, 1.0);
        requireInt("ai.pro.deepToShallow.idleDays", 0, 36500);
        requireWeightSum(1.0,
                "ai.pro.score.w.explicit",
                "ai.pro.score.w.access",
                "ai.pro.score.w.trust",
                "ai.pro.score.w.freshness",
                "ai.pro.score.w.task",
                "ai.pro.score.w.emotion");
        requireWeightSum(1.0,
                "ai.pro.evict.w.importance",
                "ai.pro.evict.w.unaccessed",
                "ai.pro.evict.w.trust",
                "ai.pro.evict.w.reconstruct",
                "ai.pro.evict.w.redundancy");
        requireInt("ai.pro.evict.unaccessedDays", 0, 36500);

        requireInt("ai.pro.emotion.decayRounds", 1, 100);
        requireInt("ai.pro.emotion.halfLifeMinutes", 1, 525600);
        requireDouble("ai.pro.emotion.aiWeight", 0.0, 1.0);
        requireDouble("ai.pro.emotion.memoryBaselineWeight", 0.0, 1.0);
        requireDouble("ai.pro.emotion.promptMaxIntensity", 0.0, 1.0);

        requireInt("ai.pro.emoji.cooldownRounds", 0, 10000);
        requireInt("ai.pro.emoji.topCandidates", 1, 50);
        requireInt("ai.pro.emoji.candidatePool", 1, 10000);
        requireInt("ai.pro.emoji.maxBytes", 1, 50 * 1024 * 1024);
        requireInt("ai.pro.emoji.maxGifBytes", 1, 100 * 1024 * 1024);

        requireInt("ai.pro.todo.maxReminders", 1, 20);
        requireInt("ai.pro.todo.maxRetries", 0, 5);
        requireInt("ai.pro.todo.maxCreatePerRound", 0, 100);
        requireInt("ai.pro.todo.maxActionsPerRound", 0, 100);
        requireInt("ai.pro.todo.maxRemindersPerRound", 0, 100);
        requireInt("ai.pro.todo.maxPendingPerUser", 1, 1000);
        requireInt("ai.pro.todo.maxChars", 1, 1000);
        requireInt("ai.pro.todo.draftTtlMinutes", 1, 1440);
        requireInt("ai.pro.todo.missWindowHours", 0, 720);
        try {
            ZoneId.of(getString("ai.pro.todo.timezone").trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("ai.pro.todo.timezone 时区无效");
        }

        String privacyMode = getString("ai.pro.privacy.mode").trim();
        if (!"drop".equalsIgnoreCase(privacyMode)
                && !"redact".equalsIgnoreCase(privacyMode)) {
            throw new IllegalArgumentException(
                    "ai.pro.privacy.mode 必须为 drop 或 redact");
        }
    }

    private void requireInt(String key, int min, int max) {
        int value;
        try {
            value = Integer.parseInt(getString(key).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须是整数");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    key + " 必须在 " + min + "–" + max + " 之间");
        }
    }

    private void requireDouble(String key, double min, double max) {
        double value;
        try {
            value = Double.parseDouble(getString(key).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须是数字");
        }
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    key + " 必须在 " + min + "–" + max + " 之间");
        }
    }

    private void requireWeightSum(double expected, String... keys) {
        double total = 0;
        for (String key : keys) {
            double value;
            try {
                value = Double.parseDouble(getString(key).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " 必须是数字");
            }
            if (value < 0 || value > 1) {
                throw new IllegalArgumentException(key + " 必须在 0–1 之间");
            }
            total += value;
        }
        if (Math.abs(total - expected) > 0.011) {
            throw new IllegalArgumentException(
                    "权重之和必须接近 " + expected + "，当前为 " + total);
        }
    }

    private static void put(String key, String value) {
        DEFAULTS.put(key, value);
    }
}
