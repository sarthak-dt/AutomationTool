package com.stellar.platform.processing.drive.speed;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

/**
 * One scored speed-source candidate for a single measurement. Display name is never identity.
 */
public record SpeedSourceCandidate(
        SpeedSourceKind kind,
        SignalCatalogEntry entry,
        SpeedUnit unit,
        int semanticScore,
        int coverageScore,
        int totalScore,
        String reason
) {
    public SpeedSourceCandidate {
        kind = kind == null ? SpeedSourceKind.GPS_COORDINATE_DERIVED : kind;
        semanticScore = Math.max(0, semanticScore);
        coverageScore = Math.max(0, coverageScore);
        totalScore = Math.max(0, totalScore);
        reason = reason == null ? "" : reason;
    }

    public SignalCatalogKey key() {
        return entry == null ? null : entry.key();
    }

    public String displayName() {
        if (kind == SpeedSourceKind.GPS_COORDINATE_DERIVED) {
            return kind.displayLabel();
        }
        return entry == null ? kind.displayLabel() : entry.signalName();
    }
}
