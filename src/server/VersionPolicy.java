package server;

/**
 * v0.2 协议与客户端版本策略。客户端版本区间以客户端 versionCode 为准。
 */
public final class VersionPolicy {

    public static final int PROTOCOL_VERSION = 2000;

    public static final int MIN_CLIENT_VERSION_CODE = 2000;
    public static final int MAX_CLIENT_VERSION_CODE = 2999;

    public static final String SERVER_VERSION_NAME = "0.2.0(beta)";

    private VersionPolicy() {
    }

    public static boolean isClientVersionSupported(int versionCode) {
        return versionCode >= MIN_CLIENT_VERSION_CODE
                && versionCode <= MAX_CLIENT_VERSION_CODE;
    }
}
