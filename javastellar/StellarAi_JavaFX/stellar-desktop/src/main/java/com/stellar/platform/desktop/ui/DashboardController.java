package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProjectSearchEmptyCopy;
import com.stellar.platform.desktop.presentation.ProcessingRunStatuses;
import com.stellar.platform.desktop.presentation.ProcessingStatusLabels;
import com.stellar.platform.desktop.presentation.ProjectDefinitionMatchingModel;
import com.stellar.platform.desktop.presentation.ProjectJourneyState;
import com.stellar.platform.desktop.presentation.ProjectJourneyStateResolver;
import com.stellar.platform.desktop.project.ProcessingSummary;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.storage.metadata.ProjectDashboardSummary;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Projects Home: create a new engineering project or resume an existing one.
 *
 * <p>It is not a second project workspace — every row action routes according to the project's
 * journey state. It never parses MF4 or materializes Graph/WebView.</p>
 */
public final class DashboardController implements AppServiceAware, Refreshable {

    private static final NumberFormat INT = NumberFormat.getIntegerInstance(Locale.US);
    private static final int RECENT_LIMIT = 5;
    private static final DateTimeFormatter ABSOLUTE_DAY =
            DateTimeFormatter.ofPattern("d MMM yyyy");

    @FXML private Label welcomeTitleLabel;
    @FXML private Label welcomeSubtitleLabel;
    @FXML private TextField globalSearchField;
    @FXML private HBox analysisStorageBanner;
    @FXML private Label analysisStorageStatusLabel;
    @FXML private Button configureStorageButton;
    @FXML private HBox kpiRow;
    @FXML private Label kpiProjectsValue;
    @FXML private Label kpiProjectsHint;
    @FXML private Label kpiFilesValue;
    @FXML private Label kpiFilesHint;
    @FXML private Label kpiSignalsValue;
    @FXML private Label kpiSignalsHint;
    @FXML private Label kpiDecodeValue;
    @FXML private Label kpiDecodeHint;
    @FXML private Label kpiFramesValue;
    @FXML private Label kpiFramesHint;
    @FXML private Button viewAllProjectsButton;
    @FXML private GridPane recentProjectsTable;
    @FXML private VBox recentProjectsEmpty;
    @FXML private Label recentProjectsEmptyTitle;
    @FXML private Label recentProjectsEmptyCopy;
    @FXML private Label journeyAvatar;
    @FXML private Label journeyProjectLabel;
    @FXML private Label journeyMetaLabel;
    @FXML private Label journeyStatusBadge;
    @FXML private Button resumeWorkspaceButton;
    @FXML private VBox recentActivityBox;

    // Legacy / hidden
    @FXML private Label projectTitleLabel;
    @FXML private Label projectStatusBadge;
    @FXML private Label projectMetaLabel;
    @FXML private Label processingStatusLabel;
    @FXML private Label lastRunLabel;
    @FXML private Label measurementMetricLabel;
    @FXML private Label measurementMetricSubLabel;
    @FXML private Label definitionMetricLabel;
    @FXML private Label inventoryMetricLabel;
    @FXML private Label inventoryMetricSubLabel;
    @FXML private Label mappedMetricLabel;
    @FXML private Label mappedMetricSubLabel;
    @FXML private Label readyMetricLabel;
    @FXML private Label attentionMetricLabel;
    @FXML private Label savedProjectsMetricLabel;
    @FXML private Label globalFilesMetricLabel;
    @FXML private Label globalFilesMetricSubLabel;
    @FXML private Label globalDefsMetricLabel;
    @FXML private Label globalSignalsMetricLabel;
    @FXML private Label recentProjectsCountLabel;
    @FXML private Label activeProjectNameLabel;
    @FXML private VBox activeProjectHealthBox;
    @FXML private Button headerNewProjectButton;
    @FXML private VBox latestRunBox;
    @FXML private VBox selectedSourcesBox;
    @FXML private Label projectLabel;
    @FXML private Label measurementLabel;
    @FXML private Label dbcLabel;
    @FXML private Label statusLabel;
    @FXML private Label summaryLabel;

