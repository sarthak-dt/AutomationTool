package com.stellar.platform.processing;

import com.stellar.platform.domain.SourceReference;

import java.util.Objects;

/**
 * Unified diagnostic from measurement reading or DBC decoding.
 */
public record ProcessingDiagnostic(
        String layer,
        String code,
        String message,
        SourceReference source
) {

    public ProcessingDiagnostic {
        if (layer == null || layer.isBlank()) {
            throw new IllegalArgumentException("layer must not be blank");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        Objects.requireNonNull(source, "source");
    }

    public static ProcessingDiagnostic ofReader(String code, String message, SourceReference source) {
        return new ProcessingDiagnostic("READER", code, message, source);
    }

    public static ProcessingDiagnostic ofDbc(String code, String message, SourceReference source) {
        return new ProcessingDiagnostic("DBC", code, message, source);
    }
}
