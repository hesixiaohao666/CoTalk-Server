package ai;

/**
 * 根据附件 fileId 读取图片内容，供 AI 多模态上下文使用。
 */
public interface AiImageResolver {

    /**
     * 返回 null 表示附件不存在、已过期或读取失败。
     */
    AiImagePart resolve(String fileId) throws Exception;
}
