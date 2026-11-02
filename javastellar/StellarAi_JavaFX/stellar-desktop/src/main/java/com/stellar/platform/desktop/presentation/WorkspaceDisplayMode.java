package com.stellar.platform.desktop.presentation;

/** UI value representation for the Active Signal Workspace table. */
public enum WorkspaceDisplayMode {
    PHYSICAL("Physical"),
    RAW("Raw");

    private final String label;

    WorkspaceDisplayMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
