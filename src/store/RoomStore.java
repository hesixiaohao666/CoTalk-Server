package store;

import model.Room;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * 单房间：只有 id=1 一行。启动时按配置创建/更新。
 */
public class RoomStore {

    private final Database database;

    public RoomStore(Database database) {
        this.database = database;
    }

    public Room loadOrCreate(String name, String announcement) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            long createdAt = System.currentTimeMillis();

            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT name, announcement, created_at FROM room WHERE id = 1");
                 ResultSet result = query.executeQuery()) {
                if (result.next()) {
                    createdAt = result.getLong("created_at");
                    try (PreparedStatement update = connection.prepareStatement(
                            "UPDATE room SET name = ?, announcement = ? WHERE id = 1")) {
                        update.setString(1, name);
                        update.setString(2, announcement);
                        update.executeUpdate();
                    }
                    return new Room(name, announcement, createdAt);
                }
            }

            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO room(id, name, announcement, created_at) VALUES(1, ?, ?, ?)")) {
                insert.setString(1, name);
                insert.setString(2, announcement);
                insert.setLong(3, createdAt);
                insert.executeUpdate();
            }
            return new Room(name, announcement, createdAt);
        }
    }
}
