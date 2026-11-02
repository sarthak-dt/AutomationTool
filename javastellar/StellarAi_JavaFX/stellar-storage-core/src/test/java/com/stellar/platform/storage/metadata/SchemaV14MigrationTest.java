package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaV14MigrationTest {

    @TempDir
    Path temp;

    @Test
    void schemaAdvancesToFourteenAndStoresDiagnostics() {
        Path db = temp.resolve("v14.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), store.schemaVersion());
            assertTrue(store.tableExists("diagnostic_rule_packs"));
            assertTrue(store.tableExists("diagnostic_runs"));
            assertTrue(store.tableExists("diagnostic_findings"));
            assertTrue(store.tableExists("diagnostic_bindings"));
            assertTrue(store.tableExists("diagnostic_workspace"));
            store.upsertProject(new ProjectMetadata("p1", "A", Instant.now(), Instant.now(), "GRAPHS", null));
            store.upsertDiagnosticRulePack(new DiagnosticRulePackRow(
                    "p1", "generic-engineering", "1.0", "Generic", "ref", "BUILT_IN", "1",
                    "{\"schemaVersion\":1}", Instant.now()));
            store.upsertDiagnosticRun(new DiagnosticRunMetadata(
                    "r1", "p1", "generic-engineering", "1.0", "COMPLETED", "VIEWPORT",
                    1L, 2L, 5, 4, 1, "inv", 1L, "1", "ok", Instant.now(), Instant.now()));
            store.replaceDiagnosticFindings("r1", List.of(new DiagnosticFindingRow(
                    "f1", "r1", "p1", "thermal.high-coolant", "1", "WARNING", "THERMAL",
                    1L, 2L, "hot", "peak=107", "COOLANT=107", Instant.now())));
            store.upsertDiagnosticBinding(new DiagnosticBindingRow(
                    "p1", "generic-engineering", "COOLANT_TEMPERATURE", "CoolantTemperature|DBC_CAN|ECM|1536|1|a|b",
                    Instant.now()));
            store.upsertDiagnosticWorkspace(new DiagnosticWorkspaceMetadata(
                    "p1", "generic-engineering", "r1", "thermal.high-coolant", true, "f1", Instant.now()));
        }
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), reopened.schemaVersion());
            assertEquals("COMPLETED", reopened.findDiagnosticRun("r1").orElseThrow().status());
            assertEquals(1, reopened.listDiagnosticFindings("r1").size());
            assertEquals("r1", reopened.findDiagnosticWorkspace("p1").orElseThrow().lastRunId());
            assertEquals(1, reopened.listDiagnosticBindings("p1", "generic-engineering").size());
            assertEquals(1, reopened.invalidateDiagnosticRuns("p1", 2L));
            assertEquals("INVALIDATED", reopened.findDiagnosticRun("r1").orElseThrow().status());
        }
    }
}
