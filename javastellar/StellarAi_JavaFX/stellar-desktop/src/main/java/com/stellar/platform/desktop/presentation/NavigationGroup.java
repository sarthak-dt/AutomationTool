package com.stellar.platform.desktop.presentation;

/**
 * Sidebar grouping for {@link NavigationPage}. Only {@link #SETUP} and {@link #ANALYSIS}
 * are permanent project navigation sections.
 */
public enum NavigationGroup {
    /** Projects Home — lives outside the open-project workspace. */
    HOME("", false),
    SETUP("SETUP", true),
    ANALYSIS("ANALYSIS", true),
    /** Reached only through a {@link WorkspaceRoute}; never rendered as a sidebar entry. */
    TRANSIENT("", false);

    private final String label;
    private final boolean permanent;

    NavigationGroup(String label, boolean permanent) {
        this.label = label;
        this.permanent = permanent;
    }

    public String label() {
        return label;
    }

    public boolean permanent() {
        return permanent;
    }
}
