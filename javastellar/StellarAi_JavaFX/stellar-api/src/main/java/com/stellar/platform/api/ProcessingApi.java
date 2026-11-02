package com.stellar.platform.api;

import com.stellar.platform.api.dto.JobDto;
import com.stellar.platform.api.dto.PlanPreviewDto;
import com.stellar.platform.api.dto.PlanPreviewRequest;
import com.stellar.platform.api.dto.ProcessJobRequest;
import com.stellar.platform.api.dto.ProcessResultDto;
import com.stellar.platform.api.dto.ProcessingRunRequest;
import com.stellar.platform.api.dto.ProcessingRunResultDto;

import java.util.List;
import java.util.Optional;

/**
 * Decode/execute jobs wrapping the shared measurement decode pipeline and
 * multi-job {@code ProjectPlanExecutor} runs.
 */
public interface ProcessingApi {

    PlanPreviewDto previewPlan(String projectId, PlanPreviewRequest request);

    JobDto startProcessJob(String projectId, ProcessJobRequest request);

    /**
     * Execute a full {@link com.stellar.platform.domain.processing.plan.ProjectProcessingPlan}
     * (via {@link ProcessingRunRequest#plan()}) with Local/HTTP-identical semantics.
     */
    JobDto startProcessingRun(String projectId, ProcessingRunRequest request);

    JobDto getProcessJob(String projectId, String jobId);

    JobDto cancelProcessJob(String projectId, String jobId);

    List<ProcessResultDto> listProcessResults(String projectId);

    Optional<ProcessResultDto> getProcessResult(String projectId, String jobId);

    Optional<ProcessingRunResultDto> getProcessingRunResult(String projectId, String runId);
}
