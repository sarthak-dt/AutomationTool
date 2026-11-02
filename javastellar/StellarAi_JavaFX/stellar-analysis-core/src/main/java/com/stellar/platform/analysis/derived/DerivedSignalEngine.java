package com.stellar.platform.analysis.derived;

import com.stellar.platform.analysis.align.SampleAligner;
import com.stellar.platform.analysis.model.AlignmentPolicy;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.analysis.source.SampleStreamer;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.parser.spi.CancellationToken;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates a typed expression into a bounded derived series. Source samples are never written.
 */
public final class DerivedSignalEngine {

    public record Series(SignalCatalogKey identity, List<SignalSamplePoint> samples) {
        public Series {
            samples = List.copyOf(samples == null ? List.of() : samples);
        }
    }

    private DerivedSignalEngine() {
    }

    public static Series evaluate(
            AnalysisSampleSource source,
            DerivedExpression expression,
            TimeWindow window,
            AlignmentPolicy policy,
            long toleranceNanos,
            int maxSamples,
            CancellationToken token
    ) {
        String fp = DerivedSignalIdentity.fingerprint(expression, policy, null);
        SignalCatalogKey id = DerivedSignalIdentity.catalogKey(expression, fp);
        int cap = Math.max(8, maxSamples);
        List<SignalSamplePoint> out = new ArrayList<>();
        switch (expression) {
            case DerivedExpression.Abs e -> unary(source, e.a(), window, token, cap, out,
                    (ts, v, prevTs, prevV) -> Math.abs(v));
            case DerivedExpression.Delta e -> unary(source, e.a(), window, token, cap, out,
                    (ts, v, prevTs, prevV) -> Double.isFinite(prevV) ? v - prevV : Double.NaN);
            case DerivedExpression.Rate e -> unary(source, e.a(), window, token, cap, out,
                    (ts, v, prevTs, prevV) -> {
                        if (!Double.isFinite(prevV) || ts <= prevTs) {
                            return Double.NaN;
                        }
                        return (v - prevV) / ((ts - prevTs) / 1_000_000_000.0d);
                    });
            case DerivedExpression.Scale e -> unary(source, e.a(), window, token, cap, out,
                    (ts, v, prevTs, prevV) -> e.divide()
                            ? (e.constant() == 0d ? Double.NaN : v / e.constant())
                            : v * e.constant());
            case DerivedExpression.Add e -> binary(source, e.a(), e.b(), window, policy, toleranceNanos, token, cap, out,
                    Double::sum);
            case DerivedExpression.Subtract e -> binary(source, e.a(), e.b(), window, policy, toleranceNanos, token, cap, out,
                    (x, y) -> x - y);
            case DerivedExpression.Min e -> binary(source, e.a(), e.b(), window, policy, toleranceNanos, token, cap, out,
                    Math::min);
            case DerivedExpression.Max e -> binary(source, e.a(), e.b(), window, policy, toleranceNanos, token, cap, out,
                    Math::max);
        }
        return new Series(id, out);
    }

    @FunctionalInterface
    private interface UnaryOp {
        double apply(long ts, double value, long prevTs, double prevValue);
    }

    @FunctionalInterface
    private interface BinaryOp {
        double apply(double a, double b);
    }

    private static void unary(
            AnalysisSampleSource source,
            SignalCatalogKey key,
            TimeWindow window,
            CancellationToken token,
            int cap,
            List<SignalSamplePoint> out,
            UnaryOp op
    ) {
        final long[] prevTs = {Long.MIN_VALUE};
        final double[] prevV = {Double.NaN};
        SampleStreamer.forEach(source, key, window, token, sample -> {
            if (!sample.usableMeasurement()) {
                prevTs[0] = Long.MIN_VALUE;
                prevV[0] = Double.NaN;
                return out.size() < cap;
            }
            double v = op.apply(sample.timestampNanos(), sample.physicalValue(), prevTs[0], prevV[0]);
            if (Double.isFinite(v)) {
                out.add(new SignalSamplePoint(sample.timestampNanos(), v, null, null));
            }
            prevTs[0] = sample.timestampNanos();
            prevV[0] = sample.physicalValue();
            return out.size() < cap;
        });
    }

    private static void binary(
            AnalysisSampleSource source,
            SignalCatalogKey a,
            SignalCatalogKey b,
            TimeWindow window,
            AlignmentPolicy policy,
            long tolerance,
            CancellationToken token,
            int cap,
            List<SignalSamplePoint> out,
            BinaryOp op
    ) {
        List<SampleAligner.Pair> pairs = SampleAligner.align(
                source, a, b, window, policy, tolerance, cap, token);
        for (SampleAligner.Pair p : pairs) {
            double v = op.apply(p.a(), p.b());
            if (Double.isFinite(v)) {
                out.add(new SignalSamplePoint(p.timestampNanos(), v, null, null));
            }
        }
    }
}
