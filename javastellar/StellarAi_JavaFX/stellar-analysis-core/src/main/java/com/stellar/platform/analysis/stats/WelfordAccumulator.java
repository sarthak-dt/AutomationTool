package com.stellar.platform.analysis.stats;

/**
 * Numerically stable streaming mean / variance (Welford) plus RMS.
 * Holds O(1) state. Does not retain samples.
 */
public final class WelfordAccumulator {

    private long count;
    private long valid;
    private long missing;
    private double mean;
    private double m2;
    private double sumSq;
    private double min = Double.POSITIVE_INFINITY;
    private double max = Double.NEGATIVE_INFINITY;
    private double first = Double.NaN;
    private double last = Double.NaN;
    private long firstTs;
    private long lastTs;
    private boolean started;
    private double prevValue = Double.NaN;
    private long prevTs;
    private double absRateSum;
    private long ratePairs;

    public void accept(long timestampNanos, double value) {
        count++;
        if (!Double.isFinite(value)) {
            missing++;
            return;
        }
        valid++;
        if (!started) {
            first = value;
            firstTs = timestampNanos;
            started = true;
        }
        last = value;
        lastTs = timestampNanos;
        if (value < min) {
            min = value;
        }
        if (value > max) {
            max = value;
        }
        double delta = value - mean;
        mean += delta / valid;
        m2 += delta * (value - mean);
        sumSq += value * value;
        if (ratePairs >= 0 && prevTs != 0L && timestampNanos > prevTs && Double.isFinite(prevValue)) {
            double dt = (timestampNanos - prevTs) / 1_000_000_000.0d;
            if (dt > 0d) {
                absRateSum += Math.abs(value - prevValue) / dt;
                ratePairs++;
            }
        } else if (!Double.isFinite(prevValue)) {
            ratePairs = 0;
        }
        prevValue = value;
        prevTs = timestampNanos;
    }

    public long count() { return count; }
    public long valid() { return valid; }
    public long missing() { return missing; }
    public double mean() { return valid == 0 ? Double.NaN : mean; }
    public double variance() { return valid < 2 ? Double.NaN : m2 / (valid - 1); }
    public double standardDeviation() {
        double v = variance();
        return Double.isFinite(v) ? Math.sqrt(v) : Double.NaN;
    }
    public double rms() { return valid == 0 ? Double.NaN : Math.sqrt(sumSq / valid); }
    public double min() { return valid == 0 ? Double.NaN : min; }
    public double max() { return valid == 0 ? Double.NaN : max; }
    public double range() { return valid == 0 ? Double.NaN : max - min; }
    public double first() { return first; }
    public double last() { return last; }
    public double delta() { return valid == 0 ? Double.NaN : last - first; }
    public long firstTimestampNanos() { return firstTs; }
    public long lastTimestampNanos() { return lastTs; }
    public double meanAbsRatePerSecond() { return ratePairs == 0 ? Double.NaN : absRateSum / ratePairs; }
}
