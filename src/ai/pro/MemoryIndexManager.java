package ai.pro;

/**
 * 记忆索引快照管理。写入后由 memory worker 重建，读侧无锁。
 */
public class MemoryIndexManager {

    private final MemoryStore memoryStore;
    private volatile MemoryIndex snapshot = new MemoryIndex(null);

    public MemoryIndexManager(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    public MemoryIndex get() {
        return snapshot;
    }

    public synchronized void rebuild() throws Exception {
        snapshot = new MemoryIndex(memoryStore.activeMemories());
    }

    public void rebuildQuietly() {
        try {
            rebuild();
        } catch (Exception ignored) {
            // 保留旧快照，下一轮继续尝试。
        }
    }
}
