package ai.pro;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import model.Message;
import server.ServerConfig;
import store.Database;
import util.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * AI Pro 本机管理：统计、记忆/待办/表情管理与导出。GUI 与 Headless 共用。
 */
public class AiProAdminService {

    private final Database database;
    private final ServerConfig config;
    private final MemoryStore memoryStore;
    private final CoreMemoryStore coreMemoryStore;
    private final AiTodoStore todoStore;
    private final AiEmojiStore emojiStore;
    private final EmotionStore emotionStore;
    private final Consumer<String> logger;
    private volatile Runnable indexRebuilder;
    private volatile Runnable emotionResetter;

    public AiProAdminService(Database database,
                             ServerConfig config,
                             Consumer<String> logger) throws Exception {
        this.database = database;
        this.config = config;
        this.memoryStore = new MemoryStore(database);
        this.coreMemoryStore = new CoreMemoryStore(database);
        this.coreMemoryStore.initialize(config.aiName,
                config.aiPromptCustom, config.aiPro.ownerNames());
        this.todoStore = new AiTodoStore(database);
        this.emojiStore = new AiEmojiStore(
                database, Paths.get("data", "ai-pro", "emoji"));
        this.emotionStore = new EmotionStore(database);
        this.logger = logger == null ? message -> { } : logger;
    }

    public void setIndexRebuilder(Runnable indexRebuilder) {
        this.indexRebuilder = indexRebuilder;
    }

    public void setEmotionResetter(Runnable emotionResetter) {
        this.emotionResetter = emotionResetter;
    }

    public String statsText() {
        try {
            long now = System.currentTimeMillis();
            long lastRound = parseLong(memoryStore.getMeta(
                    "last_round_at", "0"), 0);
            long nextRound = lastRound == 0 ? 0
                    : lastRound + config.aiPro.getInt(
                    "ai.pro.round.intervalMs");
            EmotionState emotion = emotionStore.load();
            return "记忆：深层 " + memoryStore.countActive(MemoryLayer.DEEP)
                    + "，浅层 " + memoryStore.countActive(MemoryLayer.SHALLOW)
                    + "，缓存 " + memoryStore.countActive(MemoryLayer.CACHE)
                    + "，数据库 " + (memoryStore.databaseBytes() / 1024)
                    + " KB\n"
                    + "轮次：" + memoryStore.getMeta("round_count", "0")
                    + "，上次 " + formatTime(lastRound)
                    + "，下次 " + formatTime(nextRound) + "\n"
                    + "情绪：" + emotion.mood + "，强度 "
                    + String.format(java.util.Locale.ROOT, "%.2f",
                    emotion.intensity);
        } catch (Exception e) {
            return "统计失败：" + e.getMessage();
        }
    }

    public List<Memory> listMemories(String query,
                                     MemoryLayer layer,
                                     String status,
                                     int limit,
                                     int offset) throws Exception {
        return memoryStore.search(query, layer, status, limit, offset);
    }

    public Memory addMemory(String content,
                            MemoryLayer layer,
                            String category,
                            String keywords,
                            String tags,
                            boolean explicit,
                            String operator) throws Exception {
        long now = System.currentTimeMillis();
        Memory memory = new Memory();
        memory.memoryId = "mem-" + java.util.UUID.randomUUID()
                .toString().replace("-", "").substring(0, 8);
        memory.content = MemoryText.normalize(content);
        memory.contentHash = MemoryText.contentHash(memory.content);
        memory.layer = layer == null ? MemoryLayer.SHALLOW : layer;
        memory.status = "active";
        memory.category = category == null || category.isEmpty()
                ? "fact" : category;
        memory.keywords = keywords == null ? "[]" : keywords;
        memory.tags = tags == null ? "[]" : tags;
        memory.source = MemorySource.ADMIN;
        memory.sourceTrust = 1.0;
        memory.explicitImportant = explicit;
        memory.accessCount = 0;
        memory.lastAccessAt = now;
        memory.createdAt = now;
        memory.updatedAt = now;
        memory.stableSince = now;
        memory.confidence = 1.0;
        memory.importance = MemoryScoring.importance(
                memory, config.aiPro, now);
        if (memory.layer == MemoryLayer.CACHE) {
            memory.expiresAt = now + config.aiPro.getInt(
                    "ai.pro.cache.ttlDays") * 86_400_000L;
        }
        memoryStore.insert(memory);
        audit(memory.memoryId, "create", null, memory.layer,
                null, JsonUtil.stringify(memoryJson(memory)),
                operator, "admin create");
        rebuildIndex();
        return memory;
    }

