package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProcessingSelectionWorkspaceModel;
import com.stellar.platform.desktop.presentation.SourceAliasRegistry;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver.DisplayLabel;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver.SourceRef;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProjectChannelSelection;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.processing.plan.ProcessingPlanBuilder;
import com.stellar.platform.processing.plan.ProcessingPlanInputs;
import com.stellar.platform.processing.plan.SourcePlanInput;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Dense Processing Selection workspace: source-grouped channel rows bound by {@link SourceChannelRef}.
 */
public final class ProcessingSelectionPane extends BorderPane {

    private static final NumberFormat INT = NumberFormat.getIntegerInstance(Locale.US);
    private static final NumberFormat PCT = NumberFormat.getNumberInstance(Locale.US);

    static {
        PCT.setMaximumFractionDigits(1);
        PCT.setMinimumFractionDigits(0);
    }

    private final VBox rootBox = new VBox(10);
    private final Label projectSummaryLabel = new Label();
    private final Label selectionSummaryLabel = new Label();
    private final Label selectedSourcesMetric = new Label("0");
    private final Label selectedChannelsMetric = new Label("0");
    private final Label totalJobsMetric = new Label("0");
    private final Label selectedFramesMetric = new Label("0");
    private final Label readyMetric = new Label("0");
    private final Label attentionMetric = new Label("0");
    private final Button selectAllReadyButton = new Button("Select All Ready");
    private final Button clearSelectionButton = new Button("Clear Selection");
    private final Button reviewPlanButton = new Button("Review Processing Plan");
    private final VBox channelList = new VBox(8);
    private final ScrollPane channelScroll = new ScrollPane(channelList);
    private final VBox livePlanRows = new VBox(0);
    private final ScrollPane livePlanScroll = new ScrollPane(livePlanRows);
    private final Label livePlanCountLabel = new Label("0 jobs selected");
    private final Label livePlanSummaryLabel = new Label("Select ready channels to build the live execution plan.");

    private DesktopAppService appService;
    private ProcessingSelectionWorkspaceModel model;
    private ProcessingPlanInputs inputs = new ProcessingPlanInputs(List.of(), List.of());
    private Runnable onReviewPlan = () -> {
    };
    private Map<SourceChannelRef, ProcessingJob> previewJobs = Map.of();
    private boolean syncingUi;

