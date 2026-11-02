package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.SourceAliasRegistry;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver.DisplayLabel;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver.SourceRef;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProcessingPlanSummary;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Multi-source execution plan review. Displays immutable {@link ProjectProcessingPlan} jobs only.
 */
public final class ProcessingPlanReviewPane extends BorderPane {

    private static final NumberFormat INT = NumberFormat.getIntegerInstance(Locale.US);
    private static final NumberFormat PCT = NumberFormat.getNumberInstance(Locale.US);
    private static final Set<ProcessingPlanDiagnosticCode> MAPPING_CODES = EnumSet.of(
            ProcessingPlanDiagnosticCode.UNCONFIRMED_DEFINITION,
            ProcessingPlanDiagnosticCode.NO_EFFECTIVE_DEFINITION,
            ProcessingPlanDiagnosticCode.AMBIGUOUS_DEFINITION,
            ProcessingPlanDiagnosticCode.DEFINITION_NOT_AVAILABLE
    );

    static {
        PCT.setMaximumFractionDigits(1);
        PCT.setMinimumFractionDigits(0);
    }

    private final Label planSummaryLabel = new Label();
    private final Label planStatusBadge = new Label();
    private final TableView<JobRow> jobsTable = new TableView<>();
    private final Button backButton = new Button("Back to Selection");
    private final Button reviewDbcButton = new Button("Review DBC Mapping");
    private final Button executePlanButton = new Button("Execute Plan");
    private final Label blockerLabel = new Label();
    private final Label reviewRequiredLabel = new Label();

    private DesktopAppService appService;
    private ProjectProcessingPlan plan = ProjectProcessingPlan.empty();
    private Runnable onBack = () -> {
    };
    private Consumer<SourceChannelRef> onReviewDbc = ref -> {
    };
    private Runnable onExecute = () -> {
    };
    private Map<ProjectSourceId, DisplayLabel> labels = Map.of();
    private String reviewRequiredMessage;

    public ProcessingPlanReviewPane() {
        getStyleClass().addAll("page-root", "processing-plan-review-root");
        setId("processing-plan-review-root");

        Label header = new Label("MULTI-SOURCE EXECUTION PLAN");
        header.getStyleClass().add("section-header");
        Label subtitle = new Label(
                "Validated processing jobs from the current selection. Job order is plan order, not timeline order.");
        subtitle.getStyleClass().add("section-subtitle");
        subtitle.setWrapText(true);

        planSummaryLabel.getStyleClass().add("muted");
        planSummaryLabel.setWrapText(true);
        planStatusBadge.setId("plan-status-badge");
        planStatusBadge.getStyleClass().addAll("badge", "plan-status-badge");

        HBox statusRow = new HBox(10, planSummaryLabel, new Region(), planStatusBadge);
        HBox.setHgrow(statusRow.getChildren().get(1), Priority.ALWAYS);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        configureTable();
        jobsTable.getStyleClass().add("plan-jobs-table");
        jobsTable.setId("plan-jobs-table");
        jobsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(jobsTable, Priority.ALWAYS);

        blockerLabel.getStyleClass().add("muted");
        blockerLabel.setWrapText(true);

        backButton.setId("back-to-selection-button");
        backButton.getStyleClass().addAll("secondary-button", "back-to-selection-button");
        backButton.setOnAction(e -> {
            if (onBack != null) {
                onBack.run();
            }
        });

        reviewDbcButton.setId("review-dbc-mapping-button");
        reviewDbcButton.getStyleClass().addAll("secondary-button", "review-dbc-mapping-button");
        reviewDbcButton.setOnAction(e -> openMappingForSelectedOrFirstBlocker());

        executePlanButton.setId("execute-plan-button");
        executePlanButton.getStyleClass().addAll("primary-button", "execute-plan-button");
        executePlanButton.setOnAction(e -> {
            if (onExecute != null) {
                onExecute.run();
            }
        });

        reviewRequiredLabel.setId("plan-requires-review-label");
        reviewRequiredLabel.getStyleClass().addAll("muted", "plan-requires-review-label");
        reviewRequiredLabel.setWrapText(true);
        reviewRequiredLabel.setVisible(false);
        reviewRequiredLabel.setManaged(false);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(8, backButton, reviewDbcButton, spacer, executePlanButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, header, subtitle, statusRow, jobsTable, blockerLabel, reviewRequiredLabel, actions);
        root.setPadding(new Insets(4, 4, 8, 4));
        setCenter(root);
    }

