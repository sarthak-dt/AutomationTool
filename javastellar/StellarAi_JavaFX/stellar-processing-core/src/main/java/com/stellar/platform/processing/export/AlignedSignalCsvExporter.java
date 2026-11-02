package com.stellar.platform.processing.export;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.SignalSeriesStore;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streams an aligned workspace table to UTF-8 CSV.
 *
 * <p>Rows are the timestamp union of the requested signals. A missing sample stays blank.
 * Values are not interpolated and a missing sample is not written as zero. The read page
 * size is internal and is not the Active Signal Workspace page size.</p>
 */
public final class AlignedSignalCsvExporter {

    /** Store read size. Independent of the rows-per-page control. */
    static final int READ_PAGE = 4_096;

    public enum ValueMode {
        PHYSICAL,
        RAW
    }

    public record ExportResult(long rowCount, boolean cancelled, Path path) {
    }

    public ExportResult export(
            SignalSeriesStore store,
            List<SignalCatalogEntry> columns,
            ValueMode mode,
            Path destination,
            AtomicBoolean cancel
    ) throws IOException {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(destination, "destination");
        List<SignalCatalogEntry> selected = columns == null ? List.of() : List.copyOf(columns);
        if (selected.isEmpty()) {
            throw new IllegalArgumentException("No signals to export");
        }
        ValueMode valueMode = mode == null ? ValueMode.PHYSICAL : mode;
        AtomicBoolean stop = cancel == null ? new AtomicBoolean() : cancel;
        Path parent = destination.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        List<String> headers = columnHeaders(selected);
        List<Cursor> cursors = new ArrayList<>(selected.size());
        for (SignalCatalogEntry column : selected) {
            cursors.add(new Cursor(store, column.key(), stop));
        }
        long rows = 0L;
        boolean cancelled = false;
        try (BufferedWriter writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) {
            writer.write("index,timestamp_ns");
            for (String header : headers) {
                writer.write(',');
                writer.write(csv(header));
            }
            writer.write("\r\n");
            while (!stop.get() && !Thread.currentThread().isInterrupted()) {
                long timestamp = Long.MAX_VALUE;
                boolean any = false;
                for (Cursor cursor : cursors) {
                    SignalSamplePoint current = cursor.current();
                    if (current != null && current.timestampNanos() < timestamp) {
                        timestamp = current.timestampNanos();
                        any = true;
                    }
                }
                if (!any) {
                    break;
                }
                writer.write(Long.toString(rows));
                writer.write(',');
                writer.write(Long.toString(timestamp));
                for (Cursor cursor : cursors) {
                    writer.write(',');
                    writer.write(cell(cursor.takeAt(timestamp), valueMode));
                }
                writer.write("\r\n");
                rows++;
            }
            cancelled = stop.get() || Thread.currentThread().isInterrupted();
        } catch (IOException ex) {
            Files.deleteIfExists(destination);
            throw ex;
        }
        if (cancelled) {
            Files.deleteIfExists(destination);
            return new ExportResult(rows, true, destination);
        }
        return new ExportResult(rows, false, destination);
    }

