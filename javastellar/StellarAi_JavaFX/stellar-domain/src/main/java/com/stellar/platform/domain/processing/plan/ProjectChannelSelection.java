package com.stellar.platform.domain.processing.plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Mutable, Set-like processing-channel selection (project Processing Selection).
 * Independent of JavaFX, Source Inventory Study Set, and the path-based decode
 * {@code ProcessingSelection}. Identity is {@link SourceChannelRef}
 * (projectSourceId + channelId). Study Set membership never auto-populates this.
 */
public final class ProjectChannelSelection {

    private final LinkedHashSet<SourceChannelRef> selected = new LinkedHashSet<>();
    private final List<Runnable> listeners = new ArrayList<>();

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** @return true when newly added */
    public boolean select(SourceChannelRef channel) {
        Objects.requireNonNull(channel, "channel");
        boolean added = selected.add(channel);
        if (added) {
            fireChanged();
        }
        return added;
    }

    public int selectAll(Collection<SourceChannelRef> channels) {
        if (channels == null || channels.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (SourceChannelRef channel : channels) {
            if (channel != null && selected.add(channel)) {
                n++;
            }
        }
        if (n > 0) {
            fireChanged();
        }
        return n;
    }

    /** @return true when removed */
    public boolean deselect(SourceChannelRef channel) {
        if (channel == null) {
            return false;
        }
        boolean removed = selected.remove(channel);
        if (removed) {
            fireChanged();
        }
        return removed;
    }

    public void clear() {
        if (selected.isEmpty()) {
            return;
        }
        selected.clear();
        fireChanged();
    }

    public boolean contains(SourceChannelRef channel) {
        return channel != null && selected.contains(channel);
    }

    public boolean contains(String projectSourceId, int channelId) {
        return contains(SourceChannelRef.of(projectSourceId, channelId));
    }

    public List<SourceChannelRef> selectedChannels() {
        return List.copyOf(selected);
    }

    public Set<SourceChannelRef> asSet() {
        return Set.copyOf(selected);
    }

    public int size() {
        return selected.size();
    }

    public boolean isEmpty() {
        return selected.isEmpty();
    }

    /** Drop channels whose projectSourceId is no longer present. */
    public int retainSources(Collection<String> livingSourceIds) {
        if (livingSourceIds == null) {
            return 0;
        }
        Set<String> live = Set.copyOf(livingSourceIds);
        int before = selected.size();
        selected.removeIf(ref -> !live.contains(ref.projectSourceId()));
        int removed = before - selected.size();
        if (removed > 0) {
            fireChanged();
        }
        return removed;
    }

    private void fireChanged() {
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }
}
