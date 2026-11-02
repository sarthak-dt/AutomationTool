package com.stellar.platform.domain.catalog;

import com.stellar.platform.domain.SignalValidity;

/**
 * One timestamped sample for a selected catalog signal (bounded query result).
 * The physical number is never replaced with zero because of its validity state.
 */
public record SignalSamplePoint(
        long timestampNanos,
        double physicalValue,
        Long rawValue,
        String textualValue,
        SignalValidity validity
) {
    public SignalSamplePoint(long timestampNanos, double physicalValue, Long rawValue, String textualValue) {
        this(timestampNanos, physicalValue, rawValue, textualValue, null);
    }

    public SignalSamplePoint {
        if (validity == null) {
            validity = implied(physicalValue, textualValue);
        }
    }

    public boolean usableMeasurement() {
        return validity.usableWith(physicalValue);
    }

    /**
     * A label with no explicit status stays a categorical value. A bare NaN does not.
     */
    private static SignalValidity implied(double physical, String text) {
        if (text != null && !text.isBlank() && !Double.isFinite(physical)) {
            return SignalValidity.VALID;
        }
        return Double.isFinite(physical) ? SignalValidity.VALID : SignalValidity.NON_FINITE_VALUE;
    }
}
