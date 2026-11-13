package ai.pro;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 核心记忆快照：核心提示词槽位与只读红线。
 */
public final class CoreMemory {

    public static final class Entry {
        public final String kind;
        public final String slot;
        public final String content;
        public final boolean readonly;
        public final int sortOrder;

        public Entry(String kind, String slot, String content,
                     boolean readonly, int sortOrder) {
            this.kind = kind;
            this.slot = slot;
            this.content = content == null ? "" : content;
            this.readonly = readonly;
            this.sortOrder = sortOrder;
        }
    }

    public final String identity;
    public final String nickname;
    public final String language;
    public final String style;
    public final String owner;
    public final String custom;
    public final List<Entry> entries;

    public CoreMemory(String identity,
                      String nickname,
                      String language,
                      String style,
                      String owner,
                      String custom,
                      List<Entry> entries) {
        this.identity = safe(identity);
        this.nickname = safe(nickname);
        this.language = safe(language);
        this.style = safe(style);
        this.owner = safe(owner);
        this.custom = safe(custom);
        this.entries = entries == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(entries));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
