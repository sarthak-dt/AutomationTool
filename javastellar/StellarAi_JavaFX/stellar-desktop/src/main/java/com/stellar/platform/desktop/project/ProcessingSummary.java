package com.stellar.platform.desktop.project;

import com.stellar.platform.processing.AggregatedDiagnostic;
import com.stellar.platform.processing.ProcessingStatistics;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Latest completed (or failed/cancelled) processing summary retained by the workspace.
 */
public record ProcessingSummary(
        String sourceFormat,
        Path sourceFile,
        Path dbcFile,
        ProcessingStatistics statistics,
        Duration elapsed,
        ProjectProcessingStatus completionStatus,
        List<AggregatedDiagnostic> diagnostics,
        String userMessage,
        String technicalDetail,
        boolean recoveryUsed,
        String recoveryState,
        String mf4FileStatus,
        String selectedChannelsSummary,
        long selectedMatchedIds
) {

    public ProcessingSummary {
        Objects.requireNonNull(sourceFormat, "sourceFormat");
        Objects.requireNonNull(sourceFile, "sourceFile");
        Objects.requireNonNull(dbcFile, "dbcFile");
        Objects.requireNonNull(statistics, "statistics");
        Objects.requireNonNull(elapsed, "elapsed");
        Objects.requireNonNull(completionStatus, "completionStatus");
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        userMessage = userMessage == null ? "" : userMessage;
        technicalDetail = technicalDetail == null ? "" : technicalDetail;
        recoveryState = recoveryState == null ? "" : recoveryState;
        mf4FileStatus = mf4FileStatus == null ? "" : mf4FileStatus;
        selectedChannelsSummary = selectedChannelsSummary == null ? "" : selectedChannelsSummary;
        selectedMatchedIds = Math.max(0L, selectedMatchedIds);
    }

    /** Backward-compatible constructor for tests that omit selection summary. */
    public ProcessingSummary(
            String sourceFormat,
            Path sourceFile,
            Path dbcFile,
            ProcessingStatistics statistics,
            Duration elapsed,
            ProjectProcessingStatus completionStatus,
            List<AggregatedDiagnostic> diagnostics,
            String userMessage,
            String technicalDetail,
            boolean recoveryUsed,
            String recoveryState,
            String mf4FileStatus
    ) {
        this(
                sourceFormat, sourceFile, dbcFile, statistics, elapsed, completionStatus, diagnostics,
                userMessage, technicalDetail, recoveryUsed, recoveryState, mf4FileStatus, "", 0L
        );
    }
}
