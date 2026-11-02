package com.stellar.platform.api.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.stellar.platform.api.ProcessingApi;
import com.stellar.platform.api.dto.JobDto;
import com.stellar.platform.api.dto.PlanPreviewDto;
import com.stellar.platform.api.dto.PlanPreviewRequest;
import com.stellar.platform.api.dto.ProcessJobRequest;
import com.stellar.platform.api.dto.ProcessResultDto;
import com.stellar.platform.api.dto.ProcessingRunRequest;
import com.stellar.platform.api.dto.ProcessingRunResultDto;

import java.util.List;
import java.util.Optional;

public final class HttpProcessingApi implements ProcessingApi {

    private final HttpTransport transport;

    public HttpProcessingApi(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public PlanPreviewDto previewPlan(String projectId, PlanPreviewRequest request) {
        return transport.post(
                "/api/v1/projects/" + HttpTransport.enc(projectId) + "/plans/preview",
                request,
                PlanPreviewDto.class
        );
    }

    @Override
    public JobDto startProcessJob(String projectId, ProcessJobRequest request) {
        return transport.post(
                "/api/v1/projects/" + HttpTransport.enc(projectId) + "/process/jobs",
                request,
                JobDto.class
        );
    }

    @Override
    public JobDto startProcessingRun(String projectId, ProcessingRunRequest request) {
        return transport.post(
                "/api/v1/projects/" + HttpTransport.enc(projectId) + "/processing-runs",
                request,
                JobDto.class
        );
    }

    @Override
    public JobDto getProcessJob(String projectId, String jobId) {
        return transport.get(
                "/api/v1/projects/" + HttpTransport.enc(projectId) + "/process/jobs/" + HttpTransport.enc(jobId),
                JobDto.class
        );
    }

    @Override
    public JobDto cancelProcessJob(String projectId, String jobId) {
        return transport.post(
                "/api/v1/projects/" + HttpTransport.enc(projectId) + "/process/jobs/"
                        + HttpTransport.enc(jobId) + "/cancel",
                null,
                JobDto.class
        );
    }

    @Override
    public List<ProcessResultDto> listProcessResults(String projectId) {
        return transport.get(
                "/api/v1/projects/" + HttpTransport.enc(projectId) + "/process/results",
                new TypeReference<>() {
                }
        );
    }

    @Override
    public Optional<ProcessResultDto> getProcessResult(String projectId, String jobId) {
        try {
            return Optional.ofNullable(transport.get(
                    "/api/v1/projects/" + HttpTransport.enc(projectId) + "/process/results/" + HttpTransport.enc(jobId),
                    ProcessResultDto.class
            ));
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public Optional<ProcessingRunResultDto> getProcessingRunResult(String projectId, String runId) {
        try {
            return Optional.ofNullable(transport.get(
                    "/api/v1/projects/" + HttpTransport.enc(projectId) + "/processing-runs/" + HttpTransport.enc(runId),
                    ProcessingRunResultDto.class
            ));
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
