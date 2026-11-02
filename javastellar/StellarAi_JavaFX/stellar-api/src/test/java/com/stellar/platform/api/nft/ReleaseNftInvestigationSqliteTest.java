package com.stellar.platform.api.nft;

import com.stellar.platform.storage.metadata.EngineeringInvestigationRow;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("release-nft")
class ReleaseNftInvestigationSqliteTest {

    @TempDir
    Path temp;

    @Test
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void investigationListDoesNotRequireWaveformHydration() throws Exception {
        Path db = temp.resolve("p/.stellar/stellar.db");
        Files.createDirectories(db.getParent());
        String projectId = UUID.randomUUID().toString();
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(projectId, "inv", Instant.now(), Instant.now(), "DECODE", null));
            Instant now = Instant.now();
            int[] counts = {1, 10, 100};
            for (int n : counts) {
                for (int i = 0; i < n; i++) {
                    store.upsertEngineeringInvestigation(new EngineeringInvestigationRow(
                            "inv-" + n + "-" + i, projectId, "I" + i, "meta only", "DRAFT",
                            0L, 1_000_000L, "", "STACKED", 0L, 1_000_000L, null, null, "",
                            "", "", now, now));
                }
                long t0 = System.nanoTime();
                var list = store.listEngineeringInvestigations(projectId);
                long listMs = (System.nanoTime() - t0) / 1_000_000L;
                long r0 = System.nanoTime();
                assertTrue(store.loadProject(projectId).isPresent());
                var one = store.findEngineeringInvestigation("inv-" + n + "-0");
                long openMs = (System.nanoTime() - r0) / 1_000_000L;
                System.out.println("PHASE9C_INV n=" + n + " listMs=" + listMs + " openOneMs=" + openMs
                        + " listed=" + list.size() + " onePresent=" + one.isPresent()
                        + " note=metadata-only-no-waveform");
                assertEquals(n == 1 ? 1 : (n == 10 ? 11 : 111), list.size());
            }
        }
    }
}
