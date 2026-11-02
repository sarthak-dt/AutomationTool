package com.stellar.platform.analysis.investigation.kpi;

import com.stellar.platform.analysis.investigation.ReportFactKind;

/** One KPI row. HTML and PDF print these fields and do not recalculate them. */
public record EngineeringKpiResult(
        String id,
        String name,
        String valueText,
        Double numericValue,
        String unit,
        String sourceSignal,
        String sourceFile,
        String interval,
        String method,
        String coverage,
        String threshold,
        ReportFactKind provenance,
        String notes
) {
    public EngineeringKpiResult {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        valueText = valueText == null ? "" : valueText;
        unit = unit == null ? "" : unit;
        sourceSignal = sourceSignal == null ? "" : sourceSignal;
        sourceFile = sourceFile == null ? "" : sourceFile;
        interval = interval == null ? "" : interval;
        method = method == null || method.isBlank() ? EngineeringKpiCalculator.METHOD : method;
        coverage = coverage == null ? "" : coverage;
        threshold = threshold == null ? "" : threshold;
        provenance = provenance == null ? ReportFactKind.UNAVAILABLE : provenance;
        notes = notes == null ? "" : notes;
    }

    public String sourceLabel() {
        if (sourceSignal.isBlank() && sourceFile.isBlank()) {
            return "";
        }
        if (sourceFile.isBlank()) {
            return sourceSignal;
        }
        if (sourceSignal.isBlank()) {
            return sourceFile;
        }
        return sourceSignal + " · " + sourceFile;
    }
}
