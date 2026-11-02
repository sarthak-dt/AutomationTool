package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.DesktopFixtures;
import com.stellar.platform.desktop.support.TestMeasurementFixtures;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import com.stellar.platform.storage.metadata.ProjectFileMetadata;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BD-24: a direct DAT and a decoded ASC share one project. Saving while the DAT is the
 * selected measurement and the ASC DBC is the selected definition must not attach that
 * DBC to the DAT, and must not drop the DAT signal artifact on reopen.
 */
class MixedSourceReopenIntegrityTest {

    @TempDir
    Path tempDir;

    private DesktopAppService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    @Test
    void directArtifactAndAssociationSurviveReopenBesideADecodedAsc() throws Exception {
        Path db = tempDir.resolve("meta.db");
        Path dat = TestMeasurementFixtures.validMinimalDat(tempDir.resolve("capture.dat"));
        Path asc = DesktopFixtures.copyResource("asc/desktop_engine.asc", tempDir.resolve("desktop_engine.asc"));
        Path dbc = DesktopFixtures.copyResource("dbc/desktop_engine.dbc", tempDir.resolve("desktop_engine.dbc"));

        service = open(db);
        service.newProject("BD-24 mixed reopen");
        service.addMeasurementPaths(List.of(dat), DesktopAppService.PlatformUi.NOOP);
        WorkspaceFile datFile = measurement(service, ".dat");
        service.selectMeasurement(datFile.id());
        processUntilTerminal(service);
        assertEquals(ProjectProcessingStatus.COMPLETED, service.workspace().processingStatus());

        service.addMeasurementPaths(List.of(asc), DesktopAppService.PlatformUi.NOOP);
        service.addDefinitionPaths(List.of(dbc));
        WorkspaceFile ascFile = measurement(service, ".asc");
        WorkspaceFile definition = service.workspace().definitionFiles().getFirst();
        service.selectMeasurement(ascFile.id());
        service.selectDefinition(definition.id());
        assertEquals(definition.id(), ascFile.associatedDefinitionId());
        assertNull(measurement(service, ".dat").associatedDefinitionId());
        waitForInventory(service);
        service.syncChannelSelection();
        service.channelSelectionModel().selectAll(service.sourceInventoryModel().focusedInventory().orElseThrow());
        service.prepareProcessingFromDecisionWorkspace();
        service.enterProcessingWorkspaceFromDecision();
        assertNotNull(service.processingSelectionModel().reviewedPlan());
        assertFalse(service.processingSelectionModel().reviewedPlan().isEmpty(), "ASC was not included in the processing plan");
        service.executeReviewedPlan(Runnable::run, () -> { });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (service.isPlanExecutionActive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(service.isPlanExecutionActive(), "ASC plan did not finish");

        service.selectMeasurement(datFile.id());
        assertNull(measurement(service, ".dat").associatedDefinitionId(),
                "focusing the DAT must not copy the selected DBC onto it");
        assertEquals(definition.id(), measurement(service, ".asc").associatedDefinitionId());

        long datSamplesBefore = sqlSampleCount(service, datFile.id(), "Speed");
        int catalogRowsBefore = service.metadataStore().listSignalCatalog(service.currentProjectId()).size();
        int artifactsBefore = validArtifacts(service);
        int runsBefore = service.persistedProcessingRuns().size();
        assertTrue(datSamplesBefore > 0, "persisted DAT samples missing before save");
        assertTrue(catalogRowsBefore > 1, "persisted catalog should contain the DAT and the ASC");
        assertTrue(artifactsBefore >= 2, "expected a direct artifact and a CAN artifact");

        SavedProjectSnapshot beforeSave = service.metadataStore().loadProject(service.currentProjectId()).orElseThrow();
        assertNull(association(beforeSave, datFile.id()));
        assertEquals(definition.id(), association(beforeSave, ascFile.id()));
        assertTrue(beforeSave.files().stream().anyMatch(file -> file.fileId().equals(datFile.id()) && file.selectedMeasurement()));
        assertTrue(beforeSave.files().stream().anyMatch(file -> file.fileId().equals(definition.id()) && file.selectedDefinition()));

        assertTrue(service.saveProject().saved());
        SavedProjectSnapshot afterSave = service.metadataStore().loadProject(service.currentProjectId()).orElseThrow();
        assertNull(association(afterSave, datFile.id()));
        assertEquals(definition.id(), association(afterSave, ascFile.id()));
        assertEquals(catalogRowsBefore, service.metadataStore().listSignalCatalog(service.currentProjectId()).size());
        assertEquals(artifactsBefore, validArtifacts(service));

        String projectId = service.currentProjectId();
        service.shutdown();
        service = null;

        service = open(db);
        assertTrue(service.openSavedProject(projectId).opened());
        WorkspaceFile reopenedDat = measurement(service, ".dat");
        WorkspaceFile reopenedAsc = measurement(service, ".asc");
        assertEquals(FileKind.MEASUREMENT_DAT, reopenedDat.kind());
        assertNull(reopenedDat.associatedDefinitionId(), "reopen attached the selected DBC to the DAT");
        assertEquals(definition.id(), reopenedAsc.associatedDefinitionId());
        assertEquals(datSamplesBefore, sampleCount(service, "Speed", reopenedDat.id()),
                "DAT samples were not restored into the reopened catalog");
        assertTrue(service.signalsModel().catalog().entries().size() > 1, "ASC catalog was dropped on reopen");
        assertEquals(catalogRowsBefore, service.metadataStore().listSignalCatalog(projectId).size());
        assertEquals(artifactsBefore, validArtifacts(service));
        assertEquals(runsBefore, service.persistedProcessingRuns().size(), "reopen started another processing run");
        assertTrue(service.graphInvestigation().unavailableTraces().isEmpty(),
                service.graphInvestigation().unavailableTraces().toString());

        SavedProjectSnapshot afterReopen = service.metadataStore().loadProject(projectId).orElseThrow();
        assertNull(association(afterReopen, reopenedDat.id()));
        assertEquals(definition.id(), association(afterReopen, reopenedAsc.id()));
    }

    private static String association(SavedProjectSnapshot snapshot, String fileId) {
        ProjectFileMetadata file = snapshot.files().stream()
                .filter(stored -> stored.fileId().equals(fileId))
                .findFirst()
                .orElse(null);
        if (file == null) {
            return "missing";
        }
        return file.associatedDefinitionId();
    }

    private static long sqlSampleCount(DesktopAppService opened, String sourceId, String signalName) {
        return opened.metadataStore().listSignalCatalog(opened.currentProjectId()).stream()
                .filter(row -> sourceId.equals(row.sourceId()) && signalName.equals(row.signalName()))
                .mapToLong(row -> row.sampleCount())
                .sum();
    }

    private static int validArtifacts(DesktopAppService opened) {
        return opened.metadataStore().loadProject(opened.currentProjectId()).orElseThrow()
                .processingArtifacts().stream()
                .filter(artifact -> ProcessingArtifactMetadata.STATUS_VALID.equals(artifact.status()))
                .toList()
                .size();
    }

    private static long sampleCount(DesktopAppService opened, String signalName, String sourceId) {
        if (!(opened.signalsModel().catalogStore() instanceof ProjectMergedSignalStore merged)) {
            return 0;
        }
        return merged.physicalCatalogAll().stream()
                .filter(entry -> signalName.equals(entry.signalName()))
                .filter(entry -> sourceId.equals(entry.sourceFile()) || sourceId.equals(entry.key().sourceFile()))
                .mapToLong(SignalCatalogEntry::sampleCount)
                .sum();
    }

    private static WorkspaceFile measurement(DesktopAppService opened, String suffix) {
        return opened.workspace().measurementFiles().stream()
                .filter(file -> file.fileName().toLowerCase(java.util.Locale.ROOT).endsWith(suffix))
                .findFirst()
                .orElseThrow();
    }

    private static void waitForInventory(DesktopAppService target) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            var inventory = target.sourceInventoryModel().focusedInventory().orElse(null);
            if (inventory != null && inventory.channelDiscoveryComplete() && !inventory.channels().isEmpty()) {
                return;
            }
            Thread.sleep(50);
        }
        assertNotNull(target.sourceInventoryModel().focusedInventory().orElse(null), "ASC inventory did not become ready");
    }

    private static void processUntilTerminal(DesktopAppService target) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);
        target.startLocalProcessing(DesktopAppService.PlatformUi.NOOP, () -> {
            ProjectProcessingStatus status = target.workspace().processingStatus();
            if (!finished.get() && (status == ProjectProcessingStatus.COMPLETED
                    || status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS
                    || status == ProjectProcessingStatus.FAILED
                    || status == ProjectProcessingStatus.CANCELLED)) {
                finished.set(true);
                done.countDown();
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "processing did not finish");
    }

    private DesktopAppService open(Path db) {
        System.setProperty("stellarai.home", tempDir.resolve("home").toString());
        System.setProperty("stellarai.metadata.db", db.toString());
        return new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
    }
}
