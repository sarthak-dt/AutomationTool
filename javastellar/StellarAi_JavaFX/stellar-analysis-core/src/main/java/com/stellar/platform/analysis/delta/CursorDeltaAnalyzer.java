package com.stellar.platform.analysis.delta;

import com.stellar.platform.analysis.model.EngineeringFinding;
import com.stellar.platform.analysis.model.FindingType;
import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.analysis.source.SampleStreamer;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CursorDeltaAnalyzer {

    private CursorDeltaAnalyzer() {
    }

    public static EngineeringFinding delta(
            AnalysisSampleSource source,
            SignalCatalogKey key,
            long cursorA,
            long cursorB,
            String analysisId,
            AnalysisSeverity severity
    ) {
        SignalSamplePoint a = SampleStreamer.nearest(source, key, cursorA);
        SignalSamplePoint b = SampleStreamer.nearest(source, key, cursorB);
        if (a == null || b == null) {
            return new EngineeringFinding(
                    null, analysisId, FindingType.CURSOR_DELTA, severity, List.of(key),
                    cursorA, cursorB, "No samples at cursors", "", Map.of(), null);
        }
        double valueDelta = b.physicalValue() - a.physicalValue();
        long timeDelta = b.timestampNanos() - a.timestampNanos();
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("valueA", a.physicalValue());
        metrics.put("valueB", b.physicalValue());
        metrics.put("valueDelta", valueDelta);
        metrics.put("timeDeltaSeconds", timeDelta / 1_000_000_000.0d);
        metrics.put("timestampA", (double) a.timestampNanos());
        metrics.put("timestampB", (double) b.timestampNanos());
        return new EngineeringFinding(
                null, analysisId, FindingType.CURSOR_DELTA, severity, List.of(key),
                a.timestampNanos(), b.timestampNanos(),
                String.format("Δ = %+.6g over %.6f s", valueDelta, timeDelta / 1_000_000_000.0d),
                String.format("A=%.6g @ %d ns, B=%.6g @ %d ns",
                        a.physicalValue(), a.timestampNanos(), b.physicalValue(), b.timestampNanos()),
                metrics, null);
    }
}
