package com.stellar.platform.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * Format-independent decoded signal value produced by DBC (or other) decoding.
 */
public record CanonicalSignal(
        String signalName,
        String messageName,
        long timestampNanos,
        long canId,
        int channel,
        double physicalValue,
        Long rawValue,
        String unit,
        String textualValue,
        SourceReference sourceReference,
        /**
         * True when the raw value matches an explicit invalid or inactive code.
         * The raw and physical values are still the decoded numbers; they are not replaced with zero.
         * Prefer {@link #validity()} when inactive must be distinguished from invalid.
         */
        boolean valueInvalid,
        /**
         * Explicit measurement status. Null on older constructors is derived from
         * {@code valueInvalid} and whether the physical value is finite.
         */
        SignalValidity validity
) {

    public CanonicalSignal {
        if (signalName == null || signalName.isBlank()) {
            throw new IllegalArgumentException("signalName must not be blank");
        }
        if (messageName == null || messageName.isBlank()) {
            throw new IllegalArgumentException("messageName must not be blank");
        }
        if (channel < 0) {
            throw new IllegalArgumentException("channel must be >= 0");
        }
        if (canId < 0) {
            throw new IllegalArgumentException("canId must be >= 0");
        }
        unit = unit == null ? "" : unit;
        Objects.requireNonNull(sourceReference, "sourceReference");
        if (validity == null) {
            if (valueInvalid) {
                validity = SignalValidity.INVALID_RAW_CODE;
            } else if (textualValue != null && !textualValue.isBlank() && !Double.isFinite(physicalValue)) {
                validity = SignalValidity.VALID;
            } else if (!Double.isFinite(physicalValue)) {
                validity = SignalValidity.NON_FINITE_VALUE;
            } else {
                validity = SignalValidity.VALID;
            }
        }
        valueInvalid = validity == SignalValidity.INVALID_RAW_CODE
                || validity == SignalValidity.INACTIVE_RAW_CODE;
    }

    /**
     * Signals produced before validity attributes were recorded are valid.
     */
    public CanonicalSignal(
            String signalName,
            String messageName,
            long timestampNanos,
            long canId,
            int channel,
            double physicalValue,
            Long rawValue,
            String unit,
            String textualValue,
            SourceReference sourceReference
    ) {
        this(
                signalName, messageName, timestampNanos, canId, channel, physicalValue, rawValue,
                unit, textualValue, sourceReference, false, null
        );
    }

    /**
     * Boolean-only callers cannot distinguish inactive from invalid. A true flag becomes
     * {@link SignalValidity#INVALID_RAW_CODE}. Non-finite values with a false flag become
     * {@link SignalValidity#NON_FINITE_VALUE}.
     */
    public CanonicalSignal(
            String signalName,
            String messageName,
            long timestampNanos,
            long canId,
            int channel,
            double physicalValue,
            Long rawValue,
            String unit,
            String textualValue,
            SourceReference sourceReference,
            boolean valueInvalid
    ) {
        this(
                signalName, messageName, timestampNanos, canId, channel, physicalValue, rawValue,
                unit, textualValue, sourceReference, valueInvalid, null
        );
    }

    /**
     * Records an explicit validity state. Invalid and inactive raw codes keep their physical numbers.
     */
    public CanonicalSignal(
            String signalName,
            String messageName,
            long timestampNanos,
            long canId,
            int channel,
            double physicalValue,
            Long rawValue,
            String unit,
            String textualValue,
            SourceReference sourceReference,
            SignalValidity validity
    ) {
        this(
                signalName, messageName, timestampNanos, canId, channel, physicalValue, rawValue,
                unit, textualValue, sourceReference, false, validity
        );
    }

    public boolean usableMeasurement() {
        return validity.usableWith(physicalValue);
    }

    public Optional<Long> rawValueOptional() {
        return Optional.ofNullable(rawValue);
    }

    public Optional<String> textualValueOptional() {
        return Optional.ofNullable(textualValue);
    }
}
