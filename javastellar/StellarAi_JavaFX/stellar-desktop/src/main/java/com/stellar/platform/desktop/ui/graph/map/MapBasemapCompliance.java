package com.stellar.platform.desktop.ui.graph.map;

/**
 * Whether the resolved basemap meets the English-label product requirement.
 *
 * <p>OSM Carto may be a usable geographic map ({@link #STANDARD_FALLBACK}) but is never
 * {@link #ENGLISH_COMPLIANT}.
 */
public enum MapBasemapCompliance {
    ENGLISH_COMPLIANT,
    STANDARD_FALLBACK,
    NEUTRAL
}
