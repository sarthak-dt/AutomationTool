package com.stellar.platform.desktop.ui;

import javafx.scene.Node;

import java.util.Objects;

/**
 * Switches among stable Project Files workspace roots without rebuilding them.
 * Tab activation is presentation-only.
 */
final class ProjectFilesWorkspaceHost {

    private final Node sourcesWorkspace;
    private final Node sourceInventoryWorkspace;
    private final Node dbcMappingWorkspace;

    private ProjectFilesWorkspaceKind active = ProjectFilesWorkspaceKind.SOURCES;
    private int switchCount;

    ProjectFilesWorkspaceHost(
            Node sourcesWorkspace,
            Node sourceInventoryWorkspace,
            Node dbcMappingWorkspace) {
        this.sourcesWorkspace = Objects.requireNonNull(sourcesWorkspace, "sourcesWorkspace");
        this.sourceInventoryWorkspace = Objects.requireNonNull(
                sourceInventoryWorkspace, "sourceInventoryWorkspace");
        this.dbcMappingWorkspace = Objects.requireNonNull(dbcMappingWorkspace, "dbcMappingWorkspace");
        applyVisibility();
    }

    ProjectFilesWorkspaceKind active() {
        return active;
    }

    int switchCount() {
        return switchCount;
    }

    /**
     * Shows one workspace. Does not refresh models, parse, inventory, or rematch.
     */
    void show(ProjectFilesWorkspaceKind workspace) {
        Objects.requireNonNull(workspace, "workspace");
        if (workspace != active) {
            switchCount++;
        }
        active = workspace;
        applyVisibility();
    }

    private void applyVisibility() {
        boolean decision = active.isDecisionWorkspace();
        setShown(sourcesWorkspace, active == ProjectFilesWorkspaceKind.SOURCES);
        setShown(sourceInventoryWorkspace, decision);
        // Legacy mapping pane remains constructed for tests; Decision Workspace is the live UI.
        setShown(dbcMappingWorkspace, false);
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }
}
