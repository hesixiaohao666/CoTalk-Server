package server;

/**
 * v0.3.5 协议与客户端版本策略。客户端版本区间以客户端 versionCode 为准。
 */
public final class VersionPolicy {

    public static final int PROTOCOL_VERSION = 3200;

    public static final int MIN_CLIENT_VERSION_CODE = 3200;
    public static final int MAX_CLIENT_VERSION_CODE = 3299;

    /**
     * 语音能力最低版本；3200 起全部客户端均支持语音。
     */
    public static final int VOICE_MIN_CLIENT_VERSION_CODE = 3200;

    private VersionPolicy() {
    }

    public static boolean isClientVersionSupported(int versionCode) {
        return versionCode >= MIN_CLIENT_VERSION_CODE
                && versionCode <= MAX_CLIENT_VERSION_CODE;
    }
}
