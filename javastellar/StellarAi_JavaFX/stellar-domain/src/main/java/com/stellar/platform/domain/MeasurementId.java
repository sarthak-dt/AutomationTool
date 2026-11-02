package com.stellar.platform.domain;

/**
 * Canonical identifier for a measurement recording (format-independent).
 */
public record MeasurementId(String value) {

    public MeasurementId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("MeasurementId value must not be blank");
        }
    }
}
