package server;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 服务端文件日志骨架：日期+同日序号、按日轮转、保留天数与敏感字段脱敏。
 */
public final class ServerLogService {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(password|passwd|pwd|proof|authKey|apiKey|token|"
                    + "verifier|secret)\\s*[=:]\\s*([^\\s,;]+)");

    private final Path directory;
    private final int retainDays;
    private BufferedWriter writer;
    private LocalDate currentDate;
    private int sequence;

    public ServerLogService(String directory, int retainDays) {
        this.directory = Paths.get(directory == null || directory.trim().isEmpty()
                ? "logs" : directory.trim()).toAbsolutePath().normalize();
        this.retainDays = Math.max(1, retainDays);
    }

    public synchronized void start() throws IOException {
        Files.createDirectories(directory);
        openFor(LocalDate.now());
        cleanupOldFiles(LocalDate.now());
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
            LocalDate today = LocalDate.now();
            if (writer == null || currentDate == null
                    || !today.equals(currentDate)) {
                close();
                openFor(today);
                cleanupOldFiles(today);
            }
            String line = "[" + java.time.LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern(
                            "yyyy-MM-dd HH:mm:ss")) + "] ["
                    + (level == null ? "INFO" : level) + "] ["
                    + (category == null ? "server" : category) + "] "
                    + sanitize(message) + "\n";
            writer.write(line);
            writer.flush();
        } catch (Exception ignored) {
            // 日志失败不影响服务运行
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
        Matcher matcher = SENSITIVE.matcher(message);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(buffer,
                    matcher.group(1) + "=***");
        }
        matcher.appendTail(buffer);
        return buffer.toString();
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
        LocalDate cutoff = today.minusDays(retainDays);
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