    public void editMemory(String memoryId,
                           String content,
                           String keywords,
                           String tags,
                           boolean explicit,
                           String status,
                           String operator) throws Exception {
        Memory memory = memoryStore.findByMemoryId(memoryId);
        if (memory == null) {
            throw new IllegalArgumentException("记忆不存在");
        }
        String before = JsonUtil.stringify(memoryJson(memory));
        memory.content = MemoryText.normalize(content);
        memory.contentHash = MemoryText.contentHash(memory.content);
        memory.keywords = keywords == null ? memory.keywords : keywords;
        memory.tags = tags == null ? memory.tags : tags;
        memory.explicitImportant = explicit;
        if (status != null && !status.trim().isEmpty()) {
            memory.status = status.trim();
        }
        memory.updatedAt = System.currentTimeMillis();
        memory.importance = MemoryScoring.importance(
                memory, config.aiPro, memory.updatedAt);
        memoryStore.updateAdmin(memory);
        audit(memory.memoryId, "admin_edit", memory.layer, memory.layer,
                before, JsonUtil.stringify(memoryJson(memory)),
                operator, "admin edit");
        rebuildIndex();
    }

    public void setStatus(String memoryId,
                          String status,
                          String operator) throws Exception {
        Memory memory = memoryStore.findByMemoryId(memoryId);
        if (memory == null) {
            return;
        }
        String action = "archived".equals(status) ? "archive" : "restore";
        memoryStore.updateStatus(memoryId, status, "", System.currentTimeMillis());
        audit(memoryId, action, memory.layer, memory.layer,
                null, null, operator, "admin status");
        rebuildIndex();
    }

    public void deleteMemory(String memoryId,
                             boolean hard,
                             String operator) throws Exception {
        Memory memory = memoryStore.findByMemoryId(memoryId);
        if (memory == null) {
            return;
        }
        if (hard) {
            memoryStore.deleteHard(memoryId);
        } else {
            memoryStore.updateStatus(memoryId, "deleted", "",
                    System.currentTimeMillis());
        }
        audit(memoryId, "delete", memory.layer, memory.layer,
                null, null, operator, hard ? "hard delete" : "soft delete");
        rebuildIndex();
    }

    public int clearLayer(MemoryLayer layer, String operator)
            throws Exception {
        int count = memoryStore.clearLayer(layer);
        memoryStore.insertAudit(null, "clear", layer, layer,
                null, null, operator, "clear " + count, System.currentTimeMillis());
        rebuildIndex();
        return count;
    }

    public int clearAll(String operator) throws Exception {
        int count = memoryStore.clearAll();
        memoryStore.insertAudit(null, "clear", null, null,
                null, null, operator, "clear all " + count,
                System.currentTimeMillis());
        rebuildIndex();
        return count;
    }

    public Path exportJson(Path path, String operator) throws Exception {
        Path absolute = path.toAbsolutePath();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        JsonArray array = new JsonArray();
        for (Memory memory : memoryStore.allMemories()) {
            array.add(memoryJson(memory));
        }
        Files.writeString(absolute, JsonUtil.stringify(array),
                StandardCharsets.UTF_8);
        memoryStore.insertAudit(null, "export", null, null,
                null, null, operator, "export " + array.size(),
                System.currentTimeMillis());
        return absolute;
    }

    public CoreMemory coreMemory() throws Exception {
        return coreMemoryStore.load();
    }

    public void updateCustomPrompt(String content, String operator)
            throws Exception {
        coreMemoryStore.updateCustom(content);
        memoryStore.insertAudit(null, "admin_edit", null, null,
                null, null, operator, "core custom prompt",
                System.currentTimeMillis());
    }

    public List<AiTodo> listTodos(int limit) throws Exception {
        return todoStore.listAll(limit);
    }

    public void todoAction(String todoId,
                           String action,
                           long deferTo,
                           String operator) throws Exception {
        AiTodo todo = todoStore.findByTodoId(todoId);
        if (todo == null) {
            throw new IllegalArgumentException("待办不存在");
        }
        long now = System.currentTimeMillis();
        if ("done".equals(action)) {
            todoStore.updateStatus(todoId, "done", todo.remindAt, now);
        } else if ("cancel".equals(action)) {
            todoStore.updateStatus(todoId, "cancelled", todo.remindAt, now);
        } else if ("defer".equals(action) && deferTo > now) {
            todoStore.updateStatus(todoId, "pending", deferTo, now);
        } else {
            throw new IllegalArgumentException("待办操作无效");
        }
        todoStore.insertEvent(todoId, action, operator, "", now);
    }

