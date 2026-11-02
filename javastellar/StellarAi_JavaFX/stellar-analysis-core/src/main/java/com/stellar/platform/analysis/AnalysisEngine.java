package com.stellar.platform.analysis;

import com.stellar.platform.analysis.band.OperatingBandAnalyzer;
import com.stellar.platform.analysis.compare.ComparisonAnalyzer;
import com.stellar.platform.analysis.correlation.PearsonCorrelationAnalyzer;
import com.stellar.platform.analysis.delta.CursorDeltaAnalyzer;
import com.stellar.platform.analysis.derived.DerivedExpression;
import com.stellar.platform.analysis.derived.DerivedSignalEngine;
import com.stellar.platform.analysis.derived.DerivedSignalIdentity;
import com.stellar.platform.analysis.duration.DurationAnalyzer;
import com.stellar.platform.analysis.lag.BoundedLagAnalyzer;
import com.stellar.platform.analysis.model.AnalysisRequest;
import com.stellar.platform.analysis.model.AnalysisResult;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.EngineeringFinding;
import com.stellar.platform.analysis.model.FindingType;
import com.stellar.platform.analysis.model.SignalStatistics;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.rate.RateOfChangeAnalyzer;
import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.analysis.state.StateTransitionAnalyzer;
import com.stellar.platform.analysis.stats.StreamingStatisticsAnalyzer;
import com.stellar.platform.analysis.threshold.ThresholdAnalyzer;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.parser.spi.CancellationToken;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Local automotive analysis facade. Streaming, cancellable, no JavaFX, no parser types.
 */
public final class AnalysisEngine {

