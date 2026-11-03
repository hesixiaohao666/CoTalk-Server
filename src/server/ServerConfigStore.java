package server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

/**
 * config.properties 的读取、首次生成、随机密钥与原子保存。
 */
public final class ServerConfigStore {

    public static final Path DEFAULT_PATH = Paths.get("config.properties");

    public static final class LoadResult {
        public final ServerConfig config;
        public final Path path;
        public final boolean created;
        public final boolean keyGenerated;
        public final String generatedKey;

        LoadResult(ServerConfig config,
                   Path path,
                   boolean created,
                   boolean keyGenerated,
                   String generatedKey) {
            this.config = config;
            this.path = path;
            this.created = created;
            this.keyGenerated = keyGenerated;
            this.generatedKey = generatedKey;
        }
    }

    private ServerConfigStore() {
    }

    public static LoadResult loadOrCreate() throws IOException {
        return loadOrCreate(DEFAULT_PATH);
    }

    public static LoadResult loadOrCreate(Path path) throws IOException {
        Properties merged = ServerConfig.createDefaultProperties();
        boolean created = !Files.exists(path);
        if (!created) {
            Properties loaded = new Properties();
            try (BufferedReader reader = Files.newBufferedReader(
                    path, StandardCharsets.UTF_8)) {
                loaded.load(reader);
            } catch (IllegalArgumentException e) {
                throw new IOException("配置文件格式错误：" + e.getMessage(), e);
            }
            merged.putAll(loaded);
        }

        String key = merged.getProperty("auth.key", "").trim();
        boolean keyGenerated = false;
        String generatedKey = null;
        if (key.isEmpty()) {
            generatedKey = generateAuthKey();
            merged.setProperty("auth.key", generatedKey);
            keyGenerated = true;
        }

        ServerConfig config = new ServerConfig(merged);
        validate(config);

        if (created || keyGenerated) {
            saveProperties(merged, path);
        }
        return new LoadResult(
                config, path.toAbsolutePath(), created, keyGenerated, generatedKey);
    }

    public static void save(ServerConfig config) throws IOException {
        save(config, DEFAULT_PATH);
    }

    public static void save(ServerConfig config, Path path) throws IOException {
        validate(config);
        saveProperties(config.toProperties(), path);
    }

    public static void validate(ServerConfig config) {
        if (config.port < 1 || config.port > 65535) {
            throw new IllegalArgumentException("server.port 必须在 1–65535 之间");
        }
        if (config.maxClients <= 0) {
            throw new IllegalArgumentException("server.maxClients 必须大于 0");
        }
        if (config.joinTimeoutMs <= 0 || config.heartbeatTimeoutMs <= 0) {
            throw new IllegalArgumentException("连接超时时间必须大于 0");
        }
        if (config.authKey == null || config.authKey.trim().isEmpty()) {
            throw new IllegalArgumentException("auth.key 不能为空");
        }
        if (config.historyRecent <= 0 || config.historyMaxFetch <= 0) {
            throw new IllegalArgumentException("history 配置必须大于 0");
        }
        if (config.avatarMaxBytes <= 0) {
            throw new IllegalArgumentException("avatar.maxBytes 必须大于 0");
        }
        if (config.aiCooldownMs < 0 || config.aiMaxContext <= 0
                || config.aiTimeoutMs <= 0 || config.aiMaxTokens <= 0) {
            throw new IllegalArgumentException("AI 数值配置非法");
        }
        if (config.aiMessageMaxChars <= 0
                || config.aiMaxMessages <= 0
                || config.aiMaxMessages > 10
                || config.aiReplyDelayMs < 0) {
            throw new IllegalArgumentException("AI 消息条数/字数配置非法");
        }
        if (config.aiEnabled
                && (config.aiModel.isEmpty()
                || config.aiBaseUrl.isEmpty())) {
            throw new IllegalArgumentException("AI 已启用，但模型或 BaseURL 为空");
        }
    }

    public static String generateAuthKey() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void saveProperties(Properties properties, Path path)
            throws IOException {
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temp = Files.createTempFile(
                parent == null ? Paths.get(".") : parent,
                "config-", ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(
                    temp, StandardCharsets.UTF_8)) {
                writer.write("# CoTalk v"
                        + ServerVersion.VERSION_NAME + " configuration\n");
                writer.write("# 首次启动自动生成；可手动编辑或通过可视化设置修改。\n\n");
                write(writer, properties, "server.port");
                write(writer, properties, "server.maxClients");
                write(writer, properties, "server.joinTimeoutMs");
                write(writer, properties, "server.heartbeatTimeoutMs");
                write(writer, properties, "auth.key");
                writer.write("\n");
                write(writer, properties, "room.name");
                write(writer, properties, "room.announcement");
                write(writer, properties, "db.path");
                write(writer, properties, "history.recent");
                write(writer, properties, "history.maxFetch");
                write(writer, properties, "avatar.maxBytes");
                writer.write("\n");
                write(writer, properties, "ai.enabled");
                write(writer, properties, "ai.apiKey");
                write(writer, properties, "ai.model");
                write(writer, properties, "ai.baseUrl");
                write(writer, properties, "ai.thinking");
                write(writer, properties, "ai.prompt");
                write(writer, properties, "ai.mentions");
                write(writer, properties, "ai.cooldownMs");
                write(writer, properties, "ai.maxContext");
                write(writer, properties, "ai.timeoutMs");
                write(writer, properties, "ai.maxTokens");
                write(writer, properties, "ai.messageMaxChars");
                write(writer, properties, "ai.maxMessages");
                write(writer, properties, "ai.replyDelayMs");
                write(writer, properties, "ai.name");
                write(writer, properties, "ai.avatar");
            }

            try {
                Files.move(temp, absolute,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(absolute);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void write(BufferedWriter writer,
                              Properties properties,
                              String key) throws IOException {
        writer.write(key);
        writer.write('=');
        writer.write(escape((properties.getProperty(key, ""))));
        writer.write('\n');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static void restrictPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(
                    path, PosixFilePermissions.fromString("rw-------"));
        } catch (Exception ignored) {
            // Windows 或不支持 POSIX 权限的环境忽略
        }
    }
}
