package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ExecutionJobPresentation;
import com.stellar.platform.desktop.presentation.ProcessingDetailsPresentation;
import com.stellar.platform.desktop.presentation.ProcessingElapsed;
import com.stellar.platform.desktop.presentation.ProcessingStatusLabels;
import com.stellar.platform.desktop.presentation.ProcessingJobTiming;
import com.stellar.platform.desktop.presentation.ProcessingReviewSummary;
import com.stellar.platform.desktop.presentation.ProcessingReviewSummaryFactory;
import com.stellar.platform.desktop.presentation.ProcessingRoutePhases;
import com.stellar.platform.desktop.presentation.ProcessingSelectionWorkspaceModel;
import com.stellar.platform.desktop.presentation.ProcessingWorkspacePhase;
import com.stellar.platform.desktop.presentation.QuickDecodeSourceResultAssembler;
import com.stellar.platform.desktop.presentation.QuickDecodeSourceResultAssembler.DefinitionUsage;
import com.stellar.platform.desktop.presentation.QuickDecodeSourceResultAssembler.SourceResult;
import com.stellar.platform.desktop.presentation.ReviewIssueSeverity;
import com.stellar.platform.desktop.presentation.SourceAliasRegistry;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver.DisplayLabel;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver.SourceRef;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingJobKey;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProcessingPlanSummary;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Rectangle;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.time.Duration;
import java.time.Instant;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Unified Processing Workspace: review, run, and inspect independent job results
 * on one screen. Presentation only — reuses existing plan/execution/result models.
 */
public final class ProcessingWorkspacePane extends BorderPane {

    private static final NumberFormat INT = NumberFormat.getIntegerInstance(Locale.US);
    private static final NumberFormat PCT = NumberFormat.getNumberInstance(Locale.US);
    private static final double ROW_HEIGHT = 44;
    private static final double HEADER_HEIGHT = 32;

    static {
        PCT.setMaximumFractionDigits(1);
        PCT.setMinimumFractionDigits(0);
    }

    private final Label titleLabel = new Label("Processing Workspace");
    private final Label subtitleLabel = new Label("Review and run processing jobs.");
    private final Label summaryLine = new Label("");
    private final Label totalProcessingTime = new Label();
    private final Label statusBadge = new Label("");
    private final Label statusStrip = new Label("");
    private final Label orderHint = new Label("Jobs run in the order shown.");
    private final Label jobsHeading = new Label("Processing Jobs");
    private final Label reviewRequiredLabel = new Label("");
    private final TableView<JobView> jobsTable = new TableView<>();
    private final ObservableList<JobView> rows = FXCollections.observableArrayList();
    private final TableView<QuickSourceView> quickSourceTable = new TableView<>();
    private final ObservableList<QuickSourceView> quickSourceRows = FXCollections.observableArrayList();
    private final QuickDecodeSourceResultAssembler quickSourceResultAssembler =
            new QuickDecodeSourceResultAssembler();
    private final ProcessingReviewSummaryPane reviewSummaryPane = new ProcessingReviewSummaryPane();
    private final Button backButton = new Button("Back to Decode Workspace");
    private final Button runButton = new Button("Start Processing");
    private final Button cancelButton = new Button("Cancel Processing");
    private final Button retryFailedButton = new Button("Retry Failed");
    private final Button rerunButton = new Button("Reprocess Entire Project");
    private final Button openAnalysisButton = new Button("Explore Signals");
    private final Label emptyHint = new Label(
            "No channels are ready for processing.\nConfirm or choose a DBC for at least one channel in the decode workspace.");

    private DesktopAppService appService;
    private ProcessingSelectionWorkspaceModel model;
    private Runnable onBack = () -> { };
    private Runnable onRun = () -> { };
    private Runnable onRerun = () -> { };
    private Consumer<SourceChannelRef> onFixMapping = ref -> { };
    private Consumer<IndependentJobResult> onViewSignals = r -> { };
    private Runnable onOpenQuickAnalysis;
    private Consumer<String> onViewQuickSourceSignals = sourceId -> { };
    private Runnable onCancel = () -> { };
    private Runnable onRetryFailed;
    private Map<ProjectSourceId, DisplayLabel> labels = Map.of();
    private ProcessingWorkspacePhase phase = ProcessingWorkspacePhase.REVIEW;
    private WorkspaceRoute route = WorkspaceRoute.REVIEW_AND_PROCESS;
    private DesktopAppService.ProcessingRunOutcome runOutcome =
            DesktopAppService.ProcessingRunOutcome.none();
    private ProcessingJobKey lastViewedJobKey;
    private TableColumn<JobView, JobView> progressCol;
    private TableColumn<JobView, String> elapsedCol;
    private TableColumn<JobView, String> selectedFramesCol;
    private TableColumn<JobView, String> matchedFramesCol;
    private TableColumn<JobView, String> signalsCol;
    private TableColumn<JobView, String> mappingCol;
    private TableColumn<JobView, String> matchedCol;
    private TableColumn<JobView, String> coverageCol;
    private TableColumn<JobView, String> framesCol;
    private TableColumn<JobView, JobView> actionCol;

    public ProcessingWorkspacePane() {
        getStyleClass().addAll("page-root", "processing-workspace-root");
        setId("processing-workspace-root");
        setMinHeight(0);
        setMaxHeight(Double.MAX_VALUE);

        titleLabel.getStyleClass().add("processing-workspace-title");
        subtitleLabel.getStyleClass().add("processing-workspace-subtitle");
        subtitleLabel.setWrapText(true);
        summaryLine.getStyleClass().add("processing-workspace-summary");
        totalProcessingTime.getStyleClass().addAll("processing-workspace-summary", "processing-elapsed");
        totalProcessingTime.setId("processing-total-elapsed");
        totalProcessingTime.setVisible(false);
        totalProcessingTime.setManaged(false);
        summaryLine.setWrapText(true);
        statusBadge.getStyleClass().addAll("badge", "plan-status-badge");
        statusStrip.getStyleClass().add("processing-workspace-status-strip");
        orderHint.getStyleClass().addAll("muted", "processing-workspace-order-hint");
        jobsHeading.getStyleClass().add("processing-workspace-jobs-heading");
        reviewRequiredLabel.getStyleClass().addAll("muted", "plan-requires-review-label");
        reviewRequiredLabel.setWrapText(true);
        reviewRequiredLabel.setId("plan-requires-review-label");
        emptyHint.getStyleClass().addAll("muted", "processing-workspace-empty");
        emptyHint.setWrapText(true);

        HBox summaryRow = new HBox(10, summaryLine, new Region(), statusBadge);
        HBox.setHgrow(summaryRow.getChildren().get(1), Priority.ALWAYS);
        summaryRow.setAlignment(Pos.CENTER_LEFT);
        summaryRow.setMaxHeight(Region.USE_PREF_SIZE);

        configureTable();
        jobsTable.setItems(rows);
        jobsTable.getStyleClass().add("processing-jobs-table");
        jobsTable.setId("processing-jobs-table");
        jobsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        jobsTable.setPlaceholder(emptyHint);
        jobsTable.setFixedCellSize(ROW_HEIGHT);
        jobsTable.setMinHeight(0);
        jobsTable.setMaxHeight(Double.MAX_VALUE);

        configureQuickSourceTable();
        quickSourceTable.setItems(quickSourceRows);
        quickSourceTable.getStyleClass().addAll("processing-jobs-table", "quick-source-results-table");
        quickSourceTable.setId("quick-source-results-table");
        quickSourceTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        quickSourceTable.setMinWidth(0);
        quickSourceTable.setFixedCellSize(ROW_HEIGHT);
        quickSourceTable.setVisible(false);
        quickSourceTable.setManaged(false);

        backButton.setId("processing-back-to-decision-button");
        backButton.getStyleClass().addAll("secondary-button", "secondary-action", "compact-button");
        backButton.setMinWidth(Region.USE_PREF_SIZE);
        backButton.setOnAction(e -> onBack.run());

        runButton.setId("processing-run-button");
        runButton.getStyleClass().addAll("primary-button", "primary-action", "compact-button");
        runButton.setMinWidth(Region.USE_PREF_SIZE);
        runButton.setOnAction(e -> startProcessing());

        cancelButton.setId("processing-cancel-button");
        cancelButton.getStyleClass().addAll("secondary-button", "secondary-action", "compact-button");
        cancelButton.setMinWidth(Region.USE_PREF_SIZE);
        cancelButton.setOnAction(e -> confirmAndCancel());
        cancelButton.setVisible(false);
        cancelButton.setManaged(false);

        retryFailedButton.setId("processing-retry-failed-button");
        retryFailedButton.getStyleClass().addAll("secondary-button", "secondary-action", "compact-button");
        retryFailedButton.setMinWidth(Region.USE_PREF_SIZE);
        retryFailedButton.setOnAction(e -> retryFailed());
        retryFailedButton.setTooltip(new Tooltip(
                "Runs only the jobs that did not complete. Successful results are reused without reopening their sources."));
        retryFailedButton.setVisible(false);
        retryFailedButton.setManaged(false);

        rerunButton.setId("processing-rerun-button");
        rerunButton.getStyleClass().addAll("secondary-button", "secondary-action", "compact-button");
        rerunButton.setMinWidth(Region.USE_PREF_SIZE);
        rerunButton.setOnAction(e -> confirmAndReprocessAll());
        rerunButton.setTooltip(new Tooltip(
                "Explicit full reprocess: decodes every current job again and discards reusable results."));

        openAnalysisButton.setId("processing-open-analysis-button");
        openAnalysisButton.getStyleClass().addAll("primary-button", "primary-action", "compact-button");
        openAnalysisButton.setMinWidth(Region.USE_PREF_SIZE);
        openAnalysisButton.setOnAction(e -> openAnalysis());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, backButton, spacer, openAnalysisButton, rerunButton,
                retryFailedButton, cancelButton, runButton);
        actions.getStyleClass().add("processing-workspace-actions");
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.setPadding(new Insets(8, 12, 10, 12));
        actions.setMinHeight(Region.USE_PREF_SIZE);
        actions.setMaxHeight(Region.USE_PREF_SIZE);