    public ProcessingSelectionPane() {
        getStyleClass().addAll("page-root", "processing-selection-root");
        setId("processing-selection-root");

        Label header = new Label("Processing Center");
        header.getStyleClass().add("processing-center-title");
        Label modePill = new Label("Processing Selection + Plan Review");
        modePill.getStyleClass().addAll("badge", "processing-center-mode-pill");
        HBox headerLine = new HBox(8, header, modePill);
        headerLine.setAlignment(Pos.CENTER_LEFT);

        Label subtitle = new Label(
                "Select ready sources and channels to build a multi-source processing plan. Your selections update the execution plan on the right in real time.");
        subtitle.getStyleClass().add("section-subtitle");
        subtitle.setWrapText(true);

        projectSummaryLabel.getStyleClass().add("muted");
        selectionSummaryLabel.setId("selection-summary-label");
        selectionSummaryLabel.getStyleClass().addAll("processing-selection-summary", "muted");
        selectionSummaryLabel.setWrapText(true);
        // Retain the legacy/test-facing summary text without duplicating the modern metric strip.
        selectionSummaryLabel.setManaged(false);
        selectionSummaryLabel.setVisible(false);

        HBox metricStrip = new HBox(8,
                metricCard("Selected Sources", selectedSourcesMetric, "measurement"),
                metricCard("Selected Channels", selectedChannelsMetric, "channel"),
                metricCard("Total Jobs", totalJobsMetric, "definition"),
                metricCard("Total Frames", selectedFramesMetric, "measurement"),
                metricCard("Ready (No Issues)", readyMetric, "ready"),
                metricCard("Requires Attention", attentionMetric, "attention"));
        metricStrip.getStyleClass().add("processing-metric-strip");
        for (javafx.scene.Node metric : metricStrip.getChildren()) {
            HBox.setHgrow(metric, Priority.ALWAYS);
        }

        selectAllReadyButton.setId("select-all-ready-button");
        selectAllReadyButton.getStyleClass().add("secondary-button");
        selectAllReadyButton.setOnAction(e -> onSelectAllReady());

        clearSelectionButton.setId("clear-selection-button");
        clearSelectionButton.getStyleClass().add("secondary-button");
        clearSelectionButton.setOnAction(e -> {
            if (model != null) {
                model.clearSelection();
            }
        });

        reviewPlanButton.setId("review-plan-button");
        reviewPlanButton.getStyleClass().add("primary-button");
        reviewPlanButton.setOnAction(e -> {
            if (onReviewPlan != null) {
                onReviewPlan.run();
            }
        });

        HBox bulk = new HBox(8, selectAllReadyButton, clearSelectionButton);
        bulk.setAlignment(Pos.CENTER_LEFT);

        channelScroll.setFitToWidth(true);
        channelScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        channelScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        channelScroll.getStyleClass().add("processing-selection-scroll");
        VBox.setVgrow(channelScroll, Priority.ALWAYS);

        VBox selectionColumn = new VBox(8, bulk, channelScroll);
        selectionColumn.getStyleClass().add("processing-selection-column");
        selectionColumn.setMinWidth(520);
        selectionColumn.setPrefWidth(690);
        selectionColumn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(selectionColumn, Priority.ALWAYS);

        Label planTitle = new Label("Live Multi-Source Execution Plan");
        planTitle.getStyleClass().add("modern-card-title");
        livePlanCountLabel.getStyleClass().addAll("badge", "definition-mini-badge");
        Label autoUpdating = new Label("Auto-updating");
        autoUpdating.getStyleClass().addAll("badge", "neutral-mini-badge");
        Region planHeaderSpacer = new Region();
        HBox.setHgrow(planHeaderSpacer, Priority.ALWAYS);
        HBox planHeader = new HBox(8, planTitle, livePlanCountLabel, planHeaderSpacer, autoUpdating);
        planHeader.setAlignment(Pos.CENTER_LEFT);

        livePlanScroll.setFitToWidth(true);
        livePlanScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        livePlanScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        livePlanScroll.getStyleClass().addAll("processing-selection-scroll", "processing-live-plan-scroll");
        VBox.setVgrow(livePlanScroll, Priority.ALWAYS);
        livePlanSummaryLabel.getStyleClass().addAll("muted", "processing-live-plan-summary");
        livePlanSummaryLabel.setWrapText(true);

        Region planFooterSpacer = new Region();
        HBox.setHgrow(planFooterSpacer, Priority.ALWAYS);
        HBox planFooter = new HBox(8, livePlanSummaryLabel, planFooterSpacer, reviewPlanButton);
        planFooter.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(livePlanSummaryLabel, Priority.ALWAYS);

        VBox planColumn = new VBox(8, planHeader, livePlanScroll, planFooter);
        planColumn.getStyleClass().addAll("modern-card", "processing-live-plan");
        planColumn.setMinWidth(500);
        planColumn.setPrefWidth(650);
        planColumn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(planColumn, Priority.ALWAYS);

        HBox workspace = new HBox(10, selectionColumn, planColumn);
        workspace.getStyleClass().add("processing-center-workspace");
        HBox.setHgrow(selectionColumn, Priority.ALWAYS);
        HBox.setHgrow(planColumn, Priority.ALWAYS);
        VBox.setVgrow(workspace, Priority.ALWAYS);

        rootBox.getChildren().addAll(headerLine, subtitle, projectSummaryLabel, metricStrip, selectionSummaryLabel, workspace);
        rootBox.setPadding(new Insets(4, 4, 8, 4));
        VBox.setVgrow(workspace, Priority.ALWAYS);
        setCenter(rootBox);
        BorderPane.setMargin(rootBox, new Insets(0));
    }

    public void bind(
            DesktopAppService app,
            ProcessingSelectionWorkspaceModel workspaceModel,
            ProcessingPlanInputs planInputs,
            Runnable reviewCallback
    ) {
        this.appService = Objects.requireNonNull(app, "app");
        this.model = Objects.requireNonNull(workspaceModel, "workspaceModel");
        this.inputs = planInputs == null ? new ProcessingPlanInputs(List.of(), List.of()) : planInputs;
        this.onReviewPlan = reviewCallback == null ? () -> {
        } : reviewCallback;
        rebuild();
    }

