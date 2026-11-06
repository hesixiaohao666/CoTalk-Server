package store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 服务端 SQLite：房间、消息、头像版本与连接日志。
 */
public class Database implements AutoCloseable {

    private static final int SCHEMA_VERSION = 2;

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

            int userVersion = readUserVersion(statement);

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
                    + "file_id TEXT, "
                    + "file_name TEXT, "
                    + "file_mime TEXT, "
                    + "file_size INTEGER NOT NULL DEFAULT 0, "
                    + "file_expired INTEGER NOT NULL DEFAULT 0, "
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

            statement.execute("CREATE TABLE IF NOT EXISTS attachment ("
                    + "file_id TEXT PRIMARY KEY, "
                    + "message_uuid TEXT NOT NULL, "
                    + "file_name TEXT, "
                    + "mime TEXT, "
                    + "size INTEGER NOT NULL DEFAULT 0, "
                    + "created_at INTEGER NOT NULL, "
                    + "expired INTEGER NOT NULL DEFAULT 0"
                    + ")");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_attachment_message "
                    + "ON attachment(message_uuid)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_attachment_expired "
                    + "ON attachment(expired, created_at)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_attachment_name "
                    + "ON attachment(file_name)");

            // v1 -> v2：旧库补齐消息附件字段；全新库的建表语句已包含这些列。
            addColumnIfMissing(statement, "message", "file_id", "TEXT");
            addColumnIfMissing(statement, "message", "file_name", "TEXT");
            addColumnIfMissing(statement, "message", "file_mime", "TEXT");
            addColumnIfMissing(statement, "message", "file_size",
                    "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(statement, "message", "file_expired",
                    "INTEGER NOT NULL DEFAULT 0");

            if (userVersion < SCHEMA_VERSION) {
                statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
            }
        }
    }

    private static int readUserVersion(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("PRAGMA user_version")) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    private static void addColumnIfMissing(Statement statement,
                                           String table,
                                           String column,
                                           String definition)
            throws SQLException {
        try (ResultSet result = statement.executeQuery(
                "PRAGMA table_info(" + table + ")")) {
            while (result.next()) {
                if (column.equalsIgnoreCase(result.getString("name"))) {
                    return;
                }
            }
        }
        statement.execute("ALTER TABLE " + table
                + " ADD COLUMN " + column + " " + definition);
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
