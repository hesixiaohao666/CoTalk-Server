package ai.pro;

import store.Database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * AI 表情素材库元数据与文件管理。
 */
public class AiEmojiStore {

    private final Database database;
    private final Path directory;

    public AiEmojiStore(Database database, Path directory) throws IOException {
        this.database = database;
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
    }

    public AiEmoji importFile(Path source,
                              String name,
                              String tags,
                              String sourceType,
                              String sourceUserId)
            throws Exception {
        byte[] bytes = Files.readAllBytes(source);
        String hash = sha256(bytes);
        AiEmoji existing = findByHash(hash);
        if (existing != null) {
            return existing;
        }
        String emojiId = "emo-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 8);
        String extension = extension(source.getFileName().toString());
        String fileName = emojiId + extension;
        Path target = directory.resolve(fileName).normalize();
        if (!target.getParent().equals(directory)) {
            throw new IllegalArgumentException("表情文件名非法");
        }
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        String mime = extension.equals(".png") ? "image/png"
                : extension.equals(".gif") ? "image/gif"
                : extension.equals(".webp") ? "image/webp" : "image/jpeg";
        long now = System.currentTimeMillis();
        AiEmoji emoji = new AiEmoji();
        emoji.emojiId = emojiId;
        emoji.name = name == null || name.trim().isEmpty()
                ? emojiId : name.trim();
        emoji.fileName = source.getFileName().toString();
        emoji.mime = mime;
        emoji.size = bytes.length;
        emoji.path = fileName;
        emoji.tags = tags == null || tags.trim().isEmpty() ? "[]" : tags;
        emoji.enabled = true;
        emoji.reviewStatus = "approved";
        emoji.source = sourceType == null ? "admin" : sourceType;
        emoji.sourceUserId = sourceUserId == null ? "" : sourceUserId;
        emoji.contentHash = hash;
        emoji.createdAt = now;
        emoji.updatedAt = now;
        insert(emoji);
        return emoji;
    }

