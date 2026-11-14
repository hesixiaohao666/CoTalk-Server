package ai.pro;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import util.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AI Pro 记忆导出/导入。导出格式版本固定为 3201。
 */
public final class AiProImportExport {

    public static final String FORMAT = "cotalk-ai-pro-memory";
    public static final int VERSION = 3201;
    public static final long MAX_IMPORT_BYTES = 20L * 1024 * 1024;

    private static final Pattern MEMORY_ID =
            Pattern.compile("[A-Za-z0-9_-]{1,80}");

    private AiProImportExport() {
    }

    public static Path export(Path path, List<Memory> memories)
            throws Exception {
        Path absolute = path.toAbsolutePath();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("version", VERSION);
        root.addProperty("exportedAt", System.currentTimeMillis());
        JsonArray array = new JsonArray();
        if (memories != null) {
            for (Memory memory : memories) {
                array.add(memoryJson(memory));
            }
        }
        root.add("memories", array);
        Files.writeString(absolute, JsonUtil.stringify(root),
                StandardCharsets.UTF_8);
        return absolute;
    }

    public static ImportResult importFile(Path path,
                                          MemoryStore store,
                                          String operator)
            throws Exception {
        Path absolute = path.toAbsolutePath();
        if (!Files.isRegularFile(absolute)) {
            throw new IllegalArgumentException("导入文件不存在");
        }
        if (Files.size(absolute) > MAX_IMPORT_BYTES) {
            throw new IllegalArgumentException("导入文件过大");
        }
        JsonObject root = JsonUtil.parseObject(
                Files.readString(absolute, StandardCharsets.UTF_8));
        if (root == null || !root.has("format")
                || !FORMAT.equals(string(root, "format", ""))) {
            throw new IllegalArgumentException("不是有效的 AI Pro 记忆文件");
        }
        int version = intValue(root, "version", -1);
        if (version != VERSION) {
            throw new IllegalArgumentException(
                    "记忆文件版本不匹配，需要 " + VERSION);
        }
        if (!root.has("memories") || !root.get("memories").isJsonArray()) {
            throw new IllegalArgumentException("记忆文件缺少 memories");
        }

        JsonArray array = root.getAsJsonArray("memories");
        int imported = 0;
        int skipped = 0;
        long now = System.currentTimeMillis();
        for (JsonElement element : array) {
            if (element == null || !element.isJsonObject()) {
                throw new IllegalArgumentException("记忆记录格式无效");
            }
            Memory memory = parseMemory(element.getAsJsonObject(), now);
            if (store.findByMemoryId(memory.memoryId) != null
                    || store.findByHashAny(memory.contentHash) != null) {
                skipped++;
                continue;
            }
            store.insert(memory);
            imported++;
        }
        store.insertAudit(null, "import", null, null,
                null, null, operator,
                "import " + imported + ", skipped " + skipped,
                now);
        return new ImportResult(imported, skipped);
    }

    public static JsonObject memoryJson(Memory memory) {
        JsonObject json = new JsonObject();
        json.addProperty("memoryId", memory.memoryId);
        json.addProperty("layer", memory.layer.dbValue);
        json.addProperty("status", memory.status);
        json.addProperty("category", memory.category);
        json.addProperty("content", memory.content);
        json.addProperty("contentHash", memory.contentHash);
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
        if (memory.expiresAt == null) {
            json.add("expiresAt", JsonNull.INSTANCE);
        } else {
            json.addProperty("expiresAt", memory.expiresAt);
        }
        json.addProperty("mergedInto", memory.mergedInto);
        json.addProperty("auditNote", memory.auditNote);
        json.addProperty("createdAt", memory.createdAt);
        json.addProperty("updatedAt", memory.updatedAt);
        json.addProperty("lastAccessAt", memory.lastAccessAt);
        json.addProperty("stableSince", memory.stableSince);
        return json;
    }