    public void bind(
            DesktopAppService app,
            ProjectProcessingPlan processingPlan,
            Runnable backCallback,
            Consumer<SourceChannelRef> reviewDbcCallback
    ) {
        bind(app, processingPlan, backCallback, reviewDbcCallback, null, null);
    }

    public void bind(
            DesktopAppService app,
            ProjectProcessingPlan processingPlan,
            Runnable backCallback,
            Consumer<SourceChannelRef> reviewDbcCallback,
            Runnable executeCallback
    ) {
        bind(app, processingPlan, backCallback, reviewDbcCallback, executeCallback, null);
    }

    public void bind(
            DesktopAppService app,
            ProjectProcessingPlan processingPlan,
            Runnable backCallback,
            Consumer<SourceChannelRef> reviewDbcCallback,
            Runnable executeCallback,
            String requiresReviewMessage
    ) {
        this.appService = Objects.requireNonNull(app, "app");
        this.plan = processingPlan == null ? ProjectProcessingPlan.empty() : processingPlan;
        this.onBack = backCallback == null ? () -> {
        } : backCallback;
        this.onReviewDbc = reviewDbcCallback == null ? ref -> {
        } : reviewDbcCallback;
        this.onExecute = executeCallback == null ? () -> {
        } : executeCallback;
        this.reviewRequiredMessage = requiresReviewMessage;
        rebuildLabels();
        rebuild();
    }

    private void rebuildLabels() {
        SourceAliasRegistry aliases = appService.sourceAliasRegistry();
        List<SourceRef> refs = new ArrayList<>();
        for (WorkspaceFile file : appService.workspace().measurementFiles()) {
            aliases.aliasFor(file.projectSourceId());
            refs.add(new SourceRef(file.projectSourceId(), file.fileName(), file.path()));
        }
        Map<ProjectSourceId, DisplayLabel> resolved = SourceDisplayNameResolver.resolve(refs);
        // Prefer registry aliases for stable Source A/B/C
        java.util.LinkedHashMap<ProjectSourceId, DisplayLabel> merged = new java.util.LinkedHashMap<>();
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

    private void rebuild() {
        ProcessingPlanSummary summary = plan.summary();
        planSummaryLabel.setText(
                summary.processingJobs() + " Processing Jobs · "
                        + summary.selectedSources() + " sources · "
                        + summary.selectedChannels() + " channels · "
                        + "Selected Source-Channel Frames: " + INT.format(summary.selectedFrames()));

        String statusText = displayReadiness(plan.readiness());
        planStatusBadge.setText(statusText);
        planStatusBadge.getStyleClass().setAll("badge", "plan-status-badge", readinessStyle(plan.readiness()));

        List<JobRow> rows = new ArrayList<>();
        int index = 1;
        for (ProcessingJob job : plan.jobs()) {
            rows.add(JobRow.from(index++, job, labels));
        }
        jobsTable.setItems(FXCollections.observableArrayList(rows));

        String blockers = formatBlockers(plan);
        blockerLabel.setText(blockers);
        blockerLabel.setVisible(!blockers.isBlank());
        blockerLabel.setManaged(!blockers.isBlank());

        boolean hasMappingBlocker = plan.jobs().stream().anyMatch(ProcessingPlanReviewPane::hasMappingBlocker);
        reviewDbcButton.setDisable(!hasMappingBlocker && plan.jobs().isEmpty());
        if (!hasMappingBlocker) {
            reviewDbcButton.setDisable(true);
        }

        boolean canExecute = isExecuteEnabled(plan);
        executePlanButton.setDisable(!canExecute);
        if (canExecute) {
            executePlanButton.setTooltip(null);
        } else {
            executePlanButton.setTooltip(new Tooltip(executeDisabledReason(plan)));
        }

        boolean showReviewMsg = reviewRequiredMessage != null && !reviewRequiredMessage.isBlank();
        reviewRequiredLabel.setText(showReviewMsg ? reviewRequiredMessage : "");
        reviewRequiredLabel.setVisible(showReviewMsg);
        reviewRequiredLabel.setManaged(showReviewMsg);
    }

    static boolean isExecuteEnabled(ProjectProcessingPlan plan) {
        if (plan == null || plan.jobs().isEmpty()) {
            return false;
        }
        if (plan.readiness() != ProcessingPlanReadiness.READY) {
            return false;
        }
        return plan.jobs().stream().allMatch(ProcessingJob::executable);
    }

    static String executeDisabledReason(ProjectProcessingPlan plan) {
        if (plan == null || plan.jobs().isEmpty()) {
            return "No processing jobs in plan.";
        }
        if (plan.readiness() != ProcessingPlanReadiness.READY) {
            return "Plan is not READY. Resolve mapping / inventory blockers before execute.";
        }
        for (ProcessingJob job : plan.jobs()) {
            if (!job.executable()) {
                return "Not all jobs are executable. Confirm DBC mappings for blocked channels.";
            }
        }
        return "Execute Plan is unavailable.";
    }

    private void configureTable() {
        TableColumn<JobRow, Number> jobCol = new TableColumn<>("Job#");
        jobCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().jobNumber()));
        jobCol.setMinWidth(50);
        jobCol.setPrefWidth(60);

