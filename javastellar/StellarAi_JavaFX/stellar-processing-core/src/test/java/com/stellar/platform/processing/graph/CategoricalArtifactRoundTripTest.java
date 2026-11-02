package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.CategoricalValue;
import com.stellar.platform.domain.EnumeratedState;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoricalArtifactRoundTripTest {

    @TempDir Path temp;

    @Test
    void savedLabelReopensWithoutBecomingANumber() throws Exception {
        Path root = temp.resolve("store");
        CanonicalSignal fan = categorical("ETC_Fan2_output", 5_998_194L, 0L, "FALSE");
        CanonicalSignal speed = numeric("IN_Vehicle_speed", 99_013_388L, 9340L, 72.96875, "km/h");
        try (LosslessGraphSignalStore write = new LosslessGraphSignalStore(root, "xenon.dat", "", 8)) {
            write.accept(List.of(fan, speed));
            write.persistIndex();
        }
        try (LosslessGraphSignalStore read = LosslessGraphSignalStore.openExisting(root)) {
            SignalSamplePoint reopened = sample(read, "ETC_Fan2_output");
            assertEquals(SignalValidity.VALID, reopened.validity());
            assertEquals(0, reopened.validity().storedCode());
            assertEquals(0L, reopened.rawValue());
            assertEquals("FALSE", reopened.textualValue());
            assertTrue(Double.isNaN(reopened.physicalValue()));
            assertFalse(reopened.usableMeasurement());
            assertEquals("FALSE", CategoricalValue.label(
                    reopened.validity(), reopened.physicalValue(), reopened.textualValue()));

            SignalSamplePoint kept = sample(read, "IN_Vehicle_speed");
            assertEquals(72.96875, kept.physicalValue(), 0.0);
            assertEquals(9340L, kept.rawValue());
            assertTrue(kept.usableMeasurement());

            SignalCatalogEntry fanEntry = entry(read, "ETC_Fan2_output");
            assertTrue(fanEntry.minPhysicalOptional().isEmpty());
            assertTrue(fanEntry.maxPhysicalOptional().isEmpty());
            assertEquals(72.96875, entry(read, "IN_Vehicle_speed").minPhysical(), 0.0);

            GraphBucket bucket = read.queryBuckets(fanEntry.key(), 0L, Long.MAX_VALUE, 4).getFirst();
            assertEquals("FALSE", bucket.categoricalLabel());
            assertFalse(bucket.minUsable());
            assertFalse(bucket.firstUsable());
        }
    }

    @Test
    void finiteValueTableLabelReopensWithoutChangingTheNumbers() throws Exception {
        Path root = temp.resolve("hvbat");
        CanonicalSignal sleep = new CanonicalSignal(
                "HVBAT_STATE", "Battery", 10_000_000L, 256L, 1, 0.0, 0L, "", "Sleep",
                SourceReference.ofLine(1), SignalValidity.VALID);
        CanonicalSignal ready = new CanonicalSignal(
                "HVBAT_STATE", "Battery", 20_000_000L, 256L, 1, 3.0, 3L, "", "Ready",
                SourceReference.ofLine(2), SignalValidity.VALID);
        try (LosslessGraphSignalStore write = new LosslessGraphSignalStore(root, "pack.mf4", "hvbat.dbc", 8)) {
            write.accept(List.of(sleep, ready));
            write.persistIndex();
        }
        try (LosslessGraphSignalStore read = LosslessGraphSignalStore.openExisting(root)) {
            SignalSamplePoint reopened = sample(read, "HVBAT_STATE");
            assertEquals(0.0, reopened.physicalValue(), 0.0);
            assertEquals(0L, reopened.rawValue());
            assertEquals("Sleep", reopened.textualValue());
            assertEquals(10_000_000L, reopened.timestampNanos());
            assertEquals(SignalValidity.VALID, reopened.validity());
            assertTrue(reopened.usableMeasurement());
            assertNull(CategoricalValue.label(reopened.validity(), reopened.physicalValue(), reopened.textualValue()));
            assertEquals("Sleep", EnumeratedState.text(reopened.validity(), reopened.physicalValue(), reopened.textualValue()));
            GraphBucket bucket = MinMaxLodReducer.reduce(read.querySamples(entry(read, "HVBAT_STATE").key(), 0, 10), 10)
                    .getFirst();
            assertEquals("Sleep", bucket.firstLabel());
            assertEquals(0.0, bucket.firstValue(), 0.0);
        }
    }

    @Test
    void reducerKeepsTheLabelOffTheNumericRange() {
        SignalSamplePoint fan = new SignalSamplePoint(5_998_194L, Double.NaN, 0L, "FALSE", SignalValidity.VALID);
        SignalSamplePoint speed = new SignalSamplePoint(99_013_388L, 72.96875, 9340L, null, SignalValidity.VALID);
        GraphBucket categorical = MinMaxLodReducer.fromCorners(fan, fan, null, null, 1);
        assertEquals("FALSE", categorical.categoricalLabel());
        assertFalse(categorical.minUsable());
        GraphBucket numeric = MinMaxLodReducer.fromCorners(speed, speed, speed, speed, 1);
        assertEquals(null, numeric.categoricalLabel());
        assertTrue(numeric.minUsable());
        assertEquals(72.96875, numeric.minValue(), 0.0);
    }

    @Test
    void layout1LabelAndLayout2CodeStayCompatible() throws Exception {
        SignalSamplePoint legacy = readSample(LosslessGraphSignalStore.INDEX_LAYOUT_LEGACY, false);
        assertEquals(SignalValidity.UNRECORDED, legacy.validity());
        assertEquals("FALSE", CategoricalValue.label(legacy.validity(), legacy.physicalValue(), legacy.textualValue()));

        SignalSamplePoint current = readSample(LosslessGraphSignalStore.INDEX_LAYOUT_VALIDITY, true);
        assertEquals(SignalValidity.VALID, current.validity());
        assertEquals(0, current.validity().storedCode());
        assertEquals("TRUE", current.textualValue());
    }

    private static SignalSamplePoint readSample(int layout, boolean validityByte) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeLong(5_998_194L);
        out.writeDouble(Double.NaN);
        out.writeBoolean(true);
        out.writeLong(0L);
        byte[] text = (validityByte ? "TRUE" : "FALSE").getBytes(StandardCharsets.UTF_8);
        out.writeInt(text.length);
        out.write(text);
        if (validityByte) {
            out.writeByte(SignalValidity.VALID.storedCode());
        }
        return LosslessGraphSignalStore.readRecord(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), layout);
    }

    private static SignalSamplePoint sample(LosslessGraphSignalStore store, String name) {
        return store.querySamples(entry(store, name).key(), 0, 4).getFirst();
    }

    private static SignalCatalogEntry entry(LosslessGraphSignalStore store, String name) {
        return store.catalogEntries().stream()
                .filter(entry -> name.equals(entry.signalName()))
                .findFirst()
                .orElseThrow();
    }

    private static CanonicalSignal categorical(String name, long ts, long raw, String label) {
        return new CanonicalSignal(name, "DIRECT_MEASUREMENT", ts, 0L, 1, Double.NaN, raw, "", label,
                SourceReference.ofLine(1), SignalValidity.VALID);
    }

    private static CanonicalSignal numeric(String name, long ts, long raw, double physical, String unit) {
        return new CanonicalSignal(name, "DIRECT_MEASUREMENT", ts, 0L, 1, physical, raw, unit, null,
                SourceReference.ofLine(1));
    }
}
