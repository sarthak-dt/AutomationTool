package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProjectExecutionStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickDecodeSourceResultAssemblerTest {

    @Test
    void groupsChannelsByStableSourceAndKeepsMultipleDbcsVisible() {
        MultiSourceProcessingResult batch = new MultiSourceProcessingResult(
                "plan", Instant.now(), ProjectExecutionStatus.SUCCESS,
                List.of(
                        result("source-A", 1, "dbc-1", "body.dbc", JobExecutionStatus.SUCCESS,
                                100, 80, 1_000, new Object()),
                        result("source-A", 2, "dbc-2", "powertrain.dbc", JobExecutionStatus.SUCCESS_WITH_WARNINGS,
                                200, 120, 2_000, new Object()),
                        result("source-A", 3, "dbc-1", "body.dbc", JobExecutionStatus.SUCCESS,
                                300, 300, 3_000, new Object()),
                        result("source-B", 1, "dbc-2", "powertrain.dbc", JobExecutionStatus.SUCCESS,
                                50, 45, 500, new Object())
                ), List.of(), Duration.ofSeconds(1));

        List<QuickDecodeSourceResultAssembler.SourceResult> sources =
                new QuickDecodeSourceResultAssembler().assemble(batch);

        assertEquals(2, sources.size());
        QuickDecodeSourceResultAssembler.SourceResult sourceA = sources.getFirst();
        assertEquals("source-A", sourceA.projectSourceId());
        assertEquals(3, sourceA.channelCount());
        assertEquals(3, sourceA.successfulChannels());
        assertEquals(1, sourceA.warningChannels());
        assertEquals(0, sourceA.failedChannels());
        assertEquals(600, sourceA.selectedFrames());
        assertEquals(500, sourceA.dbcMatchedFrames());
        assertEquals(6_000, sourceA.decodedSamples());
        assertEquals(2, sourceA.definitions().size());
        assertEquals("body.dbc", sourceA.definitions().get(0).displayName());
        assertEquals(2, sourceA.definitions().get(0).channelCount());
        assertEquals("powertrain.dbc", sourceA.definitions().get(1).displayName());
        assertEquals(1, sourceA.definitions().get(1).channelCount());
        assertEquals("COMPLETED", sourceA.resultLabel());
        assertEquals("badge-ready", sourceA.resultStyle());
        assertTrue(sourceA.hasSignalCatalog());
    }

    @Test
    void duplicateSourceFilenamesCannotMergeBecauseGroupingUsesProjectSourceId() {
        MultiSourceProcessingResult batch = new MultiSourceProcessingResult(
                "plan", Instant.now(), ProjectExecutionStatus.SUCCESS,
                List.of(
                        result("stable-source-left", 1, "dbc", "same.dbc", JobExecutionStatus.SUCCESS,
                                10, 10, 10, new Object()),
                        result("stable-source-right", 1, "dbc", "same.dbc", JobExecutionStatus.SUCCESS,
                                20, 20, 20, new Object())
                ), List.of(), Duration.ZERO);

        List<QuickDecodeSourceResultAssembler.SourceResult> sources =
                new QuickDecodeSourceResultAssembler().assemble(batch);

        assertEquals(2, sources.size());
        assertEquals("stable-source-left", sources.get(0).projectSourceId());
        assertEquals("stable-source-right", sources.get(1).projectSourceId());
    }

    private static IndependentJobResult result(
            String sourceId,
            int channel,
            String definitionId,
            String definitionName,
            JobExecutionStatus status,
            long selectedFrames,
            long matchedFrames,
            long decodedSamples,
            Object catalog
    ) {
        return new IndependentJobResult(
                ProcessingJobKey.of(sourceId, channel),
                definitionId,
                definitionName,
                MappingLifecycle.RECOMMENDED_FALLBACK,
                status,
                selectedFrames,
                selectedFrames,
                selectedFrames,
                matchedFrames,
                Math.max(0, selectedFrames - matchedFrames),
                decodedSamples,
                1L,
                2L,
                null,
                Duration.ofMillis(5),
                List.of(),
                "",
                catalog);
    }
}
