package com.stellar.platform.analysis.investigation.eval;

/**
 * Result frozen against one rule version. Later rule versions do not change this record.
 */
public record EvaluationResult(
        String resultId,
        String eventId,
        String eventName,
        String ruleId,
        int ruleVersion,
        String procedureName,
        String procedureVersion,
        String approvalStatus,
        boolean exploratory,
        String approver,
        String approvalReference,
        String expectedText,
        String actualText,
        String unit,
        String coverageText,
        String methodText,
        EvaluationOutcome outcome,
        String reason,
        String detail,
        String evidenceSource,
        long startNanos,
        Long endNanos,
        String sourceFile,
        String createdAt
) {
    public EvaluationResult {
        resultId = text(resultId);
        eventId = text(eventId);
        eventName = text(eventName);
        ruleId = text(ruleId);
        procedureName = text(procedureName);
        procedureVersion = text(procedureVersion);
        approvalStatus = text(approvalStatus).isBlank() ? "Draft" : text(approvalStatus);
        approver = text(approver);
        approvalReference = text(approvalReference);
        expectedText = text(expectedText);
        actualText = text(actualText);
        unit = text(unit);
        coverageText = text(coverageText);
        methodText = text(methodText);
        outcome = outcome == null ? EvaluationOutcome.NOT_EVALUATED : outcome;
        reason = text(reason);
        detail = text(detail);
        evidenceSource = text(evidenceSource);
        sourceFile = text(sourceFile);
        createdAt = text(createdAt);
    }

    public EvaluationResult withId(String id, String created) {
        return new EvaluationResult(id, eventId, eventName, ruleId, ruleVersion, procedureName, procedureVersion,
                approvalStatus, exploratory, approver, approvalReference, expectedText, actualText, unit,
                coverageText, methodText, outcome, reason, detail, evidenceSource, startNanos, endNanos,
                sourceFile, created);
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
