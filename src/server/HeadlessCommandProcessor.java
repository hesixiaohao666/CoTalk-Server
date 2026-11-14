package server;

import ai.pro.AiProAdminService;
import ai.pro.AiTodo;
import ai.pro.Memory;
import ai.pro.MemoryLayer;
import model.Member;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * 终端命令解析与分发。配置命令只修改内存副本，保存后需重启服务生效。
 */
public final class HeadlessCommandProcessor {

    private static final String OPERATOR = "admin-headless";

    private final ServerRuntime runtime;
    private final Path configPath;
    private final Consumer<String> logger;
    private final ConfigSession config;
    private PendingAction pendingAction;
    private String pendingDescription;

    public HeadlessCommandProcessor(ServerRuntime runtime,
                                    Path configPath,
                                    ServerConfig initialConfig,
                                    Consumer<String> log) {
        this.runtime = runtime;
        this.configPath = configPath;
        this.logger = log;
        this.config = new ConfigSession(configPath, initialConfig);
    }

    private void log(String message) {
        logger.accept(message);
    }

    /**
     * @return false 表示退出终端循环。
     */
    public boolean handle(String line) {
        String command = line == null ? "" : line.trim();
        if (command.isEmpty()) {
            return true;
        }
        if (pendingAction != null) {
            resolvePending(command);
            return true;
        }

        String[] parts = command.split("\\s+", 2);
        String root = parts[0].toLowerCase(Locale.ROOT);
        String rest = parts.length > 1 ? parts[1].trim() : "";

        switch (root) {
            case "stop":
            case "quit":
            case "exit":
                return false;
            case "help":
                help(rest);
                return true;
            case "status":
                status();
                return true;
            case "members":
                members();
                return true;
            case "broadcast":
                broadcast(rest);
                return true;
            case "recall":
                recall(rest);
                return true;
            case "config":
                config(rest);
                return true;
            case "aipro":
                aipro(rest);
                return true;
            case "ai":
                ai(rest);
                return true;
            default:
                unknownRoot(root);
                return true;
        }
    }