    private DesktopAppService appService;
    private MainShellController shell;
    private List<ProjectDashboardSummary> cachedSummaries = List.of();
    private ProjectJourneyState activeJourneyState = ProjectJourneyState.NEW;
    private boolean showAllProjects;

    @Override
    public void setAppService(DesktopAppService appService, MainShellController shell) {
        this.appService = appService;
        this.shell = shell;
    }

    @FXML
    private void initialize() {
        if (globalSearchField != null) {
            globalSearchField.textProperty().addListener((o, a, b) -> rebuildRecentProjectsFiltered());
        }
    }

    @FXML
    private void onNewProject() {
        if (shell != null) {
            shell.requestNewProject();
        }
    }

    @FXML
    private void onConfigureStorage() {
        if (shell != null) {
            shell.openConfigurationPage();
        }
    }

    /** Projects Home Start Project tile — same path as the header "+ New Project" button. */
    void startProjectFromHomeCard() {
        onNewProject();
    }

    @FXML
    private void onResumeWorkspace() {
        if (shell != null) {
            shell.resumeActiveProject();
        }
    }

    @FXML
    private void onViewAllProjects() {
        showAllProjects = !showAllProjects;
        if (viewAllProjectsButton != null) {
            viewAllProjectsButton.setText(showAllProjects ? "Show recent →" : "View all projects →");
        }
        rebuildRecentProjectsFiltered();
    }

    @Override
    public void refresh() {
        if (appService == null) {
            return;
        }

        if (Boolean.getBoolean("stellarai.dashboard.sync")) {
            cachedSummaries = appService.projectDashboardSummaries();
            applyDashboardRefresh();
            return;
        }
        appService.loadProjectDashboardSummaries(rows -> {
            cachedSummaries = rows == null ? List.of() : rows;
            applyDashboardRefresh();
        });
    }

    private void applyDashboardRefresh() {
        boolean durable = appService.isDurableNamedProject();
        String displayName = durable ? appService.workspace().projectName() : "No active project";

        if (welcomeTitleLabel != null) {
            welcomeTitleLabel.setText("Welcome back");
        }
        if (welcomeSubtitleLabel != null) {
            welcomeSubtitleLabel.setText("Create a new engineering project or resume an existing one.");
        }

        refreshAnalysisStorageBanner();

        updateKpis(cachedSummaries);

        List<WorkspaceFile> measurements = appService.workspace().measurementFiles();
        List<WorkspaceFile> definitions = appService.workspace().definitionFiles();
        ProjectDefinitionMatchingModel.MatchingSummary mapping = appService.definitionMatchingModel().summary();

        int inventoryReady = 0;
        for (WorkspaceFile file : measurements) {
            var inventory = appService.sourceInventoryModel().inventoryFor(file.id());
            if (inventory.isPresent() && inventory.get().status() == CanInventoryStatus.INVENTORY_READY) {
                inventoryReady++;
            }
        }

        activeJourneyState = appService.projectJourneyState();

        rebuildRecentProjectsFiltered();
        updateActiveProjectPanel(durable, activeJourneyState);
        rebuildRecentActivity(durable, measurements, definitions, inventoryReady);
        syncLegacyLabels(displayName, durable, measurements, definitions, inventoryReady, mapping);

        if (shell != null) {
            shell.refreshWorkspaceStatusBar();
        }
    }

    private void refreshAnalysisStorageBanner() {
        if (analysisStorageBanner == null || analysisStorageStatusLabel == null || appService == null) {
            return;
        }
        var status = appService.analysisStorage().currentStatus();
        boolean show = status.status() != com.stellar.platform.storage.analysis.AnalysisStorageValidator.Status.VALID;
        analysisStorageBanner.setVisible(show);
        analysisStorageBanner.setManaged(show);
        analysisStorageStatusLabel.setText(switch (status.status()) {
            case NOT_CONFIGURED -> "Not configured";
            case INVALID -> "Unavailable";
            case VALID -> "Ready";
        });
    }

