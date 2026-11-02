package com.stellar.platform.analysis.workflow;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;

public record BindingCandidate(
        SignalCatalogKey key,
        String unit,
        long sampleCount,
        Long firstTimestampNanos,
        Long lastTimestampNanos
) {
    public static BindingCandidate of(SignalCatalogEntry entry) {
        return new BindingCandidate(
                entry.key(),
                entry.unit(),
                entry.sampleCount(),
                entry.firstTimestampNanos(),
                entry.lastTimestampNanos());
    }

    public String signalName() {
        return key == null ? "" : key.signalName();
    }

    public String source() {
        return key == null ? "" : key.sourceFile();
    }

    public int channel() {
        return key == null ? 0 : key.channel();
    }

    public String sourceType() {
        return key == null || key.sourceType() == null ? "" : key.sourceType().name();
    }
}
