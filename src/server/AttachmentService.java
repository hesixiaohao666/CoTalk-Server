package server;

import model.Attachment;
import store.AttachmentStore;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 聊天附件的 Base64 解码、类型/大小校验、文件落盘与读取。
 */
public class AttachmentService {

    public static final int MAX_ATTACHMENT_BYTES = 1024 * 1024;

    private static final Pattern FILE_ID_PATTERN =
            Pattern.compile("f-[0-9a-f]{16}");
    private static final int MAX_FILE_NAME_LENGTH = 80;

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

    private final AttachmentStore attachmentStore;
    private final Path directory;

    public AttachmentService(AttachmentStore attachmentStore, Path directory) {
        this.attachmentStore = attachmentStore;
        this.directory = directory.toAbsolutePath().normalize();
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

    public Attachment save(byte[] bytes, String mime, String fileName)
            throws IOException {
        Files.createDirectories(directory);
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
        attachment.fileName = fileName;
        attachment.mime = mime;
        attachment.size = bytes.length;
        attachment.createdAt = System.currentTimeMillis();
        attachment.expired = false;
        return attachment;
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

    public void delete(String fileId) {
        if (!isValidFileId(fileId)) {
            return;
        }
        try {
            Files.deleteIfExists(pathFor(fileId));
        } catch (IOException ignored) {
            // 清理失败不影响主流程；后续过期清理会再次处理。
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
