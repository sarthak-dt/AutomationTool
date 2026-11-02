package com.stellar.platform.api.support;

import com.stellar.platform.api.dto.IndependentJobResultDto;
import com.stellar.platform.api.dto.ProcessingPlanDiagnosticDto;
import com.stellar.platform.api.dto.ProcessingRunResultDto;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;

import java.util.ArrayList;
import java.util.List;

/** Maps executor batch results to transport DTOs (catalog handles omitted). */
public final class ProcessingRunResultMapper {

    private ProcessingRunResultMapper() {
    }

    public static ProcessingRunResultDto toDto(
            String projectId,
            String runId,
            MultiSourceProcessingResult batch,
            String message
    ) {
        List<IndependentJobResultDto> jobs = new ArrayList<>();
        for (IndependentJobResult result : batch.jobResults()) {
            jobs.add(toDto(result));
        }
        List<ProcessingPlanDiagnosticDto> diagnostics = new ArrayList<>();
        for (var d : batch.diagnostics()) {
            diagnostics.add(ProcessingPlanDtoMapper.toDto(d));
        }
        return new ProcessingRunResultDto(
                projectId,
                runId,
                batch.planId(),
                batch.status().name(),
                batch.createdAt(),
                batch.elapsed().toMillis(),
                jobs,
                diagnostics,
                message == null ? "" : message
        );
    }

    public static IndependentJobResultDto toDto(IndependentJobResult result) {
        String detail = result.technicalDetail() == null ? "" : result.technicalDetail();
        boolean reused = detail.toLowerCase().contains("reused");
        return new IndependentJobResultDto(
                result.projectSourceId(),
                result.channelId(),
                result.definitionSourceId().orElse(""),
                result.definitionDisplayName(),
                result.mappingLifecycle().name(),
                result.status().name(),
                result.expectedSelectedChannelFrames(),
                result.sourceFramesRead(),
                result.selectedChannelFrames(),
                result.dbcMatchedFrames(),
                result.dbcUnmatchedFrames(),
                result.signalsProduced(),
                result.firstSampleTimestampNanos().orElse(null),
                result.lastSampleTimestampNanos().orElse(null),
                result.observedSourceTiming().map(ProcessingPlanDtoMapper::toDto).orElse(null),
                result.elapsed().toMillis(),
                result.diagnostics().stream().map(ProcessingPlanDtoMapper::toDto).toList(),
                result.technicalDetail(),
                reused
        );
    }
}
