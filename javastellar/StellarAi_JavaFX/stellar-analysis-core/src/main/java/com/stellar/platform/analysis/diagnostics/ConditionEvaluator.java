package com.stellar.platform.analysis.diagnostics;

import com.stellar.platform.analysis.align.SampleAligner;
import com.stellar.platform.analysis.correlation.PearsonCorrelationAnalyzer;
import com.stellar.platform.analysis.model.AlignmentPolicy;
import com.stellar.platform.analysis.model.AnalysisParameters;
import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.model.EngineeringFinding;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.rate.RateOfChangeAnalyzer;
import com.stellar.platform.analysis.rules.CompareOp;
import com.stellar.platform.analysis.rules.DurationOp;
import com.stellar.platform.analysis.rules.LogicalOp;
import com.stellar.platform.analysis.rules.RuleCondition;
import com.stellar.platform.analysis.rules.SignalBinding;
import com.stellar.platform.analysis.rules.TemporalConstraint;
import com.stellar.platform.analysis.rules.UnitRegistry;
import com.stellar.platform.analysis.source.AnalysisSampleSource;
import com.stellar.platform.analysis.source.SampleStreamer;
import com.stellar.platform.analysis.state.StateTransitionAnalyzer;
import com.stellar.platform.analysis.threshold.ThresholdAnalyzer;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.parser.spi.CancellationToken;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * Evaluates typed conditions using Phase 4 analyzers. No duplicated derivative/threshold math.
 */
final class ConditionEvaluator {

    record Event(
            long startNanos,
            long endNanos,
            double peak,
            String summary,
            List<FindingEvidence> evidence,
            List<ClauseTrace> trace
    ) {
    }

    record Eval(List<Event> events, boolean insufficient, String reason) {
        static Eval none() {
            return new Eval(List.of(), false, "");
        }

        static Eval data(String reason) {
            return new Eval(List.of(), true, reason);
        }
    }

    private final AnalysisSampleSource source;
    private final Map<String, SignalBinding> bindings;
    private final TimeWindow window;
    private final CancellationToken token;
    private int cacheHits;

    ConditionEvaluator(
            AnalysisSampleSource source,
            Map<String, SignalBinding> bindings,
            TimeWindow window,
            CancellationToken token
    ) {
        this.source = source;
        this.bindings = bindings;
        this.window = window;
        this.token = token;
    }

    int cacheHits() {
        return cacheHits;
    }

    Eval evaluate(RuleCondition condition) {
        return switch (condition) {
            case RuleCondition.Comparison c -> comparison(c);
            case RuleCondition.Range c -> range(c);
            case RuleCondition.Threshold c -> threshold(c);
            case RuleCondition.StateEquals c -> state(c);
            case RuleCondition.Transition c -> transition(c);
            case RuleCondition.Rate c -> rate(c);
            case RuleCondition.Delta c -> delta(c.leftRole(), c.rightRole(), c.op(), c.value(), c.unit(), c.temporal());
            case RuleCondition.CrossSignalDifference c ->
                    delta(c.leftRole(), c.rightRole(), c.op(), c.value(), c.unit(), c.temporal());
            case RuleCondition.AnalysisMetric c -> metric(c);
            case RuleCondition.Sequence c -> sequence(c);
            case RuleCondition.Logical c -> logical(c);
        };
    }

    private Eval comparison(RuleCondition.Comparison c) {
        SignalCatalogKey key = key(c.role());
        if (key == null) {
            return Eval.data("unbound " + c.role());
        }
        if (source.retainedSampleCount(key) == 0) {
            return Eval.data("insufficient data for " + c.role());
        }
        double threshold = convert(c.value(), c.unit(), unit(c.role()));
        AnalysisParameters params = AnalysisParameters.empty()
                .withThreshold(c.op().toThresholdOperator(), threshold);
        if (c.temporal().hysteresisExit() != null) {
            params = params.withHysteresisExit(convert(c.temporal().hysteresisExit(), c.unit(), unit(c.role())));
        }
        List<EngineeringFinding> raw = ThresholdAnalyzer.detect(source, key, window, params, "rule", token);
        cacheHits++;
        return eventsFromThreshold(raw, c.temporal(), c.role(),
                c.role() + " " + c.op().symbol() + " " + c.value() + " " + c.unit());
    }

