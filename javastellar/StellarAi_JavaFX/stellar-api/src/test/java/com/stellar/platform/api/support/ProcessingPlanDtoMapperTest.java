package com.stellar.platform.api.support;

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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingPlanDtoMapperTest {

    @Test
    void roundTripPreservesAutoAndCustomJobFields() {
        ProcessingJob autoJob = new ProcessingJob(
                ProcessingJobKey.of("src-auto", 0),
                MeasurementSourceFormat.ASC,
                MappingLifecycle.CONFIRMED,
                "def-1",
                "vehicle.dbc",
                10L,
                8L,
                2L,
                100L,
                0.8,
                80L,
                0.8,
                new ObservedTimingMetadata(1L, 2L, "abs", "meta"),
                true,
                List.of(ProcessingPlanDiagnostic.of(
                        ProcessingPlanDiagnosticCode.EMPTY_SELECTION,
                        SourceChannelRef.of("src-auto", 0),
                        "note"))
        );
        ProcessingJob customJob = new ProcessingJob(
                ProcessingJobKey.of("src-custom", 3),
                MeasurementSourceFormat.ASC,
                MappingLifecycle.MANUAL,
                "def-2",
                "custom.dbc",
                5L,
                5L,
                0L,
                50L,
                1.0,
                50L,
                1.0,
                null,
                true,
                List.of()
        );
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "plan-1",
                Instant.parse("2026-01-01T00:00:00Z"),
                List.of(autoJob, customJob),
                ProcessingPlanReadiness.READY,
                List.of()
        );

        ProcessingPlanDto dto = ProcessingPlanDtoMapper.toDto(plan);
        ProjectProcessingPlan restored = ProcessingPlanDtoMapper.toDomain(dto);

        assertEquals(plan.planId(), restored.planId());
        assertEquals(plan.createdAt(), restored.createdAt());
        assertEquals(plan.readiness(), restored.readiness());
        assertEquals(2, restored.jobs().size());

        ProcessingJobDto first = dto.jobs().getFirst();
        assertEquals("src-auto", first.projectSourceId());
        assertEquals(0, first.channelId());
        assertEquals("CONFIRMED", first.mappingLifecycle());
        assertEquals("def-1", first.definitionSourceId());

        ProcessingJobDto second = dto.jobs().get(1);
        assertEquals("src-custom", second.projectSourceId());
        assertEquals(3, second.channelId());
        assertEquals("MANUAL", second.mappingLifecycle());
        assertEquals("def-2", second.definitionSourceId());

        assertEquals(autoJob.frameCount(), restored.jobs().getFirst().frameCount());
        assertEquals(customJob.mappingLifecycle(), restored.jobs().get(1).mappingLifecycle());
        assertTrue(restored.jobs().getFirst().observedTiming().isPresent());
    }

    @Test
    void roundTripPreservesNativeOnlyNoMatchWithoutManufacturingADefinitionId() {
        ProcessingJob nativeOnly = new ProcessingJob(
                ProcessingJobKey.of("src-native", 1),
                MeasurementSourceFormat.MF4,
                MappingLifecycle.NO_MATCH,
                null,
                "",
                3L, 0L, 3L, 71352L, 0.0, 0L, 0.0,
                null, true, List.of(ProcessingPlanDiagnostic.of(
                        ProcessingPlanDiagnosticCode.NO_EFFECTIVE_DEFINITION,
                        SourceChannelRef.of("src-native", 1),
                        "no matching DBC; native/raw processing only"))
        );
        ProjectProcessingPlan plan = new ProjectProcessingPlan(
                "plan-native",
                Instant.parse("2026-03-05T11:54:48Z"),
                List.of(nativeOnly),
                ProcessingPlanReadiness.READY,
                nativeOnly.diagnostics()
        );

        String json = ProcessingPlanDtoMapper.toJson(plan);
        ProjectProcessingPlan restored = ProcessingPlanDtoMapper.toDomain(
                ProcessingPlanDtoMapper.toDto(plan));

        assertTrue(json.contains("\"executable\":true"));
        assertTrue(json.contains("\"mappingLifecycle\":\"NO_MATCH\""));
        ProcessingJob job = restored.jobs().getFirst();
        assertEquals(ProcessingPlanReadiness.READY, restored.readiness());
        assertTrue(job.executable());
        assertEquals(MappingLifecycle.NO_MATCH, job.mappingLifecycle());
        assertTrue(job.definitionSourceId().isEmpty());
        assertEquals("", ProcessingPlanDtoMapper.toDto(job).definitionSourceId());
    }

    @Test
    void diagnosticRoundTrip() {
        ProcessingPlanDiagnosticDto dto = new ProcessingPlanDiagnosticDto(
                "EMPTY_SELECTION", "src", 1, "detail");
        ProcessingPlanDiagnostic domain = ProcessingPlanDtoMapper.toDomain(dto);
        ProcessingPlanDiagnosticDto again = ProcessingPlanDtoMapper.toDto(domain);
        assertEquals(dto.code(), again.code());
        assertEquals(dto.projectSourceId(), again.projectSourceId());
        assertEquals(dto.channelId(), again.channelId());
        assertEquals(dto.detail(), again.detail());
    }
}
