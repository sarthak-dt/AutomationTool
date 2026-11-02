package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogKey;

/**
 * One signal's value at a requested timestamp.
 *
 * @param sampleTimestampNanos timestamp of the sample actually used; differs from the request when
 *                             the policy resolved a neighbouring sample
 * @param textualValue         decoded state text for enum/state signals, otherwise {@code null}
 */
public record SignalSnapshotValue(
        SignalCatalogKey key,
        long requestedTimestampNanos,
        long sampleTimestampNanos,
        double physicalValue,
        String textualValue,
        String unit,
        SignalAlignmentPolicy appliedPolicy
) {

    public long alignmentDeltaNanos() {
        return sampleTimestampNanos - requestedTimestampNanos;
    }

    public boolean exact() {
        return sampleTimestampNanos == requestedTimestampNanos;
    }
}
