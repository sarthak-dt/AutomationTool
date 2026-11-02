package com.stellar.platform.desktop.presentation;

import java.util.Locale;
import java.util.Optional;

/**
 * Restores Signal Workspace presentation saved with a project.
 * The table timeline is always aligned. Older saved values that name
 * Aligned Timeline or This Signal are ignored so the project still opens.
 */
public final class SignalsWorkspacePreferences {

    private SignalsWorkspacePreferences() {
    }

    public static Optional<WorkspaceDisplayMode> displayMode(String stored) {
        if (blankOrObsoleteTimeline(stored)) {
            return Optional.empty();
        }
        try {
            return Optional.of(WorkspaceDisplayMode.valueOf(stored.trim()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public static Optional<WorkspacePrecision> precision(String stored) {
        if (blankOrObsoleteTimeline(stored)) {
            return Optional.empty();
        }
        try {
            return Optional.of(WorkspacePrecision.valueOf(stored.trim()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** True when a saved preference names the removed timeline switch. */
    public static boolean obsoleteTimeline(String stored) {
        if (stored == null || stored.isBlank()) {
            return false;
        }
        String key = stored.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return "THIS_SIGNAL".equals(key) || "ALIGNED".equals(key) || "ALIGNED_TIMELINE".equals(key);
    }

    private static boolean blankOrObsoleteTimeline(String stored) {
        return stored == null || stored.isBlank() || obsoleteTimeline(stored);
    }
}
