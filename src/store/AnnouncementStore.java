package store;

import model.Announcement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 公告与用户回执持久化。只保留最新公告，回执按身份记录。
 */
public class AnnouncementStore {

    private final Database database;

    public AnnouncementStore(Database database) {
        this.database = database;
    }

    public Announcement insert(String title,
                               String content,
                               String createdBy,
                               long createdAt) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO announcement(title, content, created_by, "
                            + "created_at) VALUES(?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, title);
                statement.setString(2, content);
                statement.setString(3, createdBy == null ? "" : createdBy);
                statement.setLong(4, createdAt);
                statement.executeUpdate();
                Announcement announcement = new Announcement();
                announcement.title = title;
                announcement.content = content;
                announcement.createdBy = createdBy == null ? "" : createdBy;
                announcement.createdAt = createdAt;
                try (ResultSet keys = statement.getGeneratedKeys()) {
                    if (keys.next()) {
                        announcement.id = keys.getLong(1);
                    }
                }
                if (announcement.id <= 0) {
                    try (PreparedStatement last = connection.prepareStatement(
                            "SELECT last_insert_rowid()");
                         ResultSet keys = last.executeQuery()) {
                        if (keys.next()) {
                            announcement.id = keys.getLong(1);
                        }
                    }
                }
                return announcement;
            }
        }
    }

    public Announcement latest() throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, title, content, created_by, created_at "
                            + "FROM announcement ORDER BY id DESC LIMIT 1");
                 ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                Announcement announcement = new Announcement();
                announcement.id = result.getLong("id");
                announcement.title = result.getString("title");
                announcement.content = result.getString("content");
                announcement.createdBy = result.getString("created_by");
                announcement.createdAt = result.getLong("created_at");
                return announcement;
            }
        }
    }

    public boolean hasReceipt(String identityId,
                              long announcementId) throws SQLException {
        if (identityId == null || identityId.isEmpty()
                || announcementId <= 0) {
            return false;
        }
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM announcement_receipt "
                            + "WHERE identity_id = ? AND announcement_id = ?")) {
                statement.setString(1, identityId);
                statement.setLong(2, announcementId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next();
                }
            }
        }
    }

    public void markReceipt(String identityId,
                            long announcementId,
                            long deliveredAt) throws SQLException {
        if (identityId == null || identityId.isEmpty()
                || announcementId <= 0) {
            return;
        }
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT OR IGNORE INTO announcement_receipt("
                            + "identity_id, announcement_id, delivered_at) "
                            + "VALUES(?, ?, ?)")) {
                statement.setString(1, identityId);
                statement.setLong(2, announcementId);
                statement.setLong(3, deliveredAt);
                statement.executeUpdate();
            }
        }
    }
}