    private void updateKpis(List<ProjectDashboardSummary> summaries) {
        int totalFiles = 0;
        int totalSignals = 0;
        long totalFrames = 0L;
        int completedRuns = 0;
        int runs = 0;
        int counted = 0;
        boolean anyUnavailable = false;
        for (ProjectDashboardSummary s : summaries) {
            if (!s.countsAvailable()) {
                anyUnavailable = true;
                continue;
            }
            counted++;
            totalFiles += s.measurementFileCount();
            totalSignals += s.signalCount();
            totalFrames += s.canFrameCount();
            if (s.lastProcessingStatus() != null && !s.lastProcessingStatus().isBlank()) {
                runs++;
                if (ProcessingRunStatuses.isCompleted(s.lastProcessingStatus())) {
                    completedRuns++;
                }
            }
        }

        setText(kpiProjectsValue, INT.format(summaries.size()));
        setText(kpiProjectsHint, summaries.isEmpty() ? "None yet" : "Persisted workspaces");
        if (counted == 0 && anyUnavailable) {
            setText(kpiFilesValue, "—");
            setText(kpiFilesHint, "Project metadata could not be read");
            setText(kpiSignalsValue, "—");
            setText(kpiSignalsHint, "Project metadata could not be read");
            setText(kpiDecodeValue, "—");
            setText(kpiDecodeHint, "Project metadata could not be read");
            setText(kpiFramesValue, "—");
            setText(kpiFramesHint, "Project metadata could not be read");
        } else {
            setText(kpiFilesValue, INT.format(totalFiles));
            setText(kpiFilesHint, summaries.isEmpty()
                    ? "No saved projects yet"
                    : "Across " + counted + " readable project" + (counted == 1 ? "" : "s"));
            setText(kpiSignalsValue, INT.format(totalSignals));
            setText(kpiSignalsHint, "Unique indexed signals across all saved projects");
            setText(kpiDecodeValue, runs == 0 ? "—" : completedRuns + " / " + runs);
            setText(kpiDecodeHint, runs == 0 ? "No runs yet" : "Completed / projects with runs");
            setText(kpiFramesValue, formatCompact(totalFrames));
            setText(kpiFramesHint, "From persisted inventories");
        }

        setText(savedProjectsMetricLabel, INT.format(summaries.size()));
        setText(globalFilesMetricLabel, INT.format(totalFiles));
        setText(globalSignalsMetricLabel, INT.format(totalSignals));
        setText(globalDefsMetricLabel, INT.format(summaries.stream().mapToInt(ProjectDashboardSummary::definitionFileCount).sum()));
    }

    private static String formatCount(ProjectDashboardSummary project, int value) {
        return project.countsAvailable() ? INT.format(value) : "—";
    }

    private static String formatCompact(long value) {
        if (value >= 1_000_000_000L) {
            return String.format(Locale.US, "%.1fB", value / 1_000_000_000.0);
        }
        if (value >= 1_000_000L) {
            return String.format(Locale.US, "%.1fM", value / 1_000_000.0);
        }
        if (value >= 10_000L) {
            return String.format(Locale.US, "%.1fK", value / 1_000.0);
        }
        return INT.format(value);
    }

    private void rebuildRecentProjectsFiltered() {
        if (recentProjectsTable == null || appService == null) {
            return;
        }
        String query = globalSearchField == null || globalSearchField.getText() == null
                ? ""
                : globalSearchField.getText().trim().toLowerCase(Locale.ROOT);

        List<ProjectDashboardSummary> filtered = new ArrayList<>();
        for (ProjectDashboardSummary s : cachedSummaries) {
            if (query.isEmpty() || matchesSearch(s, query)) {
                filtered.add(s);
            }
        }

        recentProjectsTable.getChildren().clear();
        recentProjectsTable.getRowConstraints().clear();
        RecentProjectsTableLayout.apply(recentProjectsTable);

        boolean empty = filtered.isEmpty();
        if (recentProjectsEmpty != null) {
            recentProjectsEmpty.setVisible(empty);
            recentProjectsEmpty.setManaged(empty);
            if (empty) {
                boolean searchActive = !query.isBlank();
                boolean catalogHasProjects = !cachedSummaries.isEmpty();
                if (recentProjectsEmptyTitle != null) {
                    recentProjectsEmptyTitle.setText(ProjectSearchEmptyCopy.title(
                            catalogHasProjects, searchActive, false));
                }
                if (recentProjectsEmptyCopy != null) {
                    recentProjectsEmptyCopy.setText(ProjectSearchEmptyCopy.body(
                            catalogHasProjects, searchActive, false));
                }
            }
        }
        recentProjectsTable.setVisible(!empty);
        recentProjectsTable.setManaged(!empty);
        if (empty) {
            return;
        }

        addRecentProjectsHeaderRow();

        String activeId = appService.currentProjectId();
        boolean durable = appService.isDurableNamedProject();
        int limit = showAllProjects ? filtered.size() : Math.min(RECENT_LIMIT, filtered.size());
        for (int i = 0; i < limit; i++) {
            ProjectDashboardSummary row = filtered.get(i);
            boolean active = durable && row.projectId().equals(activeId);
            addRecentProjectDataRow(i + 1, row, active);
        }
        if (!showAllProjects && filtered.size() > RECENT_LIMIT && viewAllProjectsButton != null) {
            viewAllProjectsButton.setDisable(false);
        }
    }

