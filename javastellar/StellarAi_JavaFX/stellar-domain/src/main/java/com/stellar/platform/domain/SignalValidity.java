package com.stellar.platform.domain;

/**
 * Why a decoded physical value may or may not be used as a measurement.
 * Text, magnitude, and signal name are not substitutes for this state.
 * <p>
 * A successful categorical label stays {@link #VALID} together with a non-finite
 * physical value and the text. That pairing is recognized by
 * {@link CategoricalValue}. It is not a conversion failure and it is not a
 * usable number. The stored code is unchanged so previously saved projects
 * still open.
 */
public enum SignalValidity {
    VALID(0),
    INVALID_RAW_CODE(1),
    INACTIVE_RAW_CODE(2),
    CONVERSION_FAILED(3),
    NON_FINITE_VALUE(4),
    /**
     * The sample was stored before validity was recorded.
     * It is not a measurement classification and must not be treated as valid.
     */
    UNRECORDED(5);

    private final int storedCode;

    SignalValidity(int storedCode) {
        this.storedCode = storedCode;
    }

    public int storedCode() {
        return storedCode;
    }

    public boolean usableWith(double physicalValue) {
        return this == VALID && Double.isFinite(physicalValue);
    }

    public static SignalValidity fromStoredCode(int code) {
        for (SignalValidity validity : values()) {
            if (validity.storedCode == code) {
                return validity;
            }
        }
        return UNRECORDED;
    }
}
