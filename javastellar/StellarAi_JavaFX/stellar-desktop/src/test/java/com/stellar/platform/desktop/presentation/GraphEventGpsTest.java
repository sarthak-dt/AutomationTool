package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.GraphSnapshotMetadata;
import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** GNSS matching rules. Coordinates come from stored samples only. */
class GraphEventGpsTest {

    private static final Path MF4 = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/tata_sierra_0002391_20260921_133054 (1).mf4");
    private static final Path DBC = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/TEMP DBC 1 (1).dbc");

    @Test
    void pointWindowAndRefusalsUseTheStoredSample() {
        MapRouteModel route = route(new long[] {0L, 1_000_000_000L, 3_000_000_000L},
                new double[] {19.0, 19.1, 19.2}, new double[] {74.0, 74.1, 74.2});
        GraphEventGps.Evidence inside = GraphEventGps.review(point("inside", 1_200_000_000L, "meas", "a.mf4"),
                view(route, "meas", "a.mf4"));
        assertEquals(GraphEventGps.MATCHED, inside.status());
        assertEquals(1_000_000_000L, inside.sampleTimestampNanos());
        assertEquals(19.1, inside.latitude(), 0.0);
        assertEquals(-200_000_000L, inside.deltaNanos());
        assertTrue(inside.statement().contains("0.200 s before the event"));
        assertTrue(inside.statement().contains("not at the event timestamp"));
        assertTrue(inside.statement().contains("Latitude 19.10000000"));
        assertTrue(inside.statement().contains("Policy: " + GnssEventCorrelation.POLICY_POINT));

        GraphEventGps.Evidence boundary = GraphEventGps.review(point("edge", 2_000_000_000L, "meas", "a.mf4"),
                view(route, "meas", "a.mf4"));
        assertEquals(GraphEventGps.MATCHED, boundary.status());
        assertEquals(1_000_000_000L, boundary.sampleTimestampNanos());
        assertEquals(1_000_000_000L, Math.abs(boundary.deltaNanos()));

        MapRouteModel sparse = route(new long[] {0L, 5_000_000_000L},
                new double[] {19.0, 19.4}, new double[] {74.0, 74.4});
        GraphEventGps.Evidence outside = GraphEventGps.review(point("far", 2_500_000_000L, "meas", "a.mf4"),
                view(sparse, "meas", "a.mf4"));
        assertEquals(GraphEventGps.NO_SAMPLE, outside.status());
        assertTrue(outside.statement().contains("within 1 s"));
        assertTrue(outside.statement().contains("outside the 1.000 s tolerance"));
        assertFalse(outside.statement().contains("Latitude "));

        GraphEventGps.Evidence exact = GraphEventGps.review(point("exact", 1_000_000_000L, "meas", "a.mf4"),
                view(route, "meas", "a.mf4"));
        assertEquals(0L, exact.deltaNanos());
        assertTrue(exact.statement().contains("at the event timestamp"));

        GraphEventGps.Evidence window = GraphEventGps.review(window("span", 500_000_000L, 2_500_000_000L, "meas", "a.mf4"),
                view(route, "meas", "a.mf4"));
        assertEquals(GraphEventGps.KIND_WINDOW, window.kind());
        assertEquals(1_000_000_000L, window.sampleTimestampNanos());
        assertTrue(window.statement().contains("not the whole event"));
        assertTrue(window.statement().contains(GnssEventCorrelation.POLICY_WINDOW));

        GraphEventGps.Evidence emptyWindow = GraphEventGps.review(
                window("gap", 1_100_000_000L, 2_000_000_000L, "meas", "a.mf4"), view(route, "meas", "a.mf4"));
        assertEquals(GraphEventGps.NO_SAMPLE, emptyWindow.status());
        assertTrue(emptyWindow.statement().contains("inside its time window"));

        GraphEventGps.Evidence otherFile = GraphEventGps.review(point("other", 1_000_000_000L, "other", "b.mf4"),
                view(route, "meas", "a.mf4"));
        assertEquals(GraphEventGps.INCOMPATIBLE, otherFile.status());
        assertTrue(otherFile.statement().contains("not the same measurement"));

        GraphEventGps.Evidence clock = GraphEventGps.review(
                point("clock", 2_000_000_000_000_000_000L, "meas", "a.mf4"), view(route, "meas", "a.mf4"));
        assertEquals(GraphEventGps.INCOMPATIBLE, clock.status());
        assertTrue(clock.statement().contains("not on the same clock"));

        GraphEventGps.Evidence missing = GraphEventGps.review(point("none", 1_000_000_000L, "meas", "a.mf4"),
                view(MapRouteModel.empty(), "meas", "a.mf4"));
        assertEquals(GraphEventGps.NO_SAMPLE, missing.status());
        assertTrue(missing.statement().contains("GPS unavailable"));

        GraphEventGps.Evidence unnamed = GraphEventGps.review(point("channel", 1_000_000_000L, "meas", "a.mf4"),
                new GraphEventGps.RouteView(route, "meas", "a.mf4", ""));
        assertEquals(GraphEventGps.INCOMPATIBLE, unnamed.status());
        assertTrue(unnamed.statement().contains("channels are not identified"));
        assertFalse(unnamed.statement().contains("Latitude "));
        assertEquals("LATITUDE", inside.latitudeChannel());
        assertEquals("LONGITUDE", inside.longitudeChannel());
    }

