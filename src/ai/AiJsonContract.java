package ai;

/**
 * 旧引擎、AI Pro 与修复提示共用的 JSON 输出契约。
 */
public final class AiJsonContract {

    public static final String SEND_EXAMPLE =
            "{\"actions\":[{\"type\":\"text\",\"content\":\"短消息1\"}]}";
    public static final String EMOJI_EXAMPLE =
            "{\"type\":\"emoji\",\"id\":\"emo-xxxxxxxx\"}";
    public static final String NO_SEND_EXAMPLE = "{\"actions\":[]}";

    public static final String FORMAT_HINT =
            "必须严格输出 JSON 对象：\"actions\" 为动作数组，文字动作为 "
                    + "{\"type\":\"text\",\"content\":\"短消息\"}，"
                    + "表情动作为 " + EMOJI_EXAMPLE + "；"
                    + "正常示例：" + SEND_EXAMPLE + "；"
                    + "不发送时输出 " + NO_SEND_EXAMPLE + "。";

    public static final String OUTPUT_RULE =
            FORMAT_HINT + "表情动作只能使用候选列表中的 id，最多连续 3 个；"
                    + "即使不发送也必须返回非空 JSON，"
                    + "不得返回空字符串；只输出 JSON 对象，"
                    + "不输出推理、解释、Markdown 或代码块。";

    public static final String REPAIR_INSTRUCTION =
            "上一次输出为空或不是合法 JSON。请重新回答，"
                    + "并且只输出一个非空 JSON 对象，"
                    + "不要输出空内容、推理、解释、Markdown 或代码块。"
                    + "文字动作：" + SEND_EXAMPLE + "；"
                    + "表情动作：" + EMOJI_EXAMPLE + "；"
                    + "不发送：" + NO_SEND_EXAMPLE + "。";

    private AiJsonContract() {
    }
}
