package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ProcessingSummary;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.processing.AggregatedDiagnostic;
import com.stellar.platform.processing.ProcessingStage;
import com.stellar.platform.processing.ProcessingStatistics;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Presentation state for the Processing screen (thread-safe; UI binds via refresh).
 * <p>
 * Terminal statuses cannot be overwritten by late RUNNING/progress events.
 * Stage {@code COMPLETED} never coexists with status {@code RUNNING}.
 */
public final class ProcessingScreenModel {

    private final AtomicReference<ProcessingStage> stage = new AtomicReference<>(ProcessingStage.IDLE);
    private final AtomicReference<ProjectProcessingStatus> status =
            new AtomicReference<>(ProjectProcessingStatus.IDLE);
    private final AtomicReference<String> sourceFile = new AtomicReference<>("");
    private final AtomicReference<String> dbcFile = new AtomicReference<>("");
    private final AtomicReference<String> mode = new AtomicReference<>("LOCAL");
    private final AtomicReference<ProcessingStatistics> statistics =
            new AtomicReference<>(new ProcessingStatistics());
    private final AtomicReference<Duration> elapsed = new AtomicReference<>(Duration.ZERO);
    private final AtomicLong startedEpochMs = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> userMessage = new AtomicReference<>("");
    private final AtomicReference<String> technicalDetail = new AtomicReference<>("");
    private final AtomicReference<String> mf4FileStatus = new AtomicReference<>("");
    private final AtomicReference<String> recoveryState = new AtomicReference<>("NOT_REQUIRED");
    private final AtomicBoolean recoveryUsed = new AtomicBoolean(false);
    private final AtomicReference<String> selectedChannelsSummary = new AtomicReference<>("");
    private final AtomicLong selectedMatchedIds = new AtomicLong(0);
    private final List<AggregatedDiagnostic> diagnostics = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();

    public void clear() {
        synchronized (lock) {
            stage.set(ProcessingStage.IDLE);
            status.set(ProjectProcessingStatus.IDLE);
            sourceFile.set("");
            dbcFile.set("");
            mode.set("LOCAL");
            statistics.set(new ProcessingStatistics());
            elapsed.set(Duration.ZERO);
            startedEpochMs.set(0);
            running.set(false);
            userMessage.set("");
            technicalDetail.set("");
            mf4FileStatus.set("");
            recoveryState.set("NOT_REQUIRED");
            recoveryUsed.set(false);
            selectedChannelsSummary.set("");
            selectedMatchedIds.set(0);
            diagnostics.clear();
        }
    }

    public void resetForRun(String source, String dbc) {
        resetForRun(source, dbc, "", 0L);
    }

    public void resetForRun(String source, String dbc, String selectedChannels, long selectedMatched) {
        synchronized (lock) {
            stage.set(ProcessingStage.VALIDATING);
            status.set(ProjectProcessingStatus.RUNNING);
            sourceFile.set(source == null ? "" : source);
            dbcFile.set(dbc == null ? "" : dbc);
            mode.set("LOCAL");
            statistics.set(new ProcessingStatistics());
            elapsed.set(Duration.ZERO);
            startedEpochMs.set(System.currentTimeMillis());
            running.set(true);
            userMessage.set("");
            technicalDetail.set("");
            recoveryState.set("NOT_REQUIRED");
            recoveryUsed.set(false);
            selectedChannelsSummary.set(selectedChannels == null ? "" : selectedChannels);
            selectedMatchedIds.set(Math.max(0L, selectedMatched));
            diagnostics.clear();
        }
    }

    public void applyProgress(ProcessingStage newStage, ProcessingStatistics stats) {
        synchronized (lock) {
            if (isTerminal(status.get())) {
                // Late progress must not reopen a finished run (prevents COMPLETED + RUNNING).
                return;
            }
            if (newStage != null) {
                if (newStage == ProcessingStage.COMPLETED || newStage == ProcessingStage.CANCELLED
                        || newStage == ProcessingStage.FAILED) {
                    // Terminal stages are applied only via applySummary together with status.
                    stage.set(newStage == ProcessingStage.COMPLETED
                            ? ProcessingStage.FINALIZING
                            : newStage);
                } else {
                    stage.set(newStage);
                }
            }
            if (stats != null) {
                statistics.set(stats);
                if (stats.recoveredFrames() > 0 && !isTerminalRecovery(recoveryState.get())) {
                    recoveryUsed.set(true);
                    recoveryState.set("ACTIVE");
                }
            }
            if (startedEpochMs.get() > 0) {
                elapsed.set(Duration.ofMillis(System.currentTimeMillis() - startedEpochMs.get()));
            }
        }
    }

    public void applySummary(ProcessingSummary summary) {
        if (summary == null) {
            return;
        }
        synchronized (lock) {
            ProjectProcessingStatus completion = summary.completionStatus();
            status.set(completion);
            statistics.set(summary.statistics());
            elapsed.set(summary.elapsed());
            userMessage.set(summary.userMessage());
            technicalDetail.set(summary.technicalDetail());
            recoveryUsed.set(summary.recoveryUsed());
            recoveryState.set(normalizeRecoveryState(summary.recoveryState(), summary));
            mf4FileStatus.set(summary.mf4FileStatus());
            selectedChannelsSummary.set(summary.selectedChannelsSummary());
            selectedMatchedIds.set(summary.selectedMatchedIds());
            diagnostics.clear();
            diagnostics.addAll(summary.diagnostics());
            running.set(false);
            stage.set(stageForCompletion(completion));
        }
    }

