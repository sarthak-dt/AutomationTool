package com.stellar.platform.analysis.rules;

import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.model.AnalysisType;
import com.stellar.platform.analysis.model.ThresholdOperator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Restricted JSON codec for rule packs. Not a general parser. No eval.
 */
public final class RulePackJson {

    private RulePackJson() {
    }

    public static String write(DiagnosticRulePack pack) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        field(sb, "schemaVersion", pack.schemaVersion(), true);
        q(sb, "packId", pack.packId());
        q(sb, "packVersion", pack.packVersion());
        q(sb, "name", pack.name());
        q(sb, "description", pack.description());
        q(sb, "engineMinimumVersion", pack.engineMinimumVersion());
        sb.append("  \"rules\": [\n");
        for (int i = 0; i < pack.rules().size(); i++) {
            if (i > 0) {
                sb.append(",\n");
            }
            writeRule(sb, pack.rules().get(i));
        }
        sb.append("\n  ]\n}\n");
        return sb.toString();
    }

    public static DiagnosticRulePack read(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Empty rule pack");
        }
        int schema = intVal(json, "schemaVersion", 1);
        if (schema != 1) {
            throw new IllegalArgumentException("Unsupported schemaVersion " + schema);
        }
        String packId = req(json, "packId");
        List<DiagnosticRuleDefinition> rules = new ArrayList<>();
        for (String block : ruleBlocks(json)) {
            rules.add(parseRule(block));
        }
        return new DiagnosticRulePack(schema, packId, str(json, "packVersion", "1.0"),
                str(json, "name", packId), str(json, "description", ""),
                str(json, "engineMinimumVersion", "1"), rules);
    }

    private static void writeRule(StringBuilder sb, DiagnosticRuleDefinition r) {
        sb.append("    {\n");
        q(sb, "ruleId", r.ruleId(), 6);
        q(sb, "version", r.version(), 6);
        q(sb, "name", r.name(), 6);
        q(sb, "description", r.description(), 6);
        q(sb, "category", r.category().name(), 6);
        q(sb, "severity", r.severity().name(), 6);
        sb.append("      \"enabled\": ").append(r.enabled()).append(",\n");
        sb.append("      \"origin\": \"").append(r.origin().name()).append("\",\n");
        sb.append("      \"inputs\": [");
        for (int i = 0; i < r.inputs().size(); i++) {
            RuleInput in = r.inputs().get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("{\"role\":\"").append(esc(in.role())).append("\",\"unit\":\"")
                    .append(esc(in.expectedUnit())).append("\",\"required\":").append(in.required())
                    .append(",\"aliases\":[");
            for (int a = 0; a < in.aliases().size(); a++) {
                if (a > 0) {
                    sb.append(',');
                }
                sb.append('"').append(esc(in.aliases().get(a))).append('"');
            }
            sb.append("]}");
        }
        sb.append("],\n");
        sb.append("      \"parameters\": [");
        for (int i = 0; i < r.parameters().size(); i++) {
            RuleParameter p = r.parameters().get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("{\"name\":\"").append(esc(p.name())).append("\",\"type\":\"")
                    .append(esc(p.type())).append("\",\"unit\":\"").append(esc(p.unit()))
                    .append("\",\"defaultValue\":").append(p.defaultValue());
            if (p.min() != null) {
                sb.append(",\"min\":").append(p.min());
            }
            if (p.max() != null) {
                sb.append(",\"max\":").append(p.max());
            }
            sb.append("}");
        }
        sb.append("],\n");
        sb.append("      \"conditionKind\": \"").append(kind(r.condition())).append("\",\n");
        writeConditionFields(sb, r.condition());
        sb.append("      \"conditionTree\": ").append(ConditionTreeJson.write(r.condition())).append(",\n");
        sb.append("      \"guidance\": [");
        for (int i = 0; i < r.guidance().size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(esc(r.guidance().get(i))).append('"');
        }
        sb.append("]\n    }");
    }

    private static void writeConditionFields(StringBuilder sb, RuleCondition c) {
        switch (c) {
            case RuleCondition.Threshold t -> {
                q(sb, "role", t.role(), 6);
                q(sb, "operator", t.operator().name(), 6);
                sb.append("      \"value\": ").append(t.enter()).append(",\n");
                q(sb, "unit", t.unit(), 6);
                if (t.hysteresisExit() != null) {
                    sb.append("      \"hysteresisExit\": ").append(t.hysteresisExit()).append(",\n");
                }
                sb.append("      \"durationNanos\": ").append(t.temporal().durationNanos()).append(",\n");
            }
            case RuleCondition.Comparison cmp -> {
                q(sb, "role", cmp.role(), 6);
                q(sb, "operator", cmp.op().symbol(), 6);
                sb.append("      \"value\": ").append(cmp.value()).append(",\n");
                q(sb, "unit", cmp.unit(), 6);
                sb.append("      \"durationNanos\": ").append(cmp.temporal().durationNanos()).append(",\n");
            }
            case RuleCondition.Delta d -> {
                q(sb, "leftRole", d.leftRole(), 6);
                q(sb, "rightRole", d.rightRole(), 6);
                q(sb, "operator", d.op().symbol(), 6);
                sb.append("      \"value\": ").append(d.value()).append(",\n");
                q(sb, "unit", d.unit(), 6);
                sb.append("      \"durationNanos\": ").append(d.temporal().durationNanos()).append(",\n");
            }
            case RuleCondition.Rate r -> {
                q(sb, "role", r.role(), 6);
                q(sb, "operator", r.op().symbol(), 6);
                sb.append("      \"value\": ").append(r.value()).append(",\n");
                q(sb, "unit", r.unit(), 6);
            }
            case RuleCondition.AnalysisMetric m -> {
                q(sb, "metric", m.metric(), 6);
                q(sb, "analysisType", m.analysisType().name(), 6);
                q(sb, "operator", m.op().symbol(), 6);
                sb.append("      \"value\": ").append(m.value()).append(",\n");
                q(sb, "leftRole", m.roles().isEmpty() ? "" : m.roles().getFirst(), 6);
                q(sb, "rightRole", m.roles().size() < 2 ? "" : m.roles().get(1), 6);
            }
            case RuleCondition.Sequence s -> {
                q(sb, "fromState", firstTransition(s).fromState(), 6);
                q(sb, "toState", firstTransition(s).toState(), 6);
                q(sb, "role", firstTransition(s).role(), 6);
                sb.append("      \"withinNanos\": ").append(
                        s.steps().size() > 1 ? s.steps().get(1).withinNanos() : 0).append(",\n");
                if (s.steps().size() > 1 && s.steps().get(1).condition() instanceof RuleCondition.Comparison cmp) {
                    q(sb, "leftRole", cmp.role(), 6);
                    sb.append("      \"value\": ").append(cmp.value()).append(",\n");
                    q(sb, "unit", cmp.unit(), 6);
                    q(sb, "operator", cmp.op().symbol(), 6);
                }
            }
            case RuleCondition.Range r -> {
                q(sb, "role", r.role(), 6);
                sb.append("      \"inside\": ").append(r.inside()).append(",\n");
                sb.append("      \"min\": ").append(r.min()).append(",\n");
                sb.append("      \"max\": ").append(r.max()).append(",\n");
                q(sb, "unit", r.unit(), 6);
                sb.append("      \"durationNanos\": ").append(r.temporal().durationNanos()).append(",\n");
            }
            case RuleCondition.StateEquals s -> {
                q(sb, "role", s.role(), 6);
                q(sb, "state", s.state(), 6);
                sb.append("      \"negate\": ").append(s.negate()).append(",\n");
            }
            case RuleCondition.Logical ignored -> {
                q(sb, "logicalOp", ignored.op().name(), 6);
            }
            default -> {
            }
        }
    }

    private static RuleCondition.Transition firstTransition(RuleCondition.Sequence s) {
        if (s.steps().getFirst().condition() instanceof RuleCondition.Transition t) {
            return t;
        }
        return new RuleCondition.Transition("ROLE", "", "");
    }

    private static String kind(RuleCondition c) {
        return switch (c) {
            case RuleCondition.Threshold ignored -> "THRESHOLD";
            case RuleCondition.Comparison ignored -> "COMPARISON";
            case RuleCondition.Range ignored -> "RANGE";
            case RuleCondition.StateEquals ignored -> "STATE";
            case RuleCondition.Rate ignored -> "RATE";
            case RuleCondition.Delta ignored -> "DELTA";
            case RuleCondition.Transition ignored -> "TRANSITION";
            case RuleCondition.CrossSignalDifference ignored -> "CROSS_DIFF";
            case RuleCondition.AnalysisMetric ignored -> "METRIC";
            case RuleCondition.Sequence ignored -> "SEQUENCE";
            case RuleCondition.Logical ignored -> "LOGICAL";
        };
    }

    private static DiagnosticRuleDefinition parseRule(String block) {
        String id = req(block, "ruleId");
        String kind = str(block, "conditionKind", "THRESHOLD");
        RuleCondition fromTree = ConditionTreeJson.tryRead(extractObject(block, "conditionTree"));
        RuleCondition condition = fromTree != null ? fromTree : switch (kind) {
            case "THRESHOLD" -> new RuleCondition.Threshold(
                    str(block, "role", "ROLE"),
                    ThresholdOperator.valueOf(str(block, "operator", "GREATER_OR_EQUAL")),
                    dbl(block, "value", 0),
                    optionalDouble(block, "hysteresisExit"),
                    str(block, "unit", ""),
                    new TemporalConstraint(DurationOp.FOR_AT_LEAST, lng(block, "durationNanos", 0),
                            optionalDouble(block, "hysteresisExit"), 0L, 0L));
            case "COMPARISON" -> new RuleCondition.Comparison(
                    str(block, "role", "ROLE"), CompareOp.parse(str(block, "operator", ">=")),
                    dbl(block, "value", 0), str(block, "unit", ""),
                    TemporalConstraint.atLeast(lng(block, "durationNanos", 0)));
            case "DELTA" -> new RuleCondition.Delta(
                    str(block, "leftRole", "A"), str(block, "rightRole", "B"),
                    CompareOp.parse(str(block, "operator", ">=")), dbl(block, "value", 0),
                    str(block, "unit", ""), TemporalConstraint.atLeast(lng(block, "durationNanos", 0)));
            case "RATE" -> new RuleCondition.Rate(
                    str(block, "role", "ROLE"), CompareOp.parse(str(block, "operator", ">")),
                    dbl(block, "value", 0), str(block, "unit", ""));
            case "METRIC" -> new RuleCondition.AnalysisMetric(
                    AnalysisType.valueOf(str(block, "analysisType", "CORRELATION")),
                    List.of(str(block, "leftRole", "A"), str(block, "rightRole", "B")),
                    str(block, "metric", "pearson"), CompareOp.parse(str(block, "operator", "<")),
                    dbl(block, "value", 0));
            case "SEQUENCE" -> new RuleCondition.Sequence(List.of(
                    new RuleCondition.SequenceStep(new RuleCondition.Transition(
                            str(block, "role", "BRAKE_PEDAL"),
                            str(block, "fromState", "OFF"), str(block, "toState", "ON")), 0L),
                    new RuleCondition.SequenceStep(new RuleCondition.Comparison(
                            str(block, "leftRole", "BRAKE_PRESSURE"),
                            CompareOp.parse(str(block, "operator", ">=")),
                            dbl(block, "value", 0), str(block, "unit", ""), TemporalConstraint.none()),
                            lng(block, "withinNanos", 0))));
            case "RANGE" -> new RuleCondition.Range(
                    str(block, "role", "ROLE"),
                    !block.contains("\"inside\": false"),
                    dbl(block, "min", 0), dbl(block, "max", 0), str(block, "unit", ""),
                    TemporalConstraint.atLeast(lng(block, "durationNanos", 0)));
            case "STATE" -> new RuleCondition.StateEquals(
                    str(block, "role", "ROLE"), str(block, "state", ""),
                    block.contains("\"negate\": true"));
            default -> throw new IllegalArgumentException("Unsupported conditionKind " + kind);
        };
        List<RuleInput> inputs = parseInputs(block);
        if (inputs.isEmpty()) {
            inputs = List.of(RuleInput.required(str(block, "role", "ROLE"), str(block, "unit", "")));
        }
        return new DiagnosticRuleDefinition(
                id, str(block, "version", "1"), str(block, "name", id), str(block, "description", ""),
                RuleCategory.valueOf(str(block, "category", "CUSTOM")),
                AnalysisSeverity.valueOf(str(block, "severity", "OBSERVATION")),
                !block.contains("\"enabled\": false"),
                inputs, parseParameters(block), condition, parseGuidance(block), List.of(),
                RuleOrigin.valueOf(str(block, "origin", "PROJECT_CUSTOM")), null, null);
    }

    private static List<RuleParameter> parseParameters(String block) {
        List<RuleParameter> out = new ArrayList<>();
        Matcher m = Pattern.compile(
                "\\{\"name\":\"([^\"]+)\",\"type\":\"([^\"]*)\",\"unit\":\"([^\"]*)\",\"defaultValue\":(-?\\d+(?:\\.\\d+)?)"
                        + "(?:,\"min\":(-?\\d+(?:\\.\\d+)?))?(?:,\"max\":(-?\\d+(?:\\.\\d+)?))?}")
                .matcher(block);
        while (m.find()) {
            Double min = m.group(5) == null ? null : Double.parseDouble(m.group(5));
            Double max = m.group(6) == null ? null : Double.parseDouble(m.group(6));
            out.add(new RuleParameter(m.group(1), m.group(2), m.group(3),
                    Double.parseDouble(m.group(4)), min, max, m.group(1)));
        }
        return out;
    }

    static String extractObject(String json, String field) {
        if (json == null) {
            return "";
        }
        int i = json.indexOf("\"" + field + "\"");
        if (i < 0) {
            return "";
        }
        int brace = json.indexOf('{', i);
        if (brace < 0) {
            return "";
        }
        int depth = 0;
        for (int p = brace; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(brace, p + 1);
                }
            }
        }
        return "";
    }

    private static List<RuleInput> parseInputs(String block) {
        List<RuleInput> out = new ArrayList<>();
        Matcher m = Pattern.compile("\\{\"role\":\"([^\"]+)\",\"unit\":\"([^\"]*)\",\"required\":(true|false),\"aliases\":\\[([^]]*)]}")
                .matcher(block);
        while (m.find()) {
            List<String> aliases = new ArrayList<>();
            if (!m.group(4).isBlank()) {
                for (String a : m.group(4).split(",")) {
                    aliases.add(a.replace("\"", "").trim());
                }
            }
            out.add(new RuleInput(m.group(1), aliases, m.group(2), Boolean.parseBoolean(m.group(3))));
        }
        return out;
    }

    private static List<String> parseGuidance(String block) {
        int i = block.indexOf("\"guidance\"");
        if (i < 0) {
            return List.of();
        }
        int a = block.indexOf('[', i);
        int b = block.indexOf(']', a);
        if (a < 0 || b < 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"([^\"]*)\"").matcher(block.substring(a, b));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static List<String> ruleBlocks(String json) {
        List<String> out = new ArrayList<>();
        int rules = json.indexOf("\"rules\"");
        if (rules < 0) {
            return out;
        }
        int i = json.indexOf('[', rules) + 1;
        int depth = 0;
        int start = -1;
        for (; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(json.substring(start, i + 1));
                    start = -1;
                }
            } else if (c == ']' && depth == 0) {
                break;
            }
        }
        return out;
    }

    private static void field(StringBuilder sb, String name, int value, boolean comma) {
        sb.append("  \"").append(name).append("\": ").append(value).append(comma ? ",\n" : "\n");
    }

    private static void q(StringBuilder sb, String name, String value) {
        q(sb, name, value, 2);
    }

    private static void q(StringBuilder sb, String name, String value, int indent) {
        sb.append(" ".repeat(indent)).append('"').append(name).append("\": \"")
                .append(esc(value)).append("\",\n");
    }

    private static String esc(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String req(String json, String name) {
        String v = str(json, name, "");
        if (v.isBlank()) {
            throw new IllegalArgumentException("Missing " + name);
        }
        return v;
    }

    private static String str(String json, String name, String fallback) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : fallback;
    }

    private static int intVal(String json, String name, int fallback) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private static long lng(String json, String name, long fallback) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : fallback;
    }

    private static double dbl(String json, String name, double fallback) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : fallback;
    }

    private static Double optionalDouble(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }
}
