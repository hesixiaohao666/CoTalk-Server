package ai.pro;

import store.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 核心记忆与红线存储。红线每次启动按代码常量覆盖，核心提示词可编辑但默认只补齐。
 */
public class CoreMemoryStore {

    public static final String KIND_REDLINE = "redline";
    public static final String KIND_CORE = "core_prompt";

    private final Database database;

    public CoreMemoryStore(Database database) {
        this.database = database;
    }

    public void initialize(String aiName,
                           String customPrompt,
                           String ownerNames) throws SQLException {
        long now = System.currentTimeMillis();
        synchronized (database) {
            Connection connection = database.connection();
            int sort = 0;
            for (Map.Entry<String, String> redline : AiProPrompts.REDLINES.entrySet()) {
                upsert(connection, KIND_REDLINE, redline.getKey(),
                        redline.getValue(), true, sort++, now);
            }

            String name = blankTo(aiName, "大肥鱼");
            insertIfMissing(connection, KIND_CORE, "identity",
                    "你是 CoTalk 群聊中的 AI 群友", 100, now);
            insertIfMissing(connection, KIND_CORE, "nickname",
                    name, 101, now);
            insertIfMissing(connection, KIND_CORE, "language",
                    "交流语言：简体中文", 102, now);
            insertIfMissing(connection, KIND_CORE, "style",
                    "礼貌、简洁、自然，不冒充人类", 103, now);
            insertIfMissing(connection, KIND_CORE, "owner",
                    ownerNames == null ? "" : ownerNames.trim(), 104, now);
            insertIfMissing(connection, KIND_CORE, "custom",
                    customPrompt == null ? "" : customPrompt.trim(), 105, now);
        }
    }

    public CoreMemory load() throws SQLException {
        Map<String, String> core = new LinkedHashMap<>();
        List<CoreMemory.Entry> entries = new ArrayList<>();
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT kind, slot, content, readonly, sort_order "
                            + "FROM ai_core_memory ORDER BY sort_order, id");
                 ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String kind = result.getString("kind");
                    String slot = result.getString("slot");
                    String content = result.getString("content");
                    entries.add(new CoreMemory.Entry(
                            kind, slot, content,
                            result.getInt("readonly") == 1,
                            result.getInt("sort_order")));
                    if (KIND_CORE.equals(kind)) {
                        core.put(slot, content == null ? "" : content);
                    }
                }
            }
        }
        CoreMemory defaults = AiProPrompts.defaultCore(
                core.get("nickname"), core.get("custom"), core.get("owner"));
        return new CoreMemory(
                core.getOrDefault("identity", defaults.identity),
                core.getOrDefault("nickname", defaults.nickname),
                core.getOrDefault("language", defaults.language),
                core.getOrDefault("style", defaults.style),
                core.getOrDefault("owner", defaults.owner),
                core.getOrDefault("custom", defaults.custom),
                entries);
    }

    public void updateCustom(String content) throws SQLException {
        updateCoreSlot("custom", content);
    }

    public void updateCoreSlot(String slot, String content) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_core_memory SET content = ?, updated_at = ? "
                            + "WHERE kind = ? AND slot = ?")) {
                statement.setString(1, content == null ? "" : content);
                statement.setLong(2, System.currentTimeMillis());
                statement.setString(3, KIND_CORE);
                statement.setString(4, slot);
                statement.executeUpdate();
            }
        }
    }

    private static void upsert(Connection connection,
                               String kind,
                               String slot,
                               String content,
                               boolean readonly,
                               int sortOrder,
                               long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ai_core_memory"
                        + "(kind, slot, content, readonly, sort_order, updated_at) "
                        + "VALUES(?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(kind, slot) DO UPDATE SET "
                        + "content = excluded.content, "
                        + "readonly = excluded.readonly, "
                        + "sort_order = excluded.sort_order, "
                        + "updated_at = excluded.updated_at")) {
            statement.setString(1, kind);
            statement.setString(2, slot);
            statement.setString(3, content);
            statement.setInt(4, readonly ? 1 : 0);
            statement.setInt(5, sortOrder);
            statement.setLong(6, now);
            statement.executeUpdate();
        }
    }

    private static void insertIfMissing(Connection connection,
                                        String kind,
                                        String slot,
                                        String content,
                                        int sortOrder,
                                        long now) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM ai_core_memory WHERE kind = ? AND slot = ?")) {
            query.setString(1, kind);
            query.setString(2, slot);
            try (ResultSet result = query.executeQuery()) {
                if (result.next()) {
                    return;
                }
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO ai_core_memory"
                        + "(kind, slot, content, readonly, sort_order, updated_at) "
                        + "VALUES(?, ?, ?, 0, ?, ?)")) {
            insert.setString(1, kind);
            insert.setString(2, slot);
            insert.setString(3, content == null ? "" : content);
            insert.setInt(4, sortOrder);
            insert.setLong(5, now);
            insert.executeUpdate();
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.trim().isEmpty()
                ? fallback : value.trim();
    }
}