    private static Memory parseMemory(JsonObject item, long now) {
        String content = MemoryText.normalize(string(item, "content", ""));
        if (content.isEmpty()) {
            throw new IllegalArgumentException("记忆内容不能为空");
        }
        if (content.length() > 10000) {
            throw new IllegalArgumentException("记忆内容过长");
        }
        String hash = MemoryText.contentHash(content);
        String memoryId = string(item, "memoryId", "").trim();
        if (memoryId.isEmpty()) {
            memoryId = "mem-" + UUID.randomUUID().toString()
                    .replace("-", "").substring(0, 8);
        } else if (!MEMORY_ID.matcher(memoryId).matches()) {
            throw new IllegalArgumentException("memoryId 无效");
        }

        Memory memory = new Memory();
        memory.memoryId = memoryId;
        memory.layer = MemoryLayer.fromDb(string(item, "layer", "cache"));
        String status = string(item, "status", "active").trim();
        memory.status = "archived".equals(status)
                || "deleted".equals(status) || "active".equals(status)
                ? status : "active";
        String category = string(item, "category", "fact").trim();
        memory.category = category.isEmpty() ? "fact"
                : category.substring(0, Math.min(32, category.length()));
        memory.content = content;
        memory.contentHash = hash;
        memory.keywords = listJson(item.get("keywords"));
        memory.tags = listJson(item.get("tags"));
        memory.subjectId = string(item, "subjectId", "").trim();
        memory.subjectName = string(item, "subjectName", "").trim();
        memory.source = MemorySource.fromDb(
                string(item, "source", "ai_inference"));
        double trust = doubleValue(item, "sourceTrust",
                memory.source.trust);
        memory.sourceTrust = Math.max(0, Math.min(
                memory.source.trust, trust));
        boolean lowTrust = memory.source == MemorySource.AI_SELF
                || memory.source == MemorySource.SYSTEM_EVENT;
        memory.explicitImportant = !lowTrust
                && boolValue(item, "explicitImportant", false);
        memory.accessCount = Math.max(0, intValue(
                item, "accessCount", 0));
        memory.taskRelevance = clamp(doubleValue(
                item, "taskRelevance", 0));
        memory.emotionalSalience = clamp(doubleValue(
                item, "emotionalSalience", 0));
        memory.reconstructability = clamp(doubleValue(
                item, "reconstructability", 0));
        memory.redundancy = clamp(doubleValue(item, "redundancy", 0));
        memory.importance = clamp(doubleValue(item, "importance", 0));
        memory.confidence = clamp(doubleValue(
                item, "confidence", 0.6));
        memory.createdAt = positive(longValue(
                item, "createdAt", now), now);
        memory.updatedAt = positive(longValue(
                item, "updatedAt", memory.createdAt), memory.createdAt);
        memory.lastAccessAt = positive(longValue(
                item, "lastAccessAt", memory.updatedAt),
                memory.updatedAt);
        memory.stableSince = positive(longValue(
                item, "stableSince", memory.createdAt),
                memory.createdAt);
        long expires = longValue(item, "expiresAt", 0);
        memory.expiresAt = expires > 0 ? expires : null;
        memory.mergedInto = string(item, "mergedInto", "").trim();
        memory.auditNote = string(item, "auditNote", "").trim();
        return memory;
    }

    private static String listJson(JsonElement element) {
        Set<String> values = new LinkedHashSet<>();
        if (element != null && element.isJsonArray()) {
            for (JsonElement value : element.getAsJsonArray()) {
                if (value != null && value.isJsonPrimitive()
                        && value.getAsJsonPrimitive().isString()) {
                    String text = MemoryText.normalize(value.getAsString());
                    if (!text.isEmpty()) {
                        values.add(text);
                    }
                }
            }
        }
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array.toString();
    }

    private static String string(JsonObject object, String key,
                                 String fallback) {
        if (object == null || !object.has(key)
                || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isString()) {
            return fallback;
        }
        return object.get(key).getAsString();
    }

    private static int intValue(JsonObject object, String key,
                                int fallback) {
        if (object == null || !object.has(key)
                || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        try {
            return object.get(key).getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long longValue(JsonObject object, String key,
                                  long fallback) {
        if (object == null || !object.has(key)
                || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        try {
            return object.get(key).getAsLong();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static double doubleValue(JsonObject object, String key,
                                      double fallback) {
        if (object == null || !object.has(key)
                || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()
                || !object.get(key).getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        try {
            return object.get(key).getAsDouble();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static boolean boolValue(JsonObject object, String key,
                                     boolean fallback) {
        if (object == null || !object.has(key)
                || object.get(key).isJsonNull()
                || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            if (object.get(key).getAsJsonPrimitive().isBoolean()) {
                return object.get(key).getAsBoolean();
            }
            if (object.get(key).getAsJsonPrimitive().isNumber()) {
                return object.get(key).getAsInt() != 0;
            }
            return fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    public static final class ImportResult {
        public final int imported;
        public final int skipped;

        ImportResult(int imported, int skipped) {
            this.imported = imported;
            this.skipped = skipped;
        }
    }
}
