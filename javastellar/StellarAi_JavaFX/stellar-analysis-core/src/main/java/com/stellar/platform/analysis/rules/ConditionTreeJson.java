package com.stellar.platform.analysis.rules;

import com.stellar.platform.analysis.model.AnalysisType;
import com.stellar.platform.analysis.model.ThresholdOperator;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compact recursive condition codec. Not a general JSON library. */
final class ConditionTreeJson {

    private ConditionTreeJson() {
    }

    static String write(RuleCondition condition) {
        return writeNode(condition);
    }

    static RuleCondition tryRead(String json) {
        if (json == null || json.isBlank() || !json.contains("\"k\"")) {
            return null;
        }
        try {
            return readNode(json);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String writeNode(RuleCondition c) {
        return switch (c) {
            case RuleCondition.Threshold t -> obj("THRESHOLD",
                    kv("role", t.role()) + kv("op", t.operator().name())
                            + num("value", t.enter()) + kv("unit", t.unit())
                            + (t.hysteresisExit() == null ? "" : num("hexit", t.hysteresisExit()))
                            + lng("d", t.temporal().durationNanos()));
            case RuleCondition.Comparison cmp -> obj("COMPARISON",
                    kv("role", cmp.role()) + kv("op", cmp.op().symbol())
                            + num("value", cmp.value()) + kv("unit", cmp.unit())
                            + lng("d", cmp.temporal().durationNanos()));
            case RuleCondition.Range r -> obj("RANGE",
                    kv("role", r.role()) + bool("inside", r.inside())
                            + num("min", r.min()) + num("max", r.max())
                            + kv("unit", r.unit()) + lng("d", r.temporal().durationNanos()));
            case RuleCondition.StateEquals s -> obj("STATE",
                    kv("role", s.role()) + kv("state", s.state()) + bool("neg", s.negate()));
            case RuleCondition.Rate r -> obj("RATE",
                    kv("role", r.role()) + kv("op", r.op().symbol())
                            + num("value", r.value()) + kv("unit", r.unit()));
            case RuleCondition.Delta d -> obj("DELTA",
                    kv("left", d.leftRole()) + kv("right", d.rightRole())
                            + kv("op", d.op().symbol()) + num("value", d.value())
                            + kv("unit", d.unit()) + lng("d", d.temporal().durationNanos()));
            case RuleCondition.Transition t -> obj("TRANSITION",
                    kv("role", t.role()) + kv("from", t.fromState()) + kv("to", t.toState()));
            case RuleCondition.CrossSignalDifference d -> obj("CROSS_DIFF",
                    kv("left", d.leftRole()) + kv("right", d.rightRole())
                            + kv("op", d.op().symbol()) + num("value", d.value())
                            + kv("unit", d.unit()) + lng("d", d.temporal().durationNanos()));
            case RuleCondition.AnalysisMetric m -> obj("METRIC",
                    kv("metric", m.metric()) + kv("atype", m.analysisType().name())
                            + kv("op", m.op().symbol()) + num("value", m.value())
                            + kv("left", m.roles().isEmpty() ? "" : m.roles().getFirst())
                            + kv("right", m.roles().size() < 2 ? "" : m.roles().get(1)));
            case RuleCondition.Sequence s -> {
                StringBuilder sb = new StringBuilder();
                sb.append("\"steps\":[");
                for (int i = 0; i < s.steps().size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append("{\"w\":").append(s.steps().get(i).withinNanos())
                            .append(",\"c\":").append(writeNode(s.steps().get(i).condition())).append('}');
                }
                sb.append(']');
                yield obj("SEQUENCE", sb.toString());
            }
            case RuleCondition.Logical logic -> {
                StringBuilder sb = new StringBuilder();
                sb.append(kv("op", logic.op().name()));
                sb.append("\"c\":[");
                for (int i = 0; i < logic.children().size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(writeNode(logic.children().get(i)));
                }
                sb.append(']');
                yield obj("LOGICAL", sb.toString());
            }
        };
    }

    private static RuleCondition readNode(String json) {
        String k = str(json, "k");
        return switch (k) {
            case "THRESHOLD" -> new RuleCondition.Threshold(
                    str(json, "role"), ThresholdOperator.valueOf(str(json, "op")),
                    dbl(json, "value"), optional(json, "hexit"), str(json, "unit"),
                    TemporalConstraint.atLeast(lng(json, "d")));
            case "COMPARISON" -> new RuleCondition.Comparison(
                    str(json, "role"), CompareOp.parse(str(json, "op")),
                    dbl(json, "value"), str(json, "unit"), TemporalConstraint.atLeast(lng(json, "d")));
            case "RANGE" -> new RuleCondition.Range(
                    str(json, "role"), !json.contains("\"inside\":false"),
                    dbl(json, "min"), dbl(json, "max"), str(json, "unit"),
                    TemporalConstraint.atLeast(lng(json, "d")));
            case "STATE" -> new RuleCondition.StateEquals(
                    str(json, "role"), str(json, "state"), json.contains("\"neg\":true"));
            case "RATE" -> new RuleCondition.Rate(
                    str(json, "role"), CompareOp.parse(str(json, "op")),
                    dbl(json, "value"), str(json, "unit"));
            case "DELTA" -> new RuleCondition.Delta(
                    str(json, "left"), str(json, "right"), CompareOp.parse(str(json, "op")),
                    dbl(json, "value"), str(json, "unit"), TemporalConstraint.atLeast(lng(json, "d")));
            case "TRANSITION" -> new RuleCondition.Transition(str(json, "role"), str(json, "from"), str(json, "to"));
            case "CROSS_DIFF" -> new RuleCondition.CrossSignalDifference(
                    str(json, "left"), str(json, "right"), CompareOp.parse(str(json, "op")),
                    dbl(json, "value"), str(json, "unit"), TemporalConstraint.atLeast(lng(json, "d")));
            case "METRIC" -> new RuleCondition.AnalysisMetric(
                    AnalysisType.valueOf(nz(str(json, "atype"), "CORRELATION")),
                    List.of(str(json, "left"), str(json, "right")),
                    str(json, "metric"), CompareOp.parse(nz(str(json, "op"), "<")),
                    dbl(json, "value"));
            case "SEQUENCE" -> {
                List<RuleCondition.SequenceStep> steps = new ArrayList<>();
                for (String step : arrayObjects(extractArray(json, "steps"))) {
                    steps.add(new RuleCondition.SequenceStep(
                            readNode(extractFirstObject(step)), lng(step, "w")));
                }
                yield new RuleCondition.Sequence(steps);
            }
            case "LOGICAL" -> {
                List<RuleCondition> children = new ArrayList<>();
                for (String child : arrayObjects(extractArray(json, "c"))) {
                    children.add(readNode(child));
                }
                yield new RuleCondition.Logical(LogicalOp.valueOf(str(json, "op")), children);
            }
            default -> throw new IllegalArgumentException("Unknown condition k=" + k);
        };
    }

    private static String obj(String kind, String body) {
        String trimmed = body.endsWith(",") ? body.substring(0, body.length() - 1) : body;
        return "{\"k\":\"" + kind + "\"," + trimmed + "}";
    }

    private static String kv(String name, String value) {
        return "\"" + name + "\":\"" + esc(value) + "\",";
    }

    private static String num(String name, double value) {
        return "\"" + name + "\":" + value + ",";
    }

    private static String lng(String name, long value) {
        return "\"" + name + "\":" + value + ",";
    }

    private static String bool(String name, boolean value) {
        return "\"" + name + "\":" + value + ",";
    }

    private static String esc(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String str(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static String nz(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static double dbl(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : 0d;
    }

    private static long lng(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    private static Double optional(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    private static String extractArray(String json, String field) {
        int i = json.indexOf("\"" + field + "\"");
        if (i < 0) {
            return "[]";
        }
        int a = json.indexOf('[', i);
        if (a < 0) {
            return "[]";
        }
        int depth = 0;
        for (int p = a; p < json.length(); p++) {
            char c = json.charAt(p);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return json.substring(a, p + 1);
                }
            }
        }
        return "[]";
    }

    private static List<String> arrayObjects(String array) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < array.length(); i++) {
            char c = array.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    out.add(array.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return out;
    }

    private static String extractFirstObject(String json) {
        int i = json.indexOf('{');
        if (i < 0) {
            return json;
        }
        if (json.indexOf("\"k\"") >= 0 && json.indexOf("\"c\":{") < 0) {
            int c = json.indexOf("\"c\":");
            if (c >= 0) {
                return RulePackJson.extractObject(json.substring(c), "c");
            }
        }
        return json;
    }
}
