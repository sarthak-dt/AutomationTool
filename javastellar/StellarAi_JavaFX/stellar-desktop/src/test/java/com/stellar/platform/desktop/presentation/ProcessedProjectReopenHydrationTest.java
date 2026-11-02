package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.storage.metadata.ChannelMappingMetadata;
import com.stellar.platform.storage.metadata.FileFingerprint;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reopen restores paths, inventories and counts only. It must not parse measurements
 * or reload decoded signal samples.
 */
class ProcessedProjectReopenHydrationTest {

    @TempDir Path tempDir;

    @Test
    void reopenRestoresPathsAndMappingsWithoutReloadingSamples() throws Exception {
        DesktopAppService service = openVerified("saved.mf4", "MEASUREMENT_MF4", new AtomicInteger());
        assertTrue(service.signalsModel().catalog().entries().isEmpty(),
                "reopen must not hydrate decoded samples");
        assertTrue(service.processingResultRegistry().all().isEmpty(),
                "session signal handles must not be restored");
        assertTrue(service.canProceedFromDecisionWorkspace());
        assertTrue(service.projectJourneyState() == ProjectJourneyState.READY_TO_PROCESS
                        || service.projectJourneyState() == ProjectJourneyState.DECODE_SETUP_REQUIRED,
                service.projectJourneyState().name());
        assertFalse(service.projectJourneyState() == ProjectJourneyState.ANALYSIS_READY);
        service.shutdown();
    }

    @Test
    void reopenUnchangedMf4DoesNotReparse() throws Exception {
        AtomicInteger parses = new AtomicInteger();
        DesktopAppService service = openVerified("saved.mf4", "MEASUREMENT_MF4", parses);
        assertEquals(0, parses.get(), "reopen must not reparse an unchanged MF4");
        service.shutdown();
    }

    @Test
    void reopenUnchangedBlfDoesNotReparse() throws Exception {
        AtomicInteger parses = new AtomicInteger();
        DesktopAppService service = openVerified("saved.blf", "MEASUREMENT_BLF", parses);
        assertEquals(0, parses.get());
        service.shutdown();
    }

    @Test
    void reopenUnchangedAscDoesNotReparse() throws Exception {
        AtomicInteger parses = new AtomicInteger();
        DesktopAppService service = openVerified("saved.asc", "MEASUREMENT_ASC", parses);
        assertEquals(0, parses.get());
        service.shutdown();
    }

    @Test
    void reopenDoesNotExecuteProcessing() throws Exception {
        AtomicInteger parses = new AtomicInteger();
        DesktopAppService service = openVerified("saved.mf4", "MEASUREMENT_MF4", parses);
        assertTrue(service.processingResultRegistry().all().isEmpty());
        service.resolveResumeRoute();
        assertTrue(service.processingResultRegistry().all().isEmpty());
        assertFalse(service.isPlanExecutionActive());
        service.shutdown();
    }

