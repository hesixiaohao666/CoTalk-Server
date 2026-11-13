package server;

import ai.AiPrompts;

import java.util.Properties;

/**
 * 服务端配置快照。读取、生成、保存统一由 ServerConfigStore 负责。
 */
public class ServerConfig {

    public final int port;
    public final int maxClients;
    public final int joinTimeoutMs;
    public final int heartbeatTimeoutMs;

    public final String authKey;

    public final String roomName;
    public final String roomAnnouncement;
    public final String roomAvatar;
    public final String dbPath;
    public final int historyRecent;
    public final int historyMaxFetch;
    public final int avatarMaxBytes;

    public final boolean featureImage;
    public final boolean featureVoice;
    public final boolean featureEmoji;

    public final boolean pushEnabled;
    public final int reconnectGraceMs;

    public final boolean adminEnabled;
    public final String adminSalt;
    public final int adminIterations;
    public final String adminVerifier;

    public final boolean aiProEnabled;

    public final boolean aiEnabled;
    public final String aiApiKey;
    public final String aiModel;
    public final String aiBaseUrl;
    public final boolean aiThinking;
    public final boolean aiVision;
    public final boolean aiSystemMessages;
    public final String aiPromptCustom;
    public final String aiMentions;
    public final String aiKeywords;
    public final int aiKeywordChance;
    public final int aiRandomChance;
    public final int aiCooldownMs;
    public final int aiMaxContext;
    public final int aiTimeoutMs;
    public final int aiMaxTokens;
    public final int aiMessageMaxChars;
    public final int aiMaxMessages;
    public final int aiReplyDelayMs;
    public final String aiName;
    public final String aiAvatar;

    ServerConfig(Properties properties) {
        port = intValue(properties, "server.port", 4700);
        maxClients = intValue(properties, "server.maxClients", 50);
        joinTimeoutMs = intValue(properties, "server.joinTimeoutMs", 10000);
        heartbeatTimeoutMs = intValue(properties, "server.heartbeatTimeoutMs", 90000);

        authKey = value(properties, "auth.key", "");

        roomName = value(properties, "room.name", "CoTalk 大厅");
        roomAnnouncement = value(properties, "room.announcement", "欢迎来到 CoTalk");
        roomAvatar = value(properties, "room.avatar", "");

        dbPath = value(properties, "db.path", "data/cotalk.db");
        historyRecent = intValue(properties, "history.recent", 50);
        historyMaxFetch = intValue(properties, "history.maxFetch", 200);
        avatarMaxBytes = intValue(properties, "avatar.maxBytes", 204800);

        featureImage = Boolean.parseBoolean(
                value(properties, "features.image", "true"));
        featureVoice = Boolean.parseBoolean(
                value(properties, "features.voice", "true"));
        featureEmoji = Boolean.parseBoolean(
                value(properties, "features.emoji", "true"));

        pushEnabled = Boolean.parseBoolean(
                value(properties, "push.enabled", "true"));
        reconnectGraceMs = intValue(
                properties, "server.reconnectGraceMs", 15000);

        adminEnabled = Boolean.parseBoolean(
                value(properties, "admin.enabled", "false"));
        adminSalt = value(properties, "admin.salt", "");
        adminIterations = intValue(properties, "admin.iterations", 120000);
        adminVerifier = value(properties, "admin.verifier", "");

        aiProEnabled = Boolean.parseBoolean(
                value(properties, "ai.pro.enabled", "false"));

        aiEnabled = Boolean.parseBoolean(value(properties, "ai.enabled", "false"));
        aiApiKey = value(properties, "ai.apiKey", "");
        aiModel = value(properties, "ai.model", "deepseek-flash");
        aiBaseUrl = value(properties, "ai.baseUrl",
                "https://api.deepseek.com/chat/completions");
        aiThinking = Boolean.parseBoolean(value(properties, "ai.thinking", "false"));
        aiVision = Boolean.parseBoolean(value(properties, "ai.vision", "true"));
        aiSystemMessages = Boolean.parseBoolean(
                value(properties, "ai.systemMessages", "true"));
        aiPromptCustom = customPrompt(properties);
        aiMentions = value(properties, "ai.mentions", "@大肥鱼,@AI");
        aiKeywords = value(properties, "ai.keywords", "大肥鱼,鱼,AI");
        aiKeywordChance = intValue(properties, "ai.keywordChance", 80);
        aiRandomChance = intValue(properties, "ai.randomChance", 20);
        aiCooldownMs = intValue(properties, "ai.cooldownMs", 3000);
        aiMaxContext = intValue(properties, "ai.maxContext", 10);
        aiTimeoutMs = intValue(properties, "ai.timeoutMs", 20000);
        aiMaxTokens = intValue(properties, "ai.maxTokens", 300);
        aiMessageMaxChars = intValue(properties, "ai.messageMaxChars", 30);
        aiMaxMessages = intValue(properties, "ai.maxMessages", 5);
        aiReplyDelayMs = intValue(properties, "ai.replyDelayMs", 600);
        aiName = value(properties, "ai.name", "大肥鱼");
        aiAvatar = value(properties, "ai.avatar", "assets/ai_avatar.png");
    }