    @Test
    void duplicateTimestampsKeepTheFirstSampleAndInvalidCoordinatesAreSkipped() {
        var duplicated = MapRouteBuilder.validateAndBuild(
                samples(new long[] {1_000_000_000L, 1_000_000_000L, 4_000_000_000L}, new double[] {19.2, 11.0, 19.4}),
                samples(new long[] {1_000_000_000L, 1_000_000_000L, 4_000_000_000L}, new double[] {74.2, 22.0, 74.4}),
                List.of());
        assertTrue(duplicated.diagnostics().duplicateOrOutOfOrderTimestamps() > 0);
        assertEquals(19.2, duplicated.model().latitude(0), 0.0);
        assertFalse(duplicated.model().latitude(0) == 11.0);
        GraphEventGps.Evidence duplicate = GraphEventGps.review(point("dup", 1_000_000_000L, "meas", "a.mf4"),
                view(duplicated.model(), "meas", "a.mf4"));
        assertEquals(duplicated.model().latitude(0), duplicate.latitude(), 0.0);
        assertEquals(duplicated.model().longitude(0), duplicate.longitude(), 0.0);
        assertEquals(duplicated.model().timestampNanos(0), duplicate.sampleTimestampNanos());

        var invalid = MapRouteBuilder.validateAndBuild(
                samples(new long[] {1_000_000_000L, 1_400_000_000L}, new double[] {91.0, 19.5}),
                samples(new long[] {1_000_000_000L, 1_400_000_000L}, new double[] {10.0, 74.5}),
                List.of());
        assertEquals(1, invalid.model().size());
        assertEquals(19.5, invalid.model().latitude(0), 0.0);
        GraphEventGps.Evidence skipped = GraphEventGps.review(window("skip", 0L, 2_000_000_000L, "meas", "a.mf4"),
                view(invalid.model(), "meas", "a.mf4"));
        assertEquals(19.5, skipped.latitude(), 0.0);
        assertEquals(1_400_000_000L, skipped.sampleTimestampNanos());
    }

    @Test
    void savedMatchReopensAndAMissingSampleBecomesStale() {
        MapRouteModel route = route(new long[] {5_000_000_000L}, new double[] {19.03}, new double[] {74.72});
        EngineeringBookmarkMetadata original = point("saved", 5_000_000_000L, "meas", "a.mf4");
        GraphEventGps.Evidence matched = GraphEventGps.review(original, view(route, "meas", "a.mf4"));
        EngineeringBookmarkMetadata stored = with(original, GraphEventGps.write(original.observation(), matched));
        GraphEventGps.Evidence opened = GraphEventGps.read(stored);
        assertEquals(GraphEventGps.MATCHED, opened.status());
        assertEquals(19.03, opened.latitude(), 1e-6);
        assertEquals(matched.sampleTimestampNanos(), opened.sampleTimestampNanos());
        String edited = EngineerEventDocuments.withTitleAndNote(stored.observation(), "Renamed", "note");
        EngineeringBookmarkMetadata afterEdit = with(stored, edited);
        assertEquals("Renamed", InvestigationEventCodec.titleOf(afterEdit.observation()));
        assertEquals(GraphEventGps.MATCHED, GraphEventGps.read(afterEdit).status());
        assertEquals(5_000_000_000L, afterEdit.timestampNanos());

        GraphEventGps.Evidence stillThere = GraphEventGps.revalidate(afterEdit, view(route, "meas", "a.mf4"), true);
        assertEquals(GraphEventGps.MATCHED, stillThere.status());
        GraphEventGps.Evidence otherChannel = GraphEventGps.revalidate(afterEdit,
                new GraphEventGps.RouteView(route, "meas", "a.mf4", "", "OTHER_LAT", "LONGITUDE"), true);
        assertEquals(GraphEventGps.STALE, otherChannel.status());
        assertTrue(otherChannel.statement().contains("latitude channel"));
        GraphEventGps.Evidence stale = GraphEventGps.revalidate(afterEdit, view(MapRouteModel.empty(), "meas", "a.mf4"), true);
        assertEquals(GraphEventGps.STALE, stale.status());
        assertTrue(stale.statement().contains("not treated as verified"));
        assertFalse(stale.statement().contains("Latitude "));
        GraphEventGps.Evidence unchecked = GraphEventGps.revalidate(afterEdit, GraphEventGps.RouteView.none(), false);
        assertEquals(GraphEventGps.MATCHED, unchecked.status());

        String cleared = GraphEventGps.write(afterEdit.observation(), GraphEventGps.notRequested());
        assertEquals(GraphEventGps.NOT_REQUESTED, GraphEventGps.read(with(afterEdit, cleared)).status());
        assertTrue(cleared.contains("signalKeys="));
    }