    public void rebuild() {
        if (appService == null || model == null) {
            return;
        }
        previewJobs = buildPreviewJobs(inputs);
        List<WorkspaceFile> measurements = appService.workspace().measurementFiles();
        int channelCount = 0;
        for (SourcePlanInput source : inputs.sources()) {
            channelCount += source.inventory().channels().size();
        }
        int definitionCount = appService.workspace().definitionFiles().size();
        projectSummaryLabel.setText(
                measurements.size() + " Measurement Sources · "
                        + channelCount + " CAN Channels · "
                        + definitionCount + " Definitions");

        updateSelectionSummary();
        reviewPlanButton.setDisable(model.selection().isEmpty());

        channelList.getChildren().clear();
        if (measurements.isEmpty()) {
            Label empty = new Label("No measurement sources are available.");
            empty.getStyleClass().add("muted");
            channelList.getChildren().add(empty);
            rebuildLivePlan();
            return;
        }

        SourceAliasRegistry aliases = appService.sourceAliasRegistry();
        List<SourceRef> refs = new ArrayList<>();
        for (WorkspaceFile file : measurements) {
            aliases.aliasFor(file.projectSourceId());
            refs.add(new SourceRef(file.projectSourceId(), file.fileName(), file.path()));
        }
        Map<ProjectSourceId, DisplayLabel> labels = SourceDisplayNameResolver.resolve(refs);

        for (WorkspaceFile file : measurements) {
            channelList.getChildren().add(buildSourceGroup(file, aliases, labels));
        }
        rebuildLivePlan();
    }

    private VBox buildSourceGroup(
            WorkspaceFile file,
            SourceAliasRegistry aliases,
            Map<ProjectSourceId, DisplayLabel> labels
    ) {
        ProjectSourceId sourceId = file.projectSourceId();
        DisplayLabel resolved = labels.get(sourceId);
        String primary = resolved == null
                ? file.fileName()
                : SourceDisplayNameResolver.primaryIdentity(resolved);
        String fullPath = resolved == null
                ? file.path().toAbsolutePath().normalize().toString()
                : resolved.fullPath();

        CanSourceInventory inventory = inputs.source(file.id())
                .map(SourcePlanInput::inventory)
                .orElseGet(() -> CanSourceInventory.empty(
                        file.path(),
                        com.stellar.platform.domain.MeasurementSourceFormat.UNKNOWN,
                        CanInventoryStatus.FILE_ACCEPTED));

        VBox group = new VBox(4);
        group.getStyleClass().add("processing-source-group");
        group.setUserData(file.id());

        HBox header = new HBox(8);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label(primary);
        title.getStyleClass().add("processing-source-title");
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        Tooltip.install(title, new Tooltip(primary + "\n" + fullPath));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button selectReady = new Button("Select Ready Channels");
        selectReady.getStyleClass().addAll("secondary-button", "compact-button", "processing-select-ready-source");
        selectReady.setDisable(inventory.status() != CanInventoryStatus.INVENTORY_READY);
        selectReady.setOnAction(e -> selectReadyForSource(file.id(), inventory));
        Label status = new Label(inventory.status() == CanInventoryStatus.INVENTORY_READY
                ? inventory.channels().size() + " channels"
                : "Inventory " + inventory.status().name().replace('_', ' '));
        status.getStyleClass().add("muted");
        header.getChildren().addAll(title, spacer, status, selectReady);
        group.getChildren().add(header);

        if (inventory.status() != CanInventoryStatus.INVENTORY_READY) {
            Label unavailable = new Label("Channel inventory not available for selection.");
            unavailable.getStyleClass().add("muted");
            group.getChildren().add(unavailable);
            return group;
        }

        group.getChildren().add(columnHeaderRow());
        for (CanChannelInventory channel : inventory.channels()) {
            SourceChannelRef ref = SourceChannelRef.of(file.id(), channel.channelId());
            group.getChildren().add(buildChannelRow(ref, channel, inventory));
        }
        return group;
    }

    private GridPane columnHeaderRow() {
        GridPane header = baseRowGrid();
        header.getStyleClass().add("processing-selection-col-header");
        header.add(muted("Select"), 0, 0);
        header.add(muted("Channel"), 1, 0);
        header.add(muted("Observed IDs"), 2, 0);
        header.add(muted("DBC Matched"), 3, 0);
        header.add(muted("Coverage"), 4, 0);
        header.add(muted("Frames"), 5, 0);
        header.add(muted("Effective DBC"), 6, 0);
        header.add(muted("Mapping State"), 7, 0);
        header.add(muted("Readiness"), 8, 0);
        return header;
    }

