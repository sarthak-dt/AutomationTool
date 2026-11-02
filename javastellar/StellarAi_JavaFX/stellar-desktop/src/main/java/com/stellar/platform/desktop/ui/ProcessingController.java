package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.QuickDecodeSignalsViewAssembler;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.ProcessingPageMode;
import com.stellar.platform.desktop.presentation.ProcessingPlanInputAssembler;
import com.stellar.platform.desktop.presentation.ProcessingSelectionWorkspaceModel;
import com.stellar.platform.desktop.presentation.SignalsProcessingContext;
import com.stellar.platform.desktop.presentation.SignalViewScope;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.AggregatedDiagnostic;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.plan.ProcessingPlanInputs;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.layout.StackPane;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Hosts the unified Processing Workspace. Legacy selection/plan/execution/result panes
 * remain constructed for tests and migration; normal navigation uses one screen.
 */
public final class ProcessingController implements AppServiceAware, Refreshable {

    @FXML
    private StackPane processingHost;

    private DesktopAppService appService;
    private MainShellController shell;
    private final ProcessingPlanInputAssembler assembler = new ProcessingPlanInputAssembler();
    private final QuickDecodeSignalsViewAssembler quickSignalsAssembler = new QuickDecodeSignalsViewAssembler();
    private final ProcessingWorkspacePane workspacePane = new ProcessingWorkspacePane();
    // Legacy migration/test panes are intentionally lazy. The product renders workspacePane only.
    private ProcessingSelectionPane selectionPane;
    private ProcessingPlanReviewPane reviewPane;
    private ProcessingExecutionPane executionPane;
    private ProcessingExecutionResultPane resultPane;
    private Runnable modelListener;
    private boolean applyingMode;
    private boolean pendingModeApply;

    @FXML
    private void initialize() {
        if (processingHost != null) {
            processingHost.getChildren().setAll(workspacePane);
        }
    }

    @Override
    public void setAppService(DesktopAppService appService, MainShellController shell) {
        this.appService = Objects.requireNonNull(appService, "appService");
        this.shell = shell;
        ProcessingSelectionWorkspaceModel model = appService.processingSelectionModel();
        if (modelListener != null) {
            model.removeListener(modelListener);
        }
        modelListener = this::onModelChanged;
        model.addListener(modelListener);
        workspacePane.bind(
                appService,
                model,
                this::onBackToDecisionWorkspace,
                this::onRunProcessing,
                this::onRerunProcessing,
                this::onReviewDbcMapping,
                this::onViewSignals,
                this::onExploreSignals,
                this::onOpenQuickSourceSignals);
        workspacePane.setExecutionHandlers(this::onCancelProcessing, this::onRetryFailed);
        workspacePane.setEditHandlers(this::onEditSources, this::onEditDecode);
        refresh();
    }

    /**
     * Editing configuration is plain navigation back to the owning workspace. No processing or
     * decode state is touched, so the user's mode, DBC mappings and Study results survive.
     */
    private void onEditSources() {
        if (shell != null) {
            shell.openRoute(WorkspaceRoute.SOURCES);
        }
    }

    private void onEditDecode() {
        if (shell != null) {
            shell.openRoute(WorkspaceRoute.DECODE);
        }
    }

    private void onCancelProcessing() {
        if (appService != null) {
            appService.cancelProcessing();
            applyModeFromModel();
        }
    }

    /**
     * A normal incremental run: the reconciler reuses only successful, configuration-matching
     * results, so exactly the failed and never-run jobs execute again.
     */
    private void onRetryFailed() {
        if (appService == null) {
            return;
        }
        appService.executeReviewedPlan(this::dispatchToFx, this::applyModeFromModel);
    }

    /**
     * Initializes the workspace for a transient processing route. Without this every processing
     * route rendered whatever phase the model happened to be in, so Review, Resume and Result
     * were indistinguishable destinations.
     *
     * <p>Live execution always wins: a route can never pull the workspace out of a running batch.</p>
     */
    void prepareForRoute(WorkspaceRoute route) {
        if (appService == null || route == null || route.page() != NavigationPage.PROCESSING) {
            return;
        }
        if (appService.isPlanExecutionActive()) {
            refresh();
            return;
        }
        applyingMode = true;
        try {
            // Rebuild the plan from the current decode configuration and reconcile it against
            // already-produced results. Registry/artifact lookup only — nothing is reparsed.
            // A restored execution result stays Processing Complete, including on Review & Process,
            // so Reprocess Entire Project is offered from persisted artifacts. Back still returns
            // to the plan.
            appService.hydrateProcessingWorkspace();
        } finally {
            applyingMode = false;
        }
        workspacePane.setRoute(route);
        refresh();
    }

