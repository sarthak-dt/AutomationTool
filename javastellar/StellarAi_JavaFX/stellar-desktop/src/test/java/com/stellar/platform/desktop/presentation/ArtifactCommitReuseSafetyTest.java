package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import com.stellar.platform.storage.ArtifactStorageRefs;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.analysis.AnalysisStorageSettingsStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Incremental persist must never delete-then-move an already-durable artifact root.
 */
class ArtifactCommitReuseSafetyTest {

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
    void stagingDetectionRejectsFinalRootEqualsStoreRoot() throws Exception {
        Method method = DesktopAppService.class.getDeclaredMethod(
                "isStagingArtifactRoot", Path.class, Path.class);
        method.setAccessible(true);
        Path finalRoot = temp.resolve("job-root");
        Path staging = finalRoot.resolveSibling(finalRoot.getFileName().toString() + ".staging");
        Files.createDirectories(staging);
        assertTrue((Boolean) method.invoke(null, staging, finalRoot));
        assertFalse((Boolean) method.invoke(null, finalRoot, finalRoot));
    }

    @Test
    void commitRefusesWhenStagingEqualsFinalRoot() throws Exception {
        Method method = DesktopAppService.class.getDeclaredMethod(
                "commitArtifactDirectory", Path.class, Path.class);
        method.setAccessible(true);
        Path root = temp.resolve("durable");
        Files.createDirectories(root);
        Files.writeString(root.resolve("keep.txt"), "safe");
        try {
            method.invoke(null, root, root);
            throw new AssertionError("expected commit refusal");
        } catch (java.lang.reflect.InvocationTargetException ex) {
            assertTrue(ex.getCause() instanceof IllegalStateException);
            assertTrue(ex.getCause().getMessage().contains("equals final root"));
        }
        assertTrue(Files.isRegularFile(root.resolve("keep.txt")),
                "durable payload must survive refused commit");
    }

    @Test
    void reusedDurableStoreKeepsSegmentsAfterCommitGuard() throws Exception {
        configureStorage();
        Path finalRoot = ArtifactStorageRefs.jobRoot("p1", "srcA", 1, "dbc");
        Path staging = finalRoot.resolveSibling(finalRoot.getFileName().toString() + ".staging");
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(staging, "a.mf4", "d.dbc")) {
            write.accept(List.of(signal("S", 0, 1_000L, 1.0)));
            write.finishWriteSession();
        }
        Method commit = DesktopAppService.class.getDeclaredMethod(
                "commitArtifactDirectory", Path.class, Path.class);
        commit.setAccessible(true);
        commit.invoke(null, staging, finalRoot);
        assertTrue(LosslessGraphSignalStore.segmentsReadable(finalRoot));

        Method isStaging = DesktopAppService.class.getDeclaredMethod(
                "isStagingArtifactRoot", Path.class, Path.class);
        isStaging.setAccessible(true);
        assertFalse((Boolean) isStaging.invoke(null, finalRoot, finalRoot));

        try (GraphSignalSessionStore durable = GraphSignalSessionStore.openExisting(finalRoot)) {
            var key = durable.snapshot().entries().getFirst().key();
            assertFalse(durable.querySamples(key, 0, 10).isEmpty());
        }
        assertTrue(LosslessGraphSignalStore.segmentsReadable(finalRoot));
    }

    private void configureStorage() throws Exception {
        previousHome = System.getProperty("stellarai.home");
        previousDisableAuto = System.getProperty("stellarai.analysis.storage.disable-auto");
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        Path analysisRoot = temp.resolve("analysis-root");
        Files.createDirectories(analysisRoot);
        AnalysisStorageService storage = new AnalysisStorageService(
                new AnalysisStorageSettingsStore(temp.resolve("cfg.properties")));
        assertTrue(storage.save(analysisRoot).valid());
    }

    private static CanonicalSignal signal(String name, int channel, long ts, double value) {
        return new CanonicalSignal(name, "MSG", ts, 0x100, channel, value, 1L, "u", null,
                SourceReference.ofLine(1));
    }
}
