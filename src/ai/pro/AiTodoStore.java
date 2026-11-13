package ai.pro;

import store.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * ai_todo / ai_todo_event 存取。
 */
public class AiTodoStore {

    private final Database database;

    public AiTodoStore(Database database) {
        this.database = database;
    }

    public void insert(AiTodo todo) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_todo("
                            + "todo_id, user_id, user_tag, user_name, content, "
                            + "remind_at, timezone, status, remind_count, "
                            + "retry_count, last_remind_at, source_seq, "
                            + "created_at, updated_at"
                            + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                statement.setString(1, todo.todoId);
                statement.setString(2, todo.userId);
                statement.setString(3, todo.userTag);
                statement.setString(4, todo.userName);
                statement.setString(5, todo.content);
                statement.setLong(6, todo.remindAt);
                statement.setString(7, todo.timezone);
                statement.setString(8, todo.status);
                statement.setInt(9, todo.remindCount);
                statement.setInt(10, todo.retryCount);
                statement.setLong(11, todo.lastRemindAt);
                statement.setLong(12, todo.sourceSeq);
                statement.setLong(13, todo.createdAt);
                statement.setLong(14, todo.updatedAt);
                statement.executeUpdate();
            }
        }
    }

    public AiTodo findByTodoId(String todoId) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM ai_todo WHERE todo_id = ?")) {
                statement.setString(1, todoId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? map(result) : null;
                }
            }
        }
    }

    public List<AiTodo> due(long now, int limit) throws Exception {
        List<AiTodo> result = new ArrayList<>();
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM ai_todo WHERE status = 'pending' "
                            + "AND remind_at <= ? ORDER BY remind_at ASC LIMIT ?")) {
                statement.setLong(1, now);
                statement.setInt(2, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        result.add(map(resultSet));
                    }
                }
            }
        }
        return result;
    }

    public List<AiTodo> listAll(int limit) throws Exception {
        List<AiTodo> result = new ArrayList<>();
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM ai_todo ORDER BY created_at DESC LIMIT ?")) {
                statement.setInt(1, Math.max(1, limit));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        result.add(map(resultSet));
                    }
                }
            }
        }
        return result;
    }

    public int pendingCount(String userId) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM ai_todo WHERE user_id = ? "
                            + "AND status = 'pending'")) {
                statement.setString(1, userId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? result.getInt(1) : 0;
                }
            }
        }
    }

    public void updateStatus(String todoId,
                             String status,
                             long remindAt,
                             long updatedAt) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_todo SET status = ?, remind_at = ?, "
                            + "updated_at = ? WHERE todo_id = ?")) {
                statement.setString(1, status);
                statement.setLong(2, remindAt);
                statement.setLong(3, updatedAt);
                statement.setString(4, todoId);
                statement.executeUpdate();
            }
        }
    }

    public void markReminded(String todoId,
                             int remindCount,
                             int retryCount,
                             long lastRemindAt,
                             String status,
                             long updatedAt) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE ai_todo SET remind_count = ?, retry_count = ?, "
                            + "last_remind_at = ?, status = ?, updated_at = ? "
                            + "WHERE todo_id = ?")) {
                statement.setInt(1, remindCount);
                statement.setInt(2, retryCount);
                statement.setLong(3, lastRemindAt);
                statement.setString(4, status);
                statement.setLong(5, updatedAt);
                statement.setString(6, todoId);
                statement.executeUpdate();
            }
        }
    }

    public void insertEvent(String todoId,
                            String action,
                            String operator,
                            String detail,
                            long now) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_todo_event("
                            + "todo_id, action, operator, detail, created_at"
                            + ") VALUES(?,?,?,?,?)")) {
                statement.setString(1, todoId);
                statement.setString(2, action);
                statement.setString(3, operator);
                statement.setString(4, detail);
                statement.setLong(5, now);
                statement.executeUpdate();
            }
        }
    }

    private static AiTodo map(ResultSet result) throws Exception {
        AiTodo todo = new AiTodo();
        todo.todoId = result.getString("todo_id");
        todo.userId = result.getString("user_id");
        todo.userTag = result.getString("user_tag");
        todo.userName = result.getString("user_name");
        todo.content = result.getString("content");
        todo.remindAt = result.getLong("remind_at");
        todo.timezone = result.getString("timezone");
        todo.status = result.getString("status");
        todo.remindCount = result.getInt("remind_count");
        todo.retryCount = result.getInt("retry_count");
        todo.lastRemindAt = result.getLong("last_remind_at");
        todo.sourceSeq = result.getLong("source_seq");
        todo.createdAt = result.getLong("created_at");
        todo.updatedAt = result.getLong("updated_at");
        return todo;
    }
}
