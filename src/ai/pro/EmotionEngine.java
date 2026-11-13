package ai.pro;

import ai.AiReplySink;
import model.Member;
import model.Message;

import java.util.Locale;

/**
 * 本地情绪词典、AI 情绪融合、衰减与提示词注入。
 */
public class EmotionEngine {

    private final AiProConfig config;
    private final EmotionStore store;
    private final AiReplySink sink;
    private final String[] positive;
    private final String[] negative;
    private final String[] surprise;
    private final String[] intimate;
    private EmotionState state = new EmotionState();
    private int consecutiveNegative;

    public EmotionEngine(AiProConfig config,
                         EmotionStore store,
                         AiReplySink sink) {
        this.config = config;
        this.store = store;
        this.sink = sink;
        this.positive = split(config.getString(
                "ai.pro.emotion.lexicon.positive"));
        this.negative = split(config.getString(
                "ai.pro.emotion.lexicon.negative"));
        this.surprise = split(config.getString(
                "ai.pro.emotion.lexicon.surprise"));
        this.intimate = split(config.getString(
                "ai.pro.emotion.lexicon.intimate"));
    }

    public synchronized void start() {
        try {
            state = store.load();
            decayTo(System.currentTimeMillis(), true);
            store.save(state);
        } catch (Exception e) {
            sink.reportError("AI Pro 加载情绪失败：" + e.getMessage());
        }
    }

    public synchronized void onMessage(Member sender,
                                       Message message,
                                       boolean mentioned) {
        if (!enabled() || message == null
                || "system".equals(message.type)
                || ai.AiResponder.AI_USER_ID.equals(message.senderId)) {
            return;
        }
        long now = System.currentTimeMillis();
        decayTo(now, false);
        String content = message.content == null
                ? "" : message.content;
        boolean changed = false;
        boolean positiveHit = containsAny(content, positive);
        boolean negativeHit = containsAny(content, negative);
        boolean surpriseHit = containsAny(content, surprise);
        boolean intimateHit = containsAny(content, intimate);
        if (positiveHit) {
            state.valence = clampSigned(state.valence + 0.15);
            state.arousal = clamp(state.arousal + 0.05);
            state.intensity = clamp(state.intensity + 0.08);
            changed = true;
        }
        if (negativeHit) {
            state.valence = clampSigned(state.valence - 0.25);
            state.arousal = clamp(state.arousal + 0.10);
            state.intensity = clamp(state.intensity + 0.12);
            consecutiveNegative++;
            if (consecutiveNegative >= 2) {
                state.intensity = clamp(state.intensity + 0.05);
            }
            changed = true;
        } else {
            consecutiveNegative = 0;
        }
        if (surpriseHit) {
            state.arousal = clamp(state.arousal + 0.15);
            state.intensity = clamp(state.intensity + 0.10);
            changed = true;
        }
        if (intimateHit) {
            state.valence = clampSigned(state.valence + 0.15);
            state.arousal = clamp(state.arousal + 0.10);
            state.intensity = clamp(state.intensity + 0.10);
            changed = true;
        }
        if (mentioned) {
            state.arousal = clamp(state.arousal + 0.10);
            state.intensity = clamp(state.intensity + 0.05);
            changed = true;
        }
        if (changed) {
            state.mood = moodOf(state);
            state.lastTriggerAt = now;
            state.lastDecayAt = now;
            state.updatedAt = now;
            save();
        }
    }

    public synchronized void applyAiEmotion(String mood,
                                            double valence,
                                            double arousal,
                                            double intensity) {
        if (!enabled()) {
            return;
        }
        double weight = clamp(config.getDouble(
                "ai.pro.emotion.aiWeight"));
        state.valence = clampSigned((1 - weight) * state.valence
                + weight * clampSigned(valence));
        state.arousal = clamp((1 - weight) * state.arousal
                + weight * clamp(arousal));
        state.intensity = clamp(Math.max(state.intensity, intensity));
        if (mood != null && !mood.trim().isEmpty()) {
            state.mood = mood.trim();
        } else {
            state.mood = moodOf(state);
        }
        state.lastTriggerAt = System.currentTimeMillis();
        state.lastDecayAt = state.lastTriggerAt;
        state.updatedAt = state.lastTriggerAt;
        save();
    }

    public synchronized String promptSection() {
        if (!enabled() || state.intensity < 0.05) {
            return "";
        }
        double shown = Math.min(state.intensity,
                config.getDouble("ai.pro.emotion.promptMaxIntensity"));
        return "【当前情绪】\n"
                + "心情：" + state.mood + "；情绪强度："
                + String.format(Locale.ROOT, "%.2f", shown) + "。\n"
                + "表达要求：仅在措辞和语气上轻微体现，不得夸张、"
                + "不得改变身份、不得覆盖红线、不得影响 JSON 输出。";
    }

    public synchronized String statusText() {
        return "心情：" + state.mood + "，情绪强度："
                + String.format(Locale.ROOT, "%.2f", state.intensity);
    }

    public synchronized EmotionState getState() {
        return state;
    }

    public synchronized void reset() {
        state = new EmotionState();
        long now = System.currentTimeMillis();
        state.updatedAt = now;
        state.lastDecayAt = now;
        state.lastTriggerAt = 0;
        state.mood = "平静";
        save();
    }

    private void decayTo(long now, boolean force) {
        if (!enabled() || (!force && now - state.lastTriggerAt
                < config.getInt("ai.pro.emotion.halfLifeMinutes")
                * 60_000L)) {
            return;
        }
        long since = state.lastDecayAt <= 0
                ? now : state.lastDecayAt;
        double minutes = Math.max(0, now - since) / 60_000.0;
        double halfLife = Math.max(1,
                config.getInt("ai.pro.emotion.halfLifeMinutes"));
        double factor = Math.exp(-minutes * Math.log(2) / halfLife);
        if (factor < 0.999) {
            state.valence *= factor;
            state.arousal *= factor;
            state.intensity *= factor;
            if (state.intensity < 0.05) {
                state.valence = 0;
                state.arousal = 0.2;
                state.intensity = 0;
                state.mood = "平静";
            } else {
                state.mood = moodOf(state);
            }
            state.lastDecayAt = now;
            state.updatedAt = now;
        }
    }

    private void save() {
        try {
            store.save(state);
        } catch (Exception e) {
            sink.reportError("AI Pro 保存情绪失败：" + e.getMessage());
        }
    }

    private boolean enabled() {
        return config.getBoolean("ai.pro.emotion.enabled");
    }

    private String moodOf(EmotionState value) {
        if (value.valence > 0.4) {
            return value.arousal > 0.5 ? "兴奋" : "开心";
        }
        if (value.valence < -0.3) {
            return value.arousal > 0.5 ? "烦躁" : "低落";
        }
        if (value.arousal > 0.55) {
            return "惊讶";
        }
        return "平静";
    }

    private static boolean containsAny(String content, String[] words) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        for (String word : words) {
            if (!word.isEmpty()
                    && lower.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String[] split(String value) {
        if (value == null || value.trim().isEmpty()) {
            return new String[0];
        }
        String[] parts = value.split("[,，\\s]+");
        int count = 0;
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                parts[count++] = part.trim();
            }
        }
        String[] result = new String[count];
        System.arraycopy(parts, 0, result, 0, count);
        return result;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static double clampSigned(double value) {
        return Math.max(-1, Math.min(1, value));
    }
}
