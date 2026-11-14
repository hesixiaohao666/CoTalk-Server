package server;

import ai.pro.AiProConfigField;
import ai.pro.AiProConfigMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Headless 配置目录：覆盖服务端全部可管理配置，并复用 AI Pro 元数据。
 */
public final class HeadlessConfigCatalog {

    public static final String GROUP_SYSTEM = "system";
    public static final String GROUP_SERVER = "server";
    public static final String GROUP_ROOM = "room";
    public static final String GROUP_STORAGE = "storage";
    public static final String GROUP_SECURITY = "security";
    public static final String GROUP_FEATURES = "features";
    public static final String GROUP_AI = "ai";
    public static final String GROUP_AIPRO = "aipro";

    private static final List<String> GROUPS = List.of(
            GROUP_SYSTEM,
            GROUP_SERVER,
            GROUP_ROOM,
            GROUP_STORAGE,
            GROUP_SECURITY,
            GROUP_FEATURES,
            GROUP_AI,
            GROUP_AIPRO);

    private static final Map<String, Field> FIELDS = new LinkedHashMap<>();

    static {
        text("config.version", "配置版本", GROUP_SYSTEM,
                "只读；由程序维护", false, false);
        add("server.port", "服务器端口", GROUP_SERVER,
                AiProConfigField.Type.INTEGER, 1, 65535, null, null,
                "", false, true);
        add("server.maxClients", "最大连接数", GROUP_SERVER,
                AiProConfigField.Type.INTEGER, 1, Integer.MAX_VALUE,
                null, null, "", false, true);
        add("server.joinTimeoutMs", "加入超时", GROUP_SERVER,
                AiProConfigField.Type.INTEGER, 1, Integer.MAX_VALUE,
                null, null, "毫秒", false, true);
        add("server.heartbeatTimeoutMs", "心跳超时", GROUP_SERVER,
                AiProConfigField.Type.INTEGER, 1, Integer.MAX_VALUE,
                null, null, "毫秒", false, true);
        add("server.reconnectGraceMs", "重连宽限", GROUP_SERVER,
                AiProConfigField.Type.INTEGER, 5000, 60000,
                null, null, "毫秒", false, true);

        secret("auth.key", "连接密钥", GROUP_SECURITY);

        text("room.name", "房间名称", GROUP_ROOM, "", false, true);
        text("room.announcement", "房间公告", GROUP_ROOM, "", false, true);
        text("room.avatar", "房间头像", GROUP_ROOM,
                "PNG/JPG 路径，留空使用默认样式", false, true);

        text("db.path", "数据库路径", GROUP_STORAGE, "", false, true);
        add("history.recent", "最近历史条数", GROUP_STORAGE,
                AiProConfigField.Type.INTEGER, 1, Integer.MAX_VALUE,
                null, null, "条", false, true);
        add("history.maxFetch", "最大拉取条数", GROUP_STORAGE,
                AiProConfigField.Type.INTEGER, 1, Integer.MAX_VALUE,
                null, null, "条", false, true);
        add("avatar.maxBytes", "头像大小上限", GROUP_STORAGE,
                AiProConfigField.Type.INTEGER, 1, Integer.MAX_VALUE,
                null, null, "字节", false, true);

        bool("features.image", "启用图片发送", GROUP_FEATURES);
        bool("features.voice", "启用语音发送", GROUP_FEATURES);
        bool("features.emoji", "启用表情包发送", GROUP_FEATURES);
        bool("push.enabled", "启用后台推送", GROUP_FEATURES);

        bool("admin.enabled", "启用管理员模式", GROUP_SECURITY);
        secret("admin.salt", "管理员 salt", GROUP_SECURITY);
        add("admin.iterations", "管理员迭代次数", GROUP_SECURITY,
                AiProConfigField.Type.INTEGER, 1, Integer.MAX_VALUE,
                null, null, "", false, true);
        secret("admin.verifier", "管理员 verifier", GROUP_SECURITY);

        bool("ai.enabled", "启用 AI 群友", GROUP_AI);
        secret("ai.apiKey", "AI API Key", GROUP_AI);
        text("ai.model", "AI 模型", GROUP_AI, "", false, true);
        text("ai.baseUrl", "AI Base URL", GROUP_AI, "", false, true);
        bool("ai.thinking", "启用思考模式", GROUP_AI);
        bool("ai.vision", "允许 AI 识图", GROUP_AI);
        bool("ai.systemMessages", "允许 AI 读取系统消息", GROUP_AI);
        text("ai.prompt.custom", "自定义提示词", GROUP_AI, "", false, true);
        text("ai.mentions", "AI @ 触发词", GROUP_AI, "逗号分隔", false, true);
        text("ai.keywords", "AI 参与关键词", GROUP_AI, "逗号分隔", false, true);
        chance("ai.keywordChance", "关键词回复概率");
        chance("ai.randomChance", "随机参与概率");
        chance("ai.peakChance", "峰时参与概率");
        chance("ai.offPeakChance", "谷时参与概率");
        text("ai.peak.timezone", "峰时判断时区", GROUP_AI,
                "IANA 时区，留空表示系统时区", false, true);
        text("ai.peak.days", "峰时日期规则", GROUP_AI,
                "例如 MON-FRI", false, true);
        text("ai.peak.periods", "峰时时段", GROUP_AI,
                "例如 09:00-12:00,14:00-18:00", false, true);
        add("ai.cooldownMs", "AI 回复冷却", GROUP_AI,
                AiProConfigField.Type.INTEGER, 0, Integer.MAX_VALUE,
                null, null, "毫秒", false, true);
        add("ai.maxContext", "AI 上下文条数", GROUP_AI,
                AiProConfigField.Type.INTEGER, 1, Integer.MAX_VALUE,
                null, null, "条", false, true);
        add("ai.timeoutMs", "AI 请求超时", GROUP_AI,
                AiProConfigField.Type.INTEGER, 1, Integer.MAX_VALUE,
                null, null, "毫秒", false, true);
        add("ai.maxTokens", "AI maxTokens", GROUP_AI,
                AiProConfigField.Type.INTEGER, 1, Integer.MAX_VALUE,
                null, null, "", false, true);
        add("ai.messageMaxChars", "AI 单条最大字符", GROUP_AI,
                AiProConfigField.Type.INTEGER, 1, Integer.MAX_VALUE,
                null, null, "字符", false, true);
        add("ai.maxMessages", "AI 单次最大条数", GROUP_AI,
                AiProConfigField.Type.INTEGER, 1, 10,
                null, null, "条", false, true);
        add("ai.replyDelayMs", "AI 多条间隔", GROUP_AI,
                AiProConfigField.Type.INTEGER, 0, Integer.MAX_VALUE,
                null, null, "毫秒", false, true);
        text("ai.name", "AI 昵称", GROUP_AI, "", false, true);
        text("ai.avatar", "AI 头像", GROUP_AI, "", false, true);

        for (AiProConfigField field : AiProConfigMeta.fields()) {
            FIELDS.put(field.key, new Field(field));
        }
    }

