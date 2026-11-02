package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ProcessingSummary;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.ui.ProcessingController;
import com.stellar.platform.processing.AggregatedDiagnostic;
import com.stellar.platform.processing.ProcessingStage;
import com.stellar.platform.processing.ProcessingStatistics;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingScreenModelStateTest {

    @Test
    void completedStage_neverCoexistsWithRunningStatus() {
        ProcessingScreenModel model = new ProcessingScreenModel();
        model.resetForRun("a.mf4", "b.dbc");
        ProcessingStatistics stats = new ProcessingStatistics();
        stats.recordSourceFrame();
        stats.recordRecoveredFrame();
        model.applyProgress(ProcessingStage.MF4_RECOVERY, stats);
        assertEquals(ProjectProcessingStatus.RUNNING, model.status());

        ProcessingSummary summary = summary(ProjectProcessingStatus.COMPLETED_WITH_WARNINGS, stats, "RECOVERED");
        model.applySummary(summary);

        assertEquals(ProcessingStage.COMPLETED, model.stage());
        assertEquals(ProjectProcessingStatus.COMPLETED_WITH_WARNINGS, model.status());
        assertNotEquals(ProjectProcessingStatus.RUNNING, model.status());
        assertFalse(model.running());
    }

    @Test
    void terminalStatus_ignoresLateProgress() {
        ProcessingScreenModel model = new ProcessingScreenModel();
        model.resetForRun("a.mf4", "b.dbc");
        ProcessingStatistics stats = new ProcessingStatistics();
        stats.recordSourceFrame();
        model.applySummary(summary(ProjectProcessingStatus.COMPLETED, stats, "NOT_REQUIRED"));

        model.applyProgress(ProcessingStage.DBC_DECODING, stats);
        assertEquals(ProcessingStage.COMPLETED, model.stage());
        assertEquals(ProjectProcessingStatus.COMPLETED, model.status());
        assertFalse(model.running());
    }

    @Test
    void recoveryActive_becomesTerminalOnSummary() {
        ProcessingScreenModel model = new ProcessingScreenModel();
        model.resetForRun("a.mf4", "b.dbc");
        model.setMf4Hints("UNFINALIZED", true);
        assertEquals("ACTIVE", model.recoveryState());

        ProcessingStatistics stats = new ProcessingStatistics();
        stats.recordRecoveredFrame();
        model.applySummary(summary(ProjectProcessingStatus.COMPLETED_WITH_WARNINGS, stats, "RECOVERED"));
        assertEquals("RECOVERED", model.recoveryState());
        assertTrue(model.recoveryUsed());
    }

    @Test
    void snapshot_isCoherentAndDoesNotMaterializeOccurrences() {
        ProcessingScreenModel model = new ProcessingScreenModel();
        model.resetForRun("a.mf4", "b.dbc");
        ProcessingStatistics stats = new ProcessingStatistics();
        stats.setDiagnosticTotals(3, 1_000_000);
        model.applySummary(summary(ProjectProcessingStatus.COMPLETED_WITH_WARNINGS, stats, "RECOVERED"));
        var snap = model.snapshot();
        assertEquals(ProcessingStage.COMPLETED, snap.stage());
        assertEquals(ProjectProcessingStatus.COMPLETED_WITH_WARNINGS, snap.status());
        assertEquals(3, snap.statistics().uniqueDiagnosticCategories());
        assertEquals(1_000_000L, snap.statistics().totalDiagnosticOccurrences());
        assertTrue(snap.diagnostics().size() <= 3);
    }

    @Test
    void diagnostics_populatedAsUniqueAggregates_andSurviveProgressUpdates() {
        ProcessingScreenModel model = new ProcessingScreenModel();
        model.resetForRun("a.mf4", "b.dbc");
        ProcessingStatistics stats = new ProcessingStatistics();
        stats.setDiagnosticTotals(2, 1_959_714);
        List<AggregatedDiagnostic> rows = List.of(
                new AggregatedDiagnostic("DBC", "DBC_UNMATCHED_CAN_ID", "No matching DBC message", 1_200_000),
                new AggregatedDiagnostic("READER", "MF4_RECOVERED_RECORD", "Frames recovered from inconsistent metadata", 373_109)
        );
        model.applySummary(summaryWithDiagnostics(
                ProjectProcessingStatus.COMPLETED_WITH_WARNINGS, stats, "RECOVERED", rows));

        assertEquals(2, model.diagnostics().size());
        assertEquals(2, model.snapshot().diagnostics().size());
        assertEquals(1_200_000L, model.diagnostics().get(0).count());

        // Late/progress updates must not clear the diagnostics model once terminal.
        model.applyProgress(ProcessingStage.DBC_DECODING, stats);
        assertEquals(2, model.diagnostics().size());
        assertEquals("DBC_UNMATCHED_CAN_ID", model.diagnostics().get(0).code());
        assertEquals("MF4_RECOVERED_RECORD", model.diagnostics().get(1).code());
    }

    @Test
    void diagnosticRow_mapsAggregatedEntriesWithoutExplodingOccurrences() {
        AggregatedDiagnostic unmatched = new AggregatedDiagnostic(
                "DBC", "DBC_UNMATCHED_CAN_ID", "No matching DBC message", 1_200_000);
        ProcessingController.DiagnosticRow row = ProcessingController.DiagnosticRow.from(unmatched);
        assertEquals("WARNING", row.severity());
        assertEquals("DBC_UNMATCHED_CAN_ID", row.code());
        assertEquals(1_200_000L, row.occurrences());
        assertEquals("No matching DBC message", row.message());

        AggregatedDiagnostic recovered = new AggregatedDiagnostic(
                "READER", "MF4_RECOVERED_RECORD", "Frames recovered", 373_109);
        assertEquals("INFO", ProcessingController.DiagnosticRow.from(recovered).severity());
    }

    @Test
    void recoveryAttempted_withValidFramesAndZeroRecovered_isNotRequired() {
        ProcessingScreenModel model = new ProcessingScreenModel();
        model.resetForRun("a.mf4", "b.dbc");
        ProcessingStatistics stats = new ProcessingStatistics();
        stats.recordSourceFrame();
        stats.recordValidFrame();
        List<AggregatedDiagnostic> diags = List.of(
                new AggregatedDiagnostic("READER", "RECOVERY_REQUIRED", "Recovery required for DG", 1),
                new AggregatedDiagnostic("READER", "NO_VALID_RECORDS_IN_REGION", "no valid", 1)
        );
        model.applySummary(new ProcessingSummary(
                "MF4",
                Path.of("a.mf4"),
                Path.of("b.dbc"),
                stats,
                Duration.ofSeconds(1),
                ProjectProcessingStatus.COMPLETED_WITH_WARNINGS,
                diags,
                "done",
                "",
                true,
                "NOT_REQUIRED",
                "UNFINALIZED"
        ));
        assertEquals("NOT_REQUIRED", model.recoveryState());
        assertFalse(model.running());
    }

    @Test
    void failureAndCancel_stillTerminal() {
        ProcessingScreenModel failed = new ProcessingScreenModel();
        failed.resetForRun("a.mf4", "b.dbc");
        failed.applySummary(summary(ProjectProcessingStatus.FAILED, new ProcessingStatistics(), "FAILED"));
        assertEquals(ProcessingStage.FAILED, failed.stage());
        assertTrue(ProcessingScreenModel.isTerminal(failed.status()));

        ProcessingScreenModel cancelled = new ProcessingScreenModel();
        cancelled.resetForRun("a.mf4", "b.dbc");
        cancelled.applySummary(summary(ProjectProcessingStatus.CANCELLED, new ProcessingStatistics(), "ACTIVE"));
        assertEquals(ProcessingStage.CANCELLED, cancelled.stage());
        assertTrue(ProcessingScreenModel.isTerminal(cancelled.status()));
    }

    private static ProcessingSummary summary(
            ProjectProcessingStatus status,
            ProcessingStatistics stats,
            String recovery
    ) {
        return summaryWithDiagnostics(status, stats, recovery, List.of());
    }

    private static ProcessingSummary summaryWithDiagnostics(
            ProjectProcessingStatus status,
            ProcessingStatistics stats,
            String recovery,
            List<AggregatedDiagnostic> diagnostics
    ) {
        return new ProcessingSummary(
                "MF4",
                Path.of("a.mf4"),
                Path.of("b.dbc"),
                stats,
                Duration.ofSeconds(1),
                status,
                diagnostics,
                "done",
                "",
                stats.recoveredFrames() > 0 || "RECOVERED".equals(recovery) || "ACTIVE".equals(recovery),
                recovery,
                "UNFINALIZED"
        );
    }
}