    /**
     * 用于首次生成 config.properties 的完整默认值。
     */
    public static Properties createDefaultProperties() {
        Properties properties = new Properties();
        properties.setProperty("server.port", "4700");
        properties.setProperty("server.maxClients", "50");
        properties.setProperty("server.joinTimeoutMs", "10000");
        properties.setProperty("server.heartbeatTimeoutMs", "90000");

        properties.setProperty("auth.key", "");

        properties.setProperty("room.name", "CoTalk 大厅");
        properties.setProperty("room.announcement", "欢迎来到 CoTalk");
        properties.setProperty("room.avatar", "");

        properties.setProperty("db.path", "data/cotalk.db");
        properties.setProperty("history.recent", "50");
        properties.setProperty("history.maxFetch", "200");
        properties.setProperty("avatar.maxBytes", "204800");

        properties.setProperty("features.image", "true");
        properties.setProperty("features.voice", "true");
        properties.setProperty("features.emoji", "true");

        properties.setProperty("push.enabled", "true");
        properties.setProperty("server.reconnectGraceMs", "15000");

        properties.setProperty("admin.enabled", "false");
        properties.setProperty("admin.salt", "");
        properties.setProperty("admin.iterations", "120000");
        properties.setProperty("admin.verifier", "");

        properties.setProperty("ai.pro.enabled", "false");

        properties.setProperty("ai.enabled", "false");
        properties.setProperty("ai.apiKey", "");
        properties.setProperty("ai.model", "deepseek-flash");
        properties.setProperty("ai.baseUrl",
                "https://api.deepseek.com/chat/completions");
        properties.setProperty("ai.thinking", "false");
        properties.setProperty("ai.vision", "true");
        properties.setProperty("ai.systemMessages", "true");
        properties.setProperty("ai.prompt.custom", AiPrompts.DEFAULT_CUSTOM_PROMPT);
        properties.setProperty("ai.mentions", "@大肥鱼,@AI");
        properties.setProperty("ai.keywords", "大肥鱼,鱼,AI");
        properties.setProperty("ai.keywordChance", "80");
        properties.setProperty("ai.randomChance", "20");
        properties.setProperty("ai.cooldownMs", "3000");
        properties.setProperty("ai.maxContext", "10");
        properties.setProperty("ai.timeoutMs", "20000");
        properties.setProperty("ai.maxTokens", "300");
        properties.setProperty("ai.messageMaxChars", "30");
        properties.setProperty("ai.maxMessages", "5");
        properties.setProperty("ai.replyDelayMs", "600");
        properties.setProperty("ai.name", "大肥鱼");
        properties.setProperty("ai.avatar", "assets/ai_avatar.png");
        return properties;
    }

