package com.stellar.platform.api.worker;

import com.stellar.platform.api.support.ProcessingPlanDtoMapper;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.FileFingerprint;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("release-acceptance")
class SelectiveSourceInvalidationAbcdTest {

    @TempDir
    Path temp;

    @AfterEach
    void clear() {
        AnalysisStorageRoots.clear();
    }

    @Test
    void changeOnlySourceBReprocessesBAndReusesAcd() throws Exception {
        Fixture fx = fixture("src-change");
        processAll(fx, true);
        Snap first = snap(fx);

        Files.deleteIfExists(fx.bMf4);
        writeMf4(fx.bMf4, 12, 0x123);
        processAll(fx, false);
        Snap second = snap(fx);

        assertEquals(first.a.sourceFingerprint(), second.a.sourceFingerprint(), "A fingerprint");
        assertEquals(first.c.sourceFingerprint(), second.c.sourceFingerprint(), "C fingerprint");
        assertEquals(first.d.sourceFingerprint(), second.d.sourceFingerprint(), "D fingerprint");
        assertNotEquals(first.b.sourceFingerprint(), second.b.sourceFingerprint(), "B fingerprint must change");
        assertEquals(first.a.createdAt(), second.a.createdAt(), "A artifact must be reused");
        assertEquals(first.c.createdAt(), second.c.createdAt(), "C artifact must be reused");
        assertEquals(first.d.createdAt(), second.d.createdAt(), "D artifact must be reused");
        assertTrue(second.b.createdAt().isAfter(first.b.createdAt())
                || !second.b.processingRunId().equals(first.b.processingRunId()));
        assertEquals(FileFingerprint.of(fx.bMf4), second.b.sourceFingerprint());
    }

    @Test
    void changeOnlyBDefinitionReprocessesBAndReusesAcd() throws Exception {
        Fixture fx = fixture("def-change");
        processAll(fx, true);
        Snap first = snap(fx);

        Files.writeString(fx.bDbc, """
                VERSION ""
                NS_ :
                BS_:
                BU_: ECU
                BO_ 291 TestMsg: 8 ECU
                 SG_ Sig1 : 0|8@1+ (1,0) [0|255] "" ECU
                 SG_ SigB2 : 8|8@1+ (1,0) [0|255] "" ECU
                """);
        processAll(fx, false);
        Snap second = snap(fx);

        assertEquals(first.a.dbcFingerprint(), second.a.dbcFingerprint());
        assertEquals(first.c.dbcFingerprint(), second.c.dbcFingerprint());
        assertEquals(first.d.dbcFingerprint(), second.d.dbcFingerprint());
        assertNotEquals(first.b.dbcFingerprint(), second.b.dbcFingerprint());
        assertEquals(first.a.createdAt(), second.a.createdAt());
        assertEquals(first.c.createdAt(), second.c.createdAt());
        assertEquals(first.d.createdAt(), second.d.createdAt());
    }

