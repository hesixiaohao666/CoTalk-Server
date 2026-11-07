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
                + "（" + ServerVersion.VERSION_CODE + "）终端模式");
        log("配置文件：" + result.path);
        if (result.keyGenerated) {
            log("已生成连接密钥：" + result.generatedKey
                    + "（请复制到客户端；也可在 config.properties 修改）");
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
        if ("help".equalsIgnoreCase(command)) {
            log("命令：help | status | members | broadcast <text> | "
                    + "recall <seq> | ai status | config path | stop");
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
        log("未知命令：" + command + "（输入 help 查看命令）");
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
