package com.stellar.platform.analysis.investigation.eval;

import com.stellar.platform.analysis.investigation.EngineerEventDefinition;
import com.stellar.platform.analysis.investigation.EngineerEventValidation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Compares stored samples with one rule version.
 * No resampling, interpolation, averaging, or zero-fill. Samples are not modified.
 */
public final class EvaluationEngine {

    public static final String REQUIRED_SIGNAL_MISSING = "Required signal missing.";
    public static final String UNIT_INCOMPATIBLE = "Unit incompatible.";
    public static final String INSUFFICIENT_COVERAGE = "Insufficient coverage.";
    public static final String UNACCEPTABLE_TIMESTAMP_GAP = "Unacceptable timestamp gap.";
    public static final String UNKNOWN_CLOCK_COMPARABILITY = "Unknown clock comparability.";
    public static final String INVALID_RULE = "Invalid or incomplete rule.";
    public static final String RULE_NOT_SELECTED = "The rule was not selected.";
    private static final String NOT_A_VEHICLE_RESULT =
            "This outcome is not a vehicle pass or fail.";

    private EvaluationEngine() {
    }

    public static EvaluationResult notEvaluated(EvaluationRule rule, EngineerEventDefinition event) {
        EvaluationRule safe = rule == null ? blankRule() : rule;
        EngineerEventDefinition safeEvent = event;
        return base(safe, safeEvent, EvaluationOutcome.NOT_EVALUATED, RULE_NOT_SELECTED,
                "The rule was not selected for this event.", "", "0/0", List.of());
    }

    public static EvaluationResult evaluate(
            EvaluationRule rule,
            EngineerEventDefinition event,
            List<EvaluationSeries> series,
            boolean clocksComparable
    ) {
        if (rule == null || event == null || rule.ruleId().isBlank() || event.id().isBlank()) {
            return base(rule == null ? blankRule() : rule, event, EvaluationOutcome.INDETERMINATE,
                    INVALID_RULE, "The rule or the event is missing.", "", "0/0", List.of());
        }
        String invalid = incomplete(rule, event);
        if (invalid != null) {
            return base(rule, event, EvaluationOutcome.INDETERMINATE, INVALID_RULE, invalid, "", "0/0", List.of());
        }
        if (!clocksComparable) {
            return base(rule, event, EvaluationOutcome.INDETERMINATE, UNKNOWN_CLOCK_COMPARABILITY,
                    "Another file's clock was not verified against this event.", "", "0/0", List.of());
        }
        if (!rule.sourceId().isBlank() && !event.sourceId().isBlank() && !rule.sourceId().equals(event.sourceId())) {
            return base(rule, event, EvaluationOutcome.INDETERMINATE, UNKNOWN_CLOCK_COMPARABILITY,
                    "The rule names a different measurement from the event.", "", "0/0", List.of());
        }
        List<SignalJudgement> judgements = new ArrayList<>();
        for (String required : rule.requiredSignals()) {
            String name = EngineerEventValidation.signalName(required);
            EvaluationSeries found = find(series, name);
            if (found == null) {
                return base(rule, event, EvaluationOutcome.INDETERMINATE, REQUIRED_SIGNAL_MISSING,
                        name + " was not in the stored samples.", "", "0/0", List.of(name));
            }
            if (!found.sourceId().isBlank() && !event.sourceId().isBlank()
                    && !found.sourceId().equals(event.sourceId())) {
                return base(rule, event, EvaluationOutcome.INDETERMINATE, UNKNOWN_CLOCK_COMPARABILITY,
                        name + " comes from a different measurement.", "", "0/0", List.of(name));
            }
            if (!found.unit().equals(rule.unit())) {
                return base(rule, event, EvaluationOutcome.INDETERMINATE, UNIT_INCOMPATIBLE,
                        name + " is recorded in \"" + found.unit() + "\". The rule unit is \""
                                + rule.unit() + "\". No conversion was applied.",
                        "", "0/0", List.of(name));
            }
            judgements.add(judge(rule, event, name, found.samples()));
        }
        for (SignalJudgement judgement : judgements) {
            if (judgement.outcome == EvaluationOutcome.INDETERMINATE) {
                return base(rule, event, judgement.outcome, judgement.reason, judgement.detail,
                        judgement.actual, judgement.coverage, names(judgements));
            }
        }
        boolean fail = false;
        StringBuilder actual = new StringBuilder();
        String coverage = judgements.isEmpty() ? "0/0" : judgements.getFirst().coverage;
        for (SignalJudgement judgement : judgements) {
            if (!actual.isEmpty()) {
                actual.append("; ");
            }
            actual.append(judgement.actual);
            if (judgement.outcome == EvaluationOutcome.DOES_NOT_MEET_CRITERION) {
                fail = true;
            }
            if (coverageRatio(judgement.coverage) < coverageRatio(coverage)) {
                coverage = judgement.coverage;
            }
        }
        return base(rule, event, fail
                        ? EvaluationOutcome.DOES_NOT_MEET_CRITERION
                        : EvaluationOutcome.MEETS_CRITERION,
                "", "", actual.toString(), coverage, names(judgements));
    }

