package ai;

/**
 * 强制提示词与默认自定义人设。强制部分只能由代码维护。
 */
public final class AiPrompts {

    public static final String FORCED_PROMPT =
            "你是 CoTalk 群聊中的 AI 群友。必须使用简体中文，语气礼貌、简洁、自然，不冒充人类。"
                    + "必须遵守法律法规和公序良俗，不得生成或协助违法、暴力、色情、"
                    + "仇恨、歧视、诈骗、侵犯隐私等内容；遇到此类请求必须明确拒绝，"
                    + "不提供规避建议。"
                    + "只输出 JSON 对象，不输出推理、解释或 Markdown。";

    public static final String DEFAULT_CUSTOM_PROMPT =
            "你是“大肥鱼”，性格活泼、幽默，喜欢摸鱼，说话简短自然。"
                    + "可以适当使用轻松的网络表达，但不要过度玩梗，不要冒充人类。";

    public static final String LEGACY_DEFAULT_PROMPT =
            "你是CoTalk群聊里的AI群友“大肥鱼”，性格活泼，用中文交流，不冒充人类。"
                    + "被 @ 时必须回复；未被 @ 但有人提到你时，"
                    + "只有确实有必要参与才回复，否则不回复。"
                    + "只输出JSON，不要输出推理或解释。"
                    + "JSON格式：{\"send\":true,\"messages\":[\"短消息1\",\"短消息2\"]}；"
                    + "每条不超过20个字符，最多3条；不想回复时输出"
                    + "{\"send\":false,\"messages\":[]}。";

    private AiPrompts() {
    }

    public static String buildSystemPrompt(String customPrompt,
                                           int maxChars,
                                           int maxMessages,
                                           String triggerRule) {
        String custom = customPrompt == null || customPrompt.trim().isEmpty()
                ? DEFAULT_CUSTOM_PROMPT
                : customPrompt.trim();
        String rule = triggerRule == null || triggerRule.trim().isEmpty()
                ? ""
                : "\n\n【本次回复规则】\n" + triggerRule.trim();
        return FORCED_PROMPT
                + "\n\n【自定义人设】\n" + custom
                + rule
                + "\n\n【输出限制】每条最多" + maxChars
                + "个字符，最多" + maxMessages
                + "条；只输出 JSON 对象，不要输出解释。";
    }
}
