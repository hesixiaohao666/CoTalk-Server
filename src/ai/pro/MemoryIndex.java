package ai.pro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 不可变记忆倒排索引快照。
 */
public final class MemoryIndex {

    public static final class IndexedMemory {
        public final Memory memory;
        public final Set<String> tokens;
        public final int length;

        IndexedMemory(Memory memory, Set<String> tokens) {
            this.memory = memory;
            this.tokens = Collections.unmodifiableSet(new HashSet<>(tokens));
            this.length = Math.max(1, tokens.size());
        }
    }

    private final List<IndexedMemory> all;
    private final Map<MemoryLayer, List<IndexedMemory>> byLayer;
    private final Map<String, Integer> documentFrequency;
    private final double averageLength;

    public MemoryIndex(List<Memory> memories) {
        List<IndexedMemory> indexed = new ArrayList<>();
        Map<MemoryLayer, List<IndexedMemory>> layers = new HashMap<>();
        Map<String, Integer> df = new HashMap<>();
        long totalLength = 0;
        if (memories != null) {
            for (Memory memory : memories) {
                if (memory == null || !"active".equals(memory.status)) {
                    continue;
                }
                Set<String> tokens = MemoryText.tokens(
                        memory.content, memory.keywords, memory.tags);
                IndexedMemory item = new IndexedMemory(memory, tokens);
                indexed.add(item);
                layers.computeIfAbsent(memory.layer, key -> new ArrayList<>())
                        .add(item);
                for (String token : tokens) {
                    df.merge(token, 1, Integer::sum);
                }
                totalLength += item.length;
            }
        }
        this.all = Collections.unmodifiableList(indexed);
        Map<MemoryLayer, List<IndexedMemory>> frozen = new HashMap<>();
        for (Map.Entry<MemoryLayer, List<IndexedMemory>> entry : layers.entrySet()) {
            frozen.put(entry.getKey(),
                    Collections.unmodifiableList(entry.getValue()));
        }
        this.byLayer = Collections.unmodifiableMap(frozen);
        this.documentFrequency = Collections.unmodifiableMap(df);
        this.averageLength = indexed.isEmpty()
                ? 1.0 : (double) totalLength / indexed.size();
    }

    public List<IndexedMemory> all() {
        return all;
    }

    public List<IndexedMemory> layer(MemoryLayer layer) {
        List<IndexedMemory> list = byLayer.get(layer);
        return list == null ? Collections.emptyList() : list;
    }

    public List<IndexedMemory> candidates(MemoryLayer layer,
                                          Set<String> queryTokens) {
        List<IndexedMemory> layerList = layer(layer);
        if (queryTokens == null || queryTokens.isEmpty()) {
            return layerList;
        }
        Set<IndexedMemory> result = new LinkedHashSet<>();
        for (IndexedMemory item : layerList) {
            for (String token : queryTokens) {
                if (item.tokens.contains(token)) {
                    result.add(item);
                    break;
                }
            }
        }
        return new ArrayList<>(result);
    }

    public int documentCount() {
        return all.size();
    }

    public double averageLength() {
        return averageLength;
    }

    public int documentFrequency(String token) {
        return documentFrequency.getOrDefault(token, 0);
    }
}
