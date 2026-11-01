package server;

import ai.AiResponder;
import ai.AiResponderFactory;
import model.Avatar;
import model.Member;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AI 虚拟成员与固定头像：头像首次启动时压缩到 256x256 并写入 avatar 表。
 */
public final class AiMemberService {

    private static final int AVATAR_SIZE = 256;

    private AiMemberService() {
    }

    public static Member prepare(ServerConfig config,
                                 AvatarService avatarService,
                                 ServerEvents events) {
        if (!AiResponderFactory.isConfigured(config)) {
            return null;
        }
        int avatarVersion = loadOrStoreAvatar(config, avatarService, events);
        return new Member(
                AiResponder.AI_USER_ID,
                config.aiName,
                avatarVersion,
                true,
                "server-ai",
                System.currentTimeMillis()
        );
    }

    private static int loadOrStoreAvatar(ServerConfig config,
                                         AvatarService avatarService,
                                         ServerEvents events) {
        try {
            Avatar existing = avatarService.load(AiResponder.AI_USER_ID, 0);
            if (existing != null) {
                return existing.version;
            }
        } catch (Exception e) {
            events.onLog("读取 AI 头像失败：" + e.getMessage());
        }

        BufferedImage source = readSource(config.aiAvatar, events);
        if (source == null) {
            events.onLog("未找到 AI 头像 " + config.aiAvatar
                    + "，AI 仍可聊天，但不显示头像");
            return 0;
        }

        try {
            byte[] png = encodePng(source);
            String mime = "image/png";
            if (png.length > config.avatarMaxBytes) {
                byte[] jpeg = encodeJpeg(source);
                if (jpeg.length <= config.avatarMaxBytes) {
                    png = jpeg;
                    mime = "image/jpeg";
                }
            }
            if (png.length > config.avatarMaxBytes) {
                events.onLog("AI 头像压缩后仍超过 avatar.maxBytes，跳过保存");
                return 0;
            }
            AvatarService.AvatarData data =
                    new AvatarService.AvatarData(mime, png);
            int version = avatarService.save(AiResponder.AI_USER_ID, data);
            events.onLog("已写入 AI 头像，版本 " + version
                    + "，大小 " + png.length + " 字节");
            return version;
        } catch (Exception e) {
            events.onLog("保存 AI 头像失败：" + e.getMessage());
            return 0;
        }
    }

    private static BufferedImage readSource(String value, ServerEvents events) {
        Path path = Paths.get(value);
        if (Files.isRegularFile(path)) {
            try {
                return ImageIO.read(path.toFile());
            } catch (IOException e) {
                events.onLog("读取 AI 头像文件失败：" + e.getMessage());
            }
        }

        Path[] fallbacks = {
                Paths.get("assets/ai_avatar.png"),
                Paths.get("src/assets/ai_avatar.png"),
                Paths.get(path.getFileName() == null
                        ? "ai_avatar.png" : path.getFileName().toString())
        };
        for (Path fallback : fallbacks) {
            if (Files.isRegularFile(fallback)) {
                try {
                    return ImageIO.read(fallback.toFile());
                } catch (IOException ignored) {
                    // 继续尝试下一个位置
                }
            }
        }

        String[] resources = {
                "assets/ai_avatar.png",
                "ai/ai_avatar.png",
                "ai_avatar.png"
        };
        for (String resource : resources) {
            InputStream input = AiMemberService.class
                    .getClassLoader().getResourceAsStream(resource);
            if (input != null) {
                try (InputStream stream = input) {
                    return ImageIO.read(stream);
                } catch (IOException ignored) {
                    // 继续尝试下一个资源
                }
            }
        }
        return null;
    }

    private static byte[] encodePng(BufferedImage source) throws IOException {
        BufferedImage scaled = scaleSquare(source, true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(scaled, "png", output);
        return output.toByteArray();
    }

    private static byte[] encodeJpeg(BufferedImage source) throws IOException {
        BufferedImage scaled = scaleSquare(source, false);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(scaled, "jpg", output);
        return output.toByteArray();
    }

    private static BufferedImage scaleSquare(BufferedImage source, boolean alpha) {
        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;
        BufferedImage cropped = source.getSubimage(x, y, side, side);

        BufferedImage target = new BufferedImage(
                AVATAR_SIZE, AVATAR_SIZE,
                alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(
                    RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            if (!alpha) {
                graphics.setColor(java.awt.Color.WHITE);
                graphics.fillRect(0, 0, AVATAR_SIZE, AVATAR_SIZE);
            }
            graphics.drawImage(cropped, 0, 0, AVATAR_SIZE, AVATAR_SIZE, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }
}
