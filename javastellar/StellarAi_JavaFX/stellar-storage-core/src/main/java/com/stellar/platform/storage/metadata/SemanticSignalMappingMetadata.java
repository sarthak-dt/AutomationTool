package com.stellar.platform.storage.metadata;

import java.time.Instant;
import java.util.Objects;

/**
 * Persisted configuration mapping for one field of one configuration group.
 *
 * <p>{@code role} is the field key (GPS first; free-form TEXT so later parameters reuse the table).
 * A mapping either points at a catalog signal ({@code SIGNAL_REFERENCE}) or carries a signal name the
 * engineer typed ({@code MANUAL_TEXT}), which is how a product whose channel names differ from the
 * loaded measurement can still be configured.
 *
 * <p>A {@code PROJECT} row lives in the project database and applies to one project source. A
 * {@code DEFAULT} row is product-wide, has no project or source, and is only consulted when the
 * project has nothing to say.
 */
public record SemanticSignalMappingMetadata(
        String projectId,
        String sourceId,
        String schemaFingerprint,
        String configGroup,
        String scope,
        String role,
        String mappingMode,
        String signalName,
        String sourceType,
        String sourceFile,
        String messageName,
        int channel,
        String manualText,
        boolean userConfirmed,
        Instant updatedAt
) {

    public static final String GROUP_MAP_ROUTE = "MAP_ROUTE";
    public static final String GROUP_VEHICLE_SPEED = "VEHICLE_SPEED";
    public static final String ROLE_AUTHORITATIVE_SPEED = "AUTHORITATIVE_SPEED";
    public static final String SCOPE_PROJECT = "PROJECT";
    public static final String SCOPE_DEFAULT = "DEFAULT";
    public static final String MODE_SIGNAL_REFERENCE = "SIGNAL_REFERENCE";
    public static final String MODE_MANUAL_TEXT = "MANUAL_TEXT";

    public SemanticSignalMappingMetadata {
        projectId = Objects.requireNonNull(projectId, "projectId");
        sourceId = sourceId == null ? "" : sourceId;
        schemaFingerprint = schemaFingerprint == null ? "" : schemaFingerprint;
        configGroup = configGroup == null || configGroup.isBlank() ? GROUP_MAP_ROUTE : configGroup;
        scope = scope == null || scope.isBlank() ? SCOPE_PROJECT : scope;
        role = Objects.requireNonNull(role, "role");
        mappingMode = mappingMode == null || mappingMode.isBlank() ? MODE_SIGNAL_REFERENCE : mappingMode;
        signalName = signalName == null ? "" : signalName;
        sourceType = sourceType == null ? "" : sourceType;
        sourceFile = sourceFile == null ? "" : sourceFile;
        messageName = messageName == null || messageName.isBlank() ? "GPS" : messageName;
        manualText = manualText == null ? "" : manualText;
        updatedAt = updatedAt == null ? Instant.now() : updatedAt;
        if (MODE_MANUAL_TEXT.equals(mappingMode) && manualText.isBlank()) {
            throw new IllegalArgumentException("A manual mapping needs the signal name the user typed");
        }
        if (MODE_SIGNAL_REFERENCE.equals(mappingMode) && signalName.isBlank()) {
            throw new IllegalArgumentException("A signal reference needs a signal name");
        }
    }

    /** Project-scoped MAP_ROUTE mapping that points at a catalog signal. */
    public static SemanticSignalMappingMetadata signalReference(
            String projectId,
            String sourceId,
            String schemaFingerprint,
            String role,
            String signalName,
            String sourceType,
            String sourceFile,
            String messageName,
            int channel,
            boolean userConfirmed,
            Instant updatedAt
    ) {
        return new SemanticSignalMappingMetadata(projectId, sourceId, schemaFingerprint,
                GROUP_MAP_ROUTE, SCOPE_PROJECT, role, MODE_SIGNAL_REFERENCE,
                signalName, sourceType, sourceFile, messageName, channel, "", userConfirmed, updatedAt);
    }

    /** Project-scoped MAP_ROUTE mapping for a signal name the engineer typed. */
    public static SemanticSignalMappingMetadata manualText(
            String projectId,
            String sourceId,
            String schemaFingerprint,
            String role,
            String manualText,
            boolean userConfirmed,
            Instant updatedAt
    ) {
        return new SemanticSignalMappingMetadata(projectId, sourceId, schemaFingerprint,
                GROUP_MAP_ROUTE, SCOPE_PROJECT, role, MODE_MANUAL_TEXT,
                "", "", "", "GPS", 0, manualText, userConfirmed, updatedAt);
    }

    public boolean manual() {
        return MODE_MANUAL_TEXT.equals(mappingMode);
    }

    /** The name to look for in a catalog, whichever input method produced this mapping. */
    public String effectiveSignalName() {
        return manual() ? manualText : signalName;
    }
}
