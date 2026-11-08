package server;

/**
 * v0.3.2 协议与客户端版本策略。客户端版本区间以客户端 versionCode 为准。
 */
public final class VersionPolicy {

    public static final int PROTOCOL_VERSION = 3100;

    public static final int MIN_CLIENT_VERSION_CODE = 3100;
    public static final int MAX_CLIENT_VERSION_CODE = 3199;

    /**
     * 低于该版本的客户端不支持语音，服务端会把语音降级为文字提示。
     */
    public static final int VOICE_MIN_CLIENT_VERSION_CODE = 3110;

    private VersionPolicy() {
    }

    public static boolean isClientVersionSupported(int versionCode) {
        return versionCode >= MIN_CLIENT_VERSION_CODE
                && versionCode <= MAX_CLIENT_VERSION_CODE;
    }
}
