package com.stellar.platform.analysis.investigation.report;

import com.stellar.platform.analysis.investigation.EngineeringFormats;
import com.stellar.platform.analysis.investigation.eval.EvaluationResult;

/** Report text copied from a frozen result. It does not run the rule again. */
public final class EvaluationReportRows {

    private EvaluationReportRows() {
    }

    public static EngineeringReportModel.EvaluationRow row(EvaluationResult result) {
        String time = result.endNanos() == null
                ? EngineeringFormats.seconds(result.startNanos())
                : EngineeringFormats.interval(result.startNanos(), result.endNanos());
        String procedure = result.procedureName()
                + (result.procedureVersion().isBlank() ? "" : " " + result.procedureVersion());
        String approval = result.approvalStatus()
                + (result.approvalReference().isBlank() ? "" : " — " + result.approvalReference());
        return new EngineeringReportModel.EvaluationRow(
                result.eventName(),
                procedure,
                result.ruleId() + " version " + result.ruleVersion(),
                approval,
                result.expectedText(),
                result.actualText().isBlank() ? "Not calculated." : result.actualText(),
                result.unit(),
                result.coverageText(),
                result.methodText(),
                result.outcome().label(),
                result.evidenceSource(),
                time,
                result.reason(),
                result.detail(),
                result.exploratory());
    }
}
