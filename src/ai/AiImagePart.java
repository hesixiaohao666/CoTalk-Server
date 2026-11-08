package ai;

/**
 * 多模态请求中的一张图片：mime + Base64 数据。
 */
public final class AiImagePart {

    public final String mime;
    public final String base64Data;

    public AiImagePart(String mime, String base64Data) {
        this.mime = mime == null || mime.isEmpty()
                ? "image/jpeg" : mime;
        this.base64Data = base64Data == null ? "" : base64Data;
    }
}
