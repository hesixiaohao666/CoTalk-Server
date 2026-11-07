package server;

import model.Attachment;
import store.AttachmentStore;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 聊天附件的 Base64 解码、类型/大小校验、文件落盘与读取。
 */
public class AttachmentService {

    public static final int MAX_ATTACHMENT_BYTES =
            StoragePolicy.MAX_ATTACHMENT_BYTES;

    private static final Pattern FILE_ID_PATTERN =
            Pattern.compile("f-[0-9a-f]{16}");
    private static final int MAX_FILE_NAME_LENGTH = 80;
    private static final int CLEANUP_BATCH_SIZE = 200;
    private static final String FILE_NAME_TIME_PATTERN = "yyyyMMddHHmmss";

    private String lastFileNamePrefix = "";
    private int lastFileNameSequence;

    public static final class DecodedAttachment {
        public final String mime;
        public final String fileName;
        public final byte[] bytes;

        DecodedAttachment(String mime, String fileName, byte[] bytes) {
            this.mime = mime;
            this.fileName = fileName;
            this.bytes = bytes;
        }
    }

    public static final class FileData {
        public final Attachment attachment;
        public final byte[] bytes;
        public final boolean expired;

        FileData(Attachment attachment, byte[] bytes, boolean expired) {
            this.attachment = attachment;
            this.bytes = bytes;
            this.expired = expired;
        }
    }

    public static final class CleanupResult {
        public final int deletedFiles;
        public final long deletedBytes;
        public final long remainingBytes;
        public final boolean overLimit;

        CleanupResult(int deletedFiles,
                      long deletedBytes,
                      long remainingBytes,
                      boolean overLimit) {
            this.deletedFiles = deletedFiles;
            this.deletedBytes = deletedBytes;
            this.remainingBytes = remainingBytes;
            this.overLimit = overLimit;
        }
    }

