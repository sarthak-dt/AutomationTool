package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.storage.metadata.ChannelMappingMetadata;
import com.stellar.platform.storage.metadata.ProcessingRunMetadata;
import com.stellar.platform.storage.metadata.ProjectFileMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopAppServiceProjectRestoreTest {

    @TempDir Path tempDir;

    @Test
    void opensSavedProjectWithStableFilesInventoryAndProcessingAuditWithoutSignalSamples() throws Exception {
        Path source = tempDir.resolve("saved.mf4");
        Files.writeString(source, "stub");
        Instant created = Instant.parse("2026-08-26T10:00:00Z");
        ProjectMetadata project = new ProjectMetadata("project-1", "Saved Road Test", created, created);
        ProjectFileMetadata file = new ProjectFileMetadata(
                "project-1", "source-1", source.toString(), "MEASUREMENT_MF4", Files.size(source),
                "READY", "", null, "Finalized", false, true, false);
        CanMessageInventory message = new CanMessageInventory(
                new CanMessageKey(1, 0x123, false), 20, 8, 8, false);
        CanChannelInventory channel = new CanChannelInventory(
                1, "Channel 1", 20, 1, 1, 0, 0, List.of(message));
        CanSourceInventory inventory = new CanSourceInventory(
                source, source.getFileName().toString(), MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 1, 20, 1, true, List.of(channel), List.of());
        ProcessingRunMetadata run = new ProcessingRunMetadata(
                "run-1", "project-1", "COMPLETED", false, 1,
                created, created.plusSeconds(2), "");
        SavedProjectSnapshot snapshot = new SavedProjectSnapshot(
                project, List.of(file), Map.of("source-1", inventory), List.of(), List.of(run));

        ProjectMetadataStore store = new SnapshotStore(snapshot);
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()), store);

        DesktopAppService.ProjectOpenResult result = service.openSavedProject("project-1");

        assertTrue(result.opened());
        assertEquals("Saved Road Test", service.workspace().projectName());
        assertEquals("source-1", service.workspace().measurementFiles().getFirst().id());
        assertEquals("source-1", service.workspace().selectedMeasurement().orElseThrow().id());
        assertEquals(1, service.sourceInventoryModel().inventoryFor("source-1").orElseThrow().channels().size());
        assertEquals(ProjectInventoryJobState.READY,
                service.inventoryJobState(service.workspace().measurementFiles().getFirst().projectSourceId()));
        assertEquals(ProjectProcessingStatus.COMPLETED, service.workspace().processingStatus());
        assertEquals("run-1", service.latestPersistedProcessingRun().orElseThrow().runId());
        assertTrue(service.signalsModel().catalog().entries().isEmpty(),
                "Metadata-only snapshot without processing artifacts must not invent signal samples");
        // Processing plan can still be rebuilt from inventory/mappings when channels are processable.
        service.shutdown();
    }

    private static final class SnapshotStore implements ProjectMetadataStore {
        private final SavedProjectSnapshot snapshot;
        private SnapshotStore(SavedProjectSnapshot snapshot) { this.snapshot = snapshot; }
        @Override public List<ProjectMetadata> listProjects() { return List.of(snapshot.project()); }
        @Override public Optional<SavedProjectSnapshot> loadProject(String projectId) {
            return snapshot.project().projectId().equals(projectId) ? Optional.of(snapshot) : Optional.empty();
        }
        @Override public void upsertProject(ProjectMetadata project) { }
        @Override public void upsertFile(ProjectFileMetadata file) { }
        @Override public void deleteFile(String projectId, String fileId) { }
        @Override public void saveInventory(String projectId, String sourceId, CanSourceInventory inventory) { }
        @Override public void upsertChannelMapping(ChannelMappingMetadata mapping) { }
        @Override public void deleteChannelMapping(String projectId, String sourceId, int channelId) { }
        @Override public void upsertProcessingRun(ProcessingRunMetadata run) { }
    }
}