    private GridPane buildChannelRow(SourceChannelRef ref, CanChannelInventory channel, CanSourceInventory inventory) {
        ProcessingJob job = previewJobs.get(ref);
        GridPane row = baseRowGrid();
        row.getStyleClass().add("processing-selection-row");
        row.setUserData(ref);

        CheckBox check = new CheckBox();
        check.getStyleClass().add("processing-channel-checkbox");
        check.setUserData(ref);
        boolean selectable = inventory.status() == CanInventoryStatus.INVENTORY_READY;
        check.setDisable(!selectable);
        if (!selectable) {
            check.setTooltip(new Tooltip("Inventory not ready — channel cannot be selected."));
        }
        syncingUi = true;
        try {
            check.setSelected(model != null && model.selection().contains(ref));
        } finally {
            syncingUi = false;
        }
        check.selectedProperty().addListener((obs, was, is) -> {
            if (syncingUi || model == null) {
                return;
            }
            model.setSelected(ref, Boolean.TRUE.equals(is));
            updateSelectionSummary();
            rebuildLivePlan();
            reviewPlanButton.setDisable(model.selection().isEmpty());
        });

        Label channelLabel = new Label(channel.displayName());
        long observed = job == null ? channel.uniqueMessageCount() : job.observedCanIdentityCount();
        long matched = job == null ? 0L : job.matchedCanIdentityCount();
        double coverage = job == null ? 0.0 : job.coverageRatio();
        String dbcName = job == null || job.definitionDisplayName().isBlank()
                ? "—"
                : job.definitionDisplayName();
        MappingLifecycle lifecycle = job == null ? MappingLifecycle.UNMAPPED : job.mappingLifecycle();
        String readiness = readinessLabel(job);

        row.add(check, 0, 0);
        row.add(channelLabel, 1, 0);
        row.add(new Label(INT.format(observed)), 2, 0);
        row.add(new Label(INT.format(matched)), 3, 0);
        row.add(new Label(PCT.format(coverage * 100.0) + "%"), 4, 0);
        row.add(new Label(INT.format(channel.frameCount())), 5, 0);
        Label effectiveDbc = ellipsize(dbcName, 28);
        effectiveDbc.getStyleClass().add("definition-accent");
        row.add(effectiveDbc, 6, 0);
        row.add(badge(lifecycle.name().replace('_', ' '), mappingBadgeClass(lifecycle)), 7, 0);
        row.add(badge(readiness, readinessBadgeClass(job)), 8, 0);
        return row;
    }

    private void selectReadyForSource(String projectSourceId, CanSourceInventory inventory) {
        if (model == null || inventory.status() != CanInventoryStatus.INVENTORY_READY) {
            return;
        }
        List<SourceChannelRef> ready = new ArrayList<>();
        for (CanChannelInventory ch : inventory.channels()) {
            SourceChannelRef ref = SourceChannelRef.of(projectSourceId, ch.channelId());
            ProcessingJob job = previewJobs.get(ref);
            if (job != null && job.executable()) {
                ready.add(ref);
            }
        }
        model.selectAll(ready);
        rebuild();
    }

    private void onSelectAllReady() {
        if (model == null) {
            return;
        }
        List<SourceChannelRef> ready = new ArrayList<>();
        for (Map.Entry<SourceChannelRef, ProcessingJob> e : previewJobs.entrySet()) {
            if (e.getValue() != null && e.getValue().executable()) {
                ready.add(e.getKey());
            }
        }
        model.selectAll(ready);
        rebuild();
    }

    private void updateSelectionSummary() {
        if (model == null) {
            selectionSummaryLabel.setText("Selected 0 sources · 0 channels · 0 jobs");
            return;
        }
        List<SourceChannelRef> selected = model.selection().selectedChannels();
        Set<String> sources = new LinkedHashSet<>();
        long frames = 0L;
        int ready = 0;
        int attention = 0;
        for (SourceChannelRef ref : selected) {
            sources.add(ref.projectSourceId());
            ProcessingJob job = previewJobs.get(ref);
            if (job != null) {
                frames += job.frameCount();
                if (job.executable()) {
                    ready++;
                } else {
                    attention++;
                }
            } else {
                attention++;
                long channelFrames = inputs.source(ref.projectSourceId())
                        .flatMap(src -> src.inventory().channels().stream()
                                .filter(ch -> ch.channelId() == ref.channelId())
                                .map(CanChannelInventory::frameCount)
                                .findFirst())
                        .orElse(0L);
                frames += channelFrames;
            }
        }
        selectedSourcesMetric.setText(INT.format(sources.size()));
        selectedChannelsMetric.setText(INT.format(selected.size()));
        totalJobsMetric.setText(INT.format(selected.size()));
        selectedFramesMetric.setText(INT.format(frames));
        readyMetric.setText(INT.format(ready));
        attentionMetric.setText(INT.format(attention));

        selectionSummaryLabel.setText(
                "Selected " + sources.size() + " sources · "
                        + selected.size() + " channels · "
                        + selected.size() + " jobs"
                        + "  |  Selected Source-Channel Frames: " + INT.format(frames)
                        + "  |  Ready: " + ready
                        + "  |  Requires Attention: " + attention);
        reviewPlanButton.setDisable(selected.isEmpty());
    }