    private final AttachmentStore attachmentStore;
    private final Path directory;
    private final Consumer<String> logger;
    private final ScheduledExecutorService maintenance =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "cotalk-attachment-cleaner");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicBoolean cleanupQueued = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> maintenanceTask;

    public AttachmentService(AttachmentStore attachmentStore,
                             Path directory,
                             Consumer<String> logger) {
        this.attachmentStore = attachmentStore;
        this.directory = directory.toAbsolutePath().normalize();
        this.logger = logger == null ? message -> { } : logger;
    }

    /**
     * 启动后每小时检查一次；实际删除只在总量超过 2GB 且附件超过 7 天时发生。
     */
    public synchronized void startMaintenance() {
        if (maintenanceTask != null) {
            return;
        }
        maintenanceTask = maintenance.scheduleWithFixedDelay(
                this::runScheduledCleanup, 1, 1, TimeUnit.HOURS);
    }

    /**
     * 上传后异步触发一次清理检查，避免阻塞消息 ack/广播。
     */
    public void requestCleanup() {
        if (!cleanupQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            maintenance.execute(() -> {
                try {
                    cleanupExpired(System.currentTimeMillis());
                } catch (Exception e) {
                    log("附件清理失败：" + e.getMessage());
                } finally {
                    cleanupQueued.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            cleanupQueued.set(false);
        }
    }

    public void shutdownMaintenance() {
        ScheduledFuture<?> task = maintenanceTask;
        maintenanceTask = null;
        if (task != null) {
            task.cancel(false);
        }
        maintenance.shutdownNow();
    }

    public DecodedAttachment decode(String type,
                                    String encoded,
                                    String declaredMime,
                                    String fileName) {
        if (encoded == null || encoded.trim().isEmpty()) {
            throw new IllegalArgumentException("附件数据为空");
        }
        String payload = encoded.trim();
        String prefixMime = null;
        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma > 0) {
            String prefix = payload.substring("data:".length(), comma);
            if (prefix.endsWith(";base64")) {
                prefixMime = prefix.substring(0, prefix.length() - 7);
            }
            payload = payload.substring(comma + 1);
        }

        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("附件 Base64 无效");
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("附件数据为空");
        }
        if (bytes.length > MAX_ATTACHMENT_BYTES) {
            throw new IllegalArgumentException("附件不能超过 1MB");
        }

        String suppliedMime = declaredMime == null || declaredMime.trim().isEmpty()
                ? prefixMime
                : declaredMime;
        String mime = resolveMime(type, bytes, suppliedMime);
        return new DecodedAttachment(mime, safeFileName(fileName, mime), bytes);
    }

    /**
     * 附件文件名由服务端统一生成，客户端传入的 fileName 只作兼容参数。
     */
    public synchronized Attachment save(byte[] bytes,
                                        String mime,
                                        String fileName)
            throws IOException {
        Files.createDirectories(directory);
        long createdAt = System.currentTimeMillis();
        String canonicalName = nextFileName(mime, createdAt);
        String fileId = "f-" + UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 16);
        Path target = pathFor(fileId);

        Path temp = Files.createTempFile(directory, ".upload-", ".tmp");
        boolean moved = false;
        try {
            Files.write(temp, bytes);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }

        Attachment attachment = new Attachment();
        attachment.fileId = fileId;
        attachment.fileName = canonicalName;
        attachment.mime = mime;
        attachment.size = bytes.length;
        attachment.createdAt = createdAt;
        attachment.expired = false;
        return attachment;
    }

    private String nextFileName(String mime, long createdAt) throws IOException {
        String prefix = new SimpleDateFormat(
                FILE_NAME_TIME_PATTERN, Locale.ROOT).format(new Date(createdAt));
        int sequence;
        if (prefix.equals(lastFileNamePrefix)) {
            sequence = lastFileNameSequence + 1;
        } else {
            try {
                sequence = attachmentStore.maxFileNameSequence(prefix) + 1;
            } catch (SQLException e) {
                throw new IOException("附件文件名生成失败", e);
            }
        }
        lastFileNamePrefix = prefix;
        lastFileNameSequence = sequence;
        return prefix + String.format(Locale.ROOT, "%03d", sequence)
                + extensionFor(mime);
    }

    /**
     * 返回 null 表示 fileId 不存在；bytes 为 null 表示文件已过期/缺失。
     */
    public FileData load(String fileId) throws Exception {
        if (!isValidFileId(fileId)) {
            throw new IllegalArgumentException("fileId 无效");
        }
        Attachment attachment = attachmentStore.find(fileId);
        if (attachment == null) {
            return null;
        }
        if (attachment.expired) {
            return new FileData(attachment, null, true);
        }
        if (attachmentStore.isMessageRecalled(attachment.messageUuid)) {
            return new FileData(attachment, null, true);
        }
        // 超过 7 天不再向客户端发送；文件可继续留在磁盘，等待 2GB 超限清理。
        if (attachment.createdAt < StoragePolicy.syncCutoff(
                System.currentTimeMillis())) {
            return new FileData(attachment, null, true);
        }

        Path path = pathFor(fileId);
        if (!Files.isRegularFile(path)) {
            attachmentStore.markExpired(attachment.fileId, attachment.messageUuid);
            return new FileData(attachment, null, true);
        }
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > MAX_ATTACHMENT_BYTES) {
            attachmentStore.markExpired(attachment.fileId, attachment.messageUuid);
            return new FileData(attachment, null, true);
        }
        return new FileData(attachment, bytes, false);
    }

    public CleanupResult cleanupExpired(long now) throws Exception {
        return cleanupExpired(now, StoragePolicy.MAX_TOTAL_ATTACHMENT_BYTES);
    }

    /**
     * 容量超过 maxTotalBytes 时，从最旧的超时附件开始清理，直到低于阈值；
     * 如果没有更多超时附件，则保留未超时文件并允许暂时超限。
     */
    public CleanupResult cleanupExpired(long now, long maxTotalBytes)
            throws Exception {
        long total = attachmentStore.totalBytes();
        if (total <= maxTotalBytes) {
            return new CleanupResult(0, 0, total, false);
        }

        long cutoff = StoragePolicy.syncCutoff(now);
        int deletedFiles = 0;
        long deletedBytes = 0;

        while (total > maxTotalBytes) {
            List<Attachment> candidates =
                    attachmentStore.cleanupCandidates(cutoff, CLEANUP_BATCH_SIZE);
            if (candidates.isEmpty()) {
                break;
            }
            boolean progressed = false;
            for (Attachment attachment : candidates) {
                try {
                    deleteFile(attachment.fileId);
                } catch (IOException e) {
                    log("附件文件删除失败 " + attachment.fileId
                            + "：" + e.getMessage());
                    continue;
                }
                attachmentStore.markExpired(
                        attachment.fileId, attachment.messageUuid);
                long size = Math.max(0L, attachment.size);
                total -= size;
                deletedBytes += size;
                deletedFiles++;
                progressed = true;
                if (total <= maxTotalBytes) {
                    break;
                }
            }
            if (!progressed) {
                break;
            }
        }

        boolean overLimit = total > maxTotalBytes;
        if (deletedFiles > 0) {
            log("附件清理完成：删除 " + deletedFiles + " 个文件，释放 "
                    + deletedBytes + " 字节，剩余 " + total + " 字节");
        }
        if (overLimit) {
            log("附件总量仍超过上限，允许暂时超限：当前 " + total
                    + " 字节，上限 " + maxTotalBytes + " 字节");
        }
        return new CleanupResult(
                deletedFiles, deletedBytes, total, overLimit);
    }

    public void delete(String fileId) {
        try {
            deleteFile(fileId);
        } catch (IOException ignored) {
            // 清理失败不影响主流程；后续过期清理会再次处理。
        }
    }

    private void deleteFile(String fileId) throws IOException {
        if (!isValidFileId(fileId)) {
            throw new IllegalArgumentException("fileId 无效");
        }
        Files.deleteIfExists(pathFor(fileId));
    }

    private void runScheduledCleanup() {
        try {
            cleanupExpired(System.currentTimeMillis());
        } catch (Exception e) {
            log("附件定时清理失败：" + e.getMessage());
        }
    }

    private void log(String message) {
        try {
            logger.accept(message);
        } catch (RuntimeException ignored) {
            // 日志回调异常不能影响清理流程。
        }
    }

    public static boolean isValidFileId(String fileId) {
        return fileId != null && FILE_ID_PATTERN.matcher(fileId).matches();
    }

    private Path pathFor(String fileId) {
        Path path = directory.resolve(fileId).normalize();
        if (!path.getParent().equals(directory)) {
            throw new IllegalArgumentException("fileId 无效");
        }
        return path;
    }

    private String resolveMime(String type, byte[] bytes, String declaredMime) {
        String normalized = normalizeMime(declaredMime);
        if ("image".equals(type) || "emoji".equals(type)) {
            String detected = detectImageMime(bytes);
            if (detected == null) {
                throw new IllegalArgumentException("仅支持 JPEG/PNG/GIF/WebP 图片");
            }
            return detected;
        }
        if ("voice".equals(type)) {
            if (!normalized.startsWith("audio/")
                    || normalized.length() > 60
                    || !normalized.matches("audio/[a-z0-9.+-]+")) {
                throw new IllegalArgumentException("语音 MIME 无效");
            }
            return normalized;
        }
        throw new IllegalArgumentException("不支持的消息类型");
    }

    private static String normalizeMime(String value) {
        if (value == null) {
            return "";
        }
        String mime = value.trim().toLowerCase(Locale.ROOT);
        int semicolon = mime.indexOf(';');
        if (semicolon >= 0) {
            mime = mime.substring(0, semicolon).trim();
        }
        return "image/jpg".equals(mime) ? "image/jpeg" : mime;
    }

    private static String detectImageMime(byte[] bytes) {
        if (isJpeg(bytes)) {
            return "image/jpeg";
        }
        if (isPng(bytes)) {
            return "image/png";
        }
        if (isGif(bytes)) {
            return "image/gif";
        }
        if (isWebp(bytes)) {
            return "image/webp";
        }
        return null;
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G';
    }

    private static boolean isGif(byte[] bytes) {
        return bytes.length >= 6
                && bytes[0] == 'G'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == '8'
                && (bytes[4] == '7' || bytes[4] == '9')
                && bytes[5] == 'a';
    }

    private static boolean isWebp(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P';
    }

    private static String safeFileName(String fileName, String mime) {
        String name = fileName == null ? "" : fileName.trim()
                .replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\p{Cntrl}]", "_").trim();
        if (name.isEmpty()) {
            name = "attachment" + extensionFor(mime);
        }
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            name = name.substring(0, MAX_FILE_NAME_LENGTH);
        }
        return name;
    }

    private static String extensionFor(String mime) {
        switch (mime) {
            case "image/jpeg":
                return ".jpg";
            case "image/png":
                return ".png";
            case "image/gif":
                return ".gif";
            case "image/webp":
                return ".webp";
            case "audio/mpeg":
                return ".mp3";
            case "audio/mp4":
                return ".m4a";
            case "audio/wav":
                return ".wav";
            case "audio/ogg":
                return ".ogg";
            case "audio/aac":
                return ".aac";
            case "audio/amr":
                return ".amr";
            default:
                return ".bin";
        }
    }
}
