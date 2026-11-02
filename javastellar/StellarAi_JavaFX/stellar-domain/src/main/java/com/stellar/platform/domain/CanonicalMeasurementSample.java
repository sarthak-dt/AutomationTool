package com.stellar.platform.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Format-independent native measurement sample emitted by a measurement-file parser
 * (e.g. an MF4 CN value after CC conversion), without going through CAN/DBC matching.
 * <p>
 * Processing-core maps this to {@link CanonicalSignal} for the shared catalog fan-in.
 */
public record CanonicalMeasurementSample(
        String signalName,
        long timestampNanos,
        int channel,
        double physicalValue,
        Long rawValue,
        String unit,
        String textualValue,
        SourceReference sourceReference,
        SignalValidity validity
) {

    public CanonicalMeasurementSample {
        if (signalName == null || signalName.isBlank()) {
            throw new IllegalArgumentException("signalName must not be blank");
        }
        if (channel < 0) {
            throw new IllegalArgumentException("channel must be >= 0");
        }
        unit = unit == null ? "" : unit;
        Objects.requireNonNull(sourceReference, "sourceReference");
        if (validity == null) {
            if (textualValue != null && !textualValue.isBlank() && !Double.isFinite(physicalValue)) {
                validity = SignalValidity.VALID;
            } else {
                validity = Double.isFinite(physicalValue)
                        ? SignalValidity.VALID
                        : SignalValidity.NON_FINITE_VALUE;
            }
        }
    }

    public CanonicalMeasurementSample(
            String signalName,
            long timestampNanos,
            int channel,
            double physicalValue,
            Long rawValue,
            String unit,
            String textualValue,
            SourceReference sourceReference
    ) {
        this(signalName, timestampNanos, channel, physicalValue, rawValue, unit, textualValue,
                sourceReference, null);
    }

    /**
     * Converts to the shared downstream signal type using a provenance message tag
     * (e.g. {@code MF4_MEASUREMENT} for native MF4 channels).
     */
    public CanonicalSignal toCanonicalSignal(String measurementMessageName) {
        String message = measurementMessageName == null || measurementMessageName.isBlank()
                ? "MEASUREMENT"
                : measurementMessageName;
        return new CanonicalSignal(
                signalName,
                message,
                timestampNanos,
                0L,
                channel,
                physicalValue,
                rawValue,
                unit,
                textualValue,
                sourceReference,
                validity
        );
    }

    public Optional<Long> rawValueOptional() {
        return Optional.ofNullable(rawValue);
    }

    public Optional<String> textualValueOptional() {
        return Optional.ofNullable(textualValue);
    }
}