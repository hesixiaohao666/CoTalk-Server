package server;

import model.Member;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 无图形环境下的服务端：服务运行 + 终端命令。
 */
public final class HeadlessConsole {

    private static final Object LOG_LOCK = new Object();
    private static BufferedWriter logWriter;
    private static volatile String pendingAiproClear;

    private HeadlessConsole() {
    }

    public static void run(String[] args) {
        System.setProperty("java.awt.headless", "true");
        boolean noConsole = hasArg(args, "--no-console");
        openLogFile();

        ServerConfigStore.LoadResult result;
        try {
            result = ServerConfigStore.loadOrCreate();
        } catch (Exception e) {
            log("读取/生成配置失败：" + e.getMessage());
            closeLog();
            System.exit(1);
            return;
        }

        ServerConfig config = result.config;
        log("CoTalk " + ServerVersion.VERSION_NAME
                + " 终端模式");
        log("配置文件：" + result.path);
        if (result.keyGenerated) {
            log("已生成连接密钥：" + result.generatedKey
                    + "，请复制到客户端，也可在 config.properties 修改");
        }
        log("客户端版本允许区间："
                + VersionPolicy.MIN_CLIENT_VERSION_CODE + "–"
                + VersionPolicy.MAX_CLIENT_VERSION_CODE);
        log("AI：" + aiStatus(config));

        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean stopping = new AtomicBoolean(false);

        ServerEvents events = new ServerEvents() {
            @Override
            public void onLog(String message) {
                log(message);
            }

            @Override
            public void onMembersChanged(List<Member> members) {
                log("在线 " + members.size() + " 人");
            }

            @Override
            public void onStatusChanged(String status) {
                log("状态：" + status);
            }

            @Override
            public void onServerAddress(String address) {
                log("服务器地址：" + address);
            }
        };

        ServerRuntime runtime = new ServerRuntime(config, events);
        try {
            runtime.start();
        } catch (Exception e) {
            log("启动失败：" + e.getMessage());
            runtime.stop();
            closeLog();
            System.exit(1);
            return;
        }

        log("服务已启动：端口 " + config.port
                + "，房间：" + runtime.getRoom().name
                + "，数据库：" + config.dbPath);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (stopping.compareAndSet(false, true)) {
                runtime.stop();
                closeLog();
            }
        }));

        if (!noConsole) {
            Thread console = new Thread(
                    () -> consoleLoop(runtime, done, stopping),
                    "cotalk-console");
            console.setDaemon(true);
            console.start();
        }

        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (stopping.compareAndSet(false, true)) {
                runtime.stop();
            }
            closeLog();
        }
    }

    private static void consoleLoop(ServerRuntime runtime,
                                    CountDownLatch done,
                                    AtomicBoolean stopping) {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String command = line.trim();
                if (command.isEmpty()) {
                    continue;
                }
                if ("stop".equalsIgnoreCase(command)
                        || "quit".equalsIgnoreCase(command)
                        || "exit".equalsIgnoreCase(command)) {
                    if (stopping.compareAndSet(false, true)) {
                        runtime.stop();
                    }
                    done.countDown();
                    return;
                }
                handleCommand(runtime, command);
            }
        } catch (Exception e) {
            log("控制台读取失败：" + e.getMessage());
        }
    }

    private static void handleCommand(ServerRuntime runtime, String command) {
        if (pendingAiproClear != null) {
            String target = pendingAiproClear;
            pendingAiproClear = null;
            if (!"yes".equalsIgnoreCase(command)) {
                log("已取消清空操作");
                return;
            }
            handleAiproClear(runtime, target);
            return;
        }
        if ("help".equalsIgnoreCase(command)) {
            log("命令：help | status | members | broadcast <text> | "
                    + "recall <seq> | ai status | aipro stats | "
                    + "aipro memories [layer] [limit] | aipro clear cache|all | "
                    + "aipro export <path> | aipro todos | "
                    + "aipro todo done|cancel <id> | aipro emotion | "
                    + "config path | stop");
            return;
        }
        if (command.toLowerCase(java.util.Locale.ROOT).startsWith("aipro")) {
            handleAiproCommand(runtime, command);
            return;
        }
        if ("status".equalsIgnoreCase(command)) {
            ServerConfig config = runtime.getConfig();
            log("状态：" + (runtime.isRunning() ? "运行中" : "已停止")
                    + "，端口：" + config.port
                    + "，房间：" + (runtime.getRoom() == null
                    ? "-" : runtime.getRoom().name)
                    + "，在线：" + runtime.getMembers().size()
                    + "，AI：" + runtime.getAiStatus());
            return;
        }
        if ("members".equalsIgnoreCase(command)) {
            List<Member> members = runtime.getMembers();
            if (members.isEmpty()) {
                log("暂无在线成员");
            } else {
                for (Member member : members) {
                    log((member.ai ? "[AI] " : "") + member.nickname);
                }
            }
            return;
        }
        if (command.startsWith("broadcast ")) {
            String text = command.substring("broadcast ".length()).trim();
            if (text.isEmpty()) {
                log("用法：broadcast <text>");
            } else {
                runtime.broadcast(text);
                log("广播：" + text);
            }
            return;
        }
        if (command.startsWith("recall ")) {
            String value = command.substring("recall ".length()).trim();
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
            return;
        }
        if ("ai status".equalsIgnoreCase(command)) {
            log("AI：" + runtime.getAiStatus());
            return;
        }
        if ("config path".equalsIgnoreCase(command)) {
            log("配置文件：" + ServerConfigStore.DEFAULT_PATH.toAbsolutePath());
            return;
        }
        log("未知命令：" + command + "，输入 help 查看命令");
    }

    private static void handleAiproCommand(ServerRuntime runtime,
                                           String command) {
        ai.pro.AiProAdminService admin = runtime.getAiProAdminService();
        if (admin == null) {
            log("AI Pro 管理服务未初始化");
            return;
        }
        String rest = command.length() <= 5
                ? "" : command.substring(5).trim();
        if (rest.isEmpty() || "stats".equalsIgnoreCase(rest)) {
            log(admin.statsText());
            return;
        }
        if (rest.toLowerCase(java.util.Locale.ROOT).startsWith("memories")) {
            String[] parts = rest.split("\\s+");
            String layerValue = parts.length > 1 ? parts[1] : "all";
            int limit = 20;
            if (parts.length > 2) {
                try {
                    limit = Math.max(1, Integer.parseInt(parts[2]));
                } catch (NumberFormatException ignored) {
                    // 默认 20
                }
            }
            ai.pro.MemoryLayer layer = "all".equalsIgnoreCase(layerValue)
                    ? null : ai.pro.MemoryLayer.fromDb(layerValue);
            try {
                java.util.List<ai.pro.Memory> memories =
                        admin.listMemories("", layer, "", limit, 0);
                if (memories.isEmpty()) {
                    log("暂无记忆");
                } else {
                    for (ai.pro.Memory memory : memories) {
                        log(memory.memoryId + " [" + memory.layer.dbValue
                                + "] 重要度 "
                                + String.format(java.util.Locale.ROOT,
                                "%.2f", memory.importance)
                                + " " + memory.status + "："
                                + summarize(memory.content));
                    }
                }
            } catch (Exception e) {
                log("读取记忆失败：" + e.getMessage());
            }
            return;
        }
        if (rest.toLowerCase(java.util.Locale.ROOT).startsWith("clear ")) {
            String target = rest.substring(6).trim();
            if (!"cache".equalsIgnoreCase(target)
                    && !"all".equalsIgnoreCase(target)) {
                log("用法：aipro clear cache|all");
                return;
            }
            pendingAiproClear = target.toLowerCase(
                    java.util.Locale.ROOT);
            log("确认清空 AI Pro " + target
                    + " 记忆？再次输入 yes 确认");
            return;
        }
        if (rest.toLowerCase(java.util.Locale.ROOT).startsWith("export ")) {
            String path = rest.substring(7).trim();
            if (path.isEmpty()) {
                log("用法：aipro export <path>");
                return;
            }
            try {
                Path exported = admin.exportJson(
                        Paths.get(path), "admin-headless");
                log("已导出到 " + exported);
            } catch (Exception e) {
                log("导出失败：" + e.getMessage());
            }
            return;
        }
        if ("todos".equalsIgnoreCase(rest)) {
            try {
                java.util.List<ai.pro.AiTodo> todos = admin.listTodos(50);
                if (todos.isEmpty()) {
                    log("暂无待办");
                } else {
                    for (ai.pro.AiTodo todo : todos) {
                        log(todo.todoId + " " + todo.userName + " "
                                + todo.status + "："
                                + summarize(todo.content));
                    }
                }
            } catch (Exception e) {
                log("读取待办失败：" + e.getMessage());
            }
            return;
        }
        if (rest.toLowerCase(java.util.Locale.ROOT).startsWith("todo ")) {
            String[] parts = rest.split("\\s+");
            if (parts.length < 3) {
                log("用法：aipro todo done|cancel <id>");
                return;
            }
            try {
                admin.todoAction(parts[2],
                        parts[1].toLowerCase(java.util.Locale.ROOT),
                        0L, "admin-headless");
                log("待办已更新：" + parts[2]);
            } catch (Exception e) {
                log("待办操作失败：" + e.getMessage());
            }
            return;
        }
        if (rest.toLowerCase(java.util.Locale.ROOT).startsWith("emotion")) {
            try {
                if (rest.toLowerCase(java.util.Locale.ROOT)
                        .contains("reset")) {
                    admin.resetEmotion("admin-headless");
                    log("AI Pro 情绪已重置");
                } else {
                    log("AI Pro 情绪：" + admin.emotionText());
                }
            } catch (Exception e) {
                log("读取情绪失败：" + e.getMessage());
            }
            return;
        }
        log("未知 AI Pro 命令：" + rest);
    }

    private static void handleAiproClear(ServerRuntime runtime, String target) {
        ai.pro.AiProAdminService admin = runtime.getAiProAdminService();
        if (admin == null) {
            log("AI Pro 管理服务未初始化");
            return;
        }
        try {
            int count;
            if ("all".equals(target)) {
                count = admin.clearAll("admin-headless");
            } else {
                count = admin.clearLayer(
                        ai.pro.MemoryLayer.CACHE, "admin-headless");
            }
            log("已清空 AI Pro 记忆 " + count + " 条");
        } catch (Exception e) {
            log("清空失败：" + e.getMessage());
        }
    }

    private static String summarize(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace('\n', ' ').trim();
        return text.length() <= 40 ? text : text.substring(0, 40) + "…";
    }

    private static String aiStatus(ServerConfig config) {
        if (!config.aiEnabled) {
            return "未启用";
        }
        if (config.aiApiKey == null || config.aiApiKey.trim().isEmpty()) {
            return "已开启但未配置 API Key";
        }
        return "已启用，模型 " + config.aiModel;
    }

    private static boolean hasArg(String[] args, String needle) {
        for (String arg : args) {
            if (needle.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void openLogFile() {
        try {
            Path path = Paths.get("logs", "server.log");
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            logWriter = Files.newBufferedWriter(
                    path,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("无法写入 logs/server.log：" + e.getMessage());
        }
    }

    private static void log(String message) {
        String line = "[" + new SimpleDateFormat("HH:mm:ss").format(new Date())
                + "] " + message;
        System.out.println(line);
        synchronized (LOG_LOCK) {
            if (logWriter != null) {
                try {
                    logWriter.write(line);
                    logWriter.newLine();
                    logWriter.flush();
                } catch (Exception ignored) {
                    // 控制台已输出，文件日志失败不阻塞服务
                }
            }
        }
    }

    private static void closeLog() {
        synchronized (LOG_LOCK) {
            if (logWriter != null) {
                try {
                    logWriter.close();
                } catch (Exception ignored) {
                    // ignore
                }
                logWriter = null;
            }
        }
    }
}
