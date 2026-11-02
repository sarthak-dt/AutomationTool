package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyProjectImportTest {

    @TempDir Path temp;

    @Test
    void importIsIdempotentAndLeavesLegacyFileUntouched() throws Exception {
        Path legacy = temp.resolve("legacy-project").resolve(".stellar").resolve("stellar.db");
        Files.createDirectories(legacy.getParent());
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore source = new SqliteProjectMetadataStore(legacy)) {
            source.upsertProject(new ProjectMetadata("legacy-uuid", "Lavasa Copy", now, now));
            source.replaceSignalCatalog("legacy-uuid", "art", List.of(
                    new SignalCatalogMetadata(
                            "sig-1", "legacy-uuid", "src-1", 1, "def", "art",
                            "RPM|src-1", "RPM", "OBD2", 100, 1, "DBC_CAN", "rpm",
                            42, 0L, 1L, 0d, 1d, now, now)));
        }
        long size = Files.size(legacy);

        Path central = temp.resolve("central").resolve("stellar.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(central)) {
            List<LegacyProjectImporter.Result> first = store.importLegacyDatabase(legacy);
            assertEquals(1, first.size());
            assertTrue(first.getFirst().imported());
            assertEquals("legacy-uuid", first.getFirst().projectId());
            assertTrue(first.getFirst().numericId() >= 1001L);
            assertEquals(1, store.listProjects().size());
            assertEquals("Lavasa Copy", store.listProjects().getFirst().name());
            List<SignalCatalogMetadata> catalog = store.listSignalCatalog("legacy-uuid");
            assertEquals(1, catalog.size());
            assertEquals("RPM", catalog.getFirst().signalName());
            assertEquals("legacy-uuid", catalog.getFirst().projectId());

            List<LegacyProjectImporter.Result> second = store.importLegacyDatabase(legacy);
            assertEquals(1, second.size());
            assertTrue(!second.getFirst().imported());
            assertEquals(1, store.listProjects().size());
        }
        assertEquals(size, Files.size(legacy), "legacy DB must remain untouched");
    }

    @Test
    void failedImportDoesNotLeavePartialProject() {
        Path central = temp.resolve("central.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(central)) {
            Path missing = temp.resolve("missing.db");
            List<LegacyProjectImporter.Result> result = store.importLegacyDatabase(missing);
            assertTrue(result.isEmpty());
            assertTrue(store.listProjects().isEmpty());
        }
    }

    @Test
    void corruptLegacyDatabaseDoesNotCreateAProject() throws Exception {
        Path central = temp.resolve("central.db");
        Path corrupt = temp.resolve("corrupt.db");
        Files.writeString(corrupt, "this is not sqlite");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(central)) {
            try {
                store.importLegacyDatabase(corrupt);
            } catch (RuntimeException ignored) {
                // importer must fail closed
            }
            assertTrue(store.listProjects().isEmpty());
        }
    }
}
