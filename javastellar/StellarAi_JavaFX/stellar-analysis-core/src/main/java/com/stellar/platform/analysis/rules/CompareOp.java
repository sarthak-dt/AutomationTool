package com.stellar.platform.analysis.rules;

import com.stellar.platform.analysis.model.ThresholdOperator;

public enum CompareOp {
    GT, GTE, LT, LTE, EQ, NEQ;

    public boolean matches(double left, double right) {
        return switch (this) {
            case GT -> left > right;
            case GTE -> left >= right;
            case LT -> left < right;
            case LTE -> left <= right;
            case EQ -> left == right;
            case NEQ -> left != right;
        };
    }

    public ThresholdOperator toThresholdOperator() {
        return switch (this) {
            case GT -> ThresholdOperator.GREATER_THAN;
            case GTE -> ThresholdOperator.GREATER_OR_EQUAL;
            case LT -> ThresholdOperator.LESS_THAN;
            case LTE -> ThresholdOperator.LESS_OR_EQUAL;
            case EQ -> ThresholdOperator.GREATER_OR_EQUAL;
            case NEQ -> ThresholdOperator.GREATER_THAN;
        };
    }

    public static CompareOp parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("operator required");
        }
        return switch (raw.trim()) {
            case ">", "GT" -> GT;
            case ">=", "GTE" -> GTE;
            case "<", "LT" -> LT;
            case "<=", "LTE" -> LTE;
            case "==", "EQ" -> EQ;
            case "!=", "NEQ" -> NEQ;
            default -> throw new IllegalArgumentException("Unknown operator: " + raw);
        };
    }

    public String symbol() {
        return switch (this) {
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            case EQ -> "==";
            case NEQ -> "!=";
        };
    }
}
