package ai.pro;

import ai.AiReplySink;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 记忆巩固：缓存/浅层/深层流转、浅层压缩、缓存 TTL 与容量清理。
 */
public class MemoryConsolidator {

    public static final class Result {
        public int promoted;
        public int demoted;
        public int archived;
        public int deleted;
        public int merged;
    }

    private final MemoryStore store;
    private final AiProConfig config;
    private final AiReplySink sink;

    public MemoryConsolidator(MemoryStore store,
                              AiProConfig config,
                              AiReplySink sink) {
        this.store = store;
        this.config = config;
        this.sink = sink;
    }

    public Result consolidate(long roundNumber) throws Exception {
        Result result = new Result();
        long now = System.currentTimeMillis();
        int maxMoves = config.getInt("ai.pro.layer.maxMovesPerRound");
        long cooldown = config.getInt("ai.pro.layer.cooldownMs");

        List<Memory> cache = store.activeByLayer(MemoryLayer.CACHE);
        List<Memory> shallow = store.activeByLayer(MemoryLayer.SHALLOW);
        List<Memory> deep = store.activeByLayer(MemoryLayer.DEEP);

        // 缓存 -> 浅层
        for (Memory memory : new ArrayList<>(cache)) {
            if (result.promoted + result.demoted >= maxMoves) {
                break;
            }
            if (now - memory.stableSince < cooldown) {
                continue;
            }
            boolean important = memory.explicitImportant
                    || (memory.importance >= config.getDouble(
                    "ai.pro.cacheToShallow.importance")
                    && memory.accessCount >= config.getInt(
                    "ai.pro.cacheToShallow.hits"));
            if (!important) {
                continue;
            }
            promote(memory, MemoryLayer.SHALLOW, now);
            shallow.add(memory);
            cache.remove(memory);
            result.promoted++;
        }

        // 深层 -> 浅层
        for (Memory memory : new ArrayList<>(deep)) {
            if (result.promoted + result.demoted >= maxMoves) {
                break;
            }
            if (now - memory.stableSince < cooldown) {
                continue;
            }
            if (memory.importance < config.getDouble(
                    "ai.pro.deepToShallow.importance")
                    && now - memory.lastAccessAt >= config.getInt(
                    "ai.pro.deepToShallow.idleDays") * 86_400_000L) {
                demote(memory, MemoryLayer.SHALLOW, now);
                deep.remove(memory);
                shallow.add(memory);
                result.demoted++;
            }
        }

        // 浅层 -> 深层
        for (Memory memory : new ArrayList<>(shallow)) {
            if (result.promoted + result.demoted >= maxMoves) {
                break;
            }
            if (now - memory.stableSince < cooldown) {
                continue;
            }
            boolean stable = now - memory.stableSince >= config.getInt(
                    "ai.pro.shallowToDeep.stableDays") * 86_400_000L;
            boolean frequent = memory.accessCount >= config.getInt(
                    "ai.pro.shallowToDeep.hits");
            if (memory.importance >= config.getDouble(
                    "ai.pro.shallowToDeep.importance")
                    && (stable || frequent)) {
                promote(memory, MemoryLayer.DEEP, now);
                shallow.remove(memory);
                deep.add(memory);
                result.promoted++;
            }
        }

        enforceDeepLimit(deep, shallow, maxMoves, cooldown, now, result);
        compressShallow(shallow, cache, maxMoves, cooldown, now, result);
        cleanupCache(cache, roundNumber, now, result);
        return result;
    }

    private void enforceDeepLimit(List<Memory> deep,
                                  List<Memory> shallow,
                                  int maxMoves,
                                  long cooldown,
                                  long now,
                                  Result result) throws Exception {
        int max = config.getInt("ai.pro.deep.max");
        if (deep.size() <= max) {
            return;
        }
        deep.sort((left, right) -> Double.compare(
                left.importance, right.importance));
        for (Memory memory : new ArrayList<>(deep)) {
            if (deep.size() <= max
                    || result.promoted + result.demoted >= maxMoves) {
                break;
            }
            if (now - memory.stableSince < cooldown) {
                continue;
            }
            demote(memory, MemoryLayer.SHALLOW, now);
            deep.remove(memory);
            shallow.add(memory);
            result.demoted++;
        }
    }

    private void compressShallow(List<Memory> shallow,
                                 List<Memory> cache,
                                 int maxMoves,
                                 long cooldown,
                                 long now,
                                 Result result) throws Exception {
        int max = config.getInt("ai.pro.shallow.max");
        if (shallow.size() <= max) {
            return;
        }
        int keep = Math.max(1, (int) Math.floor(
                max * config.getDouble("ai.pro.compress.keepRatio")));
        shallow.sort((left, right) -> {
            int importance = Double.compare(
                    right.importance, left.importance);
            if (importance != 0) {
                return importance;
            }
            return Long.compare(right.lastAccessAt, left.lastAccessAt);
        });
        List<Memory> kept = new ArrayList<>(
                shallow.subList(0, Math.min(keep, shallow.size())));
        List<Memory> extras = new ArrayList<>(
                shallow.subList(Math.min(keep, shallow.size()), shallow.size()));
        for (Memory extra : extras) {
            Memory similar = mostSimilar(kept, extra);
            if (similar != null) {
                merge(similar, extra, now);
                archive(extra, similar.memoryId, now);
                shallow.remove(extra);
                result.merged++;
                result.archived++;
                continue;
            }
            if (cache.size() < config.getInt("ai.pro.cache.max")
                    && result.promoted + result.demoted < maxMoves
                    && now - extra.stableSince >= cooldown) {
                demote(extra, MemoryLayer.CACHE, now);
                shallow.remove(extra);
                cache.add(extra);
                result.demoted++;
            } else if (!extra.explicitImportant
                    && extra.source != MemorySource.ADMIN) {
                archive(extra, "", now);
                shallow.remove(extra);
                result.archived++;
            }
        }
    }

