package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.report.ReportNameAllocator;
import com.stellar.platform.storage.metadata.EngineeringInvestigationRow;
import com.stellar.platform.storage.metadata.InvestigationReportRow;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportRenameServiceTest {

    @TempDir
    Path temp;

    @Test
    void renameMovesFileAndKeepsCreatedTimestamp() throws Exception {
        Path db = temp.resolve("reports.db");
        Path file = temp.resolve("Test Report.html");
        Files.writeString(file, "<html></html>");
        Instant created = Instant.parse("2026-01-02T03:04:05Z");
        ReportRenameService service = new ReportRenameService();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            seed(store, "p1", "inv-1");
            InvestigationReportRow row = report("r1", "p1", "inv-1", "Test Report", file, created);
            store.upsertInvestigationReport(row);
            InvestigationReportRow renamed = service.rename(row, "Final Report",
                    List.of("Test Report.html"), store::upsertInvestigationReport);
            assertEquals("Final Report", renamed.name());
            assertEquals(created, renamed.createdAt());
            assertTrue(Files.isRegularFile(temp.resolve("Final Report.html")));
            assertFalse(Files.exists(file));
        }
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            InvestigationReportRow stored = reopened.listProjectReports("p1").getFirst();
            assertEquals("Final Report", stored.name());
            assertEquals(created, stored.createdAt());
            assertTrue(stored.destination().endsWith("Final Report.html"));
            assertTrue(reopened.findEngineeringInvestigation("inv-1").isPresent());
        }
    }

    @Test
    void duplicateTargetIsRejectedAndFileStays() throws Exception {
        Path current = temp.resolve("Report B.html");
        Path taken = temp.resolve("Report A.html");
        Files.writeString(current, "b");
        Files.writeString(taken, "a");
        InvestigationReportRow row = report("r", "p", "inv", "Report B", current, Instant.parse("2026-02-02T00:00:00Z"));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ReportRenameService().rename(row, "Report A",
                        List.of("Report A.html", "Report B.html"), updated -> {
                            throw new AssertionError("metadata must not change");
                        }));
        assertEquals(ReportNameAllocator.NAME_EXISTS, ex.getMessage());
        assertTrue(Files.isRegularFile(current));
        assertEquals("a", Files.readString(taken));
    }

    @Test
    void invalidTargetIsRejected() throws Exception {
        Path current = temp.resolve("Report.html");
        Files.writeString(current, "ok");
        InvestigationReportRow row = report("r", "p", "inv", "Report", current, Instant.now());
        assertThrows(IllegalArgumentException.class,
                () -> new ReportRenameService().rename(row, "   ", List.of("Report.html"), updated -> {
                }));
        assertTrue(Files.isRegularFile(current));
    }

    @Test
    void missingFileDoesNotUpdateMetadata() {
        InvestigationReportRow row = report("r", "p", "inv", "Report", temp.resolve("missing.html"), Instant.now());
        assertThrows(java.io.IOException.class,
                () -> new ReportRenameService().rename(row, "Other", List.of(), updated -> {
                    throw new AssertionError("metadata must not change");
                }));
    }

    @Test
    void metadataFailureRestoresTheOriginalFile() throws Exception {
        Path file = temp.resolve("Report.html");
        Files.writeString(file, "body");
        InvestigationReportRow row = report("r", "p", "inv", "Report", file, Instant.now());
        assertThrows(IllegalStateException.class,
                () -> new ReportRenameService().rename(row, "Renamed", List.of("Report.html"), updated -> {
                    throw new IllegalStateException("store failed");
                }));
        assertTrue(Files.isRegularFile(file));
        assertFalse(Files.exists(temp.resolve("Renamed.html")));
        assertEquals("body", Files.readString(file));
    }

    @Test
    void projectsKeepSeparateReportNames() throws Exception {
        Path db = temp.resolve("iso.db");
        Path a = temp.resolve("a");
        Path b = temp.resolve("b");
        Files.createDirectories(a);
        Files.createDirectories(b);
        Path fileA = a.resolve("Report.html");
        Path fileB = b.resolve("Report.html");
        Files.writeString(fileA, "a");
        Files.writeString(fileB, "b");
        Instant now = Instant.parse("2026-03-03T00:00:00Z");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            seed(store, "pa", "inv-a");
            seed(store, "pb", "inv-b");
            store.upsertInvestigationReport(report("ra", "pa", "inv-a", "Report", fileA, now));
            store.upsertInvestigationReport(report("rb", "pb", "inv-b", "Report", fileB, now));
            assertEquals(1, store.listProjectReports("pa").size());
            assertEquals(1, store.listProjectReports("pb").size());
            assertEquals(fileA.toString(), store.listProjectReports("pa").getFirst().destination());
        }
    }

    private static void seed(SqliteProjectMetadataStore store, String projectId, String investigationId) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        store.upsertProject(new ProjectMetadata(projectId, projectId + " name", now, now));
        store.upsertEngineeringInvestigation(new EngineeringInvestigationRow(
                investigationId, projectId, "Investigation", "", "DRAFT",
                null, null, "", "STACKED", null, null, null, null, "", "", "", now, now));
    }

    private static InvestigationReportRow report(
            String id, String projectId, String investigationId, String name, Path file, Instant created
    ) {
        return new InvestigationReportRow(id, investigationId, projectId, name, "HTML", "COMPLETED",
                file.toString(), "", created, created);
    }
}
