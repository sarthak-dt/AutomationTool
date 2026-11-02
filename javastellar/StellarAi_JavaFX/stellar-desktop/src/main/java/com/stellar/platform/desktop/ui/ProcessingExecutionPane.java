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
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.text.NumberFormat;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Live PROCESSING EXECUTION view — compact rows, indeterminate progress for the running job only.
 */
public final class ProcessingExecutionPane extends BorderPane {

    private static final NumberFormat INT = NumberFormat.getIntegerInstance(Locale.US);

    private final Label summaryLabel = new Label();
    private final Label overallLabel = new Label();
    private final Label queuedMetric = new Label("0");
    private final Label runningMetric = new Label("0");
    private final Label completedMetric = new Label("0");
    private final Label failedMetric = new Label("0");
    private final VBox jobList = new VBox(4);
    private final Button backButton = new Button("Back to Selection");

    private DesktopAppService appService;
    private ProcessingSelectionWorkspaceModel model;
    private Runnable onBack = () -> {
    };
    private Map<ProjectSourceId, DisplayLabel> labels = Map.of();

    public ProcessingExecutionPane() {
        getStyleClass().addAll("page-root", "processing-execution-root");
        setId("processing-execution-root");

        Label header = new Label("PROCESSING EXECUTION");
        header.getStyleClass().add("section-header");
        Label subtitle = new Label("Jobs run sequentially from the reviewed plan snapshot.");
        subtitle.getStyleClass().add("section-subtitle");
        subtitle.setWrapText(true);

        summaryLabel.getStyleClass().add("muted");
        summaryLabel.setManaged(false);
        summaryLabel.setVisible(false);
        overallLabel.getStyleClass().addAll("muted", "execution-overall-label");

        HBox metricStrip = new HBox(8,
                executionMetric("Queued", queuedMetric, "queued"),
                executionMetric("Running", runningMetric, "running"),
                executionMetric("Completed", completedMetric, "ready"),
                executionMetric("Failed", failedMetric, "failed"));
        metricStrip.getStyleClass().add("processing-metric-strip");
        for (javafx.scene.Node metric : metricStrip.getChildren()) {
            HBox.setHgrow(metric, Priority.ALWAYS);
        }

        jobList.getStyleClass().add("execution-job-list");
        ScrollPane scroll = new ScrollPane(jobList);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("processing-selection-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        backButton.setId("execution-back-to-selection-button");
        backButton.getStyleClass().addAll("secondary-button", "back-to-selection-button");
        backButton.setOnAction(e -> {
            if (onBack != null) {
                onBack.run();
            }
        });

        HBox actions = new HBox(8, backButton);
        actions.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(10, header, subtitle, metricStrip, summaryLabel, overallLabel, scroll, actions);
        root.setPadding(new Insets(4, 4, 8, 4));
        setCenter(root);
    }

    public void bind(DesktopAppService app, ProcessingSelectionWorkspaceModel workspace, Runnable backCallback) {
        this.appService = Objects.requireNonNull(app, "app");
        this.model = Objects.requireNonNull(workspace, "workspace");
        this.onBack = backCallback == null ? () -> {
        } : backCallback;
        rebuildLabels();
        rebuild();
    }

    public void rebuild() {
        if (model == null || appService == null) {
            return;
        }
        List<ExecutionJobPresentation> jobs = model.executionJobs();
        int total = jobs.size();
        int completed = model.completedJobCount();
        int failed = model.failedJobCount();
        int queued = 0;
        int running = 0;
        for (ExecutionJobPresentation job : jobs) {
            if (job.status() == JobExecutionStatus.QUEUED) queued++;
            if (job.status() == JobExecutionStatus.RUNNING) running++;
        }
        queuedMetric.setText(INT.format(queued));
        runningMetric.setText(INT.format(running));
        completedMetric.setText(INT.format(completed));
        failedMetric.setText(INT.format(failed));
        summaryLabel.setText(total + " jobs · " + completed + " completed · " + failed + " failed");
        overallLabel.setText(completed + " / " + total + " jobs complete");

        boolean executing = !model.canNavigateAwayFromExecution();
        backButton.setDisable(executing);
        if (executing) {
            backButton.setTooltip(new javafx.scene.control.Tooltip("Back is disabled while execution is running."));
        } else {
            backButton.setTooltip(null);
        }

        List<javafx.scene.Node> rows = new ArrayList<>();
        for (ExecutionJobPresentation row : jobs) {
            rows.add(buildRow(row));
        }
        jobList.getChildren().setAll(rows);
    }

    private HBox buildRow(ExecutionJobPresentation row) {
        ProcessingJob job = row.planJob();
        ProjectSourceId sourceId = ProjectSourceId.of(job.projectSourceId());
        DisplayLabel label = labels.get(sourceId);
        String source = label == null
                ? job.projectSourceId()
                : SourceDisplayNameResolver.primaryIdentity(label);
        String channel = "Ch" + job.channelId();
        String dbc = job.definitionDisplayName().isBlank() ? "—" : job.definitionDisplayName();

        Label identity = new Label(source + " · " + channel + " · " + dbc);
        identity.getStyleClass().add("execution-job-identity");
        identity.setMinWidth(220);
        HBox.setHgrow(identity, Priority.ALWAYS);

        Label frames = new Label("Frames: " + INT.format(row.displayFrameCount()));
        frames.getStyleClass().add("muted");
        frames.setMinWidth(110);

        Label status = new Label(statusText(row.status()));
        status.getStyleClass().addAll("badge", statusStyle(row.status()));
        status.setMinWidth(90);

        Label elapsed = new Label(row.elapsed().map(ProcessingExecutionPane::formatElapsed).orElse(""));
        elapsed.getStyleClass().add("muted");
        elapsed.setMinWidth(70);

        HBox rowBox = new HBox(10, identity, frames, status, elapsed);
        rowBox.setAlignment(Pos.CENTER_LEFT);
        rowBox.getStyleClass().add("execution-job-row");
        rowBox.setPadding(new Insets(4, 6, 4, 6));

        if (row.status() == JobExecutionStatus.RUNNING) {
            ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
            bar.setPrefWidth(120);
            bar.getStyleClass().add("execution-job-progress");
            rowBox.getChildren().add(3, bar);
        }
        return rowBox;
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

    private static VBox executionMetric(String title, Label value, String semantic) {
        Label caption = new Label(title);
        caption.getStyleClass().add("processing-metric-title");
        value.getStyleClass().add("processing-metric-value");
        VBox card = new VBox(3, caption, value);
        card.getStyleClass().addAll("processing-metric-card", "execution-metric-" + semantic);
        card.setMinWidth(140);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    static String statusText(JobExecutionStatus status) {
        if (status == null) {
            return "—";
        }
        return ProcessingStatusLabels.forJob(status);
    }

    static String statusStyle(JobExecutionStatus status) {
        if (status == null) {
            return "badge-unsupported";
        }
        return ProcessingStatusLabels.styleForJob(status);
    }

    static String formatElapsed(Duration d) {
        if (d == null) {
            return "";
        }
        long ms = Math.max(0L, d.toMillis());
        if (ms < 1000L) {
            return ms + " ms";
        }
        return String.format(Locale.US, "%.1f s", ms / 1000.0);
    }

    Button backButtonForTest() {
        return backButton;
    }
}
