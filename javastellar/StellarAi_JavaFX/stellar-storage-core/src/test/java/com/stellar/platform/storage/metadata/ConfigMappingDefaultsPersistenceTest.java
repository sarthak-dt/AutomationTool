package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Product defaults are installation-wide: every project on the machine reads the same rows, which is
 * the whole point of having them outside the project database.
 */
class ConfigMappingDefaultsPersistenceTest {

    @TempDir
    Path temp;

    private static SemanticSignalMappingMetadata defaultFor(String role, String typedName) {
        return new SemanticSignalMappingMetadata("", "", "FleetA",
                SemanticSignalMappingMetadata.GROUP_MAP_ROUTE,
                SemanticSignalMappingMetadata.SCOPE_DEFAULT,
                role,
                SemanticSignalMappingMetadata.MODE_MANUAL_TEXT,
                "", "", "", "GPS", 0, typedName, true, Instant.now());
    }

    @Test
    void defaultsSurviveReopenAndAreReadableWithoutAnyProject() {
        Path db = temp.resolve("defaults.db");
        try (SqliteConfigMappingDefaultsStore store = new SqliteConfigMappingDefaultsStore(db)) {
            store.upsertDefaultMapping(defaultFor("LATITUDE", "Fleet_Lat"));
            store.upsertDefaultMapping(defaultFor("LONGITUDE", "Fleet_Lon"));
        }
        try (SqliteConfigMappingDefaultsStore store = new SqliteConfigMappingDefaultsStore(db)) {
            List<SemanticSignalMappingMetadata> rows = store.listDefaultMappings(
                    SemanticSignalMappingMetadata.GROUP_MAP_ROUTE);
            assertEquals(2, rows.size());
            assertEquals("Fleet_Lat", rows.getFirst().manualText());
            assertEquals(SemanticSignalMappingMetadata.SCOPE_DEFAULT, rows.getFirst().scope());
            assertEquals("FleetA", rows.getFirst().schemaFingerprint(), "product context is kept");
            assertTrue(rows.getFirst().manual());
        }
    }

    @Test
    void savingTheSameFieldTwiceReplacesItRatherThanAccumulating() {
        Path db = temp.resolve("replace.db");
        try (SqliteConfigMappingDefaultsStore store = new SqliteConfigMappingDefaultsStore(db)) {
            store.upsertDefaultMapping(defaultFor("LATITUDE", "Old_Lat"));
            store.upsertDefaultMapping(defaultFor("LATITUDE", "New_Lat"));
            List<SemanticSignalMappingMetadata> rows = store.listDefaultMappings(
                    SemanticSignalMappingMetadata.GROUP_MAP_ROUTE);
            assertEquals(1, rows.size());
            assertEquals("New_Lat", rows.getFirst().manualText());
        }
    }

    @Test
    void anotherConfigurationGroupIsNotReturnedOrDeletedByAccident() {
        Path db = temp.resolve("groups.db");
        try (SqliteConfigMappingDefaultsStore store = new SqliteConfigMappingDefaultsStore(db)) {
            store.upsertDefaultMapping(defaultFor("LATITUDE", "Fleet_Lat"));
            store.upsertDefaultMapping(new SemanticSignalMappingMetadata("", "", "",
                    "OTHER_GROUP", SemanticSignalMappingMetadata.SCOPE_DEFAULT, "LATITUDE",
                    SemanticSignalMappingMetadata.MODE_MANUAL_TEXT,
                    "", "", "", "GPS", 0, "Other_Lat", true, Instant.now()));

            store.deleteDefaultMappings(SemanticSignalMappingMetadata.GROUP_MAP_ROUTE);
            assertTrue(store.listDefaultMappings(SemanticSignalMappingMetadata.GROUP_MAP_ROUTE).isEmpty());
            assertEquals(1, store.listDefaultMappings("OTHER_GROUP").size());
        }
    }
}
