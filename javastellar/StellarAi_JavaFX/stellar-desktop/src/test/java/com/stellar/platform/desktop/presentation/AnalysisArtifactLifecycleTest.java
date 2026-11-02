package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.processing.execution.ProcessingResultRegistry;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import com.stellar.platform.storage.ArtifactStorageRefs;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.analysis.AnalysisStorageSettingsStore;
import com.stellar.platform.storage.metadata.FileFingerprint;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Analysis Storage artifact isolation, reopen, stale fingerprint, and corrupt rejection.
 */
class AnalysisArtifactLifecycleTest {

    @TempDir Path temp;
    private String previousHome;
    private String previousDisableAuto;

    @AfterEach
    void tearDown() {
        AnalysisStorageRoots.clear();
        if (previousHome == null) {
            System.clearProperty("stellarai.home");
        } else {
            System.setProperty("stellarai.home", previousHome);
        }
        if (previousDisableAuto == null) {
            System.clearProperty("stellarai.analysis.storage.disable-auto");
        } else {
            System.setProperty("stellarai.analysis.storage.disable-auto", previousDisableAuto);
        }
    }

    @Test
    void projectArtifactsAreIsolatedUnderConfiguredRoot() throws Exception {
        Path userRoot = configureStorage();
        Path a = ArtifactStorageRefs.jobRoot("proj-A", "src1", 0, "dbc1");
        Path b = ArtifactStorageRefs.jobRoot("proj-B", "src1", 0, "dbc1");
        assertFalse(a.startsWith(b) || b.startsWith(a));
        assertTrue(a.toString().contains("proj-A"));
        assertTrue(b.toString().contains("proj-B"));
        assertTrue(a.startsWith(userRoot.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME)));
    }

    @Test
    void segmentsReadableRequiresPayloadFilesNotOnlyIndex() throws Exception {
        configureStorage();
        Path root = ArtifactStorageRefs.jobRoot("p-seg", "m1", 0, "d1");
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, "m.mf4", "d.dbc")) {
            write.accept(List.of(signal("EngineSpeed", 0, 1_000_000L, 1000.0)));
            write.finishWriteSession();
        }
        assertTrue(LosslessGraphSignalStore.hasValidIndex(root));
        assertTrue(LosslessGraphSignalStore.segmentsReadable(root));

        // Simulate the production failure mode: index retained, segment binaries deleted.
        try (var walk = Files.walk(root)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".bin"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
        assertTrue(LosslessGraphSignalStore.hasValidIndex(root));
        assertFalse(LosslessGraphSignalStore.segmentsReadable(root),
                "index without segment payloads must not be treated as reusable");
    }

    @Test
    void reopenUsesPersistedStoreWithoutRewritingSamples() throws Exception {
        configureStorage();
        Path root = ArtifactStorageRefs.jobRoot("p1", "m1", 0, "d1");
        AtomicInteger writes = new AtomicInteger();
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, "m.mf4", "d.dbc")) {
            write.accept(List.of(signal("EngineSpeed", 0, 1_000_000L, 1000.0)));
            writes.incrementAndGet();
            write.finishWriteSession();
        }
        assertTrue(LosslessGraphSignalStore.hasValidIndex(root));

        try (GraphSignalSessionStore read = GraphSignalSessionStore.openExisting(root)) {
            assertEquals(1, read.snapshot().entries().size());
            assertEquals(1, writes.get(), "reopen must not invent a second write pass");
            var key = read.snapshot().entries().getFirst().key();
            var series = read.querySamples(key, 0, 64);
            assertFalse(series.isEmpty());
            assertEquals(1000.0, series.getFirst().physicalValue(), 1e-9);
            assertEquals(1_000_000L, series.getFirst().timestampNanos());
        }
    }

    @Test
    void staleFingerprintDoesNotMatchChangedSource() throws Exception {
        Path source = temp.resolve("a.mf4");
        Files.writeString(source, "version-1");
        String fp = FileFingerprint.of(source);
        Thread.sleep(5);
        Files.writeString(source, "version-2-changed-bytes");
        assertFalse(FileFingerprint.matches(fp, source));
    }

    @Test
    void corruptArtifactMissingIndexIsRejected() throws Exception {
        Path analysisRoot = temp.resolve("analysis-root");
        Files.createDirectories(analysisRoot);
        previousHome = System.getProperty("stellarai.home");
        previousDisableAuto = System.getProperty("stellarai.analysis.storage.disable-auto");
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");

        AnalysisStorageService storage = new AnalysisStorageService(
                new AnalysisStorageSettingsStore(temp.resolve("cfg.properties")));
        assertTrue(storage.save(analysisRoot).valid());

        Path root = ArtifactStorageRefs.jobRoot("p1", "m1", 0, "d1");
        Files.createDirectories(root);
        Files.writeString(root.resolve("garbage.bin"), "not-an-index");
        assertFalse(LosslessGraphSignalStore.hasValidIndex(root));

        DesktopAppService service = new DesktopAppService(new DesktopSession(), storage);
        ProcessedProjectHydrator hydrator = new ProcessedProjectHydrator(service);
        ProcessingArtifactMetadata artifact = new ProcessingArtifactMetadata(
                "art-1", "p1", "m1", 0, "d1", "run-1",
                ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                "",
                ArtifactStorageRefs.forJob("p1", "m1", 0, "d1"),
                ProcessingArtifactMetadata.ARTIFACT_VERSION_V1,
                "v=2;size=1;mtime=1;name=a.mf4",
                "",
                1, 1, 0L, 1L,
                ProcessingArtifactMetadata.STATUS_VALID,
                Instant.now(),
                "MANUAL"
        );
        ProcessedProjectHydrator.HydrationResult result = hydrator.hydrate(
                List.of(artifact), new ProcessingResultRegistry());
        assertEquals(0, result.validArtifacts());
        assertTrue(result.missingArtifacts() >= 1);
        assertTrue(result.warnings().stream().anyMatch(w -> w.toLowerCase().contains("reprocessing")));
        service.shutdown();
    }

    @Test
    void incompleteStagingIsNotValidCompletedArtifact() throws Exception {
        configureStorage();
        Path finalRoot = ArtifactStorageRefs.jobRoot("p1", "m1", 0, "d1");
        Path staging = finalRoot.resolveSibling(finalRoot.getFileName().toString() + ".staging");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("partial.bin"), "incomplete");
        assertFalse(LosslessGraphSignalStore.hasValidIndex(staging));
        assertFalse(LosslessGraphSignalStore.hasValidIndex(finalRoot));
    }

    private Path configureStorage() throws Exception {
        previousHome = System.getProperty("stellarai.home");
        previousDisableAuto = System.getProperty("stellarai.analysis.storage.disable-auto");
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        Path root = temp.resolve("analysis-root");
        Files.createDirectories(root);
        AnalysisStorageRoots.setUserSelectedRoot(root);
        AnalysisStorageRoots.ensureControlledDirectory();
        return root.toAbsolutePath().normalize();
    }

    private static CanonicalSignal signal(String name, int channel, long ts, double value) {
        return new CanonicalSignal(name, "Engine", ts, 0x100L, channel, value, null, "rpm", null,
                SourceReference.ofLine(1));
    }
}