    /**
     * 从内存字节导入表情；用户表情可按审核策略进入待审核状态。
     */
    public AiEmoji importBytes(byte[] bytes,
                               String fileName,
                               String mime,
                               String name,
                               String tags,
                               String sourceType,
                               String sourceUserId,
                               boolean approved) throws Exception {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("表情数据为空");
        }
        String hash = sha256(bytes);
        AiEmoji existing = findByHash(hash);
        if (existing != null) {
            return existing;
        }
        String emojiId = "emo-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 8);
        String extension = extension(fileName);
        String targetName = emojiId + extension;
        Path target = directory.resolve(targetName).normalize();
        if (!target.getParent().equals(directory)) {
            throw new IllegalArgumentException("表情文件名非法");
        }
        Files.write(target, bytes);
        long now = System.currentTimeMillis();
        AiEmoji emoji = new AiEmoji();
        emoji.emojiId = emojiId;
        emoji.name = name == null || name.trim().isEmpty()
                ? emojiId : name.trim();
        emoji.fileName = fileName == null || fileName.trim().isEmpty()
                ? targetName : fileName.trim();
        emoji.mime = mime == null || mime.trim().isEmpty()
                ? mimeForExtension(extension) : mime.trim();
        emoji.size = bytes.length;
        emoji.path = targetName;
        emoji.tags = tags == null || tags.trim().isEmpty() ? "[]" : tags;
        emoji.enabled = approved;
        emoji.reviewStatus = approved ? "approved" : "pending";
        emoji.source = sourceType == null ? "user" : sourceType;
        emoji.sourceUserId = sourceUserId == null ? "" : sourceUserId;
        emoji.contentHash = hash;
        emoji.createdAt = now;
        emoji.updatedAt = now;
        insert(emoji);
        return emoji;
    }

    public void insert(AiEmoji emoji) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_emoji("
                            + "emoji_id, name, file_name, mime, size, path, "
                            + "tags, enabled, review_status, source, "
                            + "source_user_id, content_hash, created_at, "
                            + "updated_at"
                            + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                statement.setString(1, emoji.emojiId);
                statement.setString(2, emoji.name);
                statement.setString(3, emoji.fileName);
                statement.setString(4, emoji.mime);
                statement.setLong(5, emoji.size);
                statement.setString(6, emoji.path);
                statement.setString(7, emoji.tags);
                statement.setInt(8, emoji.enabled ? 1 : 0);
                statement.setString(9, emoji.reviewStatus);
                statement.setString(10, emoji.source);
                statement.setString(11, emoji.sourceUserId);
                statement.setString(12, emoji.contentHash);
                statement.setLong(13, emoji.createdAt);
                statement.setLong(14, emoji.updatedAt);
                statement.executeUpdate();
            }
        }
    }

    public AiEmoji findByEmojiId(String emojiId) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM ai_emoji WHERE emoji_id = ?")) {
                statement.setString(1, emojiId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? map(result) : null;
                }
            }
        }
    }

    public AiEmoji findByHash(String hash) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM ai_emoji WHERE content_hash = ?")) {
                statement.setString(1, hash);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? map(result) : null;
                }
            }
        }
    }

    public List<AiEmoji> listEnabled(int limit) throws Exception {
        List<AiEmoji> result = new ArrayList<>();
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM ai_emoji WHERE enabled = 1 "
                            + "AND review_status = 'approved' "
                            + "ORDER BY created_at DESC LIMIT ?")) {
                statement.setInt(1, Math.max(1, limit));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        result.add(map(resultSet));
                    }
                }
            }
        }
        return result;
    }

    public byte[] readBytes(AiEmoji emoji) throws IOException {
        if (emoji == null) {
            return null;
        }
        Path path = directory.resolve(emoji.path).normalize();
        if (!path.getParent().equals(directory) || !Files.isRegularFile(path)) {
            return null;
        }
        return Files.readAllBytes(path);
    }

    public List<AiEmoji> listAll(int limit) throws Exception {
        List<AiEmoji> result = new ArrayList<>();
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM ai_emoji ORDER BY created_at DESC LIMIT ?")) {
                statement.setInt(1, Math.max(1, limit));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        result.add(map(resultSet));
                    }
                }
            }
        }
        return result;
    }

    public void updateTags(String emojiId, String tags) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_emoji SET tags = ?, updated_at = ? "
                            + "WHERE emoji_id = ?")) {
                statement.setString(1, tags == null ? "[]" : tags);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, emojiId);
                statement.executeUpdate();
            }
        }
    }

    public void delete(String emojiId) throws Exception {
        AiEmoji emoji = findByEmojiId(emojiId);
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM ai_emoji WHERE emoji_id = ?")) {
                statement.setString(1, emojiId);
                statement.executeUpdate();
            }
        }
        if (emoji != null && emoji.path != null && !emoji.path.isEmpty()) {
            Path path = directory.resolve(emoji.path).normalize();
            if (path.getParent().equals(directory)) {
                Files.deleteIfExists(path);
            }
        }
    }

    public void setEnabled(String emojiId, boolean enabled) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_emoji SET enabled = ?, updated_at = ? "
                            + "WHERE emoji_id = ?")) {
                statement.setInt(1, enabled ? 1 : 0);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, emojiId);
                statement.executeUpdate();
            }
        }
    }

    public void setReviewStatus(String emojiId,
                                String reviewStatus,
                                boolean enabled) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_emoji SET review_status = ?, enabled = ?, "
                            + "updated_at = ? WHERE emoji_id = ?")) {
                statement.setString(1, reviewStatus);
                statement.setInt(2, enabled ? 1 : 0);
                statement.setLong(3, System.currentTimeMillis());
                statement.setString(4, emojiId);
                statement.executeUpdate();
            }
        }
    }

    public int countPending() throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM ai_emoji "
                            + "WHERE review_status = 'pending'")) {
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getInt(1) : 0;
                }
            }
        }
    }

    private static AiEmoji map(ResultSet result) throws Exception {
        AiEmoji emoji = new AiEmoji();
        emoji.emojiId = result.getString("emoji_id");
        emoji.name = result.getString("name");
        emoji.fileName = result.getString("file_name");
        emoji.mime = result.getString("mime");
        emoji.size = result.getLong("size");
        emoji.path = result.getString("path");
        emoji.tags = result.getString("tags");
        emoji.enabled = result.getInt("enabled") == 1;
        String reviewStatus = result.getString("review_status");
        emoji.reviewStatus = reviewStatus == null
                || reviewStatus.trim().isEmpty()
                ? "approved" : reviewStatus.trim();
        emoji.source = result.getString("source");
        emoji.sourceUserId = result.getString("source_user_id");
        emoji.contentHash = result.getString("content_hash");
        emoji.createdAt = result.getLong("created_at");
        emoji.updatedAt = result.getLong("updated_at");
        return emoji;
    }

    private static String extension(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot < 0) {
            return ".png";
        }
        String ext = name.substring(dot).toLowerCase(Locale.ROOT);
        return ext.matches("\\.(png|jpg|jpeg|gif|webp)") ? ext : ".png";
    }

    private static String mimeForExtension(String extension) {
        if (".gif".equals(extension)) {
            return "image/gif";
        }
        if (".webp".equals(extension)) {
            return "image/webp";
        }
        if (".jpg".equals(extension) || ".jpeg".equals(extension)) {
            return "image/jpeg";
        }
        return "image/png";
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte b : hash) {
            builder.append(String.format("%02x", b & 0xFF));
        }
        return builder.toString();
    }
}
