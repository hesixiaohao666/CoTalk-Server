package server;

import ai.AiPrompts;
import ai.pro.AiProConfig;

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
    public static final String CONFIG_VERSION = "3201";

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
        boolean promptMigrated = false;
        boolean newKeysMissing = false;
        boolean configUpgraded = false;
        boolean configVersionOutdated = false;
        if (!created) {
            Properties loaded = new Properties();
            try (BufferedReader reader = Files.newBufferedReader(
                    path, StandardCharsets.UTF_8)) {
                loaded.load(reader);
            } catch (IllegalArgumentException e) {
                throw new IOException("配置文件格式错误：" + e.getMessage(), e);
            }
            merged.putAll(loaded);

            String existingCustom = loaded.getProperty(
                    "ai.prompt.custom", "").trim();
            merged.remove("ai.prompt");
            if (existingCustom.isEmpty()) {
                String legacy = loaded.getProperty("ai.prompt", "").trim();
                String custom = legacy.isEmpty()
                        || AiPrompts.LEGACY_DEFAULT_PROMPT.equals(legacy)
                        ? AiPrompts.DEFAULT_CUSTOM_PROMPT
                        : legacy;
                merged.setProperty("ai.prompt.custom", custom);
                promptMigrated = true;
            }
            newKeysMissing = !loaded.containsKey("ai.keywords")
                    || !loaded.containsKey("ai.keywordChance")
                    || !loaded.containsKey("ai.randomChance")
                    || !loaded.containsKey("ai.peakChance")
                    || !loaded.containsKey("ai.offPeakChance")
                    || !loaded.containsKey("ai.peak.timezone")
                    || !loaded.containsKey("ai.peak.days")
                    || !loaded.containsKey("ai.peak.periods")
                    || !loaded.containsKey("room.avatar")
                    || !loaded.containsKey("ai.vision")
                    || !loaded.containsKey("ai.systemMessages")
                    || !loaded.containsKey("features.image")
                    || !loaded.containsKey("features.voice")
                    || !loaded.containsKey("features.emoji")
                    || !loaded.containsKey("push.enabled")
                    || !loaded.containsKey("server.reconnectGraceMs")
                    || !loaded.containsKey("admin.enabled")
                    || !loaded.containsKey("admin.salt")
                    || !loaded.containsKey("admin.iterations")
                    || !loaded.containsKey("admin.verifier")
                    || !AiProConfig.missingKeys(loaded).isEmpty();
            configVersionOutdated = !CONFIG_VERSION.equals(
                    loaded.getProperty("config.version", "").trim());

            if (!loaded.containsKey("config.version")) {
                // v0.2.1 早期默认值：随机 5%、3 条、每条 20 字。
                // 未自定义的旧文件在这里统一升级到 20%、5 条、30 字。
                if ("5".equals(loaded.getProperty(
                        "ai.randomChance", "").trim())) {
                    merged.setProperty("ai.randomChance", "20");
                }
                if ("3".equals(loaded.getProperty(
                        "ai.maxMessages", "").trim())) {
                    merged.setProperty("ai.maxMessages", "5");
                }
                if ("20".equals(loaded.getProperty(
                        "ai.messageMaxChars", "").trim())) {
                    merged.setProperty("ai.messageMaxChars", "30");
                }
                configUpgraded = true;
            }
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

        if (created || keyGenerated || promptMigrated
                || newKeysMissing || configUpgraded || configVersionOutdated) {
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
        if (config.reconnectGraceMs < 5000
                || config.reconnectGraceMs > 60000) {
            throw new IllegalArgumentException(
                    "server.reconnectGraceMs 必须在 5000–60000 之间");
        }
        if (config.adminIterations <= 0) {
            throw new IllegalArgumentException("admin.iterations 必须大于 0");
        }
        if (config.aiPro == null) {
            throw new IllegalArgumentException("ai.pro 配置缺失");
        }
        config.aiPro.validate();
        if (config.adminEnabled && (config.adminSalt.isEmpty()
                || config.adminVerifier.isEmpty())) {
            throw new IllegalArgumentException(
                    "启用管理员模式前必须设置管理员密钥");
        }
        if (!config.adminSalt.isEmpty()) {
            byte[] salt;
            try {
                salt = Base64.getUrlDecoder().decode(config.adminSalt);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "admin.salt 不是有效的 Base64 URL 字符串");
            }
            if (salt.length != AdminAuth.SALT_BYTES) {
                throw new IllegalArgumentException("admin.salt 长度非法");
            }
        }
        if (!config.adminVerifier.isEmpty()) {
            byte[] verifier;
            try {
                verifier = Base64.getUrlDecoder().decode(config.adminVerifier);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "admin.verifier 不是有效的 Base64 URL 字符串");
            }
            if (verifier.length != AdminAuth.VERIFIER_BITS / 8) {
                throw new IllegalArgumentException("admin.verifier 长度非法");
            }
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
        if (config.aiKeywordChance < 0 || config.aiKeywordChance > 100
                || config.aiRandomChance < 0 || config.aiRandomChance > 100
                || config.aiPeakChance < 0 || config.aiPeakChance > 100
                || config.aiOffPeakChance < 0
                || config.aiOffPeakChance > 100) {
            throw new IllegalArgumentException("AI 参与概率必须在 0–100 之间");
        }
        if (!config.aiPeakTimezone.isEmpty()) {
            try {
                java.time.ZoneId.of(config.aiPeakTimezone);
            } catch (Exception e) {
                throw new IllegalArgumentException("ai.peak.timezone 时区无效");
            }
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
                writer.write("# 首次启动自动生成；可手动编辑或通过可视化设置修改。\n");
                writer.write("config.version=" + CONFIG_VERSION + "\n\n");
                write(writer, properties, "server.port");
                write(writer, properties, "server.maxClients");
                write(writer, properties, "server.joinTimeoutMs");
                write(writer, properties, "server.heartbeatTimeoutMs");
                write(writer, properties, "auth.key");
                writer.write("\n");
                write(writer, properties, "room.name");
                write(writer, properties, "room.announcement");
                write(writer, properties, "room.avatar");
                write(writer, properties, "db.path");
                write(writer, properties, "history.recent");
                write(writer, properties, "history.maxFetch");
                write(writer, properties, "avatar.maxBytes");
                writer.write("\n");
                write(writer, properties, "features.image");
                write(writer, properties, "features.voice");
                write(writer, properties, "features.emoji");
                writer.write("\n");
                write(writer, properties, "push.enabled");
                write(writer, properties, "server.reconnectGraceMs");
                writer.write("\n");
                write(writer, properties, "admin.enabled");
                write(writer, properties, "admin.salt");
                write(writer, properties, "admin.iterations");
                write(writer, properties, "admin.verifier");
                writer.write("\n");
                for (String key : AiProConfig.keys()) {
                    write(writer, properties, key);
                }
                writer.write("\n");
                write(writer, properties, "ai.enabled");
                write(writer, properties, "ai.apiKey");
                write(writer, properties, "ai.model");
                write(writer, properties, "ai.baseUrl");
                write(writer, properties, "ai.thinking");
                write(writer, properties, "ai.vision");
                write(writer, properties, "ai.systemMessages");
                write(writer, properties, "ai.prompt.custom");
                write(writer, properties, "ai.mentions");
                write(writer, properties, "ai.keywords");
                write(writer, properties, "ai.keywordChance");
                write(writer, properties, "ai.randomChance");
                write(writer, properties, "ai.peakChance");
                write(writer, properties, "ai.offPeakChance");
                write(writer, properties, "ai.peak.timezone");
                write(writer, properties, "ai.peak.days");
                write(writer, properties, "ai.peak.periods");
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
