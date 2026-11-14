package ai;

/**
 * 旧引擎、AI Pro 与修复提示共用的 JSON 输出契约。
 */
public final class AiJsonContract {

    public static final String SEND_EXAMPLE =
            "{\"send\":true,\"messages\":[\"短消息1\"]}";
    public static final String NO_SEND_EXAMPLE =
            "{\"send\":false,\"messages\":[]}";

    public static final String FORMAT_HINT =
            "必须严格输出 JSON 对象：" + SEND_EXAMPLE + "；"
                    + "不发送时输出 " + NO_SEND_EXAMPLE + "。";

    public static final String OUTPUT_RULE =
            FORMAT_HINT + "即使不发送也必须返回非空 JSON，"
                    + "不得返回空字符串；只输出 JSON 对象，"
                    + "不输出推理、解释、Markdown 或代码块。";

    public static final String REPAIR_INSTRUCTION =
            "上一次输出为空或不是合法 JSON。请重新回答，"
                    + "并且只输出一个非空 JSON 对象，"
                    + "不要输出空内容、推理、解释、Markdown 或代码块。"
                    + "正常发送：" + SEND_EXAMPLE + "；"
                    + "不发送：" + NO_SEND_EXAMPLE + "。";

    private AiJsonContract() {
    }
}
