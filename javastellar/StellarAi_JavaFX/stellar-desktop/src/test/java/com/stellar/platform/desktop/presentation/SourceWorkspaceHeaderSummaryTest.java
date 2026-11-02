package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceWorkspaceHeaderSummaryTest {

    @Test
    void processedWithWarningsWinsOverReady() {
        SourceStatusPresenter.View warnings = SourceStatusPresenter.present(
                SourceStatus.PROCESSED, JobExecutionStatus.SUCCESS_WITH_WARNINGS);
        SourceStatusPresenter.View ready = SourceStatusPresenter.present(SourceStatus.READY, null);
        assertEquals("COMPLETED",
                SourceWorkspaceHeaderSummary.rollup(List.of(warnings, ready, ready)));
    }

    @Test
    void processingWinsOverProcessed() {
        SourceStatusPresenter.View running = SourceStatusPresenter.present(
                SourceStatus.READY, JobExecutionStatus.RUNNING);
        SourceStatusPresenter.View processed = SourceStatusPresenter.present(
                SourceStatus.PROCESSED, JobExecutionStatus.SUCCESS);
        assertEquals("PROCESSING", SourceWorkspaceHeaderSummary.rollup(List.of(processed, running)));
    }

    @Test
    void allReadyStaysReady() {
        SourceStatusPresenter.View ready = SourceStatusPresenter.present(SourceStatus.READY, null);
        assertEquals("Ready", SourceWorkspaceHeaderSummary.rollup(List.of(ready, ready)));
    }
}
