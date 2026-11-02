package com.stellar.platform.processing.drive.speed;

import com.stellar.platform.domain.catalog.SignalCatalogKey;

/**
 * Lightweight persisted resolution for one measurement. Samples stay in the lossless store.
 */
public record SpeedSourceMapping(
        int resolverVersion,
        String sourceId,
        String schemaFingerprint,
        SpeedSourceKind kind,
        SignalCatalogKey key,
        String originalUnit,
        SpeedConfidence confidence,
        String reason
) {
    public SpeedSourceMapping {
        sourceId = sourceId == null ? "" : sourceId;
        schemaFingerprint = schemaFingerprint == null ? "" : schemaFingerprint;
        kind = kind == null ? SpeedSourceKind.GPS_COORDINATE_DERIVED : kind;
        originalUnit = originalUnit == null ? "" : originalUnit;
        confidence = confidence == null ? SpeedConfidence.NONE : confidence;
        reason = reason == null ? "" : reason;
    }

    public boolean coordinateDerived() {
        return kind == SpeedSourceKind.GPS_COORDINATE_DERIVED;
    }
}
