package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VehicleSpeedMappingPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void speedMappingSurvivesReopenAndDoesNotWipeGpsRows() {
        Path db = tempDir.resolve("speed.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("p1", "Drive", now, now));
            store.upsertSemanticSignalMapping(SemanticSignalMappingMetadata.signalReference(
                    "p1", "a.mf4", "2:aaaa", "LATITUDE", "Lat", "MF4_NATIVE",
                    "a.mf4", "GPS", 0, true, now));
            store.upsertSemanticSignalMapping(new SemanticSignalMappingMetadata(
                    "p1", "a.mf4", "2:aaaa",
                    SemanticSignalMappingMetadata.GROUP_VEHICLE_SPEED,
                    SemanticSignalMappingMetadata.SCOPE_PROJECT,
                    SemanticSignalMappingMetadata.ROLE_AUTHORITATIVE_SPEED,
                    SemanticSignalMappingMetadata.MODE_SIGNAL_REFERENCE,
                    "VehSpd", "DBC_CAN", "a.mf4", "ESC", 1,
                    "1|AUTHORITATIVE_VEHICLE|HIGH|km/h|ok", false, now));
            store.deleteSemanticSignalMappings("p1", "a.mf4", SemanticSignalMappingMetadata.GROUP_MAP_ROUTE);
            List<SemanticSignalMappingMetadata> remaining = store.listSemanticSignalMappings("p1", "a.mf4");
            assertEquals(1, remaining.size());
            assertEquals(SemanticSignalMappingMetadata.GROUP_VEHICLE_SPEED, remaining.getFirst().configGroup());
            assertEquals("VehSpd", remaining.getFirst().signalName());
        }
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            List<SemanticSignalMappingMetadata> restored = store.listSemanticSignalMappings("p1", "a.mf4");
            assertEquals(1, restored.size());
            assertEquals("VehSpd", restored.getFirst().signalName());
            store.upsertSemanticSignalMapping(new SemanticSignalMappingMetadata(
                    "p1", "c.mf4", "3:cccc",
                    SemanticSignalMappingMetadata.GROUP_VEHICLE_SPEED,
                    SemanticSignalMappingMetadata.SCOPE_PROJECT,
                    SemanticSignalMappingMetadata.ROLE_AUTHORITATIVE_SPEED,
                    SemanticSignalMappingMetadata.MODE_SIGNAL_REFERENCE,
                    "ClusterSpeed", "DBC_CAN", "c.mf4", "ESC", 1,
                    "1|AUTHORITATIVE_VEHICLE|HIGH|km/h|ok", false, Instant.now()));
            assertEquals(1, store.listSemanticSignalMappings("p1", "a.mf4").size());
            assertEquals(1, store.listSemanticSignalMappings("p1", "c.mf4").size());
        }
    }

    @Test
    void speedRowKeepsPhysicalIdentityFields() {
        SemanticSignalMappingMetadata row = new SemanticSignalMappingMetadata(
                "p1", "a.mf4", "2:abcd",
                SemanticSignalMappingMetadata.GROUP_VEHICLE_SPEED,
                SemanticSignalMappingMetadata.SCOPE_PROJECT,
                SemanticSignalMappingMetadata.ROLE_AUTHORITATIVE_SPEED,
                SemanticSignalMappingMetadata.MODE_SIGNAL_REFERENCE,
                "VehSpd", "DBC_CAN", "a.mf4", "ESC", 2,
                "1|AUTHORITATIVE_VEHICLE|HIGH|km/h|ok", false, Instant.now());
        assertEquals("VehSpd", row.signalName());
        assertEquals(2, row.channel());
        assertEquals("a.mf4", row.sourceFile());
        assertTrue(row.manualText().contains("AUTHORITATIVE_VEHICLE"));
    }
}
