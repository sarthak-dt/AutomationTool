package com.stellar.platform.server.web;

import com.stellar.platform.api.StellarPlatformClient;
import com.stellar.platform.api.dto.JobDto;
import com.stellar.platform.api.dto.PlanPreviewDto;
import com.stellar.platform.api.dto.PlanPreviewRequest;
import com.stellar.platform.api.dto.ProcessJobRequest;
import com.stellar.platform.api.dto.ProcessResultDto;
import com.stellar.platform.api.dto.ProcessingRunRequest;
import com.stellar.platform.api.dto.ProcessingRunResultDto;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

@RestController
@Profile("!decoder")
@RequestMapping("/api/v1/projects/{projectId}")
public class ProcessController {

    private final StellarPlatformClient client;

    public ProcessController(StellarPlatformClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @PostMapping("/plans/preview")
    public PlanPreviewDto preview(@PathVariable("projectId") String projectId, @RequestBody PlanPreviewRequest request) {
        return client.processing().previewPlan(projectId, request);
    }

    @PostMapping("/process/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobDto start(@PathVariable("projectId") String projectId, @RequestBody ProcessJobRequest request) {
        return client.processing().startProcessJob(projectId, request);
    }

    @PostMapping("/processing-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobDto startRun(
            @PathVariable("projectId") String projectId,
            @RequestBody ProcessingRunRequest request
    ) {
        return client.processing().startProcessingRun(projectId, request);
    }

    @GetMapping("/processing-runs/{runId}")
    public ProcessingRunResultDto getRun(
            @PathVariable("projectId") String projectId,
            @PathVariable("runId") String runId
    ) {
        return client.processing().getProcessingRunResult(projectId, runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Processing run result not found"));
    }

    @GetMapping("/process/jobs/{jobId}")
    public JobDto getJob(@PathVariable("projectId") String projectId, @PathVariable("jobId") String jobId) {
        return client.processing().getProcessJob(projectId, jobId);
    }

    @PostMapping("/process/jobs/{jobId}/cancel")
    public JobDto cancel(@PathVariable("projectId") String projectId, @PathVariable("jobId") String jobId) {
        return client.processing().cancelProcessJob(projectId, jobId);
    }

    @GetMapping("/process/results")
    public List<ProcessResultDto> listResults(@PathVariable("projectId") String projectId) {
        return client.processing().listProcessResults(projectId);
    }

    @GetMapping("/process/results/{jobId}")
    public ProcessResultDto getResult(@PathVariable("projectId") String projectId, @PathVariable("jobId") String jobId) {
        return client.processing().getProcessResult(projectId, jobId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Process result not found"));
    }
}