    @Test
    void snapshotTextNamesASignalThePictureDoesNotContain() {
        SignalCatalogKey eventSignal = new SignalCatalogKey(
                "VehicleSpeed", SignalSourceType.DBC_CAN, "MsgB", 2, 1, "a.mf4", "a.dbc");
        SignalCatalogKey pictureSignal = new SignalCatalogKey(
                "BrakePressure", SignalSourceType.DBC_CAN, "MsgA", 1, 1, "a.mf4", "a.dbc");
        EngineeringBookmarkMetadata bookmark = point("shot", 1L, "meas", "a.mf4");
        String observation = EngineerEventDocuments.graphObservation(
                "shot", "", null, List.of("VehicleSpeed"),
                List.of(GraphInvestigationCodec.encodeKey(eventSignal)), "a.mf4");
        bookmark = with(bookmark, observation);
        GraphSnapshotMetadata meta = new GraphSnapshotMetadata(
                "snap", "inv", Instant.EPOCH, 0L, 10L,
                List.of(GraphInvestigationCodec.encodeKey(pictureSignal)), List.of("BrakePressure"),
                "1", "", "a.mf4", List.of(""), "Brake view", "");
        String disclosure = EventSnapshotReport.signalDisclosure(bookmark, meta);
        assertTrue(disclosure.contains("VehicleSpeed"));
        assertTrue(disclosure.contains("not a picture of that event signal"));
        GraphSnapshotMetadata same = new GraphSnapshotMetadata(
                "snap", "inv", Instant.EPOCH, 0L, 10L,
                List.of(GraphInvestigationCodec.encodeKey(eventSignal)), List.of("VehicleSpeed"),
                "1", "", "a.mf4", List.of(""), "Brake view", "");
        assertEquals("", EventSnapshotReport.signalDisclosure(bookmark, same));
    }

    @Test
    void lookupStaysOnTheRouteIndex() {
        int count = 8_000;
        long[] times = new long[count];
        double[] latitude = new double[count];
        double[] longitude = new double[count];
        for (int i = 0; i < count; i++) {
            times[i] = i * 1_000_000_000L;
            latitude[i] = 19.0 + i * 0.000001d;
            longitude[i] = 74.0 + i * 0.000001d;
        }
        MapRouteModel route = route(times, latitude, longitude);
        long started = System.nanoTime();
        GraphEventGps.Evidence last = null;
        for (int i = 0; i < 300; i++) {
            last = GraphEventGps.review(point("bulk", (count / 2L) * 1_000_000_000L + 20_000_000L, "meas", "a.mf4"),
                    view(route, "meas", "a.mf4"));
        }
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertNotNull(last);
        assertEquals(GraphEventGps.MATCHED, last.status());
        assertTrue(elapsedMs < 2_000L, "lookup took " + elapsedMs + " ms");
        System.out.println("M15 lookupMs=" + elapsedMs + " samples=" + route.size());
    }

