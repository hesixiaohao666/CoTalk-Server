package server;

import model.Member;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 无图形环境下的服务端：服务运行 + 终端命令。
 */
public final class HeadlessConsole {

    private HeadlessConsole() {
    }

    public static void run(String[] args) {
        System.setProperty("java.awt.headless", "true");
        boolean noConsole = hasArg(args, "--no-console");

        ServerConfigStore.LoadResult result;
        try {
            result = ServerConfigStore.loadOrCreate();
        } catch (Exception e) {
            log("读取/生成配置失败：" + e.getMessage());
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
            System.exit(1);
            return;
        }

        log("服务已启动：端口 " + config.port
                + "，房间：" + runtime.getRoom().name
                + "，数据库：" + config.dbPath);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (stopping.compareAndSet(false, true)) {
                runtime.stop();
                }
        }));

        if (!noConsole) {
            HeadlessCommandProcessor processor =
                    new HeadlessCommandProcessor(
                            runtime, result.path, config, HeadlessConsole::log);
            Thread console = new Thread(
                    () -> consoleLoop(runtime, processor, done, stopping),
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
        }
    }

    private static void consoleLoop(ServerRuntime runtime,
                                    HeadlessCommandProcessor processor,
                                    CountDownLatch done,
                                    AtomicBoolean stopping) {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                if (!processor.handle(line)) {
                    if (stopping.compareAndSet(false, true)) {
                        runtime.stop();
                    }
                    done.countDown();
                    return;
                }
            }
        } catch (Exception e) {
            log("控制台读取失败：" + e.getMessage());
        }
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

    /**
     * 终端只负责控制台输出；文件日志统一由 ServerRuntime/ServerLogService 写入。
     */
    private static void log(String message) {
        System.out.println("[" + new SimpleDateFormat("HH:mm:ss")
                .format(new Date()) + "] " + message);
    }
}
