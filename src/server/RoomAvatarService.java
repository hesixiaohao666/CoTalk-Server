package server;

/**
 * 房间自定义头像：复用 avatar 表，保留用户 ID 为 room-avatar。
 */
public final class RoomAvatarService {

    public static final String ROOM_AVATAR_ID = "room-avatar";

    private RoomAvatarService() {
    }

    public static int prepare(ServerConfig config,
                              AvatarService avatarService,
                              ServerEvents events) {
        return AvatarImageService.loadOrStore(
                config.roomAvatar,
                ROOM_AVATAR_ID,
                avatarService,
                events,
                config.avatarMaxBytes,
                true,
                false);
    }
}
