package com.stellar.platform.desktop.presentation;

import java.util.Locale;
import java.util.Objects;

/**
 * Authoritative analysis-measurement identity.
 * <p>
 * {@code sourceId} is the canonical workspace/source identity used for engineering catalog
 * scope, GPS mapping lookup, and map sample reads. {@code fileName} is display/provenance
 * only. Controllers must not invent a third identity.
 */
public final class AnalysisMeasurementContext {

    private static final AnalysisMeasurementContext NONE = new AnalysisMeasurementContext("", "");

    private final String sourceId;
    private final String fileName;

    private AnalysisMeasurementContext(String sourceId, String fileName) {
        this.sourceId = sourceId == null ? "" : sourceId.trim();
        this.fileName = fileName == null ? "" : fileName.trim();
    }

    public static AnalysisMeasurementContext none() {
        return NONE;
    }

    public static AnalysisMeasurementContext of(String sourceId, String fileName) {
        String id = sourceId == null ? "" : sourceId.trim();
        String name = fileName == null ? "" : fileName.trim();
        if (id.isEmpty() && name.isEmpty()) {
            return NONE;
        }
        return new AnalysisMeasurementContext(id, name);
    }

    public boolean isPresent() {
        return !sourceId.isEmpty() || !fileName.isEmpty();
    }

    /** Canonical engineering identity. Prefer this over {@link #fileName()}. */
    public String sourceId() {
        return sourceId;
    }

    /** Display / physical filename. Never the primary engineering key. */
    public String fileName() {
        return fileName;
    }

    public boolean matches(String identityOrName) {
        if (identityOrName == null || identityOrName.isBlank()) {
            return false;
        }
        String needle = identityOrName.trim();
        return equalsIgnoreCase(sourceId, needle) || equalsIgnoreCase(fileName, needle);
    }

    public String token() {
        return sourceId + '|' + fileName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AnalysisMeasurementContext that)) {
            return false;
        }
        return sourceId.equals(that.sourceId) && fileName.equals(that.fileName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceId, fileName);
    }

    @Override
    public String toString() {
        return token();
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a.equalsIgnoreCase(b)
                || a.toLowerCase(Locale.ROOT).endsWith("/" + b.toLowerCase(Locale.ROOT));
    }
}
