package com.stellar.platform.processing.map;

/**
 * Which configuration level owns a mapping.
 *
 * <p>A {@link #PROJECT} mapping belongs to one project and always wins. A {@link #DEFAULT} mapping is
 * the product-wide fallback shared by every project on the installation, so a fleet that names its
 * location channels consistently is configured once.
 */
public enum RouteMappingScope {
    PROJECT("Project configuration"),
    DEFAULT("Product default");

    private final String label;

    RouteMappingScope(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static RouteMappingScope parse(String raw) {
        if (raw == null) {
            return PROJECT;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return PROJECT;
        }
    }
}