    private void help(String topic) {
        String value = topic == null ? "" : topic.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            log("CoTalk 终端命令");
            log("  help server   服务运行、成员与广播");
            log("  help config   配置查询、修改与保存");
            log("  help aipro    AI Pro 管理");
            log("  help ai       AI 状态");
            log("  help danger   危险操作");
            log("  help all      显示全部命令");
            log("常用：status | members | broadcast <text> | recall <seq> | stop");
            return;
        }
        if ("all".equals(value)) {
            helpServer();
            log("");
            helpConfig();
            log("");
            helpAipro();
            log("");
            helpAi();
            log("");
            helpDanger();
            return;
        }
        switch (value) {
            case "server":
                helpServer();
                return;
            case "config":
                helpConfig();
                return;
            case "aipro":
                helpAipro();
                return;
            case "ai":
                helpAi();
                return;
            case "danger":
                helpDanger();
                return;
            default:
                String suggestion = suggest(value,
                        "server", "config", "aipro", "ai", "danger", "all");
                log("未知帮助主题：" + topic
                        + (suggestion == null ? "" : "，是否想输入 help " + suggestion));
        }
    }

    private void helpServer() {
        log("服务命令：");
        log("  status                 查看运行状态");
        log("  members                查看在线成员");
        log("  broadcast <text>       向房间广播系统消息");
        log("  recall <seq>           强制撤回指定消息");
        log("  stop | quit | exit     停止服务并退出");
    }

    private void helpConfig() {
        log("配置命令：");
        log("  config path                    显示配置文件路径");
        log("  config list [group]            列出全部或指定分组配置");
        log("  config get <key>               查询配置");
        log("  config set <key> <value>       修改内存中的配置");
        log("  config save                    校验并写入配置文件");
        log("  config reload                  放弃未保存修改并重新读取文件");
        log("  config validate                校验当前内存配置");
        log("分组：" + String.join(", ", HeadlessConfigCatalog.groups()));
        log("敏感配置读取时显示为 ***；修改后需 config save 并重启服务生效。");
    }

    private void helpAipro() {
        log("AI Pro 命令：");
        log("  aipro stats");
        log("  aipro memories [deep|shallow|cache|all] [limit]");
        log("  aipro memory search <text>");
        log("  aipro memory archive|restore <id>");
        log("  aipro memory delete <id>");
        log("  aipro emojis [limit]");
        log("  aipro emoji approve|reject <id>");
        log("  aipro clear cache|all");
        log("  aipro export <path>");
        log("  aipro import <path>");
        log("  aipro todos");
        log("  aipro todo done|cancel <id>");
        log("  aipro todo defer <id> <timestamp>");
        log("  aipro emotion [reset]");
        log("  aipro search status|reset");
        log("删除记忆和清空记忆需要再次输入 yes 确认。");
    }

    private void helpAi() {
        log("AI 命令：");
        log("  ai status                     查看 AI 状态");
        log("  config list ai                查看基础 AI 配置");
        log("  config list aipro             查看 AI Pro 配置");
    }

    private void helpDanger() {
        log("危险操作（需要再次输入 yes 确认）：");
        log("  aipro clear cache|all         清空缓存或全部 AI Pro 记忆");
        log("  aipro memory delete <id>      删除指定 AI Pro 记忆");
    }

    private void status() {
        ServerConfig config = runtime.getConfig();
        log("状态：" + (runtime.isRunning() ? "运行中" : "已停止")
                + "，端口：" + config.port
                + "，房间：" + (runtime.getRoom() == null
                ? "-" : runtime.getRoom().name)
                + "，在线：" + runtime.getMembers().size()
                + "，AI：" + runtime.getAiStatus());
    }

    private void members() {
        List<Member> members = runtime.getMembers();
        if (members.isEmpty()) {
            log("暂无在线成员");
            return;
        }
        for (Member member : members) {
            log((member.ai ? "[AI] " : "") + member.nickname);
        }
    }

    private void broadcast(String text) {
        if (text.isEmpty()) {
            log("用法：broadcast <text>");
            return;
        }
        runtime.broadcast(text);
        log("广播：" + text);
    }

    private void recall(String value) {
        if (value.isEmpty()) {
            log("用法：recall <seq>");
            return;
        }
        long seq;
        try {
            seq = Long.parseLong(value);
        } catch (NumberFormatException e) {
            log("用法：recall <seq>");
            return;
        }
        MessageService.RecallResult result = runtime.forceRecall(seq);
        if (result.success) {
            log("已强制撤回 #" + seq + "：" + result.notice);
        } else {
            log("撤回失败：" + result.errorMessage);
        }
    }

    private void ai(String rest) {
        String value = rest == null ? "" : rest.trim();
        if (value.isEmpty() || "status".equalsIgnoreCase(value)) {
            log("AI：" + runtime.getAiStatus());
            return;
        }
        log("未知 AI 命令：" + value + "，输入 help ai 查看命令");
    }

    private void config(String rest) {
        String value = rest == null ? "" : rest.trim();
        String[] parts = value.split("\\s+", 3);
        String sub = parts[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "":
            case "help":
                helpConfig();
                return;
            case "path":
                log("配置文件：" + configPath.toAbsolutePath());
                return;
            case "list":
                configList(parts.length > 1 ? parts[1] : "");
                return;
            case "get":
                configGet(parts.length > 1 ? parts[1] : "");
                return;
            case "set":
                configSet(parts.length > 1 ? parts[1] : "",
                        parts.length > 2 ? parts[2] : "");
                return;
            case "save":
                configSave();
                return;
            case "reload":
                configReload();
                return;
            case "validate":
                configValidate();
                return;
            default:
                String suggestion = suggest(sub,
                        "path", "list", "get", "set", "save", "reload",
                        "validate");
                log("未知配置命令：" + sub
                        + (suggestion == null ? "" : "，是否想输入 config "
                        + suggestion));
        }
    }

    private void configList(String group) {
        List<HeadlessConfigCatalog.Field> fields =
                HeadlessConfigCatalog.fieldsForGroup(group);
        if (fields.isEmpty()) {
            log("未知配置分组：" + group);
            log("可用分组：" + String.join(", ",
                    HeadlessConfigCatalog.groups()));
            return;
        }
        String currentGroup = null;
        for (HeadlessConfigCatalog.Field field : fields) {
            String displayGroup = field.displayGroup();
            if (!displayGroup.equals(currentGroup)) {
                currentGroup = displayGroup;
                log("# " + displayGroup);
            }
            String value = config.get(field.key);
            log(field.key + " = "
                    + displayValue(field, value));
        }
    }

    private void configGet(String key) {
        if (key.isEmpty()) {
            log("用法：config get <key>");
            return;
        }
        HeadlessConfigCatalog.Field field = HeadlessConfigCatalog.field(key);
        if (field == null) {
            unknownKey(key);
            return;
        }
        log(field.key + " = " + displayValue(field, config.get(field.key)));
    }

    private void configSet(String key, String rawValue) {
        if (key.isEmpty() || rawValue.isEmpty()) {
            log("用法：config set <key> <value>");
            return;
        }
        HeadlessConfigCatalog.Field field = HeadlessConfigCatalog.field(key);
        if (field == null) {
            unknownKey(key);
            return;
        }
        if (!field.writable) {
            log("配置项 " + field.key + " 为只读，不能修改");
            return;
        }
        String value;
        try {
            value = field.normalize(rawValue);
        } catch (IllegalArgumentException e) {
            log(field.key + "（" + field.label + "）：" + e.getMessage());
            return;
        }
        config.set(field.key, value);
        if (field.sensitive) {
            log("已更新 " + field.key + "（值已隐藏）");
        } else {
            log("已更新 " + field.key + " = " + displayValue(field, value));
        }
        log("使用 config save 保存；重启服务后生效。");
    }

    private void configSave() {
        try {
            config.save();
            log("配置已保存到 " + configPath.toAbsolutePath()
                    + "；重启服务后生效。");
        } catch (Exception e) {
            log("保存配置失败：" + e.getMessage());
        }
    }

    private void configReload() {
        try {
            ServerConfigStore.LoadResult loaded =
                    ServerConfigStore.loadOrCreate(configPath);
            config.replace(loaded.config);
            log("配置已重新载入，未保存的修改已丢弃；重启服务后运行配置才会更新。");
        } catch (Exception e) {
            log("重新载入配置失败：" + e.getMessage());
        }
    }

    private void configValidate() {
        try {
            config.validate();
            log("配置校验通过");
        } catch (Exception e) {
            log("配置校验失败：" + e.getMessage());
        }
    }

    private void unknownKey(String key) {
        String suggestion = HeadlessConfigCatalog.suggest(key);
        log("未知配置项：" + key
                + (suggestion == null ? "" : "，是否想输入 " + suggestion));
    }

    private void aipro(String rest) {
        String value = rest == null ? "" : rest.trim();
        if ("help".equalsIgnoreCase(value)) {
            helpAipro();
            return;
        }
        String[] parts = value.split("\\s+", 3);
        String sub = parts[0].toLowerCase(Locale.ROOT);
        if ("search".equals(sub)) {
            search(parts.length > 1 ? parts[1] : "status");
            return;
        }
        AiProAdminService admin = admin();
        if (admin == null) {
            return;
        }
        if (value.isEmpty() || "stats".equalsIgnoreCase(value)) {
            log(admin.statsText());
            return;
        }
        switch (sub) {
            case "memories":
                memories(admin, parts);
                return;
            case "memory":
                memory(admin, parts);
                return;
            case "emojis":
                emojis(admin, parts);
                return;
            case "emoji":
                emojiReview(admin, parts);
                return;
            case "clear":
                clear(parts.length > 1 ? parts[1] : "");
                return;
            case "export":
                export(admin, value);
                return;
            case "import":
                importMemory(admin, value);
                return;
            case "todos":
                todos(admin);
                return;
            case "todo":
                todo(admin, parts);
                return;
            case "emotion":
                emotion(admin, parts.length > 1 ? parts[1] : "");
                return;
            default:
                String suggestion = suggest(sub,
                        "stats", "memories", "memory", "emojis", "emoji",
                        "clear", "export", "import", "todos", "todo",
                        "emotion", "search");
                log("未知 AI Pro 命令：" + sub
                        + (suggestion == null ? "" : "，是否想输入 aipro "
                        + suggestion));
        }
    }

    private void memories(AiProAdminService admin, String[] parts) {
        String layerValue = parts.length > 1 ? parts[1] : "all";
        MemoryLayer layer;
        if ("all".equalsIgnoreCase(layerValue)) {
            layer = null;
        } else if ("deep".equalsIgnoreCase(layerValue)) {
            layer = MemoryLayer.DEEP;
        } else if ("shallow".equalsIgnoreCase(layerValue)) {
            layer = MemoryLayer.SHALLOW;
        } else if ("cache".equalsIgnoreCase(layerValue)) {
            layer = MemoryLayer.CACHE;
        } else {
            log("用法：aipro memories [deep|shallow|cache|all] [limit]");
            return;
        }
        int limit = 20;
        if (parts.length > 2) {
            try {
                limit = Math.max(1, Math.min(500,
                        Integer.parseInt(parts[2])));
            } catch (NumberFormatException ignored) {
                log("limit 必须是整数");
                return;
            }
        }
        try {
            printMemories(admin.listMemories("", layer, "", limit, 0));
        } catch (Exception e) {
            log("读取记忆失败：" + e.getMessage());
        }
    }

    private void memory(AiProAdminService admin, String[] parts) {
        String action = parts.length > 1
                ? parts[1].toLowerCase(Locale.ROOT) : "";
        String value = parts.length > 2 ? parts[2].trim() : "";
        switch (action) {
            case "search":
                if (value.isEmpty()) {
                    log("用法：aipro memory search <text>");
                    return;
                }
                try {
                    printMemories(admin.listMemories(
                            value, null, "", 20, 0));
                } catch (Exception e) {
                    log("检索记忆失败：" + e.getMessage());
                }
                return;
            case "archive":
            case "restore":
                if (value.isEmpty()) {
                    log("用法：aipro memory " + action + " <id>");
                    return;
                }
                try {
                    admin.setStatus(value,
                            "archive".equals(action) ? "archived" : "active",
                            OPERATOR);
                    log("记忆已" + ("archive".equals(action)
                            ? "归档" : "恢复") + "：" + value);
                } catch (Exception e) {
                    log("更新记忆失败：" + e.getMessage());
                }
                return;
            case "delete":
                if (value.isEmpty()) {
                    log("用法：aipro memory delete <id>");
                    return;
                }
                schedule(() -> {
                    admin.deleteMemory(value, false, OPERATOR);
                    log("已删除记忆：" + value);
                }, "删除记忆 " + value);
                return;
            default:
                log("用法：aipro memory search|archive|restore|delete <...>");
        }
    }

    private void emojis(AiProAdminService admin, String[] parts) {
        int limit = 50;
        if (parts.length > 1) {
            try {
                limit = Math.max(1, Math.min(500,
                        Integer.parseInt(parts[1])));
            } catch (NumberFormatException e) {
                log("limit 必须是整数");
                return;
            }
        }
        try {
            List<ai.pro.AiEmoji> emojis = admin.listEmojis(limit);
            if (emojis.isEmpty()) {
                log("暂无表情");
                return;
            }
            for (ai.pro.AiEmoji emoji : emojis) {
                log(emoji.emojiId + " [" + emoji.reviewStatus + "] "
                        + (emoji.enabled ? "启用" : "停用") + " "
                        + emoji.name);
            }
        } catch (Exception e) {
            log("读取表情失败：" + e.getMessage());
        }
    }

    private void emojiReview(AiProAdminService admin, String[] parts) {
        String action = parts.length > 1
                ? parts[1].toLowerCase(Locale.ROOT) : "";
        String id = parts.length > 2 ? parts[2].trim() : "";
        if (!"approve".equals(action) && !"reject".equals(action)
                || id.isEmpty()) {
            log("用法：aipro emoji approve|reject <id>");
            return;
        }
        try {
            admin.reviewEmoji(id, "approve".equals(action), OPERATOR);
            log("表情已" + ("approve".equals(action) ? "通过" : "拒绝")
                    + "：" + id);
        } catch (Exception e) {
            log("审核表情失败：" + e.getMessage());
        }
    }

    private void clear(String target) {
        if (!"cache".equalsIgnoreCase(target)
                && !"all".equalsIgnoreCase(target)) {
            log("用法：aipro clear cache|all");
            return;
        }
        String normalized = target.toLowerCase(Locale.ROOT);
        AiProAdminService admin = admin();
        if (admin == null) {
            return;
        }
        schedule(() -> clearMemories(admin, normalized),
                "清空 AI Pro " + normalized + " 记忆");
    }

    private void clearMemories(AiProAdminService admin, String target)
            throws Exception {
        int count;
        if ("all".equals(target)) {
            count = admin.clearAll(OPERATOR);
        } else {
            count = admin.clearLayer(MemoryLayer.CACHE, OPERATOR);
        }
        log("已清空 AI Pro 记忆 " + count + " 条");
    }

    private void export(AiProAdminService admin, String value) {
        String path = value.length() <= "export".length()
                ? "" : value.substring("export".length()).trim();
        if (path.isEmpty()) {
            log("用法：aipro export <path>");
            return;
        }
        try {
            Path exported = admin.exportJson(
                    Path.of(path), OPERATOR);
            log("已导出到 " + exported);
            log("导出文件包含敏感记忆，请妥善保管。");
        } catch (Exception e) {
            log("导出失败：" + e.getMessage());
        }
    }

    private void importMemory(AiProAdminService admin, String value) {
        String path = value.length() <= "import".length()
                ? "" : value.substring("import".length()).trim();
        if (path.isEmpty()) {
            log("用法：aipro import <path>");
            return;
        }
        try {
            ai.pro.AiProImportExport.ImportResult result = admin.importJson(
                    Path.of(path), OPERATOR);
            log("记忆导入完成：新增 " + result.imported
                    + " 条，跳过重复 " + result.skipped + " 条");
        } catch (Exception e) {
            log("记忆导入失败：" + e.getMessage());
        }
    }

    private void todos(AiProAdminService admin) {
        try {
            List<AiTodo> todos = admin.listTodos(50);
            if (todos.isEmpty()) {
                log("暂无待办");
                return;
            }
            for (AiTodo todo : todos) {
                log(todo.todoId + " " + todo.userName + " "
                        + todo.status + "：" + summarize(todo.content));
            }
        } catch (Exception e) {
            log("读取待办失败：" + e.getMessage());
        }
    }

    private void todo(AiProAdminService admin, String[] parts) {
        String action = parts.length > 1
                ? parts[1].toLowerCase(Locale.ROOT) : "";
        String remainder = parts.length > 2 ? parts[2].trim() : "";
        String[] todoParts = remainder.split("\\s+", 2);
        String id = todoParts.length > 0 ? todoParts[0] : "";
        if (id.isEmpty()) {
            log("用法：aipro todo done|cancel <id>");
            return;
        }
        long deferTo = 0L;
        if ("defer".equals(action)) {
            if (todoParts.length < 2) {
                log("用法：aipro todo defer <id> <timestamp>");
                return;
            }
            try {
                deferTo = Long.parseLong(todoParts[1]);
            } catch (NumberFormatException e) {
                log("defer 时间戳必须是毫秒整数");
                return;
            }
            if (deferTo <= System.currentTimeMillis()) {
                log("defer 时间必须晚于当前时间");
                return;
            }
        } else if (!"done".equals(action) && !"cancel".equals(action)) {
            log("用法：aipro todo done|cancel|defer <id> [timestamp]");
            return;
        }
        try {
            admin.todoAction(id, action, deferTo, OPERATOR);
            log("待办已更新：" + id);
        } catch (Exception e) {
            log("待办操作失败：" + e.getMessage());
        }
    }

    private void emotion(AiProAdminService admin, String value) {
        try {
            if ("reset".equalsIgnoreCase(value)) {
                admin.resetEmotion(OPERATOR);
                log("AI Pro 情绪已重置");
            } else if (value.isEmpty()
                    || "status".equalsIgnoreCase(value)) {
                log("AI Pro 情绪：" + admin.emotionText());
            } else {
                log("用法：aipro emotion [reset]");
            }
        } catch (Exception e) {
            log("读取情绪失败：" + e.getMessage());
        }
    }

    private void search(String value) {
        String action = value == null || value.trim().isEmpty()
                ? "status" : value.trim().toLowerCase(Locale.ROOT);
        AiProAdminService admin = runtime.getAiProAdminService();
        if ("status".equals(action)) {
            if (admin != null) {
                log("联网搜索：" + admin.searchStatusText()
                        + "，地址：" + config.get("ai.pro.search.baseUrl"));
            } else {
                boolean enabled = config.getBoolean(
                        "ai.pro.search.enabled");
                int limit = config.getInt("ai.pro.search.dailyLimit");
                log("联网搜索：" + (enabled ? "已启用" : "未启用")
                        + "，每日额度：" + limit
                        + "，地址：" + config.get("ai.pro.search.baseUrl"));
            }
            return;
        }
        if ("reset".equals(action)) {
            if (admin == null) {
                log("AI Pro 管理服务未初始化");
                return;
            }
            try {
                admin.resetSearchQuota(OPERATOR);
                log("联网搜索今日额度已重置");
            } catch (Exception e) {
                log("重置搜索额度失败：" + e.getMessage());
            }
            return;
        }
        log("用法：aipro search status|reset");
    }

    private void schedule(PendingAction action, String description) {
        pendingAction = action;
        pendingDescription = description;
        log("确认" + description + "？再次输入 yes 确认，其他输入取消。");
    }

    private void resolvePending(String command) {
        PendingAction action = pendingAction;
        String description = pendingDescription;
        pendingAction = null;
        pendingDescription = null;
        if (!"yes".equalsIgnoreCase(command)) {
            log("已取消：" + description);
            return;
        }
        try {
            action.run();
        } catch (Exception e) {
            log("操作失败：" + e.getMessage());
        }
    }

    private AiProAdminService admin() {
        AiProAdminService admin = runtime.getAiProAdminService();
        if (admin == null) {
            log("AI Pro 管理服务未初始化");
        }
        return admin;
    }

    private void printMemories(List<Memory> memories) {
        if (memories.isEmpty()) {
            log("暂无记忆");
            return;
        }
        for (Memory memory : memories) {
            log(memory.memoryId + " [" + memory.layer.dbValue
                    + "] 重要度 "
                    + String.format(Locale.ROOT, "%.2f", memory.importance)
                    + " " + memory.status + "："
                    + summarize(memory.content));
        }
    }

    private String displayValue(HeadlessConfigCatalog.Field field,
                                String value) {
        if (value == null) {
            return "(未设置)";
        }
        if (field.sensitive) {
            return "***（已隐藏）";
        }
        String text = value.replace("\r", "\\r").replace("\n", "\\n");
        return text.length() <= 300 ? text : text.substring(0, 300) + "…";
    }

    private void unknownRoot(String root) {
        String suggestion = suggest(root,
                "help", "status", "members", "broadcast", "recall",
                "config", "aipro", "ai", "stop");
        log("未知命令：" + root
                + (suggestion == null ? "" : "，是否想输入 " + suggestion)
                + "；输入 help 查看命令");
    }

    private static String suggest(String input, String... candidates) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        String target = input == null ? "" : input.toLowerCase(Locale.ROOT);
        for (String candidate : candidates) {
            int distance = distance(target, candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        int threshold = Math.max(2, target.length() / 2);
        return bestDistance <= threshold ? best : null;
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

    private static String summarize(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace('\n', ' ').trim();
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }

    private interface PendingAction {
        void run() throws Exception;
    }

    private static final class ConfigSession {

        private final Path path;
        private Properties values;

        ConfigSession(Path path, ServerConfig initialConfig) {
            this.path = path;
            replace(initialConfig);
        }

        void replace(ServerConfig config) {
            values = config.toProperties();
            values.setProperty("config.version",
                    ServerConfigStore.CONFIG_VERSION);
        }

        String get(String key) {
            return values.getProperty(key);
        }

        boolean getBoolean(String key) {
            return Boolean.parseBoolean(get(key));
        }

        int getInt(String key) {
            try {
                return Integer.parseInt(get(key));
            } catch (Exception e) {
                return 0;
            }
        }

        void set(String key, String value) {
            values.setProperty(key, value);
        }

        void validate() {
            ServerConfig config = new ServerConfig(values);
            ServerConfigStore.validate(config);
        }

        void save() throws IOException {
            validate();
            ServerConfig config = new ServerConfig(values);
            ServerConfigStore.save(config, path);
        }
    }
}
