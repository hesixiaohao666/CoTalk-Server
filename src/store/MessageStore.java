package store;

import model.Attachment;
import model.Message;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 消息持久化：seq 单调递增，历史分页/补拉。
 */
public class MessageStore {

    private static final String COLUMNS =
            "msg_uuid, seq, sender_id, sender_name, sender_tag, "
                    + "sender_avatar_version, content, msg_type, "
                    + "file_id, file_name, file_mime, file_size, file_expired, "
                    + "created_at";

    private final Database database;

    public MessageStore(Database database) {
        this.database = database;
    }

    public long maxSeq() throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COALESCE(MAX(seq), 0) FROM message");
                 ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : 0L;
            }
        }
    }

    public void insert(Message message) throws SQLException {
        insert(message, null);
    }

    /**
     * 消息与附件元数据在同一事务内写入，避免出现只有文件或只有消息的半成品。
     */
    public void insert(Message message, Attachment attachment) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            boolean autoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                insertMessage(connection, message);
                if (attachment != null) {
                    insertAttachment(connection, attachment);
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

    private void insertMessage(Connection connection, Message message)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO message(msg_uuid, seq, sender_id, sender_name, "
                        + "sender_tag, sender_avatar_version, content, msg_type, "
                        + "file_id, file_name, file_mime, file_size, "
                        + "file_expired, created_at) "
                        + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, message.msgUuid);
            statement.setLong(2, message.seq);
            statement.setString(3, message.senderId);
            statement.setString(4, message.senderName);
            statement.setString(5, message.senderTag);
            statement.setInt(6, message.senderAvatarVersion);
            statement.setString(7, message.content);
            statement.setString(8, message.type);
            statement.setString(9, message.fileId);
            statement.setString(10, message.fileName);
            statement.setString(11, message.fileMime);
            statement.setLong(12, message.fileSize);
            statement.setInt(13, message.fileExpired ? 1 : 0);
            statement.setLong(14, message.createdAt);
            statement.executeUpdate();
        }
    }

    private void insertAttachment(Connection connection, Attachment attachment)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO attachment(file_id, message_uuid, file_name, mime, "
                        + "size, created_at, expired) "
                        + "VALUES(?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, attachment.fileId);
            statement.setString(2, attachment.messageUuid);
            statement.setString(3, attachment.fileName);
            statement.setString(4, attachment.mime);
            statement.setLong(5, attachment.size);
            statement.setLong(6, attachment.createdAt);
            statement.setInt(7, attachment.expired ? 1 : 0);
            statement.executeUpdate();
        }
    }

    public List<Message> loadRecent(int limit, long cutoff) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM message "
                            + "WHERE created_at >= ? AND file_expired = 0 "
                            + "ORDER BY seq DESC LIMIT ?")) {
                statement.setLong(1, cutoff);
                statement.setInt(2, limit);
                List<Message> messages = readList(statement.executeQuery());
                Collections.reverse(messages);
                return messages;
            }
        }
    }

    public List<Message> before(long beforeSeq, int limit, long cutoff)
            throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM message "
                            + "WHERE seq < ? AND created_at >= ? "
                            + "AND file_expired = 0 "
                            + "ORDER BY seq DESC LIMIT ?")) {
                statement.setLong(1, beforeSeq);
                statement.setLong(2, cutoff);
                statement.setInt(3, limit);
                List<Message> messages = readList(statement.executeQuery());
                Collections.reverse(messages);
                return messages;
            }
        }
    }

    public List<Message> after(long afterSeq, int limit, long cutoff)
            throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM message "
                            + "WHERE seq > ? AND created_at >= ? "
                            + "AND file_expired = 0 "
                            + "ORDER BY seq ASC LIMIT ?")) {
                statement.setLong(1, afterSeq);
                statement.setLong(2, cutoff);
                statement.setInt(3, limit);
                return readList(statement.executeQuery());
            }
        }
    }

    private List<Message> readList(ResultSet result) throws SQLException {
        List<Message> messages = new ArrayList<>();
        while (result.next()) {
            Message message = new Message();
            message.msgUuid = result.getString("msg_uuid");
            message.seq = result.getLong("seq");
            message.senderId = result.getString("sender_id");
            message.senderName = result.getString("sender_name");
            message.senderTag = result.getString("sender_tag");
            message.senderAvatarVersion = result.getInt("sender_avatar_version");
            message.content = result.getString("content");
            message.type = result.getString("msg_type");
            message.fileId = result.getString("file_id");
            message.fileName = result.getString("file_name");
            message.fileMime = result.getString("file_mime");
            message.fileSize = result.getLong("file_size");
            message.fileExpired = result.getInt("file_expired") != 0;
            message.createdAt = result.getLong("created_at");
            messages.add(message);
        }
        result.close();
        return messages;
    }
}
