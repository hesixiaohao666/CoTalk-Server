package store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 服务端 SQLite 连接与建表。
 *
 * <p>Step 3 打开数据库、建四张表；Step 4 写入消息；Step 8 写入头像。</p>
 */
public class Database implements AutoCloseable {

    private final String path;
    private Connection connection;

    public Database(String path) {
        this.path = path;
    }

    public synchronized void open() throws Exception {
        if (connection != null) {
            return;
        }
        Path dbPath = Paths.get(path);
        if (dbPath.getParent() != null) {
            Files.createDirectories(dbPath.getParent());
        }

        Class.forName("org.sqlite.JDBC");
        connection = DriverManager.getConnection("jdbc:sqlite:" + path);

        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.execute("PRAGMA foreign_keys=ON");

            statement.execute("CREATE TABLE IF NOT EXISTS room ("
                    + "id INTEGER PRIMARY KEY CHECK (id = 1), "
                    + "name TEXT NOT NULL, "
                    + "announcement TEXT, "
                    + "created_at INTEGER NOT NULL"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS message ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "msg_uuid TEXT NOT NULL UNIQUE, "
                    + "seq INTEGER NOT NULL UNIQUE, "
                    + "sender_id TEXT NOT NULL, "
                    + "sender_name TEXT NOT NULL, "
                    + "sender_tag TEXT, "
                    + "sender_avatar_version INTEGER NOT NULL DEFAULT 0, "
                    + "content TEXT NOT NULL, "
                    + "msg_type TEXT NOT NULL DEFAULT 'text', "
                    + "created_at INTEGER NOT NULL"
                    + ")");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_message_seq "
                    + "ON message(seq)");

            statement.execute("CREATE TABLE IF NOT EXISTS avatar ("
                    + "user_id TEXT NOT NULL, "
                    + "version INTEGER NOT NULL, "
                    + "mime TEXT NOT NULL, "
                    + "data BLOB NOT NULL, "
                    + "updated_at INTEGER NOT NULL, "
                    + "PRIMARY KEY(user_id, version)"
                    + ")");

            statement.execute("CREATE TABLE IF NOT EXISTS connection_log ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "user_id TEXT NOT NULL, "
                    + "ip TEXT, "
                    + "connected_at INTEGER NOT NULL, "
                    + "disconnected_at INTEGER"
                    + ")");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_conn_user "
                    + "ON connection_log(user_id)");

            statement.execute("PRAGMA user_version=1");
        }
    }

    public synchronized Connection connection() {
        if (connection == null) {
            throw new IllegalStateException("数据库尚未打开");
        }
        return connection;
    }

    public synchronized boolean isOpen() {
        return connection != null;
    }

    public synchronized void checkpoint() {
        if (connection == null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        } catch (SQLException e) {
            System.err.println("WAL checkpoint 失败：" + e.getMessage());
        }
    }

    @Override
    public synchronized void close() {
        checkpoint();
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                System.err.println("关闭 SQLite 失败：" + e.getMessage());
            } finally {
                connection = null;
            }
        }
    }
}