    private static SignalJudgement judge(
            EvaluationRule rule, EngineerEventDefinition event, String signal, List<EvaluationSample> samples
    ) {
        if (event.window()) {
            return judgeWindow(rule, event, signal, samples);
        }
        return judgePoint(rule, event, signal, samples);
    }

    private static SignalJudgement judgePoint(
            EvaluationRule rule, EngineerEventDefinition event, String signal, List<EvaluationSample> samples
    ) {
        EvaluationSample match = null;
        for (EvaluationSample sample : samples) {
            if (sample.timestampNanos() != event.startNanos()) {
                continue;
            }
            if (match == null || (!match.usable() && sample.usable())) {
                match = sample;
            }
        }
        if (match == null || !match.usable()) {
            return new SignalJudgement(signal, EvaluationOutcome.INDETERMINATE, INSUFFICIENT_COVERAGE,
                    "No stored usable sample at the point timestamp. The value was not interpolated.",
                    "", "0/1");
        }
        boolean meets = rule.operator() == EvaluationRule.ComparisonOperator.STATE_EQUALS
                ? stateOf(match).equals(rule.expectedState())
                : inside(match.value(), rule);
        String actual = rule.operator() == EvaluationRule.ComparisonOperator.STATE_EQUALS
                ? signal + " state " + stateOf(match)
                : signal + " sample " + decimal(match.value()) + " " + rule.unit();
        return new SignalJudgement(signal, meets
                ? EvaluationOutcome.MEETS_CRITERION : EvaluationOutcome.DOES_NOT_MEET_CRITERION,
                "", "", actual, "1/1");
    }

    private static SignalJudgement judgeWindow(
            EvaluationRule rule, EngineerEventDefinition event, String signal, List<EvaluationSample> samples
    ) {
        List<EvaluationSample> inside = new ArrayList<>();
        for (EvaluationSample sample : samples) {
            if (sample.timestampNanos() >= event.startNanos() && sample.timestampNanos() <= event.endNanos()) {
                inside.add(sample);
            }
        }
        inside.sort(Comparator.comparingLong(EvaluationSample::timestampNanos));
        int usable = 0;
        for (EvaluationSample sample : inside) {
            if (sample.usable()) {
                usable++;
            }
        }
        String coverage = usable + "/" + inside.size();
        if (inside.isEmpty() || usable == 0) {
            return new SignalJudgement(signal, EvaluationOutcome.INDETERMINATE, INSUFFICIENT_COVERAGE,
                    "No stored usable sample inside the event window. Missing samples were not treated as zero.",
                    "", coverage);
        }
        long limit = rule.maxGapNanos();
        if (inside.getFirst().timestampNanos() - event.startNanos() > limit
                || event.endNanos() - inside.getLast().timestampNanos() > limit) {
            return gap(signal, coverage);
        }
        for (int i = 1; i < inside.size(); i++) {
            if (inside.get(i).timestampNanos() - inside.get(i - 1).timestampNanos() > limit) {
                return gap(signal, coverage);
            }
        }
        if (usable < rule.minCoverage() * inside.size()) {
            return new SignalJudgement(signal, EvaluationOutcome.INDETERMINATE, INSUFFICIENT_COVERAGE,
                    "Usable coverage " + coverage + " is below the rule minimum "
                            + decimal(rule.minCoverage()) + ".",
                    "", coverage);
        }
        List<EvaluationSample> measured = new ArrayList<>();
        for (EvaluationSample sample : inside) {
            if (sample.usable()) {
                measured.add(sample);
            }
        }
        if (rule.operator() == EvaluationRule.ComparisonOperator.STATE_EQUALS) {
            boolean meets = true;
            for (EvaluationSample sample : measured) {
                if (!stateOf(sample).equals(rule.expectedState())) {
                    meets = false;
                    break;
                }
            }
            return new SignalJudgement(signal, meets
                    ? EvaluationOutcome.MEETS_CRITERION : EvaluationOutcome.DOES_NOT_MEET_CRITERION,
                    "", "", signal + " state checked on " + measured.size() + " samples", coverage);
        }
        double extreme = measured.getFirst().value();
        boolean minimum = rule.operator() == EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE;
        boolean all = rule.operator() == EvaluationRule.ComparisonOperator.THRESHOLD_OVER_INTERVAL;
        boolean meets = true;
        for (EvaluationSample sample : measured) {
            double value = sample.value();
            if (minimum) {
                extreme = Math.min(extreme, value);
            } else if (!all) {
                extreme = Math.max(extreme, value);
            }
            if (all && !inside(value, rule)) {
                meets = false;
            }
        }
        if (!all) {
            meets = inside(extreme, rule);
        }
        String actual = all
                ? signal + " usable samples " + measured.size() + ", minimum " + decimal(min(measured))
                + " " + rule.unit() + ", maximum " + decimal(max(measured)) + " " + rule.unit()
                : signal + (minimum ? " minimum " : " maximum ") + decimal(extreme) + " " + rule.unit();
        return new SignalJudgement(signal, meets
                ? EvaluationOutcome.MEETS_CRITERION : EvaluationOutcome.DOES_NOT_MEET_CRITERION,
                "", "", actual, coverage);
    }

