package com.stellar.platform.desktop.ui;

/**
 * Project Files sub-workspaces. Presentation navigation only — no business mutation.
 */
enum ProjectFilesWorkspaceKind {
    SOURCES(false),
    SOURCE_INVENTORY(false),
    DBC_MAPPING(false);

    private final boolean showLegacySelectionBar;

    ProjectFilesWorkspaceKind(boolean showLegacySelectionBar) {
        this.showLegacySelectionBar = showLegacySelectionBar;
    }

    boolean showLegacySelectionBar() {
        return showLegacySelectionBar;
    }

    boolean isDecisionWorkspace() {
        return this == SOURCE_INVENTORY || this == DBC_MAPPING;
    }
}
