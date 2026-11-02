package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sources answers "what measurement data am I analysing?". It must gate Continue to Decode on real
 * usability, isolate per-source problems, never delete anything on disk and never reset unrelated
 * project state.
 *
 * <p>Duplicate detection, filename-vs-path identity and batch failure isolation are owned by
 * {@code ProjectMultiSourceStateTest}; this class covers the presentation contract layered on top.
 */
class SourcesWorkspaceBehaviourTest {

    @TempDir
    Path tempDir;

    private DesktopAppService service;

    @BeforeEach
    void setUp() {
        service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private Path touch(String relative) throws Exception {
        Path path = tempDir.resolve(relative);
        Files.createDirectories(path.getParent());
        com.stellar.platform.desktop.support.TestMeasurementFixtures.writeStubMeasurement(path);
        return path;
    }

    /** A source whose channel discovery has completed, without running any parser. */
    private WorkspaceFile readySource(String name) {
        WorkspaceFile file = service.workspace().addFile(new WorkspaceFile(
                tempDir.resolve(name).toAbsolutePath(), FileKind.MEASUREMENT_MF4, 2048));
        CanMessageInventory message =
                new CanMessageInventory(new CanMessageKey(1, 0x100L, false), 10, 8, 8, false);
        CanChannelInventory channel =
                new CanChannelInventory(1, "Channel 1", 10, 1, 1, 0, 0, List.of(message));
        service.sourceInventoryModel().put(file.id(), new CanSourceInventory(
                file.path(), file.fileName(), MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 1, 10, 1, true, List.of(channel), List.of()));
        return file;
    }

    @Test
    void newProjectSourcesEmptyState() {
        assertTrue(service.workspace().measurementFiles().isEmpty());
        assertTrue(service.usableDecodeSources().isEmpty());
        assertFalse(service.canContinueToDecode(), "an empty project cannot continue to Decode");
    }

    @Test
    void continueToDecodeDisabledWhileASourceIsStillBeingRead() {
        WorkspaceFile pending = service.workspace().addFile(new WorkspaceFile(
                tempDir.resolve("pending.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 2048));

        assertEquals(SourceStatus.READING, service.sourceStatus(pending));
        assertFalse(service.canContinueToDecode());
    }

    @Test
    void usableSourceEnablesContinueToDecode() {
        WorkspaceFile ready = readySource("run01.mf4");

        assertEquals(SourceStatus.READY, service.sourceStatus(ready));
        assertTrue(service.canContinueToDecode());
        assertEquals(1, service.usableDecodeSources().size());
    }

    @Test
    void journeyUsableSourcesUseTheSameDecodeEntryRuleAsContinue() {
        service.newProject("Decode Gate");
        assertFalse(service.canContinueToDecode());
        assertFalse(service.projectJourneyFacts().hasUsableSources());

        WorkspaceFile pending = service.workspace().addFile(new WorkspaceFile(
                tempDir.resolve("pending.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 2048));
        assertEquals(SourceStatus.READING, service.sourceStatus(pending));
        assertFalse(service.canContinueToDecode());
        assertFalse(service.projectJourneyFacts().hasUsableSources(),
                "sidebar Decode must stay disabled until Continue to Decode is valid");

        readySource("run01.mf4");
        assertTrue(service.canContinueToDecode());
        assertTrue(service.projectJourneyFacts().hasUsableSources());
        assertEquals(service.usableDecodeSources().size(), service.projectJourneyFacts().usableSourceCount());
    }

    @Test
    void headerRollupUsesProcessedWithWarningsFromTheSameStatusViewAsFooter() {
        WorkspaceFile ready = readySource("warned.mf4");
        service.rememberSourceProcessingStatusForTest(
                ready.projectSourceId().value(),
                com.stellar.platform.domain.processing.plan.JobExecutionStatus.SUCCESS_WITH_WARNINGS);
        SourceStatusPresenter.View view = service.sourceStatusView(ready);
        assertEquals("COMPLETED", view.footerLabel());
        assertEquals("COMPLETED", SourceWorkspaceHeaderSummary.rollup(java.util.List.of(view)));
    }

    @Test
    void persistedProcessingFingerprintMapsAsProcessed() {
        WorkspaceFile ready = readySource("processed.mf4");
        assertEquals(SourceStatus.READY, service.sourceStatus(ready));

        service.rememberProcessedSourceFingerprintForTest(ready.projectSourceId().value(), "fp");

        assertEquals(SourceStatus.PROCESSED, service.sourceStatus(ready),
                "restored processing artifacts must map to PROCESSED, not READY");
        assertTrue(service.sourceProcessed(ready.projectSourceId()));
    }

    @Test
    void persistedSuccessfulJobMapsZeroChannelSourceAsProcessed() {
        WorkspaceFile ready = readySource("empty-run.mf4");
        assertEquals("READY", service.sourceStatusView(ready).badgeText());

        service.rememberSourceProcessingStatusForTest(
                ready.projectSourceId().value(),
                com.stellar.platform.domain.processing.plan.JobExecutionStatus.SUCCESS);

        assertEquals(SourceStatus.PROCESSED, service.sourceStatus(ready));
        assertEquals("COMPLETED", service.sourceStatusView(ready).badgeText());
        assertEquals("COMPLETED", service.sourceStatusView(ready).footerLabel());
    }

    @Test
    void sourceTableUsesUserFacingStatuses() {
        WorkspaceFile ready = readySource("run01.mf4");
        WorkspaceFile unreadable = service.workspace().addFile(new WorkspaceFile(
                tempDir.resolve("broken.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 128));
        unreadable.setStatus(com.stellar.platform.desktop.project.FileValidationStatus.INVALID, "unreadable");
        WorkspaceFile missing = readySource("moved.mf4");
        service.sourceAvailabilityModel().markUnavailable(missing.projectSourceId());

        assertEquals("Ready", service.sourceStatus(ready).displayLabel());
        assertEquals("Needs Attention", service.sourceStatus(unreadable).displayLabel());
        assertEquals("Unavailable", service.sourceStatus(missing).displayLabel());
    }

    @Test
    void oneProblemSourceDoesNotBlockTheRestOfTheBatch() {
        readySource("healthy.mf4");
        WorkspaceFile broken = service.workspace().addFile(new WorkspaceFile(
                tempDir.resolve("broken.mf4").toAbsolutePath(), FileKind.MEASUREMENT_MF4, 128));
        broken.setStatus(com.stellar.platform.desktop.project.FileValidationStatus.INVALID, "unreadable");

        assertEquals(SourceStatus.NEEDS_ATTENTION, service.sourceStatus(broken));
        assertTrue(service.canContinueToDecode(),
                "one problem source must not stop the project from reaching Decode");
        assertEquals(1, service.usableDecodeSources().size());
    }

    @Test
    void removeSourceDoesNotDeleteExternalFile() throws Exception {
        Path path = touch("keep-me.mf4");
        service.addMeasurementPaths(List.of(path), DesktopAppService.PlatformUi.NOOP);
        WorkspaceFile added = service.workspace().measurementFiles().getFirst();

        assertTrue(service.removeFile(added.id()));

        assertTrue(service.workspace().measurementFiles().isEmpty());
        assertTrue(Files.exists(path), "Remove from Project must never delete the measurement file");
    }

    @Test
    void unavailableSourceShowsDegradedStateInsteadOfReady() {
        WorkspaceFile ready = readySource("gone.mf4");
        assertEquals(SourceStatus.READY, service.sourceStatus(ready));

        service.sourceAvailabilityModel().markUnavailable(ready.projectSourceId());

        assertEquals(SourceStatus.UNAVAILABLE, service.sourceStatus(ready));
        assertFalse(service.canContinueToDecode());
    }

    @Test
    void locatingAMovedSourceKeepsItsProjectIdentity() throws Exception {
        Path original = touch("original/drive.mf4");
        service.addMeasurementPaths(List.of(original), DesktopAppService.PlatformUi.NOOP);
        WorkspaceFile added = service.workspace().measurementFiles().getFirst();
        ProjectSourceId identity = added.projectSourceId();
        service.sourceAvailabilityModel().markUnavailable(identity);

        Path moved = touch("moved/drive.mf4");
        assertTrue(service.relocateSource(identity, moved));

        WorkspaceFile relocated = service.workspace().measurementFiles().getFirst();
        assertEquals(identity, relocated.projectSourceId(),
                "relocation must preserve identity so mappings and artifacts stay attached");
        assertEquals(moved.toAbsolutePath().normalize(), relocated.path());
        assertTrue(service.sourceAvailabilityModel().available(identity));
    }

    @Test
    void addingNewSourceDoesNotResetExistingProcessedSources() throws Exception {
        WorkspaceFile existing = readySource("run01.mf4");
        CanSourceInventory existingInventory =
                service.sourceInventoryModel().inventoryFor(existing.id()).orElseThrow();

        service.addMeasurementPaths(List.of(touch("run02.mf4")), DesktopAppService.PlatformUi.NOOP);

        assertEquals(existingInventory,
                service.sourceInventoryModel().inventoryFor(existing.id()).orElseThrow(),
                "adding a source must not discard an existing source's discovered channels");
        assertEquals(SourceStatus.READY, service.sourceStatus(existing));
        assertEquals(2, service.workspace().measurementFiles().size());
    }

    @Test
    void sourcesDoesNotTriggerProcessingOrGraphLoading() throws Exception {
        service.addMeasurementPaths(List.of(touch("run.mf4")), DesktopAppService.PlatformUi.NOOP);

        assertTrue(service.processingResultRegistry().all().isEmpty(),
                "adding a source must not execute processing");
        assertTrue(service.signalsModel().catalog().entries().isEmpty(),
                "adding a source must not load a signal catalog");
        assertTrue(service.signalsModel().workspaceKeys().isEmpty(),
                "adding a source must not prepare graph workspace data");
    }

    @Test
    void resumeRoutingDoesNotProbeTheFileSystem() {
        readySource("run01.mf4");
        service.sourceAvailabilityModel().clear();

        service.resolveResumeRoute();

        assertEquals(0, service.sourceAvailabilityModel().unavailableCount(),
                "resume routing must stay on cheap resident state");
    }
}
