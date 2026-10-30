package server;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * config.properties 读取；文件不存在时使用默认值。
 */
public class ServerConfig {

    public final int port;
    public final int maxClients;
    public final int joinTimeoutMs;
    public final int heartbeatTimeoutMs;
    public final String roomName;
    public final String roomAnnouncement;
    public final String dbPath;
    public final int historyRecent;
    public final int historyMaxFetch;
    public final int avatarMaxBytes;

    public final boolean aiEnabled;
    public final String aiName;
    public final String aiModel;
    public final String aiBaseUrl;
    public final int aiCooldownMs;

    private ServerConfig(Properties properties) {
        port = intValue(properties, "server.port", 4700);
        maxClients = intValue(properties, "server.maxClients", 50);
        joinTimeoutMs = intValue(properties, "server.joinTimeoutMs", 10000);
        heartbeatTimeoutMs = intValue(properties, "server.heartbeatTimeoutMs", 90000);

        roomName = value(properties, "room.name", "CoTalk 大厅");
        roomAnnouncement = value(properties, "room.announcement", "欢迎来到 CoTalk");

        dbPath = value(properties, "db.path", "data/cotalk.db");
        historyRecent = intValue(properties, "history.recent", 50);
        historyMaxFetch = intValue(properties, "history.maxFetch", 200);
        avatarMaxBytes = intValue(properties, "avatar.maxBytes", 204800);

        aiEnabled = Boolean.parseBoolean(value(properties, "ai.enabled", "false"));
        aiName = value(properties, "ai.name", "大肥鱼");
        aiModel = value(properties, "ai.model", "deepseek-flash");
        aiBaseUrl = value(properties, "ai.baseUrl",
                "https://api.deepseek.com/chat/completions");
        aiCooldownMs = intValue(properties, "ai.cooldownMs", 3000);
    }

    public static ServerConfig load() {
        Properties properties = new Properties();
        Path path = Paths.get("config.properties");
        if (Files.exists(path)) {
            try (Reader reader = new InputStreamReader(
                    Files.newInputStream(path), StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (Exception e) {
                System.err.println("读取 config.properties 失败，使用默认配置：" + e.getMessage());
            }
        } else {
            System.out.println("config.properties 不存在，使用默认配置");
        }
        return new ServerConfig(properties);
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
