package server;

import model.Avatar;
import store.AvatarStore;

import java.util.Base64;

/**
 * 头像 Base64 解码、格式/大小校验、版本存取。
 */
public class AvatarService {

    public static final class AvatarData {
        public final String mime;
        public final byte[] bytes;

        AvatarData(String mime, byte[] bytes) {
            this.mime = mime;
            this.bytes = bytes;
        }
    }

    private final AvatarStore avatarStore;
    private final int maxBytes;

    public AvatarService(AvatarStore avatarStore, int maxBytes) {
        this.avatarStore = avatarStore;
        this.maxBytes = maxBytes;
    }

    public AvatarData decode(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("头像为空");
        }
        String payload = value.trim();

        int comma = payload.indexOf(',');
        if (payload.startsWith("data:") && comma > 0) {
            payload = payload.substring(comma + 1);
        }

        byte[] bytes;
        try {
            bytes = Base64.getMimeDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("头像 Base64 无效");
        }
        if (bytes.length == 0 || bytes.length > maxBytes) {
            throw new IllegalArgumentException("头像大小非法");
        }

        String mime;
        if (isJpeg(bytes)) {
            mime = "image/jpeg";
        } else if (isPng(bytes)) {
            mime = "image/png";
        } else {
            throw new IllegalArgumentException("头像仅支持 JPEG/PNG");
        }
        return new AvatarData(mime, bytes);
    }

    public int save(String userId, AvatarData data) throws Exception {
        return avatarStore.save(userId, data.mime, data.bytes);
    }

    public Avatar load(String userId, int version) throws Exception {
        return avatarStore.load(userId, version);
    }

    public String toDataUri(Avatar avatar) {
        return "data:" + avatar.mime + ";base64,"
                + Base64.getEncoder().encodeToString(avatar.data);
    }

    private boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    private boolean isPng(byte[] bytes) {
        return bytes.length >= 8
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 'P'
                && bytes[2] == 'N'
                && bytes[3] == 'G';
    }
}
