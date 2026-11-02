package com.stellar.platform.analysis.rules;

import com.stellar.platform.analysis.model.AnalysisType;
import com.stellar.platform.analysis.model.ThresholdOperator;

import java.util.List;

/** Typed condition tree. Not a script. */
public sealed interface RuleCondition {

    record Comparison(
            String role,
            CompareOp op,
            double value,
            String unit,
            TemporalConstraint temporal
    ) implements RuleCondition {
        public Comparison {
            role = requireRole(role);
            op = require(op, "op");
            unit = unit == null ? "" : unit;
            temporal = temporal == null ? TemporalConstraint.none() : temporal;
            requireFinite(value);
        }
    }

    record Range(
            String role,
            boolean inside,
            double min,
            double max,
            String unit,
            TemporalConstraint temporal
    ) implements RuleCondition {
        public Range {
            role = requireRole(role);
            unit = unit == null ? "" : unit;
            temporal = temporal == null ? TemporalConstraint.none() : temporal;
            requireFinite(min);
            requireFinite(max);
        }
    }

    record StateEquals(String role, String state, boolean negate) implements RuleCondition {
        public StateEquals {
            role = requireRole(role);
            state = state == null ? "" : state;
        }
    }

    record Rate(
            String role,
            CompareOp op,
            double value,
            String unit
    ) implements RuleCondition {
        public Rate {
            role = requireRole(role);
            op = require(op, "op");
            unit = unit == null ? "" : unit;
            requireFinite(value);
        }
    }

    record Delta(
            String leftRole,
            String rightRole,
            CompareOp op,
            double value,
            String unit,
            TemporalConstraint temporal
    ) implements RuleCondition {
        public Delta {
            leftRole = requireRole(leftRole);
            rightRole = requireRole(rightRole);
            op = require(op, "op");
            unit = unit == null ? "" : unit;
            temporal = temporal == null ? TemporalConstraint.none() : temporal;
            requireFinite(value);
        }
    }

    record Transition(String role, String fromState, String toState) implements RuleCondition {
        public Transition {
            role = requireRole(role);
            fromState = fromState == null ? "" : fromState;
            toState = toState == null ? "" : toState;
        }
    }

    record Threshold(
            String role,
            ThresholdOperator operator,
            double enter,
            Double hysteresisExit,
            String unit,
            TemporalConstraint temporal
    ) implements RuleCondition {
        public Threshold {
            role = requireRole(role);
            operator = require(operator, "operator");
            unit = unit == null ? "" : unit;
            temporal = temporal == null ? TemporalConstraint.none() : temporal;
            requireFinite(enter);
        }
    }

    record CrossSignalDifference(
            String leftRole,
            String rightRole,
            CompareOp op,
            double value,
            String unit,
            TemporalConstraint temporal
    ) implements RuleCondition {
        public CrossSignalDifference {
            leftRole = requireRole(leftRole);
            rightRole = requireRole(rightRole);
            op = require(op, "op");
            unit = unit == null ? "" : unit;
            temporal = temporal == null ? TemporalConstraint.none() : temporal;
            requireFinite(value);
        }
    }

    record AnalysisMetric(
            AnalysisType analysisType,
            List<String> roles,
            String metric,
            CompareOp op,
            double value
    ) implements RuleCondition {
        public AnalysisMetric {
            analysisType = require(analysisType, "analysisType");
            roles = List.copyOf(roles == null ? List.of() : roles);
            metric = metric == null ? "" : metric;
            op = require(op, "op");
            requireFinite(value);
        }
    }

    record SequenceStep(RuleCondition condition, long withinNanos) {
        public SequenceStep {
            condition = require(condition, "condition");
            if (withinNanos < 0L) {
                throw new IllegalArgumentException("withinNanos must be >= 0");
            }
        }
    }

    record Sequence(List<SequenceStep> steps) implements RuleCondition {
        public Sequence {
            steps = List.copyOf(steps == null ? List.of() : steps);
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("sequence requires steps");
            }
        }
    }

    record Logical(LogicalOp op, List<RuleCondition> children) implements RuleCondition {
        public Logical {
            op = require(op, "op");
            children = List.copyOf(children == null ? List.of() : children);
        }
    }

    private static String requireRole(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role required");
        }
        return role.trim();
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " required");
        }
        return value;
    }

    private static void requireFinite(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("threshold must be finite");
        }
    }
}