    private static SignalJudgement gap(String signal, String coverage) {
        return new SignalJudgement(signal, EvaluationOutcome.INDETERMINATE, UNACCEPTABLE_TIMESTAMP_GAP,
                "A timestamp gap inside " + signal + " is larger than the rule allows. "
                        + "No value was interpolated across the gap.",
                "", coverage);
    }

    private static boolean inside(double value, EvaluationRule rule) {
        if (rule.expectedValue() != null) {
            double tolerance = rule.tolerance() == null ? 0.0d : rule.tolerance();
            return value >= rule.expectedValue() - tolerance && value <= rule.expectedValue() + tolerance;
        }
        if (rule.expectedLow() != null && failsLow(value, rule.expectedLow(), rule.lowBoundary())) {
            return false;
        }
        return rule.expectedHigh() == null || !failsHigh(value, rule.expectedHigh(), rule.highBoundary());
    }

    private static boolean failsLow(double value, double low, EvaluationRule.BoundaryPolicy policy) {
        return policy == EvaluationRule.BoundaryPolicy.EXCLUSIVE ? value <= low : value < low;
    }

    private static boolean failsHigh(double value, double high, EvaluationRule.BoundaryPolicy policy) {
        return policy == EvaluationRule.BoundaryPolicy.EXCLUSIVE ? value >= high : value > high;
    }

    private static String incomplete(EvaluationRule rule, EngineerEventDefinition event) {
        if (rule.procedureName().isBlank() || rule.procedureVersion().isBlank() || rule.unit().isBlank()
                || rule.requiredSignals().isEmpty()) {
            return "Procedure, unit, or required signals are missing.";
        }
        if (rule.operator().window() != event.window()) {
            return event.window()
                    ? "This rule reads one point sample. The event is a time window."
                    : "This rule reads a time window. The event is a point.";
        }
        if (event.window() && (event.endNanos() == null || event.endNanos() < event.startNanos())) {
            return "The event window is incomplete.";
        }
        if (event.window() && (rule.maxGapNanos() == null || rule.maxGapNanos() < 0
                || rule.minCoverage() == null || rule.minCoverage() < 0.0d || rule.minCoverage() > 1.0d)) {
            return "The window rule needs a maximum gap and a coverage minimum from 0 to 1.";
        }
        if (rule.tolerance() != null && rule.tolerance() < 0.0d) {
            return "Tolerance cannot be negative.";
        }
        if (rule.operator() == EvaluationRule.ComparisonOperator.STATE_EQUALS) {
            return rule.expectedState().isBlank() ? "The expected state is blank." : null;
        }
        if (rule.operator() == EvaluationRule.ComparisonOperator.POINT_SAMPLE && rule.expectedValue() == null
                && (rule.expectedLow() == null || rule.expectedHigh() == null)) {
            return "The point rule needs an expected value or a range.";
        }
        if (rule.operator() != EvaluationRule.ComparisonOperator.POINT_SAMPLE
                && rule.operator() != EvaluationRule.ComparisonOperator.STATE_EQUALS
                && rule.expectedValue() == null
                && (rule.expectedLow() == null || rule.expectedHigh() == null)) {
            return "The rule needs an expected range.";
        }
        return null;
    }

