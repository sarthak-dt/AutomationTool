package com.stellar.platform.desktop.presentation;

import com.stellar.platform.processing.graph.timeline.RecordingGroupIndex;
import com.stellar.platform.processing.graph.timeline.TimelineGroup;
import com.stellar.platform.processing.graph.timeline.TimelineViewportPlanner;

import java.util.Map;
import java.util.Objects;

/**
 * Places a display window with {@link GraphSharedViewport}, then asks the timeline planner
 * for stored query ranges. This is not a Graph control and does not draw a canvas.
 */
public final class ContinuousTimelineSession {

    private ContinuousTimelineSession() {
    }

    public static TimelineViewportPlanner.Plan plan(
            RecordingGroupIndex index,
            TimelineGroup group,
            long displayStart,
            long displayEnd,
            TimelineViewportPlanner.LogicalSignalSelection signal,
            Map<String, String> unitByMeasurement
    ) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(group, "group");
        long[] placed = GraphSharedViewport.placeWindow(
                displayStart,
                displayEnd,
                index.displayStart(group),
                index.displayEnd(group));
        return TimelineViewportPlanner.plan(index, group, placed[0], placed[1], signal, unitByMeasurement);
    }
}