    @Test
    void missingSourceIsReportedAndDoesNotFakeAnalysis() throws Exception {
        Path source = tempDir.resolve("gone.mf4");
        Path dbc = tempDir.resolve("veh.dbc");
        Files.writeString(source, "stub-measurement");
        Files.writeString(dbc, "VERSION \"\"");
        Instant created = Instant.now();
        SavedProjectSnapshot snapshot = snapshotFor("project-1", source, dbc, created);
        Files.delete(source);
        AtomicInteger parses = new AtomicInteger();
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                (sourceId, path, ctx, listener) -> {
                    parses.incrementAndGet();
                    throw new IllegalStateException("must not parse");
                },
                new SnapshotStore(snapshot));
        DesktopAppService.ProjectOpenResult opened = service.openSavedProject("project-1");
        assertTrue(opened.opened());
        assertTrue(service.signalsModel().catalog().entries().isEmpty());
        assertEquals(0, parses.get());
        assertTrue(opened.warnings().stream().anyMatch(w -> w.toLowerCase().contains("no longer available")
                || w.toLowerCase().contains("missing")));
        assertFalse(service.projectJourneyState() == ProjectJourneyState.ANALYSIS_READY);
        service.shutdown();
    }

    @Test
    void missingArtifactStillRestoresMappingsAndDoesNotCrash() throws Exception {
        Path source = tempDir.resolve("saved.mf4");
        Path dbc = tempDir.resolve("veh.dbc");
        Files.writeString(source, "stub");
        Files.writeString(dbc, "VERSION \"\"");
        Instant created = Instant.now();
        CanChannelInventory channel = new CanChannelInventory(
                1, "Channel 1", 10, 1, 1, 0, 0,
                List.of(new CanMessageInventory(new CanMessageKey(1, 0x10, false), 10, 8, 8, false)));
        CanSourceInventory inventory = new CanSourceInventory(
                source, "saved.mf4", MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                1, 10, 1, true, List.of(channel), List.of());
        ProcessingArtifactMetadata missing = new ProcessingArtifactMetadata(
                "a1", "project-1", "source-1", 1, "dbc-1", "run-1",
                "SIGNAL_CATALOG_COUNTS",
                "",
                "counts://project-1/source-1/1/dbc-1",
                "1", FileFingerprint.of(source), FileFingerprint.of(dbc),
                0, 0, null, null, "VALID", created, "CONFIRMED");
        SavedProjectSnapshot snapshot = new SavedProjectSnapshot(
                new ProjectMetadata("project-1", "P", created, created),
                List.of(
                        new ProjectFileMetadata("project-1", "source-1", source.toString(), "MEASUREMENT_MF4",
                                Files.size(source), "READY", "", null, "", false, true, false),
                        new ProjectFileMetadata("project-1", "dbc-1", dbc.toString(), "DEFINITION_DBC",
                                Files.size(dbc), "READY", "", null, "", false, false, true)
                ),
                Map.of("source-1", inventory),
                List.of(new ChannelMappingMetadata(
                        "project-1", "source-1", 1, "CONFIRMED", "CLEAR_BEST_MATCH", "dbc-1", "dbc-1",
                        1, 1, 1.0)),
                List.of(new ProcessingRunMetadata("run-1", "project-1", "COMPLETED", false, 1,
                        created, created, "")),
                List.of(missing)
        );

        DesktopAppService service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()), new SnapshotStore(snapshot));
        DesktopAppService.ProjectOpenResult opened = service.openSavedProject("project-1");
        assertTrue(opened.opened());
        assertTrue(service.signalsModel().catalog().entries().isEmpty());
        assertTrue(service.canProceedFromDecisionWorkspace(),
                "Valid mappings must keep Proceed enabled when samples were not reloaded");
        service.shutdown();
    }

    @Test
    void projectSwitchDoesNotLoadOrShareSignalStores() throws Exception {
        Path sourceA = tempDir.resolve("a.mf4");
        Path sourceB = tempDir.resolve("b.mf4");
        Path dbc = tempDir.resolve("veh.dbc");
        Files.writeString(sourceA, "a");
        Files.writeString(sourceB, "b");
        Files.writeString(dbc, "VERSION \"\"");

        Instant now = Instant.now();
        List<String> loadedIds = new java.util.ArrayList<>();
        ProjectMetadataStore store = new ProjectMetadataStore() {
            @Override public List<ProjectMetadata> listProjects() {
                return List.of(
                        new ProjectMetadata("a", "A", now, now),
                        new ProjectMetadata("b", "B", now, now));
            }
            @Override public Optional<SavedProjectSnapshot> loadProject(String projectId) {
                loadedIds.add(projectId);
                return Optional.of(snapshotFor(projectId, projectId.equals("a") ? sourceA : sourceB, dbc, now));
            }
            @Override public void upsertProject(ProjectMetadata project) { }
            @Override public void upsertFile(ProjectFileMetadata file) { }
            @Override public void deleteFile(String projectId, String fileId) { }
            @Override public void saveInventory(String projectId, String sourceId, CanSourceInventory inventory) { }
            @Override public void upsertChannelMapping(ChannelMappingMetadata mapping) { }
            @Override public void deleteChannelMapping(String projectId, String sourceId, int channelId) { }
            @Override public void upsertProcessingRun(ProcessingRunMetadata run) { }
        };

        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()), store);
        assertTrue(service.openSavedProject("a").opened());
        assertEquals(sourceA.toAbsolutePath().normalize(),
                service.workspace().measurementFiles().getFirst().path());
        assertTrue(service.signalsModel().catalog().entries().isEmpty());
        assertTrue(service.openSavedProject("b").opened());
        assertEquals(sourceB.toAbsolutePath().normalize(),
                service.workspace().measurementFiles().getFirst().path());
        assertTrue(service.signalsModel().catalog().entries().isEmpty());
        // Each open reads its own snapshot, then reads that same project again after plan
        // seeding so a stale artifact row is not reused. Opening B must not read A again.
        assertEquals(List.of("a", "a", "b", "b"), loadedIds);
        service.shutdown();
    }

    private DesktopAppService openVerified(String fileName, String kind, AtomicInteger parses) throws Exception {
        Path source = tempDir.resolve(fileName);
        Path dbc = tempDir.resolve("veh.dbc");
        Files.writeString(source, "stub-measurement");
        Files.writeString(dbc, "VERSION \"\"");
        Instant created = Instant.now();
        SavedProjectSnapshot snapshot = new SavedProjectSnapshot(
                new ProjectMetadata("project-1", "Saved Road Test", created, created),
                List.of(
                        new ProjectFileMetadata("project-1", "source-1", source.toString(), kind,
                                Files.size(source), "READY", "", null, "Finalized", false, true, false),
                        new ProjectFileMetadata("project-1", "dbc-1", dbc.toString(), "DEFINITION_DBC",
                                Files.size(dbc), "READY", "", null, "", false, false, true)
                ),
                Map.of("source-1", new CanSourceInventory(
                        source, fileName, kind.contains("BLF") ? MeasurementSourceFormat.BLF
                        : kind.contains("ASC") ? MeasurementSourceFormat.ASC : MeasurementSourceFormat.MF4,
                        CanInventoryStatus.INVENTORY_READY, 1, 20, 1, true,
                        List.of(new CanChannelInventory(1, "Channel 1", 20, 1, 1, 0, 0,
                                List.of(new CanMessageInventory(new CanMessageKey(1, 0x123, false), 20, 8, 8, false)))),
                        List.of())),
                List.of(new ChannelMappingMetadata(
                        "project-1", "source-1", 1, "CONFIRMED", "CLEAR_BEST_MATCH", "dbc-1", "dbc-1",
                        1, 1, 1.0)),
                List.of(new ProcessingRunMetadata("run-1", "project-1", "COMPLETED", false, 1, created, created, "")),
                List.of(new ProcessingArtifactMetadata(
                        "project-1|source-1|1|dbc-1", "project-1", "source-1", 1, "dbc-1", "run-1",
                        "SIGNAL_CATALOG_COUNTS", "", "counts://project-1/source-1/1/dbc-1",
                        ProcessingArtifactMetadata.ARTIFACT_VERSION_V1,
                        FileFingerprint.of(source), FileFingerprint.of(dbc),
                        1, 2, 10L, 20L, ProcessingArtifactMetadata.STATUS_VALID, created, "CONFIRMED"))
        );
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                (sourceId, path, ctx, listener) -> {
                    parses.incrementAndGet();
                    throw new IllegalStateException("reopen must not inventory/parse " + path);
                },
                new SnapshotStore(snapshot));
        assertTrue(service.openSavedProject("project-1").opened());
        return service;
    }

    private static SavedProjectSnapshot snapshotFor(String projectId, Path source, Path dbc, Instant now) {
        try {
            return new SavedProjectSnapshot(
                    new ProjectMetadata(projectId, projectId, now, now),
                    List.of(
                            new ProjectFileMetadata(projectId, "source-1", source.toString(), "MEASUREMENT_MF4",
                                    Files.size(source), "READY", "", null, "", false, true, false),
                            new ProjectFileMetadata(projectId, "dbc-1", dbc.toString(), "DEFINITION_DBC",
                                    Files.size(dbc), "READY", "", null, "", false, false, true)
                    ),
                    Map.of("source-1", new CanSourceInventory(
                            source, source.getFileName().toString(), MeasurementSourceFormat.MF4,
                            CanInventoryStatus.INVENTORY_READY, 1, 1, 1, true,
                            List.of(new CanChannelInventory(1, "Ch1", 1, 1, 1, 0, 0,
                                    List.of(new CanMessageInventory(
                                            new CanMessageKey(1, 0x1, false), 1, 8, 8, false)))),
                            List.of())),
                    List.of(new ChannelMappingMetadata(
                            projectId, "source-1", 1, "CONFIRMED", "CLEAR_BEST_MATCH", "dbc-1", "dbc-1",
                            1, 1, 1.0)),
                    List.of(new ProcessingRunMetadata("run", projectId, "COMPLETED", false, 1, now, now, "")),
                    List.of(new ProcessingArtifactMetadata(
                            projectId + "|source-1|1|dbc-1", projectId, "source-1", 1, "dbc-1", "run",
                            "SIGNAL_CATALOG_COUNTS", "", "counts://" + projectId + "/source-1/1/dbc-1", "1",
                            FileFingerprint.of(source), FileFingerprint.of(dbc), 1, 1, 1L, 1L, "VALID", now,
                            "CONFIRMED"))
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