    public List<AiEmoji> listEmojis(int limit) throws Exception {
        return emojiStore.listAll(limit);
    }

    public AiEmoji importEmoji(Path path, String name, String tags,
                               String operator) throws Exception {
        AiEmoji emoji = emojiStore.importFile(
                path, name, tags, "admin", operator);
        logger.accept("导入表情 " + emoji.emojiId);
        return emoji;
    }

    public void setEmojiEnabled(String emojiId,
                                boolean enabled,
                                String operator) throws Exception {
        emojiStore.setEnabled(emojiId, enabled);
        memoryStore.insertAudit(null, "admin_edit", null, null,
                null, null, operator,
                (enabled ? "enable emoji " : "disable emoji ") + emojiId,
                System.currentTimeMillis());
    }

    public void updateEmojiTags(String emojiId,
                                String tags,
                                String operator) throws Exception {
        emojiStore.updateTags(emojiId, tags);
        memoryStore.insertAudit(null, "admin_edit", null, null,
                null, null, operator, "emoji tags " + emojiId,
                System.currentTimeMillis());
    }

    public void deleteEmoji(String emojiId, String operator) throws Exception {
        emojiStore.delete(emojiId);
        memoryStore.insertAudit(null, "delete", null, null,
                null, null, operator, "delete emoji " + emojiId,
                System.currentTimeMillis());
    }

    public String emotionText() throws Exception {
        EmotionState state = emotionStore.load();
        return state.mood + "，强度 "
                + String.format(java.util.Locale.ROOT, "%.2f",
                state.intensity);
    }

    public void resetEmotion(String operator) throws Exception {
        Runnable resetter = emotionResetter;
        if (resetter != null) {
            resetter.run();
        } else {
            emotionStore.reset();
        }
        memoryStore.insertAudit(null, "admin_edit", null, null,
                null, null, operator, "reset emotion",
                System.currentTimeMillis());
    }

    private void audit(String memoryId,
                       String action,
                       MemoryLayer beforeLayer,
                       MemoryLayer afterLayer,
                       String before,
                       String afterJson,
                       String operator,
                       String note) throws Exception {
        memoryStore.insertAudit(memoryId, action, beforeLayer, afterLayer,
                truncate(before), truncate(afterJson),
                operator, note, System.currentTimeMillis());
    }

    private void rebuildIndex() {
        Runnable rebuild = indexRebuilder;
        if (rebuild != null) {
            try {
                rebuild.run();
            } catch (Exception e) {
                logger.accept("重建 AI Pro 索引失败：" + e.getMessage());
            }
        }
    }

    private static JsonObject memoryJson(Memory memory) {
        JsonObject json = new JsonObject();
        json.addProperty("memoryId", memory.memoryId);
        json.addProperty("layer", memory.layer.dbValue);
        json.addProperty("status", memory.status);
        json.addProperty("category", memory.category);
        json.addProperty("content", memory.content);
        json.addProperty("keywords", memory.keywords);
        json.addProperty("tags", memory.tags);
        json.addProperty("subjectId", memory.subjectId);
        json.addProperty("subjectName", memory.subjectName);
        json.addProperty("source", memory.source.dbValue);
        json.addProperty("sourceTrust", memory.sourceTrust);
        json.addProperty("explicitImportant", memory.explicitImportant);
        json.addProperty("accessCount", memory.accessCount);
        json.addProperty("taskRelevance", memory.taskRelevance);
        json.addProperty("emotionalSalience", memory.emotionalSalience);
        json.addProperty("reconstructability", memory.reconstructability);
        json.addProperty("redundancy", memory.redundancy);
        json.addProperty("importance", memory.importance);
        json.addProperty("confidence", memory.confidence);
        json.addProperty("expiresAt", memory.expiresAt);
        json.addProperty("mergedInto", memory.mergedInto);
        json.addProperty("auditNote", memory.auditNote);
        json.addProperty("createdAt", memory.createdAt);
        json.addProperty("updatedAt", memory.updatedAt);
        json.addProperty("lastAccessAt", memory.lastAccessAt);
        json.addProperty("stableSince", memory.stableSince);
        return json;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 2000 ? value
                : value.substring(0, 2000);
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String formatTime(long value) {
        if (value <= 0) {
            return "-";
        }
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                java.util.Locale.ROOT)
                .format(new java.util.Date(value));
    }
}
