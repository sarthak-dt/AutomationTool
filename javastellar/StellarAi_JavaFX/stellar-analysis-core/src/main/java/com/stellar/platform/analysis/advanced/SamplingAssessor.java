package com.stellar.platform.analysis.advanced;

import java.util.Arrays;

public final class SamplingAssessor {

    private static final double UNIFORM_JITTER_FRACTION = 0.05d;
    private static final double UNIFORM_SPAN_RATIO = 1.25d;

    private SamplingAssessor() {
    }

    public static SamplingQuality assess(long[] timestamps) {
        if (timestamps == null || timestamps.length == 0) {
            return SamplingQuality.empty("No samples in the selected window.");
        }
        if (timestamps.length == 1) {
            return SamplingQuality.empty("Insufficient samples (1). Frequency analysis requires a series.");
        }
        boolean ordered = true;
        boolean valid = true;
        int nInt = Math.min(timestamps.length - 1, AnalyticsLimits.MAX_INTERVAL_SAMPLES);
        long[] intervals = new long[nInt];
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        int written = 0;
        for (int i = 1; i < timestamps.length && written < nInt; i++) {
            long dt = timestamps[i] - timestamps[i - 1];
            if (dt < 0) {
                ordered = false;
            }
            if (dt <= 0) {
                valid = false;
                continue;
            }
            intervals[written++] = dt;
            min = Math.min(min, dt);
            max = Math.max(max, dt);
        }
        if (written == 0) {
            return new SamplingQuality(timestamps.length, timestamps[timestamps.length - 1] - timestamps[0],
                    0, 0, 0, 0, 0, ordered, false, false,
                    "No positive sample intervals. Duplicate or reversed timestamps.");
        }
        Arrays.sort(intervals, 0, written);
        long median = intervals[written / 2];
        long jitter = Math.max(Math.abs(max - median), Math.abs(median - min));
        double rate = median <= 0 ? 0d : 1e9 / median;
        long span = timestamps[timestamps.length - 1] - timestamps[0];
        boolean uniform = ordered && valid && median > 0
                && jitter <= median * UNIFORM_JITTER_FRACTION
                && (double) max / (double) min <= UNIFORM_SPAN_RATIO;
        String assessment;
        if (!ordered) {
            assessment = "Timestamps are not strictly increasing. Frequency analysis is rejected.";
        } else if (!valid) {
            assessment = "Duplicate or non-positive intervals present. Frequency analysis requires resampling or a different window.";
        } else if (!uniform) {
            assessment = "Sampling is irregular. Do not FFT this series as if it were uniform. "
                    + "Reject frequency analysis or resample explicitly.";
        } else {
            assessment = "Sampling is sufficiently uniform for frequency-domain analysis.";
        }
        return new SamplingQuality(timestamps.length, span, median, min, max, rate, jitter,
                ordered, valid, uniform, assessment);
    }
}
