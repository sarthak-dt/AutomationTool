package com.stellar.platform.api.dto;

/** Transport form of {@link com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic}. */
public record ProcessingPlanDiagnosticDto(
        String code,
        String projectSourceId,
        Integer channelId,
        String detail
) {
    public ProcessingPlanDiagnosticDto {
        code = code == null ? "" : code;
        projectSourceId = projectSourceId == null ? "" : projectSourceId;
        detail = detail == null ? "" : detail;
    }
}
