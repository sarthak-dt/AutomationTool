package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ExecutionJobPresentation;
import com.stellar.platform.desktop.presentation.ProcessingSelectionWorkspaceModel;
import com.stellar.platform.desktop.presentation.ProcessingStatusLabels;
import com.stellar.platform.desktop.presentation.SourceAliasRegistry;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver.DisplayLabel;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver.SourceRef;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * PROCESSING RESULTS after multi-source plan execution.
 */
public final class ProcessingExecutionResultPane extends BorderPane {

    private static final NumberFormat INT = NumberFormat.getIntegerInstance(Locale.US);

    private final Label summaryLabel = new Label();
    private final Label jobsMetric = new Label("0");
    private final Label successMetric = new Label("0");
    private final Label warningsMetric = new Label("0");
    private final Label failedMetric = new Label("0");
    private final VBox jobList = new VBox(4);
    private final Button backSelectionButton = new Button("Back to Selection");
    private final Button backPlanButton = new Button("Back to Plan Review");

    private DesktopAppService appService;
    private ProcessingSelectionWorkspaceModel model;
    private Runnable onBackSelection = () -> {
    };
    private Runnable onBackPlan = () -> {
    };
    private Consumer<IndependentJobResult> onViewSignals = r -> {
    };
    private Map<ProjectSourceId, DisplayLabel> labels = Map.of();