    /**
     * Immutable coherent view for JavaFX refresh.
     */
    public ProcessingUiSnapshot snapshot() {
        synchronized (lock) {
            return new ProcessingUiSnapshot(
                    stage.get(),
                    status.get(),
                    sourceFile.get(),
                    dbcFile.get(),
                    mode.get(),
                    statistics.get().snapshot(),
                    elapsed.get(),
                    running.get(),
                    userMessage.get(),
                    technicalDetail.get(),
                    mf4FileStatus.get(),
                    recoveryState.get(),
                    recoveryUsed.get(),
                    List.copyOf(diagnostics),
                    selectedChannelsSummary.get(),
                    selectedMatchedIds.get()
            );
        }
    }

    public ProcessingStage stage() {
        return stage.get();
    }

    public ProjectProcessingStatus status() {
        return status.get();
    }

    public String sourceFile() {
        return sourceFile.get();
    }

    public String dbcFile() {
        return dbcFile.get();
    }

    public String mode() {
        return mode.get();
    }

    public ProcessingStatistics statistics() {
        return statistics.get();
    }

    public Duration elapsed() {
        return elapsed.get();
    }

    public boolean running() {
        return running.get();
    }

    public String userMessage() {
        return userMessage.get();
    }

    public String technicalDetail() {
        return technicalDetail.get();
    }

    public String mf4FileStatus() {
        return mf4FileStatus.get();
    }

    public String recoveryState() {
        return recoveryState.get();
    }

    public boolean recoveryUsed() {
        return recoveryUsed.get();
    }

    public List<AggregatedDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public void setMf4Hints(String finalization, boolean recoveryCandidate) {
        synchronized (lock) {
            if (isTerminal(status.get())) {
                return;
            }
            if (finalization != null && !finalization.isBlank()) {
                mf4FileStatus.set(finalization);
            }
            if (recoveryCandidate) {
                recoveryUsed.set(true);
                if (!isTerminalRecovery(recoveryState.get())) {
                    recoveryState.set("ACTIVE");
                }
            }
        }
    }

    static boolean isTerminal(ProjectProcessingStatus status) {
        if (status == null) {
            return false;
        }
        return switch (status) {
            case COMPLETED, COMPLETED_WITH_WARNINGS, NO_CAN_DATA, RECOVERY_FAILED,
                    UNSUPPORTED_STRUCTURE, FAILED, CANCELLED -> true;
            default -> false;
        };
    }

    private static boolean isTerminalRecovery(String recovery) {
        if (recovery == null || recovery.isBlank()) {
            return false;
        }
        String upper = recovery.toUpperCase();
        return upper.equals("RECOVERED")
                || upper.equals("PARTIALLY_RECOVERED")
                || upper.equals("FAILED");
    }

    private static ProcessingStage stageForCompletion(ProjectProcessingStatus completion) {
        return switch (completion) {
            case CANCELLED -> ProcessingStage.CANCELLED;
            case FAILED, NO_CAN_DATA, RECOVERY_FAILED, UNSUPPORTED_STRUCTURE -> ProcessingStage.FAILED;
            case COMPLETED, COMPLETED_WITH_WARNINGS -> ProcessingStage.COMPLETED;
            default -> ProcessingStage.COMPLETED;
        };
    }

    private static String normalizeRecoveryState(String recoveryState, ProcessingSummary summary) {
        if (!summary.recoveryUsed()) {
            return "NOT_REQUIRED";
        }
        if (recoveryState == null || recoveryState.isBlank() || "ACTIVE".equalsIgnoreCase(recoveryState)
                || "COMPLETED".equalsIgnoreCase(recoveryState)
                || "NONE".equalsIgnoreCase(recoveryState)) {
            if (summary.completionStatus() == ProjectProcessingStatus.RECOVERY_FAILED
                    || summary.completionStatus() == ProjectProcessingStatus.FAILED) {
                return "FAILED";
            }
            if (summary.completionStatus() == ProjectProcessingStatus.CANCELLED) {
                return "ACTIVE";
            }
            if (summary.statistics().recoveredFrames() > 0L) {
                return "RECOVERED";
            }
            // Recovery was considered, but the usable CAN region decoded as VALID with zero recovered.
            if (summary.statistics().validFrames() > 0L) {
                return "NOT_REQUIRED";
            }
            return "NOT_RECOVERABLE";
        }
        return recoveryState;
    }

    /**
     * Coherent UI state captured under one lock.
     */
    public record ProcessingUiSnapshot(
            ProcessingStage stage,
            ProjectProcessingStatus status,
            String sourceFile,
            String dbcFile,
            String mode,
            ProcessingStatistics statistics,
            Duration elapsed,
            boolean running,
            String userMessage,
            String technicalDetail,
            String mf4FileStatus,
            String recoveryState,
            boolean recoveryUsed,
            List<AggregatedDiagnostic> diagnostics,
            String selectedChannelsSummary,
            long selectedMatchedIds
    ) {
        public ProcessingUiSnapshot {
            diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
            selectedChannelsSummary = selectedChannelsSummary == null ? "" : selectedChannelsSummary;
        }
    }
}
