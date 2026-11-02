package com.stellar.platform.desktop.presentation;

/**
 * A concrete workspace destination the shell can open.
 *
 * <p>Several routes share the {@link NavigationPage#PROCESSING} host: Review &amp; Process,
 * Processing Resume and Processing Result are transition/execution/result states of the one
 * existing Processing workspace rather than separate navigation entries.</p>
 */
public enum WorkspaceRoute {
    HOME(NavigationPage.HOME, "Workspace"),
    PROJECTS_HOME(NavigationPage.PROJECTS_HOME, "Projects"),
    SOURCES(NavigationPage.SOURCES, "Sources"),
    DECODE(NavigationPage.DECODE, "Decode"),
    REVIEW_AND_PROCESS(NavigationPage.PROCESSING, "Review & Process"),
    PROCESSING_RESUME(NavigationPage.PROCESSING, "Resume Processing"),
    PROCESSING_RESULT(NavigationPage.PROCESSING, "Processing Result"),
    SIGNALS(NavigationPage.SIGNALS, "Signals"),
    GRAPHS(NavigationPage.GRAPHS, "Graphs"),
    INVESTIGATIONS(NavigationPage.INVESTIGATIONS, "Investigations"),
    CONFIGURATION(NavigationPage.CONFIGURATION, "Configuration");

    private final NavigationPage page;
    private final String label;

    WorkspaceRoute(NavigationPage page, String label) {
        this.page = page;
        this.label = label;
    }

    public NavigationPage page() {
        return page;
    }

    public String label() {
        return label;
    }

    /** True for routes that must never appear as permanent sidebar navigation. */
    public boolean transientProcessingRoute() {
        return page == NavigationPage.PROCESSING;
    }

    /** Default route used when a permanent navigation entry is activated directly. */
    public static WorkspaceRoute forPage(NavigationPage page) {
        if (page == null) {
            return HOME;
        }
        return switch (page) {
            case HOME -> HOME;
            case PROJECTS_HOME -> PROJECTS_HOME;
            case SOURCES -> SOURCES;
            case DECODE -> DECODE;
            case PROCESSING -> REVIEW_AND_PROCESS;
            case SIGNALS -> SIGNALS;
            case GRAPHS -> GRAPHS;
            case INVESTIGATIONS -> INVESTIGATIONS;
            case CONFIGURATION -> CONFIGURATION;
        };
    }
}
