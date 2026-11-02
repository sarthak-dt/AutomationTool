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
import com.stellar.platform.storage.metadata.ProcessingPlanFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STEP 4 durable artifact lifecycle: corrupt / partial / version / fingerprint rejection
 * and reopen of VALID lossless stores without rewriting samples.
 */
class DurableArtifactLifecycleTest {

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
    void validArtifactReopensWithoutRewrite() throws Exception {
        configureStorage();
        Path source = temp.resolve("m.asc");
        Path definition = temp.resolve("d.dbc");
        Files.writeString(source, "asc-v1");
        Files.writeString(definition, "VERSION \"\"");
        Path root = ArtifactStorageRefs.jobRoot("p1", "src1", 0, "def1");
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, "m.asc", "d.dbc")) {
            write.accept(List.of(signal("Sig", 0, 1_000_000L, 42.0)));
            write.finishWriteSession();
        }
        String planFp = ProcessingPlanFingerprint.forJob("src1", 0, "def1", "CONFIRMED", "ASC");
        DurableArtifactLifecycle.Check check = DurableArtifactLifecycle.validateOnDisk(
                artifact("p1", "src1", 0, "def1", root, source, definition, planFp,
                        ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT,
                        ProcessingArtifactMetadata.STATUS_VALID),
                root, source, definition, planFp);
        assertTrue(check.reusable());

        try (GraphSignalSessionStore read = GraphSignalSessionStore.openExisting(root)) {
            assertEquals(1, read.snapshot().entries().size());
            var key = read.snapshot().entries().getFirst().key();
            assertEquals(42.0, read.querySamples(key, 0, 8).getFirst().physicalValue(), 1e-9);
        }
        assertEquals(GraphSignalSessionStore.DECODER_GENERATION, ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT);
    }

    @Test
    void previousDecoderGenerationRequiresReprocessingAndKeepsTheMeasurementFile() throws Exception {
        configureStorage();
        Path source = temp.resolve("m.asc");
        Files.writeString(source, "asc-v2");
        Path root = ArtifactStorageRefs.jobRoot("p1", "src1", 0, "def1");
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, "m.asc", "d.dbc")) {
            write.accept(List.of(signal("Sig", 0, 1L, 9340.0)));
            write.finishWriteSession();
        }
        DurableArtifactLifecycle.Check check = DurableArtifactLifecycle.validateOnDisk(
                artifact("p1", "src1", 0, "def1", root, source, null, "",
                        ProcessingArtifactMetadata.ARTIFACT_VERSION_V2,
                        ProcessingArtifactMetadata.STATUS_VALID),
                root, source, null, "");
        assertEquals(DurableArtifactLifecycle.Outcome.STALE, check.outcome());
        assertTrue(check.warning().contains("decoder generation"));
        assertEquals("asc-v2", Files.readString(source));
        try (GraphSignalSessionStore read = GraphSignalSessionStore.openExisting(root)) {
            var key = read.snapshot().entries().getFirst().key();
            assertEquals(9340.0, read.querySamples(key, 0, 1).getFirst().physicalValue(), 0.0);
        }
    }

    @Test
    void corruptIndexRequiresReprocessing() throws Exception {
        configureStorage();
        Path root = ArtifactStorageRefs.jobRoot("p1", "src1", 0, "def1");
        Files.createDirectories(root);
        Files.writeString(root.resolve("garbage.bin"), "not-an-index");
        DurableArtifactLifecycle.Check check = DurableArtifactLifecycle.validateOnDisk(
                artifact("p1", "src1", 0, "def1", root, null, null, "",
                        ProcessingArtifactMetadata.ARTIFACT_VERSION_V1,
                        ProcessingArtifactMetadata.STATUS_VALID),
                root, null, null, "");
        assertEquals(DurableArtifactLifecycle.Outcome.MISSING, check.outcome());
        assertTrue(check.warning().toLowerCase().contains("reprocessing"));
    }

    @Test
    void partialStagingIsNeverReusable() throws Exception {
        configureStorage();
        Path finalRoot = ArtifactStorageRefs.jobRoot("p1", "src1", 0, "def1");
        Path staging = finalRoot.resolveSibling(finalRoot.getFileName().toString() + ".staging");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("partial.bin"), "incomplete");
        assertFalse(LosslessGraphSignalStore.hasValidIndex(staging));
        assertFalse(LosslessGraphSignalStore.hasValidIndex(finalRoot));
        DurableArtifactLifecycle.Check check = DurableArtifactLifecycle.validateOnDisk(
                artifact("p1", "src1", 0, "def1", staging, null, null, "",
                        ProcessingArtifactMetadata.ARTIFACT_VERSION_V1,
                        ProcessingArtifactMetadata.STATUS_VALID),
                staging, null, null, "");
        assertEquals(DurableArtifactLifecycle.Outcome.MISSING, check.outcome());
    }

    @Test
    void legacyArtifactVersionRequiresReprocessingAndKeepsTheMeasurementFile() throws Exception {
        configureStorage();
        Path source = temp.resolve("m.asc");
        Files.writeString(source, "asc-v1");
        Path root = ArtifactStorageRefs.jobRoot("p1", "src1", 0, "def1");
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, "m.asc", "d.dbc")) {
            write.accept(List.of(signal("Sig", 0, 1L, 1.0)));
            write.finishWriteSession();
        }
        DurableArtifactLifecycle.Check check = DurableArtifactLifecycle.validateOnDisk(
                artifact("p1", "src1", 0, "def1", root, source, null, "",
                        ProcessingArtifactMetadata.ARTIFACT_VERSION_V1,
                        ProcessingArtifactMetadata.STATUS_VALID),
                root, source, null, "");
        assertEquals(DurableArtifactLifecycle.Outcome.STALE, check.outcome());
        assertFalse(check.reusable());
        assertTrue(check.warning().toLowerCase().contains("reprocessing"));
        assertTrue(Files.exists(source));
        assertEquals("asc-v1", Files.readString(source));
    }

    @Test
    void unavailableMeasurementFileIsNotReusableAndIsNotDeleted() throws Exception {
        configureStorage();
        Path source = temp.resolve("missing.asc");
        Files.writeString(source, "asc-v1");
        Path root = ArtifactStorageRefs.jobRoot("p1", "src1", 0, "def1");
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, "m.asc", "d.dbc")) {
            write.accept(List.of(signal("Sig", 0, 1L, 1.0)));
            write.finishWriteSession();
        }
        Files.delete(source);
        DurableArtifactLifecycle.Check check = DurableArtifactLifecycle.validateOnDisk(
                artifact("p1", "src1", 0, "def1", root, source, null, "",
                        ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT,
                        ProcessingArtifactMetadata.STATUS_VALID),
                root, source, null, "");
        assertEquals(DurableArtifactLifecycle.Outcome.STALE, check.outcome());
        assertTrue(check.warning().toLowerCase().contains("unavailable"));
        assertFalse(Files.exists(source));
    }

    @Test
    void versionMismatchRequiresReprocessing() throws Exception {
        configureStorage();
        Path root = ArtifactStorageRefs.jobRoot("p1", "src1", 0, "def1");
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, "m.asc", "d.dbc")) {
            write.accept(List.of(signal("Sig", 0, 1L, 1.0)));
            write.finishWriteSession();
        }
        DurableArtifactLifecycle.Check check = DurableArtifactLifecycle.validateOnDisk(
                artifact("p1", "src1", 0, "def1", root, null, null, "",
                        "99",
                        ProcessingArtifactMetadata.STATUS_VALID),
                root, null, null, "");
        assertEquals(DurableArtifactLifecycle.Outcome.STALE, check.outcome());
        assertTrue(check.warning().toLowerCase().contains("reprocessing"));
    }

    @Test
    void sourceFingerprintMismatchRequiresReprocessing() throws Exception {
        configureStorage();
        Path source = temp.resolve("m.asc");
        Files.writeString(source, "v1");
        Path root = ArtifactStorageRefs.jobRoot("p1", "src1", 0, "def1");
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, "m.asc", "d.dbc")) {
            write.accept(List.of(signal("Sig", 0, 1L, 1.0)));
            write.finishWriteSession();
        }
        ProcessingArtifactMetadata meta = artifact("p1", "src1", 0, "def1", root, source, null, "",
                ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT,
                ProcessingArtifactMetadata.STATUS_VALID);
        Files.writeString(source, "v2-changed");
        DurableArtifactLifecycle.Check check = DurableArtifactLifecycle.validateOnDisk(
                meta, root, source, null, "");
        assertEquals(DurableArtifactLifecycle.Outcome.STALE, check.outcome());
        assertTrue(check.warning().toLowerCase().contains("fingerprint"));
    }

    @Test
    void blankExpectedPlanFingerprintDoesNotInvalidateStoredProvenance() throws Exception {
        configureStorage();
        Path root = ArtifactStorageRefs.jobRoot("p1", "src1", 0, "def1");
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, "m.asc", "d.dbc")) {
            write.accept(List.of(signal("Sig", 0, 1L, 1.0)));
            write.finishWriteSession();
        }
        String stored = ProcessingPlanFingerprint.forJob("src1", 0, "def1", "CONFIRMED", "ASC");
        DurableArtifactLifecycle.Check check = DurableArtifactLifecycle.validateOnDisk(
                artifact("p1", "src1", 0, "def1", root, null, null, stored,
                        ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT,
                        ProcessingArtifactMetadata.STATUS_VALID),
                root, null, null, "");
        assertTrue(check.reusable(), "hydrator verify with blank expected must reuse valid artifacts");
        assertEquals(DurableArtifactLifecycle.Outcome.VALID, check.outcome());
        assertTrue(check.warning().isBlank());
    }

    @Test
    void hydratorDoesNotWarnWhenPlanCheckIsBlankAndArtifactsAreValid() throws Exception {
        Path analysisRoot = temp.resolve("analysis-root-valid");
        Files.createDirectories(analysisRoot);
        previousHome = System.getProperty("stellarai.home");
        previousDisableAuto = System.getProperty("stellarai.analysis.storage.disable-auto");
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        AnalysisStorageService storage = new AnalysisStorageService(
                new AnalysisStorageSettingsStore(temp.resolve("cfg-valid.properties")));
        assertTrue(storage.save(analysisRoot).valid());

        Path root = ArtifactStorageRefs.jobRoot("p1", "src1", 0, "def1");
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, "m.asc", "d.dbc")) {
            write.accept(List.of(signal("Sig", 0, 1L, 1.0)));
            write.finishWriteSession();
        }
        String stored = ProcessingPlanFingerprint.forJob("src1", 0, "def1", "CONFIRMED", "ASC");
        DesktopAppService service = new DesktopAppService(new DesktopSession(), storage);
        ProcessedProjectHydrator.HydrationResult result = new ProcessedProjectHydrator(service).hydrate(
                List.of(artifact("p1", "src1", 0, "def1", root, null, null, stored,
                        ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT,
                        ProcessingArtifactMetadata.STATUS_VALID)),
                new ProcessingResultRegistry());
        assertEquals(1, result.validArtifacts());
        assertEquals(0, result.staleArtifacts());
        assertFalse(result.reprocessingRequired());
        assertTrue(result.warnings().stream().noneMatch(w -> w.toLowerCase().contains("fingerprint")));
        service.shutdown();
    }

    @Test
    void planFingerprintMismatchRequiresReprocessing() throws Exception {
        configureStorage();
        Path root = ArtifactStorageRefs.jobRoot("p1", "src1", 0, "def1");
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, "m.asc", "d.dbc")) {
            write.accept(List.of(signal("Sig", 0, 1L, 1.0)));
            write.finishWriteSession();
        }
        String stored = ProcessingPlanFingerprint.forJob("src1", 0, "def1", "CONFIRMED", "ASC");
        String expected = ProcessingPlanFingerprint.forJob("src1", 0, "def1", "MANUAL", "ASC");
        DurableArtifactLifecycle.Check check = DurableArtifactLifecycle.validateOnDisk(
                artifact("p1", "src1", 0, "def1", root, null, null, stored,
                        ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT,
                        ProcessingArtifactMetadata.STATUS_VALID),
                root, null, null, expected);
        assertEquals(DurableArtifactLifecycle.Outcome.STALE, check.outcome());
        assertTrue(check.warning().toLowerCase().contains("plan"));
    }

    @Test
    void segmentBytesThatDisagreeWithTheIndexAreNotReusable() throws Exception {
        configureStorage();
        Path root = ArtifactStorageRefs.jobRoot("p1", "src1", 0, "def1");
        try (GraphSignalSessionStore write = GraphSignalSessionStore.createPersistent(root, "m.asc", "d.dbc")) {
            write.accept(List.of(signal("Sig", 0, 1_000_000L, 42.0), signal("Sig", 0, 2_000_000L, 43.0)));
            write.finishWriteSession();
        }
        Path segment = Files.walk(root)
                .filter(path -> path.getFileName().toString().endsWith(".bin"))
                .findFirst()
                .orElseThrow();
        byte[] bytes = Files.readAllBytes(segment);
        Files.write(segment, java.util.Arrays.copyOf(bytes, Math.max(1, bytes.length / 2)));
        DurableArtifactLifecycle.Check check = DurableArtifactLifecycle.validateOnDisk(
                artifact("p1", "src1", 0, "def1", root, null, null, "",
                        ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT,
                        ProcessingArtifactMetadata.STATUS_VALID),
                root, null, null, "");
        assertEquals(DurableArtifactLifecycle.Outcome.STALE, check.outcome());
        assertTrue(check.warning().contains("disagree"));
        assertFalse(check.reusable());
    }

    @Test
    void hydratorSurfacesReprocessingRequiredForCorruptArtifact() throws Exception {
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

        DesktopAppService service = new DesktopAppService(new DesktopSession(), storage);
        ProcessedProjectHydrator.HydrationResult result = new ProcessedProjectHydrator(service).hydrate(
                List.of(artifact("p1", "m1", 0, "d1", root, null, null, "",
                        ProcessingArtifactMetadata.ARTIFACT_VERSION_V1,
                        ProcessingArtifactMetadata.STATUS_VALID)),
                new ProcessingResultRegistry());
        assertEquals(0, result.validArtifacts());
        assertTrue(result.reprocessingRequired());
        assertTrue(result.warnings().stream().anyMatch(w -> w.toLowerCase().contains("reprocessing")));
        service.shutdown();
    }

    @Test
    void twoProjectArtifactRootsAreIsolated() throws Exception {
        Path userRoot = configureStorage();
        Path a = ArtifactStorageRefs.jobRoot("proj-A", "src", 0, "dbc");
        Path b = ArtifactStorageRefs.jobRoot("proj-B", "src", 0, "dbc");
        assertFalse(a.equals(b));
        assertTrue(a.toString().contains("proj-A"));
        assertTrue(b.toString().contains("proj-B"));
        assertTrue(a.startsWith(userRoot.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME)));
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

    private static ProcessingArtifactMetadata artifact(
            String projectId,
            String sourceId,
            int channelId,
            String definitionId,
            Path root,
            Path measurement,
            Path definition,
            String planFingerprint,
            String version,
            String status
    ) {
        String ref = ArtifactStorageRefs.forJob(projectId, sourceId, channelId, definitionId);
        return new ProcessingArtifactMetadata(
                projectId + "|" + sourceId + "|" + channelId + "|" + definitionId,
                projectId,
                sourceId,
                channelId,
                definitionId,
                "run-1",
                ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                "",
                ref,
                version,
                measurement == null ? "" : FileFingerprint.of(measurement),
                definition == null ? "" : FileFingerprint.of(definition),
                planFingerprint,
                1, 1, 0L, 1L,
                status,
                Instant.now(),
                "CONFIRMED",
                "SUCCESS_WITH_WARNINGS",
                "PIPELINE_DIAGNOSTIC|" + sourceId + "|" + channelId + "|coverage warning",
                "tech"
        );
    }

    private static CanonicalSignal signal(String name, int channel, long ts, double value) {
        return new CanonicalSignal(name, "Engine", ts, 0x100L, channel, value, null, "rpm", null,
                SourceReference.ofLine(1));
    }
}
