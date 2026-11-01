package server;

import java.util.Properties;

/**
 * 服务端配置快照。读取、生成、保存统一由 ServerConfigStore 负责。
 */
public class ServerConfig {

    public static final String DEFAULT_AI_PROMPT =
            "你是CoTalk群聊里的AI群友“大肥鱼”，性格活泼，用中文交流，不冒充人类。"
                    + "被 @ 时必须回复；未被 @ 但有人提到你时，"
                    + "只有确实有必要参与才回复，否则不回复。"
                    + "只输出JSON，不要输出推理或解释。"
                    + "JSON格式：{\"send\":true,\"messages\":[\"短消息1\",\"短消息2\"]}；"
                    + "每条不超过20个字符，最多3条；不想回复时输出"
                    + "{\"send\":false,\"messages\":[]}。";

    public final int port;
    public final int maxClients;
    public final int joinTimeoutMs;
    public final int heartbeatTimeoutMs;

    public final String authKey;

    public final String roomName;
    public final String roomAnnouncement;
    public final String dbPath;
    public final int historyRecent;
    public final int historyMaxFetch;
    public final int avatarMaxBytes;

    public final boolean aiEnabled;
    public final String aiApiKey;
    public final String aiModel;
    public final String aiBaseUrl;
    public final boolean aiThinking;
    public final String aiPrompt;
    public final String aiMentions;
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

        dbPath = value(properties, "db.path", "data/cotalk.db");
        historyRecent = intValue(properties, "history.recent", 50);
        historyMaxFetch = intValue(properties, "history.maxFetch", 200);
        avatarMaxBytes = intValue(properties, "avatar.maxBytes", 204800);

        aiEnabled = Boolean.parseBoolean(value(properties, "ai.enabled", "false"));
        aiApiKey = value(properties, "ai.apiKey", "");
        aiModel = value(properties, "ai.model", "deepseek-flash");
        aiBaseUrl = value(properties, "ai.baseUrl",
                "https://api.deepseek.com/chat/completions");
        aiThinking = Boolean.parseBoolean(value(properties, "ai.thinking", "false"));
        aiPrompt = value(properties, "ai.prompt", DEFAULT_AI_PROMPT);
        aiMentions = value(properties, "ai.mentions", "@大肥鱼,@AI");
        aiCooldownMs = intValue(properties, "ai.cooldownMs", 3000);
        aiMaxContext = intValue(properties, "ai.maxContext", 10);
        aiTimeoutMs = intValue(properties, "ai.timeoutMs", 20000);
        aiMaxTokens = intValue(properties, "ai.maxTokens", 300);
        aiMessageMaxChars = intValue(properties, "ai.messageMaxChars", 20);
        aiMaxMessages = intValue(properties, "ai.maxMessages", 3);
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

        properties.setProperty("db.path", "data/cotalk.db");
        properties.setProperty("history.recent", "50");
        properties.setProperty("history.maxFetch", "200");
        properties.setProperty("avatar.maxBytes", "204800");

        properties.setProperty("ai.enabled", "false");
        properties.setProperty("ai.apiKey", "");
        properties.setProperty("ai.model", "deepseek-flash");
        properties.setProperty("ai.baseUrl",
                "https://api.deepseek.com/chat/completions");
        properties.setProperty("ai.thinking", "false");
        properties.setProperty("ai.prompt", DEFAULT_AI_PROMPT);
        properties.setProperty("ai.mentions", "@大肥鱼,@AI");
        properties.setProperty("ai.cooldownMs", "3000");
        properties.setProperty("ai.maxContext", "10");
        properties.setProperty("ai.timeoutMs", "20000");
        properties.setProperty("ai.maxTokens", "300");
        properties.setProperty("ai.messageMaxChars", "20");
        properties.setProperty("ai.maxMessages", "3");
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
        properties.setProperty("db.path", dbPath);
        properties.setProperty("history.recent", String.valueOf(historyRecent));
        properties.setProperty("history.maxFetch", String.valueOf(historyMaxFetch));
        properties.setProperty("avatar.maxBytes", String.valueOf(avatarMaxBytes));

        properties.setProperty("ai.enabled", String.valueOf(aiEnabled));
        properties.setProperty("ai.apiKey", aiApiKey);
        properties.setProperty("ai.model", aiModel);
        properties.setProperty("ai.baseUrl", aiBaseUrl);
        properties.setProperty("ai.thinking", String.valueOf(aiThinking));
        properties.setProperty("ai.prompt", aiPrompt);
        properties.setProperty("ai.mentions", aiMentions);
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
