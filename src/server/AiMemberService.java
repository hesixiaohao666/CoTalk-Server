package server;

import ai.AiResponder;
import ai.AiResponderFactory;
import model.Member;

/**
 * AI 虚拟成员与固定头像：头像首次启动时压缩到 256x256 并写入 avatar 表。
 */
public final class AiMemberService {

    private AiMemberService() {
    }

    public static Member prepare(ServerConfig config,
                                 AvatarService avatarService,
                                 ServerEvents events) {
        if (!AiResponderFactory.isConfigured(config)) {
            return null;
        }
        int avatarVersion = AvatarImageService.loadOrStore(
                config.aiAvatar,
                AiResponder.AI_USER_ID,
                avatarService,
                events,
                config.avatarMaxBytes,
                false,
                true);
        return new Member(
                AiResponder.AI_USER_ID,
                config.aiName,
                "",
                avatarVersion,
                true,
                "server-ai",
                System.currentTimeMillis()
        );
    }
}