    public AnalysisResult execute(AnalysisRequest request, AnalysisSampleSource source, CancellationToken token) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(source, "source");
        Instant started = Instant.now();
        AnalysisResult.Builder out = AnalysisResult.builder(request.analysisId())
                .startedAt(started)
                .signalKeys(request.signalKeys())
                .requestedRange(request.resolveWindow())
                .alignmentPolicy(request.alignmentPolicy())
                .analysisVersion(request.analysisVersion())
                .status(AnalysisStatus.RUNNING);
        try {
            TimeWindow window = request.resolveWindow();
            switch (request.analysisType()) {
                case STATISTICS -> statistics(request, source, window, token, out);
                case CURSOR_DELTA -> cursorDelta(request, source, out);
                case RATE_OF_CHANGE -> rate(request, source, window, token, out);
                case THRESHOLD -> threshold(request, source, window, token, out);
                case STATE_TRANSITION -> transitions(request, source, window, token, out);
                case DURATION -> duration(request, source, window, token, out);
                case OPERATING_BAND -> band(request, source, window, token, out);
                case COMPARISON -> comparison(request, source, window, token, out);
                case DERIVED -> derived(request, source, window, token, out);
                case CORRELATION -> correlation(request, source, window, token, out);
                case LAG -> lag(request, source, window, token, out);
            }
            out.status(outHasWarnings(out) ? AnalysisStatus.COMPLETED_WITH_WARNINGS : AnalysisStatus.COMPLETED);
            out.message(request.analysisType().name());
        } catch (AnalysisCancelledException cancelled) {
            return out.status(AnalysisStatus.CANCELLED).completedAt(Instant.now())
                    .message("Cancelled").build();
        } catch (RuntimeException ex) {
            return out.status(AnalysisStatus.FAILED).completedAt(Instant.now())
                    .message(ex.getMessage() == null ? ex.toString() : ex.getMessage())
                    .addDiagnostic("INTERNAL", ex.getMessage() == null ? ex.toString() : ex.getMessage())
                    .build();
        }
        return out.completedAt(Instant.now()).build();
    }

    private static boolean outHasWarnings(AnalysisResult.Builder ignored) {
        return false;
    }

    private static SignalCatalogKey first(AnalysisRequest request) {
        if (request.signalKeys().isEmpty()) {
            throw new IllegalArgumentException("At least one signal is required");
        }
        return request.signalKeys().getFirst();
    }

    private static SignalCatalogKey second(AnalysisRequest request) {
        if (request.signalKeys().size() < 2) {
            throw new IllegalArgumentException("Two signals are required");
        }
        return request.signalKeys().get(1);
    }

    private static void statistics(
            AnalysisRequest request, AnalysisSampleSource source, TimeWindow window,
            CancellationToken token, AnalysisResult.Builder out
    ) {
        long t0 = Long.MAX_VALUE;
        long t1 = Long.MIN_VALUE;
        for (SignalCatalogKey key : request.signalKeys()) {
            SignalStatistics stats = StreamingStatisticsAnalyzer.compute(source, key, window, token);
            out.addStatistics(stats);
            out.metric(key.signalName() + ".mean", stats.mean());
            out.metric(key.signalName() + ".min", stats.min());
            out.metric(key.signalName() + ".max", stats.max());
            out.metric(key.signalName() + ".delta", stats.delta());
            if (stats.validCount() > 0) {
                t0 = Math.min(t0, stats.firstTimestampNanos());
                t1 = Math.max(t1, stats.lastTimestampNanos());
            }
        }
        if (t0 <= t1) {
            out.effectiveRange(TimeWindow.of(t0, t1));
        }
    }

    private static void cursorDelta(AnalysisRequest request, AnalysisSampleSource source, AnalysisResult.Builder out) {
        if (request.cursorANanos() == null || request.cursorBNanos() == null) {
            throw new IllegalArgumentException("Cursor A and Cursor B are required");
        }
        for (SignalCatalogKey key : request.signalKeys()) {
            EngineeringFinding finding = CursorDeltaAnalyzer.delta(
                    source, key, request.cursorANanos(), request.cursorBNanos(),
                    request.analysisId(), request.parameters().findingSeverity());
            out.addFinding(finding);
            finding.numericMetrics().forEach((k, v) -> out.metric(key.signalName() + "." + k, v));
        }
        out.effectiveRange(TimeWindow.of(request.cursorANanos(), request.cursorBNanos()));
    }

    private static void rate(
            AnalysisRequest request, AnalysisSampleSource source, TimeWindow window,
            CancellationToken token, AnalysisResult.Builder out
    ) {
        RateOfChangeAnalyzer.Summary summary = RateOfChangeAnalyzer.compute(source, first(request), window, token);
        out.metric("ratePairs", summary.pairs());
        out.metric("minRate", summary.minRate());
        out.metric("maxRate", summary.maxRate());
        out.metric("meanAbsRate", summary.meanAbsRate());
        out.metric("skippedNonPositiveDt", summary.skippedNonPositiveDt());
        out.addFinding(new EngineeringFinding(
                null, request.analysisId(), FindingType.STATISTIC, request.parameters().findingSeverity(),
                request.signalKeys(), summary.minRateAt(), summary.maxRateAt(),
                String.format("Rate min=%.6g max=%.6g mean|rate|=%.6g",
                        summary.minRate(), summary.maxRate(), summary.meanAbsRate()),
                "Zero/duplicate Δt skipped=" + summary.skippedNonPositiveDt(),
                java.util.Map.of("minRate", summary.minRate(), "maxRate", summary.maxRate()),
                null));
    }

    private static void threshold(
            AnalysisRequest request, AnalysisSampleSource source, TimeWindow window,
            CancellationToken token, AnalysisResult.Builder out
    ) {
        List<EngineeringFinding> events = ThresholdAnalyzer.detect(
                source, first(request), window, request.parameters(), request.analysisId(), token);
        events.forEach(out::addFinding);
        out.metric("eventCount", events.size());
    }

    private static void transitions(
            AnalysisRequest request, AnalysisSampleSource source, TimeWindow window,
            CancellationToken token, AnalysisResult.Builder out
    ) {
        List<EngineeringFinding> events = StateTransitionAnalyzer.detect(
                source, first(request), window, request.analysisId(),
                request.parameters().findingSeverity(), token);
        events.forEach(out::addFinding);
        out.metric("transitionCount", events.size());
    }

    private static void duration(
            AnalysisRequest request, AnalysisSampleSource source, TimeWindow window,
            CancellationToken token, AnalysisResult.Builder out
    ) {
        DurationAnalyzer.Summary summary = DurationAnalyzer.compute(
                source, first(request), window, request.parameters(), token);
        out.addFinding(DurationAnalyzer.toFinding(
                summary, first(request), request.analysisId(), request.parameters().findingSeverity()));
        out.metric("totalActiveSeconds", summary.totalActiveSeconds());
        out.metric("longestSeconds", summary.longestSeconds());
        out.metric("occurrences", summary.occurrences());
    }

    private static void band(
            AnalysisRequest request, AnalysisSampleSource source, TimeWindow window,
            CancellationToken token, AnalysisResult.Builder out
    ) {
        Double lo = request.parameters().bandMin();
        Double hi = request.parameters().bandMax();
        if (lo == null || hi == null) {
            throw new IllegalArgumentException("Operating band requires bandMin and bandMax");
        }
        OperatingBandAnalyzer.Summary summary = OperatingBandAnalyzer.compute(
                source, first(request), window, lo, hi, token);
        out.metric("timeInsideSeconds", summary.timeInsideSeconds());
        out.metric("percentInside", summary.percentInside());
        out.metric("entryCount", summary.entryCount());
        out.metric("exitCount", summary.exitCount());
        out.metric("minInside", summary.minInside());
        out.metric("maxInside", summary.maxInside());
        out.addFinding(new EngineeringFinding(
                null, request.analysisId(), FindingType.OPERATING_BAND, request.parameters().findingSeverity(),
                request.signalKeys(), window.startNanos(), window.endNanos(),
                String.format("Inside band %.3f s (%.1f%%)", summary.timeInsideSeconds(), summary.percentInside()),
                "",
                java.util.Map.of(
                        "timeInsideSeconds", summary.timeInsideSeconds(),
                        "percentInside", summary.percentInside()),
                null));
    }

    private static void comparison(
            AnalysisRequest request, AnalysisSampleSource source, TimeWindow window,
            CancellationToken token, AnalysisResult.Builder out
    ) {
        ComparisonAnalyzer.Summary summary = ComparisonAnalyzer.compare(
                source, first(request), second(request), window,
                request.alignmentPolicy(), request.parameters().nearestToleranceNanos(), token);
        out.metric("pairCount", summary.pairCount());
        out.metric("mae", summary.meanAbsoluteError());
        out.metric("maxAbsError", summary.maxAbsoluteError());
        out.metric("rmse", summary.rmse());
        out.metric("meanDifference", summary.meanDifference());
        out.addFinding(new EngineeringFinding(
                null, request.analysisId(), FindingType.COMPARISON, request.parameters().findingSeverity(),
                request.signalKeys(), window.startNanos(), window.endNanos(),
                String.format("MAE=%.6g RMSE=%.6g max|err|=%.6g",
                        summary.meanAbsoluteError(), summary.rmse(), summary.maxAbsoluteError()),
                "alignment=" + request.alignmentPolicy(),
                java.util.Map.of("mae", summary.meanAbsoluteError(), "rmse", summary.rmse()),
                null));
    }

    private static void derived(
            AnalysisRequest request, AnalysisSampleSource source, TimeWindow window,
            CancellationToken token, AnalysisResult.Builder out
    ) {
        SignalCatalogKey a = first(request);
        SignalCatalogKey b = request.signalKeys().size() > 1 ? request.signalKeys().get(1) : a;
        DerivedExpression expr = DerivedExpression.parse(
                request.parameters().derivedOp(), a, b, request.parameters().scaleConstant());
        DerivedSignalEngine.Series series = DerivedSignalEngine.evaluate(
                source, expr, window, request.alignmentPolicy(),
                request.parameters().nearestToleranceNanos(), 250_000, token);
        String identity = DerivedSignalIdentity.fingerprint(expr, request.alignmentPolicy(), request.analysisVersion());
        out.derivedIdentity(identity);
        out.metric("derivedSamples", series.samples().size());
        if (!series.samples().isEmpty()) {
            SignalSamplePoint firstS = series.samples().getFirst();
            SignalSamplePoint lastS = series.samples().getLast();
            out.effectiveRange(TimeWindow.of(firstS.timestampNanos(), lastS.timestampNanos()));
            out.metric("derivedFirst", firstS.physicalValue());
            out.metric("derivedLast", lastS.physicalValue());
        }
        out.addFinding(new EngineeringFinding(
                null, request.analysisId(), FindingType.DERIVED, request.parameters().findingSeverity(),
                series.identity() == null ? request.signalKeys() : List.of(series.identity()),
                window.startNanos(), window.endNanos(),
                "Derived " + expr.canonical(),
                "identity=" + identity,
                java.util.Map.of("derivedSamples", (double) series.samples().size()),
                null));
    }

    private static void correlation(
            AnalysisRequest request, AnalysisSampleSource source, TimeWindow window,
            CancellationToken token, AnalysisResult.Builder out
    ) {
        PearsonCorrelationAnalyzer.Summary summary = PearsonCorrelationAnalyzer.correlate(
                source, first(request), second(request), window,
                request.alignmentPolicy(), request.parameters().nearestToleranceNanos(), token);
        out.metric("pearson", summary.coefficient());
        out.metric("pairCount", summary.pairCount());
        out.effectiveRange(summary.effectiveRange());
        out.addFinding(new EngineeringFinding(
                null, request.analysisId(), FindingType.CORRELATION, request.parameters().findingSeverity(),
                request.signalKeys(), summary.effectiveRange().startNanos(), summary.effectiveRange().endNanos(),
                String.format("Pearson r=%+.4f (n=%d) — correlation is not causation",
                        summary.coefficient(), summary.pairCount()),
                "alignment=" + request.alignmentPolicy(),
                java.util.Map.of("pearson", summary.coefficient()),
                null));
    }

    private static void lag(
            AnalysisRequest request, AnalysisSampleSource source, TimeWindow window,
            CancellationToken token, AnalysisResult.Builder out
    ) {
        BoundedLagAnalyzer.Summary summary = BoundedLagAnalyzer.estimate(
                source, first(request), second(request), window,
                request.alignmentPolicy(), request.parameters().nearestToleranceNanos(),
                request.parameters().maxLagNanos(), token);
        out.metric("bestLagNanos", summary.bestLagNanos());
        out.metric("bestLagSeconds", summary.bestLagNanos() / 1_000_000_000.0d);
        out.metric("bestCorrelation", summary.bestCorrelation());
        out.metric("pairCount", summary.pairCount());
        out.addFinding(new EngineeringFinding(
                null, request.analysisId(), FindingType.LAG, request.parameters().findingSeverity(),
                request.signalKeys(), window.startNanos(), window.endNanos(),
                String.format("Best lag %.3f s (r=%+.3f) — observational, not causality",
                        summary.bestLagNanos() / 1e9, summary.bestCorrelation()),
                "bounded search maxLag=" + summary.maxLagNanos() + " ns",
                java.util.Map.of("bestLagSeconds", summary.bestLagNanos() / 1e9),
                null));
    }
}
