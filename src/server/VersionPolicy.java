package server;

/**
 * v0.4.0 协议与客户端版本策略。客户端版本区间以客户端 versionCode 为准。
 */
public final class VersionPolicy {

    public static final int PROTOCOL_VERSION = 4000;

    public static final int MIN_CLIENT_VERSION_CODE = 4000;
    public static final int MAX_CLIENT_VERSION_CODE = 4099;

    /**
     * 语音能力最低版本；4000 起全部客户端均支持语音。
     */
    public static final int VOICE_MIN_CLIENT_VERSION_CODE = 4000;

    private VersionPolicy() {
    }

    public static boolean isClientVersionSupported(int versionCode) {
        return versionCode >= MIN_CLIENT_VERSION_CODE
                && versionCode <= MAX_CLIENT_VERSION_CODE;
    }
}
