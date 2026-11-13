package ai.pro;

/**
 * AI 临时情绪状态。
 */
public class EmotionState {
    public String mood = "平静";
    public double valence;
    public double arousal = 0.2;
    public double intensity = 0.2;
    public long updatedAt;
    public long lastTriggerAt;
    public long lastDecayAt;
    public String note = "";
}
