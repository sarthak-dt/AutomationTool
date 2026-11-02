package com.stellar.platform.processing.graph;

import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.map.GpsLocationSignalIndex;
import com.stellar.platform.processing.map.GpsSignalRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native GPS channels are persisted by the ordinary lossless signal store, so reopening a saved
 * project must restore them — including the metadata the location roles are derived from.
 */
class GpsSignalReloadTest {

    private static final int GPS_RECORD_ID = 9;

    @TempDir
    Path temp;

    @Test
    void gpsSignalsAndTheirRolesSurviveASaveAndReopenCycle() {
        Path root = temp.resolve("artifact");
        try (GraphSignalSessionStore store =
                     GraphSignalSessionStore.createPersistent(root, "drive.mf4", "veh.dbc")) {
            store.accept(recording());
            store.finishWriteSession();
        }

        GraphSignalSessionStore reopened = GraphSignalSessionStore.openExisting(root);
        SignalCatalog catalog = reopened.snapshot();

        assertEquals(4, catalog.nativeMf4SignalCount());
        SignalCatalogEntry latitude = entry(catalog, "LATITUDE");
        assertEquals(SignalSourceType.MF4_NATIVE, latitude.sourceType());
        assertEquals(GPS_RECORD_ID, latitude.channel());
        assertEquals("deg", latitude.unit());
        assertEquals(3L, latitude.sampleCount());
        assertEquals(1_000_000_000L, latitude.firstTimestampNanos());
        assertEquals(3_000_000_000L, latitude.lastTimestampNanos());

        GpsLocationSignalIndex index = GpsLocationSignalIndex.build(catalog.entries());
        assertTrue(index.hasRoute(), "roles must be rediscoverable from restored metadata");
        assertEquals(Optional.of(GpsSignalRole.LATITUDE), index.roleOf(latitude));
        assertEquals(4, index.size());
    }

    @Test
    void restoredCoordinateSamplesAreIdenticalToTheDecodedOnes() {
        Path root = temp.resolve("samples");
        try (GraphSignalSessionStore store =
                     GraphSignalSessionStore.createPersistent(root, "drive.mf4", "veh.dbc")) {
            store.accept(recording());
            store.finishWriteSession();
        }

        GraphSignalSessionStore reopened = GraphSignalSessionStore.openExisting(root);
        SignalCatalogEntry latitude = entry(reopened.snapshot(), "LATITUDE");
        var samples = reopened.querySamples(latitude.key(), 0, 10);

        assertEquals(3, samples.size());
        assertEquals(1_000_000_000L, samples.get(0).timestampNanos());
        assertEquals(18.556502, samples.get(0).physicalValue(), 1e-9);
        assertEquals(3_000_000_000L, samples.get(2).timestampNanos());
        assertEquals(18.556730, samples.get(2).physicalValue(), 1e-9);
    }

    private static List<CanonicalSignal> recording() {
        double[][] rows = {
                {18.556502, 73.715589, 563.80},
                {18.556612, 73.715701, 563.95},
                {18.556730, 73.715820, 564.10},
        };
        List<CanonicalSignal> out = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            long ts = (i + 1) * 1_000_000_000L;
            out.add(gps("LATITUDE", ts, rows[i][0], "deg"));
            out.add(gps("LONGITUDE", ts, rows[i][1], "deg"));
            out.add(gps("ALTITUDE", ts, rows[i][2], "m"));
            out.add(gps("QUALITY", ts, 1.0, ""));
        }
        return out;
    }

    private static CanonicalSignal gps(String name, long timestampNanos, double value, String unit) {
        return new CanonicalSignal(
                name,
                InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE,
                timestampNanos,
                0L,
                GPS_RECORD_ID,
                value,
                null,
                unit,
                null,
                new SourceReference(1, timestampNanos));
    }

    private static SignalCatalogEntry entry(SignalCatalog catalog, String signalName) {
        return catalog.entries().stream()
                .filter(e -> e.signalName().equals(signalName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(signalName + " missing after reload"));
    }
}
