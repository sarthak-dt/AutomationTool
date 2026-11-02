package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.desktop.presentation.GraphSharedViewport;
import com.stellar.platform.domain.CanonicalMeasurementSample;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.mf4.support.SyntheticMdf4Builder;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.graph.GraphFileFocus;
import com.stellar.platform.processing.graph.GraphTrackArrangement;
import com.stellar.platform.processing.graph.SharedDisplayTimeline;
import com.stellar.platform.processing.map.time.RecordingClock;
import com.stellar.platform.processing.map.time.RecordingClockReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Placement against decoded MF4 samples. Stored timestamps stay measurement-relative.
 */
class SharedUtcPlacementTest {

    private static final long S = 1_000_000_000L;
    private static final long ORIGIN = 1_674_000_000_000_000_000L;

    @TempDir
    Path temp;

    @Test
    void decodedSamplesPlaceOnASixtySecondSharedAxis() throws Exception {
        Recording a = write("utc-a", ORIGIN, 110);
        Recording c = write("utc-c", ORIGIN + 10 * S, 330);
        Recording b = write("utc-b", ORIGIN + 40 * S, 220);
        assertTrue(a.clock().comparable());
        assertTrue(c.clock().comparable());
        assertTrue(b.clock().comparable());

        SharedDisplayTimeline timeline = SharedDisplayTimeline.of(List.of(
                member(a), member(c), member(b)));
        assertEquals(60 * S, timeline.displayEnd() - timeline.displayStart());

        assertEquals(113.0, speedAt(a, 15 * S), 0.0);
        assertEquals(331.0, speedAt(c, 5 * S), 0.0);
        assertEquals(221.0, speedAt(b, 5 * S), 0.0);
        assertEquals(15 * S, timeline.toDisplay(timeline.member("utc-a"), 15 * S));
        assertEquals(15 * S, timeline.toDisplay(timeline.member("utc-c"), 5 * S));
        assertEquals(45 * S, timeline.toDisplay(timeline.member("utc-b"), 5 * S));
        assertEquals(5 * S, timeline.storedQuery(timeline.member("utc-c"), 15 * S, 15 * S)[0]);
        assertNull(timeline.storedQuery(timeline.member("utc-a"), 35 * S, 35 * S));
        assertNull(timeline.storedQuery(timeline.member("utc-b"), 35 * S, 35 * S));
        assertNull(timeline.storedQuery(timeline.member("utc-c"), 35 * S, 35 * S));

        List<GraphTrackArrangement.Input> inputs = new ArrayList<>();
        for (Recording recording : List.of(a, c, b)) {
            SharedDisplayTimeline.Member member = timeline.member(recording.id());
            inputs.add(new GraphTrackArrangement.Input(
                    member.storedStartNanos(), member.storedEndNanos(), timeline.displayOffsetNanos(member)));
        }
        List<GraphTrackArrangement.Pane> panes = GraphTrackArrangement.panes(
                GraphTrackArrangement.Mode.SHARED_ABSOLUTE, inputs, 0, 600);
        assertEquals(0L, panes.getFirst().axisStart());
        assertEquals(60 * S, panes.getFirst().axisEnd());
        assertEquals(panes.get(0).x0(), panes.get(2).x0(), 0.0);

        long fullStart = timeline.displayStart();
        long fullEnd = timeline.displayEnd();
        long[] fitted = GraphSharedViewport.placeWindow(fullStart, fullEnd, fullStart, fullEnd);
        assertEquals(fullStart, fitted[0]);
        assertEquals(fullEnd, fitted[1]);
        long[] zoomed = GraphSharedViewport.zoomAround(fullStart, fullEnd, 0.5, 15 * S, fullStart, fullEnd);
        assertTrue(zoomed[0] >= fullStart && zoomed[1] <= fullEnd);
        assertTrue(zoomed[1] - zoomed[0] < 60 * S);
        long[] panned = GraphSharedViewport.panBy(zoomed[0], zoomed[1], 20 * S, fullStart, fullEnd);
        assertTrue(panned[1] <= fullEnd);
        long[] reset = GraphSharedViewport.placeWindow(fullStart, fullEnd, fullStart, fullEnd);
        assertEquals(60 * S, reset[1] - reset[0]);

        long[] focused = GraphFileFocus.queryWindow(0L, 20 * S, 0L, 20 * S);
        assertEquals(0L, focused[0]);
        assertEquals(20 * S, focused[1]);
    }

    private static SharedDisplayTimeline.Member member(Recording recording) {
        long first = recording.samples().getFirst().timestampNanos();
        long last = recording.samples().getLast().timestampNanos();
        return new SharedDisplayTimeline.Member(recording.id(), recording.clock().utcStartNanos(), first, last);
    }

    private static double speedAt(Recording recording, long storedNanos) {
        for (CanonicalMeasurementSample sample : recording.samples()) {
            if (sample.timestampNanos() == storedNanos) {
                return sample.physicalValue();
            }
        }
        throw new AssertionError("missing sample " + storedNanos);
    }

    private Recording write(String id, long utcStart, int firstSpeed) throws Exception {
        Path file = temp.resolve(id + ".mf4");
        try (SyntheticMdf4Builder builder = SyntheticMdf4Builder.create(file)) {
            builder.writeIdBlock("4.10", 410, 0);
            long hd = builder.writeHd(0, 0, 0);
            builder.patchLong(hd + 24 + 6L * 8L, utcStart);
            long unitS = builder.writeTx("s");
            long timeCc = builder.writeCcLinear(0.0, 1.0e-9, unitS);
            long unit = builder.writeTx("km/h");
            long valueCc = builder.writeCcLinear(0.0, 1.0, unit);
            long speed = builder.writeCn(
                    0L, 0L, builder.writeTx("VehicleSpeed"), unit, 0L, valueCc,
                    0, 0, 4, 8, 0, 64, 0, 0);
            long time = builder.writeCn(
                    speed, 0L, builder.writeTx("time"), unitS, 0L, timeCc,
                    2, 1, 4, 0, 0, 64, 0, 0);
            long group = builder.writeCg(0, time, 1, 5, 0, 16, 0);
            ByteBuffer records = ByteBuffer.allocate(16 * 5).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 5; i++) {
                records.putDouble(i * 5.0e9);
                records.putDouble(firstSpeed + i);
            }
            long data = builder.writeDt(records.array());
            long dg = builder.writeDg(0, group, data, 0);
            builder.patchLink(hd, 0, dg);
        }
        List<CanonicalMeasurementSample> samples = new MdfDynamicRecordDecoder()
                .decodeAll(file, ReaderContext.ofBatchSize(8))
                .nativeSignals()
                .stream()
                .filter(sample -> sample.signalName().equals("VehicleSpeed"))
                .toList();
        assertEquals(5, samples.size(), id);
        RecordingClock clock = RecordingClockReader.read(file, id);
        return new Recording(id, clock, samples);
    }

    private record Recording(String id, RecordingClock clock, List<CanonicalMeasurementSample> samples) {
    }
}