    private void processAll(Fixture fx, boolean force) {
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                force ? "plan-1" : "plan-2",
                Instant.now(),
                List.of(job("src-a"), job("src-b"), job("src-c"), job("src-d")),
                ProcessingPlanReadiness.READY,
                List.of());
        ProcessingTaskDescriptor task = ProcessingTaskDescriptor.plan(
                force ? "t1" : "t2",
                fx.projectId,
                force ? "run-1" : "run-2",
                fx.db.toString(),
                fx.analysis.toString(),
                ProcessingPlanDtoMapper.toJson(plan),
                Map.of(
                        "src-a", fx.aMf4.toString(),
                        "src-b", fx.bMf4.toString(),
                        "src-c", fx.cMf4.toString(),
                        "src-d", fx.dMf4.toString()),
                Map.of(
                        "def-a", fx.aDbc.toString(),
                        "def-b", fx.bDbc.toString(),
                        "def-c", fx.cDbc.toString(),
                        "def-d", fx.dDbc.toString()),
                force);
        LocalPlanWorkerEngine.Outcome outcome = new LocalPlanWorkerEngine().execute(task, null, null);
        assertEquals("SUCCESS", outcome.status(), outcome.toJson());
    }

    private Snap snap(Fixture fx) throws Exception {
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(fx.db)) {
            return new Snap(
                    store.findValidArtifact(fx.projectId, "src-a", 0, "def-a").orElseThrow(),
                    store.findValidArtifact(fx.projectId, "src-b", 0, "def-b").orElseThrow(),
                    store.findValidArtifact(fx.projectId, "src-c", 0, "def-c").orElseThrow(),
                    store.findValidArtifact(fx.projectId, "src-d", 0, "def-d").orElseThrow());
        }
    }

    private Fixture fixture(String name) throws Exception {
        Path analysis = temp.resolve(name + "-analysis");
        Files.createDirectories(analysis);
        AnalysisStorageRoots.setUserSelectedRoot(analysis);
        Path db = temp.resolve(name + "-project/.stellar/stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, name, Instant.now(), Instant.now(), "DECODE", null));
        }
        return new Fixture(
                projectId,
                db,
                analysis,
                writeMf4(temp.resolve(name + "-a.mf4"), 8, 0x111),
                writeMf4(temp.resolve(name + "-b.mf4"), 8, 0x222),
                writeMf4(temp.resolve(name + "-c.mf4"), 8, 0x333),
                writeMf4(temp.resolve(name + "-d.mf4"), 8, 0x444),
                writeDbc(temp.resolve(name + "-a.dbc")),
                writeDbc(temp.resolve(name + "-b.dbc")),
                writeDbc(temp.resolve(name + "-c.dbc")),
                writeDbc(temp.resolve(name + "-d.dbc")));
    }

    private static ProcessingJob job(String sourceId) {
        String def = "def-" + sourceId.substring(sourceId.length() - 1);
        return new ProcessingJob(
                ProcessingJobKey.of(sourceId, 0),
                MeasurementSourceFormat.MF4,
                MappingLifecycle.CONFIRMED,
                def,
                def + ".dbc",
                1L, 1L, 0L, 0L, 1.0, 0L, 1.0,
                null, true, List.of());
    }

    private static Path writeMf4(Path file, int count, int canId) throws Exception {
        List<byte[]> records = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            byte[] data = new byte[23];
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            buf.put(0, (byte) 0);
            buf.putInt(2, canId);
            buf.put(6, (byte) 8);
            data[7] = (byte) i;
            buf.putLong(15, 1_000_000L * i);
            records.add(data);
        }
        try (SyntheticMdf4Builder b = SyntheticMdf4Builder.create(file)) {
            b.writeIdBlock("4.10", 410, 0);
            long hd = b.writeHd(0, 0, 0);
            long time = b.writeCn(0, b.writeTx("Timestamp"), 0, 2, 1, 0, 15, 0, 64, 0);
            long payload = b.writeCn(time, b.writeTx("DataBytes"), 0, 0, 0, 9, 7, 0, 64, 0);
            long dlc = b.writeCn(payload, b.writeTx("DLC"), 0, 0, 0, 0, 6, 0, 8, 0);
            long id = b.writeCn(dlc, b.writeTx("CAN_ID"), 0, 0, 0, 0, 2, 0, 11, 0);
            long dir = b.writeCn(id, b.writeTx("Direction"), 0, 0, 0, 0, 1, 0, 8, 0);
            long head = b.writeCn(dir, b.writeTx("BusChannel"), 0, 0, 0, 0, 0, 0, 8, 0);
            long cg = b.writeCg(0, head, 1, count, 0, 23, 0);
            int n = 23 * count;
            byte[] dtPayload = new byte[n];
            for (int i = 0; i < count; i++) {
                System.arraycopy(records.get(i), 0, dtPayload, i * 23, 23);
            }
            long dt = b.writeDt(dtPayload);
            long dg = b.writeDg(0, cg, dt, 0);
            b.patchLink(hd, 0, dg);
        }
        return file;
    }

    private static Path writeDbc(Path target) throws Exception {
        Files.writeString(target, """
                VERSION ""
                NS_ :
                BS_:
                BU_: ECU
                BO_ 291 TestMsg: 8 ECU
                 SG_ Sig1 : 0|8@1+ (1,0) [0|255] "" ECU
                """);
        return target;
    }

    private record Fixture(
            String projectId,
            Path db,
            Path analysis,
            Path aMf4,
            Path bMf4,
            Path cMf4,
            Path dMf4,
            Path aDbc,
            Path bDbc,
            Path cDbc,
            Path dDbc
    ) {
    }

    private record Snap(
            ProcessingArtifactMetadata a,
            ProcessingArtifactMetadata b,
            ProcessingArtifactMetadata c,
            ProcessingArtifactMetadata d
    ) {
    }
}