    private boolean matchesSearch(ProjectDashboardSummary project, String query) {
        if (project.name().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        if (project.fileTypesLabel() != null
                && project.fileTypesLabel().toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        if (appService.isDurableNamedProject()
                && project.projectId().equals(appService.currentProjectId())) {
            for (WorkspaceFile f : appService.workspace().measurementFiles()) {
                if (f.fileName().toLowerCase(Locale.ROOT).contains(query)) {
                    return true;
                }
            }
            for (WorkspaceFile f : appService.workspace().definitionFiles()) {
                if (f.fileName().toLowerCase(Locale.ROOT).contains(query)) {
                    return true;
                }
            }
            if (appService.signalsModel().catalog() != null) {
                for (var entry : appService.signalsModel().catalog().entries()) {
                    if (entry.signalName() != null
                            && entry.signalName().toLowerCase(Locale.ROOT).contains(query)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void addRecentProjectsHeaderRow() {
        Label project = headerLabel("Project");
        Label files = headerLabel("Files");
        files.getStyleClass().add("dashboard-col-num");
        Label types = headerLabel("Types");
        Label signals = headerLabel("Signals");
        signals.getStyleClass().add("dashboard-col-num");
        Label updated = headerLabel("Updated");
        Label stage = headerLabel("Next Step");
        Label status = headerLabel("Status");
        Label action = headerLabel("");
        for (Label node : List.of(project, files, types, signals, updated, stage, status, action)) {
            node.getStyleClass().add("dashboard-table-header-cell");
            node.setMinHeight(28);
            node.setPrefHeight(28);
            node.setMaxHeight(28);
        }
        RecentProjectsTableLayout.place(recentProjectsTable, project, RecentProjectsTableLayout.COL_PROJECT, 0);
        RecentProjectsTableLayout.place(recentProjectsTable, files, RecentProjectsTableLayout.COL_FILES, 0);
        RecentProjectsTableLayout.place(recentProjectsTable, types, RecentProjectsTableLayout.COL_TYPES, 0);
        RecentProjectsTableLayout.place(recentProjectsTable, signals, RecentProjectsTableLayout.COL_SIGNALS, 0);
        RecentProjectsTableLayout.place(recentProjectsTable, updated, RecentProjectsTableLayout.COL_UPDATED, 0);
        RecentProjectsTableLayout.place(recentProjectsTable, stage, RecentProjectsTableLayout.COL_STAGE, 0);
        RecentProjectsTableLayout.place(recentProjectsTable, status, RecentProjectsTableLayout.COL_STATUS, 0);
        RecentProjectsTableLayout.place(recentProjectsTable, action, RecentProjectsTableLayout.COL_ACTION, 0);
    }

    private static Label headerLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("dashboard-col-label");
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private void addRecentProjectDataRow(int gridRow, ProjectDashboardSummary project, boolean active) {
        HBox identity = buildProjectIdentityCell(project, active);

        Label files = gridValue(formatCount(project,
                project.measurementFileCount() + project.definitionFileCount()), Pos.CENTER_RIGHT);
        String types = !project.countsAvailable()
                ? "—"
                : (project.fileTypesLabel() == null || project.fileTypesLabel().isBlank()
                        ? "—"
                        : project.fileTypesLabel());
        Label typeLabel = gridValue(types, Pos.CENTER_LEFT);
        Label signals = gridValue(formatCount(project, project.signalCount()), Pos.CENTER_RIGHT);
        VBox updated = buildUpdatedCell(project.updatedAt());

        ProjectJourneyState state = active
                ? activeJourneyState
                : ProjectJourneyStateResolver.resolve(project);
        Label stage = gridValue(state.primaryActionLabel(), Pos.CENTER_LEFT);
        Label status = new Label(project.countsAvailable()
                ? state.statusLabel().display()
                : "Needs attention");
        status.getStyleClass().addAll("badge", state.statusLabel().badgeStyleClass(), "dashboard-row-status");
        status.setAlignment(Pos.CENTER);
        status.setMaxWidth(Double.MAX_VALUE);
        status.setTextOverrun(OverrunStyle.ELLIPSIS);

        HBox actions = buildActionCell(project, active, state);

        for (var node : List.of(identity, files, typeLabel, signals, updated, stage, status, actions)) {
            if (node instanceof Region region) {
                region.setMinHeight(RecentProjectsTableLayout.ROW_HEIGHT);
                region.setPrefHeight(RecentProjectsTableLayout.ROW_HEIGHT);
                region.setMaxHeight(RecentProjectsTableLayout.ROW_HEIGHT);
            }
            node.getStyleClass().add("dashboard-project-cell");
            if (active) {
                node.getStyleClass().add("dashboard-project-row-active");
            }
        }

        RecentProjectsTableLayout.place(recentProjectsTable, identity, RecentProjectsTableLayout.COL_PROJECT, gridRow);
        RecentProjectsTableLayout.place(recentProjectsTable, files, RecentProjectsTableLayout.COL_FILES, gridRow);
        RecentProjectsTableLayout.place(recentProjectsTable, typeLabel, RecentProjectsTableLayout.COL_TYPES, gridRow);
        RecentProjectsTableLayout.place(recentProjectsTable, signals, RecentProjectsTableLayout.COL_SIGNALS, gridRow);
        RecentProjectsTableLayout.place(recentProjectsTable, updated, RecentProjectsTableLayout.COL_UPDATED, gridRow);
        RecentProjectsTableLayout.place(recentProjectsTable, stage, RecentProjectsTableLayout.COL_STAGE, gridRow);
        RecentProjectsTableLayout.place(recentProjectsTable, status, RecentProjectsTableLayout.COL_STATUS, gridRow);
        RecentProjectsTableLayout.place(recentProjectsTable, actions, RecentProjectsTableLayout.COL_ACTION, gridRow);
    }

    private HBox buildProjectIdentityCell(ProjectDashboardSummary project, boolean active) {
        Label avatar = new Label("▣");
        avatar.getStyleClass().add("dashboard-row-avatar");
        if (active) {
            avatar.getStyleClass().add("dashboard-row-avatar-active");
        }
        avatar.setMinWidth(36);
        avatar.setPrefWidth(36);
        avatar.setMaxWidth(36);
        avatar.setMinHeight(36);
        avatar.setPrefHeight(36);
        avatar.setMaxHeight(36);

        Label name = new Label(project.name());
        name.getStyleClass().add("dashboard-list-title");
        name.setMaxWidth(Double.MAX_VALUE);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);

        HBox subLine = new HBox(8);
        subLine.setAlignment(Pos.CENTER_LEFT);
        Label sub = new Label(active ? "Active workspace" : "Saved project");
        sub.getStyleClass().add("dashboard-row-sub");
        subLine.getChildren().add(sub);
        if (active) {
            Label activeBadge = new Label("ACTIVE");
            activeBadge.getStyleClass().addAll("badge", "badge-ready", "dashboard-active-badge");
            subLine.getChildren().add(activeBadge);
        }

        VBox text = new VBox(2, name, subLine);
        text.setAlignment(Pos.CENTER_LEFT);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        HBox identity = new HBox(8, avatar, text);
        identity.setAlignment(Pos.CENTER_LEFT);
        identity.setMaxWidth(Double.MAX_VALUE);
        identity.getStyleClass().add("dashboard-project-row");
        identity.setOnMouseClicked(e -> openDashboardProject(project, active));
        identity.setCursor(javafx.scene.Cursor.HAND);
        return identity;
    }

    private static VBox buildUpdatedCell(Instant updatedAt) {
        VBox box = new VBox(1);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setMaxWidth(Double.MAX_VALUE);
        String relative = formatUpdatedRelative(updatedAt);
        Label top = new Label(relative);
        top.getStyleClass().add("dashboard-col-value");
        top.setMaxWidth(Double.MAX_VALUE);
        top.setTextOverrun(OverrunStyle.ELLIPSIS);
        box.getChildren().add(top);
        String absolute = formatUpdatedAbsolute(updatedAt);
        if (absolute != null && !absolute.equals(relative)) {
            Label bottom = new Label(absolute);
            bottom.getStyleClass().add("dashboard-row-sub");
            bottom.setMaxWidth(Double.MAX_VALUE);
            bottom.setTextOverrun(OverrunStyle.ELLIPSIS);
            box.getChildren().add(bottom);
        }
        return box;
    }

    /**
     * The row action is the project's state-driven next step; opening always routes through the
     * shell's resume resolver so a card can never land on a workspace the project cannot use.
     */
    private HBox buildActionCell(ProjectDashboardSummary project, boolean active, ProjectJourneyState state) {
        Button open = new Button(state.primaryActionLabel());
        open.getStyleClass().addAll("secondary-button", "dashboard-row-open");
        open.setMinWidth(RecentProjectsTableLayout.OPEN_BTN_WIDTH);
        open.setPrefWidth(RecentProjectsTableLayout.OPEN_BTN_WIDTH);
        open.setMaxWidth(Double.MAX_VALUE);
        open.setTextOverrun(OverrunStyle.ELLIPSIS);
        open.setTooltip(new Tooltip(state.primaryActionLabel() + " — " + project.name()));
        open.setOnAction(e -> openDashboardProject(project, active));
        Button more = new Button("•••");
        more.getStyleClass().addAll("project-more-button", "dashboard-row-more");
        more.setMnemonicParsing(false);
        ContextMenu menu = new ContextMenu();
        MenuItem rename = new MenuItem("Rename Project");
        rename.setOnAction(e -> {
            if (shell != null) {
                shell.requestRenameProject(project.projectId());
            }
        });
        MenuItem delete = new MenuItem("Delete Project");
        delete.getStyleClass().add("project-delete-menu-item");
        delete.setOnAction(e -> {
            if (shell != null) {
                shell.requestDeleteProject(project.projectId());
            }
        });
        menu.getItems().addAll(rename, delete);
        more.setOnAction(e -> menu.show(more, Side.BOTTOM, 0, 4));
        HBox actions = new HBox(4, open, more);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMaxWidth(Double.MAX_VALUE);
        return actions;
    }

    private void openDashboardProject(ProjectDashboardSummary project, boolean active) {
        if (shell == null || project == null) {
            return;
        }
        if (active) {
            shell.resumeActiveProject();
        } else {
            shell.requestOpenSavedProject(project.projectId());
        }
    }

    private static Label gridValue(String text, Pos alignment) {
        Label label = new Label(text);
        label.getStyleClass().add("dashboard-col-value");
        if (alignment == Pos.CENTER_RIGHT) {
            label.getStyleClass().add("dashboard-col-num");
        }
        label.setAlignment(alignment);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setTextOverrun(OverrunStyle.ELLIPSIS);
        return label;
    }

    private void updateActiveProjectPanel(boolean durable, ProjectJourneyState state) {
        if (journeyProjectLabel == null) {
            return;
        }
        if (!durable) {
            journeyProjectLabel.setText("No active project");
            journeyMetaLabel.setText("Create or open a project to resume.");
            resumeWorkspaceButton.setDisable(true);
            resumeWorkspaceButton.setText("Add Sources");
        } else {
            journeyProjectLabel.setText(appService.workspace().projectName());
            cachedSummaries.stream()
                    .filter(s -> s.projectId().equals(appService.currentProjectId()))
                    .findFirst()
                    .ifPresentOrElse(
                            s -> journeyMetaLabel.setText("Updated " + formatUpdatedRelative(s.updatedAt())),
                            () -> journeyMetaLabel.setText(state.statusLabel().display()));
            resumeWorkspaceButton.setDisable(false);
            resumeWorkspaceButton.setText(state.primaryActionLabel());
        }

        if (journeyStatusBadge != null) {
            journeyStatusBadge.setText(durable ? state.statusLabel().display() : "No project");
            journeyStatusBadge.getStyleClass().removeAll(
                    "badge-idle", "badge-running", "badge-ready", "badge-warning", "badge-invalid", "badge-attention");
            journeyStatusBadge.getStyleClass().add(
                    durable ? state.statusLabel().badgeStyleClass() : "badge-idle");
        }
    }

    private void rebuildRecentActivity(
            boolean durable,
            List<WorkspaceFile> measurements,
            List<WorkspaceFile> definitions,
            int inventoryReady
    ) {
        if (recentActivityBox == null) {
            return;
        }
        recentActivityBox.getChildren().clear();
        if (!durable) {
            recentActivityBox.getChildren().add(emptyRow("Open or create a project to see activity."));
            return;
        }
        int shown = 0;
        String projectName = appService.workspace().projectName();
        if (appService.latestPersistedProcessingRun().isPresent() && shown < 5) {
            var run = appService.latestPersistedProcessingRun().get();
            String when = run.finishedAt() != null
                    ? formatUpdatedRelative(run.finishedAt())
                    : formatUpdatedRelative(run.startedAt());
            recentActivityBox.getChildren().add(activityRow(
                    "✓",
                    "Processing " + run.status().replace('_', ' ').toLowerCase(Locale.ROOT),
                    projectName,
                    when));
            shown++;
        }
        for (WorkspaceFile file : measurements) {
            if (shown++ >= 4) break;
            recentActivityBox.getChildren().add(activityRow(
                    "▤", "Measurement added", projectName, truncate(file.fileName(), 28)));
        }
        for (WorkspaceFile file : definitions) {
            if (shown++ >= 5) break;
            recentActivityBox.getChildren().add(activityRow(
                    "⬡", "DBC added", projectName, truncate(file.fileName(), 28)));
        }
        if (shown < 5 && inventoryReady > 0) {
            recentActivityBox.getChildren().add(activityRow(
                    "◎", "Inventory completed", projectName, inventoryReady + " source(s)"));
            shown++;
        }
        if (appService.lastSavedAt().isPresent() && shown < 5) {
            recentActivityBox.getChildren().add(activityRow(
                    "●", "Project saved", projectName, formatUpdatedRelative(appService.lastSavedAt().get())));
            shown++;
        }
        if (shown == 0) {
            recentActivityBox.getChildren().add(emptyRow("No activity yet."));
        }
    }

    private void syncLegacyLabels(
            String displayName,
            boolean durable,
            List<WorkspaceFile> measurements,
            List<WorkspaceFile> definitions,
            int inventoryReady,
            ProjectDefinitionMatchingModel.MatchingSummary mapping
    ) {
        setText(projectTitleLabel, displayName);
        setText(activeProjectNameLabel, durable ? appService.workspace().projectName() : "");
        setText(projectMetaLabel, measurements.size() + " files · " + definitions.size() + " defs");
        setText(projectStatusBadge, durable ? (measurements.isEmpty() ? "NEW" : "ACTIVE") : "NONE");
        setText(measurementMetricLabel, INT.format(measurements.size()));
        setText(definitionMetricLabel, INT.format(definitions.size()));
        setText(inventoryMetricLabel, inventoryReady + " / " + measurements.size());
        int mapped = Math.max(0, mapping.sourceChannelCount() - mapping.noMatchCount() - mapping.unmappedCount());
        setText(mappedMetricLabel, INT.format(mapped));
        setText(readyMetricLabel, INT.format(mapping.confirmedCount() + mapping.manualCount()));
        setText(attentionMetricLabel, INT.format(
                mapping.recommendedCount() + mapping.ambiguousCount()
                        + mapping.noMatchCount() + mapping.unmappedCount()));
        setText(processingStatusLabel, ProcessingStatusLabels.forProject(appService.workspace().processingStatus()));
        setText(projectLabel, "Project: " + displayName);
        setText(statusLabel, "Processing status: "
                + ProcessingStatusLabels.forProject(appService.workspace().processingStatus()));
        setText(summaryLabel, appService.workspace().latestSummary()
                .map(DashboardController::formatSummary)
                .orElse("No processing summary yet."));
        if (activeProjectHealthBox != null) {
            activeProjectHealthBox.setVisible(false);
            activeProjectHealthBox.setManaged(false);
        }
    }

    private static HBox activityRow(String icon, String what, String project, String contextOrWhen) {
        Label glyph = new Label(icon);
        glyph.getStyleClass().add("dashboard-activity-icon");
        glyph.setMinWidth(24);
        glyph.setPrefWidth(24);
        glyph.setMaxWidth(24);
        glyph.setAlignment(Pos.CENTER);
        Label title = new Label(what);
        title.getStyleClass().add("dashboard-activity-what");
        title.setMaxWidth(Double.MAX_VALUE);
        title.setTextOverrun(OverrunStyle.ELLIPSIS);
        Label projectLabel = new Label(project);
        projectLabel.getStyleClass().add("dashboard-activity-project");
        projectLabel.setMaxWidth(Double.MAX_VALUE);
        projectLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        Label context = new Label(contextOrWhen);
        context.getStyleClass().add("dashboard-activity-time");
        context.setMaxWidth(Double.MAX_VALUE);
        context.setTextOverrun(OverrunStyle.ELLIPSIS);
        if (contextOrWhen != null && contextOrWhen.length() > 20) {
            Tooltip.install(context, new Tooltip(contextOrWhen));
        }
        VBox text = new VBox(2, title, projectLabel, context);
        text.setMinWidth(0);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(8, glyph, text);
        row.setAlignment(Pos.TOP_LEFT);
        row.setPadding(new Insets(8, 0, 8, 0));
        row.getStyleClass().add("dashboard-activity-row");
        return row;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        return value.substring(0, Math.max(1, max - 1)) + "…";
    }

    private static Label emptyRow(String text) {
        Label label = new Label(text);
        label.getStyleClass().addAll("modern-card-caption", "dashboard-empty-row");
        label.setWrapText(true);
        return label;
    }

    private static String formatUpdatedRelative(Instant updatedAt) {
        if (updatedAt == null) {
            return "—";
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate day = updatedAt.atZone(zone).toLocalDate();
        LocalDate today = LocalDate.now(zone);
        long days = ChronoUnit.DAYS.between(day, today);
        if (days == 0) {
            long hours = ChronoUnit.HOURS.between(updatedAt, Instant.now());
            if (hours > 0 && hours < 12) {
                return hours == 1 ? "1 hour ago" : hours + " hours ago";
            }
            return "Today";
        }
        if (days == 1) {
            return "Yesterday";
        }
        if (days < 7) {
            return days + " days ago";
        }
        return ABSOLUTE_DAY.withZone(zone).format(updatedAt);
    }

    private static String formatUpdatedAbsolute(Instant updatedAt) {
        if (updatedAt == null) {
            return null;
        }
        ZoneId zone = ZoneId.systemDefault();
        return ABSOLUTE_DAY.withZone(zone).format(updatedAt);
    }

    private static String formatSummary(ProcessingSummary s) {
        return "Last run: " + ProcessingStatusLabels.forProject(s.completionStatus())
                + " | frames=" + s.statistics().sourceFramesRead()
                + " | signals=" + s.statistics().signalsEmitted()
                + " | " + UiFormat.duration(s.elapsed());
    }

    private static void setText(Label label, String text) {
        if (label != null) {
            label.setText(text);
        }
    }

    GridPane recentProjectsTableForTest() {
        return recentProjectsTable;
    }

    HBox kpiRowForTest() {
        return kpiRow;
    }

    TextField searchFieldForTest() {
        return globalSearchField;
    }

    ProjectJourneyState activeJourneyStateForTest() {
        return activeJourneyState;
    }

    Button resumeButtonForTest() {
        return resumeWorkspaceButton;
    }

    Label journeyStatusBadgeForTest() {
        return journeyStatusBadge;
    }
}
