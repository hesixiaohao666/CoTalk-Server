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

    private static final int SCHEMA_VERSION = 3201;

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
                    + "recalled INTEGER NOT NULL DEFAULT 0, "
                    + "recall_notice TEXT, "
                    + "sender_admin INTEGER NOT NULL DEFAULT 0, "
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

            createAiProTables(statement);

            // v3200 -> v3201：用户表情增加审核状态；旧数据默认视为已审核。
            addColumnIfMissing(statement, "ai_emoji", "review_status",
                    "TEXT NOT NULL DEFAULT 'approved'");

            // v1 -> v2：旧库补齐消息附件字段；全新库的建表语句已包含这些列。
            addColumnIfMissing(statement, "message", "file_id", "TEXT");
            addColumnIfMissing(statement, "message", "file_name", "TEXT");
            addColumnIfMissing(statement, "message", "file_mime", "TEXT");
            addColumnIfMissing(statement, "message", "file_size",
                    "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(statement, "message", "file_expired",
                    "INTEGER NOT NULL DEFAULT 0");
            // v2 -> v3：撤回只增加标记，原消息内容与附件元数据保留。
            addColumnIfMissing(statement, "message", "recalled",
                    "INTEGER NOT NULL DEFAULT 0");
            // v3 -> v4：撤回提示需要区分用户撤回和管理员强制撤回。
            addColumnIfMissing(statement, "message", "recall_notice", "TEXT");
            addColumnIfMissing(statement, "message", "sender_admin",
                    "INTEGER NOT NULL DEFAULT 0");

            syncAiProMeta(statement);

            if (userVersion < SCHEMA_VERSION) {
                statement.execute("PRAGMA user_version=" + SCHEMA_VERSION);
            }
        }
    }

    private static void createAiProTables(Statement statement) throws SQLException {
        statement.execute("CREATE TABLE IF NOT EXISTS ai_memory ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "memory_id TEXT NOT NULL UNIQUE, "
                + "layer TEXT NOT NULL, "
                + "status TEXT NOT NULL DEFAULT 'active', "
                + "category TEXT NOT NULL DEFAULT 'fact', "
                + "content TEXT NOT NULL, "
                + "content_hash TEXT NOT NULL, "
                + "keywords TEXT NOT NULL DEFAULT '[]', "
                + "tags TEXT NOT NULL DEFAULT '[]', "
                + "subject_id TEXT, "
                + "subject_name TEXT, "
                + "source_type TEXT NOT NULL, "
                + "source_trust REAL NOT NULL, "
                + "explicit_important INTEGER NOT NULL DEFAULT 0, "
                + "access_count INTEGER NOT NULL DEFAULT 0, "
                + "last_access_at INTEGER NOT NULL, "
                + "created_at INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL, "
                + "stable_since INTEGER NOT NULL, "
                + "task_relevance REAL NOT NULL DEFAULT 0, "
                + "emotional_salience REAL NOT NULL DEFAULT 0, "
                + "reconstructability REAL NOT NULL DEFAULT 0, "
                + "redundancy REAL NOT NULL DEFAULT 0, "
                + "importance REAL NOT NULL DEFAULT 0, "
                + "confidence REAL NOT NULL DEFAULT 0.6, "
                + "expires_at INTEGER, "
                + "merged_into TEXT, "
                + "audit_note TEXT"
                + ")");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_ai_memory_layer "
                + "ON ai_memory(layer, status, importance DESC)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_ai_memory_access "
                + "ON ai_memory(status, last_access_at)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_ai_memory_hash "
                + "ON ai_memory(content_hash)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_ai_memory_updated "
                + "ON ai_memory(status, updated_at)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_ai_memory_expires "
                + "ON ai_memory(status, expires_at)");

        statement.execute("CREATE TABLE IF NOT EXISTS ai_memory_audit ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "memory_id TEXT, "
                + "action TEXT NOT NULL, "
                + "layer_before TEXT, "
                + "layer_after TEXT, "
                + "before_json TEXT, "
                + "after_json TEXT, "
                + "operator TEXT NOT NULL, "
                + "note TEXT, "
                + "created_at INTEGER NOT NULL"
                + ")");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_ai_memory_audit_memory "
                + "ON ai_memory_audit(memory_id, created_at)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_ai_memory_audit_time "
                + "ON ai_memory_audit(created_at)");

        statement.execute("CREATE TABLE IF NOT EXISTS ai_core_memory ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "kind TEXT NOT NULL, "
                + "slot TEXT NOT NULL, "
                + "content TEXT NOT NULL, "
                + "readonly INTEGER NOT NULL DEFAULT 0, "
                + "sort_order INTEGER NOT NULL DEFAULT 0, "
                + "updated_at INTEGER NOT NULL, "
                + "UNIQUE(kind, slot)"
                + ")");

        statement.execute("CREATE TABLE IF NOT EXISTS ai_pro_meta ("
                + "\"key\" TEXT PRIMARY KEY, "
                + "value TEXT, "
                + "updated_at INTEGER NOT NULL"
                + ")");

        statement.execute("CREATE TABLE IF NOT EXISTS ai_emotion_state ("
                + "id INTEGER PRIMARY KEY CHECK(id = 1), "
                + "mood TEXT NOT NULL, "
                + "valence REAL NOT NULL, "
                + "arousal REAL NOT NULL, "
                + "intensity REAL NOT NULL, "
                + "updated_at INTEGER NOT NULL, "
                + "last_trigger_at INTEGER NOT NULL, "
                + "last_decay_at INTEGER NOT NULL, "
                + "note TEXT"
                + ")");

        statement.execute("CREATE TABLE IF NOT EXISTS ai_emoji ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "emoji_id TEXT NOT NULL UNIQUE, "
                + "name TEXT NOT NULL, "
                + "file_name TEXT NOT NULL, "
                + "mime TEXT NOT NULL, "
                + "size INTEGER NOT NULL, "
                + "path TEXT NOT NULL, "
                + "tags TEXT NOT NULL DEFAULT '[]', "
                + "enabled INTEGER NOT NULL DEFAULT 1, "
                + "review_status TEXT NOT NULL DEFAULT 'approved', "
                + "source TEXT NOT NULL, "
                + "source_user_id TEXT, "
                + "content_hash TEXT NOT NULL UNIQUE, "
                + "created_at INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL"
                + ")");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_ai_emoji_enabled "
                + "ON ai_emoji(enabled, created_at)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_ai_emoji_hash "
                + "ON ai_emoji(content_hash)");

        statement.execute("CREATE TABLE IF NOT EXISTS ai_todo ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "todo_id TEXT NOT NULL UNIQUE, "
                + "user_id TEXT NOT NULL, "
                + "user_tag TEXT, "
                + "user_name TEXT NOT NULL, "
                + "content TEXT NOT NULL, "
                + "remind_at INTEGER NOT NULL, "
                + "timezone TEXT NOT NULL, "
                + "status TEXT NOT NULL DEFAULT 'pending', "
                + "remind_count INTEGER NOT NULL DEFAULT 0, "
                + "retry_count INTEGER NOT NULL DEFAULT 0, "
                + "last_remind_at INTEGER, "
                + "source_seq INTEGER, "
                + "created_at INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL"
                + ")");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_ai_todo_due "
                + "ON ai_todo(status, remind_at)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_ai_todo_user "
                + "ON ai_todo(user_id, status)");

        statement.execute("CREATE TABLE IF NOT EXISTS ai_todo_draft ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "user_id TEXT NOT NULL, "
                + "user_tag TEXT, "
                + "user_name TEXT NOT NULL, "
                + "content TEXT NOT NULL, "
                + "created_at INTEGER NOT NULL, "
                + "expires_at INTEGER NOT NULL, "
                + "UNIQUE(user_tag)"
                + ")");

        statement.execute("CREATE TABLE IF NOT EXISTS ai_round ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "started_at INTEGER NOT NULL, "
                + "finished_at INTEGER, "
                + "trigger_type TEXT NOT NULL, "
                + "from_seq INTEGER NOT NULL, "
                + "to_seq INTEGER NOT NULL, "
                + "message_count INTEGER NOT NULL, "
                + "extracted_count INTEGER NOT NULL DEFAULT 0, "
                + "inserted_count INTEGER NOT NULL DEFAULT 0, "
                + "merged_count INTEGER NOT NULL DEFAULT 0, "
                + "promoted_count INTEGER NOT NULL DEFAULT 0, "
                + "demoted_count INTEGER NOT NULL DEFAULT 0, "
                + "deleted_count INTEGER NOT NULL DEFAULT 0, "
                + "status TEXT NOT NULL, "
                + "error TEXT"
                + ")");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_ai_round_time "
                + "ON ai_round(started_at)");

        statement.execute("CREATE TABLE IF NOT EXISTS ai_todo_event ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "todo_id TEXT NOT NULL, "
                + "action TEXT NOT NULL, "
                + "operator TEXT NOT NULL, "
                + "detail TEXT, "
                + "created_at INTEGER NOT NULL"
                + ")");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_ai_todo_event_todo "
                + "ON ai_todo_event(todo_id, created_at)");
    }

    private static void syncAiProMeta(Statement statement) throws SQLException {
        long now = System.currentTimeMillis();
        String version = String.valueOf(SCHEMA_VERSION);
        statement.execute("INSERT OR REPLACE INTO ai_pro_meta(\"key\", value, updated_at) "
                + "VALUES('db_version', '" + version + "', " + now + ")");
        statement.execute("INSERT OR REPLACE INTO ai_pro_meta(\"key\", value, updated_at) "
                + "VALUES('ai_schema', '" + version + "', " + now + ")");
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
