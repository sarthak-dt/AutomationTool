package com.stellar.platform.processing;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.export.StreamingSignalCsvExporter;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validity and non-finite values stay attached to the original numbers.
 * Expected physical values are the arithmetic results of the inputs, not a second decoder.
 */
class SignalDataIntegrityTest {

    @TempDir
    Path temp;

    @Test
    void catalogKeepsEveryStatusAndIgnoresNonFiniteExtrema() {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("a.mf4", "one.dbc", 32);
        store.accept(List.of(
                signal("Speed", 1, Double.NaN, 1L, SignalValidity.NON_FINITE_VALUE),
                signal("Speed", 2, 655.35, 65535L, SignalValidity.INVALID_RAW_CODE),
                signal("Speed", 3, 10.0, 1000L, SignalValidity.VALID),
                signal("Speed", 4, Double.POSITIVE_INFINITY, 2L, SignalValidity.NON_FINITE_VALUE),
                signal("Speed", 5, 42.5, 4250L, SignalValidity.VALID),
                signal("Speed", 6, 80.0, 80L, SignalValidity.INACTIVE_RAW_CODE),
                signal("Speed", 7, Double.NaN, 7L, SignalValidity.CONVERSION_FAILED),
                signal("Speed", 8, 1.0e6, 1L, SignalValidity.VALID)
        ));
        SignalCatalogKey key = store.snapshot().entries().getFirst().key();
        SignalCatalogEntry entry = store.snapshot().entries().getFirst();
        assertEquals(10.0, entry.minPhysical(), 0.0);
        assertEquals(1.0e6, entry.maxPhysical(), 0.0);
        List<SignalSamplePoint> page = store.querySamples(key, 0, 20);
        assertEquals(8, page.size());
        assertEquals(SignalValidity.NON_FINITE_VALUE, page.get(0).validity());
        assertEquals(655.35, page.get(1).physicalValue(), 0.0);
        assertEquals(65535L, page.get(1).rawValue());
        assertEquals(SignalValidity.INVALID_RAW_CODE, page.get(1).validity());
        assertEquals(SignalValidity.INACTIVE_RAW_CODE, page.get(5).validity());
        assertEquals(80.0, page.get(5).physicalValue(), 0.0);
        assertEquals(SignalValidity.CONVERSION_FAILED, page.get(6).validity());
        assertTrue(Double.isNaN(page.get(6).physicalValue()));
        assertEquals(7L, page.get(6).rawValue());
    }

    @Test
    void allNonFiniteSeriesDoesNotInventZeroExtrema() {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("a.mf4", "one.dbc", 8);
        store.accept(List.of(signal("Speed", 1, Double.NaN, null, SignalValidity.NON_FINITE_VALUE)));
        SignalCatalogEntry entry = store.snapshot().entries().getFirst();
        assertNull(entry.minPhysical());
        assertNull(entry.maxPhysical());
        assertEquals(1, entry.sampleCount());
    }

    @Test
    void reopenPreservesValidityForTwoMeasurementsAndTwoDefinitions() throws Exception {
        Path firstRoot = temp.resolve("file-a");
        Path secondRoot = temp.resolve("file-b");
        writeStore(firstRoot, "trip-a.mf4", "alpha.dbc", List.of(
                signal("Speed", 397_000_000L, 90.0, 9000L, SignalValidity.VALID),
                signal("Speed", 398_000_000L, 655.35, 65535L, SignalValidity.INVALID_RAW_CODE)));
        writeStore(secondRoot, "trip-b.blf", "beta.dbc", List.of(
                signal("Latitude", 742_000_000L, 13.331515, 133_315_150L, SignalValidity.VALID),
                signal("Latitude", 743_000_000L, Double.NaN, 7L, SignalValidity.CONVERSION_FAILED)));

        assertEquals(LosslessGraphSignalStore.INDEX_LAYOUT_VALIDITY,
                LosslessGraphSignalStore.indexLayoutVersion(firstRoot));
        try (LosslessGraphSignalStore read = LosslessGraphSignalStore.openExisting(firstRoot)) {
            assertEquals("trip-a.mf4", read.measurementSource());
            assertEquals("alpha.dbc", read.definitionSource());
            List<SignalSamplePoint> page = read.querySamples(read.keys().getFirst(), 0, 10);
            assertEquals(90.0, page.get(0).physicalValue(), 0.0);
            assertEquals(SignalValidity.VALID, page.get(0).validity());
            assertEquals(655.35, page.get(1).physicalValue(), 0.0);
            assertEquals(65535L, page.get(1).rawValue());
            assertEquals(SignalValidity.INVALID_RAW_CODE, page.get(1).validity());
            assertEquals(90.0, read.catalogEntries().getFirst().minPhysical(), 0.0);
            assertEquals(90.0, read.catalogEntries().getFirst().maxPhysical(), 0.0);
            var buckets = read.queryBuckets(read.keys().getFirst(), 0L, 1_000_000_000L, 8);
            assertFalse(buckets.isEmpty());
            assertEquals(90.0, buckets.stream().filter(b -> b.minUsable()).mapToDouble(b -> b.minValue()).min().orElseThrow(), 0.0);
            assertEquals(90.0, buckets.stream().filter(b -> b.maxUsable()).mapToDouble(b -> b.maxValue()).max().orElseThrow(), 0.0);
            assertTrue(buckets.stream().noneMatch(b -> b.minUsable() && b.minValue() == 655.35));
        }
        try (LosslessGraphSignalStore read = LosslessGraphSignalStore.openExisting(secondRoot)) {
            assertEquals("trip-b.blf", read.measurementSource());
            assertEquals("beta.dbc", read.definitionSource());
            List<SignalSamplePoint> page = read.querySamples(read.keys().getFirst(), 0, 10);
            assertEquals(13.331515, page.get(0).physicalValue(), 1e-12);
            assertEquals(SignalValidity.CONVERSION_FAILED, page.get(1).validity());
            assertTrue(Double.isNaN(page.get(1).physicalValue()));
            assertEquals(7L, page.get(1).rawValue());
            assertEquals(13.331515, read.catalogEntries().getFirst().minPhysical(), 1e-12);
        }
    }

