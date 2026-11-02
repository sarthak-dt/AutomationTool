package com.stellar.platform.processing.graph.timeline;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.graph.GraphViewportRequest;
import com.stellar.platform.processing.graph.SharedDisplayTimeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns one group's visible display window into independent stored-time query windows.
 *
 * <p>The display window is the range already placed for that group (the same start/end
 * {@code GraphSharedViewport} pans and zooms). This planner does not decode samples and does
 * not invent a second clock. Each window is a range the existing {@code GraphViewportEngine}
 * can query on that recording alone.
 *
 * <p>Off-screen recordings are not part of the visible query. At most two neighbours are
 * marked for prefetch. Prefetch is not a visible query and does not grow with the file count.
 */
public final class TimelineViewportPlanner {

    public static final int MAX_PREFETCH = 2;

    public record LogicalSignalSelection(String signalName, String unit) {
        public LogicalSignalSelection {
            if (signalName == null || signalName.isBlank()) {
                throw new IllegalArgumentException("signalName");
            }
            signalName = signalName.trim();
            unit = unit == null ? "" : unit.trim();
        }
    }

    /**
     * One recording's original stored range. Layout positions are intentionally absent so a
     * query cannot be mistaken for an absolute or virtual timestamp.
     */
    public record SourceQueryWindow(
            String measurementId,
            String sourceFile,
            long storedStartNanos,
            long storedEndNanos,
            TimelineGroup group,
            boolean prefetch
    ) {
        public SourceQueryWindow {
            measurementId = measurementId == null ? "" : measurementId;
            sourceFile = sourceFile == null ? "" : sourceFile;
            if (storedEndNanos < storedStartNanos) {
                throw new IllegalArgumentException("stored end before start");
            }
            Objects.requireNonNull(group, "group");
        }
    }

    public record Plan(
            TimelineGroup group,
            long displayStart,
            long displayEnd,
            List<SourceQueryWindow> visible,
            List<SourceQueryWindow> prefetch,
            int recordingsInGroup,
            int excludedOffScreen,
            int missingSignal,
            int incompatibleUnit
    ) {
        public Plan {
            Objects.requireNonNull(group, "group");
            visible = visible == null ? List.of() : List.copyOf(visible);
            prefetch = prefetch == null ? List.of() : List.copyOf(prefetch);
        }

        public int queriedRecordings() {
            return visible.size();
        }

        /** Waveforms stay in their own recording. The planner never bridges a gap or a slot seam. */
        public boolean connectsAcrossRecordings() {
            return false;
        }
    }

    private TimelineViewportPlanner() {
    }

