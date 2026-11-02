package com.stellar.platform.parser.mf4.dynamicschema;

import com.stellar.platform.parser.mf4.analysis.MdfConsistencyAnalyser;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraph;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraphDiscovery;
import com.stellar.platform.parser.mf4.id.MdfIdentification;
import com.stellar.platform.parser.mf4.id.MdfIdentificationReader;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.schema.MdfMetadataSummary;
import com.stellar.platform.parser.mf4.schema.MdfSchemaExtractor;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdfDynamicSchemaBuilderTest {

    private final MdfDynamicSchemaBuilder builder = new MdfDynamicSchemaBuilder();

    @TempDir
    Path tempDir;

    @Test
    void singleDgSingleCg() throws Exception {
        Path file = tempDir.resolve("single.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("SignalA"), 0, 0, 0, 0, 16);
            long cg = b.writeCg(0, cn, 1, 2, 0, 2, 0);
            long dt = b.writeDt(new byte[]{1, 2, 3, 4});
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        MdfDynamicSchemaResult result = build(file);
        assertEquals(1, result.recordSchemas().size());
        MdfRecordSchema schema = result.recordSchemas().getFirst();
        assertEquals(0, schema.recordIdSize());
        assertEquals(2, schema.dataBytesPerRecord());
        assertEquals(2, schema.completeRecordLength());
        assertEquals(MdfSchemaStatus.NORMAL, schema.status());
        assertEquals(1, schema.channelFields().size());
    }

    @Test
    void singleDgMultipleCgDifferentRecordSizesAndIds() throws Exception {
        Path file = tempDir.resolve("multi-cg.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn2 = b.writeCn(0, b.writeTx("B"), 0, 0, 0, 0, 32);
            long cg2 = b.writeCg(0, cn2, 2, 1, 0, 4, 1);
            long cn1 = b.writeCn(0, b.writeTx("A"), 0, 0, 0, 0, 16);
            long cg1 = b.writeCg(cg2, cn1, 1, 1, 0, 2, 0);
            long dt = b.writeDt(new byte[]{1, 2, 3, 4, 5});
            long dg = b.writeDg(0, cg1, dt, 1);
            b.patchLink(hd, 0, dg);
        }
        MdfDynamicSchemaResult result = build(file);
        assertEquals(2, result.recordSchemas().size());
        MdfRecordSchema r1 = result.find(result.recordSchemas().getFirst().dataGroupAddress(), 1).orElseThrow();
        MdfRecordSchema r2 = result.find(r1.dataGroupAddress(), 2).orElseThrow();
        assertEquals(1, r1.recordIdSize());
        assertEquals(3, r1.completeRecordLength()); // 1 + 2 + 0
        assertEquals(6, r2.completeRecordLength()); // 1 + 4 + 1
        assertNotEquals(r1.dataBytesPerRecord(), r2.dataBytesPerRecord());
    }

    @Test
    void multipleDataGroups() throws Exception {
        Path file = tempDir.resolve("multi-dg.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn2 = b.writeCn(0, b.writeTx("Y"), 0, 0, 0, 0, 8);
            long cg2 = b.writeCg(0, cn2, 1, 1, 0, 1, 0);
            long dt2 = b.writeDt(new byte[]{9});
            long dg2 = b.writeDg(0, cg2, dt2, 0);

            long cn1 = b.writeCn(0, b.writeTx("X"), 0, 0, 0, 0, 8);
            long cg1 = b.writeCg(0, cn1, 1, 1, 0, 1, 0);
            long dt1 = b.writeDt(new byte[]{1});
            long dg1 = b.writeDg(dg2, cg1, dt1, 0);
            b.patchLink(hd, 0, dg1);
        }
        MdfDynamicSchemaResult result = build(file);
        assertEquals(2, result.recordSchemas().size());
        assertEquals(2, result.byDgAndRecordId().size());
    }

    @Test
    void recordIdSizeZeroAndNonZero() throws Exception {
        Path zero = tempDir.resolve("rid0.mf4");
        writeSimple(zero, 0, 1, 2, 0, new byte[]{1, 2}, 0);
        assertEquals(0, build(zero).recordSchemas().getFirst().recordIdSize());

        Path one = tempDir.resolve("rid1.mf4");
        writeSimple(one, 1, 1, 2, 0, new byte[]{1, 2, 3}, 0);
        assertEquals(1, build(one).recordSchemas().getFirst().recordIdSize());
        assertEquals(3, build(one).recordSchemas().getFirst().completeRecordLength());
    }

    @Test
    void duplicateRecordIdsDiagnosed() throws Exception {
        Path file = tempDir.resolve("dup-rid.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn2 = b.writeCn(0, b.writeTx("B"), 0, 0, 0, 0, 8);
            long cg2 = b.writeCg(0, cn2, 7, 1, 0, 1, 0);
            long cn1 = b.writeCn(0, b.writeTx("A"), 0, 0, 0, 0, 8);
            long cg1 = b.writeCg(cg2, cn1, 7, 1, 0, 1, 0);
            long dt = b.writeDt(new byte[]{1});
            long dg = b.writeDg(0, cg1, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        MdfDynamicSchemaResult result = build(file);
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code() == MdfSchemaDiagnosticCode.DUPLICATE_RECORD_ID));
    }

    @Test
    void bitOffsetAndCrossingByteBoundary() throws Exception {
        Path file = tempDir.resolve("bits.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("Packed"), 0, 0, 0, 0, 0, 3, 12, 0);
            long cg = b.writeCg(0, cn, 1, 1, 0, 2, 0);
            long dt = b.writeDt(new byte[]{0x11, 0x22});
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        MdfChannelFieldSchema field = build(file).recordSchemas().getFirst().channelFields().getFirst();
        assertEquals(0, field.byteOffset());
        assertEquals(3, field.bitOffset());
        assertEquals(12, field.bitLength());
        assertTrue(field.supported());
    }

    @Test
    void invalidationBytesTracked() throws Exception {
        Path file = tempDir.resolve("inval.mf4");
        writeSimple(file, 0, 1, 2, 1, new byte[]{1, 2, 3}, 0);
        MdfRecordSchema schema = build(file).recordSchemas().getFirst();
        assertEquals(1, schema.invalidationBytesPerRecord());
        assertEquals(3, schema.completeRecordLength());
    }

    @Test
    void channelOutsideRecordBoundary() throws Exception {
        Path file = tempDir.resolve("oob.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("Overflow"), 0, 0, 0, 1, 16); // byteOffset 1, 16 bits, dataBytes=1
            long cg = b.writeCg(0, cn, 1, 1, 0, 1, 0);
            long dt = b.writeDt(new byte[]{1});
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        MdfDynamicSchemaResult result = build(file);
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code() == MdfSchemaDiagnosticCode.CHANNEL_OUTSIDE_RECORD_BOUNDARY));
    }

    @Test
    void arithmeticAndBoundaryOverflowDiagnosed() throws Exception {
        // Extreme byteOffset relative to tiny data region (boundary overflow path).
        Path boundary = tempDir.resolve("boundary-overflow.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(boundary)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("HugeOffset"), 0, 0, 0, Integer.MAX_VALUE, 8);
            long cg = b.writeCg(0, cn, 1, 1, 0, 1, 0);
            long dt = b.writeDt(new byte[]{1});
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        MdfDynamicSchemaResult boundaryResult = build(boundary);
        assertTrue(boundaryResult.diagnostics().stream().anyMatch(d ->
                d.code() == MdfSchemaDiagnosticCode.CHANNEL_OUTSIDE_RECORD_BOUNDARY
                        || d.code() == MdfSchemaDiagnosticCode.ARITHMETIC_OVERFLOW));

        // Invalidation bit outside invalidation region.
        Path inval = tempDir.resolve("inval-overflow.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(inval)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("Inv"), 0, 0, 0, 0, 0, 0, 8, 16); // inval bit 16, region=8 bits
            long cg = b.writeCg(0, cn, 1, 1, 0, 1, 1);
            long dt = b.writeDt(new byte[]{1, 0});
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        assertTrue(build(inval).diagnostics().stream()
                .anyMatch(d -> d.code() == MdfSchemaDiagnosticCode.INVALID_INVALIDATION_REGION));
    }

    @Test
    void dtDlDzHlDataSourcesResolved() throws Exception {
        Path dtFile = tempDir.resolve("src-dt.mf4");
        writeSimple(dtFile, 0, 1, 1, 0, new byte[]{1}, 0);
        assertEquals("DT", build(dtFile).recordSchemas().getFirst().dataSource().rootType().name());

        Path dlFile = tempDir.resolve("src-dl.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(dlFile)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("X"), 0, 0, 0, 0, 8);
            long cg = b.writeCg(0, cn, 1, 1, 0, 1, 0);
            long dt = b.writeDt(new byte[]{1});
            long dl = b.writeDl(0, new long[]{dt}, 0);
            long dg = b.writeDg(0, cg, dl, 0);
            b.patchLink(hd, 0, dg);
        }
        assertTrue(MdfRecordSchemaFormatter.format(build(dlFile)).contains("DL -> DT")
                || build(dlFile).recordSchemas().getFirst().dataSource().orderedNodes().size() >= 2);

        Path dzFile = tempDir.resolve("src-dz.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(dzFile)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("X"), 0, 0, 0, 0, 8);
            long cg = b.writeCg(0, cn, 1, 0, 0, 1, 0);
            long dz = b.writeDz("DT", 1, 10, new byte[]{1, 2});
            long dg = b.writeDg(0, cg, dz, 0);
            b.patchLink(hd, 0, dg);
        }
        assertEquals("DZ", build(dzFile).recordSchemas().getFirst().dataSource().rootType().name());

        Path hlFile = tempDir.resolve("src-hl.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(hlFile)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("X"), 0, 0, 0, 0, 8);
            long cg = b.writeCg(0, cn, 1, 1, 0, 1, 0);
            long dt = b.writeDt(new byte[]{1});
            long dl = b.writeDl(0, new long[]{dt}, 0);
            long hl = b.writeHl(dl, 1);
            long dg = b.writeDg(0, cg, hl, 0);
            b.patchLink(hd, 0, dg);
        }
        assertEquals("HL", build(hlFile).recordSchemas().getFirst().dataSource().rootType().name());
    }

    @Test
    void brokenDataChainDiagnosed() throws Exception {
        Path file = tempDir.resolve("broken.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("X"), 0, 0, 0, 0, 8);
            long cg = b.writeCg(0, cn, 1, 1, 0, 1, 0);
            long dg = b.writeDg(0, cg, 0, 0); // missing data root
            b.patchLink(hd, 0, dg);
        }
        MdfDynamicSchemaResult result = build(file);
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code() == MdfSchemaDiagnosticCode.MISSING_DATA_ROOT));
    }

    /**
     * Regression for the original POC failure mode:
     * cycleCount == 0 + physical data + structurally valid layout must yield RECOVERY_CANDIDATE,
     * never a silent "zero records" / empty / fatal-only-because-cycle-count-zero outcome.
     */
    @Test
    void pocRegression_cycleCountZeroWithPhysicalData_isRecoveryCandidateNotSilentZero() throws Exception {
        Path file = tempDir.resolve("poc-zero-cycles.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 4, 0, 0, 64, 0);
            long canId = b.writeCn(time, b.writeTx("CAN_ID"), 0, 0, 0, 0, 8, 0, 32, 0);
            long dlc = b.writeCn(canId, b.writeTx("DLC"), 0, 0, 0, 0, 12, 0, 8, 0);
            long data = b.writeCn(dlc, b.writeTx("DataBytes"), 0, 0, 0, 9, 13, 0, 64, 0);
            long cg = b.writeCg(0, data, 1, 0, 0, 21, 0); // declared cycles = 0
            byte[] payload = new byte[42]; // physical data exists
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) i;
            }
            long dt = b.writeDt(payload);
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }

        MdfDynamicSchemaResult result = build(file);
        assertFalse(result.usableSchemas().isEmpty(), "Schema must remain usable");
        MdfRecordSchema schema = result.usableSchemas().getFirst();
        assertEquals(0, schema.declaredCycleCount());
        assertTrue(schema.physicalDataExists());
        assertEquals(MdfSchemaStatus.RECOVERY_CANDIDATE, schema.status());
        assertTrue(schema.completeRecordLength() > 0);
        assertFalse(schema.channelFields().isEmpty());
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code() == MdfSchemaDiagnosticCode.RECOVERY_CANDIDATE));
        // Must not mean zero records
        assertNotEquals(MdfSchemaStatus.UNUSABLE, schema.status());
        assertTrue(schema.usable());
    }

    @Test
    void structurallyUsableUnfinalizedFileProducesSchema() throws Exception {
        Path file = tempDir.resolve("unfin.mf4");
        writeSimple(file, 0, 0, 2, 0, new byte[]{1, 2, 3, 4}, 1);
        MdfRecordSchema schema = build(file).recordSchemas().getFirst();
        assertEquals(MdfSchemaStatus.RECOVERY_CANDIDATE, schema.status());
        assertTrue(schema.usable());
    }

    @Test
    void unknownOptionalDataTypeDiagnosedButSchemaMayRemainPartial() throws Exception {
        Path file = tempDir.resolve("mime.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long unsupported = b.writeCn(0, b.writeTx("Mime"), 0, 0, 0, 10, 0, 0, 8, 0); // MIME_SAMPLE
            long supported = b.writeCn(unsupported, b.writeTx("Ok"), 0, 0, 0, 0, 1, 0, 8, 0);
            long cg = b.writeCg(0, supported, 1, 1, 0, 2, 0);
            long dt = b.writeDt(new byte[]{1, 2});
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        MdfDynamicSchemaResult result = build(file);
        assertTrue(result.diagnostics().stream()
                .anyMatch(d -> d.code() == MdfSchemaDiagnosticCode.UNSUPPORTED_MDF_DATA_TYPE));
        assertTrue(result.recordSchemas().getFirst().channelFields().stream().anyMatch(MdfChannelFieldSchema::supported));
    }

    @Test
    void canRoleClassificationIsEvidenceBased() throws Exception {
        Path file = tempDir.resolve("can-roles.mf4");
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long t = b.writeCn(0, b.writeTx("t_stamp"), 0, 2, 1, 4, 0, 0, 64, 0);
            long id = b.writeCn(t, b.writeTx("can_id"), 0, 0, 0, 0, 8, 0, 29, 0);
            long cg = b.writeCg(0, id, 1, 1, 0, 16, 0);
            long dt = b.writeDt(new byte[16]);
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        List<MdfChannelFieldSchema> fields = build(file).recordSchemas().getFirst().channelFields();
        assertTrue(fields.stream().anyMatch(f -> f.canRoleCandidate().role() == MdfCanRole.TIMESTAMP));
        assertTrue(fields.stream().anyMatch(f -> f.canRoleCandidate().role() == MdfCanRole.CAN_ID));
    }

    @Test
    void formatterIsDeterministic() throws Exception {
        Path file = tempDir.resolve("fmt.mf4");
        writeSimple(file, 0, 1, 2, 0, new byte[]{1, 2}, 0);
        MdfDynamicSchemaResult result = build(file);
        String a = MdfRecordSchemaFormatter.format(result);
        String b = MdfRecordSchemaFormatter.format(result);
        assertEquals(a, b);
        assertTrue(a.contains("recordIdSize"));
        assertTrue(a.contains("status"));
    }

    private void writeSimple(
            Path file, int recIdSize, long cycles, int dataBytes, int invalBytes, byte[] payload, int unfin
    ) throws Exception {
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, unfin);
            long hd = b.writeHd(0, 0, 0);
            long cn = b.writeCn(0, b.writeTx("Ch"), 0, 0, 0, 0, dataBytes * 8);
            long cg = b.writeCg(0, cn, 1, cycles, 0, dataBytes, invalBytes);
            long dt = b.writeDt(payload);
            long dg = b.writeDg(0, cg, dt, recIdSize);
            b.patchLink(hd, 0, dg);
        }
    }

    private MdfDynamicSchemaResult build(Path file) throws Exception {
        try (MdfFileAccess access = MdfFileAccess.open(file)) {
            MdfIdentification id = MdfIdentificationReader.read(access);
            MdfBlockGraph graph = MdfBlockGraphDiscovery.discover(access, CancellationToken.NONE);
            MdfMetadataSummary metadata = MdfSchemaExtractor.extract(access, graph, CancellationToken.NONE);
            MdfConsistencyAnalyser.AnalysisResult analysis =
                    MdfConsistencyAnalyser.analyse(id, graph, metadata);
            return builder.build(graph, metadata, analysis);
        }
    }
}
