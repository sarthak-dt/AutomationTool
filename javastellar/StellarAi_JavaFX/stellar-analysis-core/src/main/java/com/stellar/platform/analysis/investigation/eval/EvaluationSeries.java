package com.stellar.platform.analysis.investigation.eval;

import java.util.List;

/** Stored samples for one signal. The engine does not resample or replace gaps with zero. */
public record EvaluationSeries(String signalName, String unit, String sourceId, List<EvaluationSample> samples) {
    public EvaluationSeries {
        signalName = signalName == null ? "" : signalName.strip();
        unit = unit == null ? "" : unit.strip();
        sourceId = sourceId == null ? "" : sourceId;
        samples = samples == null ? List.of() : List.copyOf(samples);
    }
}
