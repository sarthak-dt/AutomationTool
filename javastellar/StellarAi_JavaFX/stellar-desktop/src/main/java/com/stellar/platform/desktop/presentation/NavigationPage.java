package com.stellar.platform.desktop.presentation;

import java.util.List;

/**
 * User-facing navigation destinations of the V1 journey.
 *
 * <p>Permanent project navigation is exactly {@link #SOURCES}, {@link #DECODE}, {@link #SIGNALS},
 * {@link #GRAPHS} and {@link #INVESTIGATIONS}. {@link #HOME} is the launcher dashboard.
 * {@link #PROJECTS_HOME} is detailed project management. {@link #PROCESSING} is a transient
 * execution/result workspace that is only reachable through a {@link WorkspaceRoute}.
 * Inventory, DBC Mapping and Study remain subordinate workspaces inside Sources/Decode.</p>
 */
public enum NavigationPage {
    HOME("Workspace", NavigationGroup.HOME),
    PROJECTS_HOME("Projects", NavigationGroup.HOME),
    SOURCES("Sources", NavigationGroup.SETUP),
    DECODE("Decode", NavigationGroup.SETUP),
    PROCESSING("Processing", NavigationGroup.TRANSIENT),
    SIGNALS("Signals", NavigationGroup.ANALYSIS),
    GRAPHS("Graphs", NavigationGroup.ANALYSIS),
    INVESTIGATIONS("Investigations", NavigationGroup.ANALYSIS),
    CONFIGURATION("Configuration", NavigationGroup.HOME);

    private static final List<NavigationPage> PERMANENT_PROJECT_PAGES =
            List.of(SOURCES, DECODE, SIGNALS, GRAPHS, INVESTIGATIONS);

    private final String label;
    private final NavigationGroup group;

    NavigationPage(String label, NavigationGroup group) {
        this.label = label;
        this.group = group;
    }

    public String label() {
        return label;
    }

    public NavigationGroup group() {
        return group;
    }

    public boolean permanentProjectNavigation() {
        return group.permanent();
    }

    /** Analysis destinations require valid processed data before they can be opened. */
    public boolean analysisPage() {
        return group == NavigationGroup.ANALYSIS;
    }

    /** Ordered permanent workspace navigation: Sources, Decode, Signals, Graphs, Investigations. */
    public static List<NavigationPage> permanentProjectPages() {
        return PERMANENT_PROJECT_PAGES;
    }
}
