package com.stellar.platform.processing.align;

import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalSamplePoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Shared timestamp-union alignment over retained catalog sample series.
 * <p>
 * <b>Alignment rule:</b> timeline = sorted UNION of exact {@code timestampNanos} values
 * across selected signals. Values are never aligned by row index, sample occurrence, or
 * assumed frequency. Missing cells stay {@code null} (no forward-fill, interpolation, or zero).
 * <p>
 * <b>Duplicate same-signal / same-timestamp:</b> the catalog may retain multiple samples at
 * an identical nanosecond tick. The aligned view collapses them to one cell using the
 * <em>last</em> sample in retention order (deterministic; no averaging). Distinct timestamps
 * are never discarded.
 * <p>
 * Series are expected in ascending timestamp order (catalog streaming order). If a series is
 * unordered, a defensive sort is applied for that series only.
 */
public final class AlignedSignalQuery {

    private AlignedSignalQuery() {
    }

    /**
     * Builds one aligned page over the provided series (column order preserved).
     *
     * @param series one sample list per selected signal, in workspace column order
     * @param offset global aligned-row index to start from (0-based)
     * @param limit  max aligned rows to emit
     */
    public static AlignedSignalPage query(List<List<SignalSamplePoint>> series, int offset, int limit) {
        Objects.requireNonNull(series, "series");
        if (series.isEmpty()) {
            return AlignedSignalPage.empty();
        }
        List<List<SignalSamplePoint>> normalized = normalizeSeries(series);
        long[] timeline = unionTimestampsNormalized(normalized);
        return queryNormalized(normalized, timeline, offset, limit);
    }

    /**
     * Builds an aligned page using an already-computed exact timestamp union.
     * <p>
     * This is intended for UI/window paging where the timeline is cached. It avoids rebuilding
     * the same global union for every page while preserving exactly the same alignment semantics
     * as {@link #query(List, int, int)}.
     */
    public static AlignedSignalPage query(
            List<List<SignalSamplePoint>> series,
            long[] timeline,
            int offset,
            int limit
    ) {
        Objects.requireNonNull(series, "series");
        Objects.requireNonNull(timeline, "timeline");
        if (series.isEmpty()) {
            return AlignedSignalPage.empty();
        }
        return queryNormalized(normalizeSeries(series), timeline, offset, limit);
    }

    /** Convenience: page from offset 0. */
    public static AlignedSignalPage query(List<List<SignalSamplePoint>> series, int limit) {
        return query(series, 0, limit);
    }

    /** Sorted unique timestamps across series (exact long ticks). */
    public static long[] unionTimestamps(List<List<SignalSamplePoint>> series) {
        Objects.requireNonNull(series, "series");
        return unionTimestampsNormalized(normalizeSeries(series));
    }

    public static int countUnionTimestamps(List<List<SignalSamplePoint>> series) {
        return unionTimestamps(series).length;
    }

    /**
     * Merges an existing sorted-unique timeline with one additional retained series using
     * primitive two-way merge. Exact nanosecond ticks are preserved and duplicates collapse.
     */
    public static long[] mergeTimeline(long[] existingTimeline, List<SignalSamplePoint> additionalSeries) {
        Objects.requireNonNull(existingTimeline, "existingTimeline");
        if (additionalSeries == null || additionalSeries.isEmpty()) {
            return Arrays.copyOf(existingTimeline, existingTimeline.length);
        }
        List<SignalSamplePoint> normalized = isNonDecreasing(additionalSeries)
                ? additionalSeries
                : normalizeSeries(List.of(additionalSeries)).getFirst();
        long[] added = uniqueTimestamps(normalized);
        if (existingTimeline.length == 0) {
            return added;
        }
        LongArrayBuilder merged = new LongArrayBuilder((long) existingTimeline.length + added.length);
        int i = 0;
        int j = 0;
        while (i < existingTimeline.length || j < added.length) {
            long value;
            if (j >= added.length || (i < existingTimeline.length && existingTimeline[i] <= added[j])) {
                value = existingTimeline[i++];
            } else {
                value = added[j++];
            }
            merged.addIfDifferent(value);
        }
        return merged.toArray();
    }

