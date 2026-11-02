package com.stellar.platform.processing.map;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

import java.util.Objects;

/** One scored GPS-role candidate drawn from catalog metadata only. */
public record GpsSignalCandidate(
        SignalCatalogKey key,
        GpsSignalRole role,
        int score,
        String evidence
) {
    public GpsSignalCandidate {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(role, "role");
        evidence = evidence == null ? "" : evidence;
    }

    public static GpsSignalCandidate of(SignalCatalogEntry entry, GpsSignalRole role, int score, String evidence) {
        Objects.requireNonNull(entry, "entry");
        return new GpsSignalCandidate(entry.key(), role, score, evidence);
    }
}
