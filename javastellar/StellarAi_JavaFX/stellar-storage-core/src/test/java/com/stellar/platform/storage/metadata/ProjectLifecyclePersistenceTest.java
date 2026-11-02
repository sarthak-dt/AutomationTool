package com.stellar.platform.storage.metadata;

import com.stellar.platform.storage.ManagedProjectArtifactStore;
import com.stellar.platform.storage.ProjectDeletionService;
import com.stellar.platform.storage.StellarAiPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectLifecyclePersistenceTest {

    @TempDir Path tempDir;

    @Test
    void rejectsExactCaseAndWhitespaceDuplicateNames() throws Exception {
        Path db = tempDir.resolve("names.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Road Test", now, now));

            assertThrows(DuplicateProjectNameException.class,
                    () -> store.upsertProject(new ProjectMetadata("p2", "Road Test", now, now)));
            assertThrows(DuplicateProjectNameException.class,
                    () -> store.upsertProject(new ProjectMetadata("p3", "road test", now, now)));
            assertThrows(DuplicateProjectNameException.class,
                    () -> store.upsertProject(new ProjectMetadata("p4", "  Road Test  ", now, now)));

            store.upsertProject(new ProjectMetadata("p5", "Road Test 2", now, now));
            assertEquals(2, store.listProjects().size());
        }
    }

    @Test
    void renameToExistingNameFailsWithoutCorruptingSource() throws Exception {
        Path db = tempDir.resolve("rename.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("a", "Alpha", now, now));
            store.upsertProject(new ProjectMetadata("b", "Beta", now, now));
            assertThrows(DuplicateProjectNameException.class,
                    () -> store.upsertProject(new ProjectMetadata("b", "alpha", now, now)));
            assertEquals("Beta", store.loadProject("b").orElseThrow().project().name());
            assertTrue(store.existsProjectName("Alpha", "b"));
            assertFalse(store.existsProjectName("Alpha", "a"));
        }
    }

    @Test
    void migratesV4ToV5AddingNormalizedNameUniqueness() throws Exception {
        Path db = tempDir.resolve("v4.db");
        System.setProperty("stellarai.home", tempDir.resolve("home").toString());
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db)) {
            new com.stellar.platform.storage.sqlite.SchemaMigrator(List.of(
                    new com.stellar.platform.storage.sqlite.MigrationV3Baseline(),
                    new com.stellar.platform.storage.sqlite.MigrationV4EnterpriseKnowledge()
            )).migrate(connection);
            try (var s = connection.createStatement()) {
                s.execute("INSERT INTO projects VALUES('p1','Road Test','2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')");
                s.execute("INSERT INTO projects VALUES('p2','Other','2026-01-02T00:00:00Z','2026-01-02T00:00:00Z')");
                s.execute("INSERT INTO processing_artifacts("
                        + "artifact_id,project_id,source_id,channel_id,definition_id,processing_run_id,"
                        + "artifact_type,artifact_path,artifact_version,source_fingerprint,dbc_fingerprint,"
                        + "signal_count,sample_count,start_timestamp_nanos,end_timestamp_nanos,status,"
                        + "created_at,mapping_lifecycle) VALUES("
                        + "'art1','p1','src',0,'dbc','run','LOSSLESS_SIGNAL_STORE','/tmp/a','1','fp','fp',"
                        + "1,1,1,2,'VALID','2026-01-01T00:00:00Z','CONFIRMED')");
            }
        }

        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertEquals(2, store.listProjects().size());
            assertTrue(store.loadProject("p1").isPresent());
            assertEquals(1, store.loadProject("p1").orElseThrow().processingArtifacts().size());
            assertThrows(DuplicateProjectNameException.class,
                    () -> store.upsertProject(new ProjectMetadata("p3", "road test", Instant.now(), Instant.now())));
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var rs = connection.createStatement().executeQuery("SELECT version FROM schema_info")) {
            assertTrue(rs.next());
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), rs.getInt(1));
        }
    }

    @Test
    void deleteProjectRemovesMetadataAndManagedArtifactsButNotSources() throws Exception {
        Path home = tempDir.resolve("stellar-home");
        System.setProperty("stellarai.home", home.toString());
        Path db = tempDir.resolve("del.db");
        Path mf4 = tempDir.resolve("run.mf4");
        Path dbc = tempDir.resolve("veh.dbc");
        Files.writeString(mf4, "mf4-bytes");
        Files.writeString(dbc, "dbc-bytes");
        Instant now = Instant.now();

        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("proj-del", "Delete Me", now, now));
            store.upsertFile(new ProjectFileMetadata(
                    "proj-del", "src1", mf4.toString(), "MEASUREMENT_MF4", 9,
                    "READY", "", null, "", false, true, false));
            store.upsertFile(new ProjectFileMetadata(
                    "proj-del", "dbc1", dbc.toString(), "DEFINITION_DBC", 9,
                    "READY", "", null, "", false, false, true));
            store.upsertProcessingArtifact(new ProcessingArtifactMetadata(
                    "art1", "proj-del", "src1", 0, "dbc1", "run1",
                    ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                    "", com.stellar.platform.storage.ArtifactStorageRefs.forJob("proj-del", "src1", 0, "dbc1"),
                    "1", "v=2;size=1;mtime=1;name=a", "v=2;size=1;mtime=1;name=b",
                    1, 10, 1L, 2L, ProcessingArtifactMetadata.STATUS_VALID, now, "CONFIRMED"));
            store.replaceSignalCatalog("proj-del", "art1", List.of(new SignalCatalogMetadata(
                    null, "proj-del", "src1", 0, "dbc1", "art1",
                    "S|CAN|M|1|0", "S", "M", 1L, 0, "CAN", "", 10, 1L, 2L, null, null, now, now)));
            store.upsertBookmark(new EngineeringBookmarkMetadata(
                    "bm1", "proj-del", "src1", "art1", "S", "S", 100L, "STEP", "note", now, now));

            Path managed = StellarAiPaths.projectRoot("proj-del");
            Files.createDirectories(managed.resolve("signal-store"));
            Files.writeString(managed.resolve("signal-store").resolve("marker.txt"), "x");

            ProjectDeletionService deletion = new ProjectDeletionService(store, new ManagedProjectArtifactStore());
            ProjectDeletionService.Result result = deletion.deleteByProjectId("proj-del");
            assertTrue(result.metadataDeleted());
            assertTrue(result.artifactsRemoved());
            assertTrue(store.listProjects().isEmpty());
            assertTrue(store.loadProject("proj-del").isEmpty());
            assertFalse(Files.exists(managed));
            assertTrue(Files.isRegularFile(mf4), "original MF4 must survive");
            assertTrue(Files.isRegularFile(dbc), "original DBC must survive");
            assertEquals("mf4-bytes", Files.readString(mf4));
        }
    }

    @Test
    void saveUpdateKeepsSameProjectId() throws Exception {
        Path db = tempDir.resolve("save.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("same", "Durability Test", now, now));
            store.upsertProject(new ProjectMetadata("same", "Durability Test", now, Instant.now()));
            assertEquals(1, store.listProjects().size());
            assertEquals("same", store.listProjects().getFirst().projectId());
        }
    }
}
