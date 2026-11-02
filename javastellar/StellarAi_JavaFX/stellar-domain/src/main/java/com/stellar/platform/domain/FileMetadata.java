package com.stellar.platform.domain;

/**
 * Lightweight metadata describing an opened measurement file.
 */
public record FileMetadata(String formatKey, String fileName, String absolutePath) {

    public FileMetadata {
        if (formatKey == null || formatKey.isBlank()) {
            throw new IllegalArgumentException("formatKey must not be blank");
        }
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("fileName must not be blank");
        }
        if (absolutePath == null || absolutePath.isBlank()) {
            throw new IllegalArgumentException("absolutePath must not be blank");
        }
    }
}