        VBox header = new VBox(4, titleLabel, subtitleLabel, summaryRow, totalProcessingTime, statusStrip, orderHint,
                reviewRequiredLabel, reviewSummaryPane, jobsHeading);
        header.setMinHeight(Region.USE_PREF_SIZE);
        header.setMaxHeight(Region.USE_PREF_SIZE);

        StackPane tableHost = new StackPane(jobsTable, quickSourceTable);
        tableHost.getStyleClass().add("processing-workspace-table-host");
        tableHost.setMinHeight(0);
        tableHost.setMaxHeight(Double.MAX_VALUE);
        tableHost.setMaxWidth(Double.MAX_VALUE);
        Rectangle tableClip = new Rectangle();
        tableHost.setClip(tableClip);
        tableHost.layoutBoundsProperty().addListener((obs, previous, bounds) -> {
            tableClip.setWidth(Math.max(0, bounds.getWidth()));
            tableClip.setHeight(Math.max(0, bounds.getHeight()));
        });
        VBox body = new VBox(8, header, tableHost);
        body.getStyleClass().add("processing-workspace-body");
        body.setPadding(new Insets(8, 12, 0, 12));
        body.setMinHeight(0);
        body.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(tableHost, Priority.ALWAYS);
        StackPane.setAlignment(jobsTable, Pos.TOP_LEFT);
        StackPane.setAlignment(quickSourceTable, Pos.TOP_LEFT);
        setCenter(body);
        setBottom(actions);
    }

    public void bind(
            DesktopAppService app,
            ProcessingSelectionWorkspaceModel workspace,
            Runnable backCallback,
            Runnable runCallback,
            Consumer<SourceChannelRef> fixMapping,
            Consumer<IndependentJobResult> viewSignals
    ) {
        bind(app, workspace, backCallback, runCallback, fixMapping, viewSignals, null, null);
    }

    public void bind(
            DesktopAppService app,
            ProcessingSelectionWorkspaceModel workspace,
            Runnable backCallback,
            Runnable runCallback,
            Consumer<SourceChannelRef> fixMapping,
            Consumer<IndependentJobResult> viewSignals,
            Runnable openAnalysis
    ) {
        bind(app, workspace, backCallback, runCallback, fixMapping, viewSignals, openAnalysis, null);
    }

    public void bind(
            DesktopAppService app,
            ProcessingSelectionWorkspaceModel workspace,
            Runnable backCallback,
            Runnable runCallback,
            Consumer<SourceChannelRef> fixMapping,
            Consumer<IndependentJobResult> viewSignals,
            Runnable openAnalysis,
            Consumer<String> viewQuickSourceSignals
    ) {
        bind(app, workspace, backCallback, runCallback, runCallback, fixMapping, viewSignals,
                openAnalysis, viewQuickSourceSignals);
    }

    public void bind(
            DesktopAppService app,
            ProcessingSelectionWorkspaceModel workspace,
            Runnable backCallback,
            Runnable runCallback,
            Runnable rerunCallback,
            Consumer<SourceChannelRef> fixMapping,
            Consumer<IndependentJobResult> viewSignals,
            Runnable openAnalysis,
            Consumer<String> viewQuickSourceSignals
    ) {
        this.appService = Objects.requireNonNull(app, "app");
        this.model = Objects.requireNonNull(workspace, "workspace");
        this.onBack = backCallback == null ? () -> { } : backCallback;
        this.onRun = runCallback == null ? () -> { } : runCallback;
        this.onRerun = rerunCallback == null ? this.onRun : rerunCallback;
        this.onFixMapping = fixMapping == null ? ref -> { } : fixMapping;
        this.onViewSignals = viewSignals == null ? r -> { } : viewSignals;
        this.onOpenQuickAnalysis = openAnalysis;
        this.onViewQuickSourceSignals = viewQuickSourceSignals == null ? sourceId -> { } : viewQuickSourceSignals;
        rebuildLabels();
        refresh();
    }

    /** Which transient processing destination is currently open. */
    public void setRoute(WorkspaceRoute route) {
        this.route = route == null ? WorkspaceRoute.REVIEW_AND_PROCESS : route;
    }

    /** Cooperative cancellation of the active batch, and the Sources/Decode edit destinations. */
    public void setExecutionHandlers(Runnable cancel, Runnable retryFailed) {
        this.onCancel = cancel == null ? () -> { } : cancel;
        this.onRetryFailed = retryFailed;
    }

    public void setEditHandlers(Runnable editSources, Runnable editDecode) {
        reviewSummaryPane.setEditHandlers(editSources, editDecode);
    }

    public void refresh() {
        if (model == null) {
            return;
        }
        rebuildLabels();
        runOutcome = appService == null
                ? DesktopAppService.ProcessingRunOutcome.none()
                : appService.lastProcessingRunOutcome();
        phase = ProcessingRoutePhases.resolve(route, model.mode(), runOutcome.interrupted());
        applyPhaseChrome();
        if (usesQuickSourceResultPresentation()) {
            rebuildRows();
            rebuildQuickSourceRows();
            applyTableHeight(quickSourceTable, quickSourceRows.size());
        } else {
            quickSourceRows.clear();
            rebuildRows();
            applyTableHeight(jobsTable, rows.size());
        }
        if (phase == ProcessingWorkspacePhase.COMPLETED || phase == ProcessingWorkspacePhase.PARTIAL) {
            openAnalysisButton.setDisable(!hasAnySignalCatalog());
        }
        jobsTable.refresh();
        quickSourceTable.refresh();
    }

    public ProcessingWorkspacePhase phaseForTest() {
        return phase;
    }

    public TableView<JobView> jobsTableForTest() {
        return jobsTable;
    }

    public TableView<QuickSourceView> quickSourceTableForTest() {
        return quickSourceTable;
    }

    public boolean usesQuickSourceResultPresentationForTest() {
        return usesQuickSourceResultPresentation();
    }

    public void fireViewQuickSourceSignalsForTest(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= quickSourceRows.size()) {
            return;
        }
        QuickSourceView row = quickSourceRows.get(rowIndex);
        if (row.canViewSignals()) {
            onViewQuickSourceSignals.accept(row.projectSourceId());
        }
    }

    public Button runButtonForTest() {
        return runButton;
    }

    public Button rerunButtonForTest() {
        return rerunButton;
    }

    public Button cancelButtonForTest() {
        return cancelButton;
    }

    public Button retryFailedButtonForTest() {
        return retryFailedButton;
    }

    /** Fires the primary action exactly as a click would, including the immediate disable. */
    public void fireStartProcessingForTest() {
        startProcessing();
    }

    public void fireRetryFailedForTest() {
        retryFailed();
    }

    ProcessingReviewSummaryPane reviewSummaryPaneForTest() {
        return reviewSummaryPane;
    }

    public Button backButtonForTest() {
        return backButton;
    }

    public Button openAnalysisButtonForTest() {
        return openAnalysisButton;
    }

    public void fireViewSignalsForTest(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= rows.size()) {
            return;
        }
        JobView row = rows.get(rowIndex);
        IndependentJobResult result = row.result();
        if (result == null || !row.canViewSignals()) {
            return;
        }
        lastViewedJobKey = result.jobKey();
        onViewSignals.accept(result);
    }

    public String emptyHintForTest() {
        return emptyHint.getText();
    }

    public String titleForTest() {
        return titleLabel.getText();
    }

    public String summaryForTest() {
        return summaryLine.getText();
    }

    public String statusStripForTest() {
        return statusStrip.getText();
    }

    private void applyPhaseChrome() {
        boolean running = phase == ProcessingWorkspacePhase.RUNNING;
        boolean complete = phase == ProcessingWorkspacePhase.COMPLETED;
        boolean partial = phase == ProcessingWorkspacePhase.PARTIAL;
        boolean interrupted = phase == ProcessingWorkspacePhase.INTERRUPTED;
        boolean completedWithIssues = complete && (model.failedJobCount() > 0 || runOutcome.failed());
        // One primary call to action for every way of getting work started.
        runButton.setText(interrupted ? "Resume Processing" : "Start Processing");
        boolean canLeave = model == null || model.canNavigateAwayFromExecution();

        boolean quickSourceResults = usesQuickSourceResultPresentation();

        if (complete) {
            titleLabel.setText(completedWithIssues ? "Processing completed with issues" : "Processing Complete");
            subtitleLabel.setText(quickSourceResults
                    ? "Consolidated results for each measurement source. Channel and DBC provenance is preserved."
                    : "Independent results for each source and channel.");
        } else if (interrupted) {
            titleLabel.setText("Processing Interrupted");
            subtitleLabel.setText(
                    "The previous run did not finish. Completed results are preserved and the remaining work can continue.");
        } else if (partial) {
            titleLabel.setText("Review & Process");
            subtitleLabel.setText("Existing results are restored. New or changed jobs are ready to process.");
        } else {
            titleLabel.setText(running ? "Processing" : "Review & Process");
            subtitleLabel.setText(running
                    ? "Jobs run in the order shown."
                    : "This is what will be processed if you start now.");
        }

        backButton.setText(interrupted || complete ? "Review Setup" : "Back to Decode Workspace");
        backButton.setDisable(!canLeave);
        backButton.setTooltip(canLeave ? null : new Tooltip("Processing is currently running."));
        runButton.setVisible(!running && !complete);
        runButton.setManaged(!running && !complete);
        setShown(cancelButton, running);
        setShown(retryFailedButton, completedWithIssues && onRetryFailed != null);
        rerunButton.setVisible(complete);
        rerunButton.setManaged(complete);
        openAnalysisButton.setVisible(complete || partial || interrupted);
        openAnalysisButton.setManaged(complete || partial || interrupted);
        // An active graph session means this completion added to an existing analysis rather
        // than creating the first one.
        openAnalysisButton.setText(complete ? "Explore All Signals" : "Explore Signals");
        openAnalysisButton.setTooltip(new Tooltip(complete
                ? "Explore signals from every processed measurement in this project."
                : "Explore signals from the completed processing results."));
        orderHint.setVisible(running);
        orderHint.setManaged(running);
        statusStrip.setVisible(running || complete || partial || interrupted);
        statusStrip.setManaged(running || complete || partial || interrupted);
        statusBadge.setVisible(!running);
        statusBadge.setManaged(!running);
        applyReviewSummary(phase == ProcessingWorkspacePhase.REVIEW || partial);
        String reviewMsg = model.planRequiresReviewMessage();
        boolean showReview = reviewMsg != null && !reviewMsg.isBlank() && !running;
        reviewRequiredLabel.setText(showReview ? reviewMsg : "");
        reviewRequiredLabel.setVisible(showReview);
        reviewRequiredLabel.setManaged(showReview);
        jobsHeading.setText(quickSourceResults ? "Processed Sources" : "Processing Jobs");
        jobsTable.setVisible(!quickSourceResults);
        jobsTable.setManaged(!quickSourceResults);
        quickSourceTable.setVisible(quickSourceResults);
        quickSourceTable.setManaged(quickSourceResults);

        ProjectProcessingPlan plan = currentPlan();
        if (phase == ProcessingWorkspacePhase.REVIEW) {
            paintReviewSummary(plan);
        } else if (phase == ProcessingWorkspacePhase.RUNNING) {
            paintRunningSummary();
        } else if (phase == ProcessingWorkspacePhase.PARTIAL) {
            paintPartialSummary(plan);
        } else if (phase == ProcessingWorkspacePhase.INTERRUPTED) {
            paintInterruptedSummary(plan);
        } else {
            paintCompletedSummary();
        }

        boolean resultCols = complete || partial || interrupted;
        boolean runCols = running;
        mappingCol.setVisible(!runCols);
        matchedCol.setVisible(!runCols && !resultCols);
        coverageCol.setVisible(!runCols && !resultCols);
        framesCol.setVisible(!resultCols);
        progressCol.setVisible(runCols);
        elapsedCol.setVisible(runCols || resultCols);
        selectedFramesCol.setVisible(resultCols);
        matchedFramesCol.setVisible(resultCols);
        signalsCol.setVisible(resultCols);
        actionCol.setVisible(!runCols);
    }

    private void paintReviewSummary(ProjectProcessingPlan plan) {
        hideTotalProcessingTime();
        ProcessingPlanSummary summary = plan.summary();
        summaryLine.setText(
                summary.processingJobs() + " Jobs \u00b7 "
                        + summary.selectedSources() + " Sources \u00b7 "
                        + summary.selectedChannels() + " Channels \u00b7 "
                        + INT.format(summary.selectedFrames()) + " Frames");
        String ready = ProcessingPlanReviewPane.displayReadiness(plan.readiness());
        ProcessingReviewSummary review = appService == null
                ? null : ProcessingReviewSummaryFactory.build(appService);
        boolean warned = review != null && review.issues().stream()
                .anyMatch(issue -> issue.severity() == ReviewIssueSeverity.WARNING);
        if (review != null && !review.readinessText().isBlank()) {
            ready = review.readinessText();
        }
        statusBadge.setText(ready);
        statusBadge.getStyleClass().setAll("badge", "plan-status-badge",
                plan.readiness() == ProcessingPlanReadiness.READY
                        ? (warned ? "badge-warning" : "badge-ready")
                        : "badge-invalid");
        boolean canRun = ProcessingPlanReviewPane.isExecuteEnabled(plan);
        runButton.setDisable(!canRun);
        runButton.setTooltip(canRun
                ? new Tooltip("Processes only new or changed jobs; successful unchanged results are reused without reopening their source files.")
                : new Tooltip(ProcessingPlanReviewPane.executeDisabledReason(plan)));
        emptyHint.setText(plan.jobs().isEmpty()
                ? "No channels are ready for processing.\nConfirm or choose a DBC for at least one channel in the decode workspace."
                : "No processing jobs in plan.");
    }

    private boolean hasActiveGraphSession() {
        return appService != null && !appService.signalsModel().workspaceKeys().isEmpty();
    }

    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    private void applyReviewSummary(boolean shown) {
        if (shown && appService != null) {
            reviewSummaryPane.apply(ProcessingReviewSummaryFactory.build(appService));
        }
        setShown(reviewSummaryPane, shown);
    }

    /**
     * Start is a single click with no confirmation dialog: the review screen the user is looking
     * at is the confirmation. The button is disabled immediately so a second click cannot arrive
     * before the service claims the run.
     */
    private void startProcessing() {
        if (runButton.isDisabled()) {
            return;
        }
        runButton.setDisable(true);
        onRun.run();
    }

    private void retryFailed() {
        if (onRetryFailed == null || retryFailedButton.isDisabled()) {
            return;
        }
        retryFailedButton.setDisable(true);
        onRetryFailed.run();
    }

    private void confirmAndCancel() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Completed results will be preserved. Pending work can be processed later.",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setTitle("Cancel Processing");
        confirm.setHeaderText("Stop processing this project?");
        confirm.showAndWait()
                .filter(button -> button == ButtonType.OK)
                .ifPresent(button -> onCancel.run());
    }

    /**
     * Full reprocess discards reusable results, so unlike Start Processing it does warrant a
     * confirmation.
     */
    private void confirmAndReprocessAll() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Every channel in this project is decoded again and existing processed results are replaced.",
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setTitle("Reprocess Entire Project");
        confirm.setHeaderText("Reprocess every source in this project?");
        confirm.showAndWait()
                .filter(button -> button == ButtonType.OK)
                .ifPresent(button -> onRerun.run());
    }

    /**
     * An interrupted run keeps whatever it already committed. Remaining work is whatever the
     * rebuilt plan still has no reusable result for.
     */
    private void paintInterruptedSummary(ProjectProcessingPlan plan) {
        hideTotalProcessingTime();
        int total = plan.jobs().size();
        int completed = 0;
        int failed = 0;
        for (ExecutionJobPresentation job : model.executionJobs()) {
            if (job.result().filter(IndependentJobResult::successful).isPresent()) {
                completed++;
            } else if (job.result().isPresent()) {
                failed++;
            }
        }
        int remaining = Math.max(0, total - completed - failed);
        summaryLine.setText("Completed " + completed
                + " \u00b7 Remaining " + remaining
                + " \u00b7 Failed " + failed);
        statusStrip.setText(runOutcome.message().isBlank()
                ? "Completed results are preserved."
                : runOutcome.message());
        boolean cancelled = appService != null
                && appService.workspace().processingStatus()
                == com.stellar.platform.desktop.project.ProjectProcessingStatus.CANCELLED;
        statusBadge.setText(cancelled ? ProcessingStatusLabels.CANCELLED : ProcessingStatusLabels.ERROR);
        statusBadge.getStyleClass().setAll("badge", "plan-status-badge",
                cancelled ? ProcessingStatusLabels.STYLE_CANCELLED : ProcessingStatusLabels.STYLE_ERROR);
        boolean canRun = ProcessingPlanReviewPane.isExecuteEnabled(plan);
        runButton.setDisable(!canRun);
        runButton.setTooltip(canRun
                ? new Tooltip("Continues the " + remaining + " outstanding job(s). Completed results are reused.")
                : new Tooltip(ProcessingPlanReviewPane.executeDisabledReason(plan)));
        openAnalysisButton.setDisable(firstSuccessfulResult() == null);
    }

    /**
     * Counts, never a synthesised percentage: the engine reports job completion, not fractional
     * progress, so "12 of 40" is the strongest honest statement available.
     */
    private void paintRunningSummary() {
        hideTotalProcessingTime();
        int queued = 0;
        int runningCount = 0;
        int completed = model.completedJobCount();
        int failed = model.failedJobCount();
        String activity = "";
        for (ExecutionJobPresentation job : model.executionJobs()) {
            if (job.status() == JobExecutionStatus.QUEUED) {
                queued++;
            }
            if (job.status() == JobExecutionStatus.RUNNING) {
                runningCount++;
                if (activity.isEmpty()) {
                    activity = labelFor(job.planJob().projectSourceId()).fullFileName();
                }
            }
        }
        int total = model.executionJobs().size();
        int firstActive = Math.min(total, completed + 1);
        int lastActive = Math.min(total, completed + runningCount);
        String position = runningCount > 1 && lastActive > firstActive
                ? firstActive + "\u2013" + lastActive
                : String.valueOf(lastActive);
        summaryLine.setText("Processing " + position
                + " of " + total + (total == 1 ? " job" : " jobs"));
        long framesRead = model.liveSourceFramesRead();
        long elapsedSeconds = model.executionElapsed().toSeconds();
        String phase = activity.isBlank()
                ? (queued > 0 ? "Starting worker\u2026" : "Preparing")
                : "Reading source \u00b7 " + activity
                        + (framesRead > 0
                        ? " \u00b7 Frames processed: " + String.format(Locale.ROOT, "%,d", framesRead)
                        : "")
                        + String.format(Locale.ROOT, " \u00b7 Elapsed: %02d:%02d",
                        elapsedSeconds / 60, elapsedSeconds % 60);
        statusStrip.setText(phase
                + "    Complete " + completed
                + "    Pending " + queued
                + "    Failed " + failed);
        statusBadge.setText(ProcessingStatusLabels.PROCESSING);
        statusBadge.getStyleClass().setAll("badge", "plan-status-badge", ProcessingStatusLabels.STYLE_PROCESSING);
    }

    private void paintCompletedSummary() {
        if (authoritativeJobResults().isEmpty()
                && runOutcome.present()
                && runOutcome.jobCount() > 0
                && appService != null) {
            appService.hydrateProcessingWorkspace();
        }
        if (usesQuickSourceResultPresentation()) {
            paintQuickCompletedSummary();
            return;
        }
        List<IndependentJobResult> authoritative = authoritativeJobResults();
        int total = Math.max(model.executionJobs().size(), authoritative.size());
        int warnings = 0;
        int clean = 0;
        int failed = 0;
        for (IndependentJobResult job : authoritative) {
            if (job.status() == JobExecutionStatus.SUCCESS_WITH_WARNINGS) {
                warnings++;
            } else if (job.status() == JobExecutionStatus.SUCCESS) {
                clean++;
            } else if (job.status() == JobExecutionStatus.FAILED
                    || job.status() == JobExecutionStatus.CANCELLED
                    || job.status() == JobExecutionStatus.BLOCKED) {
                failed++;
            }
        }
        if (authoritative.isEmpty()) {
            failed = model.failedJobCount();
            for (ExecutionJobPresentation job : model.executionJobs()) {
                if (job.status() == JobExecutionStatus.SUCCESS_WITH_WARNINGS) {
                    warnings++;
                } else if (job.status() == JobExecutionStatus.SUCCESS) {
                    clean++;
                }
            }
        }
        summaryLine.setText(total + (total == 1 ? " job \u00b7 " : " jobs \u00b7 ")
                + (clean + warnings) + " completed \u00b7 "
                + failed + " errors");
        showTotalProcessingTime();
        statusStrip.setText("Completed: " + (clean + warnings)
                + "    Errors: " + failed);
        if (failed > 0) {
            statusBadge.setText(ProcessingStatusLabels.ERROR);
            statusBadge.getStyleClass().setAll("badge", "plan-status-badge", ProcessingStatusLabels.STYLE_ERROR);
        } else {
            statusBadge.setText(ProcessingStatusLabels.COMPLETED);
            statusBadge.getStyleClass().setAll("badge", "plan-status-badge", ProcessingStatusLabels.STYLE_COMPLETED);
        }
        rerunButton.setDisable(model != null && !model.canNavigateAwayFromExecution());
    }

    /**
     * Session registry is the source of truth across incremental batches so processing only D
     * cannot hide warnings/failures that still belong to A/B/C.
     */
    /**
     * Total time is the run wall clock: batch elapsed when the executor recorded it,
     * otherwise the persisted run start and finish. Per-source rows use
     * {@link ProcessingElapsed#sourceElapsed} and never add channel durations together.
     */
    private Optional<Duration> currentRunElapsed() {
        Duration batchElapsed = null;
        if (model != null && model.latestBatch() != null) {
            batchElapsed = model.latestBatch().elapsed();
        }
        Instant runStarted = null;
        Instant runFinished = null;
        if (appService != null) {
            var run = appService.latestPersistedProcessingRun();
            if (run.isPresent()) {
                runStarted = run.get().startedAt();
                runFinished = run.get().finishedAt();
            }
        }
        return ProcessingElapsed.totalElapsed(batchElapsed, runStarted, runFinished);
    }

    private void showTotalProcessingTime() {
        Optional<Duration> total = currentRunElapsed();
        totalProcessingTime.setText("Total Processing Time " + ProcessingElapsed.format(total));
        totalProcessingTime.setVisible(true);
        totalProcessingTime.setManaged(true);
    }

    private void hideTotalProcessingTime() {
        totalProcessingTime.setVisible(false);
        totalProcessingTime.setManaged(false);
    }

    private static List<ProcessingElapsed.JobClock> clocks(List<IndependentJobResult> jobs) {
        List<ProcessingElapsed.JobClock> clocks = new ArrayList<>();
        if (jobs == null) {
            return clocks;
        }
        for (IndependentJobResult job : jobs) {
            Duration elapsed = job == null ? null : job.elapsed();
            if (elapsed == null || elapsed.isZero() || elapsed.isNegative()) {
                elapsed = null;
            }
            clocks.add(new ProcessingElapsed.JobClock(null, null, elapsed));
        }
        return clocks;
    }

    private static String formatJobElapsed(IndependentJobResult result) {
        if (result == null || result.elapsed() == null || result.elapsed().isZero() || result.elapsed().isNegative()) {
            return ProcessingElapsed.UNAVAILABLE;
        }
        return ProcessingElapsed.format(result.elapsed());
    }

    public String totalProcessingTimeForTest() {
        return totalProcessingTime.getText();
    }

    private List<IndependentJobResult> authoritativeJobResults() {
        if (appService != null && !appService.processingResultRegistry().all().isEmpty()) {
            return appService.processingResultRegistry().all();
        }
        if (model == null) {
            return List.of();
        }
        return model.executionJobs().stream()
                .map(ExecutionJobPresentation::result)
                .flatMap(Optional::stream)
                .toList();
    }

    private void paintQuickCompletedSummary() {
        List<SourceResult> sources = quickSourceResults();
        int channels = sources.stream().mapToInt(SourceResult::channelCount).sum();
        int sourcesWithFailures = (int) sources.stream().filter(source -> source.failedChannels() > 0).count();
        long frames = sources.stream().mapToLong(SourceResult::selectedFrames).reduce(0L, ProcessingWorkspacePane::safeAdd);
        summaryLine.setText(sources.size() + (sources.size() == 1 ? " Source" : " Sources")
                + " \u00b7 " + channels + (channels == 1 ? " Channel" : " Channels")
                + " \u00b7 " + INT.format(frames) + " Frames");
        showTotalProcessingTime();
        statusStrip.setText("Processed Sources: " + sources.size()
                + "    Errors: " + sourcesWithFailures);
        if (sourcesWithFailures > 0) {
            statusBadge.setText(ProcessingStatusLabels.ERROR);
            statusBadge.getStyleClass().setAll("badge", "plan-status-badge", ProcessingStatusLabels.STYLE_ERROR);
        } else {
            statusBadge.setText(ProcessingStatusLabels.COMPLETED);
            statusBadge.getStyleClass().setAll("badge", "plan-status-badge", ProcessingStatusLabels.STYLE_COMPLETED);
        }
        rerunButton.setDisable(model != null && !model.canNavigateAwayFromExecution());
    }

    private void paintPartialSummary(ProjectProcessingPlan plan) {
        hideTotalProcessingTime();
        int restored = 0;
        for (ExecutionJobPresentation job : model.executionJobs()) {
            if (job.result().isPresent()) {
                restored++;
            }
        }
        int total = plan.jobs().size();
        int pending = Math.max(0, total - restored);
        summaryLine.setText(total + " Jobs \u00b7 "
                + restored + " with results \u00b7 "
                + pending + " ready");
        statusBadge.setText("PARTIAL");
        statusBadge.getStyleClass().setAll("badge", "plan-status-badge", "badge-ready");
        statusStrip.setText("Restored " + restored + "    Ready " + pending);
        boolean canRun = ProcessingPlanReviewPane.isExecuteEnabled(plan);
        runButton.setDisable(!canRun);
        runButton.setTooltip(canRun
                ? new Tooltip("Processes only the " + pending + " new or changed jobs. "
                        + restored + " completed jobs are reused without source reads.")
                : new Tooltip(ProcessingPlanReviewPane.executeDisabledReason(plan)));
        boolean anySignals = firstSuccessfulResult() != null;
        openAnalysisButton.setDisable(!anySignals);
    }

    private void rebuildRows() {
        List<JobView> next = new ArrayList<>();
        if (phase == ProcessingWorkspacePhase.REVIEW) {
            int n = 1;
            for (ProcessingJob job : currentPlan().jobs()) {
                ExecutionJobPresentation exec = executionRowFor(job.key());
                if (exec != null && (exec.status() == JobExecutionStatus.FAILED
                        || exec.status() == JobExecutionStatus.BLOCKED
                        || exec.status() == JobExecutionStatus.CANCELLED)) {
                    next.add(JobView.fromExecution(n++, exec, labelFor(job.projectSourceId())));
                } else {
                    next.add(JobView.fromPlan(n++, job, labelFor(job.projectSourceId())));
                }
            }
        } else if (phase == ProcessingWorkspacePhase.RUNNING) {
            int n = 1;
            for (ExecutionJobPresentation exec : model.executionJobs()) {
                next.add(JobView.fromExecution(n++, exec, labelFor(exec.planJob().projectSourceId())));
            }
        } else {
            int n = 1;
            for (ProcessingJob job : currentPlan().jobs()) {
                ExecutionJobPresentation exec = executionRowFor(job.key());
                if (exec != null && exec.result().isPresent()) {
                    next.add(JobView.fromExecution(n++, exec, labelFor(job.projectSourceId())));
                } else {
                    next.add(JobView.fromPlan(n++, job, labelFor(job.projectSourceId())));
                }
            }
        }
        rows.setAll(next);
    }

    private ExecutionJobPresentation executionRowFor(ProcessingJobKey key) {
        if (model == null || key == null) {
            return null;
        }
        for (ExecutionJobPresentation row : model.executionJobs()) {
            if (key.equals(row.jobKey())) {
                return row;
            }
        }
        return null;
    }

    private ProjectProcessingPlan currentPlan() {
        if (model == null) {
            return ProjectProcessingPlan.empty();
        }
        if (phase == ProcessingWorkspacePhase.RUNNING) {
            return model.executionPlanSnapshot();
        }
        ProjectProcessingPlan reviewed = model.reviewedPlan();
        return reviewed == null ? ProjectProcessingPlan.empty() : reviewed;
    }

    /**
     * The table fills the space between the summary and the footer and scrolls inside that
     * space. A content-sized minimum pushed the footer over the last rows.
     */
    private static void applyTableHeight(TableView<?> table, int rowCount) {
        double content = HEADER_HEIGHT + Math.max(1, rowCount) * ROW_HEIGHT + 8;
        table.setMinHeight(0);
        table.setPrefHeight(content);
        table.setMaxHeight(Double.MAX_VALUE);
    }

    private boolean usesQuickSourceResultPresentation() {
        return model != null
                && phase == ProcessingWorkspacePhase.COMPLETED
                && !quickSourceResults().isEmpty();
    }

    private List<SourceResult> quickSourceResults() {
        if (model == null) {
            return List.of();
        }
        return quickSourceResultAssembler.assembleResults(authoritativeJobResults());
    }

    private void rebuildQuickSourceRows() {
        List<QuickSourceView> next = new ArrayList<>();
        int n = 1;
        Map<String, ProcessingJobTiming.SourceWindow> timing = appService == null
                ? Map.of()
                : appService.persistedSourceTiming();
        Optional<Duration> runElapsed = currentRunElapsed();
        Set<String> currentRunSources = appService == null
                ? Set.of()
                : appService.latestProcessingRunSourceIds();
        for (SourceResult source : quickSourceResults()) {
            Optional<Duration> cap = currentRunSources.contains(source.projectSourceId())
                    ? runElapsed
                    : Optional.empty();
            next.add(new QuickSourceView(n++, source, labelFor(source.projectSourceId()),
                    timing.get(source.projectSourceId()), cap));
        }
        quickSourceRows.setAll(next);
    }

    private boolean hasAnySignalCatalog() {
        if (usesQuickSourceResultPresentation()) {
            return quickSourceRows.stream().anyMatch(QuickSourceView::canViewSignals);
        }
        return firstSuccessfulResult() != null;
    }

    private static long safeAdd(long left, long right) {
        try {
            return Math.addExact(left, Math.max(0L, right));
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private void configureQuickSourceTable() {
        TableColumn<QuickSourceView, Number> numCol = new TableColumn<>("#");
        numCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().number()));
        numCol.setMinWidth(36);
        numCol.setPrefWidth(44);
        numCol.setMaxWidth(56);

        TableColumn<QuickSourceView, QuickSourceView> sourceCol = new TableColumn<>("Source");
        sourceCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        sourceCol.setMinWidth(140);
        sourceCol.setPrefWidth(220);
        sourceCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(QuickSourceView item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    setTooltip(null);
                    return;
                }
                Label primary = new Label(item.sourceAlias());
                primary.getStyleClass().addAll("processing-job-source", "measurement-source");
                primary.setTextOverrun(OverrunStyle.ELLIPSIS);
                VBox box = new VBox(0, primary);
                if (!item.sourceFile().isBlank() && !item.sourceFile().equals(item.sourceAlias())) {
                    Label file = new Label(item.sourceFile());
                    file.getStyleClass().addAll("muted", "processing-job-file");
                    file.setTextOverrun(OverrunStyle.ELLIPSIS);
                    box.getChildren().add(file);
                }
                setGraphic(box);
                setText(null);
                setTooltip(new Tooltip(item.tooltip()));
            }
        });

        TableColumn<QuickSourceView, String> channelsCol = new TableColumn<>("Channels");
        channelsCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().channelsLabel()));
        channelsCol.setMinWidth(64);
        channelsCol.setPrefWidth(76);

        TableColumn<QuickSourceView, String> definitionsCol = new TableColumn<>("DBCs Used");
        definitionsCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().definitionsLabel()));
        definitionsCol.setMinWidth(96);
        definitionsCol.setPrefWidth(140);
        definitionsCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                setText(item);
                setTextOverrun(OverrunStyle.ELLIPSIS);
                setTooltip(new Tooltip(item));
            }
        });

        TableColumn<QuickSourceView, String> selectedCol = new TableColumn<>("Selected Frames");
        selectedCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().selectedFramesLabel()));
        selectedCol.setMinWidth(72);
        selectedCol.setPrefWidth(96);

        TableColumn<QuickSourceView, String> matchedCol = new TableColumn<>("DBC Matched");
        matchedCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().dbcMatchedLabel()));
        matchedCol.setMinWidth(72);
        matchedCol.setPrefWidth(96);

        TableColumn<QuickSourceView, String> samplesCol = new TableColumn<>("Decoded Signals");
        samplesCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().decodedSamplesLabel()));
        samplesCol.setMinWidth(78);
        samplesCol.setPrefWidth(100);

        TableColumn<QuickSourceView, String> elapsedCol = new TableColumn<>("Elapsed");
        elapsedCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().elapsedLabel()));
        elapsedCol.setMinWidth(WorkstationLayoutMetrics.ELAPSED_COLUMN_MIN);
        elapsedCol.setPrefWidth(WorkstationLayoutMetrics.ELAPSED_COLUMN_PREF);
        elapsedCol.getStyleClass().add("processing-elapsed");
        elapsedCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    getStyleClass().remove("processing-elapsed");
                    return;
                }
                setText(item);
                if (!getStyleClass().contains("processing-elapsed")) {
                    getStyleClass().add("processing-elapsed");
                }
                setTooltip(ProcessingElapsed.UNAVAILABLE.equals(item)
                        ? new Tooltip(ProcessingElapsed.UNAVAILABLE_TOOLTIP)
                        : null);
            }
        });

        TableColumn<QuickSourceView, String> resultCol = new TableColumn<>("Result");
        resultCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().resultDisplayText()));
        resultCol.setMinWidth(108);
        resultCol.setPrefWidth(140);
        resultCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("badge", "badge-ready", "badge-invalid", "processing-status-warning");
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                setText(item);
                setWrapText(true);
                QuickSourceView row = getTableRow() == null ? null : getTableRow().getItem();
                getStyleClass().addAll("badge", row == null ? "badge-invalid" : row.resultStyle());
                setTooltip(row == null ? null : new Tooltip(row.resultTooltip()));
            }
        });

        TableColumn<QuickSourceView, QuickSourceView> actionCol = new TableColumn<>("");
        actionCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        actionCol.setMinWidth(196);
        actionCol.setPrefWidth(220);
        actionCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(QuickSourceView item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                HBox box = new HBox(6);
                box.setAlignment(Pos.CENTER_LEFT);
                box.setMinWidth(0);
                if (item.hasDiagnostics()) {
                    Button details = new Button("Details");
                    details.getStyleClass().addAll("secondary-button", "compact-button");
                    details.setMinWidth(Region.USE_PREF_SIZE);
                    details.setTooltip(new Tooltip("Details (" + item.affectedChannels() + ")"));
                    details.setOnAction(e -> showQuickSourceDetails(item));
                    box.getChildren().add(details);
                }
                if (item.canViewSignals()) {
                    Button view = new Button("Explore Signals");
                    view.getStyleClass().addAll(
                            "secondary-button", "compact-button", "view-signals-button",
                            "processing-explore-signals");
                    view.setMinWidth(Region.USE_PREF_SIZE);
                    view.setTooltip(new Tooltip("Explore signals for this measurement"));
                    view.setOnAction(e -> onViewQuickSourceSignals.accept(item.projectSourceId()));
                    box.getChildren().add(view);
                }
                setGraphic(box.getChildren().isEmpty() ? null : box);
            }
        });

        quickSourceTable.getColumns().setAll(
                numCol, sourceCol, channelsCol, definitionsCol, selectedCol, matchedCol,
                samplesCol, elapsedCol, resultCol, actionCol);
    }

    private Stage showQuickSourceDetails(QuickSourceView source) {
        ProcessingDetailsPresentation.Model model = ProcessingDetailsPresentation.fromJobs(
                source.sourceAlias(),
                source.sourceFile(),
                source.resultLabel(),
                source.channelsLabel(),
                source.definitionsLabel(),
                source.selectedFramesLabel(),
                source.dbcMatchedLabel(),
                source.decodedSamplesLabel(),
                source.elapsedLabel(),
                source.result().channelResults());
        return ProcessingDetailsDialog.show(getScene() == null ? null : getScene().getWindow(), model);
    }

    Stage showQuickSourceDetailsForTest(QuickSourceView source) {
        return showQuickSourceDetails(source);
    }

    private void configureTable() {
        TableColumn<JobView, Number> numCol = new TableColumn<>("#");
        numCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().number()));
        numCol.setMinWidth(36);
        numCol.setPrefWidth(44);
        numCol.setMaxWidth(56);

        TableColumn<JobView, JobView> sourceCol = new TableColumn<>("Source");
        sourceCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        sourceCol.setMinWidth(220);
        sourceCol.setPrefWidth(280);
        sourceCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(JobView item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                Label primary = new Label(item.sourceAlias());
                primary.getStyleClass().addAll("processing-job-source", "measurement-source");
                primary.setTextOverrun(OverrunStyle.ELLIPSIS);
                VBox box = new VBox(0, primary);
                if (!item.sourceFile().isBlank() && !item.sourceFile().equals(item.sourceAlias())) {
                    Label file = new Label(item.sourceFile());
                    file.getStyleClass().addAll("muted", "processing-job-file");
                    file.setTextOverrun(OverrunStyle.ELLIPSIS);
                    box.getChildren().add(file);
                }
                setGraphic(box);
                setText(null);
                setTooltip(new Tooltip(item.tooltip() + (item.blockedReason().isBlank()
                        ? "" : "\n" + item.blockedReason())));
            }
        });

        TableColumn<JobView, String> channelCol = new TableColumn<>("Channel");
        channelCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().channelLabel()));
        channelCol.setMinWidth(64);
        channelCol.setPrefWidth(80);

        TableColumn<JobView, String> dbcCol = new TableColumn<>("DBC");
        dbcCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().dbcLabel()));
        dbcCol.setMinWidth(90);
        dbcCol.setPrefWidth(130);
        dbcCol.setCellFactory(col -> badgeCell("dbc-definition"));

        mappingCol = new TableColumn<>("Mapping");
        mappingCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().mappingLabel()));
        mappingCol.setMinWidth(100);
        mappingCol.setPrefWidth(120);
        mappingCol.setCellFactory(col -> mappingCell());

        matchedCol = new TableColumn<>("Matched");
        matchedCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().matchedLabel()));
        matchedCol.setMinWidth(80);
        matchedCol.setPrefWidth(100);

        coverageCol = new TableColumn<>("Coverage");
        coverageCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().coverageLabel()));
        coverageCol.setMinWidth(70);
        coverageCol.setPrefWidth(86);

        framesCol = new TableColumn<>("Frames");
        framesCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().framesLabel()));
        framesCol.setMinWidth(80);
        framesCol.setPrefWidth(100);

        TableColumn<JobView, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().statusDisplayText()));
        statusCol.setMinWidth(110);
        statusCol.setPrefWidth(150);
        statusCol.setCellFactory(col -> statusCell());

        progressCol = new TableColumn<>("Progress");
        progressCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        progressCol.setMinWidth(90);
        progressCol.setPrefWidth(120);
        progressCol.setCellFactory(col -> progressCell());
        progressCol.setVisible(false);

        elapsedCol = new TableColumn<>("Elapsed");
        elapsedCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().elapsedLabel()));
        elapsedCol.setMinWidth(WorkstationLayoutMetrics.ELAPSED_COLUMN_MIN);
        elapsedCol.setPrefWidth(WorkstationLayoutMetrics.ELAPSED_COLUMN_PREF);
        elapsedCol.setVisible(false);

        selectedFramesCol = new TableColumn<>("Selected Frames");
        selectedFramesCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().selectedFramesLabel()));
        selectedFramesCol.setMinWidth(110);
        selectedFramesCol.setPrefWidth(120);
        selectedFramesCol.setVisible(false);

        matchedFramesCol = new TableColumn<>("DBC Matched");
        matchedFramesCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().dbcMatchedLabel()));
        matchedFramesCol.setMinWidth(100);
        matchedFramesCol.setPrefWidth(110);
        matchedFramesCol.setVisible(false);

        signalsCol = new TableColumn<>("Signals Produced");
        signalsCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().signalsLabel()));
        signalsCol.setMinWidth(110);
        signalsCol.setPrefWidth(130);
        signalsCol.setVisible(false);

        actionCol = new TableColumn<>("");
        actionCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue()));
        actionCol.setMinWidth(140);
        actionCol.setPrefWidth(180);
        actionCol.setCellFactory(col -> actionCell());

        jobsTable.getColumns().setAll(
                numCol, sourceCol, channelCol, dbcCol, mappingCol, matchedCol, coverageCol,
                framesCol, statusCol, progressCol, selectedFramesCol, matchedFramesCol,
                signalsCol, elapsedCol, actionCol);
    }

    private TableCell<JobView, String> badgeCell(String extra) {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("dbc-definition", "measurement-source");
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(item);
                if (extra != null) {
                    getStyleClass().add(extra);
                }
            }
        };
    }

    private TableCell<JobView, String> mappingCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("status-confirmed", "status-recommended", "status-attention",
                        "processing-mapping-manual");
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(item);
                JobView row = getTableRow() == null ? null : getTableRow().getItem();
                if (row == null) {
                    return;
                }
                getStyleClass().add(row.mappingStyle());
            }
        };
    }

    private TableCell<JobView, String> statusCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("badge", "badge-ready", "badge-running", "badge-invalid",
                        "badge-unsupported", "processing-status-warning");
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                    return;
                }
                setText(item);
                setWrapText(true);
                JobView row = getTableRow() == null ? null : getTableRow().getItem();
                getStyleClass().addAll("badge", row == null ? "badge-unsupported" : row.statusStyle());
                setTooltip(row == null ? null : new Tooltip(row.statusTooltip()));
            }
        };
    }

    private TableCell<JobView, JobView> progressCell() {
        return new TableCell<>() {
            private final ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);

            {
                bar.setMaxWidth(Double.MAX_VALUE);
                bar.getStyleClass().add("execution-job-progress");
            }

            @Override
            protected void updateItem(JobView item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.executionStatus() != JobExecutionStatus.RUNNING) {
                    setGraphic(null);
                    return;
                }
                bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                setGraphic(bar);
            }
        };
    }

    private TableCell<JobView, JobView> actionCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(JobView item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                HBox box = new HBox(6);
                box.setAlignment(Pos.CENTER_LEFT);
                if (phase == ProcessingWorkspacePhase.REVIEW && item.needsAttention()) {
                    Button fix = new Button("Fix in Decode Workspace");
                    fix.getStyleClass().addAll("secondary-button", "compact-button");
                    fix.setMinWidth(Region.USE_PREF_SIZE);
                    SourceChannelRef channel = item.channel();
                    fix.setOnAction(e -> onFixMapping.accept(channel));
                    box.getChildren().add(fix);
                }
                if (phase == ProcessingWorkspacePhase.COMPLETED
                        || phase == ProcessingWorkspacePhase.PARTIAL) {
                    IndependentJobResult result = item.result();
                    if (result != null && !result.diagnostics().isEmpty()) {
                        Button warn = new Button("Warnings (" + result.diagnostics().size() + ")");
                        warn.getStyleClass().addAll("secondary-button", "compact-button");
                        warn.setMinWidth(Region.USE_PREF_SIZE);
                        IndependentJobResult captured = result;
                        warn.setOnAction(e -> showWarnings(captured));
                        box.getChildren().add(warn);
                    }
                    if (item.canViewSignals()) {
                        Button view = new Button("View Signals");
                        view.getStyleClass().addAll("secondary-button", "compact-button", "view-signals-button");
                        view.setMinWidth(Region.USE_PREF_SIZE);
                        IndependentJobResult captured = result;
                        view.setOnAction(e -> {
                            lastViewedJobKey = captured.jobKey();
                            onViewSignals.accept(captured);
                        });
                        box.getChildren().add(view);
                    }
                }
                setGraphic(box.getChildren().isEmpty() ? null : box);
            }
        };
    }

    private void showWarnings(IndependentJobResult result) {
        ProcessingDetailsPresentation.Model model = ProcessingDetailsPresentation.fromJobs(
                result.definitionDisplayName().isBlank() ? "Channel " + result.channelId() : result.definitionDisplayName(),
                "",
                result.status().name(),
                "Ch" + result.channelId(),
                result.definitionDisplayName(),
                INT.format(result.selectedChannelFrames()),
                INT.format(result.dbcMatchedFrames()),
                INT.format(result.signalsProduced()),
                formatJobElapsed(result),
                List.of(result));
        ProcessingDetailsDialog.show(getScene() == null ? null : getScene().getWindow(), model);
    }

    private void openAnalysis() {
        if (onOpenQuickAnalysis != null) {
            onOpenQuickAnalysis.run();
            return;
        }
        openDefaultResultSignals();
    }

    private void openDefaultResultSignals() {
        IndependentJobResult result = null;
        if (lastViewedJobKey != null) {
            for (JobView row : rows) {
                if (row.result() != null && lastViewedJobKey.equals(row.result().jobKey()) && row.canViewSignals()) {
                    result = row.result();
                    break;
                }
            }
        }
        if (result == null) {
            result = firstSuccessfulResult();
        }
        if (result != null) {
            lastViewedJobKey = result.jobKey();
            onViewSignals.accept(result);
        }
    }

    private IndependentJobResult firstSuccessfulResult() {
        for (JobView row : rows) {
            if (row.canViewSignals()) {
                return row.result();
            }
        }
        return null;
    }

    private void rebuildLabels() {
        if (appService == null) {
            labels = Map.of();
            return;
        }
        SourceAliasRegistry aliases = appService.sourceAliasRegistry();
        List<SourceRef> refs = new ArrayList<>();
        for (WorkspaceFile file : appService.workspace().measurementFiles()) {
            aliases.aliasFor(file.projectSourceId());
            refs.add(new SourceRef(file.projectSourceId(), file.fileName(), file.path()));
        }
        Map<ProjectSourceId, DisplayLabel> resolved = SourceDisplayNameResolver.resolve(refs);
        LinkedHashMap<ProjectSourceId, DisplayLabel> merged = new LinkedHashMap<>();
        for (WorkspaceFile file : appService.workspace().measurementFiles()) {
            ProjectSourceId id = file.projectSourceId();
            DisplayLabel base = resolved.get(id);
            String alias = aliases.aliasFor(id);
            if (base == null) {
                merged.put(id, new DisplayLabel(id, alias, file.fileName(), file.fileName(),
                        file.path().toAbsolutePath().normalize().toString()));
            } else {
                merged.put(id, new DisplayLabel(id, alias, base.distinguishingName(),
                        base.fullFileName(), base.fullPath()));
            }
        }
        labels = Map.copyOf(merged);
    }

    private DisplayLabel labelFor(String projectSourceId) {
        if (projectSourceId == null || projectSourceId.isBlank()) {
            return new DisplayLabel(
                    ProjectSourceId.of("unknown-source"),
                    "Source",
                    "Measurement source",
                    "Measurement source",
                    "");
        }
        ProjectSourceId id = ProjectSourceId.of(projectSourceId);
        DisplayLabel mapped = labels.get(id);
        if (mapped != null && !looksLikeSourceId(mapped.alias()) && !looksLikeSourceId(mapped.distinguishingName())) {
            return mapped;
        }
        if (appService == null) {
            return new DisplayLabel(id, "Source", "Measurement source", "Measurement source", "");
        }
        Optional<WorkspaceFile> file = appService.workspace().findFile(projectSourceId);
        String alias = appService.sourceAliasRegistry().aliasFor(id);
        if (file.isPresent() && file.get().kind() != null && file.get().kind().measurement()) {
            WorkspaceFile measurement = file.get();
            String fileName = measurement.fileName();
            String distinguishing = mapped != null ? mapped.distinguishingName() : fileName;
            if (looksLikeSourceId(distinguishing)) {
                distinguishing = fileName;
            }
            String path = measurement.path().toAbsolutePath().normalize().toString();
            return new DisplayLabel(id, alias, distinguishing, fileName, path);
        }
        if (mapped != null) {
            return new DisplayLabel(
                    mapped.sourceId(),
                    looksLikeSourceId(mapped.alias()) ? alias : mapped.alias(),
                    looksLikeSourceId(mapped.distinguishingName()) ? "Measurement source" : mapped.distinguishingName(),
                    mapped.fullFileName(),
                    mapped.fullPath());
        }
        return new DisplayLabel(id, alias, "Measurement source", "Measurement source", "");
    }

    static boolean looksLikeSourceId(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }
        String trimmed = value.trim();
        return trimmed.length() >= 32 && trimmed.chars().filter(ch -> ch == '-').count() >= 4;
    }

    public static final class QuickSourceView {
        private final int number;
        private final SourceResult result;
        private final String sourceAlias;
        private final String sourceFile;
        private final String tooltip;

        private final ProcessingJobTiming.SourceWindow persistedWindow;
        private final Optional<Duration> runElapsed;

        private QuickSourceView(
                int number,
                SourceResult result,
                DisplayLabel label,
                ProcessingJobTiming.SourceWindow persistedWindow
        ) {
            this(number, result, label, persistedWindow, Optional.empty());
        }

        private QuickSourceView(
                int number,
                SourceResult result,
                DisplayLabel label,
                ProcessingJobTiming.SourceWindow persistedWindow,
                Optional<Duration> runElapsed
        ) {
            this.persistedWindow = persistedWindow;
            this.runElapsed = runElapsed == null ? Optional.empty() : runElapsed;
            this.number = number;
            this.result = Objects.requireNonNull(result, "result");
            this.sourceAlias = SourceDisplayNameResolver.primaryIdentity(label);
            this.sourceFile = label == null || looksLikeSourceId(label.distinguishingName())
                    ? ""
                    : label.distinguishingName();
            this.tooltip = SourceDisplayNameResolver.identityTooltip(label);
        }

        public int number() {
            return number;
        }

        public String projectSourceId() {
            return result.projectSourceId();
        }

        public String sourceAlias() {
            return sourceAlias;
        }

        public String sourceFile() {
            return sourceFile;
        }

        public String channelsLabel() {
            return INT.format(result.channelCount());
        }

        public String definitionsLabel() {
            if (result.definitions().isEmpty()) {
                return "—";
            }
            List<String> labels = new ArrayList<>(result.definitions().size());
            for (DefinitionUsage usage : result.definitions()) {
                labels.add(usage.displayName() + " · " + usage.channelCount() + " Ch");
            }
            return String.join("  •  ", labels);
        }

        public String selectedFramesLabel() {
            return INT.format(result.selectedFrames());
        }

        public String dbcMatchedLabel() {
            return INT.format(result.dbcMatchedFrames());
        }

        public String decodedSamplesLabel() {
            if (result.dbcMatchedFrames() == 0) {
                if (result.decodedSamples() == 0) {
                    return "0 DBC signals";
                }
                return "0 DBC signals · " + INT.format(result.decodedSamples()) + " native samples";
            }
            return INT.format(result.decodedSamples());
        }

        /**
         * Physical-source wall clock. Shared-pass channel jobs carry the same duration
         * and are not added. Missing or zero durations display as {@link ProcessingElapsed#UNAVAILABLE}.
         */
        public String elapsedLabel() {
            return ProcessingElapsed.format(ProcessingElapsed.resolveSourceElapsed(
                    clocks(result.channelResults()),
                    persistedWindow == null ? Optional.empty() : persistedWindow.elapsed(),
                    runElapsed));
        }

        /** True when the elapsed value came from ledger completion order rather than a recorded start. */
        public boolean elapsedDerived() {
            return persistedWindow != null && persistedWindow.derived()
                    && ProcessingElapsed.sourceElapsed(clocks(result.channelResults())).isEmpty()
                    && persistedWindow.elapsed().isPresent();
        }

        public String resultLabel() {
            return result.resultLabel();
        }

        public String resultDisplayText() {
            return result.resultDisplayText();
        }

        public String resultStyle() {
            return result.resultStyle();
        }

        public String resultTooltip() {
            String primary = result.primaryDiagnosticReason();
            if (result.failedChannels() > 0) {
                return result.resultLabel()
                        + (primary.isBlank() ? "" : "\n" + primary)
                        + "\nView Details for full diagnostics";
            }
            if (result.warningChannels() > 0) {
                return ProcessingStatusLabels.COMPLETED
                        + (primary.isBlank() ? "" : "\n" + primary)
                        + "\nView Details for full diagnostics";
            }
            return result.successfulChannels() + " channels completed";
        }

        public boolean hasDiagnostics() {
            return !result.diagnosticResults().isEmpty();
        }

        public int affectedChannels() {
            return result.affectedChannels();
        }

        public boolean canViewSignals() {
            return result.hasSignalCatalog();
        }

        public String tooltip() {
            return tooltip;
        }

        public SourceResult result() {
            return result;
        }
    }

    public static final class JobView {
        private final int number;
        private final ProcessingJob job;
        private final ExecutionJobPresentation execution;
        private final String sourceAlias;
        private final String sourceFile;
        private final String tooltip;

        private JobView(
                int number,
                ProcessingJob job,
                ExecutionJobPresentation execution,
                DisplayLabel label
        ) {
            this.number = number;
            this.job = job;
            this.execution = execution;
            this.sourceAlias = SourceDisplayNameResolver.primaryIdentity(label);
            this.sourceFile = label == null || looksLikeSourceId(label.distinguishingName())
                    ? ""
                    : label.distinguishingName();
            this.tooltip = SourceDisplayNameResolver.identityTooltip(label);
        }

        static JobView fromPlan(int number, ProcessingJob job, DisplayLabel label) {
            return new JobView(number, job, null, label);
        }

        static JobView fromExecution(int number, ExecutionJobPresentation execution, DisplayLabel label) {
            return new JobView(number, execution.planJob(), execution, label);
        }

        public int number() {
            return number;
        }

        public SourceChannelRef channel() {
            return job.channel();
        }

        public ProcessingJobKey jobKey() {
            return job.key();
        }

        public String sourceAlias() {
            return sourceAlias;
        }

        public String sourceFile() {
            return sourceFile;
        }

        public String channelLabel() {
            return "Ch" + job.channelId();
        }

        public String dbcLabel() {
            return job.definitionDisplayName().isBlank() ? "\u2014" : job.definitionDisplayName();
        }

        public String mappingLabel() {
            return job.mappingLifecycle().planLabel();
        }

        public String mappingStyle() {
            return switch (job.mappingLifecycle()) {
                case CONFIRMED -> "status-confirmed";
                case RECOMMENDED, RECOMMENDED_FALLBACK -> "status-recommended";
                case MANUAL -> "processing-mapping-manual";
                default -> "status-attention";
            };
        }

        public String matchedLabel() {
            return INT.format(job.matchedCanIdentityCount()) + " / " + INT.format(job.observedCanIdentityCount());
        }

        public String coverageLabel() {
            return PCT.format(job.coverageRatio() * 100.0) + "%";
        }

        public String framesLabel() {
            long frames = execution == null ? job.frameCount() : execution.displayFrameCount();
            return INT.format(frames);
        }

        public String statusLabel() {
            if (execution != null) {
                return displayExecutionStatus(execution.status());
            }
            return job.executable() ? "Ready" : "Needs Attention";
        }

        public String statusDisplayText() {
            String label = statusLabel();
            IndependentJobResult result = result();
            if (result == null) {
                String blocked = blockedReason();
                return blocked.isBlank() ? label : label + "\n" + shortenStatusReason(blocked);
            }
            String reason = primaryResultReason(result);
            if (reason.isBlank()) {
                return label;
            }
            return label + "\n" + reason;
        }

        public String statusTooltip() {
            if (execution == null) {
                String blocked = blockedReason();
                return blocked.isBlank() ? statusLabel() : statusLabel() + "\n" + blocked;
            }
            IndependentJobResult result = result();
            String primary = result == null ? "" : primaryResultReason(result);
            if (execution.status() == JobExecutionStatus.SUCCESS_WITH_WARNINGS) {
                return ProcessingStatusLabels.COMPLETED
                        + (primary.isBlank() ? "" : "\n" + primary)
                        + "\nView Details for full diagnostics";
            }
            if (execution.status() == JobExecutionStatus.FAILED
                    || execution.status() == JobExecutionStatus.CANCELLED
                    || execution.status() == JobExecutionStatus.BLOCKED) {
                return statusLabel()
                        + (primary.isBlank() ? "" : "\n" + primary)
                        + "\nView Details for full diagnostics";
            }
            if (!blockedReason().isBlank()) {
                return statusLabel() + "\n" + blockedReason();
            }
            return statusLabel();
        }

        private static String primaryResultReason(IndependentJobResult result) {
            if (result == null) {
                return "";
            }
            for (ProcessingPlanDiagnostic diagnostic : result.diagnostics()) {
                String detail = diagnostic.detail() == null ? "" : diagnostic.detail().trim();
                if (!detail.isBlank()) {
                    return shortenStatusReason(detail);
                }
                return shortenStatusReason(diagnostic.code().name().replace('_', ' ').toLowerCase());
            }
            if (!result.technicalDetail().isBlank()) {
                return shortenStatusReason(result.technicalDetail());
            }
            return "";
        }

        private static String shortenStatusReason(String text) {
            String cleaned = text == null ? "" : text.trim().replace('\n', ' ');
            if (cleaned.length() <= 64) {
                return cleaned;
            }
            return cleaned.substring(0, 61) + "...";
        }

        public String statusStyle() {
            if (execution != null) {
                return ProcessingStatusLabels.styleForJob(execution.status());
            }
            return job.executable() ? "badge-ready" : "badge-invalid";
        }

        public boolean needsAttention() {
            return execution == null && !job.executable();
        }

        public String blockedReason() {
            if (!needsAttention()) {
                return "";
            }
            if (!job.diagnostics().isEmpty()) {
                return ProcessingPlanReviewPane.diagnosticText(job.diagnostics().getFirst());
            }
            return "No valid DBC mapping available.";
        }

        public JobExecutionStatus executionStatus() {
            return execution == null ? null : execution.status();
        }

        public String elapsedLabel() {
            if (execution == null) {
                return "";
            }
            return execution.elapsed().map(ProcessingExecutionPane::formatElapsed).orElse("");
        }

        public String selectedFramesLabel() {
            IndependentJobResult result = result();
            if (result == null) {
                return framesLabel();
            }
            return INT.format(result.selectedChannelFrames());
        }

        public String dbcMatchedLabel() {
            IndependentJobResult result = result();
            return result == null ? "\u2014" : INT.format(result.dbcMatchedFrames());
        }

        public String signalsLabel() {
            IndependentJobResult result = result();
            return result == null ? "\u2014" : INT.format(result.signalsProduced());
        }

        public IndependentJobResult result() {
            return execution == null ? null : execution.result().orElse(null);
        }

        public boolean canViewSignals() {
            IndependentJobResult result = result();
            return result != null && result.successful() && result.catalogHandle().isPresent();
        }

        public String tooltip() {
            return tooltip;
        }

        public MappingLifecycle mappingLifecycle() {
            return job.mappingLifecycle();
        }

        public long frameCount() {
            return job.frameCount();
        }

        public double coverageRatio() {
            return job.coverageRatio();
        }
    }

    /**
     * User-facing row status. Internal execution enum names never reach the table.
     */
    static String displayExecutionStatus(JobExecutionStatus status) {
        if (status == null) {
            return "\u2014";
        }
        return ProcessingStatusLabels.forJob(status);
    }
}
