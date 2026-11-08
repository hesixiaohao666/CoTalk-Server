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
                    + "recalled, recall_notice, created_at";

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

    public Message findBySeq(long seq) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM message WHERE seq = ?")) {
                statement.setLong(1, seq);
                List<Message> messages = readList(statement.executeQuery());
                return messages.isEmpty() ? null : messages.get(0);
            }
        }
    }

    public Message findByMsgUuid(String msgUuid) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM message WHERE msg_uuid = ?")) {
                statement.setString(1, msgUuid);
                List<Message> messages = readList(statement.executeQuery());
                return messages.isEmpty() ? null : messages.get(0);
            }
        }
    }

    /**
     * 只设置撤回标记，原消息内容和附件元数据保持不动。
     */
    public boolean markRecalled(String msgUuid, String notice) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE message SET recalled = 1, recall_notice = ? "
                            + "WHERE msg_uuid = ? AND recalled = 0")) {
                statement.setString(1, notice);
                statement.setString(2, msgUuid);
                return statement.executeUpdate() > 0;
            }
        }
    }

    public List<Message> loadRecent(int limit, long cutoff) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM message "
                            + "WHERE created_at >= ? "
                            + "AND (file_expired = 0 OR recalled = 1) "
                            + "ORDER BY seq DESC LIMIT ?")) {
                statement.setLong(1, cutoff);
                statement.setInt(2, limit);
                List<Message> messages = readList(statement.executeQuery());
                Collections.reverse(messages);
                return maskRecalled(messages);
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
                            + "AND (file_expired = 0 OR recalled = 1) "
                            + "ORDER BY seq DESC LIMIT ?")) {
                statement.setLong(1, beforeSeq);
                statement.setLong(2, cutoff);
                statement.setInt(3, limit);
                List<Message> messages = readList(statement.executeQuery());
                Collections.reverse(messages);
                return maskRecalled(messages);
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
                            + "AND (file_expired = 0 OR recalled = 1) "
                            + "ORDER BY seq ASC LIMIT ?")) {
                statement.setLong(1, afterSeq);
                statement.setLong(2, cutoff);
                statement.setInt(3, limit);
                return maskRecalled(readList(statement.executeQuery()));
            }
        }
    }

    private List<Message> maskRecalled(List<Message> messages) {
        List<Message> result = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (!message.recalled) {
                result.add(message);
                continue;
            }
            Message masked = new Message();
            masked.msgUuid = message.msgUuid;
            masked.seq = message.seq;
            masked.senderId = "system";
            masked.senderName = "系统";
            masked.senderTag = "";
            masked.senderAvatarVersion = 0;
            masked.content = message.recallNotice == null
                    || message.recallNotice.trim().isEmpty()
                    ? recalledNotice(message.senderName)
                    : message.recallNotice;
            masked.type = "system";
            masked.createdAt = message.createdAt;
            masked.recalled = true;
            result.add(masked);
        }
        return result;
    }

    private static String recalledNotice(String senderName) {
        String name = senderName == null || senderName.trim().isEmpty()
                ? "用户"
                : senderName.trim();
        return name + " 撤回了一条消息";
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
            message.recalled = result.getInt("recalled") != 0;
            message.recallNotice = result.getString("recall_notice");
            message.createdAt = result.getLong("created_at");
            messages.add(message);
        }
        result.close();
        return messages;
    }
}
