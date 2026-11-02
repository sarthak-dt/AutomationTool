package com.stellar.platform.desktop.ui.graph.map;

import java.util.Locale;

/**
 * Geopolitical boundary rendering policy. Separate from map-label language.
 *
 * <p>{@link #INDIA_OFFICIAL} is modelled now but is not a supported source until an
 * authoritative licensed dataset is configured. This type must not grow hard-coded disputed
 * polygons.
 */
public enum MapBoundaryProfile {
    PROVIDER_DEFAULT("Provider Default"),
    GLOBAL_NEUTRAL("Global Neutral"),
    INDIA_OFFICIAL("India Official");

    private final String displayLabel;

    MapBoundaryProfile(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public String displayLabel() {
        if (this == INDIA_OFFICIAL && !indiaOfficialSourceConfigured()) {
            return "India Official (Unavailable)";
        }
        return displayLabel;
    }

    public static MapBoundaryProfile defaultProfile() {
        return PROVIDER_DEFAULT;
    }

    /**
     * India Official has no approved licensed source in this phase.
     */
    public static boolean indiaOfficialSourceConfigured() {
        return false;
    }

    public boolean selectableAsSupported() {
        if (this == INDIA_OFFICIAL) {
            return indiaOfficialSourceConfigured();
        }
        return true;
    }

    public static MapBoundaryProfile fromPersisted(String raw) {
        if (raw == null || raw.isBlank()) {
            return PROVIDER_DEFAULT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PROVIDER_DEFAULT;
        }
    }

    /** Profile used for tile matching when the requested profile has no source. */
    public MapBoundaryProfile forTileMatching() {
        if (this == INDIA_OFFICIAL && !indiaOfficialSourceConfigured()) {
            return PROVIDER_DEFAULT;
        }
        return this;
    }
}
