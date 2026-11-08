package server;

import model.Avatar;

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
import java.util.Arrays;

/**
 * 服务端内置头像（AI/房间）的读取、裁剪、压缩与版本写入。
 */
public final class AvatarImageService {

    private static final int AVATAR_SIZE = 256;

    private AvatarImageService() {
    }

    /**
     * 读取图片并写入 avatar 表。
     *
     * @param replaceIfChanged true 时比较字节，图片变化则生成新版本；
     *                         false 时已有头像直接复用（AI 头像旧行为）。
     * @param allowBundledFallback 文件缺失时是否回退到内置 AI 头像资源。
     * @return 头像版本；0 表示没有可用头像。
     */
    public static int loadOrStore(String configuredPath,
                                  String userId,
                                  AvatarService avatarService,
                                  ServerEvents events,
                                  int maxBytes,
                                  boolean replaceIfChanged,
                                  boolean allowBundledFallback) {
        Avatar existing = null;
        try {
            existing = avatarService.load(userId, 0);
        } catch (Exception e) {
            events.onLog("读取头像失败：" + e.getMessage());
        }

        if (!replaceIfChanged && existing != null) {
            return existing.version;
        }

        String value = configuredPath == null ? "" : configuredPath.trim();
        if (value.isEmpty()) {
            return 0;
        }

        BufferedImage source = readSource(value, events, allowBundledFallback);
        if (source == null) {
            events.onLog("未找到头像文件 " + value
                    + "，保持当前头像版本 "
                    + (existing == null ? 0 : existing.version));
            return existing == null ? 0 : existing.version;
        }

        try {
            byte[] encoded = encodePng(source);
            String mime = "image/png";
            if (encoded.length > maxBytes) {
                byte[] jpeg = encodeJpeg(source);
                if (jpeg.length <= maxBytes) {
                    encoded = jpeg;
                    mime = "image/jpeg";
                }
            }
            if (encoded.length > maxBytes) {
                events.onLog("头像压缩后仍超过 avatar.maxBytes，跳过保存");
                return existing == null ? 0 : existing.version;
            }

            if (replaceIfChanged && existing != null
                    && mime.equals(existing.mime)
                    && Arrays.equals(encoded, existing.data)) {
                return existing.version;
            }

            AvatarService.AvatarData data =
                    new AvatarService.AvatarData(mime, encoded);
            int version = avatarService.save(userId, data);
            events.onLog("已写入头像 " + userId + "，版本 " + version
                    + "，大小 " + encoded.length + " 字节");
            return version;
        } catch (Exception e) {
            events.onLog("保存头像失败：" + e.getMessage());
            return existing == null ? 0 : existing.version;
        }
    }

    private static BufferedImage readSource(String value,
                                            ServerEvents events,
                                            boolean allowBundledFallback) {
        Path path = Paths.get(value);
        if (Files.isRegularFile(path)) {
            try {
                return ImageIO.read(path.toFile());
            } catch (IOException e) {
                events.onLog("读取头像文件失败：" + e.getMessage());
            }
        }
        if (!allowBundledFallback) {
            return null;
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
            InputStream input = AvatarImageService.class
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