    /**
     * 完整写回/保存时使用的属性集合。
     */
    public Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty("server.port", String.valueOf(port));
        properties.setProperty("server.maxClients", String.valueOf(maxClients));
        properties.setProperty("server.joinTimeoutMs", String.valueOf(joinTimeoutMs));
        properties.setProperty("server.heartbeatTimeoutMs", String.valueOf(heartbeatTimeoutMs));

        properties.setProperty("auth.key", authKey);

        properties.setProperty("room.name", roomName);
        properties.setProperty("room.announcement", roomAnnouncement);
        properties.setProperty("room.avatar", roomAvatar);
        properties.setProperty("db.path", dbPath);
        properties.setProperty("history.recent", String.valueOf(historyRecent));
        properties.setProperty("history.maxFetch", String.valueOf(historyMaxFetch));
        properties.setProperty("avatar.maxBytes", String.valueOf(avatarMaxBytes));

        properties.setProperty("features.image", String.valueOf(featureImage));
        properties.setProperty("features.voice", String.valueOf(featureVoice));
        properties.setProperty("features.emoji", String.valueOf(featureEmoji));

        properties.setProperty("push.enabled", String.valueOf(pushEnabled));
        properties.setProperty("server.reconnectGraceMs",
                String.valueOf(reconnectGraceMs));

        properties.setProperty("admin.enabled", String.valueOf(adminEnabled));
        properties.setProperty("admin.salt", adminSalt);
        properties.setProperty("admin.iterations", String.valueOf(adminIterations));
        properties.setProperty("admin.verifier", adminVerifier);

        properties.setProperty("ai.pro.enabled", String.valueOf(aiProEnabled));

        properties.setProperty("ai.enabled", String.valueOf(aiEnabled));
        properties.setProperty("ai.apiKey", aiApiKey);
        properties.setProperty("ai.model", aiModel);
        properties.setProperty("ai.baseUrl", aiBaseUrl);
        properties.setProperty("ai.thinking", String.valueOf(aiThinking));
        properties.setProperty("ai.vision", String.valueOf(aiVision));
        properties.setProperty("ai.systemMessages", String.valueOf(aiSystemMessages));
        properties.setProperty("ai.prompt.custom", aiPromptCustom);
        properties.setProperty("ai.mentions", aiMentions);
        properties.setProperty("ai.keywords", aiKeywords);
        properties.setProperty("ai.keywordChance", String.valueOf(aiKeywordChance));
        properties.setProperty("ai.randomChance", String.valueOf(aiRandomChance));
        properties.setProperty("ai.cooldownMs", String.valueOf(aiCooldownMs));
        properties.setProperty("ai.maxContext", String.valueOf(aiMaxContext));
        properties.setProperty("ai.timeoutMs", String.valueOf(aiTimeoutMs));
        properties.setProperty("ai.maxTokens", String.valueOf(aiMaxTokens));
        properties.setProperty("ai.messageMaxChars", String.valueOf(aiMessageMaxChars));
        properties.setProperty("ai.maxMessages", String.valueOf(aiMaxMessages));
        properties.setProperty("ai.replyDelayMs", String.valueOf(aiReplyDelayMs));
        properties.setProperty("ai.name", aiName);
        properties.setProperty("ai.avatar", aiAvatar);
        return properties;
    }

    private static String customPrompt(Properties properties) {
        String custom = properties.getProperty("ai.prompt.custom", "").trim();
        if (!custom.isEmpty()) {
            return custom;
        }
        String legacy = properties.getProperty("ai.prompt", "").trim();
        if (legacy.isEmpty()
                || AiPrompts.LEGACY_DEFAULT_PROMPT.equals(legacy)) {
            return AiPrompts.DEFAULT_CUSTOM_PROMPT;
        }
        return legacy;
    }

    private static String value(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(key);
        return value == null || value.trim().isEmpty() ? defaultValue : value.trim();
    }

    private static int intValue(Properties properties, String key, int defaultValue) {
        String value = properties.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
