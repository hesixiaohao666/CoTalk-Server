package store;

import model.Attachment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 附件元数据表读写。文件本体由 AttachmentService 管理。
 */
public class AttachmentStore {

    private static final String COLUMNS =
            "file_id, message_uuid, file_name, mime, size, created_at, expired";

    private final Database database;

    public AttachmentStore(Database database) {
        this.database = database;
    }

    public Attachment find(String fileId) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM attachment WHERE file_id = ?")) {
                statement.setString(1, fileId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? map(result) : null;
                }
            }
        }
    }

    /**
     * 文件过期或文件缺失时，同时标记附件和对应消息。
     */
    public void markExpired(String fileId, String messageUuid) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            boolean autoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE attachment SET expired = 1 WHERE file_id = ?")) {
                    statement.setString(1, fileId);
                    statement.executeUpdate();
                }
                if (messageUuid != null && !messageUuid.isEmpty()) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE message SET file_expired = 1 WHERE msg_uuid = ?")) {
                        statement.setString(1, messageUuid);
                        statement.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    /**
     * 未过期附件总字节数，供 v0.3.0 Step 2 的 2GB 清理策略使用。
     */
    public long totalBytes() throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COALESCE(SUM(size), 0) FROM attachment WHERE expired = 0");
                 ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    private Attachment map(ResultSet result) throws SQLException {
        Attachment attachment = new Attachment();
        attachment.fileId = result.getString("file_id");
        attachment.messageUuid = result.getString("message_uuid");
        attachment.fileName = result.getString("file_name");
        attachment.mime = result.getString("mime");
        attachment.size = result.getLong("size");
        attachment.createdAt = result.getLong("created_at");
        attachment.expired = result.getInt("expired") != 0;
        return attachment;
    }
}
