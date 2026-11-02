package com.stellar.platform.processing;

/**
 * How strongly the detector could prove a container.
 * Automatic processing accepts {@link #HIGH} and {@link #MEDIUM} only.
 */
public enum DetectionConfidence {
    HIGH,
    MEDIUM,
    LOW
}
