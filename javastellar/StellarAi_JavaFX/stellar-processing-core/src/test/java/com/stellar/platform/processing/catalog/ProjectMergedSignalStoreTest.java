package com.stellar.platform.processing.catalog;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project-wide logical merge: same engineering signal across files collapses once;
 * samples merge by ascending timestamp without copying full series into one list.
 */
class ProjectMergedSignalStoreTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void mergesEquivalentSignalsAcrossFilesIntoOneLogicalRow() {
        InMemorySignalCatalogStore a = store("file-a.mf4", "veh.dbc", List.of(
                sample("EngineRPM", 1L, 100.0),
                sample("EngineRPM", 3L, 300.0),
                sample("EngineRPM", 5L, 500.0),
                sample("VehicleSpeed", 1L, 10.0),
                sample("VehicleSpeed", 3L, 30.0),
                sample("VehicleSpeed", 5L, 50.0)
        ));
        InMemorySignalCatalogStore b = store("file-b.mf4", "veh.dbc", List.of(
                sample("EngineRPM", 2L, 200.0),
                sample("EngineRPM", 4L, 400.0),
                sample("EngineRPM", 6L, 600.0),
                sample("VehicleSpeed", 2L, 20.0),
                sample("VehicleSpeed", 4L, 40.0),
                sample("VehicleSpeed", 6L, 60.0)
        ));

        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(a, "src-A", "def-veh", "Source A", "veh.dbc"),
                new ProjectMergedSignalStore.Member(b, "src-B", "def-veh", "Source B", "veh.dbc")
        ), "2 Sources", "veh.dbc");

        assertEquals(2, merged.logicalSignalCount());
        List<String> names = merged.snapshot().entries().stream().map(SignalCatalogEntry::signalName).toList();
        assertEquals(List.of("EngineRPM", "VehicleSpeed"), names);

        SignalCatalogKey rpm = merged.snapshot().entries().stream()
                .filter(e -> e.signalName().equals("EngineRPM"))
                .findFirst().orElseThrow().key();
        assertEquals(2, merged.physicalRouteCount(rpm));
        List<Long> timestamps = merged.querySamples(rpm, 0, 20).stream()
                .map(SignalSamplePoint::timestampNanos)
                .toList();
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L), timestamps);
    }

    @Test
    void doesNotDeduplicateByDisplayNameAlone() {
        InMemorySignalCatalogStore a = store("file-a.mf4", "veh.dbc", List.of(
                new CanonicalSignal("EngineRPM", "OBD2", 1L, 0x7E8, 1, 1.0, 1L, "rpm", null,
                        SourceReference.ofLine(1))
        ));
        InMemorySignalCatalogStore b = store("file-b.mf4", "veh.dbc", List.of(
                new CanonicalSignal("EngineRPM", "Engine", 1L, 0x100, 2, 1.0, 1L, "rpm", null,
                        SourceReference.ofLine(1))
        ));

        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(a, "src-A", "def-veh", "A", "veh.dbc"),
                new ProjectMergedSignalStore.Member(b, "src-B", "def-veh", "B", "veh.dbc")
        ), "Project", "veh.dbc");

        assertEquals(2, merged.logicalSignalCount());
        assertFalse(ProjectMergedSignalStore.sameLogicalEngineeringSignal(
                a.snapshot().entries().getFirst().key(),
                b.snapshot().entries().getFirst().key()));
    }

    @Test
    void sourceScopedStoresRemainIndependent() {
        InMemorySignalCatalogStore a = store("file-a.mf4", "veh.dbc", List.of(sample("EngineRPM", 1L, 1.0)));
        InMemorySignalCatalogStore b = store("file-b.mf4", "veh.dbc", List.of(sample("EngineRPM", 2L, 2.0)));

        CompositeSignalCatalogStore sourceA = CompositeSignalCatalogStore.fromMembers(List.of(
                new CompositeSignalCatalogStore.Member(a, "src-A", "def", "A", "veh.dbc")
        ), "A", "veh.dbc");
        CompositeSignalCatalogStore sourceB = CompositeSignalCatalogStore.fromMembers(List.of(
                new CompositeSignalCatalogStore.Member(b, "src-B", "def", "B", "veh.dbc")
        ), "B", "veh.dbc");

        assertEquals(1, sourceA.snapshot().size());
        assertEquals(1, sourceB.snapshot().size());
        assertEquals(1L, sourceA.querySamples(sourceA.snapshot().entries().getFirst().key(), 0, 10)
                .getFirst().timestampNanos());
        assertEquals(2L, sourceB.querySamples(sourceB.snapshot().entries().getFirst().key(), 0, 10)
                .getFirst().timestampNanos());
    }

    @Test
    void physicalCatalogIsUnprojectedAndIsolatedByMeasurement() {
        InMemorySignalCatalogStore a = store("file-a.mf4", "veh.dbc", List.of(
                sample("EngineRPM", 1L, 100.0),
                sample("VehicleSpeed", 1L, 10.0),
                sample("UniqueA", 1L, 1.0)
        ));
        InMemorySignalCatalogStore b = store("file-b.mf4", "veh.dbc", List.of(
                sample("EngineRPM", 2L, 200.0),
                sample("VehicleSpeed", 2L, 20.0),
                sample("UniqueB", 2L, 2.0)
        ));
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(a, "src-A", "def-veh", "file-a.mf4", "veh.dbc"),
                new ProjectMergedSignalStore.Member(b, "src-B", "def-veh", "file-b.mf4", "veh.dbc")
        ), "2 measurements", "veh.dbc");

        List<SignalCatalogEntry> catalogA = merged.physicalCatalog("src-A", "file-a.mf4");
        List<SignalCatalogEntry> catalogB = merged.physicalCatalog("src-B", "file-b.mf4");
        assertEquals(3, catalogA.size());
        assertEquals(3, catalogB.size());
        assertTrue(catalogA.stream().allMatch(e -> "src-A".equals(e.key().sourceFile())));
        assertTrue(catalogB.stream().allMatch(e -> "src-B".equals(e.key().sourceFile())));
        assertTrue(catalogA.stream().anyMatch(e -> e.signalName().equals("UniqueA")));
        assertTrue(catalogA.stream().noneMatch(e -> e.signalName().equals("UniqueB")));
        assertTrue(catalogB.stream().noneMatch(e -> e.signalName().equals("UniqueA")));
        assertTrue(merged.physicalCatalog("missing", "nope.mf4").isEmpty());

        SignalCatalogKey projectRpm = merged.snapshot().entries().stream()
                .filter(e -> e.signalName().equals("EngineRPM"))
                .findFirst().orElseThrow().key();
        assertEquals(Set.of("src-A", "src-B"), merged.physicalIdentities(projectRpm));
        SignalCatalogKey physicalA = catalogA.stream()
                .filter(e -> e.signalName().equals("EngineRPM"))
                .findFirst().orElseThrow().key();
        assertEquals(Set.of("src-A"), merged.physicalIdentities(physicalA));
        assertEquals(1, merged.graphQuery().queryRange(physicalA, 0, 20, "src-A").size());
        assertEquals(100.0, merged.graphQuery().queryRange(physicalA, 0, 20, "src-A").getFirst().physicalValue());
        assertEquals(1, merged.graphQuery().queryRange(physicalA, 0, 20, "src-B").size());
        assertEquals(200.0, merged.graphQuery().queryRange(physicalA, 0, 20, "src-B").getFirst().physicalValue());

        List<SignalCatalogEntry> all = merged.physicalCatalogAll();
        assertEquals(6, all.size());
        assertEquals(2, all.stream().filter(e -> e.signalName().equals("EngineRPM")).count());
        assertTrue(all.stream().anyMatch(e ->
                e.signalName().equals("EngineRPM") && "src-A".equals(e.key().sourceFile())));
        assertTrue(all.stream().anyMatch(e ->
                e.signalName().equals("EngineRPM") && "src-B".equals(e.key().sourceFile())));
    }

    @Test
    void physicalKeyStillResolvesSamplesAgainstProjectMergedStore() {
        InMemorySignalCatalogStore physical = store("file-a.mf4", "veh.dbc", List.of(
                sample("EngineRPM", 1L, 100.0),
                sample("EngineRPM", 2L, 200.0)
        ));
        SignalCatalogKey physicalKey = physical.snapshot().entries().getFirst().key();
        assertFalse(ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equals(physicalKey.sourceFile()));

        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(physical, "src-A", "def-veh", "Source A", "veh.dbc")
        ), "Project", "veh.dbc");

        assertEquals(2, merged.retainedSampleCount(physicalKey));
        List<SignalSamplePoint> samples = merged.querySamples(physicalKey, 0, 10);
        assertEquals(2, samples.size());
        assertEquals(100.0, samples.getFirst().physicalValue());
    }

    @Test
    void collapsesExactDuplicateSamplesButKeepsConflictingValues() {
        List<SignalSamplePoint> merged = ProjectMergedSignalStore.TimestampOrderedSampleMerge.mergeOrderedPages(
                List.of(
                        List.of(new SignalSamplePoint(1L, 10.0, 10L, null),
                                new SignalSamplePoint(2L, 20.0, 20L, null)),
                        List.of(new SignalSamplePoint(1L, 10.0, 10L, null),
                                new SignalSamplePoint(2L, 21.0, 21L, null))
                ),
                10
        );
        assertEquals(3, merged.size());
        assertEquals(1L, merged.get(0).timestampNanos());
        assertEquals(2L, merged.get(1).timestampNanos());
        assertEquals(20.0, merged.get(1).physicalValue());
        assertEquals(2L, merged.get(2).timestampNanos());
        assertEquals(21.0, merged.get(2).physicalValue());
    }

    @Test
    void sourceScopedGpsQueryDoesNotInterleaveIndependentFiles() {
        InMemorySignalCatalogStore pune = store("pune.mf4", "veh.dbc", List.of(
                gps("LATITUDE", 0, 18.50), gps("LONGITUDE", 0, 73.80),
                gps("LATITUDE", SECOND, 18.5001), gps("LONGITUDE", SECOND, 73.8001)
        ));
        InMemorySignalCatalogStore bengaluru = store("bengaluru.mf4", "veh.dbc", List.of(
                gps("LATITUDE", 0, 13.04), gps("LONGITUDE", 0, 77.59),
                gps("LATITUDE", SECOND, 13.0401), gps("LONGITUDE", SECOND, 77.5901)
        ));
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(pune, "src-pune", "def-veh", "pune.mf4", "veh.dbc"),
                new ProjectMergedSignalStore.Member(bengaluru, "src-blr", "def-veh", "bengaluru.mf4", "veh.dbc")
        ), "project", "veh.dbc");

        SignalCatalogKey lat = merged.snapshot().entries().stream()
                .filter(e -> e.signalName().equals("LATITUDE")).findFirst().orElseThrow().key();
        SignalCatalogKey lon = merged.snapshot().entries().stream()
                .filter(e -> e.signalName().equals("LONGITUDE")).findFirst().orElseThrow().key();

        List<SignalSamplePoint> mergedLat = merged.graphQuery().queryRange(lat, 0, 10 * SECOND);
        assertEquals(4, mergedLat.size(), "unscoped graph overlay still merges by timestamp");

        List<SignalSamplePoint> puneLat = merged.graphQuery().queryRange(lat, 0, 10 * SECOND, "src-pune");
        List<SignalSamplePoint> puneLon = merged.graphQuery().queryRange(lon, 0, 10 * SECOND, "pune.mf4");
        assertEquals(2, puneLat.size());
        assertEquals(18.50, puneLat.getFirst().physicalValue(), 1e-9);
        assertEquals(18.5001, puneLat.get(1).physicalValue(), 1e-9);
        assertEquals(73.80, puneLon.getFirst().physicalValue(), 1e-9);

        MapRouteModel scoped = MapRouteBuilder.build(puneLat, puneLon, null);
        assertEquals(2, scoped.size());
        assertEquals(18.50, scoped.latitude(0), 1e-9);
        assertEquals(73.80, scoped.longitude(0), 1e-9);
        assertFalse(scoped.segmentBreakBefore(1));
        assertTrue(scoped.totalDistanceMeters() < 50_000);
        for (int i = 0; i < scoped.size(); i++) {
            assertTrue(scoped.latitude(i) > 18.0 && scoped.longitude(i) < 75.0);
        }
    }

    @Test
    void sameNameWithDifferentUnitsStaysTwoLogicalSignals() {
        InMemorySignalCatalogStore a = store("a.dat", "", List.of(
                new CanonicalSignal("Status", InMemorySignalCatalogStore.DIRECT_MEASUREMENT_MESSAGE,
                        1L, 0L, 0, 1.0, 1L, "rpm", null, SourceReference.ofLine(1))));
        InMemorySignalCatalogStore b = store("b.dat", "", List.of(
                new CanonicalSignal("Status", InMemorySignalCatalogStore.DIRECT_MEASUREMENT_MESSAGE,
                        1L, 0L, 0, 2.0, 2L, "km/h", null, SourceReference.ofLine(1))));
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(a, "src-A", "_none_", "a.dat", "Direct"),
                new ProjectMergedSignalStore.Member(b, "src-B", "_none_", "b.dat", "Direct")
        ), "2 measurements", "Direct");
        assertEquals(2, merged.snapshot().entries().stream().filter(e -> e.signalName().equals("Status")).count());
    }

    @Test
    void allMeasurementsMergesByTimestampAndOneFileStaysIsolated() {
        InMemorySignalCatalogStore a = store("a.dat", "", List.of(
                sample("Accuracy", 1L, 1.0),
                sample("Accuracy", 5L, 5.0)));
        InMemorySignalCatalogStore b = store("b.dat", "", List.of(
                sample("Accuracy", 2L, 2.0),
                sample("Accuracy", 100L, 9.0)));
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(a, "src-A", "_none_", "a.dat", "Direct"),
                new ProjectMergedSignalStore.Member(b, "src-B", "_none_", "b.dat", "Direct")
        ), "2 measurements", "Direct");
        SignalCatalogEntry accuracy = merged.snapshot().entries().stream()
                .filter(e -> e.signalName().equals("Accuracy")).findFirst().orElseThrow();
        assertEquals(1, merged.snapshot().entries().stream().filter(e -> e.signalName().equals("Accuracy")).count());
        assertEquals(2, merged.physicalRouteCount(accuracy.key()));
        List<Long> mergedTimes = merged.querySamples(accuracy.key(), 0, 10).stream()
                .map(SignalSamplePoint::timestampNanos).toList();
        assertEquals(List.of(1L, 2L, 5L, 100L), mergedTimes);
        SignalCatalogKey fileA = merged.physicalCatalog("src-A", "a.dat").stream()
                .filter(e -> e.signalName().equals("Accuracy")).findFirst().orElseThrow().key();
        List<Double> onlyA = merged.querySamples(fileA, 0, 10).stream()
                .map(SignalSamplePoint::physicalValue).toList();
        assertEquals(List.of(1.0, 5.0), onlyA);
    }

    @Test
    void fileFocusKeyKeepsOverlappingStoredClocksApart() {
        InMemorySignalCatalogStore a = store("a.dat", "", List.of(
                sample("VehicleSpeed", 1L, 23.0),
                sample("VehicleSpeed", 2L, 85.0)
        ));
        InMemorySignalCatalogStore b = store("b.dat", "", List.of(
                sample("VehicleSpeed", 1L, 0.0),
                sample("VehicleSpeed", 2L, 94.5)
        ));
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(a, "src-A", "_none_", "a.dat", "Direct"),
                new ProjectMergedSignalStore.Member(b, "src-B", "_none_", "b.dat", "Direct")
        ), "2 measurements", "Direct");
        SignalCatalogKey logical = merged.snapshot().entries().getFirst().key();
        List<Double> mixed = merged.queryTimestampRange(logical, 1L, 2L, 10).stream()
                .map(SignalSamplePoint::physicalValue).toList();
        assertEquals(4, mixed.size());
        assertTrue(mixed.contains(23.0) && mixed.contains(0.0));
        SignalCatalogKey focused = merged.physicalKeyFor(logical, "src-A").orElseThrow();
        List<Double> oneFile = merged.queryTimestampRange(focused, 1L, 2L, 10).stream()
                .map(SignalSamplePoint::physicalValue).toList();
        assertEquals(List.of(23.0, 85.0), oneFile);
        assertEquals("src-A", focused.sourceFile());
    }

    @Test
    void equalTimestampsFromTwoFilesAreBothKeptInSourceOrder() {
        InMemorySignalCatalogStore a = store("a.dat", "", List.of(sample("Accuracy", 10L, 1.0)));
        InMemorySignalCatalogStore b = store("b.dat", "", List.of(sample("Accuracy", 10L, 2.0)));
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(a, "src-A", "_none_", "a.dat", "Direct"),
                new ProjectMergedSignalStore.Member(b, "src-B", "_none_", "b.dat", "Direct")
        ), "2 measurements", "Direct");
        SignalCatalogKey key = merged.snapshot().entries().getFirst().key();
        List<Double> values = merged.querySamples(key, 0, 10).stream()
                .map(SignalSamplePoint::physicalValue).toList();
        assertEquals(List.of(1.0, 2.0), values);
    }

    @Test
    void repeatedNativeSignalsCollapseToOneLogicalRowPerName() {
        List<ProjectMergedSignalStore.Member> three = new ArrayList<>();
        for (int file = 1; file <= 3; file++) {
            List<CanonicalSignal> signals = new ArrayList<>();
            for (int signal = 1; signal <= 30; signal++) {
                signals.add(sample("Signal" + signal, file, signal));
            }
            InMemorySignalCatalogStore store = store("m" + file + ".dat", "", signals);
            three.add(new ProjectMergedSignalStore.Member(
                    store, "src-" + file, "_none_", "m" + file + ".dat", "Direct"));
        }
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(three, "3", "Direct");
        assertEquals(30, merged.logicalSignalCount());
        int physical = 0;
        for (SignalCatalogEntry entry : merged.snapshot().entries()) {
            physical += merged.physicalRouteCount(entry.key());
        }
        assertEquals(90, physical);
        assertEquals(1, merged.snapshot().entries().stream()
                .filter(e -> e.signalName().equals("Signal1")).count());
    }

    @Test
    void formatsShareOneLogicalSignalWhenSemanticIdentityMatches() {
        List<ProjectMergedSignalStore.Member> members = new ArrayList<>();
        long ts = 1L;
        for (String format : List.of("dat", "log", "mf4", "blf")) {
            InMemorySignalCatalogStore store = store(format + ".bin", "veh.dbc", List.of(
                    new CanonicalSignal("VehicleSpeed", "VehicleState", ts++, 0x123, 1,
                            10.0, 10L, "km/h", null, SourceReference.ofLine(1))));
            members.add(new ProjectMergedSignalStore.Member(
                    store, "src-" + format, "def-veh", format, "veh.dbc"));
        }
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(members, "4", "veh.dbc");
        assertEquals(1, merged.snapshot().entries().stream()
                .filter(e -> e.signalName().equals("VehicleSpeed")).count());
        assertEquals(4, merged.physicalRouteCount(merged.snapshot().entries().getFirst().key()));
    }

    private static CanonicalSignal gps(String name, long ts, double value) {
        int channel = name.equals("LATITUDE") ? 11 : 12;
        return new CanonicalSignal(
                name,
                InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE,
                ts,
                0L,
                channel,
                value,
                null,
                "deg",
                null,
                SourceReference.ofLine(1));
    }

    private static InMemorySignalCatalogStore store(String source, String dbc, List<CanonicalSignal> signals) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(source, dbc, 100);
        store.accept(signals);
        return store;
    }

    private static CanonicalSignal sample(String name, long ts, double value) {
        return new CanonicalSignal(
                name, "OBD2", ts, 0x7E8, 1, value, (long) value, "u", null, SourceReference.ofLine(1));
    }
}
