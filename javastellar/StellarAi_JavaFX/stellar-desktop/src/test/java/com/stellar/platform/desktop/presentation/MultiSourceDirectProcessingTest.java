package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project-level Direct Processing must run every active DAT/LOG source, not only the
 * selected file.
 */
class MultiSourceDirectProcessingTest {

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
    void threeDatFilesAreAllProcessed() throws Exception {
        service = open();
        String projectId = processFiles("Three", datFiles(3));
        assertEquals(3, service.directProcessingTargets().size());
        assertEquals(3, measurementCount(service));
        assertEquals(3, service.signalsModel().catalog().entries().size());
        assertEquals(ProjectProcessingStatus.COMPLETED, service.workspace().processingStatus());
        assertTrue(service.workspace().latestSummary().orElseThrow().userMessage().contains("3 of 3"));
        assertEquals(3, service.metadataStore().loadProject(projectId).orElseThrow().processingArtifacts().size());
    }

    @Test
    void tenDatFilesAreAllProcessed() throws Exception {
        service = open();
        processFiles("Ten", datFiles(10));
        assertEquals(10, measurementCount(service));
        assertEquals(10, service.signalsModel().catalog().entries().size());
        assertEquals(10, service.analysisScopeChoices().stream().filter(AnalysisScope::isSingle).count());
    }

    @Test
    void seventyThreeLightweightFilesBuildASeventyThreeSourcePlan() throws Exception {
        service = open();
        processFiles("SeventyThree", datFiles(73));
        assertEquals(73, service.directProcessingTargets().size());
        assertEquals(73, measurementCount(service));
        assertEquals(73, service.signalsModel().catalog().entries().size());
        assertTrue(service.isProjectWideAnalysis());
        assertEquals(73, service.analysisScopeChoices().stream().filter(AnalysisScope::isSingle).count());
        assertTrue(service.workspace().latestSummary().orElseThrow().userMessage().contains("73 of 73"));
    }

    @Test
    void sameSignalNameInTwoFilesStaysTwoIdentities() throws Exception {
        service = open();
        processFiles("SameName", List.of(namedDat("a.dat", "Speed"), namedDat("b.dat", "Speed")));
        List<SignalCatalogEntry> speeds = service.signalsModel().catalog().entries().stream()
                .filter(entry -> entry.signalName().equals("Speed"))
                .toList();
        assertEquals(1, speeds.size());
        var store = service.signalsModel().catalogStore();
        assertTrue(store instanceof com.stellar.platform.processing.catalog.ProjectMergedSignalStore);
        assertEquals(2, ((com.stellar.platform.processing.catalog.ProjectMergedSignalStore) store)
                .physicalRouteCount(speeds.getFirst().key()));
    }

    @Test
    void twoLogFilesWithTheSameSignalGroupOnce() throws Exception {
        service = open();
        processFiles("LogGroup", List.of(
                valuedLog("a.log", "Accuracy", 1.0),
                valuedLog("b.log", "Accuracy", 4.0)));
        assertEquals(1, service.signalsModel().catalog().entries().stream()
                .filter(entry -> entry.signalName().equals("Accuracy")).count());
        var store = (com.stellar.platform.processing.catalog.ProjectMergedSignalStore)
                service.signalsModel().catalogStore();
        var key = service.signalsModel().catalog().entries().stream()
                .filter(entry -> entry.signalName().equals("Accuracy"))
                .findFirst().orElseThrow().key();
        assertEquals(2, store.physicalRouteCount(key));
        String firstId = service.workspace().measurementFiles().getFirst().id();
        var physical = store.physicalCatalog(firstId, "a.log").stream()
                .filter(entry -> entry.signalName().equals("Accuracy"))
                .findFirst().orElseThrow().key();
        assertTrue(store.querySamples(key, 0, 20).size() > store.querySamples(physical, 0, 20).size());
    }

    @Test
    void removingOneFileKeepsTheLogicalSignalAndDropsItsSamples() throws Exception {
        service = open();
        processFiles("LogicalRemove", List.of(
                valuedDat("a.dat", "Accuracy", 1.0),
                valuedDat("b.dat", "Accuracy", 2.0),
                valuedDat("c.dat", "Accuracy", 3.0)));
        var merged = (com.stellar.platform.processing.catalog.ProjectMergedSignalStore)
                service.signalsModel().catalogStore();
        var logical = service.signalsModel().catalog().entries().stream()
                .filter(entry -> entry.signalName().equals("Accuracy"))
                .findFirst().orElseThrow().key();
        assertEquals(1, service.signalsModel().catalog().entries().size());
        assertEquals(3, merged.physicalRouteCount(logical));
        int before = merged.querySamples(logical, 0, 20).size();
        WorkspaceFile removed = service.workspace().measurementFiles().getFirst();
        assertTrue(service.removeFile(removed.id()));
        merged = (com.stellar.platform.processing.catalog.ProjectMergedSignalStore)
                service.signalsModel().catalogStore();
        logical = service.signalsModel().catalog().entries().stream()
                .filter(entry -> entry.signalName().equals("Accuracy"))
                .findFirst().orElseThrow().key();
        assertEquals(1, service.signalsModel().catalog().entries().stream()
                .filter(entry -> entry.signalName().equals("Accuracy")).count());
        assertEquals(2, merged.physicalRouteCount(logical));
        assertTrue(merged.querySamples(logical, 0, 20).size() < before);
    }

