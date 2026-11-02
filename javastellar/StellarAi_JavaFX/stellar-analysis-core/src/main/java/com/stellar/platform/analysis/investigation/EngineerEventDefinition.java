package com.stellar.platform.analysis.investigation;

import java.util.ArrayList;
import java.util.List;

/**
 * Engineer-authored event. Categories are labels only and do not start a calculation.
 * A null {@code endNanos} is a point event. A window requires {@code endNanos >= startNanos}.
 */
public record EngineerEventDefinition(
        String id,
        String name,
        String category,
        String sourceId,
        String sourceFile,
        long startNanos,
        Long endNanos,
        List<String> signals,
        String description,
        String expected,
        String measured,
        List<String> snapshotIds,
        boolean gpsAttached,
        String confirmation,
        String gnssStatement,
        String limitations,
        String snapshotNote
) {
    public EngineerEventDefinition {
        id = id == null ? "" : id;
        name = name == null ? "" : name.strip();
        category = category == null ? "" : category.strip();
        sourceId = sourceId == null ? "" : sourceId;
        sourceFile = sourceFile == null ? "" : sourceFile;
        signals = copy(signals);
        description = description == null ? "" : description.strip();
        expected = expected == null ? "" : expected.strip();
        measured = measured == null ? "" : measured.strip();
        snapshotIds = copy(snapshotIds);
        confirmation = confirmation == null || confirmation.isBlank() ? "Draft" : confirmation.strip();
        gnssStatement = gnssStatement == null ? "" : gnssStatement.strip();
        limitations = limitations == null ? "" : limitations.strip();
        snapshotNote = snapshotNote == null ? "" : snapshotNote.strip();
    }

    public boolean window() {
        return endNanos != null;
    }

    public EngineerEventDefinition withId(String eventId) {
        return new EngineerEventDefinition(eventId, name, category, sourceId, sourceFile, startNanos, endNanos,
                signals, description, expected, measured, snapshotIds, gpsAttached, confirmation,
                gnssStatement, limitations, snapshotNote);
    }

    public EngineerEventDefinition withSignals(List<String> names) {
        return new EngineerEventDefinition(id, name, category, sourceId, sourceFile, startNanos, endNanos,
                names, description, expected, measured, snapshotIds, gpsAttached, confirmation,
                gnssStatement, limitations, snapshotNote);
    }

    public EngineerEventDefinition withStatements(String gnss, String limits, String snapshot) {
        return new EngineerEventDefinition(id, name, category, sourceId, sourceFile, startNanos, endNanos,
                signals, description, expected, measured, snapshotIds, gpsAttached, confirmation,
                gnss, limits, snapshot);
    }

    private static List<String> copy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.strip());
            }
        }
        return List.copyOf(out);
    }
}