    @Override
    public void refresh() {
        if (appService == null || processingHost == null) {
            return;
        }
        ProcessingSelectionWorkspaceModel model = appService.processingSelectionModel();

        ProcessingPageMode mode = model.mode();
        // Live EXECUTING must not be rebuilt. A populated EXECUTION_RESULT keeps its
        // in-memory rows. An empty EXECUTION_RESULT must reconstruct from durable artifacts
        // so navigation cannot present COMPLETE with 0 sources after the registry was cleared.
        boolean livePopulatedResult = mode == ProcessingPageMode.EXECUTION_RESULT
                && (!appService.processingResultRegistry().all().isEmpty()
                || model.executionJobs().stream().anyMatch(job -> job.result().isPresent()));
        if (mode != ProcessingPageMode.EXECUTING
                && mode != ProcessingPageMode.SELECTION
                && !livePopulatedResult) {
            applyingMode = true;
            try {
                appService.hydrateProcessingWorkspace();
            } finally {
                applyingMode = false;
            }
            mode = model.mode();
        }
        if (mode == ProcessingPageMode.SELECTION && selectionPane != null) {
            // The legacy/test-facing selection pane is lazy. Once materialized it must receive
            // fresh ProcessingPlanInputs when project files, inventories, or mappings change;
            // otherwise refresh() would rebuild against the stale snapshot captured at creation.
            selectionPane.bind(appService, model, assembler.assemble(appService), this::onReviewPlan);
        } else if (mode == ProcessingPageMode.PLAN_REVIEW || mode == ProcessingPageMode.PARTIAL) {
            bindLegacyReview(model);
        } else if (mode == ProcessingPageMode.EXECUTING && executionPane != null) {
            executionPane.bind(appService, model, this::onBackToDecisionWorkspace);
        } else if (mode == ProcessingPageMode.EXECUTION_RESULT) {
            bindLegacyResult(model);
        }
        workspacePane.refresh();
        showWorkspace();
    }

    private void onModelChanged() {
        if (applyingMode) {
            pendingModeApply = true;
            return;
        }
        dispatchToFx(this::applyModeFromModel);
    }

