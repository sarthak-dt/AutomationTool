package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.MeasurementSourceCapability;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.testsupport.DesktopTestIsolation;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.storage.metadata.ProjectDashboardSummary;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DAT and LOG are direct-processing projects. Opening them must not require a DBC or
 * land on Decode &amp; Process. MF4 keeps the existing decode workflow.
 */
class DirectSourceWorkflowTest {

    @TempDir
    Path tempDir;
    private DesktopAppService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
        DesktopTestIsolation.resetProcessState();
        System.clearProperty("stellarai.home");
    }

    @Test
    void datProjectNextStepIsDirectProcessingAndDoesNotRequestADefinition() {
        ProjectJourneyFacts facts = summaryFacts("DAT", 1, 0, "", 0);
        assertEquals(ProjectJourneyState.DIRECT_PROCESS, ProjectJourneyStateResolver.resolve(facts));
        assertEquals("Direct Processing", ProjectJourneyStateResolver.resolve(facts).primaryActionLabel());
        assertEquals(ProjectNextStep.DIRECT_PROCESS, ProjectNextStepResolver.nextStep(facts));
        assertFalse(ProjectNextStepResolver.nextStep(facts).requestsDefinition());
        assertEquals(WorkspaceRoute.SOURCES, resume(facts, null));
        assertEquals(WorkspaceRoute.SOURCES, resume(facts, WorkspaceRoute.DECODE));
        assertFalse(facts.decodeWorkspaceApplicable());
    }

    @Test
    void logProjectNextStepIsDirectProcessingAndDoesNotRequestADefinition() {
        ProjectJourneyFacts facts = summaryFacts("LOG", 1, 0, "", 0);
        assertEquals(ProjectJourneyState.DIRECT_PROCESS, ProjectJourneyStateResolver.resolve(facts));
        assertEquals(ProjectNextStep.DIRECT_PROCESS, ProjectNextStepResolver.nextStep(facts));
        assertFalse(ProjectNextStepResolver.nextStep(facts).requestsDefinition());
        assertNotEquals(WorkspaceRoute.DECODE, resume(facts, WorkspaceRoute.DECODE));
    }

    @Test
    void processedDatAndLogOpenSignalsEvenWhenDecodeWasTheSavedPage() {
        ProjectJourneyFacts dat = summaryFacts("DAT", 1, 3, ProcessingRunStatuses.COMPLETED, 0);
        ProjectJourneyFacts log = summaryFacts("LOG", 1, 2, ProcessingRunStatuses.COMPLETED, 0);
        for (ProjectJourneyFacts facts : List.of(dat, log)) {
            assertEquals(ProjectJourneyState.ANALYSIS_READY, ProjectJourneyStateResolver.resolve(facts));
            assertEquals(ProjectNextStep.SIGNALS, ProjectNextStepResolver.nextStep(facts));
            assertEquals(WorkspaceRoute.SIGNALS, resume(facts, null));
            assertEquals(WorkspaceRoute.SIGNALS, resume(facts, WorkspaceRoute.DECODE));
            assertFalse(ProjectNextStepResolver.nextStep(facts).requestsDefinition());
            assertTrue(facts.analysisDataReady());
            assertFalse(facts.decodeWorkspaceApplicable());
        }
    }

    @Test
    void processedDirectProjectRestoresGraphsWhenThatWasTheSavedDownstreamScreen() {
        ProjectJourneyFacts facts = new ProjectJourneyFacts(
                true, 1, 1, false, false, false, false, true, true, false, true, false, false);
        assertEquals(WorkspaceRoute.GRAPHS, resume(facts, WorkspaceRoute.GRAPHS));
        assertEquals(WorkspaceRoute.GRAPHS, resume(facts, WorkspaceRoute.DECODE));
        assertEquals(WorkspaceRoute.INVESTIGATIONS, resume(facts, WorkspaceRoute.INVESTIGATIONS));
    }

    @Test
    void completedDirectProjectWithNoSignalsDoesNotAskForADbc() {
        ProjectJourneyFacts facts = summaryFacts("DAT", 1, 0, ProcessingRunStatuses.COMPLETED, 0);
        assertEquals(ProjectJourneyState.DIRECT_RESULT, ProjectJourneyStateResolver.resolve(facts));
        assertEquals(ProjectNextStep.DIRECT_RESULT, ProjectNextStepResolver.nextStep(facts));
        assertFalse(ProjectNextStepResolver.nextStep(facts).requestsDefinition());
        assertEquals(WorkspaceRoute.SOURCES, resume(facts, WorkspaceRoute.DECODE));
        assertNotEquals(WorkspaceRoute.DECODE, resume(facts, null));
    }

    @Test
    void mixedMf4AndDatDoesNotForceDecodeWhileTheDatIsUnprocessed() {
        ProjectJourneyFacts mixed = summaryFacts("DAT · MF4", 2, 0, "", 0);
        assertEquals(ProjectJourneyState.DIRECT_PROCESS, ProjectJourneyStateResolver.resolve(mixed));
        assertEquals(ProjectNextStep.DIRECT_PROCESS, ProjectNextStepResolver.nextStep(mixed));
        assertFalse(ProjectNextStepResolver.nextStep(mixed).requestsDefinition());
        assertTrue(mixed.decodeWorkspaceApplicable());
        assertEquals(WorkspaceRoute.SOURCES, resume(mixed, WorkspaceRoute.DECODE));
    }

    @Test
    void mixedProjectWithDatSignalsStaysOnSignalsWhileMf4HasNoDbc() {
        ProjectJourneyFacts mixed = summaryFacts("MF4 · DAT", 2, 4, "", 0);
        assertEquals(ProjectJourneyState.ANALYSIS_READY, ProjectJourneyStateResolver.resolve(mixed));
        assertEquals(WorkspaceRoute.SIGNALS, resume(mixed, null));
        assertTrue(mixed.analysisDataReady());
        assertTrue(mixed.decodeWorkspaceApplicable());
        assertEquals(WorkspaceRoute.DECODE, resume(mixed, WorkspaceRoute.DECODE));
    }

    @Test
    void mf4WithoutADbcStillOpensDecode() {
        ProjectJourneyFacts mf4 = summaryFacts("MF4", 1, 0, "", 0);
        assertEquals(ProjectJourneyState.DECODE_SETUP_REQUIRED, ProjectJourneyStateResolver.resolve(mf4));
        assertEquals(ProjectNextStep.DECODE_PROCESS, ProjectNextStepResolver.nextStep(mf4));
        assertTrue(ProjectNextStepResolver.nextStep(mf4).requestsDefinition());
        assertEquals(WorkspaceRoute.DECODE, resume(mf4, null));
        assertEquals("Continue Setup", ProjectJourneyStateResolver.resolve(mf4).primaryActionLabel());
    }

    @Test
    void workspaceRowActionFollowsTheDirectProcessingRoute() {
        ProjectDashboardSummary row = summary("dat", "DAT", 1, 0, "", 0);
        ProjectJourneyState state = ProjectJourneyStateResolver.resolve(row);
        ProjectJourneyFacts facts = ProjectJourneyStateResolver.factsFrom(row);
        assertEquals("Direct Processing", state.primaryActionLabel());
        assertEquals(WorkspaceRoute.SOURCES,
                ResumeDestinationResolver.resolve(state, WorkspaceRoute.DECODE, facts));
        assertFalse(ProjectNextStepResolver.nextStep(facts).requestsDefinition());
    }

    @Test
    void openingADatProjectNeverResolvesToDecodeAcrossRestartAndProjectSwitch() throws Exception {
        openService();
        service.newProject("dat");
        Path dat = tempDir.resolve("capture.dat");
        Files.write(dat, mdf320());
        service.addMeasurementPaths(List.of(dat), DesktopAppService.PlatformUi.NOOP);
        String datId = service.currentProjectId();
        assertEquals(FileKind.MEASUREMENT_DAT, service.workspace().measurementFiles().getFirst().kind());
        assertFalse(MeasurementSourceCapability.requiresDefinition(FileKind.MEASUREMENT_DAT));
        assertEquals(ProjectJourneyState.DIRECT_PROCESS, service.projectJourneyState());
        assertNotEquals(WorkspaceRoute.DECODE, service.resolveResumeRoute());
        assertFalse(service.projectJourneyFacts().decodeWorkspaceApplicable());

        service.rememberMeaningfulWorkspace(WorkspaceRoute.DECODE);
        assertTrue(service.saveProject().saved());
        service.closeProject();
        assertTrue(service.openSavedProject(datId).opened());
        assertEquals(FileKind.MEASUREMENT_DAT, service.workspace().measurementFiles().getFirst().kind());
        assertFalse(MeasurementSourceCapability.requiresDefinition(
                service.workspace().measurementFiles().getFirst().kind()));
        assertEquals(ProjectJourneyState.DIRECT_PROCESS, service.projectJourneyState());
        assertEquals(WorkspaceRoute.SOURCES, service.resolveResumeRoute());
        assertNotEquals(WorkspaceRoute.DECODE, service.resolveResumeRoute());

        service.signalsModel().replaceCatalog(catalog());
        service.rememberMeaningfulWorkspace(WorkspaceRoute.DECODE);
        assertEquals(WorkspaceRoute.SIGNALS, service.resolveResumeRoute());
        assertTrue(service.projectJourneyFacts().analysisDataReady());
        assertFalse(service.projectJourneyFacts().decodeWorkspaceApplicable());

        service.newProject("mf4");
        Path mf4 = tempDir.resolve("run.mf4");
        try (SyntheticMdf4Builder builder = SyntheticMdf4Builder.create(mf4)) {
            builder.writeIdBlock("4.10", 410, 0);
        }
        service.addMeasurementPaths(List.of(mf4), DesktopAppService.PlatformUi.NOOP);
        WorkspaceFile mf4File = service.workspace().measurementFiles().getFirst();
        assertEquals(FileKind.MEASUREMENT_MF4, mf4File.kind());
        assertTrue(MeasurementSourceCapability.requiresDefinition(FileKind.MEASUREMENT_MF4));
        assertTrue(service.inventoryCoordinator().awaitIdle(5_000));
        markInventoryReady(mf4File);
        String mf4Id = service.currentProjectId();
        var mf4Save = service.saveProject();
        assertTrue(mf4Save.saved(), mf4Save.message() + " " + mf4Save.warnings()
                + " name=" + service.workspace().projectName());
        WorkspaceRoute mf4Route = service.resolveResumeRoute();
        assertTrue(definitionWorkflow(mf4Route),
                "route=" + mf4Route
                        + " state=" + service.projectJourneyState()
                        + " facts=" + service.projectJourneyFacts()
                        + " last=" + service.lastMeaningfulWorkspace());
        assertNotEquals(ProjectJourneyState.DIRECT_PROCESS, service.projectJourneyState());
        service.closeProject();
        assertTrue(service.openSavedProject(mf4Id).opened());
        assertTrue(service.inventoryCoordinator().awaitIdle(5_000));
        markInventoryReady(service.workspace().measurementFiles().getFirst());
        assertTrue(definitionWorkflow(service.resolveResumeRoute()));
        assertFalse(service.projectJourneyFacts().directOnlyWorkflow());

        assertTrue(service.openSavedProject(datId).opened());
        assertEquals(FileKind.MEASUREMENT_DAT, service.workspace().measurementFiles().getFirst().kind());
        assertNotEquals(WorkspaceRoute.DECODE, service.resolveResumeRoute());
        assertFalse(service.projectJourneyFacts().decodeWorkspaceApplicable());
        assertTrue(MeasurementSourceCapability.supportsDirectProcessing(FileKind.MEASUREMENT_DAT));
    }

    @Test
    void mixedProjectOffersDirectProcessingWhileTheDatIsUnprocessed() throws Exception {
        openService();
        service.newProject("mixed");
        Path dat = tempDir.resolve("capture.dat");
        Path mf4 = tempDir.resolve("run.mf4");
        Files.write(dat, mdf320());
        try (SyntheticMdf4Builder builder = SyntheticMdf4Builder.create(mf4)) {
            builder.writeIdBlock("4.10", 410, 0);
        }
        service.addMeasurementPaths(List.of(mf4, dat), DesktopAppService.PlatformUi.NOOP);
        WorkspaceFile mf4File = service.workspace().measurementFiles().stream()
                .filter(file -> file.kind() == FileKind.MEASUREMENT_MF4)
                .findFirst()
                .orElseThrow();
        service.selectMeasurement(mf4File.id());
        assertEquals(ProjectJourneyState.DIRECT_PROCESS, service.projectJourneyState());
        assertTrue(service.directProcessingActionAvailable());
        assertTrue(service.prepareDirectProcessingSelection());
        assertEquals(FileKind.MEASUREMENT_DAT, service.workspace().selectedMeasurement().orElseThrow().kind());
    }

    @Test
    void openingALogProjectNeverResolvesToDecode() throws Exception {
        openService();
        service.newProject("log");
        Path log = tempDir.resolve("capture.log");
        Files.write(log, mdf320());
        service.addMeasurementPaths(List.of(log), DesktopAppService.PlatformUi.NOOP);
        String id = service.currentProjectId();
        assertEquals(FileKind.MEASUREMENT_LOG, service.workspace().measurementFiles().getFirst().kind());
        assertFalse(MeasurementSourceCapability.requiresDefinition(FileKind.MEASUREMENT_LOG));
        assertEquals(ProjectJourneyState.DIRECT_PROCESS, service.projectJourneyState());
        assertEquals(WorkspaceRoute.SOURCES, service.resolveResumeRoute());
        service.rememberMeaningfulWorkspace(WorkspaceRoute.DECODE);
        assertTrue(service.saveProject().saved());
        service.closeProject();
        assertTrue(service.openSavedProject(id).opened());
        assertEquals(WorkspaceRoute.SOURCES, service.resolveResumeRoute());
        assertFalse(ProjectNextStepResolver.nextStep(service.projectJourneyFacts()).requestsDefinition());

        service.signalsModel().replaceCatalog(catalog());
        assertEquals(WorkspaceRoute.SIGNALS, service.resolveResumeRoute());
    }

    private static boolean definitionWorkflow(WorkspaceRoute route) {
        return route == WorkspaceRoute.DECODE || route == WorkspaceRoute.REVIEW_AND_PROCESS;
    }

    private void markInventoryReady(WorkspaceFile file) {
        CanMessageInventory message =
                new CanMessageInventory(new CanMessageKey(1, 0x100L, false), 10, 8, 8, false);
        CanChannelInventory channel =
                new CanChannelInventory(1, "Channel 1", 10, 1, 1, 0, 0, List.of(message));
        service.sourceInventoryModel().put(file.id(), new CanSourceInventory(
                file.path(), file.fileName(), MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 1, 10, 1, true, List.of(channel), List.of()));
    }

    private void openService() {
        DesktopTestIsolation.isolateHome(tempDir.resolve("home"));
        Path db = tempDir.resolve("meta.db");
        System.setProperty("stellarai.metadata.db", db.toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
    }

    private static ProjectJourneyFacts summaryFacts(
            String types, int files, int signals, String status, int mappings) {
        return ProjectJourneyStateResolver.factsFrom(summary("p", types, files, signals, status, mappings));
    }

    private static ProjectDashboardSummary summary(
            String name, String types, int files, int signals, String status, int mappings) {
        Instant now = Instant.parse("2026-10-09T12:00:00Z");
        return new ProjectDashboardSummary(
                "project-" + name, name, now, now, files, 0, signals, status, types, 0L, 0, mappings);
    }

    private static WorkspaceRoute resume(ProjectJourneyFacts facts, WorkspaceRoute saved) {
        return ResumeDestinationResolver.resolve(
                ProjectJourneyStateResolver.resolve(facts), saved, facts);
    }

    /** Minimal MDF 3.20 image. Content detection accepts it inside a .dat or .log name. */
    private static byte[] mdf320() {
        ByteBuffer buf = ByteBuffer.allocate(832).order(ByteOrder.LITTLE_ENDIAN);
        byte[] id = new byte[64];
        writeAscii(id, 0, "MDF     ");
        writeAscii(id, 8, "3.20");
        ByteBuffer.wrap(id).order(ByteOrder.LITTLE_ENDIAN).putShort(28, (short) 320);
        buf.put(0, id);
        buf.put(64, (byte) 'H');
        buf.put(65, (byte) 'D');
        buf.putInt(68, 100);
        buf.put(100, (byte) 'D');
        buf.put(101, (byte) 'G');
        buf.putInt(108, 140);
        buf.putInt(116, 800);
        buf.put(140, (byte) 'C');
        buf.put(141, (byte) 'G');
        buf.putInt(148, 180);
        buf.putShort(160, (short) 16);
        buf.putInt(162, 2);
        writeChannel(buf, 180, 400, 620, 1, "time", 0);
        writeChannel(buf, 400, 0, 700, 0, "Latitude", 64);
        writeConversion(buf, 620, "s");
        writeConversion(buf, 700, "degrees");
        buf.putDouble(800, 0.1);
        buf.putDouble(808, 18.5);
        buf.putDouble(816, 0.2);
        buf.putDouble(824, 18.6);
        return buf.array();
    }

    private static void writeChannel(ByteBuffer buf, int offset, int next, int cc, int type, String name, int bitOffset) {
        buf.put(offset, (byte) 'C');
        buf.put(offset + 1, (byte) 'N');
        buf.putShort(offset + 2, (short) 192);
        buf.putInt(offset + 4, next);
        buf.putInt(offset + 8, cc);
        buf.putShort(offset + 24, (short) type);
        buf.put(offset + 26, name.getBytes(StandardCharsets.US_ASCII));
        buf.putShort(offset + 186, (short) bitOffset);
        buf.putShort(offset + 188, (short) 64);
        buf.putShort(offset + 190, (short) 3);
    }

    private static void writeConversion(ByteBuffer buf, int offset, String unit) {
        buf.put(offset, (byte) 'C');
        buf.put(offset + 1, (byte) 'C');
        buf.putShort(offset + 2, (short) 64);
        buf.put(offset + 22, unit.getBytes(StandardCharsets.US_ASCII));
        buf.putShort(offset + 42, (short) 0);
    }

    private static void writeAscii(byte[] raw, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, raw, offset, bytes.length);
    }

    private static InMemorySignalCatalogStore catalog() {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("capture.dat", "", 1_000);
        store.accept(List.of(new CanonicalSignal(
                "Speed", "Speed", 1_000L, 0, 1, 1.5, 0L, "m/s", null,
                SourceReference.ofLine(1))));
        return store;
    }
}
