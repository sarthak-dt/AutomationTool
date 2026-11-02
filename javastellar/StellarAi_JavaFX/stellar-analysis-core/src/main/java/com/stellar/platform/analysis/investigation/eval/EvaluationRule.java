package com.stellar.platform.analysis.investigation.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * One immutable version of an evaluation rule. A later edit is a new version.
 * The rule does not change the engineer event it may later be applied to.
 */
public record EvaluationRule(
        String ruleId,
        int version,
        String procedureName,
        String procedureVersion,
        String eventCategory,
        List<String> requiredSignals,
        String sourceId,
        ComparisonOperator operator,
        Double expectedValue,
        Double expectedLow,
        Double expectedHigh,
        String expectedState,
        String unit,
        BoundaryPolicy lowBoundary,
        BoundaryPolicy highBoundary,
        Double tolerance,
        Long maxGapNanos,
        Double minCoverage,
        RuleApproval approval,
        String approver,
        String approvalReference,
        String createdAt,
        String modifiedAt
) {
    public static final String CONVERSION_POLICY = "AS_RECORDED";
    public static final String SAMPLING_POLICY = "STORED_SAMPLES";
    public static final String MISSING_DATA_POLICY = "EXCLUDE_FROM_CALCULATION";
    public static final String INTERVAL_POLICY = "EVENT";
    public static final String METHOD_VERSION = "1";

    public EvaluationRule {
        ruleId = ruleId == null ? "" : ruleId;
        procedureName = text(procedureName);
        procedureVersion = text(procedureVersion);
        eventCategory = text(eventCategory);
        requiredSignals = copy(requiredSignals);
        sourceId = sourceId == null ? "" : sourceId;
        operator = operator == null ? ComparisonOperator.MINIMUM_WITHIN_RANGE : operator;
        expectedState = text(expectedState);
        unit = text(unit);
        lowBoundary = lowBoundary == null ? BoundaryPolicy.INCLUSIVE : lowBoundary;
        highBoundary = highBoundary == null ? BoundaryPolicy.INCLUSIVE : highBoundary;
        approval = approval == null ? RuleApproval.DRAFT : approval;
        approver = text(approver);
        approvalReference = text(approvalReference);
        createdAt = text(createdAt);
        modifiedAt = text(modifiedAt);
        if (approval == RuleApproval.APPROVED && (approver.isBlank() || approvalReference.isBlank())) {
            approval = RuleApproval.DRAFT;
        }
    }

    public String calculationMethod() {
        return operator.methodId();
    }

    public String calculationVersion() {
        return METHOD_VERSION;
    }

    public boolean exploratory() {
        return approval != RuleApproval.APPROVED;
    }

    public EvaluationRule withIdentity(String id, int ruleVersion, String created, String modified) {
        return new EvaluationRule(id, ruleVersion, procedureName, procedureVersion, eventCategory, requiredSignals,
                sourceId, operator, expectedValue, expectedLow, expectedHigh, expectedState, unit,
                lowBoundary, highBoundary, tolerance, maxGapNanos, minCoverage, approval, approver,
                approvalReference, created, modified);
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }

    private static List<String> copy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                out.add(value.strip());
            }
        }
        return List.copyOf(out);
    }

    public enum ComparisonOperator {
        MINIMUM_WITHIN_RANGE("stored-sample-min"),
        MAXIMUM_WITHIN_RANGE("stored-sample-max"),
        POINT_SAMPLE("stored-point-sample"),
        STATE_EQUALS("stored-state-equals"),
        THRESHOLD_OVER_INTERVAL("stored-threshold-coverage");

        private final String methodId;

        ComparisonOperator(String methodId) {
            this.methodId = methodId;
        }

        public String methodId() {
            return methodId;
        }

        public boolean window() {
            return this != POINT_SAMPLE;
        }

        public static ComparisonOperator parse(String text) {
            if (text == null || text.isBlank()) {
                return MINIMUM_WITHIN_RANGE;
            }
            for (ComparisonOperator operator : values()) {
                if (operator.name().equals(text) || operator.methodId.equals(text)) {
                    return operator;
                }
            }
            return MINIMUM_WITHIN_RANGE;
        }
    }

    public enum BoundaryPolicy {
        INCLUSIVE, EXCLUSIVE;

        public static BoundaryPolicy parse(String text) {
            return "EXCLUSIVE".equals(text) ? EXCLUSIVE : INCLUSIVE;
        }
    }

    public enum RuleApproval {
        DRAFT("Draft"),
        APPROVED("Approved");

        private final String label;

        RuleApproval(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static RuleApproval parse(String text) {
            return "APPROVED".equals(text) || "Approved".equals(text) ? APPROVED : DRAFT;
        }
    }
}