    private void dispatchToFx(Runnable task) {
        if (task == null) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    private void applyModeFromModel() {
        if (appService == null) {
            return;
        }
        if (applyingMode) {
            pendingModeApply = true;
            return;
        }
        applyingMode = true;
        ProcessingPageMode mode;
        try {
            ProcessingSelectionWorkspaceModel model = appService.processingSelectionModel();
            mode = model.mode();
            if (mode == ProcessingPageMode.SELECTION) {
                if (selectionPane != null) selectionPane.rebuild();
            } else if (mode == ProcessingPageMode.PLAN_REVIEW || mode == ProcessingPageMode.PARTIAL) {
                bindLegacyReview(model);
            } else if (mode == ProcessingPageMode.EXECUTING) {
                if (executionPane != null) {
                    executionPane.bind(appService, model, this::onBackToDecisionWorkspace);
                    executionPane.rebuild();
                }
            } else if (mode == ProcessingPageMode.EXECUTION_RESULT) {
                workspacePane.setRoute(WorkspaceRoute.PROCESSING_RESULT);
                bindLegacyResult(model);
                if (resultPane != null) resultPane.rebuild();
            }
            workspacePane.refresh();
            showWorkspace();
            if (shell != null) {
                shell.refreshProcessingChrome();
            }
        } finally {
            applyingMode = false;
        }
        if (pendingModeApply) {
            pendingModeApply = false;
            applyModeFromModel();
        }
    }

    private void showWorkspace() {
        if (processingHost == null) {
            return;
        }
        if (!processingHost.getChildren().contains(workspacePane)
                || processingHost.getChildren().getFirst() != workspacePane) {
            processingHost.getChildren().setAll(workspacePane);
        }
    }

    private void bindLegacyReview(ProcessingSelectionWorkspaceModel model) {
        if (reviewPane == null) return;
        reviewPane.bind(
                appService,
                model.reviewedPlan(),
                this::onBackToSelection,
                this::onReviewDbcMapping,
                this::onRunProcessing,
                model.planRequiresReviewMessage());
    }

    private void bindLegacyResult(ProcessingSelectionWorkspaceModel model) {
        if (resultPane == null) return;
        resultPane.bind(
                appService,
                model,
                this::onBackToSelection,
                this::onBackToPlanReview,
                this::onViewSignals);
    }

    private void onReviewPlan() {
        if (appService == null) {
            return;
        }
        ProcessingPlanInputs inputs = assembler.assemble(appService);
        applyingMode = true;
        try {
            appService.processingSelectionModel().reviewPlan(inputs);
        } finally {
            applyingMode = false;
        }
        bindLegacyReview(appService.processingSelectionModel());
        workspacePane.refresh();
        showWorkspace();
    }

    private void onRunProcessing() {
        if (appService == null) {
            return;
        }
        appService.executeReviewedPlan(this::dispatchToFx, this::applyModeFromModel);
    }

    private void onRerunProcessing() {
        if (appService == null) {
            return;
        }
        appService.forceReprocessReviewedPlan(this::dispatchToFx, this::applyModeFromModel);
    }

    private void onBackToDecisionWorkspace() {
        if (appService == null) {
            return;
        }
        ProcessingSelectionWorkspaceModel model = appService.processingSelectionModel();
        if (!model.canNavigateAwayFromExecution()) {
            return;
        }
        if (shell != null) {
            appService.setDecisionWorkspaceMode(model.decisionOrigin());
            shell.openDbcMappingWorkspace();
        }
    }

    private void onBackToSelection() {
        if (appService == null) {
            return;
        }
        ProcessingSelectionWorkspaceModel model = appService.processingSelectionModel();
        if (!model.canNavigateAwayFromExecution()) {
            return;
        }
        applyingMode = true;
        try {
            model.backToSelection();
        } finally {
            applyingMode = false;
        }
        if (selectionPane != null) selectionPane.rebuild();
        workspacePane.refresh();
        showWorkspace();
    }

    private void onBackToPlanReview() {
        if (appService == null) {
            return;
        }
        ProcessingSelectionWorkspaceModel model = appService.processingSelectionModel();
        applyingMode = true;
        try {
            model.backToPlanReview();
        } finally {
            applyingMode = false;
        }
        ProcessingPlanInputs inputs = assembler.assemble(appService);
        applyingMode = true;
        try {
            model.rebuildReviewedPlan(inputs);
        } finally {
            applyingMode = false;
        }
        bindLegacyReview(model);
        workspacePane.refresh();
        showWorkspace();
    }

    private void onExploreSignals() {
        if (appService == null) {
            return;
        }
        if (!ensureProjectSignalsCatalog()) {
            return;
        }
        appService.signalsModel().requestAllMeasurementsNavigation();
        if (shell != null) {
            shell.show(NavigationPage.SIGNALS);
        }
    }

    private void onViewSignals(IndependentJobResult result) {
        if (appService == null || result == null) {
            return;
        }
        SignalSeriesStore store = appService.resolveExplorationStore(result);
        if (store == null) {
            if (!appService.openCompletedSignalsForExploration()) {
                return;
            }
            if (shell != null) {
                shell.show(NavigationPage.SIGNALS);
            }
            return;
        }
        String hint = buildSignalsHint(result);
        SignalsProcessingContext ctx = SignalsProcessingContext.of(
                sourceDisplayName(result.projectSourceId()),
                result.definitionDisplayName(),
                "Ch" + result.channelId(),
                SignalViewScope.JOB
        );
        // Viewing a different processing result is a source/context switch: never carry active
        // signal columns from the previously viewed MF4 into the new table.
        appService.signalsModel().replaceCatalog(store, hint, ctx, false);
        if (shell != null) {
            shell.show(NavigationPage.SIGNALS);
        }
    }

    private void onOpenQuickSourceSignals(String projectSourceId) {
        if (appService == null || projectSourceId == null || projectSourceId.isBlank()) {
            return;
        }
        if (!ensureProjectSignalsCatalog()) {
            return;
        }
        // Filter the existing project catalog. Do not build a second catalog or reopen the MF4.
        appService.signalsModel().requestMeasurementNavigation(projectSourceId);
        if (shell != null) {
            shell.show(NavigationPage.SIGNALS);
        }
    }

    /**
     * Publishes the already-processed project catalog. Falls back to the in-memory job
     * results when the session registry has not been republished yet.
     */
    private boolean ensureProjectSignalsCatalog() {
        if (appService.openCompletedSignalsForExploration()
                && appService.signalsModel().catalog() != null
                && !appService.signalsModel().catalog().entries().isEmpty()) {
            return true;
        }
        Optional<QuickDecodeSignalsViewAssembler.View> view = quickSignalsAssembler.assembleResults(
                currentProcessingResults(), this::sourceDisplayName);
        view.ifPresent(assembled -> appService.signalsModel().replaceCatalog(
                assembled.store(), assembled.hint(), assembled.context(), false));
        return appService.signalsModel().catalog() != null
                && !appService.signalsModel().catalog().entries().isEmpty();
    }

    private java.util.List<IndependentJobResult> currentProcessingResults() {
        if (appService == null) {
            return java.util.List.of();
        }
        // Project Explore must use the session registry (merged A+B+C+D), not only the
        // latest plan-scoped executionJobs list.
        java.util.List<IndependentJobResult> registry =
                appService.processingResultRegistry().all().stream()
                        .filter(IndependentJobResult::successful)
                        .toList();
        if (!registry.isEmpty()) {
            return registry;
        }
        return appService.processingSelectionModel().executionJobs().stream()
                .map(row -> row.result().orElse(null))
                .filter(Objects::nonNull)
                .filter(IndependentJobResult::successful)
                .toList();
    }

    private String buildSignalsHint(IndependentJobResult result) {
        String source = sourceDisplayName(result.projectSourceId());
        String dbc = result.definitionDisplayName().isBlank() ? "def.dbc" : result.definitionDisplayName();
        return source + " \u00b7 Ch" + result.channelId() + " \u00b7 " + dbc;
    }

    private String sourceDisplayName(String projectSourceId) {
        if (appService == null || projectSourceId == null) {
            return "";
        }
        Optional<WorkspaceFile> file = appService.workspace().findFile(projectSourceId);
        return file.map(WorkspaceFile::fileName).orElse(projectSourceId);
    }

    private void onReviewDbcMapping(SourceChannelRef channel) {
        if (shell == null) {
            return;
        }
        if (channel == null) {
            shell.openDbcMappingWorkspace();
            return;
        }
        SourceChannelKey key = SourceChannelKey.of(
                ProjectSourceId.of(channel.projectSourceId()),
                channel.channelId());
        shell.openDbcMappingWorkspace(key);
    }

    StackPane processingHostForTest() {
        return processingHost;
    }

    ProcessingWorkspacePane workspacePaneForTest() {
        return workspacePane;
    }

    ProcessingSelectionPane selectionPaneForTest() {
        if (selectionPane == null) {
            selectionPane = new ProcessingSelectionPane();
            if (appService != null) selectionPane.bind(appService, appService.processingSelectionModel(), assembler.assemble(appService), this::onReviewPlan);
        }
        return selectionPane;
    }

    ProcessingPlanReviewPane reviewPaneForTest() {
        if (reviewPane == null) reviewPane = new ProcessingPlanReviewPane();
        if (appService != null) bindLegacyReview(appService.processingSelectionModel());
        return reviewPane;
    }

    ProcessingExecutionPane executionPaneForTest() {
        if (executionPane == null) executionPane = new ProcessingExecutionPane();
        if (appService != null) executionPane.bind(appService, appService.processingSelectionModel(), this::onBackToDecisionWorkspace);
        return executionPane;
    }

    ProcessingExecutionResultPane resultPaneForTest() {
        if (resultPane == null) resultPane = new ProcessingExecutionResultPane();
        if (appService != null) bindLegacyResult(appService.processingSelectionModel());
        return resultPane;
    }

    int materializedLegacyPaneCountForTest() {
        int count = 0;
        if (selectionPane != null) count++;
        if (reviewPane != null) count++;
        if (executionPane != null) count++;
        if (resultPane != null) count++;
        return count;
    }

    ProcessingPlanInputAssembler assemblerForTest() {
        return assembler;
    }

    /**
     * Presentation row for one aggregated diagnostic category.
     * Retained for {@code ProcessingScreenModelStateTest} severity mapping coverage.
     */
    public record DiagnosticRow(String severity, String code, long occurrences, String message) {
        public static DiagnosticRow from(AggregatedDiagnostic d) {
            return new DiagnosticRow(deriveSeverity(d), d.code(), d.count(), d.message());
        }

        static String deriveSeverity(AggregatedDiagnostic d) {
            String code = d.code() == null ? "" : d.code().toUpperCase(Locale.ROOT);
            if (code.contains("FAIL") || code.contains("ERROR") || code.contains("FATAL")
                    || code.contains("UNSUPPORTED")) {
                return "ERROR";
            }
            if (code.contains("UNMATCHED") || code.contains("DBC_NO_MATCHING") || code.contains("WARNING")
                    || code.contains("MALFORMED")
                    || code.contains("TRUNCATED") || code.contains("INVALID")) {
                return "WARNING";
            }
            if (code.contains("RECOVER") || code.contains("SKIP") || code.contains("NON_CAN")
                    || code.contains("VLSD") || code.contains("ZERO_DECLARED") || code.contains("INFO")) {
                return "INFO";
            }
            return "DBC".equalsIgnoreCase(d.layer()) ? "WARNING" : "INFO";
        }
    }
}
