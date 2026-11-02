package com.stellar.platform.api.identity;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Cross-boundary identity helpers. Prefer plain {@link String} IDs in DTOs over wrapper types.
 * <p>
 * Logical names used across Local/HTTP:
 * {@code projectId}, {@code sourceId}, {@code fileAssetId}/{@code measurementAssetId},
 * {@code definitionAssetId}, {@code uploadSessionId}.
 */
public final class PlatformIds {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private PlatformIds() {
    }

    /** New opaque identity suitable for project/source/asset/upload session ids. */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Validates a client/server identity string. Rejects path separators and traversal tokens.
     */
    public static String requireId(String label, String value) {
        Objects.requireNonNull(label, "label");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.contains("/") || trimmed.contains("\\") || trimmed.contains("..")) {
            throw new IllegalArgumentException(label + " must not contain path elements");
        }
        if (!SAFE_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(label + " has invalid characters: " + trimmed);
        }
        return trimmed;
    }

    /** Optional id: blank becomes empty string; non-blank must pass {@link #requireId}. */
    public static String optionalId(String label, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return requireId(label, value);
    }

    /**
     * Metadata-only display name. Never used as a filesystem path segment.
     * Strips separators so collisions across projects are harmless for storage layout.
     */
    public static String displayFileName(String originalFileName) {
        if (originalFileName == null || originalFileName.isBlank()) {
            return "unnamed";
        }
        String name = originalFileName.trim().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replace("..", "_");
        if (name.isBlank()) {
            return "unnamed";
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }

    public static String normalizeKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return FileAssetKinds.MEASUREMENT;
        }
        String upper = kind.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case FileAssetKinds.DEFINITION, "DBC", "A2L", "ODX", "PDX" -> FileAssetKinds.DEFINITION;
            case FileAssetKinds.MEASUREMENT, "MF4", "ASC", "BLF", "MDF" -> FileAssetKinds.MEASUREMENT;
            default -> upper;
        };
    }
}
