package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.inventory.CanSourceInventory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Per-measurement CAN source inventory state plus optional DBC Message-ID match presentation.
 * Not a global singleton. Matching algorithm lives in processing-core.
 */
public final class SourceInventoryModel {

    private final Map<String, CanSourceInventory> byFileId = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile String focusedFileId;
    private volatile SourceInventoryMatchState matchState = SourceInventoryMatchState.noDbcSelected();

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public void clear() {
        byFileId.clear();
        focusedFileId = null;
        matchState = SourceInventoryMatchState.noDbcSelected();
        notifyListeners();
    }

    public void put(String fileId, CanSourceInventory inventory) {
        Objects.requireNonNull(fileId, "fileId");
        Objects.requireNonNull(inventory, "inventory");
        byFileId.put(fileId, inventory);
        if (focusedFileId == null) {
            focusedFileId = fileId;
        }
        notifyListeners();
    }

    public void remove(String fileId) {
        if (fileId == null) {
            return;
        }
        byFileId.remove(fileId);
        if (Objects.equals(focusedFileId, fileId)) {
            focusedFileId = byFileId.isEmpty() ? null : byFileId.keySet().iterator().next();
        }
        notifyListeners();
    }

    public void focus(String fileId) {
        if (fileId == null || !byFileId.containsKey(fileId)) {
            return;
        }
        if (Objects.equals(focusedFileId, fileId)) {
            return;
        }
        focusedFileId = fileId;
        notifyListeners();
    }

    public void setMatchState(SourceInventoryMatchState state) {
        SourceInventoryMatchState next = state == null ? SourceInventoryMatchState.noDbcSelected() : state;
        if (Objects.equals(matchState, next)) {
            return;
        }
        this.matchState = next;
        notifyListeners();
    }

    public SourceInventoryMatchState matchState() {
        return matchState;
    }

    public Optional<String> focusedFileId() {
        return Optional.ofNullable(focusedFileId);
    }

    public Optional<CanSourceInventory> focusedInventory() {
        return focusedFileId == null
                ? Optional.empty()
                : Optional.ofNullable(byFileId.get(focusedFileId));
    }

    public Optional<CanSourceInventory> inventoryFor(String fileId) {
        return Optional.ofNullable(byFileId.get(fileId));
    }

    public Map<String, CanSourceInventory> all() {
        return Map.copyOf(byFileId);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