    @Test
    @Timeout(180)
    void sierraSampleMatchesWithoutChangingTheStoredValue() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC));
        WorkspaceFile measurement = new WorkspaceFile(MF4, FileKind.MEASUREMENT_MF4, Files.size(MF4));
        WorkspaceFile definition = new WorkspaceFile(DBC, FileKind.DEFINITION_DBC, Files.size(DBC));
        LocalProcessingGateway.GatewayResult processed = LocalProcessingGateway.createDefault().process(
                measurement, definition, 512, new SignalPreviewBuffer(64), ProcessingProgressListener.NOOP, null);
        try {
            SignalSeriesStore store = processed.catalogStore();
            SignalCatalogEntry latitude = entry(store, "LATITUDE");
            SignalCatalogEntry longitude = entry(store, "LONGITUDE");
            SignalCatalogEntry thermal = entry(store, "CH01_FRONT_BUMPER_GRILL_AMBIENT");
            assertNotNull(latitude);
            assertNotNull(longitude);
            assertNotNull(thermal);
            var built = MapRouteBuilder.validateAndBuild(readAll(store, latitude), readAll(store, longitude), List.of());
            assertTrue(built.model().size() > 2, built.diagnostics().summary());
            int index = built.model().size() / 2;
            long sampleTime = built.model().timestampNanos(index);
            double kept = store.querySamples(thermal.key(), (int) (thermal.sampleCount() / 2), 1).getFirst().physicalValue();
            GraphEventGps.Evidence matched = GraphEventGps.review(
                    point("sierra", sampleTime, "sierra", MF4.getFileName().toString()),
                    view(built.model(), "sierra", MF4.getFileName().toString()));
            assertEquals(GraphEventGps.MATCHED, matched.status(), matched.statement());
            assertEquals(sampleTime, matched.sampleTimestampNanos());
            assertEquals(0L, matched.deltaNanos());
            assertEquals(built.model().latitude(index), matched.latitude(), 0.0);
            assertEquals(built.model().longitude(index), matched.longitude(), 0.0);
            assertTrue(matched.statement().contains("at the event timestamp"));
            long before = sampleTime - 2_000_000_000L;
            GraphEventGps.Evidence far = GraphEventGps.review(
                    point("before", before, "sierra", MF4.getFileName().toString()),
                    view(built.model(), "sierra", MF4.getFileName().toString()));
            if (before < built.model().timestampNanos(0) - GnssEventCorrelation.POINT_TOLERANCE_NANOS) {
                assertEquals(GraphEventGps.NO_SAMPLE, far.status(), far.statement());
            }
            assertEquals(kept, store.querySamples(thermal.key(), (int) (thermal.sampleCount() / 2), 1)
                    .getFirst().physicalValue(), 0.0);
            System.out.println("M15 sierraSampleNanos=" + sampleTime
                    + " lat=" + matched.latitude() + " lon=" + matched.longitude());
        } finally {
            if (processed.catalogStore() instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private static GraphEventGps.RouteView view(MapRouteModel model, String sourceId, String file) {
        return new GraphEventGps.RouteView(model, sourceId, file, "", "LATITUDE", "LONGITUDE");
    }

    private static MapRouteModel route(long[] times, double[] latitude, double[] longitude) {
        var built = MapRouteBuilder.validateAndBuild(samples(times, latitude), samples(times, longitude), List.of());
        assertFalse(built.model().isEmpty(), built.diagnostics().summary());
        return built.model();
    }

    private static List<SignalSamplePoint> samples(long[] times, double[] values) {
        List<SignalSamplePoint> out = new ArrayList<>();
        for (int i = 0; i < times.length; i++) {
            out.add(new SignalSamplePoint(times[i], values[i], null, null));
        }
        return out;
    }

    private static EngineeringBookmarkMetadata point(String name, long timestamp, String sourceId, String file) {
        return event(name, timestamp, null, sourceId, file);
    }

    private static EngineeringBookmarkMetadata window(
            String name, long start, long end, String sourceId, String file
    ) {
        return event(name, start, end, sourceId, file);
    }

    private static EngineeringBookmarkMetadata event(
            String name, long start, Long end, String sourceId, String file
    ) {
        String observation = EngineerEventDocuments.graphObservation(
                name, "", end, List.of("Speed"), List.of("speed-key"), file);
        return new EngineeringBookmarkMetadata(
                name, "project", sourceId, "", "Speed", "speed-key", start, "Braking",
                observation, Instant.EPOCH, Instant.EPOCH);
    }

    private static EngineeringBookmarkMetadata with(EngineeringBookmarkMetadata bookmark, String observation) {
        return new EngineeringBookmarkMetadata(
                bookmark.bookmarkId(), bookmark.projectId(), bookmark.sourceId(), bookmark.artifactId(),
                bookmark.signalName(), bookmark.signalKey(), bookmark.timestampNanos(), bookmark.eventType(),
                observation, bookmark.createdAt(), Instant.EPOCH);
    }

    private static SignalCatalogEntry entry(SignalSeriesStore store, String name) {
        for (SignalCatalogEntry candidate : store.snapshot().entries()) {
            if (name.equals(candidate.signalName())) {
                return candidate;
            }
        }
        return null;
    }

    private static List<SignalSamplePoint> readAll(SignalSeriesStore store, SignalCatalogEntry entry) {
        int retained = store.retainedSampleCount(entry.key());
        List<SignalSamplePoint> all = new ArrayList<>(Math.max(16, retained));
        int offset = 0;
        while (offset < retained) {
            List<SignalSamplePoint> page = store.querySamples(entry.key(), offset, 5_000);
            if (page.isEmpty()) {
                break;
            }
            all.addAll(page);
            offset += page.size();
        }
        return all;
    }
}