    private void rebuildLivePlan() {
        livePlanRows.getChildren().clear();
        if (model == null || appService == null) {
            livePlanCountLabel.setText("0 jobs selected");
            livePlanSummaryLabel.setText("Select ready channels to build the live execution plan.");
            return;
        }
        List<SourceChannelRef> selected = model.selection().selectedChannels();
        livePlanCountLabel.setText(selected.size() + (selected.size() == 1 ? " job selected" : " jobs selected"));
        if (selected.isEmpty()) {
            Label empty = new Label("No processing jobs selected yet. Select source channels on the left.");
            empty.getStyleClass().addAll("muted", "processing-live-plan-empty");
            empty.setWrapText(true);
            livePlanRows.getChildren().add(empty);
            livePlanSummaryLabel.setText("Selection changes update this preview without parsing source files.");
            return;
        }

        GridPane header = livePlanGrid();
        header.getStyleClass().add("processing-live-plan-header");
        header.add(muted("Job"), 0, 0);
        header.add(muted("Source"), 1, 0);
        header.add(muted("Channel"), 2, 0);
        header.add(muted("DBC"), 3, 0);
        header.add(muted("Mapping"), 4, 0);
        header.add(muted("Observed"), 5, 0);
        header.add(muted("Matched"), 6, 0);
        header.add(muted("Coverage"), 7, 0);
        header.add(muted("Frames"), 8, 0);
        header.add(muted("Status"), 9, 0);
        livePlanRows.getChildren().add(header);

        int jobIndex = 1;
        int ready = 0;
        int attention = 0;
        long totalFrames = 0L;
        for (SourceChannelRef ref : selected) {
            ProcessingJob job = previewJobs.get(ref);
            GridPane row = livePlanGrid();
            row.getStyleClass().add("processing-live-plan-row");
            String source = sourceDisplayName(ref.projectSourceId());
            String dbc = job == null || job.definitionDisplayName().isBlank() ? "—" : job.definitionDisplayName();
            double coverage = job == null ? 0.0 : job.coverageRatio();
            long observed = job == null ? 0L : job.observedCanIdentityCount();
            long matched = job == null ? 0L : job.matchedCanIdentityCount();
            long frames = job == null ? sourceChannelFrameCount(ref) : job.frameCount();
            boolean executable = job != null && job.executable();
            totalFrames += frames;
            if (executable) ready++; else attention++;

            row.add(new Label(Integer.toString(jobIndex++)), 0, 0);
            row.add(ellipsize(source, 48), 1, 0);
            row.add(new Label("Ch" + ref.channelId()), 2, 0);
            Label dbcLabel = ellipsize(dbc, 22);
            dbcLabel.getStyleClass().add("definition-accent");
            row.add(dbcLabel, 3, 0);
            MappingLifecycle lifecycle = job == null ? MappingLifecycle.UNMAPPED : job.mappingLifecycle();
            row.add(badge(lifecycle.name().replace('_', ' '), mappingBadgeClass(lifecycle)), 4, 0);
            row.add(new Label(INT.format(observed)), 5, 0);
            row.add(new Label(INT.format(matched)), 6, 0);
            row.add(new Label(PCT.format(coverage * 100.0) + "%"), 7, 0);
            row.add(new Label(INT.format(frames)), 8, 0);
            row.add(badge(executable ? "READY" : readinessLabel(job), readinessBadgeClass(job)), 9, 0);
            livePlanRows.getChildren().add(row);
        }
        livePlanSummaryLabel.setText(
                selected.size() + " jobs · " + ready + " ready · " + attention
                        + " require attention · " + INT.format(totalFrames) + " selected frames");
    }

    private String sourceDisplayName(String projectSourceId) {
        if (appService == null || projectSourceId == null || projectSourceId.isBlank()) {
            return "Measurement source";
        }
        return appService.workspace().findFile(projectSourceId)
                .map(WorkspaceFile::fileName)
                .orElse("Measurement source");
    }

