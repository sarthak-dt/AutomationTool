package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanChannelMatchSummary;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.ProcessingSelection;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Presentation-only CAN channel selection for LOCAL processing.
 * Does not mutate Source Inventory or DBC match models.
 */
public final class ChannelSelectionModel {

    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final LinkedHashSet<Integer> selectedChannelIds = new LinkedHashSet<>();
    private volatile String sourceFileId;
    private volatile String sourceIdentity;
    private volatile String lastProcessedFingerprint = "";
    private volatile boolean resultStale;

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public void clear() {
        selectedChannelIds.clear();
        sourceFileId = null;
        sourceIdentity = null;
        lastProcessedFingerprint = "";
        resultStale = false;
        notifyListeners();
    }

    public void bindSource(String fileId, Path measurementPath, CanSourceInventory inventory) {
        String identity = measurementPath == null
                ? null
                : ProcessingSelection.sourceIdentity(measurementPath);
        boolean sourceChanged = !Objects.equals(sourceFileId, fileId)
                || !Objects.equals(sourceIdentity, identity);
        LinkedHashSet<Integer> beforeSelection = new LinkedHashSet<>(selectedChannelIds);
        boolean beforeStale = resultStale;

        sourceFileId = fileId;
        sourceIdentity = identity;
        if (sourceChanged) {
            selectedChannelIds.clear();
            resultStale = false;
        } else if (inventory != null) {
            Set<Integer> available = new LinkedHashSet<>();
            for (CanChannelInventory ch : inventory.channels()) {
                available.add(ch.channelId());
            }
            selectedChannelIds.removeIf(id -> !available.contains(id));
        }
        markStaleIfProcessedSelectionChanged();

        // Idempotent sync must not notify: Project Files wires selection listeners to
        // updateProcessingControls → syncChannelSelection, which would starve the FX thread.
        boolean selectionChanged = !beforeSelection.equals(selectedChannelIds);
        boolean staleChanged = beforeStale != resultStale;
        if (sourceChanged || selectionChanged || staleChanged) {
            notifyListeners();
        }
    }

    public void toggle(int channelId) {
        if (selectedChannelIds.contains(channelId)) {
            selectedChannelIds.remove(channelId);
        } else {
            selectedChannelIds.add(channelId);
        }
        markStaleIfProcessedSelectionChanged();
        notifyListeners();
    }

    public void select(int channelId, boolean selected) {
        boolean changed = selected
                ? selectedChannelIds.add(channelId)
                : selectedChannelIds.remove(channelId);
        if (!changed) {
            return;
        }
        markStaleIfProcessedSelectionChanged();
        notifyListeners();
    }

    public void selectAll(CanSourceInventory inventory) {
        selectedChannelIds.clear();
        if (inventory != null) {
            for (CanChannelInventory ch : inventory.channels()) {
                selectedChannelIds.add(ch.channelId());
            }
        }
        markStaleIfProcessedSelectionChanged();
        notifyListeners();
    }

    public void selectAllMatched(CanSourceInventory inventory, SourceInventoryMatchState matchState) {
        selectedChannelIds.clear();
        if (inventory == null || matchState == null) {
            notifyListeners();
            return;
        }
        for (CanChannelInventory ch : inventory.channels()) {
            Optional<CanChannelMatchSummary> summary = matchState.channelMatch(ch.channelId());
            if (summary.isPresent() && summary.get().matchedCount() > 0) {
                selectedChannelIds.add(ch.channelId());
            }
        }
        markStaleIfProcessedSelectionChanged();
        notifyListeners();
    }

    public void clearSelection() {
        selectedChannelIds.clear();
        markStaleIfProcessedSelectionChanged();
        notifyListeners();
    }

    /**
     * Replaces the pending selection set in one step (used by Change Channels draft apply).
     */
    public void replaceSelection(Collection<Integer> channelIds) {
        LinkedHashSet<Integer> next = new LinkedHashSet<>();
        if (channelIds != null) {
            for (Integer id : channelIds) {
                if (id != null && id >= 0) {
                    next.add(id);
                }
            }
        }
        if (selectedChannelIds.equals(next)) {
            return;
        }
        selectedChannelIds.clear();
        selectedChannelIds.addAll(next);
        markStaleIfProcessedSelectionChanged();
        notifyListeners();
    }

    /** Copies pending channel ids from another selection model (same source identity expected). */
    public void copyPendingSelectionFrom(ChannelSelectionModel other) {
        Objects.requireNonNull(other, "other");
        replaceSelection(other.selectedChannelIds());
    }

    public boolean isSelected(int channelId) {
        return selectedChannelIds.contains(channelId);
    }

    public Set<Integer> selectedChannelIds() {
        return Set.copyOf(selectedChannelIds);
    }

    public int selectedCount() {
        return selectedChannelIds.size();
    }

    public boolean hasSelection() {
        return !selectedChannelIds.isEmpty();
    }

    public Optional<String> sourceFileId() {
        return Optional.ofNullable(sourceFileId);
    }

    public Optional<String> sourceIdentity() {
        return Optional.ofNullable(sourceIdentity);
    }

    public ProcessingSelection toProcessingSelection() {
        if (sourceIdentity == null || sourceIdentity.isBlank()) {
            return ProcessingSelection.none();
        }
        return ProcessingSelection.forSource(sourceIdentity, selectedChannelIds);
    }

    public String currentFingerprint() {
        return toProcessingSelection().fingerprint();
    }

    public void markProcessed() {
        lastProcessedFingerprint = currentFingerprint();
        resultStale = false;
        notifyListeners();
    }

    public boolean resultStale() {
        return resultStale;
    }

    public Optional<String> lastProcessedFingerprint() {
        return lastProcessedFingerprint == null || lastProcessedFingerprint.isBlank()
                ? Optional.empty()
                : Optional.of(lastProcessedFingerprint);
    }

    public SelectionSummary summarize(CanSourceInventory inventory, SourceInventoryMatchState matchState) {
        long observed = 0;
        long matched = 0;
        if (inventory != null) {
            for (CanChannelInventory ch : inventory.channels()) {
                if (!selectedChannelIds.contains(ch.channelId())) {
                    continue;
                }
                observed += ch.uniqueMessageCount();
                if (matchState != null) {
                    matched += matchState.channelMatch(ch.channelId())
                            .map(CanChannelMatchSummary::matchedCount)
                            .orElse(0L);
                }
            }
        }
        return new SelectionSummary(
                selectedChannelIds.size(),
                observed,
                matched,
                toProcessingSelection().summaryLabel(),
                resultStale
        );
    }

    private void markStaleIfProcessedSelectionChanged() {
        if (lastProcessedFingerprint != null && !lastProcessedFingerprint.isBlank()
                && !lastProcessedFingerprint.equals(currentFingerprint())) {
            resultStale = true;
        } else if (lastProcessedFingerprint != null
                && lastProcessedFingerprint.equals(currentFingerprint())) {
            resultStale = false;
        }
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    public record SelectionSummary(
            int selectedChannels,
            long selectedObservedIds,
            long selectedMatchedIds,
            String channelLabel,
            boolean resultStale
    ) {
    }
}
