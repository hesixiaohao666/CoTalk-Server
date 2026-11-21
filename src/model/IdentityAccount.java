package model;

/**
 * 群聊身份账号。密码只保存 PBKDF2 verifier，不保存明文。
 */
public final class IdentityAccount {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";

    public long rowId;
    public String id = "";
    public String username = "";
    public String salt = "";
    public int iterations;
    public String verifier = "";
    public String nickname = "";
    public String status = STATUS_PENDING;
    public boolean admin;
    public boolean muted;
    public long createdAt;
    public long updatedAt;
    public long lastLoginAt;
    public long lastSeenAt;

    public boolean approved() {
        return STATUS_APPROVED.equals(status);
    }

    public boolean pending() {
        return STATUS_PENDING.equals(status);
    }
}