    @Test
    void legacySampleBytesStayAlignedAndAreNotMarkedValid() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        writeLegacy(out, 10L, 42.5, 425L, "km/h");
        writeLegacy(out, 20L, Double.NaN, 1L, null);
        out.flush();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw.toByteArray()));
        SignalSamplePoint first = LosslessGraphSignalStore.readRecord(
                in, LosslessGraphSignalStore.INDEX_LAYOUT_LEGACY);
        SignalSamplePoint second = LosslessGraphSignalStore.readRecord(
                in, LosslessGraphSignalStore.INDEX_LAYOUT_LEGACY);
        assertEquals(10L, first.timestampNanos());
        assertEquals(42.5, first.physicalValue(), 0.0);
        assertEquals(425L, first.rawValue());
        assertEquals("km/h", first.textualValue());
        assertEquals(SignalValidity.UNRECORDED, first.validity());
        assertFalse(first.usableMeasurement());
        assertEquals(20L, second.timestampNanos());
        assertTrue(Double.isNaN(second.physicalValue()));
        assertEquals(SignalValidity.UNRECORDED, second.validity());
        assertEquals(-1, in.read());
    }

    @Test
    void csvKeepsNumbersAndWritesValidityWithoutDroppingRows() throws Exception {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("a.mf4", "one.dbc", 8);
        store.accept(List.of(
                signal("Speed", 1, 10.0, 1000L, SignalValidity.VALID),
                signal("Speed", 2, 655.35, 65535L, SignalValidity.INVALID_RAW_CODE),
                signal("Speed", 3, Double.NaN, 7L, SignalValidity.CONVERSION_FAILED)));
        Path csv = temp.resolve("signals.csv");
        new StreamingSignalCsvExporter().export(
                store, List.of(store.snapshot().entries().getFirst().key()), csv, null);
        String text = Files.readString(csv);
        assertTrue(text.startsWith("signal,timestamp_nanos,physical,raw,validity"));
        assertTrue(text.contains("10.0,1000,VALID"));
        assertTrue(text.contains("655.35,65535,INVALID_RAW_CODE"));
        assertTrue(text.contains("NaN,7,CONVERSION_FAILED"));
        assertFalse(text.contains(",0,VALID"));
        assertEquals(4, text.lines().count());
    }

    private static void writeStore(Path root, String measurement, String definition, List<CanonicalSignal> batch)
            throws Exception {
        try (LosslessGraphSignalStore store = new LosslessGraphSignalStore(root, measurement, definition)) {
            store.accept(batch);
            store.persistIndex();
        }
    }

    private static void writeLegacy(DataOutputStream out, long ts, double physical, long raw, String text)
            throws Exception {
        byte[] bytes = text == null ? null : text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeLong(ts);
        out.writeDouble(physical);
        out.writeBoolean(true);
        out.writeLong(raw);
        out.writeInt(bytes == null ? -1 : bytes.length);
        if (bytes != null) {
            out.write(bytes);
        }
    }

    private static CanonicalSignal signal(
            String name, long timestamp, double physical, Long raw, SignalValidity validity) {
        return new CanonicalSignal(
                name, "Vehicle", timestamp, 0x100L, 0, physical, raw, "km/h", null,
                SourceReference.ofLine(1), validity);
    }
}
