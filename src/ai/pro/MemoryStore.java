package ai.pro;

import store.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * ai_memory / ai_memory_audit / ai_pro_meta / ai_round 的基础读写。
 */
public class MemoryStore {

    private final Database database;

    public MemoryStore(Database database) {
        this.database = database;
    }

    public void insert(Memory memory) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_memory("
                            + "memory_id, layer, status, category, content, "
                            + "content_hash, keywords, tags, subject_id, "
                            + "subject_name, source_type, source_trust, "
                            + "explicit_important, access_count, last_access_at, "
                            + "created_at, updated_at, stable_since, "
                            + "task_relevance, emotional_salience, "
                            + "reconstructability, redundancy, importance, "
                            + "confidence, expires_at, merged_into, audit_note"
                            + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                int i = 1;
                statement.setString(i++, memory.memoryId);
                statement.setString(i++, memory.layer.dbValue);
                statement.setString(i++, memory.status);
                statement.setString(i++, memory.category);
                statement.setString(i++, memory.content);
                statement.setString(i++, memory.contentHash);
                statement.setString(i++, memory.keywords);
                statement.setString(i++, memory.tags);
                statement.setString(i++, emptyToNull(memory.subjectId));
                statement.setString(i++, emptyToNull(memory.subjectName));
                statement.setString(i++, memory.source.dbValue);
                statement.setDouble(i++, memory.sourceTrust);
                statement.setInt(i++, memory.explicitImportant ? 1 : 0);
                statement.setInt(i++, memory.accessCount);
                statement.setLong(i++, memory.lastAccessAt);
                statement.setLong(i++, memory.createdAt);
                statement.setLong(i++, memory.updatedAt);
                statement.setLong(i++, memory.stableSince);
                statement.setDouble(i++, memory.taskRelevance);
                statement.setDouble(i++, memory.emotionalSalience);
                statement.setDouble(i++, memory.reconstructability);
                statement.setDouble(i++, memory.redundancy);
                statement.setDouble(i++, memory.importance);
                statement.setDouble(i++, memory.confidence);
                if (memory.expiresAt == null) {
                    statement.setNull(i++, Types.INTEGER);
                } else {
                    statement.setLong(i++, memory.expiresAt);
                }
                statement.setString(i++, emptyToNull(memory.mergedInto));
                statement.setString(i++, emptyToNull(memory.auditNote));
                statement.executeUpdate();
            }
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT id FROM ai_memory WHERE memory_id = ?")) {
                query.setString(1, memory.memoryId);
                try (ResultSet result = query.executeQuery()) {
                    if (result.next()) {
                        memory.id = result.getLong(1);
                    }
                }
            }
        }
    }

    public List<Memory> activeMemories() throws SQLException {
        List<Memory> result = new ArrayList<>();
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM ai_memory WHERE status = 'active' "
                            + "ORDER BY importance DESC, id ASC");
                 ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(map(resultSet));
                }
            }
        }
        return result;
    }

    public Memory findByHash(String hash) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM ai_memory WHERE content_hash = ? "
                            + "AND status = 'active' LIMIT 1")) {
                statement.setString(1, hash);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? map(result) : null;
                }
            }
        }
    }

    public Memory findByMemoryId(String memoryId) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM ai_memory WHERE memory_id = ?")) {
                statement.setString(1, memoryId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? map(result) : null;
                }
            }
        }
    }

    public void updateMerge(Memory memory) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_memory SET content = ?, content_hash = ?, "
                            + "keywords = ?, tags = ?, explicit_important = ?, "
                            + "source_type = ?, source_trust = ?, "
                            + "access_count = ?, last_access_at = ?, "
                            + "created_at = ?, updated_at = ?, "
                            + "task_relevance = ?, emotional_salience = ?, "
                            + "reconstructability = ?, redundancy = ?, "
                            + "importance = ?, confidence = ?, audit_note = ? "
                            + "WHERE id = ?")) {
                int i = 1;
                statement.setString(i++, memory.content);
                statement.setString(i++, memory.contentHash);
                statement.setString(i++, memory.keywords);
                statement.setString(i++, memory.tags);
                statement.setInt(i++, memory.explicitImportant ? 1 : 0);
                statement.setString(i++, memory.source.dbValue);
                statement.setDouble(i++, memory.sourceTrust);
                statement.setInt(i++, memory.accessCount);
                statement.setLong(i++, memory.lastAccessAt);
                statement.setLong(i++, memory.createdAt);
                statement.setLong(i++, memory.updatedAt);
                statement.setDouble(i++, memory.taskRelevance);
                statement.setDouble(i++, memory.emotionalSalience);
                statement.setDouble(i++, memory.reconstructability);
                statement.setDouble(i++, memory.redundancy);
                statement.setDouble(i++, memory.importance);
                statement.setDouble(i++, memory.confidence);
                statement.setString(i++, emptyToNull(memory.auditNote));
                statement.setLong(i, memory.id);
                statement.executeUpdate();
            }
        }
    }

    public void updateImportance(Memory memory) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_memory SET importance = ?, updated_at = ?, "
                            + "last_access_at = ?, access_count = ? "
                            + "WHERE id = ?")) {
                statement.setDouble(1, memory.importance);
                statement.setLong(2, memory.updatedAt);
                statement.setLong(3, memory.lastAccessAt);
                statement.setInt(4, memory.accessCount);
                statement.setLong(5, memory.id);
                statement.executeUpdate();
            }
        }
    }

    /**
     * 检索命中：同一批次同一 memory 只更新一次。
     */
    public void recordHits(Collection<String> memoryIds,
                           long now,
                           AiProConfig config) throws SQLException {
        if (memoryIds == null || memoryIds.isEmpty()) {
            return;
        }
        synchronized (database) {
            Connection connection = database.connection();
            for (String memoryId : memoryIds) {
                Memory memory = findByMemoryIdLocked(connection, memoryId);
                if (memory == null) {
                    continue;
                }
                int max = config.getInt("ai.pro.memory.maxAccessCount");
                memory.accessCount = Math.min(max, memory.accessCount + 1);
                memory.lastAccessAt = now;
                memory.importance = MemoryScoring.importance(memory, config, now);
                if (memory.layer == MemoryLayer.CACHE) {
                    memory.expiresAt = now
                            + config.getInt("ai.pro.cache.ttlDays") * 86_400_000L;
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE ai_memory SET access_count = ?, "
                                + "last_access_at = ?, "
                                + "importance = ?, expires_at = ? WHERE id = ?")) {
                    statement.setInt(1, memory.accessCount);
                    statement.setLong(2, memory.lastAccessAt);
                    statement.setDouble(3, memory.importance);
                    if (memory.expiresAt == null) {
                        statement.setNull(4, Types.INTEGER);
                    } else {
                        statement.setLong(4, memory.expiresAt);
                    }
                    statement.setLong(5, memory.id);
                    statement.executeUpdate();
                }
            }
        }
    }

    private Memory findByMemoryIdLocked(Connection connection, String memoryId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM ai_memory WHERE memory_id = ?")) {
            statement.setString(1, memoryId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? map(result) : null;
            }
        }
    }

    public void insertAudit(String memoryId,
                            String action,
                            MemoryLayer layerBefore,
                            MemoryLayer layerAfter,
                            String beforeJson,
                            String afterJson,
                            String operator,
                            String note,
                            long now) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_memory_audit("
                            + "memory_id, action, layer_before, layer_after, "
                            + "before_json, after_json, operator, note, created_at"
                            + ") VALUES(?,?,?,?,?,?,?,?,?)")) {
                statement.setString(1, emptyToNull(memoryId));
                statement.setString(2, action);
                statement.setString(3, layerBefore == null
                        ? null : layerBefore.dbValue);
                statement.setString(4, layerAfter == null
                        ? null : layerAfter.dbValue);
                statement.setString(5, beforeJson);
                statement.setString(6, afterJson);
                statement.setString(7, operator);
                statement.setString(8, note);
                statement.setLong(9, now);
                statement.executeUpdate();
            }
        }
    }

    public String getMeta(String key, String fallback) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT value FROM ai_pro_meta WHERE \"key\" = ?")) {
                statement.setString(1, key);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getString(1) : fallback;
                }
            }
        }
    }

    public void setMeta(String key, String value) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_pro_meta(\"key\", value, updated_at) "
                            + "VALUES(?,?,?) ON CONFLICT(\"key\") DO UPDATE SET "
                            + "value = excluded.value, "
                            + "updated_at = excluded.updated_at")) {
                statement.setString(1, key);
                statement.setString(2, value);
                statement.setLong(3, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }

    public void insertRound(long startedAt,
                            long finishedAt,
                            String triggerType,
                            long fromSeq,
                            long toSeq,
                            int messageCount,
                            int extracted,
                            int inserted,
                            int merged,
                            String status,
                            String error) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_round("
                            + "started_at, finished_at, trigger_type, from_seq, "
                            + "to_seq, message_count, extracted_count, "
                            + "inserted_count, merged_count, status, error"
                            + ") VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                statement.setLong(1, startedAt);
                statement.setLong(2, finishedAt);
                statement.setString(3, triggerType);
                statement.setLong(4, fromSeq);
                statement.setLong(5, toSeq);
                statement.setInt(6, messageCount);
                statement.setInt(7, extracted);
                statement.setInt(8, inserted);
                statement.setInt(9, merged);
                statement.setString(10, status);
                statement.setString(11, error);
                statement.executeUpdate();
            }
        }
    }

    public int countActive(MemoryLayer layer) throws SQLException {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM ai_memory "
                            + "WHERE status = 'active' AND layer = ?")) {
                statement.setString(1, layer.dbValue);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getInt(1) : 0;
                }
            }
        }
    }

    private static Memory map(ResultSet result) throws SQLException {
        Memory memory = new Memory();
        memory.id = result.getLong("id");
        memory.memoryId = result.getString("memory_id");
        memory.layer = MemoryLayer.fromDb(result.getString("layer"));
        memory.status = result.getString("status");
        memory.category = result.getString("category");
        memory.content = result.getString("content");
        memory.contentHash = result.getString("content_hash");
        memory.keywords = result.getString("keywords");
        memory.tags = result.getString("tags");
        memory.subjectId = nullToEmpty(result.getString("subject_id"));
        memory.subjectName = nullToEmpty(result.getString("subject_name"));
        memory.source = MemorySource.fromDb(result.getString("source_type"));
        memory.sourceTrust = result.getDouble("source_trust");
        memory.explicitImportant = result.getInt("explicit_important") == 1;
        memory.accessCount = result.getInt("access_count");
        memory.lastAccessAt = result.getLong("last_access_at");
        memory.createdAt = result.getLong("created_at");
        memory.updatedAt = result.getLong("updated_at");
        memory.stableSince = result.getLong("stable_since");
        memory.taskRelevance = result.getDouble("task_relevance");
        memory.emotionalSalience = result.getDouble("emotional_salience");
        memory.reconstructability = result.getDouble("reconstructability");
        memory.redundancy = result.getDouble("redundancy");
        memory.importance = result.getDouble("importance");
        memory.confidence = result.getDouble("confidence");
        long expires = result.getLong("expires_at");
        memory.expiresAt = result.wasNull() ? null : expires;
        memory.mergedInto = nullToEmpty(result.getString("merged_into"));
        memory.auditNote = nullToEmpty(result.getString("audit_note"));
        return memory;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