    private Eval range(RuleCondition.Range c) {
        SignalCatalogKey key = key(c.role());
        if (key == null || source.retainedSampleCount(key) == 0) {
            return Eval.data("insufficient data for " + c.role());
        }
        double lo = convert(c.min(), c.unit(), unit(c.role()));
        double hi = convert(c.max(), c.unit(), unit(c.role()));
        AnalysisParameters params = new AnalysisParameters(
                c.inside()
                        ? com.stellar.platform.analysis.model.ThresholdOperator.INSIDE_RANGE
                        : com.stellar.platform.analysis.model.ThresholdOperator.OUTSIDE_RANGE,
                lo, hi, c.temporal().hysteresisExit(), null, null, null,
                1_000_000L, 500_000_000L, AnalysisSeverity.OBSERVATION, "", "");
        List<EngineeringFinding> raw = ThresholdAnalyzer.detect(source, key, window, params, "rule", token);
        cacheHits++;
        return eventsFromThreshold(raw, c.temporal(), c.role(),
                c.role() + (c.inside() ? " inside " : " outside ") + c.min() + "–" + c.max() + " " + c.unit());
    }

    private Eval threshold(RuleCondition.Threshold c) {
        return comparison(new RuleCondition.Comparison(
                c.role(),
                switch (c.operator()) {
                    case GREATER_THAN -> CompareOp.GT;
                    case GREATER_OR_EQUAL -> CompareOp.GTE;
                    case LESS_THAN -> CompareOp.LT;
                    case LESS_OR_EQUAL -> CompareOp.LTE;
                    case INSIDE_RANGE, OUTSIDE_RANGE -> CompareOp.GTE;
                },
                c.enter(),
                c.unit(),
                new TemporalConstraint(
                        c.temporal().durationOp(),
                        c.temporal().durationNanos(),
                        c.hysteresisExit() == null ? c.temporal().hysteresisExit() : c.hysteresisExit(),
                        c.temporal().debounceNanos(),
                        c.temporal().mergeGapNanos())
        ));
    }

    private Eval state(RuleCondition.StateEquals c) {
        SignalCatalogKey key = key(c.role());
        if (key == null || source.retainedSampleCount(key) == 0) {
            return Eval.data("insufficient data for " + c.role());
        }
        List<Event> events = new ArrayList<>();
        final boolean[] active = {false};
        final long[] start = {0};
        final long[] last = {0};
        SampleStreamer.forEach(source, key, window, token, sample -> {
            String state = StateTransitionAnalyzer.stateOf(
                    sample.textualValue(), sample.physicalValue(), sample.validity());
            boolean on = c.negate() != state.equalsIgnoreCase(c.state());
            if (on) {
                if (!active[0]) {
                    active[0] = true;
                    start[0] = sample.timestampNanos();
                }
                last[0] = sample.timestampNanos();
            } else if (active[0]) {
                events.add(stateEvent(c.role(), start[0], last[0], c.state()));
                active[0] = false;
            }
            return true;
        });
        if (active[0]) {
            events.add(stateEvent(c.role(), start[0], last[0], c.state()));
        }
        return new Eval(List.copyOf(events), false, "");
    }

    private Event stateEvent(String role, long start, long end, String state) {
        return new Event(start, end, Double.NaN, role + " == " + state,
                List.of(new FindingEvidence(role, name(role), "state=" + state, Map.of())),
                List.of(new ClauseTrace(role + " state", true, state)));
    }

    private Eval transition(RuleCondition.Transition c) {
        SignalCatalogKey key = key(c.role());
        if (key == null || source.retainedSampleCount(key) == 0) {
            return Eval.data("insufficient data for " + c.role());
        }
        List<EngineeringFinding> raw = StateTransitionAnalyzer.detect(
                source, key, window, "rule", AnalysisSeverity.OBSERVATION, token);
        cacheHits++;
        String want = (c.fromState() + " → " + c.toState()).toUpperCase();
        List<Event> events = new ArrayList<>();
        for (EngineeringFinding f : raw) {
            if (f.summary().equalsIgnoreCase(c.fromState() + " → " + c.toState())
                    || f.summary().toUpperCase().equals(want)) {
                events.add(new Event(f.startTimestampNanos(), f.effectiveEndNanos(), Double.NaN, f.summary(),
                        List.of(new FindingEvidence(c.role(), name(c.role()), f.summary(), Map.of())),
                        List.of(new ClauseTrace("transition", true, f.summary()))));
            }
        }
        return new Eval(List.copyOf(events), false, "");
    }

