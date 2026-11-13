package model;

public class Room {

    public volatile String name;
    public volatile String announcement;
    public final long createdAt;

    public Room(String name, String announcement, long createdAt) {
        this.name = name;
        this.announcement = announcement;
        this.createdAt = createdAt;
    }
}
