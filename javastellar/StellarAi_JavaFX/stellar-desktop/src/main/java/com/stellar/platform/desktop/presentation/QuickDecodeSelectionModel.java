package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lightweight Quick Decode intent only. It never mutates Detailed Mapping confirmation state.
 *
 * <p>All discovered channels are included by default. Exclusions and manual DBC overrides are
 * stored sparsely so projects with hundreds of files do not duplicate inventory/match data.</p>
 */
public final class QuickDecodeSelectionModel {

    private final Map<SourceChannelKey, DefinitionSourceId> overrides = new LinkedHashMap<>();
    private final Set<SourceChannelKey> excluded = new LinkedHashSet<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public synchronized boolean included(SourceChannelKey key) {
        return key != null && !excluded.contains(key);
    }

    public void setIncluded(SourceChannelKey key, boolean include) {
        if (key == null) {
            return;
        }
        boolean changed;
        synchronized (this) {
            changed = include ? excluded.remove(key) : excluded.add(key);
        }
        if (changed) {
            notifyListeners();
        }
    }

    public void selectAll(Collection<SourceChannelKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        boolean changed = false;
        synchronized (this) {
            for (SourceChannelKey key : keys) {
                if (key != null) {
                    changed |= excluded.remove(key);
                }
            }
        }
        if (changed) {
            notifyListeners();
        }
    }

    public void deselectAll(Collection<SourceChannelKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        boolean changed = false;
        synchronized (this) {
            for (SourceChannelKey key : keys) {
                if (key != null) {
                    changed |= excluded.add(key);
                }
            }
        }
        if (changed) {
            notifyListeners();
        }
    }

    public synchronized Optional<DefinitionSourceId> overrideFor(SourceChannelKey key) {
        return Optional.ofNullable(key == null ? null : overrides.get(key));
    }

    public synchronized Map<SourceChannelKey, DefinitionSourceId> overrides() {
        return Map.copyOf(overrides);
    }

    public void setOverride(SourceChannelKey key, DefinitionSourceId definitionId) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(definitionId, "definitionId");
        boolean changed;
        synchronized (this) {
            changed = !Objects.equals(overrides.put(key, definitionId), definitionId);
        }
        if (changed) {
            notifyListeners();
        }
    }

    /** Applies one manual DBC to multiple channels with a single listener notification. */
    public void setOverrides(Collection<SourceChannelKey> keys, DefinitionSourceId definitionId) {
        Objects.requireNonNull(definitionId, "definitionId");
        if (keys == null || keys.isEmpty()) {
            return;
        }
        boolean changed = false;
        synchronized (this) {
            for (SourceChannelKey key : keys) {
                if (key != null) {
                    changed |= !Objects.equals(overrides.put(key, definitionId), definitionId);
                }
            }
        }
        if (changed) {
            notifyListeners();
        }
    }

    /**
     * Restores system best-match decisions for the supplied channels and includes them.
     * Used by the Quick Decode "Auto Select Best" action without touching other channels.
     */
    public void resetToRecommendations(Collection<SourceChannelKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        Set<SourceChannelKey> target = new LinkedHashSet<>();
        for (SourceChannelKey key : keys) {
            if (key != null) {
                target.add(key);
            }
        }
        if (target.isEmpty()) {
            return;
        }
        boolean changed;
        synchronized (this) {
            changed = overrides.keySet().removeIf(target::contains);
            changed |= excluded.removeIf(target::contains);
        }
        if (changed) {
            notifyListeners();
        }
    }

    public void clearOverride(SourceChannelKey key) {
        if (key == null) {
            return;
        }
        boolean changed;
        synchronized (this) {
            changed = overrides.remove(key) != null;
        }
        if (changed) {
            notifyListeners();
        }
    }

    public void clearOverridesForSource(ProjectSourceId sourceId) {
        if (sourceId == null) {
            return;
        }
        boolean changed;
        synchronized (this) {
            changed = overrides.keySet().removeIf(key -> key.sourceId().equals(sourceId));
        }
        if (changed) {
            notifyListeners();
        }
    }

    public void clearAllOverrides() {
        boolean changed;
        synchronized (this) {
            changed = !overrides.isEmpty();
            overrides.clear();
        }
        if (changed) {
            notifyListeners();
        }
    }

    /** Remove sparse state for channels no longer present in the project. */
    public void retainChannels(Collection<SourceChannelKey> liveChannels) {
        Set<SourceChannelKey> live = liveChannels == null
                ? Set.of()
                : new LinkedHashSet<>(liveChannels);
        boolean changed;
        synchronized (this) {
            changed = overrides.keySet().removeIf(key -> !live.contains(key));
            changed |= excluded.removeIf(key -> !live.contains(key));
        }
        if (changed) {
            notifyListeners();
        }
    }

    public synchronized Map<SourceChannelKey, DefinitionSourceId> overridesSnapshot() {
        return Map.copyOf(overrides);
    }

    public synchronized List<SourceChannelKey> excludedSnapshot() {
        return List.copyOf(excluded);
    }

    public void reset() {
        boolean changed;
        synchronized (this) {
            changed = !overrides.isEmpty() || !excluded.isEmpty();
            overrides.clear();
            excluded.clear();
        }
        if (changed) {
            notifyListeners();
        }
    }

    private void notifyListeners() {
        for (Runnable listener : new ArrayList<>(listeners)) {
            listener.run();
        }
    }
}
