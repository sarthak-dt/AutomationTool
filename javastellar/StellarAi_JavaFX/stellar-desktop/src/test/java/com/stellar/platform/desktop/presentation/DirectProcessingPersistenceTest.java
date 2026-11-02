package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.TestMeasurementFixtures;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Successful DAT/LOG direct processing must survive a new service instance on the same database.
 */
class DirectProcessingPersistenceTest {

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
    void datDirectProcessingRestoresCatalogOnANewServiceInstance() throws Exception {
        Path db = database();
        service = open(db);
        String projectId = processDirect(service, "QA DAT", datFile("capture.dat"));
        int catalogRows = service.metadataStore().listSignalCatalog(projectId).size();
        assertTrue(catalogRows > 0);
        assertTrue(service.projectJourneyFacts().analysisDataReady());
        service.shutdown();
        service = null;

        service = open(db);
        assertTrue(service.openSavedProject(projectId).opened());
        assertRestored(service, projectId, catalogRows, FileKind.MEASUREMENT_DAT);
    }

    @Test
    void logDirectProcessingRestoresCatalogOnANewServiceInstance() throws Exception {
        Path db = database();
        service = open(db);
        String projectId = processDirect(service, "QA LOG", logFile("capture.log"));
        int catalogRows = service.metadataStore().listSignalCatalog(projectId).size();
        assertTrue(catalogRows > 0);
        service.shutdown();
        service = null;

        service = open(db);
        assertTrue(service.openSavedProject(projectId).opened());
        assertRestored(service, projectId, catalogRows, FileKind.MEASUREMENT_LOG);
    }

    @Test
    void reprocessReplacesCatalogRowsInsteadOfDuplicatingThem() throws Exception {
        Path db = database();
        service = open(db);
        String projectId = processDirect(service, "QA DAT", datFile("capture.dat"));
        int first = service.metadataStore().listSignalCatalog(projectId).size();
        assertTrue(first > 0);
        processSelected(service);
        int second = service.metadataStore().listSignalCatalog(projectId).size();
        int artifacts = service.metadataStore().loadProject(projectId).orElseThrow()
                .processingArtifacts().size();
        assertEquals(first, second);
        assertEquals(1, artifacts);
    }

    @Test
    void twoProjectsKeepSeparateCatalogsAfterRestart() throws Exception {
        Path db = database();
        service = open(db);
        String datId = processDirect(service, "Project A", namedDat("DatOnly"));
        String logId = processDirect(service, "Project B", namedLog("LogOnly"));
        assertTrue(service.openSavedProject(datId).opened());
        service.createInvestigation("DAT investigation", "range", "CURRENT");
        assertTrue(service.saveProject().saved());
        assertTrue(service.openSavedProject(logId).opened());
        service.createInvestigation("LOG investigation", "range", "CURRENT");
        assertTrue(service.saveProject().saved());
        service.shutdown();
        service = null;

        service = open(db);
        assertTrue(service.openSavedProject(datId).opened());
        assertSignalNames(service, "DatOnly");
        assertInvestigationNames(service, datId, "DAT investigation");
        assertTrue(service.openSavedProject(logId).opened());
        assertSignalNames(service, "LogOnly");
        assertInvestigationNames(service, logId, "LOG investigation");
        assertTrue(service.openSavedProject(datId).opened());
        assertSignalNames(service, "DatOnly");
        assertFalse(signalNames(service).stream().anyMatch(name -> name.contains("LogOnly")));
    }

    @Test
    void catalogWriteFailureDoesNotReportSuccess() throws Exception {
        Path db = database();
        service = open(db);
        service.failNextDirectPersistenceForTest = true;
        String projectId = startDirect(service, "QA DAT", datFile("capture.dat"));
        assertEquals(ProjectProcessingStatus.FAILED, service.workspace().processingStatus());
        assertEquals(0, service.metadataStore().listSignalCatalog(projectId).size());
        assertFalse(service.projectJourneyFacts().analysisDataReady());
        assertNotEquals(ProjectNextStep.SIGNALS, nextStep(service));
        service.shutdown();
        service = null;

        service = open(db);
        assertTrue(service.openSavedProject(projectId).opened());
        assertTrue(service.signalsModel().catalog().entries().isEmpty());
        assertFalse(service.projectJourneyFacts().analysisDataReady());
        assertNotEquals(ProjectNextStep.SIGNALS, nextStep(service));
    }

    @Test
    void missingSourceAfterRestartDoesNotFabricateSignals() throws Exception {
        Path db = database();
        Path dat = datFile("capture.dat");
        service = open(db);
        String projectId = processDirect(service, "QA DAT", dat);
        int rows = service.metadataStore().listSignalCatalog(projectId).size();
        service.shutdown();
        service = null;
        Files.delete(dat);

        service = open(db);
        assertTrue(service.openSavedProject(projectId).opened());
        assertTrue(service.signalsModel().catalog().entries().isEmpty());
        assertFalse(service.projectJourneyFacts().analysisDataReady());
        assertNotEquals(ProjectNextStep.SIGNALS, nextStep(service));
        assertEquals(rows, service.metadataStore().listSignalCatalog(projectId).size());
    }