    /**
     * @param unitByMeasurement measurement id to the unit of {@code signal} in that recording.
     *                          A missing id means the signal is absent. {@code null} means every
     *                          recording in the group has the signal at {@code signal.unit()}.
     */
    public static Plan plan(
            RecordingGroupIndex index,
            TimelineGroup group,
            long displayStart,
            long displayEnd,
            LogicalSignalSelection signal,
            Map<String, String> unitByMeasurement
    ) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(signal, "signal");
        if (displayEnd < displayStart) {
            throw new IllegalArgumentException("display end before start");
        }
        if (group == TimelineGroup.VERIFIED_UTC) {
            return planUtc(index, displayStart, displayEnd, signal, unitByMeasurement);
        }
        return planUnknown(index, displayStart, displayEnd, signal, unitByMeasurement);
    }

    /**
     * Visible windows as engine requests. {@code timeOriginNanos} stays 0: the request is stored time.
     * Prefetch is omitted so an invisible recording is not decoded.
     */
    public static List<GraphViewportRequest> lodRequests(
            Plan plan,
            Map<String, List<SignalCatalogKey>> keysByMeasurement,
            int pixelWidth
    ) {
        Objects.requireNonNull(plan, "plan");
        if (keysByMeasurement == null || keysByMeasurement.isEmpty()) {
            return List.of();
        }
        List<GraphViewportRequest> requests = new ArrayList<>(plan.visible().size());
        for (SourceQueryWindow window : plan.visible()) {
            List<SignalCatalogKey> keys = keysByMeasurement.get(window.measurementId());
            if (keys == null || keys.isEmpty()) {
                continue;
            }
            requests.add(new GraphViewportRequest(
                    keys, window.storedStartNanos(), window.storedEndNanos(), pixelWidth, 0L));
        }
        return List.copyOf(requests);
    }

    private static Plan planUtc(
            RecordingGroupIndex index,
            long displayStart,
            long displayEnd,
            LogicalSignalSelection signal,
            Map<String, String> unitByMeasurement
    ) {
        SharedDisplayTimeline timeline = index.utcTimeline();
        if (timeline == null) {
            return empty(TimelineGroup.VERIFIED_UTC, displayStart, displayEnd);
        }
        List<SourceQueryWindow> visible = new ArrayList<>();
        int missing = 0;
        int incompatible = 0;
        int intersecting = 0;
        for (RecordingDescriptor recording : index.verifiedUtc()) {
            SharedDisplayTimeline.Member member = timeline.member(recording.measurementId());
            long[] stored = timeline.storedQuery(member, displayStart, displayEnd);
            if (stored == null) {
                continue;
            }
            intersecting++;
            SourceQueryWindow window = accept(recording, stored[0], stored[1], TimelineGroup.VERIFIED_UTC, false, signal, unitByMeasurement);
            if (window == null) {
                if (absent(recording, unitByMeasurement)) {
                    missing++;
                } else {
                    incompatible++;
                }
                continue;
            }
            visible.add(window);
        }
        List<SourceQueryWindow> prefetch = prefetchUtc(
                index, timeline, displayStart, displayEnd, signal, unitByMeasurement, visible);
        return new Plan(
                TimelineGroup.VERIFIED_UTC,
                displayStart,
                displayEnd,
                visible,
                prefetch,
                index.verifiedUtc().size(),
                index.verifiedUtc().size() - intersecting,
                missing,
                incompatible);
    }

    private static Plan planUnknown(
            RecordingGroupIndex index,
            long displayStart,
            long displayEnd,
            LogicalSignalSelection signal,
            Map<String, String> unitByMeasurement
    ) {
        UnknownOrderLayout layout = index.unknownLayout();
        if (layout == null) {
            return empty(TimelineGroup.UNKNOWN_ORDER, displayStart, displayEnd);
        }
        List<SourceQueryWindow> visible = new ArrayList<>();
        int missing = 0;
        int incompatible = 0;
        int first = -1;
        int last = -1;
        for (UnknownOrderLayout.Slot slot : layout.slots()) {
            long[] stored = layout.storedQuery(slot, displayStart, displayEnd);
            if (stored == null) {
                continue;
            }
            if (first < 0) {
                first = slot.index();
            }
            last = slot.index();
            RecordingDescriptor recording = find(index.unknownOrder(), slot.measurementId());
            SourceQueryWindow window = accept(recording, stored[0], stored[1], TimelineGroup.UNKNOWN_ORDER, false, signal, unitByMeasurement);
            if (window == null) {
                if (absent(recording, unitByMeasurement)) {
                    missing++;
                } else {
                    incompatible++;
                }
                continue;
            }
            visible.add(window);
        }
        int intersecting = first < 0 ? 0 : last - first + 1;
        List<SourceQueryWindow> prefetch = prefetchUnknown(
                index, layout, first, last, signal, unitByMeasurement);
        return new Plan(
                TimelineGroup.UNKNOWN_ORDER,
                displayStart,
                displayEnd,
                visible,
                prefetch,
                layout.size(),
                layout.size() - intersecting,
                missing,
                incompatible);
    }

    private static List<SourceQueryWindow> prefetchUtc(
            RecordingGroupIndex index,
            SharedDisplayTimeline timeline,
            long displayStart,
            long displayEnd,
            LogicalSignalSelection signal,
            Map<String, String> unitByMeasurement,
            List<SourceQueryWindow> visible
    ) {
        long span = Math.max(0L, displayEnd - displayStart);
        RecordingDescriptor left = null;
        RecordingDescriptor right = null;
        long leftGap = Long.MAX_VALUE;
        long rightGap = Long.MAX_VALUE;
        for (RecordingDescriptor recording : index.verifiedUtc()) {
            if (contains(visible, recording.measurementId())) {
                continue;
            }
            SharedDisplayTimeline.Member member = timeline.member(recording.measurementId());
            long memberStart = timeline.toDisplay(member, member.storedStartNanos());
            long memberEnd = timeline.toDisplay(member, member.storedEndNanos());
            if (memberEnd < displayStart) {
                long gap = displayStart - memberEnd;
                if (gap <= span && gap < leftGap) {
                    left = recording;
                    leftGap = gap;
                }
            } else if (memberStart > displayEnd) {
                long gap = memberStart - displayEnd;
                if (gap <= span && gap < rightGap) {
                    right = recording;
                    rightGap = gap;
                }
            }
        }
        List<SourceQueryWindow> prefetch = new ArrayList<>(MAX_PREFETCH);
        addPrefetch(prefetch, left, signal, unitByMeasurement, TimelineGroup.VERIFIED_UTC);
        addPrefetch(prefetch, right, signal, unitByMeasurement, TimelineGroup.VERIFIED_UTC);
        return List.copyOf(prefetch);
    }

    private static List<SourceQueryWindow> prefetchUnknown(
            RecordingGroupIndex index,
            UnknownOrderLayout layout,
            int first,
            int last,
            LogicalSignalSelection signal,
            Map<String, String> unitByMeasurement
    ) {
        if (first < 0) {
            return List.of();
        }
        List<SourceQueryWindow> prefetch = new ArrayList<>(MAX_PREFETCH);
        if (first > 0) {
            addSlotPrefetch(prefetch, index, layout.slot(first - 1), signal, unitByMeasurement);
        }
        if (last >= 0 && last + 1 < layout.size()) {
            addSlotPrefetch(prefetch, index, layout.slot(last + 1), signal, unitByMeasurement);
        }
        return List.copyOf(prefetch);
    }

    private static void addSlotPrefetch(
            List<SourceQueryWindow> prefetch,
            RecordingGroupIndex index,
            UnknownOrderLayout.Slot slot,
            LogicalSignalSelection signal,
            Map<String, String> unitByMeasurement
    ) {
        RecordingDescriptor recording = find(index.unknownOrder(), slot.measurementId());
        addPrefetch(prefetch, recording, signal, unitByMeasurement, TimelineGroup.UNKNOWN_ORDER);
    }

    private static void addPrefetch(
            List<SourceQueryWindow> prefetch,
            RecordingDescriptor recording,
            LogicalSignalSelection signal,
            Map<String, String> unitByMeasurement,
            TimelineGroup group
    ) {
        if (recording == null || prefetch.size() >= MAX_PREFETCH) {
            return;
        }
        SourceQueryWindow window = accept(
                recording, recording.storedStartNanos(), recording.storedEndNanos(), group, true, signal, unitByMeasurement);
        if (window != null) {
            prefetch.add(window);
        }
    }

    private static SourceQueryWindow accept(
            RecordingDescriptor recording,
            long storedStart,
            long storedEnd,
            TimelineGroup group,
            boolean prefetch,
            LogicalSignalSelection signal,
            Map<String, String> unitByMeasurement
    ) {
        if (recording == null) {
            return null;
        }
        if (unitByMeasurement != null) {
            String unit = unitByMeasurement.get(recording.measurementId());
            if (unit == null || !LogicalSignalTrack.sameTrack(signal.signalName(), signal.unit(), signal.signalName(), unit)) {
                return null;
            }
        }
        return new SourceQueryWindow(
                recording.measurementId(),
                recording.sourceFile(),
                storedStart,
                storedEnd,
                group,
                prefetch);
    }

    private static boolean absent(RecordingDescriptor recording, Map<String, String> unitByMeasurement) {
        return unitByMeasurement != null && !unitByMeasurement.containsKey(recording.measurementId());
    }

    private static boolean contains(List<SourceQueryWindow> windows, String measurementId) {
        for (SourceQueryWindow window : windows) {
            if (measurementId.equals(window.measurementId())) {
                return true;
            }
        }
        return false;
    }

    private static RecordingDescriptor find(List<RecordingDescriptor> recordings, String measurementId) {
        for (RecordingDescriptor recording : recordings) {
            if (measurementId.equals(recording.measurementId())) {
                return recording;
            }
        }
        return null;
    }

    private static Plan empty(TimelineGroup group, long displayStart, long displayEnd) {
        return new Plan(group, displayStart, displayEnd, List.of(), List.of(), 0, 0, 0, 0);
    }
}
