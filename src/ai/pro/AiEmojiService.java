package ai.pro;

import ai.AiImagePart;
import ai.AiMessage;
import ai.AiReplySink;
import ai.DeepSeekClient;
import ai.DeepSeekResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import model.Attachment;
import model.Message;
import server.AttachmentService;
import server.ServerConfig;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/**
 * AI 表情标签、候选选择、token 校验与发送。
 */
public class AiEmojiService {

    private static final int MAX_AI_TAGS = 6;

    private final AiEmojiStore store;
    private final AttachmentService attachmentService;
    private final AiProConfig config;
    private final boolean visionEnabled;
    private final DeepSeekClient client;
    private final AiReplySink sink;
    private final ExecutorService tagWorker =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "cotalk-ai-emoji-tag");
                thread.setDaemon(true);
                return thread;
            });
    private volatile Set<String> allowedCandidateIds = Collections.emptySet();

    public AiEmojiService(AiEmojiStore store,
                          AttachmentService attachmentService,
                          ServerConfig serverConfig,
                          DeepSeekClient client,
                          AiReplySink sink) {
        this(store, attachmentService, serverConfig.aiPro,
                serverConfig.aiVision, client, sink);
    }

    public AiEmojiService(AiEmojiStore store,
                          AttachmentService attachmentService,
                          AiProConfig config,
                          boolean visionEnabled,
                          DeepSeekClient client,
                          AiReplySink sink) {
        this.store = store;
        this.attachmentService = attachmentService;
        this.config = config;
        this.visionEnabled = visionEnabled;
        this.client = client;
        this.sink = sink;
        try {
            store.enforceLibraryLimit(
                    config.getInt("ai.pro.emoji.libraryMax"));
        } catch (Exception e) {
            if (sink != null) {
                sink.reportError("AI 表情库清理失败：" + e.getMessage());
            }
        }
    }

    public boolean enabled() {
        return config.getBoolean("ai.pro.emoji.enabled");
    }

    /**
     * 自动收集用户发送的表情。返回 null 表示未导入或不满足条件。
     */
    public AiEmoji importUserEmoji(Message message) {
        if (!config.getBoolean("ai.pro.emoji.autoImport")
                || message == null || !"emoji".equals(message.type)
                || message.fileId == null || message.fileId.isEmpty()) {
            return null;
        }
        try {
            AttachmentService.FileData file =
                    attachmentService.load(message.fileId);
            if (file == null || file.expired || file.bytes == null
                    || file.bytes.length == 0) {
                return null;
            }
            int maxBytes = isGif(file.attachment.mime, file.attachment.fileName)
                    ? config.getInt("ai.pro.emoji.maxGifBytes")
                    : config.getInt("ai.pro.emoji.maxBytes");
            if (file.bytes.length > maxBytes
                    || file.bytes.length > AttachmentService.MAX_ATTACHMENT_BYTES) {
                return null;
            }
            String hash = AiEmojiStore.hash(file.bytes);
            AiEmoji existing = store.findByHash(hash);
            if (existing != null) {
                scheduleTag(existing, false);
                return existing;
            }
            if (store.countUserImportsSince(todayStart())
                    >= config.getInt("ai.pro.emoji.dailyImportMax")) {
                return null;
            }
            boolean skipReview = config.getBoolean("ai.pro.emoji.skipReview");
            if (skipReview && !hit(config.getInt(
                    "ai.pro.emoji.learnChance"))) {
                return null;
            }
            AiEmoji emoji = store.importBytes(file.bytes, message.fileName,
                    file.attachment.mime, message.fileName, "[]",
                    "user", message.senderId, skipReview);
            scheduleTag(emoji, false);
            enforceLibraryLimit();
            return emoji;
        } catch (Exception e) {
            sink.reportError("AI 收集用户表情失败：" + e.getMessage());
            return null;
        }
    }

    /**
     * 随机抽取启用且已审核的候选，只把这些标签放入提示词。
     */
    public List<AiEmoji> candidates(String mood) {
        List<AiEmoji> result = new ArrayList<>();
        allowedCandidateIds = Collections.emptySet();
        if (!enabled()) {
            return result;
        }
        try {
            String[] sensitive = config.getString(
                    "ai.pro.emoji.sensitiveTags").split("[,，\\s]+");
            int poolSize = Math.max(
                    config.getInt("ai.pro.emoji.randomCandidates"),
                    config.getInt("ai.pro.emoji.libraryMax"));
            for (AiEmoji emoji : store.listEnabled(poolSize)) {
                if (sensitiveHit(tagsOf(emoji), sensitive)) {
                    continue;
                }
                result.add(emoji);
            }
            Collections.shuffle(result);
            int max = config.getInt("ai.pro.emoji.randomCandidates");
            if (result.size() > max) {
                result = new ArrayList<>(result.subList(0, max));
            }
            Set<String> ids = new LinkedHashSet<>();
            for (AiEmoji emoji : result) {
                ids.add(emoji.emojiId);
            }
            allowedCandidateIds = ids;
        } catch (Exception e) {
            sink.reportError("AI 读取表情候选失败：" + e.getMessage());
        }
        return result;
    }

    public String promptSection(String mood) {
        List<AiEmoji> candidates = candidates(mood);
        if (candidates.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("【表情候选】（通过 actions 中的 emoji 动作发送，"
                + "最多连续 ").append(Math.min(3,
                config.getInt("ai.pro.emoji.maxConsecutive")))
                .append(" 个；不选则完全省略）\n");
        for (AiEmoji emoji : candidates) {
            builder.append("- id=").append(emoji.emojiId).append("，")
                    .append(emoji.name);
            Set<String> tags = new LinkedHashSet<>(tagsOf(emoji));
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
        if (!enabled() || emojiId == null || emojiId.isEmpty()
                || !allowedCandidateIds.contains(emojiId)) {
            return false;
        }
        try {
            AiEmoji emoji = store.findByEmojiId(emojiId);
            if (emoji == null || !emoji.enabled
                    || !"approved".equals(emoji.reviewStatus)) {
                return false;
            }
            byte[] bytes = store.readBytes(emoji);
            int maxBytes = isGif(emoji.mime, emoji.fileName)
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
            store.markUsed(emojiId, System.currentTimeMillis());
            return true;
        } catch (Exception e) {
            sink.reportError("AI 发送表情失败：" + e.getMessage());
            return false;
        }
    }

    public void scheduleTag(AiEmoji emoji) {
        scheduleTag(emoji, false);
    }

    /**
     * 异步生成视觉标签。force=true 用于管理员手动重试。
     */
    public void scheduleTag(AiEmoji emoji, boolean force) {
        if (emoji == null) {
            return;
        }
        long now = System.currentTimeMillis();
        try {
            if (isGif(emoji.mime, emoji.fileName)) {
                store.updateAiTags(emoji.emojiId, "[\"GIF表情\"]",
                        "done", now);
                emoji.aiTags = "[\"GIF表情\"]";
                emoji.tagStatus = "done";
                emoji.tagUpdatedAt = now;
                return;
            }
            if (!force && !config.getBoolean("ai.pro.emoji.autoTag")) {
                return;
            }
            if (!force && ("pending".equals(emoji.tagStatus)
                    || "done".equals(emoji.tagStatus))) {
                return;
            }
            if (client == null || !visionEnabled) {
                store.updateAiTags(emoji.emojiId, "[]", "skipped", now);
                emoji.aiTags = "[]";
                emoji.tagStatus = "skipped";
                emoji.tagUpdatedAt = now;
                return;
            }
            store.updateAiTags(emoji.emojiId, "[]", "pending", now);
            emoji.tagStatus = "pending";
            emoji.tagUpdatedAt = now;
            tagWorker.execute(() -> generateTags(emoji));
        } catch (Exception e) {
            sink.reportError("AI 表情标签任务失败：" + e.getMessage());
        }
    }

    public void retryTag(String emojiId) throws Exception {
        AiEmoji emoji = store.findByEmojiId(emojiId);
        if (emoji == null) {
            throw new IllegalArgumentException("表情不存在");
        }
        scheduleTag(emoji, true);
    }

    public int enforceLibraryLimit() throws Exception {
        return store.enforceLibraryLimit(
                config.getInt("ai.pro.emoji.libraryMax"));
    }

    public void shutdown() {
        tagWorker.shutdownNow();
    }

    private void generateTags(AiEmoji emoji) {
        try {
            byte[] bytes = store.readBytes(emoji);
            if (bytes == null || bytes.length == 0) {
                store.updateAiTags(emoji.emojiId, "[]", "failed",
                        System.currentTimeMillis());
                return;
            }
            List<AiMessage> request = new ArrayList<>();
            request.add(new AiMessage("system",
                    "你是群聊表情包标签生成器。只输出 JSON 字符串数组，"
                            + "不要输出解释、Markdown 或代码块。"));
            List<AiImagePart> images = new ArrayList<>();
            images.add(new AiImagePart(emoji.mime,
                    Base64.getEncoder().encodeToString(bytes)));
            request.add(new AiMessage("user",
                    "为这个表情生成最多 " + MAX_AI_TAGS
                            + " 个简短中文检索标签，例如"
                            + "[\"开心\",\"点赞\"]；只描述画面和情绪。",
                    images));
            DeepSeekResult result = client.chatCompletionResult(
                    request, 200, false);
            List<String> tags = parseTags(result == null
                    ? "" : result.content);
            long now = System.currentTimeMillis();
            if (tags.isEmpty()) {
                store.updateAiTags(emoji.emojiId, "[]", "failed", now);
                return;
            }
            store.updateAiTags(emoji.emojiId, toJson(tags), "done", now);
        } catch (Exception e) {
            try {
                store.updateAiTags(emoji.emojiId, "[]", "failed",
                        System.currentTimeMillis());
            } catch (Exception ignored) {
                // 标签失败不影响表情本身
            }
            sink.reportError("AI 表情标签生成失败：" + e.getMessage());
        }
    }

    private static List<String> parseTags(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String value = raw.trim();
        if (value.startsWith("```")) {
            int first = value.indexOf('\n');
            int last = value.lastIndexOf("```");
            if (first > 0 && last > first) {
                value = value.substring(first + 1, last).trim();
            }
        }
        int start = value.indexOf('[');
        int end = value.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return Collections.emptyList();
        }
        try {
            JsonElement element = JsonParser.parseString(
                    value.substring(start, end + 1));
            if (!element.isJsonArray()) {
                return Collections.emptyList();
            }
            Set<String> result = new LinkedHashSet<>();
            for (JsonElement item : element.getAsJsonArray()) {
                if (!item.isJsonPrimitive()
                        || !item.getAsJsonPrimitive().isString()) {
                    continue;
                }
                String tag = MemoryText.normalize(item.getAsString())
                        .toLowerCase(Locale.ROOT);
                if (!tag.isEmpty()) {
                    result.add(tag);
                }
                if (result.size() >= MAX_AI_TAGS) {
                    break;
                }
            }
            return new ArrayList<>(result);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static String toJson(List<String> tags) {
        JsonArray array = new JsonArray();
        for (String tag : tags) {
            array.add(tag);
        }
        return array.toString();
    }

    private static List<String> tagsOf(AiEmoji emoji) {
        List<String> result = new ArrayList<>();
        result.addAll(MemoryText.listValues(emoji.tags));
        result.addAll(MemoryText.listValues(emoji.aiTags));
        return result;
    }

    private static boolean sensitiveHit(List<String> tags, String[] sensitive) {
        for (String word : sensitive) {
            String value = word == null ? "" : word.trim();
            if (!value.isEmpty() && tags.contains(
                    value.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isGif(String mime, String fileName) {
        if (mime != null && mime.toLowerCase(Locale.ROOT).contains("gif")) {
            return true;
        }
        return fileName != null
                && fileName.toLowerCase(Locale.ROOT).endsWith(".gif");
    }

    private static boolean hit(int percent) {
        if (percent <= 0) {
            return false;
        }
        if (percent >= 100) {
            return true;
        }
        return ThreadLocalRandom.current().nextInt(100) < percent;
    }

    private static long todayStart() {
        return LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
    }
}
