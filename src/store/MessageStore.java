package store;

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
                    + "sender_avatar_version, content, msg_type, created_at";

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
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO message(msg_uuid, seq, sender_id, sender_name, "
                            + "sender_tag, sender_avatar_version, content, msg_type, created_at) "
                            + "VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, message.msgUuid);
                statement.setLong(2, message.seq);
                statement.setString(3, message.senderId);
                statement.setString(4, message.senderName);
                statement.setString(5, message.senderTag);
                statement.setInt(6, message.senderAvatarVersion);
                statement.setString(7, message.content);
                statement.setString(8, message.type);
                statement.setLong(9, message.createdAt);
                statement.executeUpdate();
            }
        }
    }

    public List<Message> loadRecent(int limit) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM message "
                            + "ORDER BY seq DESC LIMIT ?")) {
                statement.setInt(1, limit);
                List<Message> messages = readList(statement.executeQuery());
                Collections.reverse(messages);
                return messages;
            }
        }
    }

    public List<Message> before(long beforeSeq, int limit) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM message "
                            + "WHERE seq < ? ORDER BY seq DESC LIMIT ?")) {
                statement.setLong(1, beforeSeq);
                statement.setInt(2, limit);
                List<Message> messages = readList(statement.executeQuery());
                Collections.reverse(messages);
                return messages;
            }
        }
    }

    public List<Message> after(long afterSeq, int limit) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT " + COLUMNS + " FROM message "
                            + "WHERE seq > ? ORDER BY seq ASC LIMIT ?")) {
                statement.setLong(1, afterSeq);
                statement.setInt(2, limit);
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
            message.createdAt = result.getLong("created_at");
            messages.add(message);
        }
        result.close();
        return messages;
    }
}
