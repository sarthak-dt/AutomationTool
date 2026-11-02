package com.stellar.platform.analysis.investigation.report;

import com.stellar.platform.analysis.investigation.EngineerEventDefinition;
import com.stellar.platform.analysis.investigation.EngineeringFormats;

import java.util.StringJoiner;

/** Report rows for an engineer event. The text is the saved definition, not a new measurement. */
public final class EngineerEventReportRows {

    private EngineerEventReportRows() {
    }

    public static EngineeringReportModel.EventRow row(EngineerEventDefinition event) {
        String type = event.category().isBlank()
                ? (event.window() ? "Time window" : "Point")
                : event.category();
        String end = event.window() ? EngineeringFormats.seconds(event.endNanos()) : "";
        String signals = join(event.signals());
        String location = event.gnssStatement().isBlank()
                ? "GPS evidence was not attached. No coordinate was invented."
                : event.gnssStatement();
        String snapshot = event.snapshotNote().isBlank()
                ? (event.snapshotIds().isEmpty() ? "No graph snapshot is linked." : join(event.snapshotIds()))
                : event.snapshotNote();
        String provenance = "Engineer event " + event.id()
                + ". Confirmation: " + event.confirmation()
                + ". Timestamps are measurement-relative seconds from this recording's own zero."
                + " The original samples were not modified.";
        return new EngineeringReportModel.EventRow(
                event.name(),
                type,
                EngineeringFormats.seconds(event.startNanos()),
                end,
                event.sourceFile(),
                signals,
                event.description(),
                snapshot,
                location,
                provenance,
                new EngineeringReportModel.EventNarrative(
                        event.confirmation(), event.expected(), event.measured(), event.limitations()));
    }

    public static String evidenceSummary(EngineeringReportModel.EventRow event) {
        boolean snapshot = hasSnapshot(event.snapshot());
        String location = event.location() == null ? "" : event.location();
        boolean gnss = location.contains("Latitude ")
                || location.contains("First GNSS")
                || location.contains("Nearest GNSS");
        boolean requested = location.contains("was requested");
        if (location.contains("not attached")) {
            return snapshot ? "Snapshot" : "GPS not attached";
        }
        if (snapshot && gnss) {
            return "Snapshot, GNSS sample";
        }
        if (gnss) {
            return "GNSS sample";
        }
        if (snapshot && requested) {
            return "Snapshot, GPS requested";
        }
        if (requested) {
            return "GPS requested";
        }
        if (snapshot) {
            return "Snapshot";
        }
        return "No GNSS sample";
    }

    private static boolean hasSnapshot(String snapshot) {
        return snapshot != null && !snapshot.isBlank()
                && !snapshot.startsWith("No graph snapshot");
    }

    private static String join(java.util.List<String> values) {
        StringJoiner joiner = new StringJoiner(", ");
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                joiner.add(value);
            }
        }
        return joiner.toString();
    }
}