    private Eval rate(RuleCondition.Rate c) {
        SignalCatalogKey key = key(c.role());
        if (key == null || source.retainedSampleCount(key) < 2) {
            return Eval.data("insufficient data for " + c.role());
        }
        RateOfChangeAnalyzer.Summary summary = RateOfChangeAnalyzer.compute(source, key, window, token);
        cacheHits++;
        double observed = Math.max(Math.abs(summary.maxRate()), Math.abs(summary.minRate()));
        boolean hit = c.op().matches(summary.maxRate(), c.value())
                || c.op().matches(summary.minRate(), c.value());
        if (!hit) {
            return Eval.none();
        }
        long at = c.op().matches(summary.maxRate(), c.value()) ? summary.maxRateAt() : summary.minRateAt();
        return new Eval(List.of(new Event(at, at, observed,
                c.role() + " rate " + c.op().symbol() + " " + c.value(),
                List.of(new FindingEvidence(c.role(), name(c.role()),
                        "rate max=" + summary.maxRate() + " min=" + summary.minRate(),
                        Map.of("maxRate", summary.maxRate(), "minRate", summary.minRate()))),
                List.of(new ClauseTrace("rate", true, Double.toString(observed))))), false, "");
    }

    private Eval delta(
            String leftRole, String rightRole, CompareOp op, double value, String unit, TemporalConstraint temporal
    ) {
        SignalCatalogKey a = key(leftRole);
        SignalCatalogKey b = key(rightRole);
        if (a == null || b == null) {
            return Eval.data("unbound delta inputs");
        }
        if (source.retainedSampleCount(a) == 0 || source.retainedSampleCount(b) == 0) {
            return Eval.data("insufficient data for delta");
        }
        List<SampleAligner.Pair> pairs = SampleAligner.align(
                source, a, b, window, AlignmentPolicy.PREVIOUS_VALUE_HOLD, 1_000_000L, 250_000, token);
        cacheHits++;
        if (pairs.isEmpty()) {
            return Eval.data("no aligned pairs");
        }
        double converted = convert(value, unit, unit(leftRole));
        List<Event> events = new ArrayList<>();
        boolean active = false;
        long start = 0;
        long last = 0;
        double peak = 0;
        for (SampleAligner.Pair p : pairs) {
            double abs = Math.abs(p.a() - p.b());
            if (op.matches(abs, converted)) {
                if (!active) {
                    active = true;
                    start = p.timestampNanos();
                    peak = abs;
                }
                last = p.timestampNanos();
                peak = Math.max(peak, abs);
            } else if (active) {
                events.add(deltaEvent(leftRole, rightRole, start, last, peak, temporal, value, unit));
                active = false;
            }
        }
        if (active) {
            events.add(deltaEvent(leftRole, rightRole, start, last, peak, temporal, value, unit));
        }
        return new Eval(filterTemporal(events, temporal), false, "");
    }

    private Event deltaEvent(
            String left, String right, long start, long end, double peak, TemporalConstraint temporal,
            double limit, String unit
    ) {
        return new Event(start, end, peak, "abs(" + left + "-" + right + ") peak=" + peak,
                List.of(new FindingEvidence(left, name(left),
                        "abs error peak=" + peak + " limit=" + limit + " " + unit,
                        Map.of("peakAbsError", peak, "limit", limit))),
                List.of(new ClauseTrace("delta", true, Double.toString(peak))));
    }

