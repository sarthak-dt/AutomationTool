package com.stellar.platform.analysis.investigation.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Line codec for one immutable rule version or one frozen result. Values are not recalculated on read. */
public final class EvaluationDocuments {

    private EvaluationDocuments() {
    }

    public static String encodeRule(EvaluationRule rule) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("ruleId", rule.ruleId());
        values.put("version", Integer.toString(rule.version()));
        values.put("procedureName", rule.procedureName());
        values.put("procedureVersion", rule.procedureVersion());
        values.put("eventCategory", rule.eventCategory());
        values.put("requiredSignals", String.join("|", rule.requiredSignals()));
        values.put("sourceId", rule.sourceId());
        values.put("operator", rule.operator().name());
        values.put("expectedValue", number(rule.expectedValue()));
        values.put("expectedLow", number(rule.expectedLow()));
        values.put("expectedHigh", number(rule.expectedHigh()));
        values.put("expectedState", rule.expectedState());
        values.put("unit", rule.unit());
        values.put("lowBoundary", rule.lowBoundary().name());
        values.put("highBoundary", rule.highBoundary().name());
        values.put("tolerance", number(rule.tolerance()));
        values.put("maxGapNanos", rule.maxGapNanos() == null ? "" : Long.toString(rule.maxGapNanos()));
        values.put("minCoverage", number(rule.minCoverage()));
        values.put("approval", rule.approval().name());
        values.put("approver", rule.approver());
        values.put("approvalReference", rule.approvalReference());
        values.put("createdAt", rule.createdAt());
        values.put("modifiedAt", rule.modifiedAt());
        values.put("conversionPolicy", EvaluationRule.CONVERSION_POLICY);
        values.put("samplingPolicy", EvaluationRule.SAMPLING_POLICY);
        values.put("missingDataPolicy", EvaluationRule.MISSING_DATA_POLICY);
        values.put("intervalPolicy", EvaluationRule.INTERVAL_POLICY);
        values.put("calculationMethod", rule.calculationMethod());
        values.put("calculationVersion", rule.calculationVersion());
        return write(values);
    }

    public static EvaluationRule decodeRule(String document) {
        Map<String, String> values = read(document);
        if (values.isEmpty()) {
            return null;
        }
        return new EvaluationRule(
                values.getOrDefault("ruleId", ""),
                integer(values.get("version")),
                values.getOrDefault("procedureName", ""),
                values.getOrDefault("procedureVersion", ""),
                values.getOrDefault("eventCategory", ""),
                split(values.get("requiredSignals")),
                values.getOrDefault("sourceId", ""),
                EvaluationRule.ComparisonOperator.parse(values.get("operator")),
                decimal(values.get("expectedValue")),
                decimal(values.get("expectedLow")),
                decimal(values.get("expectedHigh")),
                values.getOrDefault("expectedState", ""),
                values.getOrDefault("unit", ""),
                EvaluationRule.BoundaryPolicy.parse(values.get("lowBoundary")),
                EvaluationRule.BoundaryPolicy.parse(values.get("highBoundary")),
                decimal(values.get("tolerance")),
                longOrNull(values.get("maxGapNanos")),
                decimal(values.get("minCoverage")),
                EvaluationRule.RuleApproval.parse(values.get("approval")),
                values.getOrDefault("approver", ""),
                values.getOrDefault("approvalReference", ""),
                values.getOrDefault("createdAt", ""),
                values.getOrDefault("modifiedAt", ""));
    }

    public static String encodeResult(EvaluationResult result) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("resultId", result.resultId());
        values.put("eventId", result.eventId());
        values.put("eventName", result.eventName());
        values.put("ruleId", result.ruleId());
        values.put("ruleVersion", Integer.toString(result.ruleVersion()));
        values.put("procedureName", result.procedureName());
        values.put("procedureVersion", result.procedureVersion());
        values.put("approvalStatus", result.approvalStatus());
        values.put("exploratory", result.exploratory() ? "yes" : "no");
        values.put("approver", result.approver());
        values.put("approvalReference", result.approvalReference());
        values.put("expectedText", result.expectedText());
        values.put("actualText", result.actualText());
        values.put("unit", result.unit());
        values.put("coverageText", result.coverageText());
        values.put("methodText", result.methodText());
        values.put("outcome", result.outcome().name());
        values.put("reason", result.reason());
        values.put("detail", result.detail());
        values.put("evidenceSource", result.evidenceSource());
        values.put("startNanos", Long.toString(result.startNanos()));
        values.put("endNanos", result.endNanos() == null ? "" : Long.toString(result.endNanos()));
        values.put("sourceFile", result.sourceFile());
        values.put("createdAt", result.createdAt());
        return write(values);
    }

    public static EvaluationResult decodeResult(String document) {
        Map<String, String> values = read(document);
        if (values.isEmpty()) {
            return null;
        }
        return new EvaluationResult(
                values.getOrDefault("resultId", ""),
                values.getOrDefault("eventId", ""),
                values.getOrDefault("eventName", ""),
                values.getOrDefault("ruleId", ""),
                integer(values.get("ruleVersion")),
                values.getOrDefault("procedureName", ""),
                values.getOrDefault("procedureVersion", ""),
                values.getOrDefault("approvalStatus", ""),
                "yes".equals(values.get("exploratory")),
                values.getOrDefault("approver", ""),
                values.getOrDefault("approvalReference", ""),
                values.getOrDefault("expectedText", ""),
                values.getOrDefault("actualText", ""),
                values.getOrDefault("unit", ""),
                values.getOrDefault("coverageText", ""),
                values.getOrDefault("methodText", ""),
                EvaluationOutcome.parse(values.get("outcome")),
                values.getOrDefault("reason", ""),
                values.getOrDefault("detail", ""),
                values.getOrDefault("evidenceSource", ""),
                longOrZero(values.get("startNanos")),
                longOrNull(values.get("endNanos")),
                values.getOrDefault("sourceFile", ""),
                values.getOrDefault("createdAt", ""));
    }

    private static String write(Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue() == null ? "" : entry.getValue().replace('\n', ' ').replace('\r', ' ');
            out.append(entry.getKey()).append('=').append(value).append('\n');
        }
        return out.toString();
    }

    private static Map<String, String> read(String document) {
        Map<String, String> values = new LinkedHashMap<>();
        if (document == null || document.isBlank()) {
            return values;
        }
        for (String line : document.split("\n", -1)) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq), line.substring(eq + 1));
            }
        }
        return values;
    }

    private static List<String> split(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : text.split("\\|")) {
            if (!part.isBlank()) {
                out.add(part.strip());
            }
        }
        return List.copyOf(out);
    }

    private static String number(Double value) {
        return value == null ? "" : Double.toString(value);
    }

    private static Double decimal(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return Double.parseDouble(text);
    }

    private static int integer(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Integer.parseInt(text);
    }

    private static Long longOrNull(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        return Long.parseLong(text);
    }

    private static long longOrZero(String text) {
        Long value = longOrNull(text);
        return value == null ? 0L : value;
    }
}