    /**
     * First index in sorted {@code timestamps} with value &gt;= {@code timestampNanos},
     * or {@code timestamps.length} if all are smaller (ceil / at-or-after navigation).
     */
    public static int firstIndexAtOrAfter(long[] timestamps, long timestampNanos) {
        Objects.requireNonNull(timestamps, "timestamps");
        int idx = Arrays.binarySearch(timestamps, timestampNanos);
        if (idx >= 0) {
            return idx;
        }
        return -idx - 1;
    }

    private static AlignedSignalPage queryNormalized(
            List<List<SignalSamplePoint>> normalized,
            long[] timeline,
            int offset,
            int limit
    ) {
        int n = normalized.size();
        int total = timeline.length;
        int start = Math.min(Math.max(0, offset), total);
        int cap = Math.max(1, limit);
        if (total == 0 || start >= total) {
            return new AlignedSignalPage(List.of(), start, cap, total, n);
        }

        int end = Math.min(total, start + cap);
        List<AlignedSignalRow> rows = new ArrayList<>(end - start);
        int[] cursors = new int[n];
        long firstTimestamp = timeline[start];
        for (int s = 0; s < n; s++) {
            cursors[s] = firstSampleAtOrAfter(normalized.get(s), firstTimestamp);
        }

        for (int i = start; i < end; i++) {
            long ts = timeline[i];
            Double[] physical = new Double[n];
            Long[] raw = new Long[n];
            String[] textual = new String[n];
            SignalValidity[] validity = new SignalValidity[n];
            for (int s = 0; s < n; s++) {
                SignalSamplePoint tick = advanceTo(normalized.get(s), cursors, s, ts);
                if (tick != null) {
                    physical[s] = tick.physicalValue();
                    raw[s] = tick.rawValue();
                    textual[s] = tick.textualValue();
                    validity[s] = tick.validity();
                }
            }
            rows.add(new AlignedSignalRow(i, ts, physical, raw, textual, validity));
        }
        return new AlignedSignalPage(rows, start, cap, total, n);
    }

    private static List<List<SignalSamplePoint>> normalizeSeries(List<List<SignalSamplePoint>> series) {
        List<List<SignalSamplePoint>> out = new ArrayList<>(series.size());
        for (List<SignalSamplePoint> points : series) {
            if (points == null || points.isEmpty()) {
                out.add(List.of());
                continue;
            }
            if (isNonDecreasing(points)) {
                out.add(points);
            } else {
                List<SignalSamplePoint> copy = new ArrayList<>(points);
                copy.sort(Comparator.comparingLong(SignalSamplePoint::timestampNanos));
                out.add(List.copyOf(copy));
            }
        }
        return out;
    }

    private static boolean isNonDecreasing(List<SignalSamplePoint> points) {
        long prev = Long.MIN_VALUE;
        for (SignalSamplePoint p : points) {
            if (p.timestampNanos() < prev) {
                return false;
            }
            prev = p.timestampNanos();
        }
        return true;
    }

