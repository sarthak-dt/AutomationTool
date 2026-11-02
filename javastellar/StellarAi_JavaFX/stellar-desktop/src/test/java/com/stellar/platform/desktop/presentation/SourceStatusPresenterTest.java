package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceStatusPresenterTest {

    @Test
    void readyWithoutJobStaysReady() {
        SourceStatusPresenter.View view = SourceStatusPresenter.present(SourceStatus.READY, null);
        assertEquals("READY", view.badgeText());
        assertEquals("Ready", view.footerLabel());
        assertEquals("status-ready", view.styleClass());
    }

    @Test
    void successfulJobIsProcessedEvenWhenInventoryLooksEmpty() {
        SourceStatusPresenter.View view = SourceStatusPresenter.present(
                SourceStatus.READY, JobExecutionStatus.SUCCESS);
        assertEquals("COMPLETED", view.badgeText());
        assertEquals("COMPLETED", view.footerLabel());
        assertEquals("status-processed", view.styleClass());
    }

    @Test
    void informationalDiagnosticsStayCompleted() {
        SourceStatusPresenter.View view = SourceStatusPresenter.present(
                SourceStatus.PROCESSED, JobExecutionStatus.SUCCESS_WITH_WARNINGS);
        assertEquals("COMPLETED", view.badgeText());
        assertEquals("COMPLETED", view.footerLabel());
        assertEquals("status-processed", view.styleClass());
    }

    @Test
    void runningJobIsProcessing() {
        SourceStatusPresenter.View view = SourceStatusPresenter.present(
                SourceStatus.READY, JobExecutionStatus.RUNNING);
        assertEquals("PROCESSING", view.badgeText());
        assertEquals("status-processing", view.styleClass());
    }

    @Test
    void failedJobIsFailed() {
        SourceStatusPresenter.View view = SourceStatusPresenter.present(
                SourceStatus.READY, JobExecutionStatus.FAILED);
        assertEquals("ERROR", view.badgeText());
        assertEquals("ERROR", view.footerLabel());
        assertEquals("status-failed", view.styleClass());
    }

    @Test
    void queuedDoesNotHideStoredSuccess() {
        assertEquals(JobExecutionStatus.SUCCESS,
                SourceStatusPresenter.prefer(JobExecutionStatus.SUCCESS, JobExecutionStatus.QUEUED));
    }
}
