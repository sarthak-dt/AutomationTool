package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.SignalValidity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CategoricalPresentationTest {

    @Test
    void signalsShowTheLabelInPhysicalModeAndTheCodeBesideItInRawMode() {
        AlignedSampleRow row = new AlignedSampleRow(0, 5_998_194L,
                new Double[] {Double.NaN, 72.96875, null, 255.0},
                new Long[] {0L, 9340L, null, 255L},
                new String[] {"FALSE", null, null, "invalid"});
        assertEquals("FALSE", SampleDisplay.physical(row.physicalAt(0), row.textualAt(0), WorkspacePrecision.AUTO));
        assertEquals("0 (FALSE)", SampleDisplay.raw(row.rawAt(0), row.textualAt(0), WorkspacePrecision.AUTO));
        assertEquals(WorkspacePrecision.AUTO.formatPhysical(72.96875),
                SampleDisplay.physical(row.physicalAt(1), row.textualAt(1), WorkspacePrecision.AUTO));
        assertEquals("9340", SampleDisplay.raw(row.rawAt(1), row.textualAt(1), WorkspacePrecision.AUTO));
        assertEquals(AlignedSampleRow.MISSING, SampleDisplay.physical(row.physicalAt(2), row.textualAt(2), WorkspacePrecision.AUTO));
        assertEquals(AlignedSampleRow.MISSING, SampleDisplay.raw(row.rawAt(2), row.textualAt(2), WorkspacePrecision.AUTO));
        assertEquals("255 (invalid)", SampleDisplay.raw(row.rawAt(3), row.textualAt(3), WorkspacePrecision.AUTO));
        assertEquals("NaN", SampleDisplay.physical(Double.NaN, null, WorkspacePrecision.AUTO));
    }

    @Test
    void graphPointerAndHoverNameTheLabelAndTheMeasurement() {
        String pointer = SampleDisplay.pointer(
                "ETC_Fan2_output", "Xenon.dat", "", Double.NaN, "FALSE", 5_998_194L);
        assertTrue(pointer.contains("FALSE"));
        assertTrue(pointer.contains("ETC_Fan2_output"));
        assertTrue(pointer.contains("Xenon.dat"));
        assertFalse(pointer.contains("VALID"));
        assertFalse(pointer.contains("NaN"));
        assertEquals("FALSE", SampleDisplay.hover(Double.NaN, "FALSE", "", SignalValidity.VALID));
        String numeric = SampleDisplay.pointer("IN_Vehicle_speed", "Xenon.dat", "km/h", 72.96875, null, 99_013_388L);
        assertTrue(numeric.contains("72.9688"));
        assertTrue(numeric.contains("km/h"));
    }

    @Test
    void exactSampleUsesItsOwnTimestampAndABucketDoesNotBorrowThePointerTime() {
        String exact = SampleDisplay.provenance(
                "IN_Vehicle_speed", "Xenon.dat", "km/h", 72.96875, null,
                99_013_388L, 99_013_388L, 99_013_388L, 99_013_388L, 1, "exact", "VALID", null);
        assertTrue(exact.startsWith("Pointer:"));
        assertTrue(exact.contains("sample 0.099013 s"));
        assertTrue(exact.contains("72.9688 km/h"));
        assertFalse(exact.contains("UTC"));
        assertFalse(exact.contains("≈"));

        String bucket = SampleDisplay.provenance(
                "IN_Vehicle_speed", "Xenon.dat", "km/h", 72.96875, null,
                5_998_194L, 99_013_388L, 99_013_388L, 260_997_772L, 40, "nearest", "VALID", null);
        assertTrue(bucket.contains("axis 0.005998 s"));
        assertTrue(bucket.contains("nearest sample 0.099013 s"));
        assertTrue(bucket.contains("bucket "));
        assertTrue(bucket.contains("72.9688 km/h"));
        assertFalse(bucket.contains("axis 0.005998 s · IN_Vehicle_speed"));
        assertFalse(bucket.contains("UTC"));

        String aligned = SampleDisplay.provenance(
                "IN_Vehicle_speed", "Xenon.dat", "km/h", 72.96875, null,
                1_700_000_000_000_000_000L, 99_013_388L, 99_013_388L, 99_013_388L, 1,
                "exact", "VALID", null);
        assertTrue(aligned.contains("axis "));
        assertTrue(aligned.contains("sample 0.099013 s"));
        assertFalse(aligned.contains("UTC"));
    }

    @Test
    void categoricalProvenanceKeepsTheLabelRawCodeAndValidity() {
        String text = SampleDisplay.provenance(
                "ETC_Fan2_output", "Xenon.dat", "", Double.NaN, "FALSE",
                5_998_194L, 5_998_194L, 5_998_194L, 5_998_194L, 1, "exact", "VALID", 0L);
        assertTrue(text.contains("FALSE"));
        assertTrue(text.contains("raw 0"));
        assertTrue(text.contains("VALID"));
        assertTrue(text.contains("sample 0.005998 s"));
        assertFalse(text.contains("NaN"));
        assertFalse(text.contains("0.00000"));
    }

    @Test
    void reportDistinguishesACategoricalRangeFromAMissingSignal() {
        assertEquals(CategoricalSummary.UNAVAILABLE, ReportSignalFormat.extremum(null, 9638));
        assertEquals(AlignedSampleRow.MISSING, ReportSignalFormat.extremum(null, 0));
        assertEquals("23.09", ReportSignalFormat.extremum(23.0859375, 100));
        assertEquals("Categorical — FALSE", CategoricalSummary.engineeringCue("FALSE"));
    }
}
