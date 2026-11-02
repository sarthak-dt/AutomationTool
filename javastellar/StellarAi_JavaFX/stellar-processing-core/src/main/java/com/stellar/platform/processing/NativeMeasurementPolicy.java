package com.stellar.platform.processing;

/**
 * Whether a decode pass publishes the measurement file's native (non-bus) channels.
 *
 * <p>Native channels such as GPS latitude/longitude belong to the measurement source, not to a
 * CAN bus channel, so they cannot be scoped by a
 * {@link com.stellar.platform.domain.processing.ProcessingSelection}. When one source is decoded
 * by several channel-scoped jobs, exactly one of them must own the native channels, otherwise the
 * same physical series would be written to several stores and appear twice in the combined
 * catalog.
 */
public enum NativeMeasurementPolicy {

    /** This pass owns the source's native channels and publishes them. */
    INCLUDE,

    /** Another pass over the same source owns the native channels. */
    EXCLUDE;

    public boolean publishes() {
        return this == INCLUDE;
    }
}
