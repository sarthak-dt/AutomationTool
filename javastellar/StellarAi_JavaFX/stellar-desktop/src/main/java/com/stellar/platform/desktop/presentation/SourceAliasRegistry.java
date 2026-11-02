package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ProjectSourceId;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stable project-session Source A/B/C aliases keyed by {@link ProjectSourceId}.
 * <p>
 * Aliases are never derived from filtered/visual index, HashMap iteration, or matching state.
 * Gaps are preserved when a source is removed (Source C stays Source C).
 * Cleared only on new project.
 */
public final class SourceAliasRegistry {

    private final Map<String, String> aliasBySourceId = new LinkedHashMap<>();
    /** Monotonic - never reused within a project session (preserves gaps). */
    private int nextIndex;

    public synchronized String aliasFor(ProjectSourceId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        String existing = aliasBySourceId.get(sourceId.value());
        if (existing != null) {
            return existing;
        }
        String alias = SourceDisplayNameResolver.aliasForIndex(nextIndex++);
        aliasBySourceId.put(sourceId.value(), alias);
        return alias;
    }

    public synchronized String aliasOrNull(ProjectSourceId sourceId) {
        if (sourceId == null) {
            return null;
        }
        return aliasBySourceId.get(sourceId.value());
    }

    /**
     * Drop aliases for sources no longer in the project workspace.
     * Does not renumber remaining aliases and does not reuse freed letters.
     */
    public synchronized void retainOnly(Set<String> liveSourceIds) {
        if (liveSourceIds == null) {
            return;
        }
        aliasBySourceId.keySet().removeIf(id -> !liveSourceIds.contains(id));
    }

    public synchronized void clear() {
        aliasBySourceId.clear();
        nextIndex = 0;
    }

    public synchronized int size() {
        return aliasBySourceId.size();
    }

    public synchronized Map<String, String> snapshot() {
        return Map.copyOf(aliasBySourceId);
    }
}