    private static EvaluationResult base(
            EvaluationRule rule,
            EngineerEventDefinition event,
            EvaluationOutcome outcome,
            String reason,
            String detail,
            String actual,
            String coverage,
            List<String> signals
    ) {
        String evidence = "Stored samples"
                + (event == null || event.sourceFile().isBlank() ? "" : " from " + event.sourceFile())
                + (signals.isEmpty() ? "" : " (" + String.join(", ", signals) + ")")
                + ". Samples were not modified. " + NOT_A_VEHICLE_RESULT;
        if (rule.exploratory()) {
            evidence = evidence + " Exploratory evaluation against a draft rule. "
                    + "This is not a customer-approved compliance result.";
        }
        return new EvaluationResult(
                "",
                event == null ? "" : event.id(),
                event == null ? "" : event.name(),
                rule.ruleId(),
                rule.version(),
                rule.procedureName(),
                rule.procedureVersion(),
                rule.approval().label(),
                rule.exploratory(),
                rule.approver(),
                rule.approvalReference(),
                expectedText(rule),
                actual,
                rule.unit(),
                coverage,
                rule.calculationMethod() + " " + rule.calculationVersion(),
                outcome,
                reason,
                detail,
                evidence,
                event == null ? 0L : event.startNanos(),
                event == null ? null : event.endNanos(),
                event == null ? "" : event.sourceFile(),
                "");
    }

    static String expectedText(EvaluationRule rule) {
        if (rule.operator() == EvaluationRule.ComparisonOperator.STATE_EQUALS) {
            return "State equals " + rule.expectedState();
        }
        if (rule.expectedValue() != null) {
            String tolerance = rule.tolerance() == null ? "" : " tolerance " + decimal(rule.tolerance());
            return decimal(rule.expectedValue()) + " " + rule.unit() + tolerance;
        }
        if (rule.expectedLow() == null || rule.expectedHigh() == null) {
            return "";
        }
        return decimal(rule.expectedLow()) + " " + rule.lowBoundary().name().toLowerCase()
                + " to " + decimal(rule.expectedHigh()) + " " + rule.highBoundary().name().toLowerCase()
                + " " + rule.unit();
    }

    private static EvaluationSeries find(List<EvaluationSeries> series, String name) {
        if (series == null) {
            return null;
        }
        for (EvaluationSeries candidate : series) {
            if (candidate != null && name.equals(EngineerEventValidation.signalName(candidate.signalName()))) {
                return candidate;
            }
        }
        return null;
    }

    private static String stateOf(EvaluationSample sample) {
        return sample.state().isBlank() ? decimal(sample.value()) : sample.state();
    }

    private static double min(List<EvaluationSample> samples) {
        double value = samples.getFirst().value();
        for (EvaluationSample sample : samples) {
            value = Math.min(value, sample.value());
        }
        return value;
    }

    private static double max(List<EvaluationSample> samples) {
        double value = samples.getFirst().value();
        for (EvaluationSample sample : samples) {
            value = Math.max(value, sample.value());
        }
        return value;
    }

    private static List<String> names(List<SignalJudgement> judgements) {
        List<String> names = new ArrayList<>();
        for (SignalJudgement judgement : judgements) {
            names.add(judgement.signal);
        }
        return names;
    }

    private static double coverageRatio(String coverage) {
        int slash = coverage.indexOf('/');
        if (slash < 1) {
            return 0.0d;
        }
        try {
            double usable = Double.parseDouble(coverage.substring(0, slash));
            double total = Double.parseDouble(coverage.substring(slash + 1));
            return total == 0.0d ? 0.0d : usable / total;
        } catch (RuntimeException ex) {
            return 0.0d;
        }
    }

    private static EvaluationRule blankRule() {
        return new EvaluationRule("", 0, "", "", "", List.of(), "", null, null, null, null, "", "",
                null, null, null, null, null, null, "", "", "", "");
    }

    static String decimal(double value) {
        return new BigDecimal(Double.toString(value)).setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private record SignalJudgement(
            String signal, EvaluationOutcome outcome, String reason, String detail, String actual, String coverage
    ) {
    }
}
