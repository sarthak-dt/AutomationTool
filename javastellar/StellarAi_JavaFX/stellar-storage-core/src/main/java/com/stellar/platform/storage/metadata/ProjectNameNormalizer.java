package com.stellar.platform.storage.metadata;

import java.util.Locale;
import java.util.Objects;

/**
 * Application-controlled project display/normalized names.
 * Normalization: trim + {@link Locale#ROOT} lower-case — portable to PostgreSQL.
 */
public final class ProjectNameNormalizer {

    private ProjectNameNormalizer() {
    }

    /** Trimmed display name; blank rejected. */
    public static String requireDisplayName(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Project name must not be empty.");
        }
        return trimmed;
    }

    /** Case- and whitespace-insensitive identity key for uniqueness. */
    public static String normalized(String raw) {
        return requireDisplayName(raw).toLowerCase(Locale.ROOT);
    }

    public static boolean sameNormalized(String a, String b) {
        if (a == null || b == null) return false;
        try {
            return Objects.equals(normalized(a), normalized(b));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
