package server;

import model.Avatar;

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
        // 管理员上传的新头像已写入数据库；重启时优先使用数据库最新版本，
        // 避免旧的 room.avatar 文件路径覆盖管理员修改。
        try {
            Avatar existing = avatarService.load(ROOM_AVATAR_ID, 0);
            if (existing != null && existing.version > 0) {
                return existing.version;
            }
        } catch (Exception e) {
            events.onLog("读取房间头像失败：" + e.getMessage());
        }
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
