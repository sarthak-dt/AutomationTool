package com.stellar.platform.analysis.investigation.kpi;

import java.util.List;

/** One bound signal. Samples stay in measurement order until the calculator sorts them. */
public record EngineeringKpiSeries(
        String role,
        String signalName,
        String unit,
        String sourceFile,
        Availability availability,
        List<Sample> samples
) {
    public enum Availability {
        PRESENT,
        NOT_CONFIGURED,
        NOT_FOUND,
        SAMPLES_NOT_LOADED
    }

    public record Sample(long timestampNanos, double value, boolean valid, String text) {
    }

    public EngineeringKpiSeries {
        role = role == null ? "" : role;
        signalName = signalName == null ? "" : signalName;
        unit = unit == null ? "" : unit;
        sourceFile = sourceFile == null ? "" : sourceFile;
        availability = availability == null ? Availability.PRESENT : availability;
        samples = samples == null ? List.of() : List.copyOf(samples);
    }

    public static EngineeringKpiSeries present(
            String role, String signalName, String unit, String sourceFile, List<Sample> samples
    ) {
        return new EngineeringKpiSeries(role, signalName, unit, sourceFile, Availability.PRESENT, samples);
    }

    public static EngineeringKpiSeries missing(String role, String signalName, Availability availability) {
        return new EngineeringKpiSeries(role, signalName, "", "", availability, List.of());
    }
}
