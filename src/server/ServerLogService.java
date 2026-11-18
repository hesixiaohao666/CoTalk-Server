package server;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务端文件日志：日期+同日序号、按日轮转、保留天数与敏感字段脱敏。
 * GUI 与 Headless 共用同一实例，由 ServerRuntime 注入。
 */
public final class ServerLogService {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern JSON_SENSITIVE = Pattern.compile(
            "(?i)(\"(?:password|passwd|pwd|proof|auth[_-]?key|"
                    + "api[_-]?key|access[_-]?token|token|verifier|secret)\""
                    + "\\s*:\\s*\")([^\"]*)(\")");
    private static final Pattern KV_SENSITIVE = Pattern.compile(
            "(?i)\\b(password|passwd|pwd|proof|auth[_-]?key|"
                    + "api[_-]?key|access[_-]?token|token|verifier|secret)\\b"
                    + "(\\s*[=:]\\s*)([^\\s,;}\\]]+)");
    private static final Pattern BEARER = Pattern.compile(
            "(?i)(Bearer\\s+)\\S+");
    private static final Pattern API_KEY_VALUE = Pattern.compile(
            "\\bsk-[A-Za-z0-9_-]{8,}\\b");

    private final Path directory;
    private final int retainDays;
    private final Clock clock;
    private BufferedWriter writer;
    private LocalDate currentDate;
    private int sequence;

    public ServerLogService(String directory, int retainDays) {
        this(directory, retainDays, Clock.systemDefaultZone());
    }

    public ServerLogService(String directory, int retainDays, Clock clock) {
        this.directory = Paths.get(directory == null || directory.trim().isEmpty()
                ? "logs" : directory.trim()).toAbsolutePath().normalize();
        this.retainDays = Math.max(1, retainDays);
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    public synchronized void start() throws IOException {
        Files.createDirectories(directory);
        LocalDate today = LocalDate.now(clock);
        openFor(today);
        cleanupOldFiles(today);
    }

    public synchronized void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // ignore
            }
            writer = null;
        }
    }

    public synchronized void log(String level,
                                 String category,
                                 String message) {
        try {
            LocalDate today = LocalDate.now(clock);
            if (writer == null || currentDate == null
                    || !today.equals(currentDate)) {
                close();
                openFor(today);
                cleanupOldFiles(today);
            }
            writer.write(line(level, category, sanitize(message)));
            writer.flush();
        } catch (Exception ignored) {
            // 日志失败不影响服务运行
        }
    }

    public synchronized void log(String level,
                                 String category,
                                 String message,
                                 Throwable error) {
        log(level, category, message);
        if (error == null) {
            return;
        }
        try {
            LocalDate today = LocalDate.now(clock);
            if (writer == null || currentDate == null
                    || !today.equals(currentDate)) {
                close();
                openFor(today);
                cleanupOldFiles(today);
            }
            writer.write(line(level, category + "/stack",
                    sanitize(error.toString())));
            for (StackTraceElement element : error.getStackTrace()) {
                writer.write(line(level, category + "/stack",
                        "  at " + sanitize(element.toString())));
            }
            Throwable cause = error.getCause();
            if (cause != null && cause != error) {
                writer.write(line(level, category + "/stack",
                        "Caused by: " + sanitize(cause.toString())));
            }
            writer.flush();
        } catch (Exception ignored) {
            // ignore
        }
    }

    public synchronized Path currentFile() {
        if (currentDate == null || sequence <= 0) {
            return null;
        }
        return directory.resolve(fileName(currentDate, sequence));
    }

    public Path getDirectory() {
        return directory;
    }

    public int getRetainDays() {
        return retainDays;
    }

    public static String sanitize(String message) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        Matcher json = JSON_SENSITIVE.matcher(message);
        StringBuffer buffer = new StringBuffer();
        while (json.find()) {
            json.appendReplacement(buffer, Matcher.quoteReplacement(
                    json.group(1) + "***" + json.group(3)));
        }
        json.appendTail(buffer);

        Matcher kv = KV_SENSITIVE.matcher(buffer.toString());
        StringBuffer kvBuffer = new StringBuffer();
        while (kv.find()) {
            kv.appendReplacement(kvBuffer, Matcher.quoteReplacement(
                    kv.group(1) + kv.group(2) + "***"));
        }
        kv.appendTail(kvBuffer);

        Matcher bearer = BEARER.matcher(kvBuffer.toString());
        String bearerValue = bearer.replaceAll("$1***");
        return API_KEY_VALUE.matcher(bearerValue).replaceAll("sk-***");
    }

    /**
     * 消息预览：单行化并限制字符数，避免日志写入完整消息正文。
     */
    public static String preview(String message, int maxChars) {
        if (message == null || message.isEmpty()) {
            return "";
        }
        String value = message.replace('\r', ' ').replace('\n', ' ').trim();
        int limit = Math.max(1, maxChars);
        if (value.codePointCount(0, value.length()) <= limit) {
            return value;
        }
        int end = value.offsetByCodePoints(0, limit);
        return value.substring(0, end) + "…";
    }

    private String line(String level, String category, String message) {
        return "[" + LocalDateTime.now(clock).format(TIME) + "] ["
                + (level == null || level.isEmpty() ? "INFO" : level)
                + "] [" + (category == null || category.isEmpty()
                ? "server" : category) + "] " + message + "\n";
    }

    private void openFor(LocalDate date) throws IOException {
        currentDate = date;
        sequence = nextSequence(date);
        writer = Files.newBufferedWriter(
                directory.resolve(fileName(date, sequence)),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private int nextSequence(LocalDate date) throws IOException {
        String prefix = date.format(DATE);
        int max = 0;
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(directory, prefix + "*.log")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.length() < prefix.length() + 7) {
                    continue;
                }
                String number = name.substring(prefix.length(),
                        prefix.length() + 3);
                try {
                    max = Math.max(max, Integer.parseInt(number));
                } catch (NumberFormatException ignored) {
                    // 非本服务命名格式
                }
            }
        }
        return max + 1;
    }

    private void cleanupOldFiles(LocalDate today) throws IOException {
        LocalDate cutoff = today.minusDays(Math.max(0, retainDays - 1));
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(directory, "*.log")) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.length() < 8) {
                    continue;
                }
                try {
                    LocalDate date = LocalDate.parse(
                            name.substring(0, 8), DATE);
                    if (date.isBefore(cutoff)) {
                        Files.deleteIfExists(path);
                    }
                } catch (Exception ignored) {
                    // 非本服务生成的文件不处理
                }
            }
        }
    }

    private static String fileName(LocalDate date, int sequence) {
        return date.format(DATE)
                + String.format(java.util.Locale.ROOT, "%03d", sequence)
                + ".log";
    }
}
