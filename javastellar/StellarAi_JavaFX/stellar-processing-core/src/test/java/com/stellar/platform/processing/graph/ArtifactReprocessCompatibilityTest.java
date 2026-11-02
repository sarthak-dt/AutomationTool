package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.execution.ProcessingResultRegistry;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forced replacement of an existing graph store must publish one layout-2 generation.
 * A layout-1 index must not survive on top of layout-2 sample records.
 */
class ArtifactReprocessCompatibilityTest {

    @TempDir Path temp;

    @Test
    void freshStoreIsValidityLayoutAndReadable() throws Exception {
        Path root = temp.resolve("fresh");
        writeStore(root, "speed.mf4", "veh.dbc", "Speed", 10.5, 20.5);
        assertEquals(LosslessGraphSignalStore.INDEX_LAYOUT_VALIDITY, LosslessGraphSignalStore.indexLayoutVersion(root));
        assertTrue(LosslessGraphSignalStore.payloadMatchesValidityLayout(root));
        assertEquals(10.5, readFirst(root), 0.0);
        assertEquals(SignalValidity.VALID, readPage(root).getFirst().validity());
    }

    @Test
    void reopeningDoesNotRewriteTheIndex() throws Exception {
        Path root = temp.resolve("reopen");
        writeStore(root, "a.mf4", "a.dbc", "Speed", 1.0, 2.0);
        byte[] before = Files.readAllBytes(root.resolve(LosslessGraphSignalStore.INDEX_FILE_NAME));
        try (LosslessGraphSignalStore opened = LosslessGraphSignalStore.openExisting(root)) {
            opened.finishWriteSession();
        }
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(root.resolve(LosslessGraphSignalStore.INDEX_FILE_NAME))));
    }

    @Test
    void staleOpenStoreCannotReplaceANewerIndexWithLayoutOne() throws Exception {
        Path root = temp.resolve("mixed");
        writeStore(root, "old.mf4", "old.dbc", "Speed", 11.0, 12.0);
        downgradePayloadToLegacyLayout(root);
        assertEquals(LosslessGraphSignalStore.INDEX_LAYOUT_LEGACY, LosslessGraphSignalStore.indexLayoutVersion(root));
        assertFalse(LosslessGraphSignalStore.payloadMatchesValidityLayout(root));

        Path replacement = temp.resolve("replacement");
        writeStore(replacement, "old.mf4", "old.dbc", "Speed", 22.0, 23.0);
        try (LosslessGraphSignalStore stale = LosslessGraphSignalStore.openExisting(root)) {
            copyTree(replacement, root);
            stale.finishWriteSession();
        }
        assertEquals(LosslessGraphSignalStore.INDEX_LAYOUT_VALIDITY, LosslessGraphSignalStore.indexLayoutVersion(root));
        assertTrue(LosslessGraphSignalStore.payloadMatchesValidityLayout(root));
        assertEquals(22.0, readFirst(root), 0.0);
    }

    @Test
    void promoteReplacesLegacyGenerationWithVerifiedValidityStore() throws Exception {
        Path legacy = temp.resolve("final");
        writeStore(legacy, "trip.mf4", "veh.dbc", "Speed", 11.0, 12.0);
        downgradePayloadToLegacyLayout(legacy);
        byte[] legacyMarker = Files.readAllBytes(legacy.resolve(LosslessGraphSignalStore.INDEX_FILE_NAME));

        Path staging = temp.resolve("final.staging");
        writeStore(staging, "trip.mf4", "veh.dbc", "Speed", 30.25, -4.5);
        ArtifactStoreCommit.promote(staging, legacy);

        assertFalse(Files.exists(staging));
        assertFalse(Files.exists(temp.resolve("final.previous")));
        assertEquals(LosslessGraphSignalStore.INDEX_LAYOUT_VALIDITY, LosslessGraphSignalStore.indexLayoutVersion(legacy));
        assertTrue(LosslessGraphSignalStore.payloadMatchesValidityLayout(legacy));
        assertFalse(java.util.Arrays.equals(legacyMarker, Files.readAllBytes(legacy.resolve(LosslessGraphSignalStore.INDEX_FILE_NAME))));
        List<SignalSamplePoint> page = readPage(legacy);
        assertEquals(30.25, page.get(0).physicalValue(), 0.0);
        assertEquals(-4.5, page.get(1).physicalValue(), 0.0);
        assertEquals(SignalValidity.VALID, page.get(0).validity());
        assertEquals("Speed", readName(legacy));
    }

    @Test
    void repeatedPromoteKeepsOnlyTheLatestGeneration() throws Exception {
        Path dest = temp.resolve("job");
        writeStore(dest, "a.mf4", "a.dbc", "Speed", 1.0, 1.0);
        promoteValue(dest, 5.5);
        promoteValue(dest, 9.25);
        assertEquals(9.25, readFirst(dest), 0.0);
        assertTrue(LosslessGraphSignalStore.payloadMatchesValidityLayout(dest));
        assertFalse(Files.exists(temp.resolve("job.previous")));
    }

    @Test
    void failedPromoteLeavesThePreviousGenerationUntouched() throws Exception {
        Path dest = temp.resolve("keep");
        writeStore(dest, "a.mf4", "a.dbc", "Speed", 41.0, 42.0);
        byte[] index = Files.readAllBytes(dest.resolve(LosslessGraphSignalStore.INDEX_FILE_NAME));
        Path staging = temp.resolve("keep.staging");
        Files.createDirectories(staging);
        Files.writeString(staging.resolve("incomplete.flag"), "1");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ArtifactStoreCommit.promote(staging, dest));
        assertTrue(ex.getMessage().contains("layout verification"));
        assertTrue(java.util.Arrays.equals(index, Files.readAllBytes(dest.resolve(LosslessGraphSignalStore.INDEX_FILE_NAME))));
        assertEquals(41.0, readFirst(dest), 0.0);
        assertTrue(Files.isDirectory(staging), "failed staging must remain for a later retry");
    }

    @Test
    void discardingStagingDoesNotRemoveTheFinalStore() throws Exception {
        Path dest = temp.resolve("cancel-final");
        Path staging = temp.resolve("cancel-final.staging");
        writeStore(dest, "a.mf4", "a.dbc", "Speed", 7.0, 8.0);
        writeStore(staging, "a.mf4", "a.dbc", "Speed", 99.0, 99.0);
        deleteTree(staging);
        assertFalse(Files.exists(staging));
        assertEquals(7.0, readFirst(dest), 0.0);
        assertTrue(LosslessGraphSignalStore.payloadMatchesValidityLayout(dest));
    }

    @Test
    void secondMeasurementAndDefinitionStayIndependent() throws Exception {
        Path first = temp.resolve("m1");
        Path second = temp.resolve("m2");
        writeStore(first, "one.mf4", "alpha.dbc", "Speed", 3.0, 4.0);
        writeStore(second, "two.mf4", "beta.dbc", "Rpm", 900.0, 910.0);
        promoteValue(first, 8.0);
        assertEquals(8.0, readFirst(first), 0.0);
        assertEquals(900.0, readFirst(second), 0.0);
        try (LosslessGraphSignalStore read = LosslessGraphSignalStore.openExisting(second)) {
            assertEquals("two.mf4", read.measurementSource());
            assertEquals("beta.dbc", read.definitionSource());
        }
    }

    @Test
    void graphAndRouteQueriesReadThePromotedSamples() throws Exception {
        Path dest = temp.resolve("route");
        writeStore(dest, "drive.mf4", "gps.dbc", "GPS_LAT", 1.0, 1.0);
        Path staging = temp.resolve("route.staging");
        try (GraphSignalSessionStore store = GraphSignalSessionStore.createPersistent(staging, "drive.mf4", "gps.dbc")) {
            store.accept(List.of(
                    signal("GPS_LAT", 0L, 13.331515),
                    signal("GPS_LAT", 1_000_000_000L, 13.332000),
                    signal("GPS_LON", 0L, 77.637770),
                    signal("GPS_LON", 1_000_000_000L, 77.638100),
                    signal("GPS_ALT", 0L, 900.0),
                    signal("GPS_ALT", 1_000_000_000L, 901.0)));
            store.finishWriteSession();
        }
        ArtifactStoreCommit.promote(staging, dest);
        try (LosslessGraphSignalStore read = LosslessGraphSignalStore.openExisting(dest)) {
            SignalCatalogKey lat = keyNamed(read, "GPS_LAT");
            SignalCatalogKey lon = keyNamed(read, "GPS_LON");
            SignalCatalogKey alt = keyNamed(read, "GPS_ALT");
            List<SignalSamplePoint> latitudes = read.queryRange(lat, 0L, 2_000_000_000L);
            List<SignalSamplePoint> longitudes = read.queryRange(lon, 0L, 2_000_000_000L);
            List<SignalSamplePoint> altitudes = read.queryRange(alt, 0L, 2_000_000_000L);
            assertEquals(13.331515, latitudes.getFirst().physicalValue(), 1e-9);
            assertEquals(SignalValidity.VALID, latitudes.getFirst().validity());
            MapRouteModel route = MapRouteBuilder.build(latitudes, longitudes, altitudes);
            assertEquals(2, route.size());
            assertEquals(13.331515, route.latitude(0), 1e-9);
            assertEquals(77.637770, route.longitude(0), 1e-9);
            assertEquals(900.0, route.altitude(0), 0.0);
            assertEquals(0L, route.timestampNanos(0));
            assertEquals(1_000_000_000L, route.timestampNanos(1));
        }
    }

    @Test
    void invalidRawCodeSurvivesPromote() throws Exception {
        Path dest = temp.resolve("validity");
        Files.createDirectories(dest);
        Path staging = temp.resolve("validity.staging");
        try (GraphSignalSessionStore store = GraphSignalSessionStore.createPersistent(staging, "a.mf4", "a.dbc")) {
            store.accept(List.of(new CanonicalSignal(
                    "Speed", "MSG", 10L, 0x100L, 1, 655.35, 65535L, "km/h", null,
                    SourceReference.ofLine(1), SignalValidity.INVALID_RAW_CODE)));
            store.finishWriteSession();
        }
        ArtifactStoreCommit.promote(staging, dest);
        SignalSamplePoint point = readPage(dest).getFirst();
        assertEquals(655.35, point.physicalValue(), 0.0);
        assertEquals(SignalValidity.INVALID_RAW_CODE, point.validity());
        assertFalse(point.usableMeasurement());
    }

    @Test
    void forceReprocessDropsThePreviousRegistryHandle() {
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        ProcessingJobKey key = ProcessingJobKey.of("src", 1);
        registry.publishJob(new IndependentJobResult(
                key, "dbc", "a.dbc", MappingLifecycle.CONFIRMED, JobExecutionStatus.SUCCESS,
                1, 1, 1, 1, 0, 1, 0L, 1L, null, Duration.ZERO, List.of(), "reopened-from-durable-job",
                new Object()));
        assertTrue(registry.get(key).isPresent());
        registry.removeJob(key);
        assertTrue(registry.get(key).isEmpty());
    }

    private static void promoteValue(Path dest, double value) throws Exception {
        Path staging = dest.resolveSibling(dest.getFileName().toString() + ".staging-" + value);
        writeStore(staging, "a.mf4", "a.dbc", "Speed", value, value);
        ArtifactStoreCommit.promote(staging, dest);
    }

    private static void writeStore(Path root, String measurement, String definition, String name,
                                   double first, double second) throws Exception {
        try (GraphSignalSessionStore store = GraphSignalSessionStore.createPersistent(root, measurement, definition)) {
            store.accept(List.of(signal(name, 10L, first), signal(name, 20L, second)));
            store.finishWriteSession();
        }
    }

    private static CanonicalSignal signal(String name, long timestamp, double value) {
        return new CanonicalSignal(name, "MSG", timestamp, 0x100L, 1, value, 1L, "unit", null,
                SourceReference.ofLine(1));
    }

    private static double readFirst(Path root) throws Exception {
        return readPage(root).getFirst().physicalValue();
    }

    private static List<SignalSamplePoint> readPage(Path root) throws Exception {
        try (LosslessGraphSignalStore read = LosslessGraphSignalStore.openExisting(root)) {
            return read.querySamples(read.keys().getFirst(), 0, 10);
        }
    }

    private static String readName(Path root) throws Exception {
        try (LosslessGraphSignalStore read = LosslessGraphSignalStore.openExisting(root)) {
            return read.keys().getFirst().signalName();
        }
    }

    private static SignalCatalogKey keyNamed(LosslessGraphSignalStore store, String name) {
        return store.keys().stream().filter(key -> name.equals(key.signalName())).findFirst().orElseThrow();
    }

    /** Rewrites a validity-layout store so the index says layout 1 while the test can still open it. */
    private static void downgradePayloadToLegacyLayout(Path root) throws Exception {
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> segments = walk.filter(path -> path.getFileName().toString().endsWith(".bin")).toList();
            for (Path segment : segments) {
                byte[] raw = Files.readAllBytes(segment);
                assertEquals(0, raw.length % 30, "fixture samples are fixed 30-byte validity records");
                ByteArrayOutputStream legacy = new ByteArrayOutputStream();
                for (int offset = 0; offset < raw.length; offset += 30) {
                    legacy.write(raw, offset, 29);
                }
                Files.write(segment, legacy.toByteArray());
            }
        }
        Path index = root.resolve(LosslessGraphSignalStore.INDEX_FILE_NAME);
        byte[] header = Files.readAllBytes(index);
        header[4] = 0;
        header[5] = 0;
        header[6] = 0;
        header[7] = (byte) LosslessGraphSignalStore.INDEX_LAYOUT_LEGACY;
        Files.write(index, header);
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path source : walk.toList()) {
                Path target = to.resolve(from.relativize(source));
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = new ArrayList<>(walk.toList());
            paths.sort(java.util.Comparator.reverseOrder());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }
}