    private Eval metric(RuleCondition.AnalysisMetric c) {
        if (c.roles().size() < 2) {
            return Eval.data("metric requires two roles");
        }
        SignalCatalogKey a = key(c.roles().getFirst());
        SignalCatalogKey b = key(c.roles().get(1));
        if (a == null || b == null) {
            return Eval.data("unbound metric roles");
        }
        PearsonCorrelationAnalyzer.Summary summary = PearsonCorrelationAnalyzer.correlate(
                source, a, b, window, AlignmentPolicy.PREVIOUS_VALUE_HOLD, 1_000_000L, token);
        cacheHits++;
        if (!Double.isFinite(summary.coefficient()) || summary.pairCount() < 3) {
            return Eval.data("insufficient aligned samples for correlation");
        }
        if (!c.op().matches(summary.coefficient(), c.value())) {
            return Eval.none();
        }
        return new Eval(List.of(new Event(
                summary.effectiveRange().startNanos(), summary.effectiveRange().endNanos(),
                summary.coefficient(), "Pearson r=" + summary.coefficient(),
                List.of(new FindingEvidence(c.roles().getFirst(), name(c.roles().getFirst()),
                        "Pearson r=" + summary.coefficient() + " n=" + summary.pairCount(),
                        Map.of("pearson", summary.coefficient(), "pairs", (double) summary.pairCount()))),
                List.of(new ClauseTrace("correlation", true, Double.toString(summary.coefficient()))))), false, "");
    }

    /**
     * Response-time sequence: a finding is emitted when a later step is late or missing.
     * An in-time sequence is a pass (no finding). Look-ahead is bounded.
     */
    private Eval sequence(RuleCondition.Sequence seq) {
        Eval first = evaluate(seq.steps().getFirst().condition());
        if (first.insufficient) {
            return first;
        }
        List<Event> out = new ArrayList<>();
        for (Event seed : first.events) {
            List<FindingEvidence> ev = new ArrayList<>(seed.evidence);
            List<ClauseTrace> tr = new ArrayList<>(seed.trace);
            long t = seed.startNanos;
            Event last = seed;
            boolean violation = false;
            for (int i = 1; i < seq.steps().size(); i++) {
                RuleCondition.SequenceStep step = seq.steps().get(i);
                Eval next = evaluate(step.condition());
                if (next.insufficient) {
                    return next;
                }
                long lookAhead = Math.min(30_000_000_000L, Math.max(step.withinNanos() * 10L, 5_000_000_000L));
                Event match = null;
                for (Event e : next.events) {
                    long dt = e.startNanos - t;
                    if (dt >= 0 && dt <= lookAhead) {
                        match = e;
                        break;
                    }
                }
                if (match == null) {
                    violation = true;
                    ev.add(new FindingEvidence("sequence", "",
                            "step " + i + " not observed within look-ahead " + lookAhead + " ns",
                            Map.of("allowedDelayNanos", (double) step.withinNanos())));
                    tr.add(new ClauseTrace("sequence step " + i, false,
                            "not observed; allowed=" + (step.withinNanos() / 1_000_000d) + " ms"));
                    last = new Event(seed.startNanos, seed.endNanos + lookAhead, Double.NaN,
                            seed.summary + " then missing step " + i, ev, tr);
                    break;
                }
                long delay = match.startNanos - t;
                ev.addAll(match.evidence);
                ev.add(new FindingEvidence("delay", "",
                        "observed delay=" + (delay / 1_000_000d) + " ms; allowed="
                                + (step.withinNanos() / 1_000_000d) + " ms",
                        Map.of("observedDelayMs", delay / 1_000_000d,
                                "allowedDelayMs", step.withinNanos() / 1_000_000d)));
                if (delay > step.withinNanos()) {
                    violation = true;
                    tr.add(new ClauseTrace("sequence step " + i, false,
                            "delay=" + (delay / 1_000_000d) + " ms exceeds "
                                    + (step.withinNanos() / 1_000_000d) + " ms"));
                    last = new Event(seed.startNanos, match.endNanos, match.peak,
                            seed.summary + " then late " + match.summary, ev, tr);
                    break;
                }
                tr.add(new ClauseTrace("sequence step " + i, true,
                        "delay=" + (delay / 1_000_000d) + " ms"));
                last = match;
                t = match.startNanos;
            }
            if (violation) {
                out.add(last);
            }
        }
        return new Eval(List.copyOf(out), false, "");
    }

    private Eval logical(RuleCondition.Logical logic) {
        if (logic.op() == LogicalOp.NOT) {
            Eval inner = evaluate(logic.children().getFirst());
            if (inner.insufficient) {
                return inner;
            }
            if (inner.events.isEmpty()) {
                return new Eval(List.of(new Event(window.startNanos(), window.endNanos(), Double.NaN, "NOT",
                        List.of(), List.of(new ClauseTrace("NOT", true, "inner produced no events")))), false, "");
            }
            return Eval.none();
        }
        List<Eval> children = new ArrayList<>();
        for (RuleCondition child : logic.children()) {
            Eval e = evaluate(child);
            if (e.insufficient) {
                return e;
            }
            children.add(e);
        }
        if (logic.op() == LogicalOp.OR) {
            List<Event> all = new ArrayList<>();
            children.forEach(c -> all.addAll(c.events));
            return new Eval(List.copyOf(all), false, "");
        }
        List<Event> acc = children.getFirst().events;
        for (int i = 1; i < children.size(); i++) {
            acc = intersect(acc, children.get(i).events);
        }
        return new Eval(List.copyOf(acc), false, "");
    }

