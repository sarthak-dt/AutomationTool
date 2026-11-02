package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.project.WorkspaceFile;
import javafx.scene.control.TableView;

import java.util.List;
import java.util.Objects;

/**
 * One-directional list/selection synchronization for Project Files tables.
 * Separates model list replacement from conditional selection restore.
 */
final class ProjectFilesTableSync {

    private ProjectFilesTableSync() {
    }

    /**
     * {@code true} when the table selection must change to match {@code desiredId}.
     */
    static boolean shouldChangeSelection(String currentSelectedId, String desiredId) {
        return !Objects.equals(currentSelectedId, desiredId);
    }

    static String idOf(WorkspaceFile file) {
        return file == null ? null : file.id();
    }

    static boolean sameItemsById(List<WorkspaceFile> current, List<WorkspaceFile> desired) {
        if (current == desired) {
            return true;
        }
        if (current == null || desired == null || current.size() != desired.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            if (!Objects.equals(idOf(current.get(i)), idOf(desired.get(i)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Replaces table items when needed, then restores selection only when it differs.
     *
     * @return {@code true} if the items list or selection model was mutated
     */
    static boolean replaceItemsAndSyncSelection(
            TableView<WorkspaceFile> table,
            List<WorkspaceFile> items,
            String desiredId
    ) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(items, "items");

        String currentId = idOf(table.getSelectionModel().getSelectedItem());
        if (sameItemsById(table.getItems(), items) && !shouldChangeSelection(currentId, desiredId)) {
            return false;
        }

        if (!sameItemsById(table.getItems(), items)) {
            table.getItems().setAll(items);
        }

        String afterId = idOf(table.getSelectionModel().getSelectedItem());
        if (!shouldChangeSelection(afterId, desiredId)) {
            return true;
        }

        if (desiredId == null) {
            table.getSelectionModel().clearSelection();
            return true;
        }

        for (WorkspaceFile item : table.getItems()) {
            if (desiredId.equals(item.id())) {
                table.getSelectionModel().select(item);
                return true;
            }
        }
        table.getSelectionModel().clearSelection();
        return true;
    }
}
