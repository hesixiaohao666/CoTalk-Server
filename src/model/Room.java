package model;

public class Room {

    public final String name;
    public final String announcement;
    public final long createdAt;

    public Room(String name, String announcement, long createdAt) {
        this.name = name;
        this.announcement = announcement;
        this.createdAt = createdAt;
    }
}