    private static List<Event> intersect(List<Event> a, List<Event> b) {
        List<Event> out = new ArrayList<>();
        for (Event x : a) {
            for (Event y : b) {
                long s = Math.max(x.startNanos, y.startNanos);
                long e = Math.min(x.endNanos, y.endNanos);
                if (e >= s) {
                    List<FindingEvidence> ev = new ArrayList<>(x.evidence);
                    ev.addAll(y.evidence);
                    List<ClauseTrace> tr = new ArrayList<>(x.trace);
                    tr.addAll(y.trace);
                    out.add(new Event(s, e, Double.isFinite(x.peak) ? x.peak : y.peak,
                            x.summary + " AND " + y.summary, ev, tr));
                }
            }
        }
        return out;
    }

    private Eval eventsFromThreshold(
            List<EngineeringFinding> raw, TemporalConstraint temporal, String role, String clause
    ) {
        List<Event> events = new ArrayList<>();
        for (EngineeringFinding f : raw) {
            double peak = f.numericMetrics().getOrDefault("peak", Double.NaN);
            events.add(new Event(f.startTimestampNanos(), f.effectiveEndNanos(), peak, f.summary(),
                    List.of(new FindingEvidence(role, name(role),
                            clause + " peak=" + peak + " duration=" + f.durationSeconds() + " s",
                            Map.of("peak", peak, "durationSeconds", f.durationSeconds()))),
                    List.of(new ClauseTrace(clause, true, "duration=" + f.durationSeconds() + " s"))));
        }
        return new Eval(filterTemporal(events, temporal), false, "");
    }

    private static List<Event> filterTemporal(List<Event> events, TemporalConstraint temporal) {
        if (temporal == null) {
            return events;
        }
        List<Event> kept = new ArrayList<>();
        for (Event e : events) {
            long dur = Math.max(0L, e.endNanos - e.startNanos);
            if (temporal.debounceNanos() > 0 && dur < temporal.debounceNanos()) {
                continue;
            }
            if (temporal.hasDuration()) {
                if (temporal.durationOp() == DurationOp.FOR_AT_LEAST && dur < temporal.durationNanos()) {
                    continue;
                }
                if (temporal.durationOp() == DurationOp.FOR_AT_MOST && dur > temporal.durationNanos()) {
                    continue;
                }
            }
            kept.add(e);
        }
        if (temporal.mergeGapNanos() <= 0 || kept.size() < 2) {
            return kept;
        }
        List<Event> merged = new ArrayList<>();
        Event cur = kept.getFirst();
        for (int i = 1; i < kept.size(); i++) {
            Event n = kept.get(i);
            if (n.startNanos - cur.endNanos <= temporal.mergeGapNanos()) {
                cur = new Event(cur.startNanos, n.endNanos, Math.max(cur.peak, n.peak), cur.summary,
                        cur.evidence, cur.trace);
            } else {
                merged.add(cur);
                cur = n;
            }
        }
        merged.add(cur);
        return merged;
    }

    private SignalCatalogKey key(String role) {
        SignalBinding b = bindings.get(role);
        return b == null || !b.bound() ? null : b.key();
    }

    private String unit(String role) {
        SignalBinding b = bindings.get(role);
        return b == null ? "" : b.signalUnit();
    }

    private String name(String role) {
        SignalBinding b = bindings.get(role);
        return b == null || b.key() == null ? role : b.key().signalName();
    }

    private static double convert(double value, String from, String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank() || from.equals(to)) {
            return value;
        }
        OptionalDouble converted = UnitRegistry.convert(value, from, to);
        if (converted.isEmpty()) {
            throw new IllegalArgumentException("Cannot convert " + from + " to " + to);
        }
        return converted.getAsDouble();
    }
}
