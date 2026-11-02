package com.stellar.platform.analysis.advanced;

import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.analysis.source.SampleStreamer;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.parser.spi.CancellationToken;

import java.util.ArrayList;
import java.util.List;

/** Collects a bounded window of numeric samples. Never materializes a full 10 GB series. */
public final class BoundedSampleBuffer {

    public record Series(
            long[] timestamps,
            double[] values,
            int nanCount,
            int textCount,
            int excludedCount,
            int[] segmentEnds
    ) {
        public Series(long[] timestamps, double[] values, int nanCount, int textCount, int excludedCount) {
            this(timestamps, values, nanCount, textCount, excludedCount, oneSegment(timestamps));
        }

        public Series(long[] timestamps, double[] values, int nanCount, int textCount) {
            this(timestamps, values, nanCount, textCount, 0);
        }

        public int size() {
            return timestamps.length;
        }

        /** Eligible runs. An ineligible stored sample, or a gap above the explicit limit, starts a new run. */
        public int segmentCount() {
            return segmentEnds == null ? 0 : segmentEnds.length;
        }

        /**
         * Splits eligible runs where a successive pair is farther apart than {@code maxGapNanos}.
         * A non-positive limit does not invent a gap size and leaves the runs unchanged.
         */
        public Series limitingGaps(long maxGapNanos) {
            if (maxGapNanos <= 0 || timestamps.length < 2 || segmentEnds == null || segmentEnds.length == 0) {
                return this;
            }
            List<Integer> ends = new ArrayList<>();
            int previous = 0;
            for (int end : segmentEnds) {
                int run = previous;
                for (int i = previous + 1; i < end; i++) {
                    if (timestamps[i] - timestamps[i - 1] > maxGapNanos) {
                        if (i > run) {
                            ends.add(i);
                        }
                        run = i;
                    }
                }
                if (end > run) {
                    ends.add(end);
                }
                previous = end;
            }
            return new Series(timestamps, values, nanCount, textCount, excludedCount,
                    ends.stream().mapToInt(Integer::intValue).toArray());
        }

        private static int[] oneSegment(long[] timestamps) {
            return timestamps == null || timestamps.length == 0 ? new int[0] : new int[]{timestamps.length};
        }
    }

    private BoundedSampleBuffer() {
    }

    public static Series collect(
            AnalysisSampleSource source,
            SignalCatalogKey key,
            TimeWindow window,
            int maxPoints,
            CancellationToken token
    ) {
        if (window == null || window.isUnbounded()) {
            throw new IllegalArgumentException(
                    "Frequency/advanced analytics require a finite time window. "
                            + "Zoom the graph or use Cursor A→B.");
        }
        if (maxPoints <= 0 || maxPoints > AnalyticsLimits.MAX_ANALYSIS_POINTS) {
            throw new IllegalArgumentException(
                    "Requested point budget " + maxPoints + " exceeds safe limit "
                            + AnalyticsLimits.MAX_ANALYSIS_POINTS
                            + ". Use a smaller window or lower target sample rate.");
        }
        List<Long> ts = new ArrayList<>();
        List<Double> vs = new ArrayList<>();
        int[] nan = {0};
        int[] text = {0};
        int[] excluded = {0};
        List<Integer> segmentEnds = new ArrayList<>();
        int[] run = {0};
        long visited = SampleStreamer.forEach(source, key, window, token, sample -> {
            if (ts.size() >= maxPoints) {
                return false;
            }
            SampleStreamer.throwIfCancelled(token);
            if (!sample.usableMeasurement()) {
                if (run[0] > 0) {
                    segmentEnds.add(ts.size());
                    run[0] = 0;
                }
                if (com.stellar.platform.domain.CategoricalValue.isCategorical(
                        sample.validity(), sample.physicalValue(), sample.textualValue())) {
                    text[0]++;
                } else if (!Double.isFinite(sample.physicalValue())) {
                    nan[0]++;
                } else {
                    excluded[0]++;
                }
                return true;
            }
            ts.add(sample.timestampNanos());
            vs.add(sample.physicalValue());
            run[0]++;
            return true;
        });
        if (run[0] > 0) {
            segmentEnds.add(ts.size());
        }
        if (visited > maxPoints && ts.size() >= maxPoints) {
            throw new IllegalArgumentException(
                    "Window contains more than " + maxPoints + " samples. "
                            + "Narrow the time range or resample explicitly at a lower rate. "
                            + "No silent downsampling is applied.");
        }
        long[] t = new long[ts.size()];
        double[] v = new double[vs.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = ts.get(i);
            v[i] = vs.get(i);
        }
        int[] ends = segmentEnds.stream().mapToInt(Integer::intValue).toArray();
        return new Series(t, v, nan[0], text[0], excluded[0], ends);
    }

    public static List<SignalSamplePoint> toPoints(Series series) {
        List<SignalSamplePoint> out = new ArrayList<>(series.size());
        for (int i = 0; i < series.size(); i++) {
            out.add(new SignalSamplePoint(series.timestamps[i], series.values[i], null, null));
        }
        return List.copyOf(out);
    }
}
