package server;

import java.util.concurrent.TimeUnit;

/**
 * v0.3.0 云端消息保留与附件容量策略。
 */
public final class StoragePolicy {

    public static final int RETENTION_DAYS = 7;
    public static final long RETENTION_MS =
            TimeUnit.DAYS.toMillis(RETENTION_DAYS);

    public static final long MAX_TOTAL_ATTACHMENT_BYTES =
            2L * 1024 * 1024 * 1024;
    public static final int MAX_ATTACHMENT_BYTES = 1024 * 1024;

    public static final String ATTACHMENT_DIR = "data/attachments";

    private StoragePolicy() {
    }

    public static long syncCutoff(long now) {
        return now - RETENTION_MS;
    }
}