    @Test
    void removingOneProcessedFileLeavesTheOthers() throws Exception {
        service = open();
        processFiles("Remove", datFiles(3));
        WorkspaceFile removed = service.workspace().measurementFiles().getFirst();
        assertTrue(service.removeFile(removed.id()));
        assertEquals(2, service.workspace().measurementFiles().size());
        assertEquals(2, measurementCount(service));
        assertTrue(service.signalsModel().catalog().entries().stream()
                .noneMatch(entry -> removed.id().equals(entry.key().sourceFile())));
        assertEquals(2, service.analysisScopeChoices().stream().filter(AnalysisScope::isSingle).count());
        assertFalse(service.workspace().processingStatus() == ProjectProcessingStatus.RUNNING);
    }

    @Test
    void restartRestoresEveryProcessedMeasurement() throws Exception {
        Path db = tempDir.resolve("meta.db");
        service = open(db);
        String projectId = processFiles("Restart", datFiles(3));
        int catalog = service.signalsModel().catalog().entries().size();
        service.shutdown();
        service = null;

        service = open(db);
        assertTrue(service.openSavedProject(projectId).opened());
        assertEquals(3, service.workspace().measurementFiles().size());
        assertEquals(catalog, service.signalsModel().catalog().entries().size());
        assertEquals(3, measurementCount(service));
        assertEquals(3, service.analysisScopeChoices().stream().filter(AnalysisScope::isSingle).count());
    }

    @Test
    void processingAgainDoesNotDuplicateCatalogRows() throws Exception {
        service = open();
        String projectId = processFiles("Again", datFiles(3));
        int catalog = service.metadataStore().listSignalCatalog(projectId).size();
        int artifacts = service.metadataStore().loadProject(projectId).orElseThrow().processingArtifacts().size();
        processSelected(service);
        assertEquals(catalog, service.metadataStore().listSignalCatalog(projectId).size());
        assertEquals(catalog, service.signalsModel().catalog().entries().size());
        assertEquals(artifacts, service.metadataStore().loadProject(projectId).orElseThrow().processingArtifacts().size());
        assertEquals(3, measurementCount(service));
    }

    @Test
    void oneInvalidFileDoesNotDropTheValidFiles() throws Exception {
        service = open();
        List<Path> files = new ArrayList<>(datFiles(2));
        Path corrupt = tempDir.resolve("corrupt.dat");
        Files.writeString(corrupt, "this is not a measurement\n", StandardCharsets.UTF_8);
        files.add(corrupt);
        processFiles("Partial", files);
        assertEquals(ProjectProcessingStatus.FAILED, service.workspace().processingStatus());
        assertEquals(2, measurementCount(service));
        assertEquals(2, service.directProcessingTargets().size());
        String message = service.workspace().latestSummary().orElseThrow().userMessage();
        assertTrue(message.contains("2 of 3"));
        assertTrue(message.contains("1 failed"));
        assertTrue(service.workspace().latestSummary().orElseThrow().technicalDetail().contains("corrupt.dat"));
        assertTrue(service.signalsModel().catalog().entries().stream()
                .noneMatch(entry -> entry.sourceFile() != null && entry.sourceFile().contains("corrupt")));
    }

    private String processFiles(String projectName, List<Path> files) throws Exception {
        service.newProject(projectName);
        service.addMeasurementPaths(files, DesktopAppService.PlatformUi.NOOP);
        assertTrue(service.prepareDirectProcessingSelection());
        processSelected(service);
        return service.currentProjectId();
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
        assertTrue(done.await(120, TimeUnit.SECONDS), "direct processing did not finish");
    }

    private static int measurementCount(DesktopAppService opened) {
        return MeasurementCatalogScope.measurementCount(
                opened.signalsModel().catalog().entries(),
                opened.signalsModel().catalogStore());
    }

    private DesktopAppService open() {
        return open(tempDir.resolve("meta.db"));
    }

    private DesktopAppService open(Path db) {
        System.setProperty("stellarai.home", tempDir.resolve("home").toString());
        System.setProperty("stellarai.metadata.db", db.toString());
        return new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
    }

    private List<Path> datFiles(int count) throws Exception {
        List<Path> files = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            files.add(namedDat("m" + i + ".dat", "Speed" + i));
        }
        return files;
    }

    private Path valuedLog(String fileName, String signal, double base) throws Exception {
        return valuedDat(fileName, signal, base);
    }

    private Path valuedDat(String fileName, String signal, double base) throws Exception {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, "time_s," + signal + " (m/s)\n0.0," + base + "\n0.1," + (base + 0.1)
                + "\n0.2," + (base + 0.2) + "\n", StandardCharsets.UTF_8);
        return file;
    }

    private Path namedDat(String fileName, String signal) throws Exception {
        Path file = tempDir.resolve(fileName);
        Files.writeString(file, "time_s," + signal + " (m/s)\n0.0,1.5\n0.1,1.6\n0.2,1.7\n", StandardCharsets.UTF_8);
        return file;
    }
}
