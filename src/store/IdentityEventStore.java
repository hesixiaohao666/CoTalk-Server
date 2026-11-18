package store;

import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * 身份审计事件。密码/verifier 不进入事件表。
 */
public class IdentityEventStore {

    private final Database database;

    public IdentityEventStore(Database database) {
        this.database = database;
    }

    public void record(String identityId,
                       String username,
                       String action,
                       String operator,
                       String note) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO identity_event("
                            + "identity_id, username, action, operator, "
                            + "note, created_at"
                            + ") VALUES(?,?,?,?,?,?)")) {
                statement.setString(1, identityId);
                statement.setString(2, username);
                statement.setString(3, action);
                statement.setString(4, operator);
                statement.setString(5, note);
                statement.setLong(6, System.currentTimeMillis());
                statement.executeUpdate();
            }
        }
    }
}
