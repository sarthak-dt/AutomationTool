package com.stellar.platform.storage.metadata;

import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteProjectMetadataStoreTest {
    @TempDir Path tempDir;

    @Test
    void persistsOnlyProjectMetadataAndNeverCreatesSignalSampleTables() throws Exception {
        Path db = tempDir.resolve("stellar.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            Instant now = Instant.now();
            store.upsertProject(new ProjectMetadata("p1", "Road Test", now, now));
            store.upsertFile(new ProjectFileMetadata(
                    "p1", "src1", tempDir.resolve("run.mf4").toString(), "MEASUREMENT_MF4", 1234,
                    "READY", "", null, "", false, true, false));

            CanMessageInventory message = new CanMessageInventory(
                    new CanMessageKey(0, 0x7E8, false), 100, 8, 8, false);
            CanChannelInventory channel = new CanChannelInventory(
                    0, "CAN 1", 100, 1, 1, 0, 0, List.of(message));
            CanSourceInventory inventory = new CanSourceInventory(
                    tempDir.resolve("run.mf4"), "run.mf4", MeasurementSourceFormat.MF4,
                    CanInventoryStatus.INVENTORY_READY, 1, 100, 1, true, List.of(channel), List.of());
            store.saveInventory("p1", "src1", inventory);
            store.upsertChannelMapping(new ChannelMappingMetadata(
                    "p1", "src1", 0, "CONFIRMED", "CLEAR_BEST_MATCH", "dbc1", "dbc1", 1, 1, 1.0));
            store.upsertProcessingRun(new ProcessingRunMetadata(
                    "run1", "p1", "COMPLETED", false, 1, now, Instant.now(), ""));
            store.upsertProcessingArtifact(new ProcessingArtifactMetadata(
                    "art1", "p1", "src1", 0, "dbc1", "run1",
                    ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                    tempDir.resolve("signal-store").toString(),
                    ProcessingArtifactMetadata.ARTIFACT_VERSION_V1,
                    "size=1;mtime=1;path=x",
                    "size=2;mtime=2;path=y",
                    3, 1000, 1L, 2L,
                    ProcessingArtifactMetadata.STATUS_VALID,
                    now,
                    "CONFIRMED"
            ));

            assertTrue(store.tableExists("projects"));
            assertTrue(store.tableExists("project_files"));
            assertTrue(store.tableExists("source_inventory"));
            assertTrue(store.tableExists("channel_inventory"));
            assertTrue(store.tableExists("channel_messages"));
            assertTrue(store.tableExists("channel_mappings"));
            assertTrue(store.tableExists("processing_runs"));
            assertTrue(store.tableExists("processing_artifacts"));
            assertTrue(store.tableExists("signal_catalog"));
            assertTrue(store.tableExists("engineering_bookmarks"));
            assertTrue(store.tableExists("data_quality_findings"));
            assertFalse(store.tableExists("signal_samples"));
            assertFalse(store.tableExists("signals"));
            assertFalse(store.tableExists("raw_signal_values"));
            assertFalse(store.tableExists("waveform_values"));
            assertFalse(store.tableExists("waveform_samples"));
            assertFalse(store.tableExists("raw_can_frames"));

            assertEquals(1, store.listProjects().size());
            List<ProjectDashboardSummary> summaries = store.listProjectDashboardSummaries();
            assertEquals(1, summaries.size());
            assertEquals(1, store.countProjects());
            assertEquals(summaries.getFirst().signalCount(), store.signalCatalogCount("p1"));
            assertEquals("p1", store.latestUpdatedProjectId().orElseThrow());
            assertEquals("Road Test", summaries.getFirst().name());
            assertEquals(1, summaries.getFirst().measurementFileCount());
            assertEquals(0, summaries.getFirst().definitionFileCount());
            assertEquals("COMPLETED", summaries.getFirst().lastProcessingStatus());
            assertEquals(100L, summaries.getFirst().canFrameCount());
            assertTrue(summaries.getFirst().fileTypesLabel().contains("MF4"));
            assertEquals(1, summaries.getFirst().inventorySourceCount());
            SavedProjectSnapshot restored = store.loadProject("p1").orElseThrow();
            assertEquals("Road Test", restored.project().name());
            assertEquals(1, restored.files().size());
            assertEquals(1, restored.inventories().size());
            assertEquals(1, restored.inventories().get("src1").channels().size());
            assertEquals(1, restored.inventories().get("src1").channels().get(0).messages().size());
            assertEquals(0x7E8L, restored.inventories().get("src1").channels().get(0).messages().get(0).canId());
            assertEquals(1, restored.channelMappings().size());
            assertEquals(1, restored.processingRuns().size());
            assertEquals(1, restored.processingArtifacts().size());
            assertEquals(3, restored.processingArtifacts().getFirst().signalCount());
        }

        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + db);
             var statement = connection.createStatement()) {
            try (var rs = statement.executeQuery("SELECT count(*) FROM project_files WHERE project_id='p1'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
            try (var rs = statement.executeQuery("SELECT total_can_frames FROM source_inventory WHERE project_id='p1' AND source_id='src1'")) {
                assertTrue(rs.next());
                assertEquals(100L, rs.getLong(1));
            }
        }
    }
}
