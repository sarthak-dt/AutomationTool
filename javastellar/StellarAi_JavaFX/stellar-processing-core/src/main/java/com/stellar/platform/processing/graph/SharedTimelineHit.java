package com.stellar.platform.processing.graph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Names the measurements under a cursor on a shared absolute timeline.
 * {@link #select} keeps a single winner: the series nearest the pointer, or the
 * front-most series when distances are equal. {@link #withinTolerance} keeps every
 * recording inside the pointer tolerance, including recordings that share one pixel.
 * A time that falls in a gap names no measurement.
 */
public final class SharedTimelineHit {

    public record Segment(
            String measurementId,
            String signalName,
            long storedStartNanos,
            long storedEndNanos,
            Long absoluteOriginNanos
    ) {
        public Segment {
            measurementId = measurementId == null ? "" : measurementId.trim();
            signalName = signalName == null ? "" : signalName;
        }

        public boolean onSharedClock() {
            return absoluteOriginNanos != null && !measurementId.isBlank() && storedEndNanos >= storedStartNanos;
        }
    }

    public record Hit(
            String measurementId,
            String signalName,
            int segmentIndex,
            long storedTimestampNanos,
            Long absoluteTimestampNanos,
            long segmentStartNanos,
            long segmentEndNanos
    ) {
    }

    /**
     * One recording that contains the pointer time. Equal screen positions stay as
     * separate candidates. Physical value and unit are attached by the waveform canvas.
     */
    public record Candidate(
            String measurementId,
            String signalName,
            int segmentIndex,
            long storedTimestampNanos,
            Long absoluteTimestampNanos,
            double distance
    ) {
    }

    private SharedTimelineHit() {
    }

    /**
     * Absolute time at a fraction of a shared axis. Zoom and pan change the axis ends;
     * they do not change which measurement owns an absolute instant.
     */
    public static long absoluteAt(long axisStart, long axisEnd, double fraction) {
        double clamped = Math.max(0, Math.min(1, fraction));
        if (axisEnd <= axisStart) {
            return axisStart;
        }
        return axisStart + (long) ((axisEnd - axisStart) * clamped);
    }

    public static Optional<Hit> select(long absoluteTimestampNanos, List<Segment> segments) {
        return select(absoluteTimestampNanos, segments, null, null);
    }

    public static Optional<Hit> select(
            long absoluteTimestampNanos,
            List<Segment> segments,
            Double pointerValue,
            double[] segmentValues
    ) {
        if (segments == null || segments.isEmpty()) {
            return Optional.empty();
        }
        int chosen = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        boolean useValue = pointerValue != null && Double.isFinite(pointerValue) && segmentValues != null;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (segment == null || !segment.onSharedClock() || !contains(segment, absoluteTimestampNanos)) {
                continue;
            }
            double distance = Double.POSITIVE_INFINITY;
            if (useValue && i < segmentValues.length && Double.isFinite(segmentValues[i])) {
                distance = Math.abs(segmentValues[i] - pointerValue);
            }
            boolean closer = distance < bestDistance;
            boolean sameDistancePreferFront = distance == bestDistance && i > chosen;
            if (chosen < 0 || closer || sameDistancePreferFront) {
                chosen = i;
                bestDistance = distance;
            }
        }
        if (chosen < 0) {
            return Optional.empty();
        }
        Segment segment = segments.get(chosen);
        long stored = Math.subtractExact(absoluteTimestampNanos, segment.absoluteOriginNanos());
        return Optional.of(new Hit(
                segment.measurementId(),
                segment.signalName(),
                chosen,
                stored,
                absoluteTimestampNanos,
                segment.storedStartNanos(),
                segment.storedEndNanos()));
    }

    /**
     * Every shared-clock segment that contains the absolute instant and lies within
     * {@code tolerance} of the pointer. Identical distances are all returned.
     * A missing pointer value returns every segment that contains the instant.
     */
    public static List<Candidate> withinTolerance(
            long absoluteTimestampNanos,
            List<Segment> segments,
            Double pointerValue,
            double[] segmentValues,
            double tolerance
    ) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        double limit = Double.isFinite(tolerance) ? Math.max(0, tolerance) : Double.POSITIVE_INFINITY;
        boolean useValue = pointerValue != null && Double.isFinite(pointerValue) && segmentValues != null;
        ArrayList<Candidate> found = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (segment == null || !segment.onSharedClock() || !contains(segment, absoluteTimestampNanos)) {
                continue;
            }
            double distance = 0;
            if (useValue) {
                if (i >= segmentValues.length || !Double.isFinite(segmentValues[i])) {
                    continue;
                }
                distance = Math.abs(segmentValues[i] - pointerValue);
                if (distance > limit) {
                    continue;
                }
            }
            long stored = Math.subtractExact(absoluteTimestampNanos, segment.absoluteOriginNanos());
            found.add(new Candidate(
                    segment.measurementId(),
                    segment.signalName(),
                    i,
                    stored,
                    absoluteTimestampNanos,
                    distance));
        }
        found.sort((left, right) -> {
            int byDistance = Double.compare(left.distance(), right.distance());
            if (byDistance != 0) {
                return byDistance;
            }
            return Integer.compare(right.segmentIndex(), left.segmentIndex());
        });
        return List.copyOf(found);
    }

    private static boolean contains(Segment segment, long absoluteTimestampNanos) {
        long stored;
        try {
            stored = Math.subtractExact(absoluteTimestampNanos, segment.absoluteOriginNanos());
        } catch (ArithmeticException overflow) {
            return false;
        }
        return stored >= segment.storedStartNanos() && stored <= segment.storedEndNanos();
    }
}
