package com.stellar.platform.api.support;

import com.stellar.platform.api.dto.IndependentJobResultDto;
import com.stellar.platform.api.dto.ProcessingRunResultDto;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProjectExecutionStatus;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Rebuilds domain batch results from transport DTOs (catalog handles null). */
public final class ProcessingRunResultDomainMapper {

    private ProcessingRunResultDomainMapper() {
    }

    public static MultiSourceProcessingResult toDomain(ProcessingRunResultDto dto) {
        List<IndependentJobResult> jobs = new ArrayList<>();
        for (IndependentJobResultDto job : dto.jobResults()) {
            jobs.add(toDomain(job));
        }
        ProjectExecutionStatus status;
        try {
            status = ProjectExecutionStatus.valueOf(dto.status());
        } catch (RuntimeException ex) {
            status = ProjectExecutionStatus.FAILED;
        }
        List<com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic> diagnostics =
                dto.diagnostics().stream().map(ProcessingPlanDtoMapper::toDomain).toList();
        return new MultiSourceProcessingResult(
                dto.planId(),
                dto.createdAt(),
                status,
                jobs,
                diagnostics,
                Duration.ofMillis(dto.elapsedMillis())
        );
    }

    public static IndependentJobResult toDomain(IndependentJobResultDto dto) {
        JobExecutionStatus status;
        try {
            status = JobExecutionStatus.valueOf(dto.status());
        } catch (RuntimeException ex) {
            status = JobExecutionStatus.FAILED;
        }
        MappingLifecycle lifecycle;
        try {
            lifecycle = MappingLifecycle.valueOf(dto.mappingLifecycle());
        } catch (RuntimeException ex) {
            lifecycle = MappingLifecycle.UNMAPPED;
        }
        String technical = dto.technicalDetail();
        if (dto.reused() && (technical == null || technical.isBlank())) {
            technical = "reused without source read";
        }
        return new IndependentJobResult(
                ProcessingJobKey.of(dto.projectSourceId(), dto.channelId()),
                dto.definitionSourceId().isBlank() ? null : dto.definitionSourceId(),
                dto.definitionDisplayName(),
                lifecycle,
                status,
                dto.expectedSelectedChannelFrames(),
                dto.sourceFramesRead(),
                dto.selectedChannelFrames(),
                dto.dbcMatchedFrames(),
                dto.dbcUnmatchedFrames(),
                dto.signalsProduced(),
                dto.firstSampleTimestampNanos(),
                dto.lastSampleTimestampNanos(),
                dto.observedSourceTiming() == null ? null : ProcessingPlanDtoMapper.toDomain(dto.observedSourceTiming()),
                Duration.ofMillis(dto.elapsedMillis()),
                dto.diagnostics().stream().map(ProcessingPlanDtoMapper::toDomain).toList(),
                technical,
                null
        );
    }
}
