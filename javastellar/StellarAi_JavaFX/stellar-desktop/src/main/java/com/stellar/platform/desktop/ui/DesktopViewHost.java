package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.NavigationPage;

/**
 * FXML view actually materialized for a {@link NavigationPage}.
 *
 * <p>Sources and Decode are two navigation destinations inside the one Project Files view, so the
 * shell keys loaded pages by host. This keeps a single controller, session store and signal
 * catalog per view even though the sidebar exposes more destinations than FXML files.</p>
 */
enum DesktopViewHost {
    HOME("/fxml/home-dashboard-view.fxml"),
    PROJECTS_HOME("/fxml/dashboard-view.fxml"),
    PROJECT_FILES("/fxml/project-files-view.fxml"),
    PROCESSING("/fxml/processing-view.fxml"),
    SIGNALS("/fxml/signals-view.fxml"),
    GRAPHS("/fxml/graph-view.fxml"),
    INVESTIGATIONS("/fxml/investigation-view.fxml"),
    CONFIGURATION("/fxml/configuration-view.fxml");

    private final String resource;

    DesktopViewHost(String resource) {
        this.resource = resource;
    }

    String resource() {
        return resource;
    }

    /**
     * Analysis hosts keep a large JavaFX scene graph. Leaving the host disposes that graph
     * and drops the cached FXML root so navigation cannot retain the previous instance.
     * Lightweight session state lives on {@code DesktopAppService}, not on these controllers.
     */
    boolean releasesSceneGraphOnLeave() {
        return this == SIGNALS || this == GRAPHS;
    }

    static DesktopViewHost of(NavigationPage page) {
        if (page == null) {
            return HOME;
        }
        return switch (page) {
            case HOME -> HOME;
            case PROJECTS_HOME -> PROJECTS_HOME;
            case SOURCES, DECODE -> PROJECT_FILES;
            case PROCESSING -> PROCESSING;
            case SIGNALS -> SIGNALS;
            case GRAPHS -> GRAPHS;
            case INVESTIGATIONS -> INVESTIGATIONS;
            case CONFIGURATION -> CONFIGURATION;
        };
    }
}
