package ai.pro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Pro 配置注册表：键、分组、类型、范围与提示的唯一来源。
 *
 * <p>新增配置时必须同时在这里注册；测试会校验注册表覆盖
 * {@link AiProConfig#keys()} 的全部键。</p>
 */
public final class AiProConfigMeta {

    public static final String GROUP_BASIC = "基础与轮次";
    public static final String GROUP_MEMORY = "记忆容量与清理";
    public static final String GROUP_EXTRACT = "抽取、合并与检索";
    public static final String GROUP_EMOTION = "情绪";
    public static final String GROUP_EMOJI = "表情";
    public static final String GROUP_TODO = "待办";
    public static final String GROUP_SEARCH = "搜索、时间与隐私";
    public static final String GROUP_WEIGHTS = "权重与高级参数";

    private static final List<String> GROUPS = List.of(
            GROUP_BASIC,
            GROUP_MEMORY,
            GROUP_EXTRACT,
            GROUP_EMOTION,
            GROUP_EMOJI,
            GROUP_TODO,
            GROUP_SEARCH,
            GROUP_WEIGHTS);

    private static final Map<String, AiProConfigField> FIELDS =
            new LinkedHashMap<>();

    static {
        // 基础与轮次
        bool("enabled", "启用 AI Pro", GROUP_BASIC,
                "由 AI 功能页的 AI群友Pro 开关控制", false);
        text("model", "Pro 模型", GROUP_BASIC,
                "留空表示沿用 AI 功能页的模型");
        integer("maxTokens", "Pro maxTokens", GROUP_BASIC, 0,
                Integer.MAX_VALUE, "",
                "0 表示使用基础模型默认值");
        integer("timeoutMs", "Pro 请求超时", GROUP_BASIC, 0,
                Integer.MAX_VALUE, "毫秒",
                "0 表示使用基础请求超时");
        integer("prompt.contextMessages", "Pro 上下文条数", GROUP_BASIC,
                1, 200, "条", "");
        integer("prompt.maxChars", "Pro 提示词上限", GROUP_BASIC,
                500, 100000, "字符", "");
        integer("prompt.maxMemoryChars", "Pro 记忆预算", GROUP_BASIC,
                0, 100000, "字符", "");
        list("owner.names", "主人昵称", GROUP_BASIC,
                "逗号分隔，可留空");
        bool("migrate.importHistory", "迁移历史消息", GROUP_BASIC,
                "首次启用 Pro 时导入历史消息", true);
        bool("migrate.backup", "迁移前备份", GROUP_BASIC,
                "迁移前备份旧记忆数据", true);
        integer("round.intervalMs", "轮次间隔", GROUP_BASIC, 1000,
                Integer.MAX_VALUE, "毫秒",
                "默认 1200000（20 分钟）");
        integer("round.messageCount", "轮次消息数", GROUP_BASIC,
                1, 100000, "条", "默认 10 条");
        integer("round.maxMessages", "轮次最大消息数", GROUP_BASIC,
                1, 10000, "条", "");
        integer("round.minMessages", "轮次最小消息数", GROUP_BASIC,
                1, 10000, "条", "");
        integer("round.pendingMax", "待处理轮次上限", GROUP_BASIC,
                0, 100, "轮", "");

        // 记忆容量与清理
        integer("deep.max", "深层记忆上限", GROUP_MEMORY,
                1, 100000, "条", "");
        integer("shallow.max", "浅层记忆上限", GROUP_MEMORY,
                1, 1000000, "条", "");
        integer("cache.max", "缓存记忆上限", GROUP_MEMORY,
                1, 10000000, "条", "");
        integer("cache.cleanupEveryRounds", "缓存清理间隔", GROUP_MEMORY,
                1, 10000, "轮", "");
        decimal("cache.cleanupTargetRatio", "缓存清理目标比例", GROUP_MEMORY,
                0.1, 1.0, "", "");
        integer("cache.ttlDays", "缓存 TTL", GROUP_MEMORY,
                1, 36500, "天", "");
        integer("cache.ttlMinDays", "缓存最短 TTL", GROUP_MEMORY,
                1, 36500, "天", "");
        decimal("compress.keepRatio", "压缩保留比例", GROUP_MEMORY,
                0.1, 1.0, "", "");
        integer("layer.cooldownMs", "层级迁移冷却", GROUP_MEMORY,
                0, Integer.MAX_VALUE, "毫秒", "");
        integer("layer.maxMovesPerRound", "每轮最大迁移数", GROUP_MEMORY,
                0, 100000, "条", "");
        integer("memory.maxAccessCount", "访问计数上限", GROUP_MEMORY,
                1, Integer.MAX_VALUE, "次", "");

        // 抽取、合并与检索
        integer("extract.maxPerRound", "每轮最多抽取", GROUP_EXTRACT,
                0, 100, "条", "");
        integer("extract.maxTokens", "抽取 maxTokens", GROUP_EXTRACT,
                1, Integer.MAX_VALUE, "", "");
        integer("extract.maxChars", "抽取内容最大字符", GROUP_EXTRACT,
                1, 10000, "字符", "");
        integer("extract.minChars", "抽取内容最小字符", GROUP_EXTRACT,
                1, 10000, "字符", "");
        decimal("extract.minConfidence", "抽取最低置信度", GROUP_EXTRACT,
                0.0, 1.0, "", "");
        integer("extract.maxKeywords", "每条最多关键词", GROUP_EXTRACT,
                0, 100, "个", "");
        integer("extract.maxTags", "每条最多标签", GROUP_EXTRACT,
                0, 100, "个", "");
        bool("review.enabled", "启用抽取复核", GROUP_EXTRACT,
                "对低置信度记忆进行二次复核", true);
        integer("review.max", "复核队列上限", GROUP_EXTRACT,
                0, 1000, "条", "");
        decimal("merge.similarity", "合并相似度阈值", GROUP_EXTRACT,
                0.0, 1.0, "", "");
        integer("merge.maxKeywords", "合并后最多关键词", GROUP_EXTRACT,
                0, 1000, "个", "");
        integer("merge.maxTags", "合并后最多标签", GROUP_EXTRACT,
                0, 1000, "个", "");
        integer("retrieve.deep", "深层检索条数", GROUP_EXTRACT,
                0, 1000, "条", "");
        integer("retrieve.shallow", "浅层检索条数", GROUP_EXTRACT,
                0, 1000, "条", "");
        integer("retrieve.cache", "缓存检索条数", GROUP_EXTRACT,
                0, 1000, "条", "");
        integer("retrieve.maxTotal", "检索总条数上限", GROUP_EXTRACT,
                1, 1000, "条", "");
        integer("retrieve.maxChars", "检索内容字符上限", GROUP_EXTRACT,
                0, 100000, "字符", "");
        integer("retrieve.queryContextMessages", "检索上下文条数",
                GROUP_EXTRACT, 1, 100, "条", "");
        decimal("retrieve.minTokenScore", "检索最低词元分", GROUP_EXTRACT,
                0.0, 1.0, "", "");
        decimal("retrieve.dedupeSimilarity", "检索去重相似度", GROUP_EXTRACT,
                0.0, 1.0, "", "");

        // 情绪
        bool("emotion.enabled", "启用情绪系统", GROUP_EMOTION,
                "让 AI 的语气随群聊情绪变化", true);
        integer("emotion.decayRounds", "情绪衰减轮次", GROUP_EMOTION,
                1, 100, "轮", "");
        integer("emotion.halfLifeMinutes", "情绪半衰期", GROUP_EMOTION,
                1, 525600, "分钟", "");
        decimal("emotion.aiWeight", "AI 情绪权重", GROUP_EMOTION,
                0.0, 1.0, "", "");
        decimal("emotion.memoryBaselineWeight", "记忆情绪基线权重",
                GROUP_EMOTION, 0.0, 1.0, "", "");
        decimal("emotion.promptMaxIntensity", "提示词情绪强度上限",
                GROUP_EMOTION, 0.0, 1.0, "", "");
        list("emotion.lexicon.positive", "积极情绪词", GROUP_EMOTION,
                "逗号分隔");
        list("emotion.lexicon.negative", "消极情绪词", GROUP_EMOTION,
                "逗号分隔");
        list("emotion.lexicon.surprise", "惊讶情绪词", GROUP_EMOTION,
                "逗号分隔");
        list("emotion.lexicon.intimate", "亲密情绪词", GROUP_EMOTION,
                "逗号分隔");

        // 表情
        bool("emoji.enabled", "启用表情系统", GROUP_EMOJI,
                "允许 AI 选择并发送表情", true);
        bool("emoji.autoImport", "自动保存用户表情", GROUP_EMOJI,
                "收到用户表情时自动收集", true);
        bool("emoji.skipReview", "无需管理员审核", GROUP_EMOJI,
                "开启后新表情直接可用；关闭时先进入待审核", true);
        integer("emoji.cooldownRounds", "表情冷却轮次", GROUP_EMOJI,
                0, 10000, "轮", "");
        integer("emoji.topCandidates", "候选表情数", GROUP_EMOJI,
                1, 50, "个", "");
        integer("emoji.candidatePool", "候选池大小", GROUP_EMOJI,
                1, 10000, "个", "");
        integer("emoji.maxBytes", "单文件上限", GROUP_EMOJI,
                1, 50 * 1024 * 1024, "字节", "");
        integer("emoji.maxGifBytes", "GIF 上限", GROUP_EMOJI,
                1, 100 * 1024 * 1024, "字节", "");
        list("emoji.sensitiveTags", "敏感标签", GROUP_EMOJI,
                "逗号分隔");

        // 待办
        bool("todo.enabled", "启用待办系统", GROUP_TODO,
                "允许 AI 创建与提醒待办", true);
        integer("todo.maxReminders", "单条最多提醒", GROUP_TODO,
                1, 20, "次", "");
        integer("todo.maxRetries", "提醒最大重试", GROUP_TODO,
                0, 5, "次", "");
        integer("todo.maxCreatePerRound", "每轮最多创建", GROUP_TODO,
                0, 100, "条", "");
        integer("todo.maxActionsPerRound", "每轮最多操作", GROUP_TODO,
                0, 100, "条", "");
        integer("todo.maxRemindersPerRound", "每轮最多提醒", GROUP_TODO,
                0, 100, "条", "");
        integer("todo.maxPendingPerUser", "每人待办上限", GROUP_TODO,
                1, 1000, "条", "");
        integer("todo.maxChars", "待办内容最大字符", GROUP_TODO,
                1, 1000, "字符", "");
        text("todo.timezone", "待办时区", GROUP_TODO,
                "IANA 时区，例如 Asia/Shanghai");
        bool("todo.confirmDraft", "确认后再创建", GROUP_TODO,
                "先发送确认草稿，用户确认后创建待办", true);
        integer("todo.draftTtlMinutes", "草稿有效期", GROUP_TODO,
                1, 1440, "分钟", "");
        integer("todo.missWindowHours", "错过提醒窗口", GROUP_TODO,
                0, 720, "小时", "");
        bool("todo.includeContentInGroup", "群内显示待办内容",
                GROUP_TODO, "关闭后群内只提示有提醒", true);
        list("todo.triggers", "待办触发词", GROUP_TODO,
                "逗号分隔");

        // 搜索、时间与隐私
        bool("search.enabled", "启用联网搜索", GROUP_SEARCH,
                "仅在消息需要最新信息时按需搜索", true);
        integer("search.dailyLimit", "每日搜索额度", GROUP_SEARCH,
                0, 100000, "次", "0 表示不限制");
        text("search.baseUrl", "搜索 API 地址", GROUP_SEARCH,
                "默认 DeepSeek Search API");
        integer("search.count", "搜索结果条数", GROUP_SEARCH,
                1, 20, "条", "");
        bool("search.needContent", "搜索返回正文", GROUP_SEARCH,
                "请求搜索结果时附带正文", true);
        integer("search.timeoutMs", "搜索超时", GROUP_SEARCH,
                1000, 60000, "毫秒", "");
        list("search.triggers", "搜索触发词", GROUP_SEARCH,
                "逗号分隔");
        integer("search.maxQueryChars", "查询词最大字符", GROUP_SEARCH,
                1, 128, "字符", "");
        bool("time.enabled", "感知本地时间", GROUP_SEARCH,
                "向 AI 提供服务器本地日期、时间与星期", true);
        text("time.timezone", "本地时区", GROUP_SEARCH,
                "IANA 时区，留空表示系统时区");
        textChoices("privacy.mode", "隐私处理方式", GROUP_SEARCH,
                List.of("drop", "redact"), "drop 丢弃，redact 脱敏");
        list("privacy.patterns", "隐私正则", GROUP_SEARCH,
                "逗号分隔");
        bool("privacy.allowPhone", "允许手机号", GROUP_SEARCH,
                "关闭时手机号会按隐私方式处理", true);

        // 权重与高级参数
        decimal("retrieve.w.token", "检索-词元权重", GROUP_WEIGHTS,
                0.0, 1.0, "", "所有权重之和应接近 1");
        decimal("retrieve.w.importance", "检索-重要度权重", GROUP_WEIGHTS,
                0.0, 1.0, "", "");
        decimal("retrieve.w.tag", "检索-标签权重", GROUP_WEIGHTS,
                0.0, 1.0, "", "");
        decimal("retrieve.w.recency", "检索-新鲜度权重", GROUP_WEIGHTS,
                0.0, 1.0, "", "");
        decimal("cacheToShallow.importance", "缓存升浅层重要度",
                GROUP_WEIGHTS, 0.0, 1.0, "", "");
        integer("cacheToShallow.hits", "缓存升浅层命中数", GROUP_WEIGHTS,
                0, 100000, "次", "");
        decimal("shallowToDeep.importance", "浅层升深层重要度",
                GROUP_WEIGHTS, 0.0, 1.0, "", "");
        integer("shallowToDeep.stableDays", "浅层升深层稳定天数",
                GROUP_WEIGHTS, 0, 36500, "天", "");
        integer("shallowToDeep.hits", "浅层升深层命中数", GROUP_WEIGHTS,
                0, 100000, "次", "");
        decimal("deepToShallow.importance", "深层降浅层重要度",
                GROUP_WEIGHTS, 0.0, 1.0, "", "");
        integer("deepToShallow.idleDays", "深层降浅层闲置天数",
                GROUP_WEIGHTS, 0, 36500, "天", "");
        decimal("score.w.explicit", "评分-显式权重", GROUP_WEIGHTS,
                0.0, 1.0, "", "所有权重之和应接近 1");
        decimal("score.w.access", "评分-访问权重", GROUP_WEIGHTS,
                0.0, 1.0, "", "");
        decimal("score.w.trust", "评分-可信度权重", GROUP_WEIGHTS,
                0.0, 1.0, "", "");
        decimal("score.w.freshness", "评分-新鲜度权重", GROUP_WEIGHTS,
                0.0, 1.0, "", "");
        decimal("score.w.task", "评分-任务相关权重", GROUP_WEIGHTS,
                0.0, 1.0, "", "");
        decimal("score.w.emotion", "评分-情绪权重", GROUP_WEIGHTS,
                0.0, 1.0, "", "");
        decimal("evict.w.importance", "淘汰-重要度权重", GROUP_WEIGHTS,
                0.0, 1.0, "", "所有权重之和应接近 1");
        decimal("evict.w.unaccessed", "淘汰-未访问权重", GROUP_WEIGHTS,
                0.0, 1.0, "", "");
        decimal("evict.w.trust", "淘汰-可信度权重", GROUP_WEIGHTS,
                0.0, 1.0, "", "");
        decimal("evict.w.reconstruct", "淘汰-可重建权重", GROUP_WEIGHTS,
                0.0, 1.0, "", "");
        decimal("evict.w.redundancy", "淘汰-冗余度权重", GROUP_WEIGHTS,
                0.0, 1.0, "", "");
        integer("evict.unaccessedDays", "淘汰未访问天数", GROUP_WEIGHTS,
                0, 36500, "天", "");
    }

    private AiProConfigMeta() {
    }

    public static List<String> groups() {
        return GROUPS;
    }

    public static List<AiProConfigField> fields() {
        return Collections.unmodifiableList(new ArrayList<>(FIELDS.values()));
    }

    public static List<AiProConfigField> advancedFields() {
        List<AiProConfigField> fields = new ArrayList<>();
        for (AiProConfigField field : FIELDS.values()) {
            if (field.advanced) {
                fields.add(field);
            }
        }
        return fields;
    }

    public static List<AiProConfigField> fieldsForGroup(String group) {
        List<AiProConfigField> fields = new ArrayList<>();
        for (AiProConfigField field : FIELDS.values()) {
            if (field.advanced && field.group.equals(group)) {
                fields.add(field);
            }
        }
        return fields;
    }

    public static AiProConfigField field(String key) {
        return FIELDS.get(key);
    }

    public static List<String> keys() {
        return Collections.unmodifiableList(new ArrayList<>(FIELDS.keySet()));
    }

    /**
     * 校验并规范化单个值；失败时抛出可直接展示给用户的消息。
     */
    public static String normalize(AiProConfigField field, String raw) {
        String value = raw == null ? "" : raw.trim();
        switch (field.type) {
            case BOOLEAN:
                if (!"true".equalsIgnoreCase(value)
                        && !"false".equalsIgnoreCase(value)) {
                    throw new IllegalArgumentException("请输入 true 或 false");
                }
                return value.toLowerCase(java.util.Locale.ROOT);
            case INTEGER:
                int integer;
                try {
                    integer = Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("请输入整数");
                }
                if (field.minInteger != null && integer < field.minInteger
                        || field.maxInteger != null
                        && integer > field.maxInteger) {
                    throw new IllegalArgumentException("必须在 "
                            + field.minInteger + "–" + field.maxInteger
                            + " 之间");
                }
                return String.valueOf(integer);
            case DECIMAL:
                double decimal;
                try {
                    decimal = Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("请输入数字");
                }
                if (field.minDecimal != null && decimal < field.minDecimal
                        || field.maxDecimal != null
                        && decimal > field.maxDecimal) {
                    throw new IllegalArgumentException("必须在 "
                            + field.minDecimal + "–" + field.maxDecimal
                            + " 之间");
                }
                return value;
            case SECRET:
                return raw == null ? "" : raw;
            case TEXT:
            case LIST:
            default:
                if (!field.choices.isEmpty()) {
                    for (String choice : field.choices) {
                        if (choice.equalsIgnoreCase(value)) {
                            return choice;
                        }
                    }
                    throw new IllegalArgumentException("必须是 "
                            + String.join(" 或 ", field.choices));
                }
                return value;
        }
    }

    private static void bool(String suffix,
                             String label,
                             String group,
                             String hint,
                             boolean advanced) {
        put(new AiProConfigField(key(suffix), label, group,
                AiProConfigField.Type.BOOLEAN, null, null, null, null,
                null, "", hint, advanced, false, true));
    }

    private static void integer(String suffix,
                                String label,
                                String group,
                                int min,
                                int max,
                                String unit,
                                String hint) {
        put(new AiProConfigField(key(suffix), label, group,
                AiProConfigField.Type.INTEGER, min, max, null, null,
                null, unit, hint, true, false, true));
    }

    private static void decimal(String suffix,
                                String label,
                                String group,
                                double min,
                                double max,
                                String unit,
                                String hint) {
        put(new AiProConfigField(key(suffix), label, group,
                AiProConfigField.Type.DECIMAL, null, null, min, max,
                null, unit, hint, true, false, true));
    }

    private static void text(String suffix,
                             String label,
                             String group,
                             String hint) {
        put(new AiProConfigField(key(suffix), label, group,
                AiProConfigField.Type.TEXT, null, null, null, null,
                null, "", hint, true, false, true));
    }

    private static void textChoices(String suffix,
                                    String label,
                                    String group,
                                    List<String> choices,
                                    String hint) {
        put(new AiProConfigField(key(suffix), label, group,
                AiProConfigField.Type.TEXT, null, null, null, null,
                choices, "", hint, true, false, true));
    }

    private static void list(String suffix,
                             String label,
                             String group,
                             String hint) {
        put(new AiProConfigField(key(suffix), label, group,
                AiProConfigField.Type.LIST, null, null, null, null,
                null, "", hint, true, false, true));
    }

    private static String key(String suffix) {
        return "ai.pro." + suffix;
    }

    private static void put(AiProConfigField field) {
        if (!GROUPS.contains(field.group)) {
            throw new IllegalStateException("未知配置分组：" + field.group);
        }
        if (FIELDS.put(field.key, field) != null) {
            throw new IllegalStateException("重复配置键：" + field.key);
        }
    }
}
