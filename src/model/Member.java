package model;

public class Member {

    public final String id;
    public volatile String nickname;
    public volatile String signature;
    public volatile int avatarVersion;
    public final boolean ai;
    public final String ip;
    public final long connectedAt;

    public Member(String id, String nickname, String signature,
                  int avatarVersion, boolean ai, String ip, long connectedAt) {
        this.id = id;
        this.nickname = nickname;
        this.signature = signature == null ? "" : signature;
        this.avatarVersion = avatarVersion;
        this.ai = ai;
        this.ip = ip;
        this.connectedAt = connectedAt;
    }
}
