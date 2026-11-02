package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphMeasurementSeriesTest {

    @Test
    void allMeasurementsKeepsEachFileClockAndDoesNotRebaseTimestamps() {
        InMemorySignalCatalogStore fileA = store("trip-a.mf4", "alpha.dbc", List.of(
                sample("VehicleSpeed", 0L, 11.0),
                sample("VehicleSpeed", 1_000_000_000L, 15.0)));
        InMemorySignalCatalogStore fileB = store("trip-b.blf", "beta.dbc", List.of(
                sample("VehicleSpeed", 0L, 80.0),
                sample("VehicleSpeed", 1_000_000_000L, 90.0)));
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(fileA, "src-A", "def-a", "trip-a.mf4", "alpha.dbc"),
                new ProjectMergedSignalStore.Member(fileB, "src-B", "def-b", "trip-b.blf", "beta.dbc")
        ), "2 measurements", "alpha.dbc");

        SignalCatalogEntry logical = merged.snapshot().entries().stream()
                .filter(e -> e.signalName().equals("VehicleSpeed"))
                .findFirst()
                .orElseThrow();
        List<GraphMeasurementSeries.PlotGroup> groups = GraphMeasurementSeries.groupsForPlot(
                List.of(logical.key()),
                List.of(logical),
                merged.physicalCatalogAll(),
                true);
        assertEquals(1, groups.size());
        List<SignalCatalogKey> plotted = groups.getFirst().physicalKeys();

        assertEquals(2, plotted.size());
        assertTrue(GraphMeasurementSeries.spansMultipleMeasurements(plotted));
        assertTrue(plotted.stream().noneMatch(k ->
                ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equals(k.sourceFile())));

        PhysicalIdentityGraphSeriesQuery query = new PhysicalIdentityGraphSeriesQuery(merged.graphQuery());
        List<List<Double>> series = new ArrayList<>();
        for (SignalCatalogKey key : plotted) {
            List<SignalSamplePoint> points = query.queryRange(key, 0L, 2_000_000_000L);
            assertEquals(List.of(0L, 1_000_000_000L), points.stream().map(SignalSamplePoint::timestampNanos).toList());
            series.add(values(points));
        }
        assertTrue(series.contains(List.of(11.0, 15.0)));
        assertTrue(series.contains(List.of(80.0, 90.0)));
        assertEquals(2, series.size());
    }

    @Test
    void differentUnitsStayOnDifferentSeries() {
        InMemorySignalCatalogStore speed = store("a.mf4", "one.dbc", List.of(sample("Status", 0L, 10.0, "km/h")));
        InMemorySignalCatalogStore rpm = store("b.mf4", "two.dbc", List.of(sample("Status", 0L, 900.0, "rpm")));
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(List.of(
                new ProjectMergedSignalStore.Member(speed, "src-A", "def-speed", "a.mf4", "one.dbc"),
                new ProjectMergedSignalStore.Member(rpm, "src-B", "def-rpm", "b.mf4", "two.dbc")
        ), "2 measurements", "one.dbc");
        List<SignalCatalogEntry> logical = merged.snapshot().entries().stream()
                .filter(e -> e.signalName().equals("Status"))
                .toList();
        assertEquals(2, logical.size());
        PhysicalIdentityGraphSeriesQuery query = new PhysicalIdentityGraphSeriesQuery(merged.graphQuery());
        for (SignalCatalogEntry entry : logical) {
            List<GraphMeasurementSeries.PlotGroup> groups = GraphMeasurementSeries.groupsForPlot(
                    List.of(entry.key()), List.of(entry), merged.physicalCatalogAll(), true);
            assertEquals(1, groups.size());
            List<SignalCatalogKey> plotted = groups.getFirst().physicalKeys();
            assertEquals(1, plotted.size());
            List<Double> values = query.queryRange(plotted.getFirst(), 0L, 1L).stream()
                    .map(SignalSamplePoint::physicalValue)
                    .toList();
            assertEquals(1, values.size());
            assertTrue(values.getFirst() == 10.0 || values.getFirst() == 900.0);
            assertEquals(entry.unit().equals("km/h") ? 10.0 : 900.0, values.getFirst(), 0);
        }
    }

    @Test
    void seventyThreeFilesOfOneSignalStayOneGroupAndKeepEveryFile() {
        List<ProjectMergedSignalStore.Member> members = new ArrayList<>();
        for (int i = 0; i < 73; i++) {
            String file = "file-" + i + ".dat";
            members.add(new ProjectMergedSignalStore.Member(
                    store(file, "one.dbc", List.of(sample("VehicleSpeed", 0L, i))),
                    "src-" + i, "def", file, "one.dbc"));
        }
        members.add(new ProjectMergedSignalStore.Member(
                store("other.dat", "one.dbc", List.of(sample("Coolant", 0L, 80))),
                "src-coolant", "def", "other.dat", "one.dbc"));
        ProjectMergedSignalStore merged = ProjectMergedSignalStore.fromMembers(members, "74 measurements", "one.dbc");
        SignalCatalogEntry speed = merged.snapshot().entries().stream()
                .filter(e -> e.signalName().equals("VehicleSpeed"))
                .findFirst()
                .orElseThrow();
        SignalCatalogEntry coolant = merged.snapshot().entries().stream()
                .filter(e -> e.signalName().equals("Coolant"))
                .findFirst()
                .orElseThrow();
        List<GraphMeasurementSeries.PlotGroup> one = GraphMeasurementSeries.groupsForPlot(
                List.of(speed.key()), List.of(speed), merged.physicalCatalogAll(), true);
        assertEquals(1, one.size());
        assertEquals(73, one.getFirst().physicalKeys().size());
        assertEquals(73, one.getFirst().measurementLabels().size());
        assertEquals("file-0.dat", one.getFirst().measurementLabels().getFirst());
        assertEquals("file-72.dat", one.getFirst().measurementLabels().get(72));
        List<GraphMeasurementSeries.PlotGroup> two = GraphMeasurementSeries.groupsForPlot(
                List.of(speed.key(), coolant.key()), List.of(speed, coolant), merged.physicalCatalogAll(), true);
        assertEquals(2, two.size());
        assertEquals(73, two.get(0).physicalKeys().size());
        assertEquals(1, two.get(1).physicalKeys().size());
    }

    @Test
    void largeSeriesKeepsKnownExtremaAfterReduction() {
        List<SignalSamplePoint> samples = new ArrayList<>();
        samples.add(new SignalSamplePoint(0L, 655.35, 65535L, null, SignalValidity.INVALID_RAW_CODE));
        samples.add(new SignalSamplePoint(1L, Double.NaN, 1L, null, SignalValidity.NON_FINITE_VALUE));
        for (int i = 2; i < 20_000; i++) {
            double value = i == 100 ? -500.0 : i == 9_000 ? 1_000_000.0 : (i % 50);
            samples.add(new SignalSamplePoint(i, value, (long) value, null, SignalValidity.VALID));
        }
        List<GraphBucket> buckets = MinMaxLodReducer.reduce(samples, 40);
        assertTrue(buckets.size() <= 40);
        double min = buckets.stream().filter(GraphBucket::minUsable).mapToDouble(GraphBucket::minValue).min().orElseThrow();
        double max = buckets.stream().filter(GraphBucket::maxUsable).mapToDouble(GraphBucket::maxValue).max().orElseThrow();
        assertEquals(-500.0, min, 0);
        assertEquals(1_000_000.0, max, 0);
        assertTrue(buckets.stream().noneMatch(b -> b.minUsable() && b.minValue() == 655.35));
        assertEquals(0L, samples.getFirst().timestampNanos());
        assertEquals(655.35, samples.getFirst().physicalValue(), 0);
    }

    private static List<Double> values(List<SignalSamplePoint> points) {
        return points.stream().map(SignalSamplePoint::physicalValue).toList();
    }

    private static InMemorySignalCatalogStore store(String source, String dbc, List<CanonicalSignal> signals) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(source, dbc, 32);
        store.accept(signals);
        return store;
    }

    private static CanonicalSignal sample(String name, long ts, double value) {
        return sample(name, ts, value, "km/h");
    }

    private static CanonicalSignal sample(String name, long ts, double value, String unit) {
        return new CanonicalSignal(
                name, "Vehicle", ts, 0x100L, 1, value, (long) value, unit, null, SourceReference.ofLine(1));
    }
}