    public ProcessingExecutionResultPane() {
        getStyleClass().addAll("page-root", "processing-results-root");
        setId("processing-results-root");

        Label header = new Label("PROCESSING RESULTS");
        header.getStyleClass().add("section-header");
        Label subtitle = new Label("Independent job results — one catalog per successful job.");
        subtitle.getStyleClass().add("section-subtitle");
        subtitle.setWrapText(true);

        summaryLabel.getStyleClass().add("muted");
        summaryLabel.setManaged(false);
        summaryLabel.setVisible(false);

        HBox metricStrip = new HBox(8,
                resultMetric("Total Jobs", jobsMetric, "queued"),
                resultMetric("Successful", successMetric, "ready"),
                resultMetric("Success w/ Warnings", warningsMetric, "warning"),
                resultMetric("Failed", failedMetric, "failed"));
        metricStrip.getStyleClass().add("processing-metric-strip");
        for (javafx.scene.Node metric : metricStrip.getChildren()) {
            HBox.setHgrow(metric, Priority.ALWAYS);
        }

        jobList.getStyleClass().add("execution-result-list");
        ScrollPane scroll = new ScrollPane(jobList);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("processing-selection-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        backSelectionButton.setId("results-back-to-selection-button");
        backSelectionButton.getStyleClass().addAll("secondary-button", "back-to-selection-button");
        backSelectionButton.setOnAction(e -> onBackSelection.run());

        backPlanButton.setId("results-back-to-plan-button");
        backPlanButton.getStyleClass().addAll("secondary-button", "back-to-plan-button");
        backPlanButton.setOnAction(e -> onBackPlan.run());

        HBox actions = new HBox(8, backSelectionButton, backPlanButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, header, subtitle, metricStrip, summaryLabel, scroll, actions);
        root.setPadding(new Insets(4, 4, 8, 4));
        setCenter(root);
    }

    public void bind(
            DesktopAppService app,
            ProcessingSelectionWorkspaceModel workspace,
            Runnable backSelection,
            Runnable backPlan,
            Consumer<IndependentJobResult> viewSignals
    ) {
        this.appService = Objects.requireNonNull(app, "app");
        this.model = Objects.requireNonNull(workspace, "workspace");
        this.onBackSelection = backSelection == null ? () -> {
        } : backSelection;
        this.onBackPlan = backPlan == null ? () -> {
        } : backPlan;
        this.onViewSignals = viewSignals == null ? r -> {
        } : viewSignals;
        rebuildLabels();
        rebuild();
    }

    public void rebuild() {
        if (model == null || appService == null) {
            return;
        }
        MultiSourceProcessingResult batch = model.latestBatch();
        List<ExecutionJobPresentation> jobs = model.executionJobs();
        int total = jobs.isEmpty() && batch != null ? batch.requestedJobs() : jobs.size();
        int success = batch != null ? batch.successfulJobs() : model.successfulJobCount();
        int failed = batch != null ? batch.failedJobs() : model.failedJobCount();
        int warnings = 0;
        for (ExecutionJobPresentation job : jobs) {
            if (job.status() == JobExecutionStatus.SUCCESS_WITH_WARNINGS) warnings++;
        }
        int cleanSuccess = Math.max(0, success - warnings);
        jobsMetric.setText(INT.format(total));
        successMetric.setText(INT.format(cleanSuccess));
        warningsMetric.setText(INT.format(warnings));
        failedMetric.setText(INT.format(failed));
        summaryLabel.setText(total + " jobs · " + success + " successful · " + failed + " failed");

        List<javafx.scene.Node> rows = new ArrayList<>();
        for (ExecutionJobPresentation row : jobs) {
            rows.add(buildRow(row));
        }
        jobList.getChildren().setAll(rows);
    }

    private VBox buildRow(ExecutionJobPresentation row) {
        ProcessingJob job = row.planJob();
        IndependentJobResult result = row.result().orElse(null);
        ProjectSourceId sourceId = ProjectSourceId.of(job.projectSourceId());
        DisplayLabel label = labels.get(sourceId);
        String source = label == null
                ? job.projectSourceId()
                : SourceDisplayNameResolver.primaryIdentity(label);
        String channel = "Ch" + job.channelId();
        String dbc = job.definitionDisplayName().isBlank() ? "—" : job.definitionDisplayName();

        Label identity = new Label(source + " · " + channel + " · " + dbc);
        identity.getStyleClass().add("execution-job-identity");

        JobExecutionStatus status = result == null ? row.status() : result.status();
        Label badge = new Label(userFacingStatus(status));
        badge.getStyleClass().addAll("badge", ProcessingExecutionPane.statusStyle(status));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(10, identity, spacer, badge);
        top.setAlignment(Pos.CENTER_LEFT);

        HBox metricsBox = new HBox(18);
        metricsBox.getStyleClass().add("execution-result-metrics");
        if (result != null) {
            metricsBox.getChildren().addAll(
                    resultMetricBlock("Selected Frames", INT.format(result.selectedChannelFrames())),
                    resultMetricBlock("DBC Matched", INT.format(result.dbcMatchedFrames())),
                    resultMetricBlock("Signals Produced", INT.format(result.signalsProduced())),
                    resultMetricBlock("Elapsed", ProcessingExecutionPane.formatElapsed(result.elapsed())));
        } else {
            metricsBox.getChildren().add(resultMetricBlock("Frames", INT.format(row.displayFrameCount())));
        }

        VBox box = new VBox(7, top, metricsBox);
        box.getStyleClass().add("execution-result-row");
        box.setPadding(new Insets(6, 6, 6, 6));

        if (result != null) {
            appendDiagnosticSummary(box, result, source, channel, dbc, status);
        }

        boolean canView = result != null && result.successful() && result.catalogHandle().isPresent();
        if (canView) {
            Button view = new Button("View Signals");
            view.getStyleClass().addAll("secondary-button", "compact-button", "view-signals-button");
            IndependentJobResult captured = result;
            view.setOnAction(e -> onViewSignals.accept(captured));
            box.getChildren().add(view);
        }
        return box;
    }

    /**
     * Surfaces real job diagnostics so the user can answer "what went wrong?" without logs.
     * Job status remains authoritative; individual diagnostics are not reclassified as warnings/errors.
     */
    private void appendDiagnosticSummary(
            VBox box,
            IndependentJobResult result,
            String source,
            String channel,
            String dbc,
            JobExecutionStatus status
    ) {
        List<ProcessingPlanDiagnostic> diagnostics = result.diagnostics();
        String technical = result.technicalDetail();
        boolean hasDiagnostics = diagnostics != null && !diagnostics.isEmpty();
        boolean hasTechnical = technical != null && !technical.isBlank();
        if (!hasDiagnostics && !hasTechnical) {
            return;
        }

        if (hasDiagnostics) {
            Label count = new Label(diagnostics.size()
                    + (diagnostics.size() == 1 ? " diagnostic" : " diagnostics"));
            count.getStyleClass().addAll("muted", "execution-diagnostic");
            box.getChildren().add(count);
            int shown = Math.min(3, diagnostics.size());
            for (int i = 0; i < shown; i++) {
                Label line = new Label("• " + conciseDiagnostic(diagnostics.get(i)));
                line.getStyleClass().addAll("muted", "execution-diagnostic");
                line.setWrapText(true);
                box.getChildren().add(line);
            }
            if (diagnostics.size() > shown) {
                Label more = new Label("… and " + (diagnostics.size() - shown) + " more");
                more.getStyleClass().addAll("muted", "execution-diagnostic");
                box.getChildren().add(more);
            }
        } else if (status == JobExecutionStatus.FAILED && hasTechnical) {
            Label line = new Label("• " + firstLine(technical));
            line.getStyleClass().addAll("muted", "execution-diagnostic");
            line.setWrapText(true);
            box.getChildren().add(line);
        }

        String actionLabel = status == JobExecutionStatus.FAILED
                ? "View error"
                : (status == JobExecutionStatus.SUCCESS_WITH_WARNINGS ? "View warnings" : "Details");
        Button details = new Button(actionLabel);
        details.getStyleClass().addAll("secondary-button", "compact-button");
        IndependentJobResult captured = result;
        String titleSource = source + " · " + channel + " · " + dbc;
        details.setOnAction(e -> showDiagnosticDetails(titleSource, captured, status));
        box.getChildren().add(details);
    }

    private void showDiagnosticDetails(String identity, IndependentJobResult result, JobExecutionStatus status) {
        StringBuilder body = new StringBuilder();
        body.append(identity).append('\n');
        body.append(userFacingStatus(status)).append('\n');
        body.append("Selected frames: ").append(result.selectedChannelFrames()).append('\n');
        body.append("DBC matched: ").append(result.dbcMatchedFrames()).append('\n');
        body.append("Signals produced: ").append(result.signalsProduced()).append('\n');
        if (!result.diagnostics().isEmpty()) {
            body.append('\n').append("Diagnostics:").append('\n');
            for (ProcessingPlanDiagnostic d : result.diagnostics()) {
                body.append("• ").append(ProcessingPlanReviewPane.diagnosticText(d)).append('\n');
            }
        }
        if (result.technicalDetail() != null && !result.technicalDetail().isBlank()) {
            body.append('\n').append("Technical details:").append('\n');
            body.append(result.technicalDetail().trim()).append('\n');
        }

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(status == JobExecutionStatus.FAILED ? "Processing error" : "Processing details");
        alert.setHeaderText(identity + " — " + userFacingStatus(status));
        TextArea area = new TextArea(body.toString().trim());
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefRowCount(16);
        area.setPrefColumnCount(60);
        alert.getDialogPane().setContent(area);
        alert.getButtonTypes().setAll(ButtonType.CLOSE);
        alert.showAndWait();
    }

    static String userFacingStatus(JobExecutionStatus status) {
        if (status == null) {
            return "—";
        }
        return ProcessingStatusLabels.forJob(status);
    }

    private static String conciseDiagnostic(ProcessingPlanDiagnostic diagnostic) {
        if (diagnostic == null) {
            return "";
        }
        String code = diagnostic.code() == null ? "DIAGNOSTIC" : diagnostic.code().name();
        String detail = diagnostic.detail() == null ? "" : diagnostic.detail().trim();
        if (detail.isEmpty()) {
            return code;
        }
        return code + " — " + detail;
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String trimmed = text.trim();
        int nl = trimmed.indexOf('\n');
        return nl < 0 ? trimmed : trimmed.substring(0, nl).trim();
    }

    private static VBox resultMetric(String title, Label value, String semantic) {
        Label caption = new Label(title);
        caption.getStyleClass().add("processing-metric-title");
        value.getStyleClass().add("processing-metric-value");
        VBox card = new VBox(3, caption, value);
        card.getStyleClass().addAll("processing-metric-card", "execution-metric-" + semantic);
        card.setMinWidth(140);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static VBox resultMetricBlock(String title, String value) {
        Label caption = new Label(title);
        caption.getStyleClass().add("execution-result-metric-title");
        Label data = new Label(value == null || value.isBlank() ? "—" : value);
        data.getStyleClass().add("execution-result-metric-value");
        VBox block = new VBox(2, caption, data);
        block.setMinWidth(120);
        return block;
    }

    private void rebuildLabels() {
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
}
