package com.stellar.platform.desktop.presentation;

/**
 * Workspace / Projects Home empty-table copy. Catalog-empty and search-no-match are different.
 */
public final class ProjectSearchEmptyCopy {

    private ProjectSearchEmptyCopy() {
    }

    public static String title(boolean catalogHasProjects, boolean searchActive, boolean favoritesOnly) {
        if (favoritesOnly && !searchActive) {
            return "No favourite projects yet.";
        }
        if (catalogHasProjects && searchActive) {
            return "No projects match your search.";
        }
        return "No saved projects yet.";
    }

    public static String body(boolean catalogHasProjects, boolean searchActive, boolean favoritesOnly) {
        if (favoritesOnly && !searchActive) {
            return "Star a project to keep it here.";
        }
        if (catalogHasProjects && searchActive) {
            return "Try a different project name.";
        }
        return "Create a project to begin engineering work.";
    }
}
