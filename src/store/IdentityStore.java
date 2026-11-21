package store;

import model.IdentityAccount;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * 群聊身份账号读写。用户名以规范化小写存储。
 */
public class IdentityStore {

    private final Database database;

    public IdentityStore(Database database) {
        this.database = database;
    }

    public IdentityAccount findByUsername(String username) throws Exception {
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM chat_identity WHERE username = ?")) {
                statement.setString(1, normalize(username));
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? map(result) : null;
                }
            }
        }
    }

    public IdentityAccount findById(String id) throws Exception {
        if (id == null || id.isEmpty()) {
            return null;
        }
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM chat_identity WHERE id = ?")) {
                statement.setString(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? map(result) : null;
                }
            }
        }
    }

    public void insert(IdentityAccount account) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO chat_identity("
                            + "id, username, salt, iterations, verifier, "
                            + "nickname, status, admin, muted, created_at, "
                            + "updated_at, last_login_at, last_seen_at"
                            + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                statement.setString(1, account.id);
                statement.setString(2, normalize(account.username));
                statement.setString(3, account.salt);
                statement.setInt(4, account.iterations);
                statement.setString(5, account.verifier);
                statement.setString(6, account.nickname);
                statement.setString(7, account.status);
                statement.setInt(8, account.admin ? 1 : 0);
                statement.setInt(9, account.muted ? 1 : 0);
                statement.setLong(10, account.createdAt);
                statement.setLong(11, account.updatedAt);
                statement.setLong(12, account.lastLoginAt);
                statement.setLong(13, account.lastSeenAt);
                statement.executeUpdate();
            }
        }
    }

    public void updateLogin(String id, long loginAt) throws Exception {
        update(id, "last_login_at = ?, updated_at = ?",
                loginAt, loginAt);
    }

    public void updateSeen(String id, long seenAt) throws Exception {
        update(id, "last_seen_at = ?, updated_at = ?",
                seenAt, seenAt);
    }

    public void setMuted(String id, boolean muted) throws Exception {
        update(id, "muted = ?, updated_at = ?",
                muted ? 1L : 0L, System.currentTimeMillis());
    }

    public void updateNickname(String id, String nickname) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE chat_identity SET nickname = ?, updated_at = ? "
                            + "WHERE id = ?")) {
                statement.setString(1, nickname);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, id);
                statement.executeUpdate();
            }
        }
    }

    public void updateStatus(String id, String status) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE chat_identity SET status = ?, updated_at = ? "
                            + "WHERE id = ?")) {
                statement.setString(1, status);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, id);
                statement.executeUpdate();
            }
        }
    }

    public void delete(String id) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM chat_identity WHERE id = ?")) {
                statement.setString(1, id);
                statement.executeUpdate();
            }
        }
    }

    public List<IdentityAccount> listByStatus(String status,
                                              int limit) throws Exception {
        List<IdentityAccount> result = new ArrayList<>();
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM chat_identity WHERE status = ? "
                            + "ORDER BY created_at ASC LIMIT ?")) {
                statement.setString(1, status);
                statement.setInt(2, Math.max(1, limit));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        result.add(map(resultSet));
                    }
                }
            }
        }
        return result;
    }

    public List<IdentityAccount> listApproved(int limit) throws Exception {
        return listByStatus(IdentityAccount.STATUS_APPROVED, limit);
    }

    public List<IdentityAccount> listPending(int limit) throws Exception {
        return listByStatus(IdentityAccount.STATUS_PENDING, limit);
    }

    public int countAdmins() throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM chat_identity "
                            + "WHERE status = 'approved' AND admin = 1")) {
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getInt(1) : 0;
                }
            }
        }
    }

    private void update(String id, String setClause,
                        long first, long second) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE chat_identity SET " + setClause + " WHERE id = ?")) {
                statement.setLong(1, first);
                statement.setLong(2, second);
                statement.setString(3, id);
                statement.executeUpdate();
            }
        }
    }

    private static IdentityAccount map(ResultSet result) throws Exception {
        IdentityAccount account = new IdentityAccount();
        account.rowId = 0;
        account.id = result.getString("id");
        account.username = result.getString("username");
        account.salt = result.getString("salt");
        account.iterations = result.getInt("iterations");
        account.verifier = result.getString("verifier");
        account.nickname = result.getString("nickname");
        account.status = result.getString("status");
        account.admin = result.getInt("admin") == 1;
        account.muted = result.getInt("muted") == 1;
        account.createdAt = result.getLong("created_at");
        account.updatedAt = result.getLong("updated_at");
        account.lastLoginAt = result.getLong("last_login_at");
        account.lastSeenAt = result.getLong("last_seen_at");
        return account;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(
                java.util.Locale.ROOT);
    }
}
