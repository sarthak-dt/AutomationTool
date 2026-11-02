package com.stellar.platform.parser.mf4;

import com.stellar.platform.parser.mf4.analysis.MdfFinalizationStatus;
import com.stellar.platform.parser.mf4.block.MdfLinkIssueKind;
import com.stellar.platform.parser.mf4.report.MdfInspectionReport;
import com.stellar.platform.parser.mf4.report.MdfInspectionReportFormatter;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Mf4InspectionTest {

    private final Mf4Inspector inspector = new Mf4Inspector();

    @TempDir
    Path tempDir;

    @Test
    void minimalValidFinalizedMdf4() throws Exception {
        Path file = tempDir.resolve("minimal.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long name = b.writeTx("EngineSpeed");
            long cn = b.writeCn(0, name, 0, 0, 0, 0, 16);
            long cg = b.writeCg(0, cn, 1, 2, 0, 2, 0);
            byte[] payload = new byte[]{0x01, 0x02, 0x03, 0x04};
            long dt = b.writeDt(payload);
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }

        MdfInspectionReport report = inspector.inspect(file);
        assertEquals("4.10", report.mdfVersion().versionString());
        assertEquals(1, report.dgCount());
        assertEquals(1, report.cgCount());
        assertEquals(1, report.cnCount());
        assertEquals(1, report.dtCount());
        assertEquals(2, report.declaredCycleCountTotal());
        assertTrue(report.physicalDataDetected());
        assertEquals(MdfFinalizationStatus.FINALIZED, report.finalizationStatus());
        assertFalse(report.recoveryCandidate());
        assertTrue(report.brokenLinks().isEmpty());

        String formatted = MdfInspectionReportFormatter.formatSummary(report);
        assertTrue(formatted.contains("Finalization status: FINALIZED"));
        assertTrue(formatted.contains("DG count: 1"));
        assertEquals(formatted, MdfInspectionReportFormatter.formatSummary(report));
    }

    @Test
    void multipleDgCgChains() throws Exception {
        Path file = tempDir.resolve("multi.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.11", 411, 0);
            long hd = b.writeHd(0, 0, 0);

            long cn2 = b.writeCn(0, b.writeTx("B"), 0, 0, 0, 0, 8);
            long cg2 = b.writeCg(0, cn2, 2, 1, 0, 1, 0);
            long dt2 = b.writeDt(new byte[]{0x22});
            long dg2 = b.writeDg(0, cg2, dt2, 0);

            long cn1b = b.writeCn(0, b.writeTx("A2"), 0, 0, 0, 1, 8);
            long cn1a = b.writeCn(0, b.writeTx("A1"), 0, 0, 0, 0, 8);
            long cg1b = b.writeCg(0, cn1b, 2, 1, 0, 1, 0);
            long cg1a = b.writeCg(cg1b, cn1a, 1, 1, 0, 1, 0);
            long dt1 = b.writeDt(new byte[]{0x11});
            long dg1 = b.writeDg(dg2, cg1a, dt1, 0);

            b.patchLink(hd, 0, dg1);
        }

        MdfInspectionReport report = inspector.inspect(file);
        assertEquals(2, report.dgCount());
        assertEquals(3, report.cgCount());
        assertEquals(3, report.cnCount());
        assertEquals(2, report.dtCount());
    }

    @Test
    void validDtBlockCounted() throws Exception {
        Path file = tempDir.resolve("dt.mf4");
        writeSimple(file, 3, new byte[]{1, 2, 3, 4, 5, 6}, 0);
        MdfInspectionReport report = inspector.inspect(file);
        assertEquals(1, report.dtCount());
        assertEquals(6, report.physicalDataRegionLength());
    }

    @Test
    void dzMetadataExtracted() throws Exception {
        Path file = tempDir.resolve("dz.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("X"), 0, 0, 0, 0, 8);
            long cg = b.writeCg(0, cn, 1, 0, 0, 1, 0);
            long dz = b.writeDz("DT", 1, 100, new byte[]{0x01, 0x02, 0x03, 0x04});
            long dg = b.writeDg(0, cg, dz, 0);
            b.patchLink(hd, 0, dg);
        }

        MdfInspectionReport report = inspector.inspect(file);
        assertEquals(1, report.dzCount());
        assertTrue(report.physicalDataDetected());
    }

    @Test
    void dlChainDiscovered() throws Exception {
        Path file = tempDir.resolve("dl.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("X"), 0, 0, 0, 0, 8);
            long cg = b.writeCg(0, cn, 1, 2, 0, 1, 0);
            long dt1 = b.writeDt(new byte[]{0x01});
            long dt2 = b.writeDt(new byte[]{0x02});
            long dl2 = b.writeDl(0, new long[]{dt2}, 0);
            long dl1 = b.writeDl(dl2, new long[]{dt1}, 0);
            long dg = b.writeDg(0, cg, dl1, 0);
            b.patchLink(hd, 0, dg);
        }

        MdfInspectionReport report = inspector.inspect(file);
        assertEquals(2, report.dlCount());
        assertEquals(2, report.dtCount());
    }

    @Test
    void circularLinkDoesNotLoopInfinitely() throws Exception {
        Path file = tempDir.resolve("circular.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("X"), 0, 0, 0, 0, 8);
            long cg = b.writeCg(0, cn, 1, 0, 0, 1, 0);
            long dt = b.writeDt(new byte[]{0x01});
            long dg1 = b.writeDg(0, cg, dt, 0);
            long dg2 = b.writeDg(dg1, cg, dt, 0);
            b.patchLink(dg1, 0, dg2); // dg1 -> dg2 -> dg1
            b.patchLink(hd, 0, dg1);
        }

        MdfInspectionReport report = inspector.inspect(file);
        assertTrue(report.consistencyIssues().stream()
                .anyMatch(i -> i.code().contains("CYCLIC") || i.message().toLowerCase().contains("cyclic")));
        assertEquals(2, report.dgCount());
    }

    @Test
    void linkOutsidePhysicalFile() throws Exception {
        Path file = tempDir.resolve("outside.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("X"), 0, 0, 0, 0, 8);
            long cg = b.writeCg(0, cn, 1, 0, 0, 1, 0);
            long dt = b.writeDt(new byte[]{0x01});
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
            b.patchLink(dg, 2, 9_999_999L);
        }

        MdfInspectionReport report = inspector.inspect(file);
        assertFalse(report.brokenLinks().isEmpty());
        assertTrue(report.brokenLinks().stream()
                .anyMatch(l -> l.kind() == MdfLinkIssueKind.OUTSIDE_FILE));
        assertEquals(MdfFinalizationStatus.CORRUPTED, report.finalizationStatus());
    }

    @Test
    void declaredLengthBeyondEof() throws Exception {
        Path file = tempDir.resolve("beyond.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("X"), 0, 0, 0, 0, 8);
            long cg = b.writeCg(0, cn, 1, 1, 0, 1, 0);
            long dt = b.writeDt(new byte[]{0x01});
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
            b.patchDeclaredLength(dt, 5_000_000L);
        }

        MdfInspectionReport report = inspector.inspect(file);
        assertTrue(report.consistencyIssues().stream()
                .anyMatch(i -> i.code().contains("IMPOSSIBLE") || i.message().contains("exceeds")));
        assertEquals(MdfFinalizationStatus.CORRUPTED, report.finalizationStatus());
    }

    @Test
    void cycleCountZeroWithPhysicalDataIsRecoverable() throws Exception {
        Path file = tempDir.resolve("unfinalized-cycles.mf4");
        writeSimple(file, 0, new byte[]{0x10, 0x20, 0x30, 0x40}, 0);

        MdfInspectionReport report = inspector.inspect(file);
        assertEquals(0, report.declaredCycleCountTotal());
        assertTrue(report.physicalDataDetected());
        assertTrue(report.recoveryCandidate());
        assertEquals(MdfFinalizationStatus.UNFINALIZED, report.finalizationStatus());
        assertTrue(report.consistencyIssues().stream()
                .anyMatch(i -> "CYCLE_COUNT_ZERO_WITH_PHYSICAL_DATA".equals(i.code())));
        // Must not silently look like empty finalized success
        assertFalse(report.finalizationStatus() == MdfFinalizationStatus.FINALIZED
                && report.physicalDataRegionLength() == 0);
    }

    @Test
    void declaredPayloadZeroWithTrailingBytes() throws Exception {
        Path file = tempDir.resolve("payload-zero.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("X"), 0, 0, 0, 0, 8);
            long cg = b.writeCg(0, cn, 1, 0, 0, 1, 0);
            long dt = b.writeDt(new byte[0]);
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
            b.appendRaw(new byte[]{0x55, 0x66, 0x77, (byte) 0x88});
        }

        MdfInspectionReport report = inspector.inspect(file);
        assertTrue(report.consistencyIssues().stream()
                .anyMatch(i -> "DECLARED_PAYLOAD_ZERO_WITH_PHYSICAL_BYTES".equals(i.code())));
        assertTrue(report.recoveryCandidate());
    }

    @Test
    void unknownReadableBlockRetained() throws Exception {
        Path file = tempDir.resolve("unknown.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long unknown = b.writeUnknown("##ZZ", new byte[]{0x01});
            long cn = b.writeCn(0, b.writeTx("X"), 0, 0, 0, 0, 8);
            long cg = b.writeCg(0, cn, 1, 1, 0, 1, 0);
            long dt = b.writeDt(new byte[]{0x01});
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
            b.patchLink(hd, 5, unknown); // md_comment -> unknown block
        }

        MdfInspectionReport report = inspector.inspect(file);
        assertFalse(report.unknownBlocks().isEmpty());
        assertTrue(report.unknownBlocks().stream().anyMatch(s -> s.startsWith("##ZZ")));
    }

    @Test
    void emptyAndTruncatedFilesRejectedOrDiagnosed() throws Exception {
        Path empty = tempDir.resolve("empty.mf4");
        Files.write(empty, new byte[0]);
        assertThrows(ParserException.class, () -> inspector.inspect(empty));

        Path truncated = tempDir.resolve("truncated.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(truncated)) {
            b.writeIdBlock("4.10", 410, 0);
            b.writeHd(0, 0, 0);
            b.truncateTo(70);
        }
        MdfInspectionReport truncatedReport = inspector.inspect(truncated);
        assertTrue(truncatedReport.finalizationStatus() == MdfFinalizationStatus.CORRUPTED
                || truncatedReport.finalizationStatus() == MdfFinalizationStatus.UNKNOWN
                || !truncatedReport.consistencyIssues().isEmpty()
                || truncatedReport.dgCount() == 0);
    }

    @Test
    void nonMdfFileRejectedClearly() throws Exception {
        Path file = tempDir.resolve("not-mdf.bin");
        byte[] payload = new byte[128];
        byte[] hello = "HELLO WORLD NOT MDF FILE CONTENT".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(hello, 0, payload, 0, hello.length);
        Files.write(file, payload);
        ParserException ex = assertThrows(ParserException.class, () -> inspector.inspect(file));
        String msg = ex.getMessage().toLowerCase();
        assertTrue(msg.contains("not an mdf") || msg.contains("signature") || msg.contains("expected signature"),
                () -> "Unexpected message: " + ex.getMessage());
    }

    @Test
    void cancellationIsSafe() throws Exception {
        Path file = tempDir.resolve("cancel.mf4");
        writeSimple(file, 1, new byte[]{0x01, 0x02}, 0);

        SimpleCancellationToken token = new SimpleCancellationToken();
        token.cancel();
        MdfInspectionReport report = inspector.inspect(file, token);
        assertTrue(report.cancelled());
    }

    @Test
    void identificationNotOnlyByExtension() throws Exception {
        Path file = tempDir.resolve("content-mdf.dat");
        writeSimple(file, 1, new byte[]{0x01, 0x02}, 0);
        assertTrue(new Mf4MeasurementFileParser().supports(file));
        MdfInspectionReport report = inspector.inspect(file);
        assertEquals(1, report.dgCount());
    }

    private void writeSimple(Path file, long cycleCount, byte[] payload, int unfinFlags) throws Exception {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, unfinFlags);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("Ch"), 0, 0, 0, 0, payload.length * 8);
            long cg = b.writeCg(0, cn, 1, cycleCount, 0, payload.length, 0);
            long dt = b.writeDt(payload);
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
    }
}
