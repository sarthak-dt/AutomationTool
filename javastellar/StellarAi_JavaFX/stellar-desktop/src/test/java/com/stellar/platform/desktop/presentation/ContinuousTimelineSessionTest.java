package com.stellar.platform.desktop.presentation;

import com.stellar.platform.processing.graph.timeline.RecordingDescriptor;
import com.stellar.platform.processing.graph.timeline.RecordingGroupIndex;
import com.stellar.platform.processing.graph.timeline.TimelineGroup;
import com.stellar.platform.processing.graph.timeline.TimelineViewportPlanner;
import com.stellar.platform.processing.graph.timeline.UnknownOrderLayout;
import com.stellar.platform.processing.map.time.ClockKind;
import com.stellar.platform.processing.map.time.RecordingClock;
import com.stellar.platform.processing.map.time.TimeUnitKind;
import com.stellar.platform.processing.map.time.TimestampSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContinuousTimelineSessionTest {

    private static final TimelineViewportPlanner.LogicalSignalSelection SPEED =
            new TimelineViewportPlanner.LogicalSignalSelection("VehicleSpeed", "km/h");

    @Test
    void panPastEitherEndStaysInsideTheUnknownOverview() {
        long slot = UnknownOrderLayout.EQUAL_SLOT_SPAN;
        RecordingGroupIndex index = RecordingGroupIndex.of(List.of(
                recording("m-b"),
                recording("m-a"),
                recording("m-c")));
        TimelineViewportPlanner.Plan left = ContinuousTimelineSession.plan(
                index, TimelineGroup.UNKNOWN_ORDER, -10 * slot, -9 * slot, SPEED, null);
        assertEquals(List.of("m-a"), left.visible().stream().map(TimelineViewportPlanner.SourceQueryWindow::measurementId).toList());
        assertEquals(0L, left.displayStart());
        assertEquals(slot, left.displayEnd());
        assertTrue(left.visible().getFirst().storedStartNanos() == 10L);

        TimelineViewportPlanner.Plan right = ContinuousTimelineSession.plan(
                index, TimelineGroup.UNKNOWN_ORDER, 10 * slot, 11 * slot, SPEED, null);
        assertEquals(List.of("m-c"), right.visible().stream().map(TimelineViewportPlanner.SourceQueryWindow::measurementId).toList());
        assertEquals(2 * slot, right.displayStart());
        assertEquals(3 * slot, right.displayEnd());
    }

    private static RecordingDescriptor recording(String id) {
        return new RecordingDescriptor(id, id + ".dat", RecordingClock.unordered(
                id, TimestampSource.NONE, ClockKind.RELATIVE_ONLY, TimeUnitKind.NANOSECONDS, "relative"),
                10L, 90L);
    }
}
