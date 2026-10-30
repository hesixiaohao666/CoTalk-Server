package model;

public class Member {

    public final String id;
    public final String nickname;
    public final int avatarVersion;
    public final boolean ai;
    public final String ip;
    public final long connectedAt;

    public Member(String id, String nickname, int avatarVersion,
                  boolean ai, String ip, long connectedAt) {
        this.id = id;
        this.nickname = nickname;
        this.avatarVersion = avatarVersion;
        this.ai = ai;
        this.ip = ip;
        this.connectedAt = connectedAt;
    }
}
