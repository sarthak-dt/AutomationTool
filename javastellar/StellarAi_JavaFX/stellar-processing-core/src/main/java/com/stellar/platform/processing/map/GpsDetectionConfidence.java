package com.stellar.platform.processing.map;

/**
 * Discrete confidence for automatic GPS coordinate discovery.
 *
 * <p>Map Route auto-renders only {@link #HIGH} and {@link #MEDIUM}. {@link #LOW} and {@link #NONE}
 * never plot a route.
 */
public enum GpsDetectionConfidence {
    HIGH,
    MEDIUM,
    LOW,
    NONE;

    public boolean isReliable() {
        return this == HIGH || this == MEDIUM;
    }
}
