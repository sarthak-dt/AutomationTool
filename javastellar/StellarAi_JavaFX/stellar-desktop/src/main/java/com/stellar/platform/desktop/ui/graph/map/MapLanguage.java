package com.stellar.platform.desktop.ui.graph.map;

import java.util.Locale;

/**
 * Application-level map label language. Not stored per project, measurement, or GPS mapping.
 */
public enum MapLanguage {
    ENGLISH("English");

    private final String displayLabel;

    MapLanguage(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public String displayLabel() {
        return displayLabel;
    }

    public static MapLanguage defaultLanguage() {
        return ENGLISH;
    }

    public static MapLanguage fromPersisted(String raw) {
        if (raw == null || raw.isBlank()) {
            return ENGLISH;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return ENGLISH;
        }
    }
}
