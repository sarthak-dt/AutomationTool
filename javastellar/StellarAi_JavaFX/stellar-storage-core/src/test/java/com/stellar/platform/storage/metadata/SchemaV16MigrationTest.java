package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("release-acceptance")
class SchemaV16MigrationTest {

    @TempDir
    Path temp;

    @Test
    void schemaAdvancesToSixteenAndStoresInvestigations() {
        Path db = temp.resolve("v16.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), store.schemaVersion());
            assertTrue(store.tableExists("engineering_investigations"));
            assertTrue(store.tableExists("investigation_signals"));
            assertTrue(store.tableExists("investigation_evidence"));
            assertTrue(store.tableExists("investigation_observations"));
            assertTrue(store.tableExists("investigation_reports"));
            assertTrue(store.tableExists("investigation_workspace"));
            assertFalse(store.tableExists("signal_samples"));
            store.upsertProject(new ProjectMetadata("p1", "Vehicle_Test_Run_042", now, now, "GRAPHS", null));
            store.upsertEngineeringInvestigation(new EngineeringInvestigationRow(
                    "inv-1", "p1", "Brake Response Issue", "desc", "DRAFT",
                    125_300_000_000L, 142_800_000_000L, "GIV1", "STACKED",
                    125L, 142L, 1L, 2L, "", "Need more pressure data", "1111111110", now, now));
            store.replaceInvestigationSignals("inv-1", List.of("VehicleSpeed|DBC_CAN", "BrakePedal|DBC_CAN"));
            store.replaceInvestigationEvidence("inv-1", List.of(
                    new InvestigationEvidenceRow("e1", "inv-1", "SIGNAL", "VehicleSpeed", 0, false, "",
                            "fpA", 1L, "Speed", "", now),
                    new InvestigationEvidenceRow("e2", "inv-1", "DIAGNOSTIC_FINDING", "f1", 1, false, "",
                            "fpA", 1L, "Finding", "slow", now)));
            store.replaceInvestigationObservations("inv-1", List.of(
                    new InvestigationObservationRow("o1", "inv-1", 1L, 2L, "Note", "Pedal applied", now, now)));
            store.upsertInvestigationReport(new InvestigationReportRow(
                    "r1", "inv-1", "p1", "Brake report", "HTML", "COMPLETED", "report.html", "", now, now));
            store.upsertInvestigationWorkspace(new InvestigationWorkspaceMetadata("p1", "inv-1", now));
            assertEquals(1, store.countEngineeringInvestigations("p1"));
        }
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), reopened.schemaVersion());
            EngineeringInvestigationRow inv = reopened.findEngineeringInvestigation("inv-1").orElseThrow();
            assertEquals("Brake Response Issue", inv.name());
            assertEquals("DRAFT", inv.status());
            assertEquals(List.of("VehicleSpeed|DBC_CAN", "BrakePedal|DBC_CAN"),
                    reopened.listInvestigationSignals("inv-1"));
            List<InvestigationEvidenceRow> evidence = reopened.listInvestigationEvidence("inv-1");
            assertEquals(2, evidence.size());
            assertEquals("SIGNAL", evidence.getFirst().kind());
            assertEquals("DIAGNOSTIC_FINDING", evidence.get(1).kind());
            assertEquals("Pedal applied", reopened.listInvestigationObservations("inv-1").getFirst().note());
            assertEquals("COMPLETED", reopened.listInvestigationReports("inv-1").getFirst().status());
            assertEquals("inv-1", reopened.findInvestigationWorkspace("p1").orElseThrow().lastInvestigationId());
            reopened.deleteEngineeringInvestigation("inv-1");
            assertTrue(reopened.findEngineeringInvestigation("inv-1").isEmpty());
            assertTrue(reopened.listInvestigationEvidence("inv-1").isEmpty());
        }
    }

    @Test
    void oneHundredInvestigationsListWithoutWaveforms() {
        Path db = temp.resolve("v16-perf.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Many", now, now, "GRAPHS", null));
            long start = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                store.upsertEngineeringInvestigation(new EngineeringInvestigationRow(
                        "inv-" + i, "p1", "Inv " + i, "", i % 3 == 0 ? "COMPLETED" : "DRAFT",
                        null, null, "", "STACKED", null, null, null, null, "", "", "",
                        now, now.plusSeconds(i)));
            }
            long listed = System.nanoTime();
            List<EngineeringInvestigationRow> all = store.listEngineeringInvestigations("p1");
            long elapsedMs = (System.nanoTime() - listed) / 1_000_000L;
            assertEquals(100, all.size());
            assertEquals("Inv 99", all.getFirst().name());
            assertTrue(elapsedMs < 2_000, "list 100 investigations took " + elapsedMs + " ms");
            assertTrue((listed - start) / 1_000_000L < 5_000);
        }
    }
}