    private void cleanupCache(List<Memory> cache,
                              long roundNumber,
                              long now,
                              Result result) throws Exception {
        int max = config.getInt("ai.pro.cache.max");
        int every = config.getInt("ai.pro.cache.cleanupEveryRounds");
        if (cache.size() <= max
                && (every <= 0 || roundNumber % every != 0)) {
            return;
        }
        int ttl = config.getInt("ai.pro.cache.ttlDays") * 86_400_000;
        for (Memory memory : new ArrayList<>(cache)) {
            if (memory.expiresAt != null && memory.expiresAt <= now) {
                if (memory.accessCount == 0
                        && !memory.explicitImportant
                        && memory.source != MemorySource.ADMIN) {
                    delete(memory, now);
                    cache.remove(memory);
                    result.deleted++;
                } else if (memory.accessCount > 0) {
                    long expires = now + ttl;
                    store.updateExpires(memory.memoryId, expires);
                    memory.expiresAt = expires;
                }
            }
        }
        if (cache.size() <= max) {
            return;
        }
        cache.sort((left, right) -> Double.compare(
                MemoryScoring.evictionPriority(right, config, now),
                MemoryScoring.evictionPriority(left, config, now)));
        int target = Math.max(1, (int) Math.floor(
                max * config.getDouble("ai.pro.cache.cleanupTargetRatio")));
        for (Memory memory : new ArrayList<>(cache)) {
            if (cache.size() <= target) {
                break;
            }
            if (memory.explicitImportant
                    || memory.source == MemorySource.ADMIN) {
                continue;
            }
            delete(memory, now);
            cache.remove(memory);
            result.deleted++;
        }
        try {
            long cleanupAt = now - 30L * 86_400_000L;
            store.deletePhysicalBefore(cleanupAt);
        } catch (Exception e) {
            sink.reportError("AI Pro 物理清理失败：" + e.getMessage());
        }
    }

    private void promote(Memory memory, MemoryLayer layer, long now)
            throws Exception {
        MemoryLayer before = memory.layer;
        store.updateLayer(memory, layer, now);
        store.insertAudit(memory.memoryId, "promote",
                before, layer, null, null,
                "system", "consolidate", now);
    }

    private void demote(Memory memory, MemoryLayer layer, long now)
            throws Exception {
        MemoryLayer before = memory.layer;
        store.updateLayer(memory, layer, now);
        store.insertAudit(memory.memoryId, "demote",
                before, layer, null, null,
                "system", "consolidate", now);
    }

    private void archive(Memory memory, String mergedInto, long now)
            throws Exception {
        store.updateStatus(memory.memoryId, "archived", mergedInto, now);
        store.insertAudit(memory.memoryId, "archive",
                memory.layer, memory.layer, null, null,
                "system", mergedInto.isEmpty()
                ? "compress" : "compress-merge", now);
    }

    private void delete(Memory memory, long now) throws Exception {
        store.updateStatus(memory.memoryId, "deleted", "", now);
        store.insertAudit(memory.memoryId, "delete",
                memory.layer, memory.layer, null, null,
                "system", "cache-cleanup", now);
    }

    private void merge(Memory primary, Memory candidate, long now)
            throws Exception {
        primary.accessCount = Math.min(
                config.getInt("ai.pro.memory.maxAccessCount"),
                primary.accessCount + candidate.accessCount + 1);
        primary.explicitImportant = primary.explicitImportant
                || candidate.explicitImportant;
        primary.keywords = unionJson(primary.keywords, candidate.keywords,
                config.getInt("ai.pro.merge.maxKeywords"));
        primary.tags = unionJson(primary.tags, candidate.tags,
                config.getInt("ai.pro.merge.maxTags"));
        if (candidate.sourceTrust > primary.sourceTrust) {
            primary.source = candidate.source;
            primary.sourceTrust = candidate.sourceTrust;
        }
        primary.updatedAt = now;
        primary.lastAccessAt = Math.max(primary.lastAccessAt, now);
        primary.confidence = Math.max(primary.confidence, candidate.confidence);
        primary.importance = MemoryScoring.importance(primary, config, now);
        store.updateMerge(primary);
        store.insertAudit(primary.memoryId, "merge",
                primary.layer, primary.layer, null, null,
                "system", "compress", now);
    }

    private Memory mostSimilar(List<Memory> kept, Memory extra) {
        double threshold = config.getDouble("ai.pro.merge.similarity");
        Memory best = null;
        double bestSimilarity = 0;
        for (Memory memory : kept) {
            double similarity = MemoryText.similarity(memory, extra);
            if (similarity > threshold && similarity > bestSimilarity) {
                bestSimilarity = similarity;
                best = memory;
            }
        }
        return best;
    }

    private String unionJson(String left, String right, int max) {
        Set<String> values = new LinkedHashSet<>();
        values.addAll(MemoryText.listValues(left));
        values.addAll(MemoryText.listValues(right));
        com.google.gson.JsonArray array = new com.google.gson.JsonArray();
        int count = 0;
        for (String value : values) {
            if (count++ >= max) {
                break;
            }
            array.add(value);
        }
        return array.toString();
    }
}
