package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSourceType;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Capability-driven Inspector rows from {@link SignalCatalogEntry} only.
 * Never invents DLC, cycle time, data type, or quality when absent from the model.
 */
public final class SignalInspectorPresentation {

    public record Row(String label, String value, boolean copyable, String exactValue) {
        public Row(String label, String value) {
            this(label, value, false, value);
        }

        public Row(String label, String value, boolean copyable) {
            this(label, value, copyable, value);
        }

        public String exactValue() {
            return exactValue == null || exactValue.isBlank() ? value : exactValue;
        }
    }

    public record Section(String title, List<Row> rows) {
        public Section {
            Objects.requireNonNull(title, "title");
            rows = List.copyOf(rows);
        }

        public boolean isEmpty() {
            return rows.isEmpty();
        }
    }

    private SignalInspectorPresentation() {
    }

    public static List<Section> sections(SignalCatalogEntry entry) {
        return sections(entry, List.of(entry));
    }

    /**
     * Inspector rows from catalog metadata. {@code catalog} is used only for measurement
     * coverage; this method never queries waveform samples.
     */
    public static List<Section> sections(SignalCatalogEntry entry, List<SignalCatalogEntry> catalog) {
        Objects.requireNonNull(entry, "entry");
        List<Section> out = new ArrayList<>(5);
        Section signal = signalSection(entry);
        if (!signal.isEmpty()) {
            out.add(signal);
        }
        Section can = canSection(entry);
        if (!can.isEmpty()) {
            out.add(can);
        }
        Section coverage = measurementCoverageSection(entry, catalog);
        if (!coverage.isEmpty()) {
            out.add(coverage);
        }
        Section dataset = datasetSection(entry);
        if (!dataset.isEmpty()) {
            out.add(dataset);
        }
        Section statistics = statisticsSection(entry);
        if (!statistics.isEmpty()) {
            out.add(statistics);
        }
        Section technical = technicalSection(entry);
        if (!technical.isEmpty()) {
            out.add(technical);
        }
        return List.copyOf(out);
    }

    public static boolean showsCanMessageSection(SignalCatalogEntry entry) {
        return entry != null && entry.sourceType() == SignalSourceType.DBC_CAN;
    }

    private static Section signalSection(SignalCatalogEntry entry) {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Name", entry.signalName(), true));
        if (entry.unit() != null && !entry.unit().isBlank()) {
            rows.add(new Row("Unit", entry.unit()));
        }
        return new Section("Signal Information", rows);
    }

    private static Section canSection(SignalCatalogEntry entry) {
        if (!showsCanMessageSection(entry)) {
            return new Section("DBC Information", List.of());
        }
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Channel", SignalChannelLabels.full(entry)));
        if (entry.messageName() != null && !entry.messageName().isBlank()) {
            rows.add(new Row("Message", entry.messageName()));
        }
        rows.add(new Row("CAN ID", formatCanId(entry.canId()), true));
        if (entry.sourceDatabase() != null && !entry.sourceDatabase().isBlank()) {
            rows.add(new Row("DBC File", entry.sourceDatabase(), true));
        }
        return new Section("DBC Information", rows);
    }

    private static Section datasetSection(SignalCatalogEntry entry) {
        List<Row> rows = new ArrayList<>();
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        rows.add(new Row("Samples", nf.format(entry.sampleCount())));
        entry.firstTimestampNanosOptional().ifPresent(ts ->
                rows.add(new Row("Start", formatNanos(ts) + " s")));
        entry.lastTimestampNanosOptional().ifPresent(ts ->
                rows.add(new Row("End", formatNanos(ts) + " s")));
        if (entry.firstTimestampNanos() != null && entry.lastTimestampNanos() != null
                && entry.lastTimestampNanos() >= entry.firstTimestampNanos()) {
            long dur = entry.lastTimestampNanos() - entry.firstTimestampNanos();
            rows.add(new Row("Duration", formatDuration(dur)));
        }
        rows.add(new Row("Type", SignalBrowserFilter.sourceBadge(entry.sourceType())));
        return new Section("Dataset", rows);
    }

    static Section measurementCoverageSection(SignalCatalogEntry entry, List<SignalCatalogEntry> catalog) {
        List<MeasurementCatalogScope.CoverageRow> coverage =
                MeasurementCatalogScope.coverage(entry, catalog);
        int measurements = Math.max(1, MeasurementCatalogScope.measurementCount(catalog));
        if (coverage.size() <= 1) {
            MeasurementCatalogScope.CoverageRow row = coverage.isEmpty()
                    ? new MeasurementCatalogScope.CoverageRow(
                    MeasurementCatalogScope.identityOf(entry),
                    MeasurementCatalogScope.displayOf(entry),
                    entry.channel(),
                    entry.messageName(),
                    entry.canId(),
                    entry.sampleCount(),
                    entry.sourceType())
                    : coverage.getFirst();
            String name = row.displayName().isBlank() ? "—" : row.displayName();
            return new Section("Measurement", List.of(new Row("Measurement", name, true)));
        }
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(
                "Present in",
                coverage.size() + " / " + measurements + " measurements"));
        for (MeasurementCatalogScope.CoverageRow row : coverage) {
            rows.add(new Row(row.displayName(), row.detailLabel(), true));
        }
        return new Section("Measurement Coverage", rows);
    }

    private static Section statisticsSection(SignalCatalogEntry entry) {
        List<Row> rows = new ArrayList<>();
        entry.minPhysicalOptional().ifPresent(v ->
                rows.add(new Row("Minimum", formatPhysicalDisplay(v), true, Double.toString(v))));
        entry.maxPhysicalOptional().ifPresent(v ->
                rows.add(new Row("Maximum", formatPhysicalDisplay(v), true, Double.toString(v))));
        if (rows.isEmpty() && entry.sampleCount() > 0) {
            rows.add(new Row("Numeric range", "n/a"));
        }
        return new Section("Statistics", rows);
    }

    private static Section technicalSection(SignalCatalogEntry entry) {
        // Only known metadata — never invent scale/offset/signed/byte-order.
        return new Section("Technical", List.of());
    }

    /** Compact Inspector display; full IEEE string remains in tooltip/copy. */
    public static String formatPhysicalDisplay(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Double.toString(value);
        }
        return String.format(Locale.ROOT, "%.8g", value);
    }

    /** Formats a nanosecond span as mm:ss.mmm when useful, else seconds. */
    public static String formatDuration(long durationNanos) {
        long abs = Math.max(0L, durationNanos);
        long totalMs = abs / 1_000_000L;
        long minutes = totalMs / 60_000L;
        long seconds = (totalMs % 60_000L) / 1000L;
        long millis = totalMs % 1000L;
        if (minutes > 0) {
            return String.format(Locale.ROOT, "%d:%02d.%03d", minutes, seconds, millis);
        }
        return formatNanos(abs) + " s";
    }

    private static String formatCanId(long id) {
        return String.format(Locale.ROOT, "0x%X", id);
    }

    private static String formatNanos(long nanos) {
        boolean negative = nanos < 0;
        long abs = negative ? -nanos : nanos;
        long whole = abs / 1_000_000_000L;
        long frac = abs % 1_000_000_000L;
        if (frac == 0L) {
            return (negative ? "-" : "") + Long.toString(whole);
        }
        String frac9 = String.format(Locale.ROOT, "%09d", frac);
        int end = frac9.length();
        while (end > 1 && frac9.charAt(end - 1) == '0') {
            end--;
        }
        if (end < 6) {
            end = 6;
        }
        return (negative ? "-" : "") + whole + '.' + frac9.substring(0, end);
    }
}
