package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaV11MigrationTest {

    @TempDir
    Path temp;

    @Test
    void lastSavedAtAndGraphPresentationRoundTrip() {
        Instant saved = Instant.parse("2026-09-07T17:27:43Z");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(temp.resolve("v11.db"))) {
            store.upsertProject(new ProjectMetadata("p1", "Workspace", Instant.now(), Instant.now(), "GRAPHS", saved));
            store.upsertGraphInvestigation(new GraphInvestigationMetadata(
                    "p1", "OVERLAY", 100L, 200L, 100L, 200L, null, null, "",
                    "GIV1", Instant.now(), "focus", 2, false, true, "PHYSICAL", "P3",
                    GraphInvestigationMetadata.PRESENTATION_VERSION));
            ProjectMetadata project = store.loadProject("p1").orElseThrow().project();
            assertEquals(saved, project.lastSavedAt());
            assertEquals("GRAPHS", project.lastWorkspace());
            GraphInvestigationMetadata graph = store.findGraphInvestigation("p1").orElseThrow();
            assertEquals("OVERLAY", graph.layoutMode());
            assertEquals(2, graph.analysisTab());
            assertFalse(graph.inspectorVisible());
            assertTrue(graph.plotSettingsVisible());
            assertEquals("PHYSICAL", graph.signalsDisplayMode());
            assertEquals(1, graph.presentationVersion());
        }
    }
}