    private HeadlessConfigCatalog() {
    }

    public static List<String> groups() {
        return GROUPS;
    }

    public static List<Field> fields() {
        return Collections.unmodifiableList(new ArrayList<>(FIELDS.values()));
    }

    public static Field field(String key) {
        if (key == null) {
            return null;
        }
        Field field = FIELDS.get(key);
        if (field != null) {
            return field;
        }
        return FIELDS.get(key.trim().toLowerCase(Locale.ROOT));
    }

    public static List<Field> fieldsForGroup(String group) {
        if (group == null || group.trim().isEmpty()
                || "all".equalsIgnoreCase(group)) {
            return fields();
        }
        String target = group.trim();
        List<Field> result = new ArrayList<>();
        for (Field field : FIELDS.values()) {
            if (field.matchesGroup(target)) {
                result.add(field);
            }
        }
        return result;
    }

    public static String suggest(String key) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }
        String target = key.trim().toLowerCase(Locale.ROOT);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : FIELDS.keySet()) {
            int distance = distance(target, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        int threshold = Math.max(3, target.length() / 2);
        return bestDistance <= threshold ? best : null;
    }

    public static final class Field {

        public final String key;
        public final String label;
        public final String group;
        public final String subgroup;
        public final String hint;
        public final AiProConfigField.Type type;
        public final Integer minInteger;
        public final Integer maxInteger;
        public final Double minDecimal;
        public final Double maxDecimal;
        public final List<String> choices;
        public final boolean sensitive;
        public final boolean writable;
        private final AiProConfigField aiProField;

        private Field(String key,
                      String label,
                      String group,
                      String subgroup,
                      String hint,
                      AiProConfigField.Type type,
                      Integer minInteger,
                      Integer maxInteger,
                      Double minDecimal,
                      Double maxDecimal,
                      List<String> choices,
                      boolean sensitive,
                      boolean writable,
                      AiProConfigField aiProField) {
            this.key = key;
            this.label = label;
            this.group = group;
            this.subgroup = subgroup;
            this.hint = hint;
            this.type = type;
            this.minInteger = minInteger;
            this.maxInteger = maxInteger;
            this.minDecimal = minDecimal;
            this.maxDecimal = maxDecimal;
            this.choices = choices == null ? List.of() : List.copyOf(choices);
            this.sensitive = sensitive;
            this.writable = writable;
            this.aiProField = aiProField;
        }

        private Field(AiProConfigField field) {
            this(field.key, field.label, GROUP_AIPRO, field.group,
                    field.rangeHint(), field.type,
                    field.minInteger, field.maxInteger,
                    field.minDecimal, field.maxDecimal, field.choices,
                    field.sensitive, true, field);
        }

        public boolean matchesGroup(String group) {
            if (group == null || group.isEmpty()) {
                return true;
            }
            if (this.group.equalsIgnoreCase(group)) {
                return true;
            }
            return subgroup != null && subgroup.equalsIgnoreCase(group);
        }

        public String displayGroup() {
            return subgroup == null ? group : group + "/" + subgroup;
        }

        public String normalize(String raw) {
            if (aiProField != null) {
                return AiProConfigMeta.normalize(aiProField, raw);
            }
            String value = raw == null ? "" : raw.trim();
            switch (type) {
                case BOOLEAN:
                    if (!"true".equalsIgnoreCase(value)
                            && !"false".equalsIgnoreCase(value)) {
                        throw new IllegalArgumentException("请输入 true 或 false");
                    }
                    return value.toLowerCase(Locale.ROOT);
                case INTEGER:
                    int integer;
                    try {
                        integer = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("请输入整数");
                    }
                    if (minInteger != null && integer < minInteger
                            || maxInteger != null && integer > maxInteger) {
                        throw new IllegalArgumentException("必须在 "
                                + minInteger + "–" + maxInteger + " 之间");
                    }
                    return String.valueOf(integer);
                case DECIMAL:
                    double decimal;
                    try {
                        decimal = Double.parseDouble(value);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("请输入数字");
                    }
                    if (minDecimal != null && decimal < minDecimal
                            || maxDecimal != null && decimal > maxDecimal) {
                        throw new IllegalArgumentException("必须在 "
                                + minDecimal + "–" + maxDecimal + " 之间");
                    }
                    return value;
                case SECRET:
                    return value;
                case TEXT:
                case LIST:
                default:
                    if (!choices.isEmpty()) {
                        for (String choice : choices) {
                            if (choice.equalsIgnoreCase(value)) {
                                return choice;
                            }
                        }
                        throw new IllegalArgumentException("必须是 "
                                + String.join(" 或 ", choices));
                    }
                    return value;
            }
        }
    }

    private static void add(String key,
                            String label,
                            String group,
                            AiProConfigField.Type type,
                            Integer minInteger,
                            Integer maxInteger,
                            Double minDecimal,
                            Double maxDecimal,
                            String hint,
                            boolean sensitive,
                            boolean writable) {
        FIELDS.put(key, new Field(key, label, group, null, hint, type,
                minInteger, maxInteger, minDecimal, maxDecimal,
                null, sensitive, writable, null));
    }

    private static void bool(String key, String label, String group) {
        add(key, label, group, AiProConfigField.Type.BOOLEAN,
                null, null, null, null, "", false, true);
    }

    private static void chance(String key, String label) {
        add(key, label, GROUP_AI, AiProConfigField.Type.INTEGER,
                0, 100, null, null, "0–100", false, true);
    }

    private static void text(String key,
                             String label,
                             String group,
                             String hint,
                             boolean sensitive,
                             boolean writable) {
        add(key, label, group, AiProConfigField.Type.TEXT,
                null, null, null, null, hint, sensitive, writable);
    }

    private static void secret(String key, String label, String group) {
        add(key, label, group, AiProConfigField.Type.SECRET,
                null, null, null, null, "敏感值，读取时隐藏",
                true, true);
    }

    private static int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(
                        current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }
}