    /**
     * Column titles keep signal name, unit, and enough source identity that two signals
     * with the same name do not share a header.
     */
    public static List<String> columnHeaders(List<SignalCatalogEntry> columns) {
        List<SignalCatalogEntry> entries = columns == null ? List.of() : columns;
        List<String> headers = new ArrayList<>(entries.size());
        Set<String> used = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            SignalCatalogEntry entry = entries.get(i);
            String header = baseHeader(entry);
            if (!used.add(header)) {
                header = qualifiedHeader(entry);
                if (!used.add(header)) {
                    header = qualifiedHeader(entry) + " #" + (i + 1);
                    used.add(header);
                }
            }
            headers.add(header);
        }
        return List.copyOf(headers);
    }

    private static String baseHeader(SignalCatalogEntry entry) {
        StringBuilder header = new StringBuilder(entry.signalName());
        appendIdentity(header, entry);
        appendUnit(header, entry);
        return header.toString();
    }

    private static String qualifiedHeader(SignalCatalogEntry entry) {
        SignalCatalogKey key = entry.key();
        StringBuilder header = new StringBuilder(entry.signalName());
        header.append(" [")
                .append(key.sourceType().name())
                .append(" ch").append(key.channel())
                .append(" 0x").append(Long.toHexString(key.canId()))
                .append(" ").append(key.messageName());
        if (!key.sourceFile().isBlank()) {
            header.append(' ').append(key.sourceFile().trim());
        }
        if (!key.sourceDatabase().isBlank()) {
            header.append(' ').append(key.sourceDatabase().trim());
        }
        header.append(']');
        appendUnit(header, entry);
        return header.toString();
    }

    private static void appendIdentity(StringBuilder header, SignalCatalogEntry entry) {
        SignalCatalogKey key = entry.key();
        if (key.sourceType() == SignalSourceType.DBC_CAN) {
            header.append(" [Ch").append(key.channel())
                    .append(" | 0x").append(Long.toHexString(key.canId()))
                    .append(']');
        } else if (key.channel() > 0) {
            header.append(" [Ch").append(key.channel()).append(']');
        }
        String source = !key.sourceFile().isBlank() ? key.sourceFile() : entry.sourceFile();
        if (source != null && !source.isBlank()) {
            header.append(" [").append(source.trim()).append(']');
        }
        String database = !key.sourceDatabase().isBlank() ? key.sourceDatabase() : entry.sourceDatabase();
        if (database != null && !database.isBlank()) {
            header.append(" [").append(database.trim()).append(']');
        }
    }

    private static void appendUnit(StringBuilder header, SignalCatalogEntry entry) {
        if (entry.unit() != null && !entry.unit().isBlank()) {
            header.append(" (").append(entry.unit().trim()).append(')');
        }
    }

    private static String cell(SignalSamplePoint point, ValueMode mode) {
        if (point == null) {
            return "";
        }
        if (mode == ValueMode.RAW) {
            if (point.rawValue() != null) {
                return Long.toString(point.rawValue());
            }
            if (point.textualValue() != null && !point.textualValue().isBlank()) {
                return csv(point.textualValue().trim());
            }
            return "";
        }
        if (point.textualValue() != null && !point.textualValue().isBlank()
                && !Double.isFinite(point.physicalValue())) {
            return csv(point.textualValue().trim());
        }
        if (!Double.isFinite(point.physicalValue())) {
            return "";
        }
        return Double.toString(point.physicalValue());
    }

    static String csv(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        boolean quote = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                quote = true;
                break;
            }
        }
        if (!quote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static final class Cursor {
        private final SignalSeriesStore store;
        private final SignalCatalogKey key;
        private final int retained;
        private final AtomicBoolean stop;
        private List<SignalSamplePoint> page = List.of();
        private int pageIndex;
        private int nextOffset;
        private SignalSamplePoint current;

        private Cursor(SignalSeriesStore store, SignalCatalogKey key, AtomicBoolean stop) {
            this.store = store;
            this.key = key;
            this.stop = stop;
            this.retained = Math.max(0, store.retainedSampleCount(key));
            pull();
        }

        private SignalSamplePoint current() {
            return current;
        }

        private SignalSamplePoint takeAt(long timestampNanos) {
            if (current == null || current.timestampNanos() != timestampNanos) {
                return null;
            }
            SignalSamplePoint last = current;
            advance();
            while (current != null && current.timestampNanos() == timestampNanos) {
                last = current;
                advance();
            }
            return last;
        }

        private void advance() {
            pageIndex++;
            pull();
        }

        private void pull() {
            while (pageIndex >= page.size()) {
                if (stop.get() || (retained > 0 && nextOffset >= retained)) {
                    current = null;
                    page = List.of();
                    return;
                }
                List<SignalSamplePoint> next = store.querySamples(key, nextOffset, READ_PAGE);
                if (next == null || next.isEmpty()) {
                    current = null;
                    page = List.of();
                    return;
                }
                int advanced = nextOffset + next.size();
                if (advanced <= nextOffset) {
                    current = null;
                    page = List.of();
                    return;
                }
                page = next;
                pageIndex = 0;
                nextOffset = advanced;
            }
            current = page.get(pageIndex);
        }
    }
}
