package com.stellar.platform.storage.metadata;

/** File-level project metadata. File contents and decoded signal samples are never stored here. */
public record ProjectFileMetadata(
        String projectId,
        String fileId,
        String path,
        String kind,
        long sizeBytes,
        String validationStatus,
        String validationMessage,
        String associatedDefinitionId,
        String finalizationLabel,
        boolean recoveryCandidate,
        boolean selectedMeasurement,
        boolean selectedDefinition
) {
    public ProjectFileMetadata {
        projectId = requireText(projectId, "projectId");
        fileId = requireText(fileId, "fileId");
        path = requireText(path, "path");
        kind = kind == null ? "UNKNOWN" : kind;
        sizeBytes = Math.max(0L, sizeBytes);
        validationStatus = validationStatus == null ? "" : validationStatus;
        validationMessage = validationMessage == null ? "" : validationMessage;
        finalizationLabel = finalizationLabel == null ? "" : finalizationLabel;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
