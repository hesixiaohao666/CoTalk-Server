package store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * 只记录临时 UUID、IP、连接/断开时间；不存账号。
 */
public class ConnectionLogStore {

    private final Database database;

    public ConnectionLogStore(Database database) {
        this.database = database;
    }

    public void insert(String userId, String ip, long connectedAt) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO connection_log(user_id, ip, connected_at) VALUES(?, ?, ?)")) {
                statement.setString(1, userId);
                statement.setString(2, ip);
                statement.setLong(3, connectedAt);
                statement.executeUpdate();
            }
        }
    }

    public void closeConnection(String userId, long disconnectedAt) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE connection_log SET disconnected_at = ? "
                            + "WHERE user_id = ? AND disconnected_at IS NULL")) {
                statement.setLong(1, disconnectedAt);
                statement.setString(2, userId);
                statement.executeUpdate();
            }
        }
    }
}