    private static long[] unionTimestampsNormalized(List<List<SignalSamplePoint>> series) {
        int nonEmpty = 0;
        long estimatedSamples = 0L;
        for (List<SignalSamplePoint> points : series) {
            if (points != null && !points.isEmpty()) {
                nonEmpty++;
                estimatedSamples += points.size();
            }
        }
        if (nonEmpty == 0) {
            return new long[0];
        }
        if (nonEmpty == 1) {
            for (List<SignalSamplePoint> points : series) {
                if (points != null && !points.isEmpty()) {
                    return uniqueTimestamps(points);
                }
            }
        }

        // The heap already emits timestamps in sorted order. Appending directly to a primitive
        // buffer makes a TreeSet<Long> redundant and avoids one boxed Long/tree node per tick.
        PriorityQueue<MergeCursor> heap = new PriorityQueue<>(
                Comparator.comparingLong((MergeCursor c) -> c.timestamp)
                        .thenComparingInt(c -> c.seriesIndex)
        );
        for (int s = 0; s < series.size(); s++) {
            List<SignalSamplePoint> points = series.get(s);
            if (points == null || points.isEmpty()) {
                continue;
            }
            heap.add(new MergeCursor(s, 0, points.get(0).timestampNanos()));
        }

        LongArrayBuilder unique = new LongArrayBuilder(estimatedSamples);
        while (!heap.isEmpty()) {
            MergeCursor head = heap.poll();
            unique.addIfDifferent(head.timestamp);
            List<SignalSamplePoint> points = series.get(head.seriesIndex);
            if (head.advancePastTimestamp(points)) {
                // Reuse the cursor object rather than allocating one object per emitted sample.
                heap.add(head);
            }
        }
        return unique.toArray();
    }

    private static long[] uniqueTimestamps(List<SignalSamplePoint> points) {
        LongArrayBuilder unique = new LongArrayBuilder(points.size());
        for (SignalSamplePoint p : points) {
            unique.addIfDifferent(p.timestampNanos());
        }
        return unique.toArray();
    }

    private static int firstSampleAtOrAfter(List<SignalSamplePoint> points, long timestampNanos) {
        int low = 0;
        int high = points.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (points.get(mid).timestampNanos() < timestampNanos) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /**
     * Advances a monotonic cursor to the last sample at {@code ts} (last-wins duplicates).
     * Returns null when the series has no sample at that exact timestamp.
     */
    private static SignalSamplePoint advanceTo(
            List<SignalSamplePoint> points,
            int[] cursors,
            int seriesIndex,
            long ts
    ) {
        int i = cursors[seriesIndex];
        int size = points.size();
        while (i < size && points.get(i).timestampNanos() < ts) {
            i++;
        }
        if (i >= size || points.get(i).timestampNanos() != ts) {
            cursors[seriesIndex] = i;
            return null;
        }
        int last = i;
        while (last + 1 < size && points.get(last + 1).timestampNanos() == ts) {
            last++;
        }
        cursors[seriesIndex] = last + 1;
        return points.get(last);
    }

    private static final class MergeCursor {
        private final int seriesIndex;
        private int pointIndex;
        private long timestamp;

        private MergeCursor(int seriesIndex, int pointIndex, long timestamp) {
            this.seriesIndex = seriesIndex;
            this.pointIndex = pointIndex;
            this.timestamp = timestamp;
        }

        private boolean advancePastTimestamp(List<SignalSamplePoint> points) {
            int next = pointIndex + 1;
            while (next < points.size() && points.get(next).timestampNanos() == timestamp) {
                next++;
            }
            if (next >= points.size()) {
                return false;
            }
            pointIndex = next;
            timestamp = points.get(next).timestampNanos();
            return true;
        }
    }

    private static final class LongArrayBuilder {
        private static final int MAX_INITIAL_CAPACITY = 1_000_000;
        private long[] values;
        private int size;

        private LongArrayBuilder(long estimatedSize) {
            int initial = (int) Math.max(16L, Math.min(estimatedSize, MAX_INITIAL_CAPACITY));
            this.values = new long[initial];
        }

        private void addIfDifferent(long value) {
            if (size > 0 && values[size - 1] == value) {
                return;
            }
            ensureCapacity(size + 1);
            values[size++] = value;
        }

        private void ensureCapacity(int required) {
            if (required <= values.length) {
                return;
            }
            int current = values.length;
            int grown = current + Math.max(16, current >>> 1);
            if (grown < required) {
                grown = required;
            }
            if (grown < 0) {
                throw new IllegalStateException("Aligned timestamp union exceeds JVM array capacity");
            }
            values = Arrays.copyOf(values, grown);
        }

        private long[] toArray() {
            return size == 0 ? new long[0] : Arrays.copyOf(values, size);
        }
    }
}
