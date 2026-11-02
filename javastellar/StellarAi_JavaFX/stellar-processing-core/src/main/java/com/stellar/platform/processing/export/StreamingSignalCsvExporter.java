package com.stellar.platform.processing.export;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.SignalSeriesStore;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streaming CSV export. Pages the store; never materializes a full series.
 */
public final class StreamingSignalCsvExporter {

    public static final int PAGE = 1_000;

    public long export(SignalSeriesStore store, List<SignalCatalogKey> keys, Path destination, AtomicBoolean cancel)
            throws IOException {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(destination, "destination");
        List<SignalCatalogKey> selected = keys == null ? List.of() : List.copyOf(keys);
        AtomicBoolean stop = cancel == null ? new AtomicBoolean() : cancel;
        Files.createDirectories(destination.getParent() == null ? Path.of(".") : destination.getParent());
        long bytes;
        try (BufferedWriter writer = Files.newBufferedWriter(destination, StandardCharsets.UTF_8)) {
            writer.write("signal,timestamp_nanos,physical,raw,validity");
            writer.newLine();
            for (SignalCatalogKey key : selected) {
                if (stop.get()) {
                    break;
                }
                int retained = store.retainedSampleCount(key);
                for (int offset = 0; offset < retained && !stop.get(); offset += PAGE) {
                    List<SignalSamplePoint> page = store.querySamples(key, offset, PAGE);
                    for (SignalSamplePoint point : page) {
                        writer.write(escape(key.signalName()));
                        writer.write(',');
                        writer.write(Long.toString(point.timestampNanos()));
                        writer.write(',');
                        writer.write(Double.toString(point.physicalValue()));
                        writer.write(',');
                        writer.write(point.rawValue() == null ? "" : Long.toString(point.rawValue()));
                        writer.write(',');
                        writer.write(point.validity().name());
                        writer.newLine();
                    }
                }
            }
        }
        bytes = Files.size(destination);
        return bytes;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
