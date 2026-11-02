package com.stellar.platform.api.support;

import com.stellar.platform.api.dto.ObservedTimingDto;
import com.stellar.platform.api.dto.ProcessingJobDto;
import com.stellar.platform.api.dto.ProcessingPlanDiagnosticDto;
import com.stellar.platform.api.dto.ProcessingPlanDto;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ObservedTimingMetadata;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic bidirectional mapping between {@link ProjectProcessingPlan} and
 * {@link ProcessingPlanDto}. No Local/Remote business-rule forks.
 */
public final class ProcessingPlanDtoMapper {

    private static final ObjectMapper JSON = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ProcessingPlanDtoMapper() {
    }

    public static String toJson(ProjectProcessingPlan plan) {
        try {
            return JSON.writeValueAsString(toDto(plan));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize processing plan", e);
        }
    }

    public static ProcessingPlanDto toDto(ProjectProcessingPlan plan) {
        if (plan == null) {
            throw new IllegalArgumentException("plan must not be null");
        }
        List<ProcessingJobDto> jobs = new ArrayList<>(plan.jobs().size());
        for (ProcessingJob job : plan.jobs()) {
            jobs.add(toDto(job));
        }
        List<ProcessingPlanDiagnosticDto> diagnostics = new ArrayList<>(plan.diagnostics().size());
        for (ProcessingPlanDiagnostic d : plan.diagnostics()) {
            diagnostics.add(toDto(d));
        }
        return new ProcessingPlanDto(
                plan.planId(),
                plan.createdAt(),
                plan.readiness().name(),
                jobs,
                diagnostics
        );
    }

    public static ProjectProcessingPlan toDomain(ProcessingPlanDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("dto must not be null");
        }
        List<ProcessingJob> jobs = new ArrayList<>(dto.jobs().size());
        for (ProcessingJobDto job : dto.jobs()) {
            jobs.add(toDomain(job));
        }
        List<ProcessingPlanDiagnostic> diagnostics = new ArrayList<>(dto.diagnostics().size());
        for (ProcessingPlanDiagnosticDto d : dto.diagnostics()) {
            diagnostics.add(toDomain(d));
        }
        ProcessingPlanReadiness readiness;
        try {
            readiness = ProcessingPlanReadiness.valueOf(dto.readiness());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Unknown plan readiness: " + dto.readiness(), ex);
        }
        return new ProjectProcessingPlan(
                dto.planId().isBlank() ? null : dto.planId(),
                dto.createdAt(),
                jobs,
                readiness,
                diagnostics
        );
    }

    public static ProcessingJobDto toDto(ProcessingJob job) {
        return new ProcessingJobDto(
                job.projectSourceId(),
                job.channelId(),
                job.sourceFormat().name(),
                job.mappingLifecycle().name(),
                job.definitionSourceId().orElse(""),
                job.definitionDisplayName(),
                job.observedCanIdentityCount(),
                job.matchedCanIdentityCount(),
                job.unmatchedCanIdentityCount(),
                job.frameCount(),
                job.coverageRatio(),
                job.expectedDbcMatchedFrameCount(),
                job.frameCoverageRatio(),
                job.observedTiming().map(ProcessingPlanDtoMapper::toDto).orElse(null),
                job.executable(),
                job.diagnostics().stream().map(ProcessingPlanDtoMapper::toDto).toList()
        );
    }

    public static ProcessingJob toDomain(ProcessingJobDto dto) {
        MeasurementSourceFormat format;
        try {
            format = MeasurementSourceFormat.valueOf(dto.sourceFormat());
        } catch (RuntimeException ex) {
            format = MeasurementSourceFormat.UNKNOWN;
        }
        MappingLifecycle lifecycle;
        try {
            lifecycle = MappingLifecycle.valueOf(dto.mappingLifecycle());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Unknown mappingLifecycle: " + dto.mappingLifecycle(), ex);
        }
        return new ProcessingJob(
                ProcessingJobKey.of(dto.projectSourceId(), dto.channelId()),
                format,
                lifecycle,
                dto.definitionSourceId().isBlank() ? null : dto.definitionSourceId(),
                dto.definitionDisplayName(),
                dto.observedCanIdentityCount(),
                dto.matchedCanIdentityCount(),
                dto.unmatchedCanIdentityCount(),
                dto.frameCount(),
                dto.coverageRatio(),
                dto.expectedDbcMatchedFrameCount(),
                dto.frameCoverageRatio(),
                dto.observedTiming() == null ? null : toDomain(dto.observedTiming()),
                dto.executable(),
                dto.diagnostics().stream().map(ProcessingPlanDtoMapper::toDomain).toList()
        );
    }

    public static ProcessingPlanDiagnosticDto toDto(ProcessingPlanDiagnostic diagnostic) {
        return new ProcessingPlanDiagnosticDto(
                diagnostic.code().name(),
                diagnostic.channel().map(SourceChannelRef::projectSourceId).orElse(""),
                diagnostic.channel().map(SourceChannelRef::channelId).orElse(null),
                diagnostic.detail()
        );
    }

    public static ProcessingPlanDiagnostic toDomain(ProcessingPlanDiagnosticDto dto) {
        ProcessingPlanDiagnosticCode code;
        try {
            code = ProcessingPlanDiagnosticCode.valueOf(dto.code());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Unknown diagnostic code: " + dto.code(), ex);
        }
        SourceChannelRef channel = null;
        if (dto.projectSourceId() != null && !dto.projectSourceId().isBlank() && dto.channelId() != null) {
            channel = SourceChannelRef.of(dto.projectSourceId(), dto.channelId());
        }
        return ProcessingPlanDiagnostic.of(code, channel, dto.detail());
    }

    public static ObservedTimingDto toDto(ObservedTimingMetadata timing) {
        return new ObservedTimingDto(
                timing.recordingStartNanos().orElse(null),
                timing.recordingEndNanos().orElse(null),
                timing.timeBaseLabel(),
                timing.absoluteTimeMetadata()
        );
    }

    public static ObservedTimingMetadata toDomain(ObservedTimingDto dto) {
        return new ObservedTimingMetadata(
                dto.recordingStartNanos(),
                dto.recordingEndNanos(),
                dto.timeBaseLabel(),
                dto.absoluteTimeMetadata()
        );
    }
}