        TableColumn<JobRow, String> sourceCol = new TableColumn<>("Source");
        sourceCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().sourceLabel()));
        sourceCol.setMinWidth(120);
        sourceCol.setPrefWidth(160);
        sourceCol.setCellFactory(col -> tipCell());

        TableColumn<JobRow, String> channelCol = new TableColumn<>("Channel");
        channelCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().channelLabel()));
        channelCol.setMinWidth(80);
        channelCol.setPrefWidth(100);

        TableColumn<JobRow, String> dbcCol = new TableColumn<>("DBC");
        dbcCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().dbcLabel()));
        dbcCol.setMinWidth(100);
        dbcCol.setPrefWidth(140);
        dbcCol.setCellFactory(col -> tipCell());

        TableColumn<JobRow, String> mappingCol = new TableColumn<>("Mapping");
        mappingCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().mappingLabel()));
        mappingCol.setMinWidth(90);
        mappingCol.setPrefWidth(110);

        TableColumn<JobRow, String> observedCol = new TableColumn<>("Observed");
        observedCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().observedLabel()));
        observedCol.setMinWidth(70);
        observedCol.setPrefWidth(80);

        TableColumn<JobRow, String> matchedCol = new TableColumn<>("Matched");
        matchedCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().matchedLabel()));
        matchedCol.setMinWidth(70);
        matchedCol.setPrefWidth(80);

        TableColumn<JobRow, String> coverageCol = new TableColumn<>("Coverage");
        coverageCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().coverageLabel()));
        coverageCol.setMinWidth(70);
        coverageCol.setPrefWidth(80);

        TableColumn<JobRow, String> framesCol = new TableColumn<>("Frames");
        framesCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().framesLabel()));
        framesCol.setMinWidth(80);
        framesCol.setPrefWidth(100);

        TableColumn<JobRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> new ReadOnlyStringWrapper(c.getValue().statusLabel()));
        statusCol.setMinWidth(120);
        statusCol.setPrefWidth(150);
        statusCol.setCellFactory(col -> tipCell());

        jobsTable.getColumns().setAll(
                jobCol, sourceCol, channelCol, dbcCol, mappingCol,
                observedCol, matchedCol, coverageCol, framesCol, statusCol);
        jobsTable.setPlaceholder(new Label("No processing jobs in plan."));
        jobsTable.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            reviewDbcButton.setDisable(b == null || !b.mappingRelated());
        });
    }

    private static TableCell<JobRow, String> tipCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    JobRow row = getTableRow() == null ? null : getTableRow().getItem();
                    String tip = row == null ? item : row.tooltip();
                    setTooltip(new Tooltip(tip));
                }
            }
        };
    }

    private void openMappingForSelectedOrFirstBlocker() {
        JobRow selected = jobsTable.getSelectionModel().getSelectedItem();
        if (selected != null && selected.mappingRelated()) {
            onReviewDbc.accept(selected.channel());
            return;
        }
        for (ProcessingJob job : plan.jobs()) {
            if (hasMappingBlocker(job)) {
                onReviewDbc.accept(job.channel());
                return;
            }
        }
    }

    static String displayReadiness(ProcessingPlanReadiness readiness) {
        if (readiness == null) {
            return "EMPTY";
        }
        return switch (readiness) {
            case READY -> "READY";
            case EMPTY -> "EMPTY";
            case REQUIRES_CONFIRMATION, INVALID_SELECTION -> "REQUIRES ATTENTION";
        };
    }

    private static String readinessStyle(ProcessingPlanReadiness readiness) {
        if (readiness == ProcessingPlanReadiness.READY) {
            return "badge-ready";
        }
        if (readiness == ProcessingPlanReadiness.EMPTY) {
            return "badge-unsupported";
        }
        return "badge-invalid";
    }

    private static boolean hasMappingBlocker(ProcessingJob job) {
        for (ProcessingPlanDiagnostic d : job.diagnostics()) {
            if (MAPPING_CODES.contains(d.code())) {
                return true;
            }
        }
        return false;
    }

    private static String formatBlockers(ProjectProcessingPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (ProcessingJob job : plan.jobs()) {
            for (ProcessingPlanDiagnostic d : job.diagnostics()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(diagnosticText(d));
            }
        }
        for (ProcessingPlanDiagnostic d : plan.diagnostics()) {
            if (d.channel().isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(diagnosticText(d));
            }
        }
        return sb.toString();
    }

    static String diagnosticText(ProcessingPlanDiagnostic diagnostic) {
        String channel = diagnostic.channel().map(SourceChannelRef::toString).orElse("");
        String detail = diagnostic.detail() == null || diagnostic.detail().isBlank()
                ? ""
                : " — " + diagnostic.detail();
        String prefix = channel.isBlank() ? "" : channel + ": ";
        return prefix + diagnostic.code().name().replace('_', ' ') + detail;
    }

    record JobRow(
            int jobNumber,
            SourceChannelRef channel,
            String sourceLabel,
            String channelLabel,
            String dbcLabel,
            String mappingLabel,
            String observedLabel,
            String matchedLabel,
            String coverageLabel,
            String framesLabel,
            String statusLabel,
            String tooltip,
            boolean mappingRelated
    ) {
        static JobRow from(int number, ProcessingJob job, Map<ProjectSourceId, DisplayLabel> labels) {
            ProjectSourceId sourceId = ProjectSourceId.of(job.projectSourceId());
            DisplayLabel label = labels.get(sourceId);
            String source = label == null
                    ? job.projectSourceId()
                    : SourceDisplayNameResolver.primaryIdentity(label);
            String channelName = "Channel " + job.channelId();
            String dbc = job.definitionDisplayName().isBlank() ? "—" : job.definitionDisplayName();
            String mapping = job.mappingLifecycle().planLabel();
            String status = job.executable()
                    ? "READY"
                    : (job.mappingLifecycle() == MappingLifecycle.RECOMMENDED
                    ? "REQUIRES CONFIRMATION"
                    : "BLOCKED");
            String tip = source + " / " + channelName
                    + "\nPlan job #" + number
                    + "\n" + status
                    + (job.diagnostics().isEmpty()
                    ? ""
                    : "\n" + job.diagnostics().stream().map(ProcessingPlanReviewPane::diagnosticText)
                    .reduce((a, b) -> a + "\n" + b).orElse(""));
            return new JobRow(
                    number,
                    job.channel(),
                    source,
                    channelName,
                    dbc,
                    mapping,
                    INT.format(job.observedCanIdentityCount()),
                    INT.format(job.matchedCanIdentityCount()),
                    PCT.format(job.coverageRatio() * 100.0) + "%",
                    INT.format(job.frameCount()),
                    status,
                    tip,
                    hasMappingBlocker(job)
            );
        }
    }

    Button backButtonForTest() {
        return backButton;
    }

    Button executePlanButtonForTest() {
        return executePlanButton;
    }

    TableView<JobRow> jobsTableForTest() {
        return jobsTable;
    }
}