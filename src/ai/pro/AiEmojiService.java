package ai.pro;

import ai.AiReplySink;
import model.Attachment;
import server.AttachmentService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * AI 表情候选选择、token 校验与发送。
 */
public class AiEmojiService {

    private final AiEmojiStore store;
    private final AttachmentService attachmentService;
    private final AiProConfig config;
    private final AiReplySink sink;

    public AiEmojiService(AiEmojiStore store,
                          AttachmentService attachmentService,
                          AiProConfig config,
                          AiReplySink sink) {
        this.store = store;
        this.attachmentService = attachmentService;
        this.config = config;
        this.sink = sink;
    }

    public boolean enabled() {
        return config.getBoolean("ai.pro.emoji.enabled");
    }

    public List<AiEmoji> candidates(String mood) {
        List<AiEmoji> result = new ArrayList<>();
        if (!enabled()) {
            return result;
        }
        try {
            String[] sensitive = config.getString(
                    "ai.pro.emoji.sensitiveTags").split("[,，\\s]+");
            String context = mood == null ? ""
                    : mood.toLowerCase(Locale.ROOT);
            for (AiEmoji emoji : store.listEnabled(
                    config.getInt("ai.pro.emoji.candidatePool"))) {
                if (sensitiveHit(emoji.tags, sensitive)) {
                    continue;
                }
                result.add(emoji);
            }
            result.sort(Comparator.comparingInt(
                    (AiEmoji emoji) -> -tagScore(emoji.tags, context)));
            int max = config.getInt("ai.pro.emoji.topCandidates");
            if (result.size() > max) {
                return new ArrayList<>(result.subList(0, max));
            }
        } catch (Exception e) {
            sink.reportError("AI Pro 读取表情候选失败：" + e.getMessage());
        }
        return result;
    }

    public String promptSection(String mood) {
        List<AiEmoji> candidates = candidates(mood);
        if (candidates.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("【表情候选】（可选，最多选 1 个；不选则完全省略）\n");
        for (AiEmoji emoji : candidates) {
            builder.append("- [[emoji:").append(emoji.emojiId).append("]] ")
                    .append(emoji.name);
            List<String> tags = new ArrayList<>(
                    MemoryText.listValues(emoji.tags));
            if (!tags.isEmpty()) {
                builder.append("，").append(String.join("，", tags));
            }
            builder.append('\n');
        }
        return builder.toString().trim();
    }

    /**
     * @return true 表示已作为表情消息发送。
     */
    public boolean send(String emojiId, String content) {
        if (!enabled() || emojiId == null || emojiId.isEmpty()) {
            return false;
        }
        try {
            AiEmoji emoji = store.findByEmojiId(emojiId);
            if (emoji == null || !emoji.enabled) {
                return false;
            }
            byte[] bytes = store.readBytes(emoji);
            int maxBytes = "image/gif".equalsIgnoreCase(emoji.mime)
                    ? config.getInt("ai.pro.emoji.maxGifBytes")
                    : config.getInt("ai.pro.emoji.maxBytes");
            if (bytes == null || bytes.length == 0
                    || bytes.length > maxBytes
                    || bytes.length
                    > AttachmentService.MAX_ATTACHMENT_BYTES) {
                return false;
            }
            Attachment attachment = attachmentService.save(
                    bytes, emoji.mime, emoji.fileName);
            sink.appendAiEmojiMessage(attachment,
                    content == null ? emoji.name : content);
            return true;
        } catch (Exception e) {
            sink.reportError("AI Pro 发送表情失败：" + e.getMessage());
            return false;
        }
    }

    private static int tagScore(String tags, String context) {
        int score = 0;
        for (String tag : MemoryText.listValues(tags)) {
            if (!context.isEmpty() && context.contains(tag)) {
                score += 2;
            }
        }
        return score;
    }

    private static boolean sensitiveHit(String tags, String[] sensitive) {
        List<String> values = new ArrayList<>(
                MemoryText.listValues(tags));
        for (String word : sensitive) {
            String value = word == null ? "" : word.trim();
            if (!value.isEmpty() && values.contains(
                    value.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
