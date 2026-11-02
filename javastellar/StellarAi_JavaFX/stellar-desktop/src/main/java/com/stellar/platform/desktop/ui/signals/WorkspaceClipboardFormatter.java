package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.AlignedSampleRow;
import com.stellar.platform.desktop.presentation.SampleDisplay;
import com.stellar.platform.desktop.presentation.AlignedWorkspaceSnapshot;
import com.stellar.platform.desktop.presentation.WorkspaceDisplayMode;
import com.stellar.platform.desktop.presentation.WorkspacePrecision;
import com.stellar.platform.desktop.ui.UiFormat;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSourceType;

import java.util.List;
import java.util.Objects;

/**
 * Excel-friendly TSV formatter for the bounded Active Signal Workspace table.
 * <p>
 * This is presentation-only. It reads the immutable aligned snapshot and never mutates signal
 * samples, timestamps, precision settings, or processing state.
 */
final class WorkspaceClipboardFormatter {

    private WorkspaceClipboardFormatter() {
    }

    static String toTsv(
            AlignedWorkspaceSnapshot snapshot,
            WorkspaceDisplayMode displayMode,
            WorkspacePrecision precision,
            boolean includeIndex,
            boolean includeTimestamp,
            boolean includeDelta
    ) {
        return toTsv(snapshot, displayMode, precision, includeIndex, includeTimestamp, includeDelta, "Index");
    }

    static String toTsv(
            AlignedWorkspaceSnapshot snapshot,
            WorkspaceDisplayMode displayMode,
            WorkspacePrecision precision,
            boolean includeIndex,
            boolean includeTimestamp,
            boolean includeDelta,
            String indexHeader
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        WorkspaceDisplayMode mode = displayMode == null ? WorkspaceDisplayMode.PHYSICAL : displayMode;
        WorkspacePrecision p = precision == null ? WorkspacePrecision.AUTO : precision;
        List<SignalCatalogEntry> signals = snapshot.signals();
        List<AlignedSampleRow> rows = snapshot.rows();

        StringBuilder out = new StringBuilder(estimateCapacity(rows.size(), signals.size()));
        boolean first = true;
        if (includeIndex) {
            appendCell(out, indexHeader == null || indexHeader.isBlank() ? "Index" : indexHeader, first);
            first = false;
        }
        if (includeTimestamp) {
            appendCell(out, "Timestamp (s)", first);
            first = false;
        }
        if (includeDelta) {
            appendCell(out, "Δt (ms)", first);
            first = false;
        }
        boolean includeSourceIdentity = signals.stream()
                .map(SignalCatalogEntry::sourceFile)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .limit(2)
                .count() > 1;
        boolean includeDbcIdentity = signals.stream()
                .map(SignalCatalogEntry::sourceDatabase)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .limit(2)
                .count() > 1;
        for (SignalCatalogEntry signal : signals) {
            appendCell(out, signalHeader(signal, includeSourceIdentity, includeDbcIdentity), first);
            first = false;
        }
        out.append("\r\n");

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            AlignedSampleRow row = rows.get(rowIndex);
            first = true;
            if (includeIndex) {
                appendCell(out, Long.toString(row.index()), first);
                first = false;
            }
            if (includeTimestamp) {
                appendCell(out, UiFormat.nanos(row.timestampNanos()), first);
                first = false;
            }
            if (includeDelta) {
                String delta = rowIndex == 0
                        ? AlignedSampleRow.MISSING
                        : formatDeltaMillis(
                                row.timestampNanos() - rows.get(rowIndex - 1).timestampNanos());
                appendCell(out, delta, first);
                first = false;
            }
            for (int signalIndex = 0; signalIndex < signals.size(); signalIndex++) {
                appendCell(out, formatValue(row, signalIndex, mode, p), first);
                first = false;
            }
            out.append("\r\n");
        }
        return out.toString();
    }

    private static String signalHeader(
            SignalCatalogEntry entry,
            boolean includeSourceIdentity,
            boolean includeDbcIdentity
    ) {
        StringBuilder header = new StringBuilder(entry.signalName());
        if (entry.sourceType() == SignalSourceType.DBC_CAN) {
            header.append(" [Ch")
                    .append(entry.channel())
                    .append(" | ")
                    .append(UiFormat.canId(entry.canId()))
                    .append(']');
        }
        if (includeDbcIdentity && entry.sourceDatabase() != null && !entry.sourceDatabase().isBlank()) {
            header.append(" [DBC: ").append(entry.sourceDatabase().trim()).append(']');
        }
        if (includeSourceIdentity && entry.sourceFile() != null && !entry.sourceFile().isBlank()) {
            header.append(" [Source: ").append(entry.sourceFile().trim()).append(']');
        }
        if (entry.unit() != null && !entry.unit().isBlank()) {
            header.append(" (").append(entry.unit().trim()).append(')');
        }
        return header.toString();
    }

    private static String formatDeltaMillis(long deltaNanos) {
        boolean negative = deltaNanos < 0;
        long abs = negative ? -deltaNanos : deltaNanos;
        long wholeMs = abs / 1_000_000L;
        long remNanos = abs % 1_000_000L;
        if (remNanos == 0L) {
            return (negative ? "-" : "") + wholeMs;
        }
        String fraction = String.format(java.util.Locale.ROOT, "%06d", remNanos);
        int end = fraction.length();
        while (end > 0 && fraction.charAt(end - 1) == '0') {
            end--;
        }
        return (negative ? "-" : "") + wholeMs + "." + fraction.substring(0, end);
    }

    private static String formatValue(
            AlignedSampleRow row,
            int signalIndex,
            WorkspaceDisplayMode mode,
            WorkspacePrecision precision
    ) {
        if (mode == WorkspaceDisplayMode.RAW) {
            return SampleDisplay.raw(row.rawAt(signalIndex), row.textualAt(signalIndex), precision);
        }
        return SampleDisplay.physical(
                row.physicalAt(signalIndex),
                row.rawAt(signalIndex),
                row.textualAt(signalIndex),
                row.validityAt(signalIndex),
                precision);
    }

    private static void appendCell(StringBuilder out, String value, boolean first) {
        if (!first) {
            out.append('\t');
        }
        out.append(sanitize(value));
    }

    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static int estimateCapacity(int rows, int signals) {
        long estimate = 128L + (long) Math.max(0, rows) * Math.max(1, signals + 3) * 12L;
        return (int) Math.min(Integer.MAX_VALUE - 8L, Math.max(256L, estimate));
    }
}
