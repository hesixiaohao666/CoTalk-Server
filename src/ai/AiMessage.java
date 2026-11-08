package ai;

import java.util.Collections;
import java.util.List;

/**
 * AI 请求消息：role = system/user/assistant；图片为可选多模态内容。
 */
public class AiMessage {

    public final String role;
    public final String content;
    public final List<AiImagePart> images;

    public AiMessage(String role, String content) {
        this(role, content, Collections.emptyList());
    }

    public AiMessage(String role, String content, List<AiImagePart> images) {
        this.role = role;
        this.content = content == null ? "" : content;
        this.images = images == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(images);
    }
}