    @Test
    void deletingTheProjectRemovesItsCatalog() throws Exception {
        Path db = database();
        service = open(db);
        String projectId = processDirect(service, "QA DAT", datFile("capture.dat"));
        assertTrue(service.metadataStore().listSignalCatalog(projectId).size() > 0);
        service.deleteProject(projectId);
        assertEquals(0, service.metadataStore().listSignalCatalog(projectId).size());
    }

    private void assertRestored(DesktopAppService opened, String projectId, int catalogRows, FileKind kind) {
        assertEquals(catalogRows, opened.signalsModel().catalog().entries().size());
        assertEquals(catalogRows, opened.metadataStore().listSignalCatalog(projectId).size());
        assertTrue(opened.projectJourneyFacts().analysisDataReady());
        assertEquals(ProjectJourneyState.ANALYSIS_READY, opened.projectJourneyState());
        assertEquals(ProjectNextStep.SIGNALS, nextStep(opened));
        assertNotEquals(ProjectNextStep.DIRECT_PROCESS, nextStep(opened));
        assertEquals(kind, opened.workspace().measurementFiles().getFirst().kind());
        assertTrue(opened.workspace().measurementFiles().getFirst().validationMessage()
                .contains("DBC is not required"));
        assertTrue(opened.projectJourneyState().analysisAvailable());
    }

    private static void assertSignalNames(DesktopAppService opened, String expectedFragment) {
        Set<String> names = signalNames(opened);
        assertFalse(names.isEmpty());
        assertTrue(names.stream().anyMatch(name -> name.contains(expectedFragment)), names.toString());
    }

    private static Set<String> signalNames(DesktopAppService opened) {
        return opened.signalsModel().catalog().entries().stream()
                .map(entry -> entry.key().signalName())
                .collect(Collectors.toSet());
    }

    private static void assertInvestigationNames(DesktopAppService opened, String projectId, String name) {
        List<String> names = opened.engineeringInvestigations().list(opened.metadataStore(), projectId).stream()
                .map(investigation -> investigation.name())
                .toList();
        assertTrue(names.contains(name), names.toString());
    }

    private static ProjectNextStep nextStep(DesktopAppService opened) {
        return ProjectNextStep.from(opened.projectJourneyState(), opened.projectJourneyFacts());
    }

    private String processDirect(DesktopAppService target, String projectName, Path measurement) throws Exception {
        String projectId = startDirect(target, projectName, measurement);
        assertEquals(
                ProjectProcessingStatus.COMPLETED,
                target.workspace().processingStatus(),
                target.workspace().latestSummary()
                        .map(summary -> summary.userMessage() + " | " + summary.technicalDetail())
                        .orElse("no summary")
                        + " issues=" + target.lastValidationIssues());
        return projectId;
    }

    private static String startDirect(DesktopAppService target, String projectName, Path measurement) throws Exception {
        target.newProject(projectName);
        target.addMeasurementPaths(List.of(measurement), DesktopAppService.PlatformUi.NOOP);
        target.selectMeasurement(target.workspace().measurementFiles().getFirst().id());
        processSelected(target);
        return target.currentProjectId();
    }

    private static void processSelected(DesktopAppService target) throws InterruptedException {
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
        assertTrue(done.await(30, TimeUnit.SECONDS), "direct processing did not finish");
    }

    private DesktopAppService open(Path db) {
        System.setProperty("stellarai.home", tempDir.resolve("home").toString());
        System.setProperty("stellarai.metadata.db", db.toString());
        return new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
    }

    private Path database() {
        return tempDir.resolve("meta.db");
    }

    private Path datFile(String name) throws Exception {
        return TestMeasurementFixtures.validMinimalDat(tempDir.resolve(name));
    }

    private Path logFile(String name) throws Exception {
        return TestMeasurementFixtures.validMinimalLog(tempDir.resolve(name));
    }

    private Path namedDat(String signal) throws Exception {
        Path file = tempDir.resolve(signal + ".dat");
        Files.writeString(file, "time_s," + signal + " (m/s)\n0.0,1.5\n0.1,1.6\n0.2,1.7\n", StandardCharsets.UTF_8);
        return file;
    }

    private Path namedLog(String signal) throws Exception {
        Path file = tempDir.resolve(signal + ".log");
        Files.writeString(file, "time_ms\t" + signal + " (km/h)\n0\t10\n10\t11\n20\t12\n", StandardCharsets.UTF_8);
        return file;
    }
}
