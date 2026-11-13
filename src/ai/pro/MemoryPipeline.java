package ai.pro;

import ai.AiMessage;
import ai.AiReplySink;
import ai.DeepSeekClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import model.Message;
import server.MessageService;
import store.Database;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮次记忆写入：读取消息窗口 → 抽取 → 过滤 → 去重/合并 → 计分 → 写缓存。
 */
public class MemoryPipeline {

    private final MessageService messageService;
    private final DeepSeekClient client;
    private final AiProConfig config;
    private final AiReplySink sink;
    private final MemoryStore store;
    private final MemoryIndexManager indexManager;
    private final MemoryExtractor extractor;
    private final MemoryConsolidator consolidator;
    private final EmotionEngine emotionEngine;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "cotalk-ai-memory-timer");
                thread.setDaemon(true);
                return thread;
            });
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "cotalk-ai-memory");
                thread.setDaemon(true);
                return thread;
            });

    private volatile boolean closed;
    private final AtomicBoolean inFlight = new AtomicBoolean(false);
    private final AtomicBoolean rerun = new AtomicBoolean(false);
    private final AtomicInteger pendingMessages = new AtomicInteger(0);
    private volatile long lastRoundAt;
    private ScheduledFuture<?> timer;

    public MemoryPipeline(Database database,
                          MessageService messageService,
                          DeepSeekClient client,
                          AiProConfig config,
                          AiReplySink sink,
                          MemoryStore store,
                          MemoryIndexManager indexManager) {
        this(database, messageService, client, config, sink,
                store, indexManager, null);
    }

    public MemoryPipeline(Database database,
                          MessageService messageService,
                          DeepSeekClient client,
                          AiProConfig config,
                          AiReplySink sink,
                          MemoryStore store,
                          MemoryIndexManager indexManager,
                          EmotionEngine emotionEngine) {
        this.messageService = messageService;
        this.client = client;
        this.config = config;
        this.sink = sink;
        this.store = store;
        this.indexManager = indexManager;
        this.emotionEngine = emotionEngine;
        this.extractor = new MemoryExtractor(config);
        this.consolidator = new MemoryConsolidator(store, config, sink);
    }

    public void start() {
        try {
            long maxSeq = messageService.lastSeq();
            String lastSeq = store.getMeta("last_processed_seq", null);
            if (lastSeq == null) {
                long startSeq = config.getBoolean(
                        "ai.pro.migrate.importHistory")
                        ? Math.max(0, maxSeq - config.getInt(
                        "ai.pro.round.maxMessages"))
                        : maxSeq;
                store.setMeta("last_processed_seq",
                        String.valueOf(startSeq));
            }
            String roundAt = store.getMeta("last_round_at", null);
            lastRoundAt = roundAt == null
                    ? System.currentTimeMillis()
                    : parseLong(roundAt, System.currentTimeMillis());
            if (roundAt == null) {
                store.setMeta("last_round_at",
                        String.valueOf(lastRoundAt));
            }
            indexManager.rebuildQuietly();
        } catch (Exception e) {
            sink.reportError("AI Pro 记忆管线初始化失败："
                    + e.getMessage());
        }
    }

    public void onMessage(Message message) {
        if (closed || message == null
                || "system".equals(message.type)
                || ai.AiResponder.AI_USER_ID.equals(message.senderId)) {
            return;
        }
        int pending = pendingMessages.incrementAndGet();
        if (inFlight.get()) {
            if (pending >= config.getInt(
                    "ai.pro.round.messageCount")) {
                rerun.set(true);
            }
            return;
        }
        if (pending >= config.getInt("ai.pro.round.messageCount")) {
            trigger("messages");
            return;
        }
        long interval = config.getInt("ai.pro.round.intervalMs");
        long delay = Math.max(1000L,
                lastRoundAt + interval - System.currentTimeMillis());
        synchronized (this) {
            if (timer == null || timer.isDone()) {
                timer = scheduler.schedule(
                        () -> trigger("interval"), delay,
                        TimeUnit.MILLISECONDS);
            }
        }
    }

    private void trigger(String type) {
        if (closed) {
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            rerun.set(true);
            return;
        }
        worker.execute(() -> {
            try {
                runRound(type);
            } finally {
                inFlight.set(false);
                if (rerun.getAndSet(false) && !closed) {
                    trigger("pending");
                } else if (!closed) {
                    scheduleTimer();
                }
            }
        });
    }

    private void scheduleTimer() {
        synchronized (this) {
            if (closed || (timer != null && !timer.isDone())) {
                return;
            }
            long interval = config.getInt("ai.pro.round.intervalMs");
            long delay = Math.max(1000L,
                    lastRoundAt + interval - System.currentTimeMillis());
            timer = scheduler.schedule(
                    () -> trigger("interval"), delay,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void runRound(String type) {
        long startedAt = System.currentTimeMillis();
        long fromSeq = 0;
        long toSeq = 0;
        int extractedCount = 0;
        int inserted = 0;
        int merged = 0;
        try {
            fromSeq = parseLong(store.getMeta(
                    "last_processed_seq", "0"), 0);
            long lastSeq = messageService.lastSeq();
            List<Message> window = messageService.after(
                    fromSeq, config.getInt("ai.pro.round.maxMessages"));
            pendingMessages.set(0);
            if (window.isEmpty()) {
                if (lastSeq > fromSeq) {
                    store.setMeta("last_processed_seq",
                            String.valueOf(lastSeq));
                }
                store.setMeta("last_round_at",
                        String.valueOf(System.currentTimeMillis()));
                lastRoundAt = System.currentTimeMillis();
                return;
            }
            toSeq = window.get(window.size() - 1).seq;
            List<Message> human = new ArrayList<>();
            for (Message message : window) {
                if (message == null || "system".equals(message.type)
                        || ai.AiResponder.AI_USER_ID.equals(message.senderId)) {
                    continue;
                }
                human.add(message);
            }
            if (human.size() < config.getInt(
                    "ai.pro.round.minMessages")) {
                finishRound(startedAt, type, fromSeq, toSeq, human.size(),
                        0, 0, 0, "skipped", "");
                return;
            }

            List<AiMessage> request = extractor.buildMessages(human);
            String raw = client.chatCompletion(
                    request,
                    config.getInt("ai.pro.extract.maxTokens"),
                    false);
            List<MemoryExtractor.Extracted> candidates =
                    extractor.parse(raw);
            extractedCount = candidates.size();
            if (emotionEngine != null) {
                MemoryExtractor.ExtractedEmotion emotion =
                        extractor.parseEmotion(raw);
                if (emotion != null) {
                    emotionEngine.applyAiEmotion(emotion.mood,
                            emotion.valence, emotion.arousal,
                            emotion.intensity);
                }
            }

            List<Memory> active = store.activeMemories();
            String explicitText = joinContents(human);
            boolean explicitTrigger = containsExplicit(explicitText);
            boolean confirmed = containsConfirmation(explicitText);
            double mergeThreshold = config.getDouble(
                    "ai.pro.merge.similarity");
            List<java.util.regex.Pattern> custom =
                    MemoryFilter.customPatterns(config);
            for (MemoryExtractor.Extracted candidate : candidates) {
                String filtered = MemoryFilter.filter(
                        candidate.content, config,
                        config.getInt("ai.pro.extract.minChars"));
                if (filtered == null) {
                    continue;
                }
                if (MemoryFilter.matchesCustom(filtered, custom)) {
                    if (!"redact".equalsIgnoreCase(config.getString(
                            "ai.pro.privacy.mode"))) {
                        continue;
                    }
                    for (java.util.regex.Pattern pattern : custom) {
                        filtered = pattern.matcher(filtered)
                                .replaceAll("[隐私]");
                    }
                }

                Memory memory = toMemory(
                        candidate, filtered, explicitTrigger,
                        confirmed, active, mergeThreshold);
                Memory duplicate = exactDuplicate(active, memory.contentHash);
                if (duplicate == null) {
                    double maxSimilarity = maxSimilarity(active, memory);
                    memory.redundancy = MemoryScoring.redundancy(
                            maxSimilarity);
                    if (maxSimilarity > mergeThreshold) {
                        duplicate = mostSimilar(active, memory);
                    }
                }
                if (duplicate != null) {
                    merge(duplicate, memory, active, mergeThreshold);
                    merged++;
                } else {
                    memory.importance = MemoryScoring.importance(
                            memory, config, System.currentTimeMillis());
                    if (memory.layer == MemoryLayer.CACHE) {
                        int ttlDays = memory.importance < 0.3
                                && memory.accessCount == 0
                                ? config.getInt("ai.pro.cache.ttlMinDays")
                                : config.getInt("ai.pro.cache.ttlDays");
                        memory.expiresAt = System.currentTimeMillis()
                                + ttlDays * 86_400_000L;
                    }
                    store.insert(memory);
                    store.insertAudit(memory.memoryId, "create",
                            null, memory.layer, null, null,
                            "system", "round-extract",
                            System.currentTimeMillis());
                    active.add(memory);
                    inserted++;
                }
            }

            finishRound(startedAt, type, fromSeq, toSeq, human.size(),
                    extractedCount, inserted, merged, "ok", "");
            try {
                long roundNumber = parseLong(
                        store.getMeta("round_count", "0"), 0);
                consolidator.consolidate(roundNumber);
            } catch (Exception e) {
                sink.reportError("AI Pro 记忆巩固失败：" + e.getMessage());
            }
            indexManager.rebuildQuietly();
        } catch (Exception e) {
            sink.reportError("AI Pro 记忆轮次失败：" + e.getMessage());
            try {
                store.insertRound(startedAt, System.currentTimeMillis(),
                        type, fromSeq, toSeq, pendingMessages.get(),
                        extractedCount, inserted, merged, "failed",
                        e.getMessage());
            } catch (Exception ignored) {
                // 记录失败不影响回复。
            }
            storeLastRoundAt();
        }
    }

    private void finishRound(long startedAt,
                             String triggerType,
                             long fromSeq,
                             long toSeq,
                             int messageCount,
                             int extracted,
                             int inserted,
                             int merged,
                             String status,
                             String error) throws Exception {
        store.setMeta("last_processed_seq", String.valueOf(toSeq));
        storeLastRoundAt();
        long rounds = parseLong(store.getMeta("round_count", "0"), 0) + 1;
        store.setMeta("round_count", String.valueOf(rounds));
        store.insertRound(startedAt, System.currentTimeMillis(), triggerType,
                fromSeq, toSeq, messageCount,
                extracted, inserted, merged, status, error);
    }

    private void storeLastRoundAt() {
        lastRoundAt = System.currentTimeMillis();
        try {
            store.setMeta("last_round_at", String.valueOf(lastRoundAt));
        } catch (Exception ignored) {
            // 元数据失败不阻塞后续轮次。
        }
    }

    private Memory toMemory(MemoryExtractor.Extracted candidate,
                            String content,
                            boolean explicitTrigger,
                            boolean confirmed,
                            List<Memory> active,
                            double mergeThreshold) {
        long now = System.currentTimeMillis();
        Memory memory = new Memory();
        memory.memoryId = "mem-" + UUID.randomUUID().toString()
                .replace("-", "").substring(0, 8);
        memory.content = content;
        memory.contentHash = MemoryText.contentHash(content);
        memory.layer = MemoryLayer.CACHE;
        memory.category = candidate.category;
        memory.keywords = candidate.keywords;
        memory.tags = candidate.tags;
        memory.subjectName = candidate.subject;
        memory.source = remapSource(candidate, confirmed);
        memory.sourceTrust = memory.source.trust;
        memory.explicitImportant = candidate.explicit
                && (explicitTrigger || memory.source == MemorySource.ADMIN);
        if (memory.explicitImportant
                && (explicitTrigger || memory.source == MemorySource.ADMIN)) {
            memory.layer = MemoryLayer.SHALLOW;
        }
        memory.accessCount = 0;
        memory.lastAccessAt = now;
        memory.createdAt = now;
        memory.updatedAt = now;
        memory.stableSince = now;
        memory.taskRelevance = candidate.taskRelevance;
        memory.emotionalSalience = candidate.emotionalSalience;
        memory.reconstructability = candidate.reconstructable ? 1 : 0;
        memory.confidence = candidate.confidence;
        memory.redundancy = MemoryScoring.redundancy(
                maxSimilarity(active, memory));
        memory.importance = MemoryScoring.importance(memory, config, now);
        return memory;
    }

    private MemorySource remapSource(MemoryExtractor.Extracted candidate,
                                     boolean confirmed) {
        if (candidate.source == MemorySource.ADMIN) {
            return MemorySource.ADMIN;
        }
        if (candidate.source == MemorySource.USER_CONFIRMED && confirmed) {
            return MemorySource.USER_CONFIRMED;
        }
        List<String> owners = ownerNames();
        if (owners.isEmpty()) {
            return MemorySource.USER_STATEMENT;
        }
        String subject = candidate.subject == null
                ? "" : candidate.subject.trim();
        for (String owner : owners) {
            if (owner.equalsIgnoreCase(subject)) {
                return MemorySource.USER_STATEMENT;
            }
        }
        return MemorySource.GROUP_MEMBER;
    }

    private List<String> ownerNames() {
        List<String> result = new ArrayList<>();
        String value = config.ownerNames();
        if (value == null || value.trim().isEmpty()) {
            return result;
        }
        for (String part : value.split("[,，\\s]+")) {
            if (!part.trim().isEmpty()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private void merge(Memory primary,
                       Memory candidate,
                       List<Memory> active,
                       double threshold) {
        long now = System.currentTimeMillis();
        primary.accessCount = Math.min(
                config.getInt("ai.pro.memory.maxAccessCount"),
                primary.accessCount + 1);
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
        if (candidate.content.length() > primary.content.length()
                && candidate.content.length()
                <= config.getInt("ai.pro.extract.maxChars")) {
            primary.content = candidate.content;
            primary.contentHash = candidate.contentHash;
        } else if (!candidate.content.equals(primary.content)) {
            primary.auditNote = "merge: " + candidate.content;
        }
        primary.createdAt = Math.min(primary.createdAt, candidate.createdAt);
        primary.updatedAt = Math.max(primary.updatedAt, now);
        primary.lastAccessAt = Math.max(primary.lastAccessAt, now);
        primary.confidence = Math.max(primary.confidence, candidate.confidence);
        primary.redundancy = 1.0;
        primary.importance = MemoryScoring.importance(primary, config, now);
        try {
            store.updateMerge(primary);
            store.insertAudit(primary.memoryId, "merge",
                    primary.layer, primary.layer, null, null,
                    "system", "similarity-merge", now);
        } catch (Exception e) {
            sink.reportError("AI Pro 合并记忆失败：" + e.getMessage());
        }
    }

    private Memory exactDuplicate(List<Memory> active, String hash) {
        for (Memory memory : active) {
            if (hash.equals(memory.contentHash)) {
                return memory;
            }
        }
        return null;
    }

    private double maxSimilarity(List<Memory> active, Memory candidate) {
        double max = 0;
        for (Memory memory : active) {
            max = Math.max(max, MemoryText.similarity(memory, candidate));
        }
        return max;
    }

    private Memory mostSimilar(List<Memory> active, Memory candidate) {
        Memory result = null;
        double max = 0;
        for (Memory memory : active) {
            double similarity = MemoryText.similarity(memory, candidate);
            if (similarity > max) {
                max = similarity;
                result = memory;
            }
        }
        return result;
    }

    private String unionJson(String left, String right, int max) {
        Set<String> values = new LinkedHashSet<>();
        values.addAll(MemoryText.listValues(left));
        values.addAll(MemoryText.listValues(right));
        JsonArray array = new JsonArray();
        int count = 0;
        for (String value : values) {
            if (count++ >= max) {
                break;
            }
            array.add(value);
        }
        return array.toString();
    }

    private static String joinContents(List<Message> messages) {
        StringBuilder builder = new StringBuilder();
        for (Message message : messages) {
            if (message.content != null) {
                builder.append(message.content).append('\n');
            }
        }
        return builder.toString();
    }

    private static boolean containsExplicit(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.ROOT);
        return value.contains("记住") || value.contains("记一下")
                || value.contains("记下") || value.contains("别忘了");
    }

    private static boolean containsConfirmation(String text) {
        String value = text == null ? "" : text;
        return value.contains("没错") || value.contains("就是这样")
                || value.contains("对的") || value.contains("是的")
                || value.equals("对");
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    public void shutdown() {
        closed = true;
        synchronized (this) {
            if (timer != null) {
                timer.cancel(false);
                timer = null;
            }
        }
        scheduler.shutdownNow();
        worker.shutdownNow();
    }
}