    private long sourceChannelFrameCount(SourceChannelRef ref) {
        return inputs.source(ref.projectSourceId())
                .flatMap(src -> src.inventory().channels().stream()
                        .filter(ch -> ch.channelId() == ref.channelId())
                        .map(CanChannelInventory::frameCount)
                        .findFirst())
                .orElse(0L);
    }

    private static GridPane livePlanGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(2);
        grid.setPadding(new Insets(7, 6, 7, 6));
        double[] widths = {34, 220, 52, 96, 86, 62, 62, 70, 86, 120};
        for (double width : widths) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setMinWidth(width * 0.62);
            cc.setPrefWidth(width);
            cc.setHgrow(Priority.SOMETIMES);
            grid.getColumnConstraints().add(cc);
        }
        return grid;
    }

    /**
     * Preview readiness for all inventory channels without mutating user selection.
     */
    static Map<SourceChannelRef, ProcessingJob> buildPreviewJobs(ProcessingPlanInputs inputs) {
        if (inputs == null) {
            return Map.of();
        }
        ProjectChannelSelection previewSelection = new ProjectChannelSelection();
        List<SourceChannelRef> all = new ArrayList<>();
        for (SourcePlanInput source : inputs.sources()) {
            if (source.inventory().status() != CanInventoryStatus.INVENTORY_READY) {
                continue;
            }
            for (CanChannelInventory channel : source.inventory().channels()) {
                all.add(SourceChannelRef.of(source.projectSourceId(), channel.channelId()));
            }
        }
        previewSelection.selectAll(all);
        ProjectProcessingPlan preview = new ProcessingPlanBuilder().build(previewSelection, inputs);
        Map<SourceChannelRef, ProcessingJob> byRef = new HashMap<>();
        for (ProcessingJob job : preview.jobs()) {
            byRef.put(job.channel(), job);
        }
        return Map.copyOf(byRef);
    }

    private static String readinessLabel(ProcessingJob job) {
        if (job == null) {
            return "BLOCKED";
        }
        if (job.executable()) {
            return "READY";
        }
        if (job.mappingLifecycle() == MappingLifecycle.RECOMMENDED) {
            return "REQUIRES CONFIRMATION";
        }
        return "BLOCKED";
    }

    private static String readinessBadgeClass(ProcessingJob job) {
        if (job != null && job.executable()) {
            return "badge-ready";
        }
        if (job != null && job.mappingLifecycle() == MappingLifecycle.RECOMMENDED) {
            return "badge-running";
        }
        return "badge-invalid";
    }

    private static String mappingBadgeClass(MappingLifecycle lifecycle) {
        if (lifecycle == null) {
            return "badge-unsupported";
        }
        return switch (lifecycle) {
            case CONFIRMED, MANUAL -> "badge-ready";
            case RECOMMENDED, RECOMMENDED_FALLBACK -> "badge-running";
            case AMBIGUOUS, NO_MATCH, UNMAPPED, NOT_APPLICABLE -> "badge-unsupported";
        };
    }

    private static VBox metricCard(String title, Label value, String semantic) {
        Label caption = new Label(title);
        caption.getStyleClass().add("processing-metric-title");
        value.getStyleClass().add("processing-metric-value");
        VBox card = new VBox(3, caption, value);
        card.getStyleClass().addAll("processing-metric-card", "processing-metric-" + semantic);
        card.setMinWidth(112);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted");
        return label;
    }

    private static Label badge(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().addAll("badge", style);
        return label;
    }

    private static Label ellipsize(String text, int max) {
        String value = text == null ? "" : text;
        Label label = new Label(value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…");
        if (value.length() > max) {
            label.setTooltip(new Tooltip(value));
        }
        return label;
    }

    private static GridPane baseRowGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(2);
        grid.setPadding(new Insets(2, 4, 2, 4));
        double[] widths = {56, 110, 90, 90, 80, 100, 140, 120, 150};
        for (double w : widths) {
            ColumnConstraints cc = new ColumnConstraints();
            cc.setMinWidth(w * 0.7);
            cc.setPrefWidth(w);
            cc.setHgrow(Priority.SOMETIMES);
            grid.getColumnConstraints().add(cc);
        }
        return grid;
    }

    Button reviewPlanButtonForTest() {
        return reviewPlanButton;
    }

    Label selectionSummaryForTest() {
        return selectionSummaryLabel;
    }

    VBox channelListForTest() {
        return channelList;
    }
}