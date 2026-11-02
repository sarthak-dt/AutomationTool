package com.stellar.platform.domain;

/**
 * Non-fatal issue encountered while processing a measurement file.
 * Processing should continue when safe.
 */
public record ProcessingWarning(String code, String message, SourceReference source) {

    public ProcessingWarning {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
    }
}
