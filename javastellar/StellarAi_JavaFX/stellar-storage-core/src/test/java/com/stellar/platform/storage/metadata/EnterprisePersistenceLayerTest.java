package com.stellar.platform.storage.metadata;

import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryDiagnostic;
import com.stellar.platform.domain.inventory.CanInventoryDiagnosticCode;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.storage.ArtifactStorageRefs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schema v4 enterprise knowledge: migration, signal catalog (no samples),
 * bookmarks, DQ findings, provenance, portable refs, cross-file search.
 */
class EnterprisePersistenceLayerTest {

    @TempDir Path tempDir;

    @Test
    void migratesV3DatabaseInPlacePreservingRowsAndAddsV4Tables() throws Exception {
        Path db = tempDir.resolve("legacy-v3.db");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            new com.stellar.platform.storage.sqlite.SchemaMigrator(List.of(
                    new com.stellar.platform.storage.sqlite.MigrationV3Baseline()
            )).migrate(connection);
            try (Statement s = connection.createStatement()) {
                s.execute("INSERT INTO projects VALUES('p-legacy','Legacy','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
                s.execute("INSERT INTO project_files VALUES('p-legacy','src1','C:/Users/ADMIN/run.mf4','MEASUREMENT_MF4',10,'READY','',NULL,'',0,1,0,'2026-01-01T00:00:00Z')");
                s.execute("INSERT INTO channel_mappings VALUES('p-legacy','src1',0,'CONFIRMED','CLEAR_BEST_MATCH','dbc1','dbc1',1,1,1.0,'2026-01-01T00:00:00Z')");
                s.execute("INSERT INTO processing_runs VALUES('run1','p-legacy','COMPLETED',0,1,'2026-01-01T00:00:00Z','2026-01-01T00:01:00Z','')");
                s.execute("INSERT INTO processing_artifacts VALUES('art1','p-legacy','src1',0,'dbc1','run1','LOSSLESS_SIGNAL_STORE',"
                        + "'C:/Users/ADMIN/.stellarai/projects/p-legacy/signal-store','1',"
                        + "'v=1;size=10;mtime=1;path=C:/Users/ADMIN/run.mf4',"
                        + "'v=1;size=2;mtime=2;path=C:/Users/ADMIN/veh.dbc',"
                        + "2,500,1,2,'VALID','2026-01-01T00:01:00Z','CONFIRMED')");
            }
            try (var rs = connection.createStatement().executeQuery("SELECT version FROM schema_info")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1));
            }
        }

        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertTrue(store.tableExists("signal_catalog"));
            assertTrue(store.tableExists("engineering_bookmarks"));
            assertTrue(store.tableExists("data_quality_findings"));
            assertTrue(store.tableExists("graph_investigation"));
            assertFalse(store.tableExists("signal_samples"));
            assertFalse(store.tableExists("waveform_samples"));
            assertFalse(store.tableExists("raw_can_frames"));

            SavedProjectSnapshot snap = store.loadProject("p-legacy").orElseThrow();
            assertEquals("Legacy", snap.project().name());
            assertEquals(1, snap.files().size());
            assertEquals(1, snap.channelMappings().size());
            assertEquals(1, snap.processingArtifacts().size());
            assertEquals("art1", snap.processingArtifacts().getFirst().artifactId());
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var rs = connection.createStatement().executeQuery("SELECT version FROM schema_info")) {
            assertTrue(rs.next());
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), rs.getInt(1));
        }
    }

    @Test
    void signalCatalogPersistsMetadataOnlyNotOneRowPerSample() throws Exception {
        Path db = tempDir.resolve("catalog.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Cat", now, now));
            store.upsertFile(new ProjectFileMetadata(
                    "p1", "srcA", "D:/data/a.mf4", "MEASUREMENT_MF4", 100,
                    "READY", "", null, "", false, true, false));
            store.upsertProcessingRun(new ProcessingRunMetadata(
                    "run1", "p1", "COMPLETED", false, 1, now, now, ""));
            String ref = ArtifactStorageRefs.forJob("p1", "srcA", 0, "dbc1");
            store.upsertProcessingArtifact(new ProcessingArtifactMetadata(
                    "artA", "p1", "srcA", 0, "dbc1", "run1",
                    ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                    "", ref, ProcessingArtifactMetadata.ARTIFACT_VERSION_V1,
                    FileFingerprint.of(tempDir.resolve("a.mf4")),
                    FileFingerprint.of(tempDir.resolve("dbc1.dbc")),
                    1, 1_000_000L, 1L, 2L,
                    ProcessingArtifactMetadata.STATUS_VALID, now, "CONFIRMED"));

            store.replaceSignalCatalog("p1", "artA", List.of(new SignalCatalogMetadata(
                    "sig1", "p1", "srcA", 0, "dbc1", "artA",
                    "EngineSpeed|CAN|EngineData|100|0",
                    "EngineSpeed", "EngineData", 100L, 0, "CAN", "rpm",
                    1_000_000L, 1L, 2L, 800.0, 4500.0, now, now)));

            assertEquals(1, store.listSignalCatalog("p1").size());
            assertEquals(1_000_000L, store.listSignalCatalog("p1").getFirst().sampleCount());

            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
                 var rs = connection.createStatement().executeQuery("SELECT count(*) FROM signal_catalog")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "must not create one DB row per sample");
            }
        }
    }

    @Test
    void crossFileSignalSearchReturnsMatchingSources() throws Exception {
        Path db = tempDir.resolve("search.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Search", now, now));
            for (String src : List.of("srcA", "srcB", "srcC")) {
                store.upsertFile(new ProjectFileMetadata(
                        "p1", src, "/var/data/" + src + ".mf4", "MEASUREMENT_MF4", 10,
                        "READY", "", null, "", false, false, false));
                String art = "art-" + src;
                store.upsertProcessingArtifact(new ProcessingArtifactMetadata(
                        art, "p1", src, 1, "dbc1", "run1",
                        ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                        ArtifactStorageRefs.forJob("p1", src, 1, "dbc1"),
                        ProcessingArtifactMetadata.ARTIFACT_VERSION_V1,
                        "v=2;size=1;mtime=1;name=x", "v=2;size=1;mtime=1;name=y",
                        1, 10, 1L, 2L, ProcessingArtifactMetadata.STATUS_VALID, now, "CONFIRMED"));
                store.replaceSignalCatalog("p1", art, List.of(new SignalCatalogMetadata(
                        null, "p1", src, 1, "dbc1", art,
                        "EngineSpeed|CAN|Eng|256|1",
                        "EngineSpeed", "Eng", 256L, 1, "CAN", "rpm",
                        10, 1L, 2L, null, null, now, now)));
            }
            store.replaceSignalCatalog("p1", "art-srcC", List.of(new SignalCatalogMetadata(
                    null, "p1", "srcC", 1, "dbc1", "art-srcC",
                    "BatteryVoltage|CAN|Batt|257|1",
                    "BatteryVoltage", "Batt", 257L, 1, "CAN", "V",
                    10, 1L, 2L, null, null, now, now)));

            List<SignalCatalogMetadata> byName = store.searchSignalCatalog("p1", "EngineSpeed");
            assertEquals(2, byName.size());
            assertTrue(byName.stream().anyMatch(e -> e.sourceId().equals("srcA")));
            assertTrue(byName.stream().anyMatch(e -> e.sourceId().equals("srcB")));

            List<SignalCatalogMetadata> byCan = store.searchSignalCatalog("p1", "257");
            assertEquals(1, byCan.size());
            assertEquals("BatteryVoltage", byCan.getFirst().signalName());
        }
    }

    @Test
    void provenanceLinksSignalToArtifactRunSourceAndDbc() throws Exception {
        Path db = tempDir.resolve("prov.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Prov", now, now));
            store.upsertFile(new ProjectFileMetadata(
                    "p1", "src1", "/data/RoadTest_042.mf4", "MEASUREMENT_MF4", 10,
                    "READY", "", null, "", false, true, false));
            store.upsertFile(new ProjectFileMetadata(
                    "p1", "dbc1", "/defs/Powertrain_v17.dbc", "DEFINITION_DBC", 2,
                    "READY", "", null, "", false, false, true));
            store.upsertProcessingRun(new ProcessingRunMetadata(
                    "run99", "p1", "COMPLETED", false, 1, now, now, ""));
            String ref = ArtifactStorageRefs.forJob("p1", "src1", 2, "dbc1");
            store.upsertProcessingArtifact(new ProcessingArtifactMetadata(
                    "art1", "p1", "src1", 2, "dbc1", "run99",
                    ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                    "", ref, "1",
                    "v=2;size=10;mtime=1;name=RoadTest_042.mf4",
                    "v=2;size=2;mtime=2;name=Powertrain_v17.dbc",
                    1, 100, 1L, 2L, ProcessingArtifactMetadata.STATUS_VALID, now, "CONFIRMED"));
            store.replaceSignalCatalog("p1", "art1", List.of(new SignalCatalogMetadata(
                    "sig-uuid", "p1", "src1", 2, "dbc1", "art1",
                    "EngineSpeed|CAN|Eng|100|2",
                    "EngineSpeed", "Eng", 100L, 2, "CAN", "rpm",
                    100, 1L, 2L, 1.0, 2.0, now, now)));

            SignalProvenance prov = store.getSignalProvenance("p1", "sig-uuid").orElseThrow();
            assertEquals("EngineSpeed", prov.signalName());
            assertEquals("RoadTest_042.mf4", prov.sourceFileName());
            assertEquals("Powertrain_v17.dbc", prov.definitionFileName());
            assertEquals(2, prov.channelId());
            assertEquals("run99", prov.processingRunId());
            assertEquals("art1", prov.artifactId());
            assertEquals(ref, prov.artifactStorageRef());
            assertTrue(prov.sourceFingerprint().contains("size=10"));
            assertTrue(prov.dbcFingerprint().contains("Powertrain_v17.dbc")
                    || prov.dbcFingerprint().contains("size=2"));
        }
    }

    @Test
    void bookmarksAndDataQualityFindingsSurviveReopen() throws Exception {
        Path db = tempDir.resolve("bm.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "BM", now, now));
            store.upsertFile(new ProjectFileMetadata(
                    "p1", "src1", "C:\\data\\run.mf4", "MEASUREMENT_MF4", 10,
                    "READY", "", null, "", false, true, false));
            store.upsertBookmark(new EngineeringBookmarkMetadata(
                    "bm1", "p1", "src1", "art1", "EngineSpeed", "EngineSpeed",
                    1_234_567_890L, "STEP", "Sudden rise", now, now));

            CanMessageInventory message = new CanMessageInventory(
                    new CanMessageKey(0, 0x100, false), 10, 8, 8, false);
            CanChannelInventory channel = new CanChannelInventory(
                    0, "CAN 1", 10, 1, 1, 0, 0, List.of(message));
            CanSourceInventory inventory = new CanSourceInventory(
                    Path.of("C:\\data\\run.mf4"), "run.mf4", MeasurementSourceFormat.MF4,
                    CanInventoryStatus.INVENTORY_READY, 1, 10, 1, true, List.of(channel),
                    List.of(new CanInventoryDiagnostic(
                            CanInventoryDiagnosticCode.CAN_PAYLOAD_UNREADABLE,
                            "Observed discontinuity in compressed payload")));
            store.saveInventory("p1", "src1", inventory);
            store.upsertDataQualityFinding(new DataQualityFindingMetadata(
                    "dq1", "p1", "src1", null,
                    CanInventoryDiagnosticCode.CAN_PAYLOAD_UNREADABLE.name(),
                    "WARNING", null, null,
                    "Observed discontinuity: CAN payload unreadable",
                    "Observed discontinuity in compressed payload",
                    "", now));
        }

        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            SavedProjectSnapshot snap = store.loadProject("p1").orElseThrow();
            assertEquals(1, snap.bookmarks().size());
            assertEquals(1_234_567_890L, snap.bookmarks().getFirst().timestampNanos());
            assertEquals(1, snap.dataQualityFindings().size());
            assertEquals("CAN_PAYLOAD_UNREADABLE", snap.dataQualityFindings().getFirst().findingType());
        }
    }

    @Test
    void portableArtifactRefsAndFingerprintsIgnoreOsPathSeparators() throws Exception {
        Path winStyle = tempDir.resolve("nested").resolve("run.mf4");
        Files.createDirectories(winStyle.getParent());
        Files.writeString(winStyle, "payload");
        String fp = FileFingerprint.of(winStyle);
        assertFalse(FileFingerprint.isLegacyPathBound(fp));
        assertFalse(fp.contains("path="));
        assertTrue(FileFingerprint.matches(fp, winStyle));

        String linuxRef = ArtifactStorageRefs.forJob("proj-1", "src-1", 0, "dbc-1");
        assertTrue(ArtifactStorageRefs.isLogicalRef(linuxRef));
        Path resolved = ArtifactStorageRefs.resolveLocal(linuxRef);
        assertTrue(resolved.toString().contains("signal-store"));

        // Absolute legacy path still resolves.
        Path abs = ArtifactStorageRefs.resolveLocal(winStyle.toAbsolutePath().toString());
        assertEquals(winStyle.toAbsolutePath().normalize(), abs);
    }

    @Test
    void findValidArtifactSupportsIncrementalReuseLookup() throws Exception {
        Path db = tempDir.resolve("reuse.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Reuse", now, now));
            store.upsertProcessingArtifact(new ProcessingArtifactMetadata(
                    "artA", "p1", "srcA", 0, "dbcA", "run1",
                    ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                    ArtifactStorageRefs.forJob("p1", "srcA", 0, "dbcA"),
                    "1", "v=2;size=1;mtime=1;name=a", "v=2;size=1;mtime=1;name=d",
                    1, 10, 1L, 2L, ProcessingArtifactMetadata.STATUS_VALID, now, "CONFIRMED"));
            store.upsertProcessingArtifact(new ProcessingArtifactMetadata(
                    "artB", "p1", "srcB", 0, "dbcB", "run1",
                    ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                    ArtifactStorageRefs.forJob("p1", "srcB", 0, "dbcB"),
                    "1", "v=2;size=1;mtime=1;name=b", "v=2;size=1;mtime=1;name=d",
                    1, 10, 1L, 2L, ProcessingArtifactMetadata.STATUS_VALID, now, "CONFIRMED"));

            assertTrue(store.findValidArtifact("p1", "srcA", 0, "dbcA").isPresent());
            assertTrue(store.findValidArtifact("p1", "srcB", 0, "dbcB").isPresent());
            assertTrue(store.findValidArtifact("p1", "srcB", 0, "dbcChanged").isEmpty());
        }
    }

    @Test
    void historicalDbcMappingQueryReturnsSupportingEvidenceOnly() throws Exception {
        Path db = tempDir.resolve("hist.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p-old", "Old", now, now));
            store.upsertFile(new ProjectFileMetadata(
                    "p-old", "src1", "/old/run.mf4", "MEASUREMENT_MF4", 10,
                    "READY", "", null, "", false, true, false));
            store.upsertFile(new ProjectFileMetadata(
                    "p-old", "dbc-pt", "/old/Powertrain.dbc", "DEFINITION_DBC", 2,
                    "READY", "", null, "", false, false, true));
            CanMessageInventory message = new CanMessageInventory(
                    new CanMessageKey(0, 0x200, false), 5, 8, 8, false);
            CanChannelInventory channel = new CanChannelInventory(
                    0, "CAN 1", 5, 1, 1, 0, 0, List.of(message));
            store.saveInventory("p-old", "src1", new CanSourceInventory(
                    Path.of("/old/run.mf4"), "run.mf4", MeasurementSourceFormat.MF4,
                    CanInventoryStatus.INVENTORY_READY, 1, 5, 1, true, List.of(channel), List.of()));
            store.upsertChannelMapping(new ChannelMappingMetadata(
                    "p-old", "src1", 0, "CONFIRMED", "CLEAR_BEST_MATCH",
                    "dbc-pt", "dbc-pt", 1, 1, 1.0));

            store.upsertProject(new ProjectMetadata("p-new", "New", now, now));
            List<HistoricalDbcMappingSummary> hits =
                    store.findHistoricalDbcMappings("p-new", List.of(0x200L));
            assertFalse(hits.isEmpty());
            assertTrue(hits.stream().anyMatch(h -> "dbc-pt".equals(h.definitionId())));
        }
    }

    @Test
    void bookmarkUpsertUpdatesEditableFieldsAndDeleteStaysInsideTheProject() throws Exception {
        Path db = tempDir.resolve("bookmark-edit.db");
        Instant created = Instant.parse("2026-10-21T12:00:00Z");
        Instant edited = Instant.parse("2026-10-21T12:05:00Z");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "One", created, created));
            store.upsertProject(new ProjectMetadata("p2", "Two", created, created));
            store.upsertBookmark(new EngineeringBookmarkMetadata(
                    "bm1", "p1", "src1", "art1", "EngineSpeed", "EngineSpeed",
                    1_234_567_890L, "Observation", "Original", created, created));
            store.upsertBookmark(new EngineeringBookmarkMetadata(
                    "bm2", "p2", "src2", "art2", "VehicleSpeed", "VehicleSpeed",
                    50L, "Issue", "Other project", created, created));

            store.upsertBookmark(new EngineeringBookmarkMetadata(
                    "bm1", "p1", "src-moved", "art-moved", "OtherSignal", "OtherSignal",
                    999L, "Braking", "Edited title", created, edited));

            EngineeringBookmarkMetadata saved = store.listBookmarks("p1").getFirst();
            assertEquals("bm1", saved.bookmarkId());
            assertEquals("p1", saved.projectId());
            assertEquals("src1", saved.sourceId());
            assertEquals("art1", saved.artifactId());
            assertEquals("EngineSpeed", saved.signalName());
            assertEquals(999L, saved.timestampNanos());
            assertEquals("Braking", saved.eventType());
            assertEquals("Edited title", saved.observation());
            assertEquals(created, saved.createdAt());
            assertEquals(1, store.listBookmarks("p1").size());

            store.deleteBookmark("p1", "bm2");
            assertEquals(1, store.listBookmarks("p2").size());
            assertEquals("bm2", store.listBookmarks("p2").getFirst().bookmarkId());
            store.deleteBookmark("p1", "bm1");
            assertTrue(store.listBookmarks("p1").isEmpty());
            assertEquals(1, store.listBookmarks("p2").size());
        }
    }
}
