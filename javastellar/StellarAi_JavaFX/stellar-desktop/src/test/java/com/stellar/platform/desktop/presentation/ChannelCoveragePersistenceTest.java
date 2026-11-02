package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.metadata.ChannelMappingMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Channel coverage is committed when matching finishes, and a report reads that commit.
 * Counts come from the stored row. They are not copied from another project.
 */
class ChannelCoveragePersistenceTest {

    private static final Path MF4 = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/tata_sierra_0002391_20260921_133054 (1).mf4");
    private static final Path DBC = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/TEMP DBC 1 (1).dbc");

    @TempDir
    Path temp;

    private DesktopAppService service;
    private ExecutorService reports;

    @AfterEach
    void tearDown() {
        if (reports != null) {
            reports.shutdownNow();
        }
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    @Test
    @Timeout(180)
    void completedMatchingIsVisibleToAnImmediateReportAndDoesNotLeakAcrossProjects() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC));
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(temp.resolve("project.db"));
        service = new DesktopAppService(new DesktopSession(), store);
        service.newProject("Coverage A");
        String projectA = service.currentProjectId();
        service.addDefinitionPaths(List.of(DBC));
        service.addMeasurementPaths(List.of(MF4), DesktopAppService.PlatformUi.NOOP);

        ChannelMappingMetadata committed = awaitOne(store, projectA, 30_000);
        assertEquals(45L, committed.observedUniqueMessageCount());
        assertEquals(4L, committed.matchedUniqueMessageCount());
        assertEquals("RECOMMENDED", committed.status());
        assertEquals(41L, committed.observedUniqueMessageCount() - committed.matchedUniqueMessageCount());

        EngineeringInvestigation investigation = v2(service.createInvestigation("Coverage A", "persistence", "FULL"));
        reports = Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "coverage-report");
            thread.setDaemon(true);
            return thread;
        });
        Future<String> html = reports.submit(() -> Files.readString(service.generateInvestigationReport(
                investigation, "qa", temp.resolve("a.html"))));
        Future<String> pdf = reports.submit(() -> pdfText(service.generateInvestigationPdfReport(
                investigation, "qa", temp.resolve("a.pdf"))));
        String htmlText = html.get(90, TimeUnit.SECONDS);
        String pdfText = pdf.get(90, TimeUnit.SECONDS);
        assertTrue(htmlText.contains("observed=45"), htmlText);
        assertTrue(htmlText.contains("matched=4"), htmlText);
        assertTrue(htmlText.contains("unmatchedIdentities=41"), htmlText);
        assertFalse(htmlText.contains("dbc-coverage-unavailable"));
        assertTrue(pdfText.replaceAll("\\s+", "").contains("observed=45"));
        assertTrue(pdfText.replaceAll("\\s+", "").contains("matched=4"));
        ChannelMappingMetadata afterReports = awaitOne(store, projectA, 1_000);
        assertEquals(45L, afterReports.observedUniqueMessageCount());
        assertEquals(4L, afterReports.matchedUniqueMessageCount());

        service.newProject("Coverage B");
        String projectB = service.currentProjectId();
        service.addDefinitionPaths(List.of(DBC));
        EngineeringInvestigation other = v2(service.createInvestigation("Coverage B", "no measurement", "FULL"));
        String otherHtml = Files.readString(service.generateInvestigationReport(
                other, "qa", temp.resolve("b.html")));
        assertTrue(otherHtml.contains("dbc-coverage-unavailable"), otherHtml);
        assertFalse(otherHtml.contains("observed=45"));
        assertTrue(mappings(store, projectB).isEmpty());
        ChannelMappingMetadata stillA = awaitOne(store, projectA, 1_000);
        assertEquals(45L, stillA.observedUniqueMessageCount());
        assertEquals(4L, stillA.matchedUniqueMessageCount());

        var reopened = service.openSavedProject(projectA);
        assertTrue(reopened.opened(), reopened.warnings().toString());
        ChannelMappingMetadata reloaded = awaitOne(store, projectA, 1_000);
        assertEquals(45L, reloaded.observedUniqueMessageCount());
        assertEquals(4L, reloaded.matchedUniqueMessageCount());
        assertEquals(1, service.definitionMatchingModel().allMappings().size());
        assertEquals(45L, service.definitionMatchingModel().allMappings().get(0).observedUniqueMessageCount());
        assertEquals(4L, service.definitionMatchingModel().allMappings().get(0).matchedUniqueMessageCount());

        String measurementId = service.workspace().measurementFiles().get(0).id();
        assertTrue(service.removeFile(measurementId));
        assertTrue(mappings(store, projectA).isEmpty());
        EngineeringInvestigation afterRemoval = v2(service.createInvestigation("Coverage A2", "removed", "FULL"));
        String removedHtml = Files.readString(service.generateInvestigationReport(
                afterRemoval, "qa", temp.resolve("removed.html")));
        assertTrue(removedHtml.contains("dbc-coverage-unavailable"), removedHtml);
        assertFalse(removedHtml.contains("observed=45"));
    }

    @Test
    @Timeout(90)
    void aFailedMappingWriteStaysUnavailableAndACancelledInventoryDoesNotCommit() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC));
        System.setProperty("stellarai.home", temp.resolve("fail-home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("fail-meta.db").toString());

        SqliteProjectMetadataStore cancelStore = new SqliteProjectMetadataStore(temp.resolve("cancel.db"));
        service = new DesktopAppService(new DesktopSession(), cancelStore);
        service.newProject("Cancel");
        String cancelledProject = service.currentProjectId();
        service.addDefinitionPaths(List.of(DBC));
        service.addMeasurementPaths(List.of(MF4), DesktopAppService.PlatformUi.NOOP);
        String measurementId = service.workspace().measurementFiles().get(0).id();
        assertTrue(service.removeFile(measurementId));
        Thread.sleep(8_000);
        assertTrue(mappings(cancelStore, cancelledProject).isEmpty(),
                mappings(cancelStore, cancelledProject).toString());
        service.shutdown();
        service = null;

        AtomicInteger attempts = new AtomicInteger();
        SqliteProjectMetadataStore failingStore = new SqliteProjectMetadataStore(temp.resolve("fail.db"));
        ProjectMetadataStore failing = mappingWriteFails(failingStore, attempts);
        service = new DesktopAppService(new DesktopSession(), failing);
        service.newProject("Write fail");
        String failedProject = service.currentProjectId();
        service.addDefinitionPaths(List.of(DBC));
        service.addMeasurementPaths(List.of(MF4), DesktopAppService.PlatformUi.NOOP);
        Thread.sleep(12_000);
        assertTrue(attempts.get() > 0);
        assertTrue(mappings(failing, failedProject).isEmpty());
        assertEquals(ProjectSaveState.SAVE_FAILED, service.projectSaveState());
        EngineeringInvestigation investigation = v2(service.createInvestigation("Write fail", "disk", "FULL"));
        String html = Files.readString(service.generateInvestigationReport(
                investigation, "qa", temp.resolve("failed.html")));
        assertTrue(html.contains("dbc-coverage-unavailable"), html);
        assertFalse(html.contains("observed=45"));
        assertTrue(mappings(failing, failedProject).isEmpty());
    }

    private EngineeringInvestigation v2(EngineeringInvestigation investigation) {
        return service.saveInvestigation(investigation.toBuilder()
                .reportConfig(InvestigationReportConfig.engineeringV2())
                .build());
    }

    private static ProjectMetadataStore mappingWriteFails(SqliteProjectMetadataStore delegate, AtomicInteger attempts) {
        return (ProjectMetadataStore) Proxy.newProxyInstance(
                ProjectMetadataStore.class.getClassLoader(),
                new Class<?>[] {ProjectMetadataStore.class},
                (proxy, method, args) -> {
                    if ("upsertChannelMapping".equals(method.getName())) {
                        attempts.incrementAndGet();
                        throw new IllegalStateException("channel mapping store is not writable");
                    }
                    try {
                        return method.invoke(delegate, args == null ? new Object[0] : args);
                    } catch (InvocationTargetException ex) {
                        Throwable cause = ex.getCause();
                        if (cause instanceof RuntimeException runtime) {
                            throw runtime;
                        }
                        if (cause instanceof Error error) {
                            throw error;
                        }
                        throw new IllegalStateException(cause);
                    }
                });
    }

    private static ChannelMappingMetadata awaitOne(
            ProjectMetadataStore store, String projectId, long timeoutMs
    ) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        List<ChannelMappingMetadata> rows = List.of();
        while (System.nanoTime() < deadline) {
            rows = mappings(store, projectId);
            if (rows.size() == 1) {
                return rows.get(0);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("expected one channel mapping for " + projectId + " but found " + rows);
    }

    private static List<ChannelMappingMetadata> mappings(ProjectMetadataStore store, String projectId) {
        return store.loadProject(projectId).map(SavedProjectSnapshot::channelMappings).orElse(List.of());
    }

    private static String pdfText(Path pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            return new PDFTextStripper().getText(document);
        }
    }

}
