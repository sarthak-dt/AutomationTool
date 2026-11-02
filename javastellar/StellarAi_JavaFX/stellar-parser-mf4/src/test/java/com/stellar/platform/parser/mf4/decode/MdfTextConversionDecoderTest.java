package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.domain.CanonicalMeasurementSample;
import com.stellar.platform.domain.CategoricalValue;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Synthetic MF4 CC type 7 and 8 text. Numeric channels and CC type 9 stay on their existing paths.
 */
class MdfTextConversionDecoderTest {

    @TempDir
    Path temp;

    @Test
    void valueToTextKeepsRawCodesAndSourceLabels() throws Exception {
        Path file = temp.resolve("cc7.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long timeCc = b.writeCcLinear(0.0, 1.0e-5);
            long textCc = b.writeCc(7, 2, new long[] {b.writeTx("No reset"), b.writeTx("Reset")}, new double[] {0, 1});
            long code = b.writeCn(0L, 0L, b.writeTx("RESET_AFE_TRIP"), 0L, 0L, textCc, 0, 0, 0, 8, 0, 32, 0, 0);
            long time = b.writeCn(code, 0L, b.writeTx("Timestamp"), 0L, 0L, timeCc, 2, 1, 0, 0, 0, 64, 0, 0);
            long cg = b.writeCg(0, time, 1, 3, 0, 12, 0);
            long dt = b.writeDt(records(12, new int[] {0, 1, 2}, new long[] {10_000L, 20_000L, 30_000L}));
            b.patchLink(hd, 0, b.writeDg(0, cg, dt, 0));
        }

        List<CanonicalMeasurementSample> signals = decode(file);
        assertEquals(3, signals.size());
        assertText(signals.get(0), "RESET_AFE_TRIP", 100_000_000L, 0L, "No reset");
        assertText(signals.get(1), "RESET_AFE_TRIP", 200_000_000L, 1L, "Reset");
        assertFailed(signals.get(2), "RESET_AFE_TRIP", 300_000_000L, 2L, "no exact entry");
        assertTrue(signals.stream().allMatch(sample -> sample.channel() == 1));
    }

    @Test
    void valueRangeToTextKeepsNumericSiblingsAndAnotherChannelGroup() throws Exception {
        Path file = temp.resolve("cc8.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long timeCc = b.writeCcLinear(0.0, 1.0e-5);
            long linearCc = b.writeCcLinear(0.0, 1.0);
            long textCc = b.writeCc(
                    8, 3,
                    new long[] {b.writeTx("Off"), b.writeTx("On"), b.writeTx("Fault")},
                    new double[] {0, 0, 1, 3, 10, 10});
            long status = b.writeCn(0L, 0L, b.writeTx("FRPK"), 0L, 0L, textCc, 0, 0, 0, 12, 0, 32, 0, 0);
            long pressure = b.writeCn(status, 0L, b.writeTx("Pressure"), 0L, 0L, linearCc, 0, 0, 0, 8, 0, 32, 0, 0);
            long time = b.writeCn(pressure, 0L, b.writeTx("Timestamp"), 0L, 0L, timeCc, 2, 1, 0, 0, 0, 64, 0, 0);
            long cg = b.writeCg(0, time, 1, 6, 0, 16, 0);
            int[] raw = {0, 1, 3, 2, 4, 10};
            long[] stamps = {10_000L, 20_000L, 30_000L, 40_000L, 50_000L, 60_000L};
            ByteBuffer payload = ByteBuffer.allocate(16 * raw.length).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < raw.length; i++) {
                payload.putLong(stamps[i]);
                payload.putInt(100 + i);
                payload.putInt(raw[i]);
            }
            long dt = b.writeDt(payload.array());

            long otherCc = b.writeCcLinear(-40.0, 1.0);
            long other = b.writeCn(0L, 0L, b.writeTx("Temperature"), 0L, 0L, otherCc, 0, 0, 0, 8, 0, 32, 0, 0);
            long otherTime = b.writeCn(other, 0L, b.writeTx("Timestamp"), 0L, 0L, timeCc, 2, 1, 0, 0, 0, 64, 0, 0);
            long otherCg = b.writeCg(0, otherTime, 1, 1, 0, 12, 0);
            long otherDt = b.writeDt(records(12, new int[] {50}, new long[] {70_000L}));
            long otherDg = b.writeDg(0, otherCg, otherDt, 0);
            b.patchLink(hd, 0, b.writeDg(otherDg, cg, dt, 0));
        }

