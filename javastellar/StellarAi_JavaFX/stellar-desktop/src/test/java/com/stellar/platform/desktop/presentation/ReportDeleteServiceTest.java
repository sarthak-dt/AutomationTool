package com.stellar.platform.desktop.presentation;

import com.stellar.platform.storage.metadata.EngineeringInvestigationRow;
import com.stellar.platform.storage.metadata.InvestigationReportRow;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportDeleteServiceTest {

    @TempDir
    Path temp;

    @Test
    void deleteRemovesFileAndMetadataOnly() throws Exception {
        Path db = temp.resolve("reports.db");
        Path file = temp.resolve("Test Report_1.html");
        Files.writeString(file, "<html>report</html>");
        Path source = temp.resolve("measurement.mf4");
        Files.writeString(source, "measurement");
        Instant created = Instant.parse("2026-04-04T00:00:00Z");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Project", created, created));
            store.upsertEngineeringInvestigation(new EngineeringInvestigationRow(
                    "inv-1", "p1", "Investigation", "", "DRAFT",
                    null, null, "", "STACKED", null, null, null, null, "", "", "", created, created));
            store.upsertInvestigationReport(new InvestigationReportRow(
                    "r1", "inv-1", "p1", "Test Report_1", "HTML", "COMPLETED",
                    file.toString(), "", created, created));
            new ReportDeleteService().delete(store.findInvestigationReport("r1").orElseThrow(),
                    row -> store.deleteInvestigationReport(row.reportId()));
            assertFalse(Files.exists(file));
            assertTrue(Files.isRegularFile(source));
            assertTrue(store.listInvestigationReports("inv-1").isEmpty());
            assertTrue(store.findEngineeringInvestigation("inv-1").isPresent());
            assertEquals("Project", store.loadProject("p1").orElseThrow().project().name());
        }
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            assertTrue(reopened.listProjectReports("p1").isEmpty());
            assertTrue(reopened.findEngineeringInvestigation("inv-1").isPresent());
        }
    }

    @Test
    void missingFileStillRemovesMetadata() throws Exception {
        Path db = temp.resolve("missing.db");
        Instant created = Instant.parse("2026-05-05T00:00:00Z");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Project", created, created));
            store.upsertEngineeringInvestigation(new EngineeringInvestigationRow(
                    "inv-1", "p1", "Investigation", "", "DRAFT",
                    null, null, "", "STACKED", null, null, null, null, "", "", "", created, created));
            store.upsertInvestigationReport(new InvestigationReportRow(
                    "r1", "inv-1", "p1", "Gone", "HTML", "COMPLETED",
                    temp.resolve("gone.html").toString(), "", created, created));
            new ReportDeleteService().delete(store.findInvestigationReport("r1").orElseThrow(),
                    row -> store.deleteInvestigationReport(row.reportId()));
            assertTrue(store.listInvestigationReports("inv-1").isEmpty());
        }
    }

    @Test
    void deletedSuffixIsReusedByTheNextReport() throws Exception {
        Path dir = temp.resolve("reports");
        Path first = ReportDestinationClaim.claim(dir, "Analysis", List.of());
        Path second = ReportDestinationClaim.claim(dir, "Analysis", List.of());
        Path third = ReportDestinationClaim.claim(dir, "Analysis", List.of());
        assertEquals("Analysis.html", first.getFileName().toString());
        assertEquals("Analysis_1.html", second.getFileName().toString());
        assertEquals("Analysis_2.html", third.getFileName().toString());
        new ReportDeleteService().delete(new InvestigationReportRow(
                "r2", "inv", "p", "Analysis_1", "HTML", "COMPLETED",
                second.toString(), "", Instant.now(), Instant.now()), row -> {
        });
        Path reused = ReportDestinationClaim.claim(dir, "Analysis", List.of());
        assertEquals("Analysis_1.html", reused.getFileName().toString());
        assertTrue(Files.isRegularFile(first));
        assertTrue(Files.isRegularFile(third));
    }

    @Test
    void concurrentClaimsDoNotShareAFilename() throws Exception {
        Path dir = temp.resolve("burst");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<Path>> futures = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(pool.submit(() -> ReportDestinationClaim.claim(dir, "Analysis", List.of())));
        }
        Set<String> names = new HashSet<>();
        for (Future<Path> future : futures) {
            names.add(future.get().getFileName().toString());
        }
        pool.shutdownNow();
        assertEquals(8, names.size());
        assertTrue(names.contains("Analysis.html"));
        assertTrue(names.contains("Analysis_7.html"));
    }

    @Test
    void failedDeleteLeavesMetadata() throws Exception {
        Path db = temp.resolve("locked.db");
        Path folder = temp.resolve("not-a-file");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("child.txt"), "locked");
        Instant created = Instant.parse("2026-06-06T00:00:00Z");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Project", created, created));
            store.upsertEngineeringInvestigation(new EngineeringInvestigationRow(
                    "inv-1", "p1", "Investigation", "", "DRAFT",
                    null, null, "", "STACKED", null, null, null, null, "", "", "", created, created));
            store.upsertInvestigationReport(new InvestigationReportRow(
                    "r1", "inv-1", "p1", "Locked", "HTML", "COMPLETED",
                    folder.toString(), "", created, created));
            assertThrows(java.io.IOException.class, () -> new ReportDeleteService().delete(
                    store.findInvestigationReport("r1").orElseThrow(),
                    row -> store.deleteInvestigationReport(row.reportId())));
            assertEquals(1, store.listInvestigationReports("inv-1").size());
            assertTrue(Files.isDirectory(folder));
        }
    }
}
