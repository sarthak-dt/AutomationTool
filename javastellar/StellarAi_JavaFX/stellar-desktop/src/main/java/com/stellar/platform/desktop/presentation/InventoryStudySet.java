package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.StudyMessageKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Persistent Study Set for Source Inventory Phase 2.
 * Presentation-only Set keyed by {@link StudyMessageKey}. Idempotent add. No decode/parser.
 */
public final class InventoryStudySet {

    private final Map<StudyMessageKey, StudyMessageEntry> entries = new LinkedHashMap<>();
    /**
     * Study identities whose DBC/signal configuration changed after they were added, so the
     * preview the engineer last looked at is no longer the one being shown.
     */
    private final Set<StudyMessageKey> staleKeys = new LinkedHashSet<>();
    private final List<Runnable> listeners = new ArrayList<>();

    /** Eligible for Study: MATCHED with at least one usable signal definition. */
    public static boolean isEligible(CanMessageInventoryRow row) {
        return row != null
                && row.matchState() == InventoryEnrichmentMatchState.MATCHED
                && row.signalCount() > 0;
    }

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public boolean contains(StudyMessageKey key) {
        return key != null && entries.containsKey(key);
    }

    public boolean contains(CanMessageInventoryRow row) {
        return row != null && contains(StudyMessageKey.of(row.channelKey(), row.canId(), row.extended()));
    }

    /**
     * Adds one eligible row if missing. Returns true when newly added.
     * Unmatched / NO DBC / AMBIGUOUS / no-signal rows are ignored.
     */
    public boolean add(CanMessageInventoryRow row) {
        if (!isEligible(row)) {
            return false;
        }
        StudyMessageKey key = StudyMessageKey.of(row.channelKey(), row.canId(), row.extended());
        if (entries.containsKey(key)) {
            return false;
        }
        entries.put(key, StudyMessageEntry.fromEligible(row));
        fireChanged();
        return true;
    }

    /**
     * Idempotent bulk add. Returns number of newly inserted identities.
     */
    public int addAll(Collection<CanMessageInventoryRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int added = 0;
        boolean changed = false;
        for (CanMessageInventoryRow row : rows) {
            if (!isEligible(row)) {
                continue;
            }
            StudyMessageKey key = StudyMessageKey.of(row.channelKey(), row.canId(), row.extended());
            if (entries.containsKey(key)) {
                continue;
            }
            entries.put(key, StudyMessageEntry.fromEligible(row));
            added++;
            changed = true;
        }
        if (changed) {
            fireChanged();
        }
        return added;
    }

    public boolean remove(CanMessageInventoryRow row) {
        if (row == null) {
            return false;
        }
        return remove(StudyMessageKey.of(row.channelKey(), row.canId(), row.extended()));
    }

    public boolean remove(StudyMessageKey key) {
        if (key == null || entries.remove(key) == null) {
            return false;
        }
        staleKeys.remove(key);
        fireChanged();
        return true;
    }

    /**
     * Removes current-view identities. Returns number removed. One listener notification.
     */
    public int removeAll(Collection<CanMessageInventoryRow> rows) {
        if (rows == null || rows.isEmpty() || entries.isEmpty()) {
            return 0;
        }
        int removed = 0;
        for (CanMessageInventoryRow row : rows) {
            if (row == null) {
                continue;
            }
            StudyMessageKey key = StudyMessageKey.of(row.channelKey(), row.canId(), row.extended());
            if (entries.remove(key) != null) {
                staleKeys.remove(key);
                removed++;
            }
        }
        if (removed > 0) {
            fireChanged();
        }
        return removed;
    }

    public void clear() {
        if (entries.isEmpty()) {
            return;
        }
        entries.clear();
        staleKeys.clear();
        fireChanged();
    }

    /**
     * Refresh stored DBC/signal metadata for Study identities present in {@code rows}.
     * Does not add/remove membership. Returns number of entries whose projection actually changed.
     *
     * <p>A changed projection means the engineer altered the DBC configuration behind a Study
     * identity, so that identity is marked stale and the preview stops presenting itself as the
     * result the engineer last confirmed. Unchanged rows are left untouched, so routine enrichment
     * refreshes neither rewrite entries nor notify listeners.</p>
     */
    public int refreshMetadata(Collection<CanMessageInventoryRow> rows) {
        if (rows == null || rows.isEmpty() || entries.isEmpty()) {
            return 0;
        }
        int updated = 0;
        for (CanMessageInventoryRow row : rows) {
            if (row == null) {
                continue;
            }
            StudyMessageKey key = StudyMessageKey.of(row.channelKey(), row.canId(), row.extended());
            StudyMessageEntry current = entries.get(key);
            if (current == null) {
                continue;
            }
            StudyMessageEntry refreshed = StudyMessageEntry.fromRow(row);
            if (current.sameProjectionAs(refreshed)) {
                continue;
            }
            entries.put(key, refreshed);
            staleKeys.add(key);
            updated++;
        }
        if (updated > 0) {
            fireChanged();
        }
        return updated;
    }

    /** True when this Study identity's configuration changed after it was added. */
    public boolean stale(StudyMessageKey key) {
        return key != null && staleKeys.contains(key);
    }

    /** Number of Study identities whose configuration changed since they were added. */
    public int staleCount() {
        return staleKeys.size();
    }

    /**
     * Clears the stale marks after the engineer has re-read the refreshed preview.
     * Membership and metadata are untouched.
     */
    public void acknowledgeStale() {
        if (staleKeys.isEmpty()) {
            return;
        }
        staleKeys.clear();
        fireChanged();
    }

    public List<StudyMessageEntry> entries() {
        return List.copyOf(entries.values());
    }

    public Map<StudyMessageKey, StudyMessageEntry> asMap() {
        return Collections.unmodifiableMap(entries);
    }

    /** Count of eligible rows in {@code rows} that are not yet in this set. */
    public int missingEligibleCount(Collection<CanMessageInventoryRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (CanMessageInventoryRow row : rows) {
            if (isEligible(row) && !contains(row)) {
                n++;
            }
        }
        return n;
    }

    private void fireChanged() {
        for (Runnable listener : List.copyOf(listeners)) {
            listener.run();
        }
    }
}