        List<CanonicalMeasurementSample> signals = decode(file);
        List<CanonicalMeasurementSample> text = signals.stream().filter(s -> s.signalName().equals("FRPK")).toList();
        List<CanonicalMeasurementSample> pressure = signals.stream().filter(s -> s.signalName().equals("Pressure")).toList();
        CanonicalMeasurementSample temperature = signals.stream()
                .filter(s -> s.signalName().equals("Temperature")).findFirst().orElseThrow();
        assertEquals(6, text.size());
        assertEquals(6, pressure.size());
        assertText(text.get(0), "FRPK", 100_000_000L, 0L, "Off");
        assertText(text.get(1), "FRPK", 200_000_000L, 1L, "On");
        assertText(text.get(2), "FRPK", 300_000_000L, 3L, "On");
        assertText(text.get(3), "FRPK", 400_000_000L, 2L, "On");
        assertFailed(text.get(4), "FRPK", 500_000_000L, 4L, "outside");
        assertText(text.get(5), "FRPK", 600_000_000L, 10L, "Fault");
        for (int i = 0; i < pressure.size(); i++) {
            assertEquals(100L + i, pressure.get(i).rawValue());
            assertEquals(100.0 + i, pressure.get(i).physicalValue(), 0.0);
            assertEquals(SignalValidity.VALID, pressure.get(i).validity());
            assertNull(pressure.get(i).textualValue());
            assertEquals(text.get(i).timestampNanos(), pressure.get(i).timestampNanos());
        }
        assertEquals(50L, temperature.rawValue());
        assertEquals(10.0, temperature.physicalValue(), 0.0);
        assertEquals(700_000_000L, temperature.timestampNanos());
        assertEquals(SignalValidity.VALID, temperature.validity());
    }

    @Test
    void missingTextReferenceDoesNotInventALabel() throws Exception {
        List<CanonicalMeasurementSample> signals = singleValueFile(
                "missing-tx.mf4",
                7,
                2,
                new double[] {0, 1},
                new int[] {0, 1},
                "Known",
                null);
        assertText(signals.get(0), "State", 100_000_000L, 0L, "Known");
        assertFailed(signals.get(1), "State", 200_000_000L, 1L, "reference is missing");
    }

    @Test
    void supportedDefaultConversionStaysNumeric() throws Exception {
        Path file = temp.resolve("default-linear.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long timeCc = b.writeCcLinear(0.0, 1.0e-5);
            long textCc = b.writeCc(
                    7, 2,
                    new long[] {b.writeTx("Zero"), b.writeCcLinear(100.0, 2.0)},
                    new double[] {0});
            long code = b.writeCn(0L, 0L, b.writeTx("State"), 0L, 0L, textCc, 0, 0, 0, 8, 0, 32, 0, 0);
            long time = b.writeCn(code, 0L, b.writeTx("Timestamp"), 0L, 0L, timeCc, 2, 1, 0, 0, 0, 64, 0, 0);
            long cg = b.writeCg(0, time, 1, 2, 0, 12, 0);
            long dt = b.writeDt(records(12, new int[] {0, 3}, new long[] {10_000L, 20_000L}));
            b.patchLink(hd, 0, b.writeDg(0, cg, dt, 0));
        }

        List<CanonicalMeasurementSample> signals = decode(file);
        assertText(signals.get(0), "State", 100_000_000L, 0L, "Zero");
        CanonicalMeasurementSample scaled = signals.get(1);
        assertEquals(3L, scaled.rawValue());
        assertEquals(106.0, scaled.physicalValue(), 0.0);
        assertNull(scaled.textualValue());
        assertEquals(SignalValidity.VALID, scaled.validity());
        assertFalse(CategoricalValue.isCategorical(scaled.validity(), scaled.physicalValue(), scaled.textualValue()));
    }

    @Test
    void defaultTextLabelIsUsedOnlyWhenNothingMatches() throws Exception {
        List<CanonicalMeasurementSample> signals = singleValueFile(
                "default-text.mf4",
                8,
                2,
                new double[] {1, 1},
                new int[] {1, 0},
                "Loose",
                "Other");
        assertText(signals.get(0), "State", 100_000_000L, 1L, "Loose");
        assertText(signals.get(1), "State", 200_000_000L, 0L, "Other");
    }

    @Test
    void unsupportedDefaultStaysConversionFailed() throws Exception {
        Path file = temp.resolve("default-type9.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long timeCc = b.writeCcLinear(0.0, 1.0e-5);
            long textCc = b.writeCc(
                    7, 2,
                    new long[] {b.writeTx("Zero"), b.writeCc(9, 0, new long[0], new double[0])},
                    new double[] {0});
            long code = b.writeCn(0L, 0L, b.writeTx("State"), 0L, 0L, textCc, 0, 0, 0, 8, 0, 32, 0, 0);
            long time = b.writeCn(code, 0L, b.writeTx("Timestamp"), 0L, 0L, timeCc, 2, 1, 0, 0, 0, 64, 0, 0);
            long cg = b.writeCg(0, time, 1, 2, 0, 12, 0);
            long dt = b.writeDt(records(12, new int[] {0, 4}, new long[] {10_000L, 20_000L}));
            b.patchLink(hd, 0, b.writeDg(0, cg, dt, 0));
        }

        List<CanonicalMeasurementSample> signals = decode(file);
        assertText(signals.get(0), "State", 100_000_000L, 0L, "Zero");
        assertFailed(signals.get(1), "State", 200_000_000L, 4L, "cc_type=9");
    }

    @Test
    void malformedTextMetadataStaysUnapplied() throws Exception {
        List<CanonicalMeasurementSample> odd = singleValueFile(
                "odd-range.mf4", 8, 1, new double[] {0, 1, 2}, new int[] {0}, "Only", null);
        assertFailed(odd.getFirst(), "State", 100_000_000L, 0L, "lower/upper");

        List<CanonicalMeasurementSample> count = singleValueFile(
                "ref-count.mf4", 7, 1, new double[] {0, 1}, new int[] {0}, "Known", null);
        assertFailed(count.getFirst(), "State", 100_000_000L, 0L, "does not match");
    }

    private List<CanonicalMeasurementSample> decode(Path file) throws Exception {
        MdfDynamicRecordDecoder.DecodeResult decoded =
                new MdfDynamicRecordDecoder().decodeAll(file, ReaderContext.ofBatchSize(16));
        assertTrue(decoded.frames().isEmpty());
        return decoded.nativeSignals();
    }

    private List<CanonicalMeasurementSample> singleValueFile(
            String name,
            int ccType,
            int refCount,
            double[] values,
            int[] raw,
            String firstLabel,
            String defaultLabel
    ) throws Exception {
        Path file = temp.resolve(name);
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long timeCc = b.writeCcLinear(0.0, 1.0e-5);
            long[] links = new long[refCount];
            if (links.length > 0 && firstLabel != null) {
                links[0] = b.writeTx(firstLabel);
            }
            if (defaultLabel != null && links.length > 1) {
                links[links.length - 1] = b.writeTx(defaultLabel);
            }
            long textCc = b.writeCc(ccType, refCount, links, values);
            long code = b.writeCn(0L, 0L, b.writeTx("State"), 0L, 0L, textCc, 0, 0, 0, 8, 0, 32, 0, 0);
            long time = b.writeCn(code, 0L, b.writeTx("Timestamp"), 0L, 0L, timeCc, 2, 1, 0, 0, 0, 64, 0, 0);
            long[] stamps = new long[raw.length];
            for (int i = 0; i < raw.length; i++) {
                stamps[i] = (i + 1) * 10_000L;
            }
            long cg = b.writeCg(0, time, 1, raw.length, 0, 12, 0);
            long dt = b.writeDt(records(12, raw, stamps));
            b.patchLink(hd, 0, b.writeDg(0, cg, dt, 0));
        }
        return decode(file);
    }

    private static byte[] records(int recordBytes, int[] raw, long[] timestamps) {
        ByteBuffer payload = ByteBuffer.allocate(recordBytes * raw.length).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < raw.length; i++) {
            payload.putLong(timestamps[i]);
            payload.putInt(raw[i]);
        }
        return payload.array();
    }

    private static void assertText(
            CanonicalMeasurementSample sample, String name, long timestamp, long raw, String label
    ) {
        assertEquals(name, sample.signalName());
        assertEquals(timestamp, sample.timestampNanos());
        assertEquals(raw, sample.rawValue());
        assertEquals(label, sample.textualValue());
        assertTrue(Double.isNaN(sample.physicalValue()));
        assertEquals(SignalValidity.VALID, sample.validity());
        assertTrue(CategoricalValue.isCategorical(sample.validity(), sample.physicalValue(), sample.textualValue()));
    }

    private static void assertFailed(
            CanonicalMeasurementSample sample, String name, long timestamp, long raw, String detail
    ) {
        assertEquals(name, sample.signalName());
        assertEquals(timestamp, sample.timestampNanos());
        assertEquals(raw, sample.rawValue());
        assertTrue(Double.isNaN(sample.physicalValue()));
        assertEquals(SignalValidity.CONVERSION_FAILED, sample.validity());
        assertTrue(sample.textualValue().contains(detail), sample.textualValue());
        assertFalse(CategoricalValue.isCategorical(sample.validity(), sample.physicalValue(), sample.textualValue()));
    }
}
