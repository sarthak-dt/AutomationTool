package com.stellar.platform.analysis.investigation;

/** Whether Generate HTML Report has a saved investigation to bind to. */
public final class ReportAvailability {

    private ReportAvailability() {
    }

    public static boolean canGenerate(EngineeringInvestigation investigation) {
        return investigation != null
                && investigation.investigationId() != null
                && !investigation.investigationId().isBlank()
                && investigation.projectId() != null
                && !investigation.projectId().isBlank();
    }
}
