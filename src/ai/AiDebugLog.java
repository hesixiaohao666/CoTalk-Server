package ai;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * AI 调试日志：只记录请求规模、响应元信息和增强注入状态，不记录提示词或模型原文。
 */
public final class AiDebugLog {

    private static final Path PATH = Paths.get("logs", "ai-debug.log");

    private AiDebugLog() {
    }

    public static synchronized void log(String message) {
        try {
            Files.createDirectories(PATH.toAbsolutePath().getParent());
            String line = "[" + new SimpleDateFormat("HH:mm:ss")
                    .format(new Date()) + "] " + message + "\n";
            Files.writeString(PATH, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 调试日志失败不影响 AI 回复。
        }
    }
}
