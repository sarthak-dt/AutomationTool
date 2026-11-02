package com.stellar.platform.processing.graph.timeline;

import com.stellar.platform.processing.graph.SharedDisplayTimeline;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Splits a project into verified-UTC recordings and unknown-order recordings.
 *
 * <p>One unknown clock does not remove UTC alignment from the others. Membership follows
 * {@link com.stellar.platform.processing.map.time.RecordingClock#comparable()} only.
 * File names and the order of {@code recordings} are not a chronology.
 */
public final class RecordingGroupIndex {

    private static final Comparator<RecordingDescriptor> PRESENTATION_ORDER =
            Comparator.comparing(RecordingDescriptor::measurementId);

    private final List<RecordingDescriptor> verifiedUtc;
    private final List<RecordingDescriptor> unknownOrder;
    private final SharedDisplayTimeline utcTimeline;
    private final UnknownOrderLayout unknownLayout;

    private RecordingGroupIndex(
            List<RecordingDescriptor> verifiedUtc,
            List<RecordingDescriptor> unknownOrder,
            SharedDisplayTimeline utcTimeline,
            UnknownOrderLayout unknownLayout
    ) {
        this.verifiedUtc = verifiedUtc;
        this.unknownOrder = unknownOrder;
        this.utcTimeline = utcTimeline;
        this.unknownLayout = unknownLayout;
    }

    public static RecordingGroupIndex of(List<RecordingDescriptor> recordings) {
        if (recordings == null || recordings.isEmpty()) {
            return new RecordingGroupIndex(List.of(), List.of(), null, null);
        }
        Map<String, RecordingDescriptor> byId = new LinkedHashMap<>();
        for (RecordingDescriptor recording : recordings) {
            if (recording == null) {
                continue;
            }
            if (byId.putIfAbsent(recording.measurementId(), recording) != null) {
                throw new IllegalArgumentException("duplicate measurement " + recording.measurementId());
            }
        }
        List<RecordingDescriptor> utc = new ArrayList<>();
        List<RecordingDescriptor> unknown = new ArrayList<>();
        for (RecordingDescriptor recording : byId.values()) {
            if (recording.verifiedUtc()) {
                utc.add(recording);
            } else {
                unknown.add(recording);
            }
        }
        utc.sort(Comparator.comparingLong((RecordingDescriptor recording) -> recording.clock().utcStartNanos())
                .thenComparing(PRESENTATION_ORDER));
        unknown.sort(PRESENTATION_ORDER);
        SharedDisplayTimeline timeline = utc.isEmpty() ? null : SharedDisplayTimeline.of(members(utc));
        UnknownOrderLayout layout = unknown.isEmpty() ? null : layout(unknown);
        return new RecordingGroupIndex(List.copyOf(utc), List.copyOf(unknown), timeline, layout);
    }

    public List<RecordingDescriptor> verifiedUtc() {
        return verifiedUtc;
    }

    public List<RecordingDescriptor> unknownOrder() {
        return unknownOrder;
    }

    public SharedDisplayTimeline utcTimeline() {
        return utcTimeline;
    }

    public UnknownOrderLayout unknownLayout() {
        return unknownLayout;
    }

    public int size(TimelineGroup group) {
        return group == TimelineGroup.VERIFIED_UTC ? verifiedUtc.size() : unknownOrder.size();
    }

    public long displayStart(TimelineGroup group) {
        if (group == TimelineGroup.VERIFIED_UTC) {
            return utcTimeline == null ? 0L : utcTimeline.displayStart();
        }
        return unknownLayout == null ? 0L : unknownLayout.layoutStart();
    }

    public long displayEnd(TimelineGroup group) {
        if (group == TimelineGroup.VERIFIED_UTC) {
            return utcTimeline == null ? 0L : utcTimeline.displayEnd();
        }
        return unknownLayout == null ? 0L : unknownLayout.layoutEnd();
    }

    /**
     * Verified UTC for a shared-display offset. Empty for the unknown-order group and for a
     * display position that is not on that UTC timeline. Layout slot positions are never converted.
     */
    public Long verifiedUtcNanos(TimelineGroup group, long displayNanos) {
        if (group != TimelineGroup.VERIFIED_UTC || utcTimeline == null) {
            return null;
        }
        return utcTimeline.toEpoch(displayNanos);
    }

    private static List<SharedDisplayTimeline.Member> members(List<RecordingDescriptor> utc) {
        List<SharedDisplayTimeline.Member> members = new ArrayList<>(utc.size());
        for (RecordingDescriptor recording : utc) {
            members.add(new SharedDisplayTimeline.Member(
                    recording.measurementId(),
                    recording.clock().utcStartNanos(),
                    recording.storedStartNanos(),
                    recording.storedEndNanos()));
        }
        return members;
    }

    private static UnknownOrderLayout layout(List<RecordingDescriptor> unknown) {
        List<UnknownOrderLayout.Slot> slots = new ArrayList<>(unknown.size());
        for (int i = 0; i < unknown.size(); i++) {
            RecordingDescriptor recording = unknown.get(i);
            long start = Math.multiplyExact((long) i, UnknownOrderLayout.EQUAL_SLOT_SPAN);
            slots.add(new UnknownOrderLayout.Slot(
                    recording.measurementId(),
                    recording.sourceFile(),
                    i,
                    recording.storedStartNanos(),
                    recording.storedEndNanos(),
                    start,
                    start + UnknownOrderLayout.EQUAL_SLOT_SPAN));
        }
        return new UnknownOrderLayout(slots);
    }
}
