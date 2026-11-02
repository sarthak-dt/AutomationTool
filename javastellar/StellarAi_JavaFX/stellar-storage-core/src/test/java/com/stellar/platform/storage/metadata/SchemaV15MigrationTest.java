package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaV15MigrationTest {

    @TempDir
    Path temp;

    @Test
    void schemaAdvancesToFifteenAndStoresReviews() {
        Path db = temp.resolve("v15.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), store.schemaVersion());
            assertTrue(store.tableExists("diagnostic_finding_reviews"));
            assertTrue(store.tableExists("diagnostic_rule_preferences"));
            store.upsertProject(new ProjectMetadata("p1", "A", Instant.now(), Instant.now(), "GRAPHS", null));
            store.upsertDiagnosticRun(new DiagnosticRunMetadata(
                    "r1", "p1", "generic-engineering", "1.0", "COMPLETED", "VIEWPORT",
                    1L, 2L, 5, 4, 1, "inv", 1L, "1", "ok", Instant.now(), Instant.now()));
            store.replaceDiagnosticFindings("r1", List.of(new DiagnosticFindingRow(
                    "f1", "r1", "p1", "thermal.high-coolant", "1", "WARNING", "THERMAL",
                    1L, 2L, "hot", "peak=107", "COOLANT=107", Instant.now())));
            store.upsertDiagnosticFindingReview(new DiagnosticFindingReviewRow(
                    "f1", "p1", "REVIEWED", "Occurred during aggressive brake test.", Instant.now()));
            store.upsertDiagnosticRulePreference(new DiagnosticRulePreferenceRow(
                    "p1", "generic-engineering", "thermal.high-coolant", false, Instant.now()));
            assertEquals(1, store.listDiagnosticRuns("p1").size());
        }
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            assertEquals(SqliteProjectMetadataStore.currentSchemaVersion(), reopened.schemaVersion());
            var review = reopened.findDiagnosticFindingReview("f1").orElseThrow();
            assertEquals("REVIEWED", review.reviewState());
            assertEquals("Occurred during aggressive brake test.", review.engineerNote());
            assertEquals(false, reopened.listDiagnosticRulePreferences("p1", "generic-engineering")
                    .getFirst().enabled());
        }
    }
}
