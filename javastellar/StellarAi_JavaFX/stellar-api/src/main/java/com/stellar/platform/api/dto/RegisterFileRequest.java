package com.stellar.platform.api.dto;

public record RegisterFileRequest(
        String path,
        String kind,
        String fileId,
        long sizeBytes,
        String validationStatus,
        String validationMessage
) {
    public RegisterFileRequest {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        kind = kind == null || kind.isBlank() ? "UNKNOWN" : kind.trim();
        fileId = fileId == null ? "" : fileId.trim();
        sizeBytes = Math.max(0L, sizeBytes);
        validationStatus = validationStatus == null ? "" : validationStatus;
        validationMessage = validationMessage == null ? "" : validationMessage;
    }

    public RegisterFileRequest(String path, String kind) {
        this(path, kind, "", 0L, "", "");
    }
}
