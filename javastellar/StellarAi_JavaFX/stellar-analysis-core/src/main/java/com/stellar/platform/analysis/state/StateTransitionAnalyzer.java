package com.stellar.platform.analysis.state;

import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.model.EngineeringFinding;
import com.stellar.platform.analysis.model.FindingType;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.analysis.source.SampleStreamer;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.parser.spi.CancellationToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Discrete / enumerated transitions. Consecutive identical samples do not emit events.
 */
public final class StateTransitionAnalyzer {

    private StateTransitionAnalyzer() {
    }

    public static List<EngineeringFinding> detect(
            AnalysisSampleSource source,
            SignalCatalogKey key,
            TimeWindow window,
            String analysisId,
            AnalysisSeverity severity,
            CancellationToken token
    ) {
        List<EngineeringFinding> out = new ArrayList<>();
        final String[] prev = {null};
        SampleStreamer.forEach(source, key, window, token, sample -> {
            String state = stateOf(sample.textualValue(), sample.physicalValue(), sample.validity());
            if (prev[0] == null) {
                prev[0] = state;
                return true;
            }
            if (!Objects.equals(prev[0], state)) {
                Map<String, Double> metrics = new LinkedHashMap<>();
                metrics.put("previousNumeric", parseNumeric(prev[0]));
                metrics.put("newNumeric", parseNumeric(state));
                out.add(new EngineeringFinding(
                        null, analysisId, FindingType.STATE_TRANSITION, severity, List.of(key),
                        sample.timestampNanos(), sample.timestampNanos(),
                        prev[0] + " → " + state,
                        "previous=" + prev[0] + " new=" + state,
                        metrics, null));
                prev[0] = state;
            }
            return true;
        });
        return List.copyOf(out);
    }

    public static String stateOf(String textual, double physical, SignalValidity validity) {
        if (textual != null && !textual.isBlank()) {
            return textual.trim();
        }
        if (validity != SignalValidity.VALID) {
            return validity == null ? SignalValidity.UNRECORDED.name() : validity.name();
        }
        if (!Double.isFinite(physical)) {
            return "NaN";
        }
        if (physical == Math.rint(physical) && Math.abs(physical) < 1_000_000_000d) {
            return Long.toString((long) physical);
        }
        return Double.toString(physical);
    }

    private static double parseNumeric(String state) {
        try {
            return Double.parseDouble(state);
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }
}
