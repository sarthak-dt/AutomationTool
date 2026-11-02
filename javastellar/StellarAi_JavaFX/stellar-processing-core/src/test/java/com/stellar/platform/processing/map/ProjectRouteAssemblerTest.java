package com.stellar.platform.processing.map;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectRouteAssemblerTest {

    @Test
    void mixedGpsProjectKeepsIndependentSegmentsAndNoArtificialConnection() {
        MapRoute gpsA = route(2, 12.9, 77.5, 100);
        MapRoute gpsC = route(3, 18.5, 73.8, 200);
        ProjectRoute project = ProjectRouteAssembler.assemble(List.of(
                new ProjectRouteAssembler.PreparedMeasurement("src-A", "A.mf4", gpsA),
                new ProjectRouteAssembler.PreparedMeasurement("src-B", "B.mf4", MapRoute.emptyRoute()),
                new ProjectRouteAssembler.PreparedMeasurement("src-C", "C.mf4", gpsC)
        ), 3);

        assertEquals(3, project.measurementCount());
        assertEquals(2, project.gpsCapableCount());
        assertEquals(2, project.routeSegmentCount());
        assertEquals(5, project.gpsSampleCount());
        assertEquals(gpsA.model().totalDistanceMeters() + gpsC.model().totalDistanceMeters(),
                project.totalDistanceMeters(), 1e-9);
        assertFalse(ProjectRouteAssembler.connectsAdjacentFiles(project));
        assertEquals(gpsA.model().totalDistanceMeters() + gpsC.model().totalDistanceMeters(),
                ProjectRouteAssembler.independentDistanceMeters(project), 1e-9);
        assertTrue(project.gpsSegments().stream().noneMatch(s -> "src-B".equals(s.measurementId())));
        assertEquals("src-A", project.gpsSegments().get(0).measurementId());
        assertEquals("src-C", project.gpsSegments().get(1).measurementId());
    }

    @Test
    void geographicallySeparatedFilesDoNotAddTheGapBetweenThem() {
        MapRoute fileA = routeEndingAt(12.9716, 77.5946, 19_600);
        MapRoute fileB = routeEndingAt(19.0760, 72.8777, 12_400);
        double gap = GeoDistance.haversineMeters(
                fileA.model().latitude(fileA.model().size() - 1),
                fileA.model().longitude(fileA.model().size() - 1),
                fileB.model().latitude(0),
                fileB.model().longitude(0));
        assertTrue(gap > 100_000, "fixture files must be far apart, gap=" + gap);

        ProjectRoute project = ProjectRouteAssembler.assemble(List.of(
                new ProjectRouteAssembler.PreparedMeasurement("src-A", "A.dat", fileA),
                new ProjectRouteAssembler.PreparedMeasurement("src-B", "B.dat", fileB)
        ), 2);

        double aggregate = project.totalDistanceMeters();
        assertEquals(fileA.model().totalDistanceMeters() + fileB.model().totalDistanceMeters(), aggregate, 1e-6);
        assertEquals(32_000, aggregate, 1e-6);
        assertTrue(aggregate + 1 < fileA.model().totalDistanceMeters()
                + fileB.model().totalDistanceMeters() + gap);
        assertFalse(ProjectRouteAssembler.connectsAdjacentFiles(project));
    }

    @Test
    void measurementWithoutGpsDoesNotAddDistance() {
        MapRoute gps = route(2, 12.9, 77.5, 8_100);
        ProjectRoute project = ProjectRouteAssembler.assemble(List.of(
                new ProjectRouteAssembler.PreparedMeasurement("src-A", "A.dat", gps),
                new ProjectRouteAssembler.PreparedMeasurement("src-B", "B.dat", MapRoute.emptyRoute())
        ), 2);
        assertEquals(1, project.gpsCapableCount());
        assertEquals(gps.model().totalDistanceMeters(), project.totalDistanceMeters(), 1e-9);
        assertTrue(project.gpsSegments().stream().noneMatch(segment -> "src-B".equals(segment.measurementId())));
    }

    @Test
    void seventyThreeSegmentsKeepTheCanonicalSampleTotal() {
        final int expected = 634_781;
        int base = expected / 73;
        int remainder = expected - base * 72;
        List<ProjectRouteAssembler.PreparedMeasurement> prepared = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < 73; i++) {
            int samples = i == 72 ? remainder : base;
            total += samples;
            prepared.add(new ProjectRouteAssembler.PreparedMeasurement(
                    "src-" + i, "file-" + i + ".dat", route(samples, 18.5, 73.8, 0)));
        }
        ProjectRoute project = ProjectRouteAssembler.assemble(prepared, 73);
        assertEquals(73, project.routeSegmentCount());
        assertEquals(73, project.gpsCapableCount());
        assertEquals(expected, total);
        assertEquals(expected, project.gpsSampleCount());
        assertEquals(0.0, project.totalDistanceMeters(), 0.0);
    }

    @Test
    void seventyThreeIndependentSegmentsShareOneAggregateDistance() {
        List<ProjectRouteAssembler.PreparedMeasurement> prepared = new ArrayList<>();
        double expected = 0;
        for (int i = 0; i < 73; i++) {
            double meters = 1_000 + i;
            expected += meters;
            prepared.add(new ProjectRouteAssembler.PreparedMeasurement(
                    "src-" + i, "file-" + i + ".dat", routeEndingAt(10 + i * 0.01, 70, meters)));
        }
        ProjectRoute project = ProjectRouteAssembler.assemble(prepared, 73);
        assertEquals(73, project.measurementCount());
        assertEquals(73, project.gpsCapableCount());
        assertEquals(expected, project.totalDistanceMeters(), 1e-6);
        assertEquals(expected, ProjectRouteAssembler.independentDistanceMeters(project), 1e-6);
    }

    @Test
    void fourMeasurementAllKeepsSourceOrder() {
        ProjectRoute project = ProjectRouteAssembler.assemble(List.of(
                new ProjectRouteAssembler.PreparedMeasurement("src-A", "A.mf4", route(1, 1, 1, 10)),
                new ProjectRouteAssembler.PreparedMeasurement("src-B", "B.mf4", route(1, 2, 2, 10)),
                new ProjectRouteAssembler.PreparedMeasurement("src-C", "C.mf4", route(1, 3, 3, 10)),
                new ProjectRouteAssembler.PreparedMeasurement("src-D", "D.mf4", route(1, 4, 4, 10))
        ), 4);
        assertEquals(4, project.routeSegmentCount());
        assertEquals(List.of("src-A", "src-B", "src-C", "src-D"),
                project.gpsSegments().stream().map(RouteSegment::measurementId).toList());
    }

    private static MapRoute route(int points, double lat, double lon, double metersPerStep) {
        long[] ts = new long[points];
        double[] latitude = new double[points];
        double[] longitude = new double[points];
        double[] distance = new double[points];
        boolean[] breaks = new boolean[points];
        for (int i = 0; i < points; i++) {
            ts[i] = i * 1_000_000_000L;
            latitude[i] = lat + i * 0.001;
            longitude[i] = lon + i * 0.001;
            distance[i] = i * metersPerStep;
        }
        return new MapRoute(new MapRouteModel(ts, latitude, longitude, null, distance, breaks));
    }

    /** Two samples. Cumulative distance ends at {@code meters}; coordinates do not invent that length. */
    private static MapRoute routeEndingAt(double endLat, double endLon, double meters) {
        long[] ts = {0L, 60_000_000_000L};
        double[] latitude = {endLat - 0.01, endLat};
        double[] longitude = {endLon - 0.01, endLon};
        double[] distance = {0, meters};
        boolean[] breaks = {false, false};
        return new MapRoute(new MapRouteModel(ts, latitude, longitude, null, distance, breaks));
    }
}
