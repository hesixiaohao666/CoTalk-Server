package store;

import model.Avatar;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 头像多版本存储：(user_id, version) 唯一；旧版本不覆盖。
 */
public class AvatarStore {

    private final Database database;

    public AvatarStore(Database database) {
        this.database = database;
    }

    public int save(String userId, String mime, byte[] data) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            int nextVersion = 1;
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COALESCE(MAX(version), 0) + 1 FROM avatar WHERE user_id = ?")) {
                statement.setString(1, userId);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        nextVersion = result.getInt(1);
                    }
                }
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO avatar(user_id, version, mime, data, updated_at) "
                            + "VALUES(?, ?, ?, ?, ?)")) {
                statement.setString(1, userId);
                statement.setInt(2, nextVersion);
                statement.setString(3, mime);
                statement.setBytes(4, data);
                statement.setLong(5, System.currentTimeMillis());
                statement.executeUpdate();
            }
            return nextVersion;
        }
    }

    /**
     * version <= 0 取最新；指定版本不存在时回退最新。
     */
    public Avatar load(String userId, int version) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            if (version > 0) {
                Avatar avatar = query(connection, userId, version);
                if (avatar != null) {
                    return avatar;
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT user_id, version, mime, data FROM avatar "
                            + "WHERE user_id = ? ORDER BY version DESC LIMIT 1")) {
                statement.setString(1, userId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? map(result) : null;
                }
            }
        }
    }

    private Avatar query(Connection connection, String userId, int version)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT user_id, version, mime, data FROM avatar "
                        + "WHERE user_id = ? AND version = ?")) {
            statement.setString(1, userId);
            statement.setInt(2, version);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? map(result) : null;
            }
        }
    }

    private Avatar map(ResultSet result) throws SQLException {
        Avatar avatar = new Avatar();
        avatar.userId = result.getString("user_id");
        avatar.version = result.getInt("version");
        avatar.mime = result.getString("mime");
        avatar.data = result.getBytes("data");
        return avatar;
    }
}
