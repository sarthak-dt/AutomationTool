package com.stellar.platform.testkit.export;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Deterministic StellarAI signal-series CSV export for offline parity comparison.
 * <p>
 * Streams samples via bounded {@link InMemorySignalCatalogStore#querySamples} pages —
 * never materialises the full series in one list. Not a production UI feature.
 */
public final class SignalSeriesCsvExporter {

    public static final String HEADER = String.join(",",
            "signal_name",
            "global_index",
            "timestamp_nanos",
            "timestamp_seconds",
            "raw_value",
            "physical_value",
            "text_value",
            "unit",
            "source",
            "message",
            "can_id",
            "source_file",
            "source_database"
    );

    private final int pageSize;

    public SignalSeriesCsvExporter() {
        this(InMemorySignalCatalogStore.DEFAULT_QUERY_PAGE_LIMIT);
    }

    public SignalSeriesCsvExporter(int pageSize) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be >= 1");
        }
        this.pageSize = pageSize;
    }

    public long export(
            InMemorySignalCatalogStore store,
            SignalCatalogEntry entry,
            Path outputFile
    ) throws IOException {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(outputFile, "outputFile");
        Path parent = outputFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            return export(store, entry, writer);
        }
    }

    public long export(
            InMemorySignalCatalogStore store,
            SignalCatalogEntry entry,
            Writer writer
    ) throws IOException {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(writer, "writer");

        SignalCatalogKey key = entry.key();
        long windowStart = store.retainedWindowStartIndex(key);
        int retained = store.retainedSampleCount(key);
        writer.write(HEADER);
        writer.write('\n');

        long written = 0L;
        int offset = 0;
        while (offset < retained) {
            List<SignalSamplePoint> page = store.querySamples(key, offset, pageSize);
            if (page.isEmpty()) {
                break;
            }
            for (int i = 0; i < page.size(); i++) {
                SignalSamplePoint p = page.get(i);
                long globalIndex = windowStart + offset + i;
                writeRow(writer, entry, globalIndex, p);
                written++;
            }
            offset += page.size();
            if (page.size() < pageSize) {
                break;
            }
        }
        writer.flush();
        return written;
    }

    private static void writeRow(
            Writer writer,
            SignalCatalogEntry entry,
            long globalIndex,
            SignalSamplePoint point
    ) throws IOException {
        writer.write(csv(entry.signalName()));
        writer.write(',');
        writer.write(Long.toString(globalIndex));
        writer.write(',');
        writer.write(Long.toString(point.timestampNanos()));
        writer.write(',');
        writer.write(secondsExact(point.timestampNanos()));
        writer.write(',');
        writer.write(point.rawValue() == null ? "" : Long.toString(point.rawValue()));
        writer.write(',');
        writer.write(Double.toString(point.physicalValue()));
        writer.write(',');
        writer.write(csv(point.textualValue() == null ? "" : point.textualValue()));
        writer.write(',');
        writer.write(csv(entry.unit()));
        writer.write(',');
        writer.write(csv(entry.sourceType() == null ? "" : entry.sourceType().name()));
        writer.write(',');
        writer.write(csv(entry.messageName()));
        writer.write(',');
        if (entry.sourceType() == SignalSourceType.MF4_NATIVE) {
            writer.write("");
        } else {
            writer.write("0x" + Long.toHexString(entry.canId()).toUpperCase());
        }
        writer.write(',');
        writer.write(csv(entry.sourceFile()));
        writer.write(',');
        writer.write(csv(entry.sourceDatabase()));
        writer.write('\n');
    }

    /** Integer-arithmetic seconds string for CSV (no double noise). */
    public static String secondsExact(long nanos) {
        boolean negative = nanos < 0;
        long abs = negative ? -nanos : nanos;
        long whole = abs / 1_000_000_000L;
        long frac = abs % 1_000_000_000L;
        StringBuilder sb = new StringBuilder();
        if (negative) {
            sb.append('-');
        }
        sb.append(whole);
        if (frac != 0L) {
            sb.append('.');
            String frac9 = String.format("%09d", frac);
            int end = frac9.length();
            while (end > 0 && frac9.charAt(end - 1) == '0') {
                end--;
            }
            sb.append(frac9, 0, end);
        }
        return sb.toString();
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        if (!quote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
