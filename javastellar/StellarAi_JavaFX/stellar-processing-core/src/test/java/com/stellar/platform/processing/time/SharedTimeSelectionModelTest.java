package com.stellar.platform.processing.time;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedTimeSelectionModelTest {

    @Test
    void startsWithNoSelection() {
        SharedTimeSelectionModel model = new SharedTimeSelectionModel();

        assertEquals(TimeSelection.Kind.NONE, model.selection().kind());
        assertFalse(model.selection().active());
    }

    @Test
    void notifiesListenersOnChange() {
        SharedTimeSelectionModel model = new SharedTimeSelectionModel();
        List<TimeSelection> seen = new ArrayList<>();
        model.addListener(seen::add);

        model.selectPoint(1_000, TimeSelection.Origin.MAP);
        model.selectRange(1_000, 2_000, TimeSelection.Origin.MAP);
        model.clear(TimeSelection.Origin.WAVEFORM);

        assertEquals(3, seen.size());
        assertEquals(TimeSelection.Kind.POINT, seen.get(0).kind());
        assertEquals(TimeSelection.Kind.RANGE, seen.get(1).kind());
        assertEquals(TimeSelection.Kind.NONE, seen.get(2).kind());
    }

    @Test
    void sameTimestampInAnotherMeasurementIsANewCursor() {
        SharedTimeSelectionModel model = new SharedTimeSelectionModel();
        List<TimeSelection> seen = new ArrayList<>();
        model.addListener(seen::add);

        assertTrue(model.selectMeasurementPoint("file-1", 500, null, false, true, TimeSelection.Origin.WAVEFORM));
        assertTrue(model.selectMeasurementPoint("file-2", 500, null, false, true, TimeSelection.Origin.WAVEFORM));

        assertEquals(2, seen.size());
        assertEquals("file-2", model.measurementCursor().measurementId());
        assertFalse(model.measurementCursor().clockComparable());
    }

    @Test
    void repeatingTheSameSelectionFromAnotherOriginIsIgnored() {
        SharedTimeSelectionModel model = new SharedTimeSelectionModel();
        List<TimeSelection> seen = new ArrayList<>();
        model.addListener(seen::add);

        assertTrue(model.set(TimeSelection.point(500, TimeSelection.Origin.MAP)));
        assertFalse(model.set(TimeSelection.point(500, TimeSelection.Origin.WAVEFORM)));

        assertEquals(1, seen.size());
    }

    @Test
    void reentrantUpdatesFromListenersCannotLoop() {
        SharedTimeSelectionModel model = new SharedTimeSelectionModel();
        List<TimeSelection> seen = new ArrayList<>();
        model.addListener(selection -> {
            seen.add(selection);
            // A naive two-way binding would ping-pong here forever.
            model.selectPoint(selection.startNanos() + 1, TimeSelection.Origin.WAVEFORM);
        });

        model.selectPoint(100, TimeSelection.Origin.MAP);

        assertEquals(1, seen.size());
        assertEquals(100, model.selection().startNanos());
    }

    @Test
    void reversedRangeIsNormalised() {
        TimeSelection selection = TimeSelection.range(9_000, 1_000, TimeSelection.Origin.MAP);

        assertEquals(1_000, selection.startNanos());
        assertEquals(9_000, selection.endNanos());
        assertEquals(8_000, selection.durationNanos());
    }

    @Test
    void removingAListenerStopsNotifications() {
        SharedTimeSelectionModel model = new SharedTimeSelectionModel();
        List<TimeSelection> seen = new ArrayList<>();
        Runnable remove = model.addListener(seen::add);

        model.selectPoint(1, TimeSelection.Origin.MAP);
        remove.run();
        model.selectPoint(2, TimeSelection.Origin.MAP);

        assertEquals(1, seen.size());
        assertEquals(0, model.listenerCount());
    }

    @Test
    void eventOriginUpdatesTheSameSelection() {
        SharedTimeSelectionModel model = new SharedTimeSelectionModel();
        model.selectPoint(42, TimeSelection.Origin.WAVEFORM);
        assertTrue(model.set(TimeSelection.point(99, TimeSelection.Origin.EVENT)));
        assertEquals(99, model.selection().startNanos());
        assertEquals(TimeSelection.Origin.EVENT, model.selection().origin());
        assertFalse(model.set(TimeSelection.point(99, TimeSelection.Origin.MAP)));
    }

    @Test
    void mapSegmentRangeSurvivesALaterPointSelection() {
        SharedTimeSelectionModel model = new SharedTimeSelectionModel();
        model.selectRange(1_000, 9_000, TimeSelection.Origin.MAP_SEGMENT);
        model.selectPoint(4_000, TimeSelection.Origin.MAP);

        assertEquals(TimeSelection.Kind.POINT, model.selection().kind());
        assertEquals(4_000, model.selection().startNanos());
        assertEquals(TimeSelection.Kind.RANGE, model.analysisRange().kind());
        assertEquals(1_000, model.analysisRange().startNanos());
        assertEquals(9_000, model.analysisRange().endNanos());
        assertEquals(TimeSelection.Origin.MAP_SEGMENT, model.analysisRange().origin());
    }

    @Test
    void waveformCanConsumeTheSameMapSegmentRange() {
        SharedTimeSelectionModel model = new SharedTimeSelectionModel();
        model.selectRange(10, 50, TimeSelection.Origin.MAP_SEGMENT);
        TimeSelection consumed = model.analysisRange();

        assertEquals(TimeSelection.Kind.RANGE, consumed.kind());
        assertEquals(10, consumed.startNanos());
        assertEquals(50, consumed.endNanos());
        assertTrue(consumed.origin().fromMap());
    }

    @Test
    void gapClearsTheOwnerAndTheNextSegmentRestoresIt() {
        SharedTimeSelectionModel model = new SharedTimeSelectionModel();
        List<String> states = new ArrayList<>();
        model.addListener(selection -> states.add(model.explicitNoOwner()
                ? MeasurementCursor.NO_MEASUREMENT_AT_TIME
                : model.measurementCursor().measurementId()));

        assertTrue(model.selectMeasurementPoint("mf4-a", 5L, 1_005L, true, true, TimeSelection.Origin.WAVEFORM));
        assertTrue(model.selectNoOwner(3_000L, TimeSelection.Origin.WAVEFORM));
        assertTrue(model.explicitNoOwner());
        assertFalse(model.measurementCursor().hasMeasurement());
        assertFalse(model.measurementCursor().placeMarker());
        assertEquals(3_000L, model.measurementCursor().absoluteTimestampNanos());
        assertTrue(model.selectMeasurementPoint("mf4-b", 4L, 5_004L, true, true, TimeSelection.Origin.WAVEFORM));
        assertFalse(model.explicitNoOwner());
        assertEquals("mf4-b", model.measurementCursor().measurementId());
        assertEquals(4L, model.measurementCursor().relativeTimestampNanos());
        assertEquals(List.of("mf4-a", MeasurementCursor.NO_MEASUREMENT_AT_TIME, "mf4-b"), states);
    }
}
