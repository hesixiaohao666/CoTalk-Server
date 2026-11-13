package ai.pro;

import store.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * ai_emotion_state 单行持久化。
 */
public class EmotionStore {

    private final Database database;

    public EmotionStore(Database database) {
        this.database = database;
    }

    public EmotionState load() throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT mood, valence, arousal, intensity, updated_at, "
                            + "last_trigger_at, last_decay_at, note "
                            + "FROM ai_emotion_state WHERE id = 1");
                 ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    EmotionState state = new EmotionState();
                    state.mood = result.getString("mood");
                    state.valence = result.getDouble("valence");
                    state.arousal = result.getDouble("arousal");
                    state.intensity = result.getDouble("intensity");
                    state.updatedAt = result.getLong("updated_at");
                    state.lastTriggerAt = result.getLong("last_trigger_at");
                    state.lastDecayAt = result.getLong("last_decay_at");
                    state.note = result.getString("note");
                    return state;
                }
            }
            long now = System.currentTimeMillis();
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO ai_emotion_state("
                            + "id, mood, valence, arousal, intensity, "
                            + "updated_at, last_trigger_at, last_decay_at, note"
                            + ") VALUES(1,'平静',0,0.2,0.2,?,?,?,'')")) {
                insert.setLong(1, now);
                insert.setLong(2, 0);
                insert.setLong(3, now);
                insert.executeUpdate();
            }
            EmotionState state = new EmotionState();
            state.updatedAt = now;
            state.lastTriggerAt = 0;
            state.lastDecayAt = now;
            return state;
        }
    }

    public void save(EmotionState state) throws Exception {
        synchronized (database) {
            Connection connection = database.connection();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ai_emotion_state("
                            + "id, mood, valence, arousal, intensity, "
                            + "updated_at, last_trigger_at, last_decay_at, note"
                            + ") VALUES(1,?,?,?,?,?,?,?,?) "
                            + "ON CONFLICT(id) DO UPDATE SET "
                            + "mood=excluded.mood, valence=excluded.valence, "
                            + "arousal=excluded.arousal, "
                            + "intensity=excluded.intensity, "
                            + "updated_at=excluded.updated_at, "
                            + "last_trigger_at=excluded.last_trigger_at, "
                            + "last_decay_at=excluded.last_decay_at, "
                            + "note=excluded.note")) {
                statement.setString(1, state.mood);
                statement.setDouble(2, state.valence);
                statement.setDouble(3, state.arousal);
                statement.setDouble(4, state.intensity);
                statement.setLong(5, state.updatedAt);
                statement.setLong(6, state.lastTriggerAt);
                statement.setLong(7, state.lastDecayAt);
                statement.setString(8, state.note);
                statement.executeUpdate();
            }
        }
    }
}
