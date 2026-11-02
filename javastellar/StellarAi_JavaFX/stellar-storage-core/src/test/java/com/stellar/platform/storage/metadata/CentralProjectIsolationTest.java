package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CentralProjectIsolationTest {

    @TempDir Path temp;

    @Test
    void sameSignalNameAndFilenameStayProjectScoped() throws Exception {
        Path db = temp.resolve("central.db");
        Instant now = Instant.now();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata("1001", "Lavasa", now, now));
            store.upsertProject(new ProjectMetadata("1002", "Bengaluru", now, now));
            store.upsertFile(file("1001", "file-a", "/data/run.mf4"));
            store.upsertFile(file("1002", "file-b", "/data/run.mf4"));
            store.replaceSignalCatalog("1001", "art-a", List.of(signal("1001", "src-a", "art-a", "RPM")));
            store.replaceSignalCatalog("1002", "art-b", List.of(signal("1002", "src-b", "art-b", "RPM")));

            List<SignalCatalogMetadata> a = store.listSignalCatalog("1001");
            List<SignalCatalogMetadata> b = store.listSignalCatalog("1002");
            assertEquals(1, a.size());
            assertEquals(1, b.size());
            assertEquals("RPM", a.getFirst().signalName());
            assertEquals("RPM", b.getFirst().signalName());
            assertEquals("1001", a.getFirst().projectId());
            assertEquals("1002", b.getFirst().projectId());
            assertEquals("src-a", a.getFirst().sourceId());
            assertEquals("src-b", b.getFirst().sourceId());

            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + db);
                 var st = connection.createStatement();
                 var rs = st.executeQuery(
                         "EXPLAIN QUERY PLAN SELECT * FROM signal_catalog WHERE project_id='1001'")) {
                String plan = "";
                while (rs.next()) {
                    plan += rs.getString("detail") + " ";
                }
                assertTrue(plan.toLowerCase(java.util.Locale.ROOT).contains("idx_signal_catalog")
                                || plan.toLowerCase(java.util.Locale.ROOT).contains("project_id"),
                        plan);
            }

            store.deleteProject("1001");
            assertTrue(store.listSignalCatalog("1001").isEmpty());
            assertEquals(1, store.listSignalCatalog("1002").size());
            assertEquals("Bengaluru", store.listProjects().getFirst().name());
        }
    }

    private static ProjectFileMetadata file(String projectId, String fileId, String path) {
        return new ProjectFileMetadata(
                projectId, fileId, path, "MF4", 10L, "VALID", "", "", "",
                false, true, false);
    }

    private static SignalCatalogMetadata signal(String projectId, String sourceId, String artifactId, String name) {
        return new SignalCatalogMetadata(
                UUID.randomUUID().toString(), projectId, sourceId, 1, "def", artifactId,
                name + "|" + sourceId, name, "OBD2", 100, 1, "DBC_CAN", "rpm",
                42, 0L, 1L, 0d, 1d, Instant.now(), Instant.now());
    }
}
