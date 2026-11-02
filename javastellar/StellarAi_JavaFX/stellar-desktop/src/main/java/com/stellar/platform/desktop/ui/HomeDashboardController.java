package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProcessingRunStatuses;
import com.stellar.platform.desktop.presentation.ProjectJourneyFacts;
import com.stellar.platform.desktop.presentation.ProjectJourneyState;
import com.stellar.platform.desktop.presentation.ProjectJourneyStateResolver;
import com.stellar.platform.desktop.presentation.ProjectSearchEmptyCopy;
import com.stellar.platform.desktop.presentation.SignalMetricPresenter;
import com.stellar.platform.desktop.presentation.WorkspaceChromeStatus;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.ui.home.HomeMetricCard;
import com.stellar.platform.desktop.ui.home.HomeQuickActionItem;
import com.stellar.platform.desktop.ui.home.HomeStatusBadge;
import com.stellar.platform.desktop.ui.home.HomeWorkflowCard;
import com.stellar.platform.desktop.ui.home.WorkspaceHomeLayout;
import com.stellar.platform.desktop.ui.home.WorkspaceUiPreferencesStore;
import com.stellar.platform.storage.metadata.ProjectDashboardSummary;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

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
 * Workspace: unified project home. Presentation only — existing shell handlers and metadata.
 */
public final class HomeDashboardController implements AppServiceAware, Refreshable {

    private static final int RECENT_LIMIT = 8;
    private static final NumberFormat INT = NumberFormat.getIntegerInstance(Locale.US);
    private static final DateTimeFormatter TIME_OF_DAY = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter ABSOLUTE_DAY =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    @FXML private ScrollPane rootScroll;
    @FXML private VBox pageRoot;
    @FXML private HBox kpiRow;
    @FXML private HBox kpiRowSecond;
    @FXML private VBox workspaceStack;
    @FXML private HBox workspaceMain;
    @FXML private VBox rightColumn;
    @FXML private FlowPane workflowRow;
    @FXML private HBox kpiProjectsCard;
    @FXML private HBox kpiFilesCard;
    @FXML private HBox kpiSignalsCard;
    @FXML private HBox kpiDecodeCard;
    @FXML private HBox kpiFramesCard;
    @FXML private VBox browserPane;
    @FXML private ToggleButton recentTab;
    @FXML private ToggleButton allTab;
    @FXML private ToggleButton favoritesTab;
    @FXML private TextField projectSearchField;
    @FXML private Button refreshProjectsButton;
    @FXML private ScrollPane tableScroll;
    @FXML private GridPane projectsTable;
    @FXML private VBox projectsEmpty;
    @FXML private Label projectsEmptyTitle;
    @FXML private Label projectsEmptyCopy;
    @FXML private VBox quickActionsPane;
    @FXML private VBox quickActionsBox;
    @FXML private VBox activityPane;
    @FXML private ScrollPane activityScroll;
    @FXML private VBox activityBox;
    @FXML private Button workflowSourcesCard;
    @FXML private Button workflowDecodeCard;
    @FXML private Button workflowSignalsCard;
    @FXML private Button workflowGraphsCard;

    private DesktopAppService appService;
    private MainShellController shell;
    private String searchQuery = "";
    private boolean applyingSearch;
    private List<ProjectDashboardSummary> cachedSummaries = List.of();
    private final WorkspaceUiPreferencesStore uiPreferences = new WorkspaceUiPreferencesStore();

    private HomeMetricCard projectsMetric;
    private HomeMetricCard filesMetric;
    private HomeMetricCard signalsMetric;
    private HomeMetricCard decodeMetric;
    private HomeMetricCard framesMetric;

    private HomeQuickActionItem newProjectAction;
    private HomeQuickActionItem addMeasurementAction;
    private HomeQuickActionItem importDbcAction;
    private HomeQuickActionItem openExistingAction;
    private HomeQuickActionItem exploreSignalsAction;
    private HomeQuickActionItem openGraphsAction;

    private HomeWorkflowCard workflowSources;
    private HomeWorkflowCard workflowDecode;
    private HomeWorkflowCard workflowSignals;
    private HomeWorkflowCard workflowGraphs;

    @Override
    public void setAppService(DesktopAppService appService, MainShellController shell) {
        this.appService = appService;
        this.shell = shell;
    }

    @FXML
    private void initialize() {
        projectsMetric = new HomeMetricCard(kpiProjectsCard, "Projects",
                NavIcons.projects(), "metric-icon-projects");
        filesMetric = new HomeMetricCard(kpiFilesCard, "Measurement Files",
                NavIcons.projectFiles(), "metric-icon-files");
        signalsMetric = new HomeMetricCard(kpiSignalsCard, SignalMetricPresenter.WORKSPACE_KPI_TITLE,
                NavIcons.signals(), "metric-icon-signals");
        decodeMetric = new HomeMetricCard(kpiDecodeCard, "Processing Success",
                NavIcons.processing(), "metric-icon-decode");
        framesMetric = new HomeMetricCard(kpiFramesCard, "CAN Frames",
                StellarIcons.channel(), "metric-icon-frames");
        installKpiTooltip(kpiProjectsCard, "All saved projects in this workspace.");
        installKpiTooltip(kpiFilesCard, "Measurement files across all saved projects.");
        installKpiTooltip(kpiSignalsCard, SignalMetricPresenter.WORKSPACE_KPI_TOOLTIP);
        installKpiTooltip(kpiDecodeCard, "Completed processing runs across all saved projects.");
        installKpiTooltip(kpiFramesCard, "CAN frames across all saved projects.");

        ToggleGroup tabs = new ToggleGroup();
        wireTab(recentTab, tabs, true);
        wireTab(allTab, tabs, false);
        wireTab(favoritesTab, tabs, false);
        if (projectSearchField != null) {
            projectSearchField.textProperty().addListener((o, a, b) -> {
                if (applyingSearch) {
                    return;
                }
                searchQuery = b == null ? "" : b.trim().toLowerCase(Locale.ROOT);
                rebuildProjectsTable();
            });
        }
        if (refreshProjectsButton != null) {
            refreshProjectsButton.setGraphic(StellarIcons.refresh());
            refreshProjectsButton.setTooltip(new Tooltip("Refresh projects"));
        }
        installQuickActions();
        installGetStarted();
        configureActivityScroll();
        WorkspaceHomeLayout.install(
                rootScroll, pageRoot, kpiRow, kpiRowSecond, workspaceStack, workspaceMain,
                browserPane, rightColumn, quickActionsPane, activityPane, workflowRow, projectsTable);
    }

    void applySearch(String query) {
        searchQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (projectSearchField != null) {
            applyingSearch = true;
            try {
                String current = projectSearchField.getText() == null ? "" : projectSearchField.getText();
                if (!current.equals(query == null ? "" : query)) {
                    projectSearchField.setText(query == null ? "" : query);
                }
            } finally {
                applyingSearch = false;
            }
        }
        rebuildProjectsTable();
    }

    @Override
    public void refresh() {
        if (appService == null) {
            return;
        }
        if (Boolean.getBoolean("stellarai.dashboard.sync")) {
            cachedSummaries = appService.projectDashboardSummaries();
            applyHomeRefresh();
            return;
        }
        appService.loadProjectDashboardSummaries(rows -> {
            cachedSummaries = rows == null ? List.of() : rows;
            applyHomeRefresh();
        });
    }

    private void applyHomeRefresh() {
        updateKpis(cachedSummaries);
        setVisible(browserPane, true);
        setVisible(activityPane, true);
        rebuildProjectsTable();
        rebuildRecentActivity();
        applyActionAvailability();
    }

    @FXML
    private void onRefreshProjects() {
        refresh();
    }

    @FXML
    private void onNewProject() {
        if (shell != null) {
            shell.requestNewProject();
        }
    }

    @FXML
    private void onOpenProject() {
        if (shell != null) {
            shell.requestOpenExistingProject();
        }
    }

    private void onOpenLogFile() {
        if (shell != null) {
            shell.requestOpenLogFile();
        }
    }

    private void onImportDbc() {
        if (shell != null) {
            shell.requestImportDbc();
        }
    }

    private void openWorkspace(WorkspaceRoute route) {
        if (shell != null) {
            shell.openRoute(route);
        }
    }

    private void installQuickActions() {
        if (quickActionsBox == null) {
            return;
        }
        newProjectAction = new HomeQuickActionItem("New Project", StellarIcons.plus(), this::onNewProject);
        addMeasurementAction = new HomeQuickActionItem("Add Measurement File",
                NavIcons.projectFiles(), this::onOpenLogFile);
        importDbcAction = new HomeQuickActionItem("Import DBC File",
                StellarIcons.database(), this::onImportDbc);
        openExistingAction = new HomeQuickActionItem("Open Existing Project",
                NavIcons.projects(), this::onOpenProject);
        exploreSignalsAction = new HomeQuickActionItem("Explore Signals",
                NavIcons.signals(), () -> openWorkspace(WorkspaceRoute.SIGNALS));
        openGraphsAction = new HomeQuickActionItem("Open Graphs",
                NavIcons.investigations(), () -> openWorkspace(WorkspaceRoute.GRAPHS));
        quickActionsBox.getChildren().setAll(
                newProjectAction.node(),
                addMeasurementAction.node(),
                importDbcAction.node(),
                openExistingAction.node(),
                exploreSignalsAction.node(),
                openGraphsAction.node()
        );
    }

    private void installGetStarted() {
        if (workflowSourcesCard != null) {
            workflowSources = new HomeWorkflowCard(workflowSourcesCard, "Project Sources",
                    "Manage MF4, ASC, BLF and DBC files.", NavIcons.projectFiles());
            workflowSourcesCard.setOnAction(e -> openWorkspace(WorkspaceRoute.SOURCES));
        }
        if (workflowDecodeCard != null) {
            workflowDecode = new HomeWorkflowCard(workflowDecodeCard, "Decode & Process",
                    "Configure decode and process measurement data.", NavIcons.processing());
            workflowDecodeCard.setOnAction(e -> openWorkspace(WorkspaceRoute.DECODE));
        }
        if (workflowSignalsCard != null) {
            workflowSignals = new HomeWorkflowCard(workflowSignalsCard, "Explore Signals",
                    "Search and analyze available signals.", NavIcons.signals());
            workflowSignalsCard.setOnAction(e -> openWorkspace(WorkspaceRoute.SIGNALS));
        }
        if (workflowGraphsCard != null) {
            workflowGraphs = new HomeWorkflowCard(workflowGraphsCard, "Graphs & Analysis",
                    "Visualize signals and investigate events.", NavIcons.investigations());
            workflowGraphsCard.setOnAction(e -> openWorkspace(WorkspaceRoute.GRAPHS));
        }
    }

    private void applyActionAvailability() {
        boolean durable = appService != null && appService.isDurableNamedProject();
        ProjectJourneyFacts facts = durable ? appService.projectJourneyFacts() : null;
        ProjectJourneyState journey = durable ? appService.projectJourneyState() : null;
        boolean analysisReady = facts != null && facts.analysisDataReady();
        boolean decodeReady = facts != null && facts.decodeWorkspaceApplicable();
        String needProject = WorkspaceChromeStatus.gatedActionReason(false, null);
        String journeyAction = WorkspaceChromeStatus.gatedActionReason(durable,
                journey == null ? null : journey.primaryActionLabel());

        setQuickAction(newProjectAction, false, null);
        setQuickAction(openExistingAction, false, null);
        setQuickAction(addMeasurementAction, !durable, durable ? null : needProject);
        setQuickAction(importDbcAction, !durable, durable ? null : needProject);
        setQuickAction(exploreSignalsAction, !durable || !analysisReady,
                analysisReady ? null : journeyAction);
        setQuickAction(openGraphsAction, !durable || !analysisReady,
                analysisReady ? null : journeyAction);

        setWorkflow(workflowSources, !durable, durable ? null : needProject);
        setWorkflow(workflowDecode, !durable || !decodeReady,
                decodeReady ? null : journeyAction);
        setWorkflow(workflowSignals, !durable || !analysisReady,
                analysisReady ? null : journeyAction);
        setWorkflow(workflowGraphs, !durable || !analysisReady,
                analysisReady ? null : journeyAction);
    }

    private static void setQuickAction(HomeQuickActionItem item, boolean disabled, String tooltip) {
        if (item == null) {
            return;
        }
        item.setDisable(disabled);
        item.setDisabledTooltip(disabled ? tooltip : null);
    }

    private static void setWorkflow(HomeWorkflowCard card, boolean disabled, String tooltip) {
        if (card == null) {
            return;
        }
        card.setDisable(disabled);
        card.setDisabledTooltip(disabled ? tooltip : null);
    }

    /**
     * Recent Activity is presentation of already-resident project state only.
     * No synthetic events are invented when those facts are absent.
     */
    private void rebuildRecentActivity() {
        if (activityBox == null) {
            return;
        }
        activityBox.getChildren().clear();
        if (appService == null || !appService.isDurableNamedProject()) {
            activityBox.getChildren().add(emptyActivity("No recent activity yet."));
            return;
        }

        int shown = 0;
        String projectName = appService.workspace().projectName();
        ProjectJourneyFacts facts = appService.projectJourneyFacts();

        if (appService.latestPersistedProcessingRun().isPresent() && shown < 6) {
            var run = appService.latestPersistedProcessingRun().get();
            String when = run.finishedAt() != null
                    ? formatWhen(run.finishedAt())
                    : formatWhen(run.startedAt());
            activityBox.getChildren().add(activityItem(processingActivityTitle(run.status()), projectName, when));
            shown++;
        }
        if (facts != null && facts.analysisDataReady() && shown < 6) {
            activityBox.getChildren().add(activityItem("Added signals", projectName,
                    INT.format(Math.max(0, persistedOrResidentSignalCount())) + " signals"));
            shown++;
        }
        if (facts != null && facts.savedGraphContextValid() && shown < 6) {
            activityBox.getChildren().add(activityItem("Generated graph", projectName, "Workspace graph context"));
            shown++;
        }
        for (var file : appService.workspace().definitionFiles()) {
            if (shown >= 6) {
                break;
            }
            activityBox.getChildren().add(activityItem("Imported DBC", projectName, file.fileName()));
            shown++;
        }
        for (var file : appService.workspace().measurementFiles()) {
            if (shown >= 6) {
                break;
            }
            boolean processed = appService.sourceProcessed(file.projectSourceId());
            activityBox.getChildren().add(activityItem(
                    processed ? "Processed measurement file" : "Measurement added",
                    projectName,
                    file.fileName()));
            shown++;
        }
        if (appService.lastSavedAt().isPresent() && shown < 6) {
            activityBox.getChildren().add(activityItem(
                    "Saved project", projectName, formatWhen(appService.lastSavedAt().get())));
            shown++;
        }
        if (shown == 0) {
            activityBox.getChildren().add(emptyActivity("No recent activity yet."));
        }
        fitActivityViewport();
    }

    /**
     * The card stays a bounded dashboard tile. Rows scroll inside it instead of
     * painting past the border or stretching the Workspace page.
     */
    private void configureActivityScroll() {
        if (activityScroll == null || activityBox == null) {
            return;
        }
        activityScroll.setFitToWidth(true);
        activityScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        activityScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        activityScroll.setPannable(true);
        activityScroll.setFocusTraversable(true);
        activityScroll.setMinHeight(0);
        activityScroll.setMinWidth(0);
        activityScroll.setPrefWidth(0);
        activityScroll.setMaxWidth(Double.MAX_VALUE);
        activityScroll.setMinViewportHeight(0);
        VBox.setVgrow(activityScroll, Priority.ALWAYS);
        activityBox.setFillWidth(true);
        activityBox.setMinWidth(0);
        activityBox.setPrefWidth(0);
        activityBox.setMaxWidth(Double.MAX_VALUE);
        activityPane.setMinHeight(0);
        if (activityPane != null) {
            Rectangle clip = new Rectangle();
            activityPane.setClip(clip);
            activityPane.layoutBoundsProperty().addListener((obs, previous, bounds) -> {
                clip.setWidth(Math.max(0, bounds.getWidth()));
                clip.setHeight(Math.max(0, bounds.getHeight()));
            });
        }
    }

    private void fitActivityViewport() {
        if (activityScroll == null || activityBox == null) {
            return;
        }
        activityBox.applyCss();
        double content = Math.max(28, activityBox.prefHeight(-1));
        double cap = activityViewportCap();
        double viewport = Math.min(content, cap);
        activityScroll.setPrefViewportHeight(viewport);
        activityScroll.setMaxHeight(cap);
        activityScroll.setPrefHeight(viewport);
    }

    private double activityViewportCap() {
        double sceneHeight = activityPane != null && activityPane.getScene() != null
                ? activityPane.getScene().getHeight()
                : 0;
        return sceneHeight >= 900 ? 280 : 220;
    }

    /** Layout-only. Does not read or write activity history. */
    void replaceActivityRowsForTest(List<String> titles, List<String> details) {
        if (activityBox == null) {
            return;
        }
        activityBox.getChildren().clear();
        int count = titles == null ? 0 : titles.size();
        if (count == 0) {
            activityBox.getChildren().add(emptyActivity("No recent activity yet."));
        } else {
            for (int i = 0; i < count; i++) {
                String detail = details != null && i < details.size() ? details.get(i) : "";
                activityBox.getChildren().add(activityItem(titles.get(i), "Test", detail));
            }
        }
        fitActivityViewport();
    }

    List<String> activityTitlesForTest() {
        if (activityBox == null) {
            return List.of();
        }
        List<String> titles = new ArrayList<>();
        for (Node child : activityBox.getChildren()) {
            collectActivityText(child, "activity-title", titles, false);
        }
        return titles;
    }

    ScrollPane activityScrollForTest() {
        return activityScroll;
    }

    VBox activityPaneForTest() {
        return activityPane;
    }

    String activityTooltipForTest(int index) {
        if (activityBox == null || index < 0 || index >= activityBox.getChildren().size()) {
            return "";
        }
        List<String> tips = new ArrayList<>();
        collectActivityText(activityBox.getChildren().get(index), "activity-meta", tips, true);
        return tips.isEmpty() ? "" : tips.getFirst();
    }

    private static void collectActivityText(Node node, String style, List<String> into, boolean tooltip) {
        if (node instanceof Label label
                && (label.getStyleClass().contains(style)
                || (!tooltip && label.getStyleClass().contains("empty-state-title")))) {
            if (tooltip) {
                into.add(label.getTooltip() == null ? "" : label.getTooltip().getText());
            } else if (label.getStyleClass().contains(style) || label.getStyleClass().contains("empty-state-title")) {
                into.add(label.getText());
            }
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectActivityText(child, style, into, tooltip);
            }
        }
    }

    private int persistedOrResidentSignalCount() {
        ProjectDashboardSummary summary = cachedSummaries.stream()
                .filter(s -> s.projectId().equals(appService.currentProjectId()))
                .findFirst()
                .orElse(null);
        return summary == null ? 0 : summary.signalCount();
    }

    private static String processingActivityTitle(String status) {
        if (ProcessingRunStatuses.isFailed(status)) {
            return "Processing failed";
        }
        return "Processed measurement file";
    }

    private static Node activityItem(String title, String project, String detail) {
        Label heading = new Label(title);
        heading.getStyleClass().add("activity-title");
        heading.setWrapText(false);
        heading.setMinWidth(0);
        heading.setMaxWidth(Double.MAX_VALUE);
        heading.setTextOverrun(OverrunStyle.ELLIPSIS);
        String metaText = project + " · " + detail;
        Label meta = new Label(metaText);
        meta.getStyleClass().add("activity-meta");
        meta.setMaxWidth(Double.MAX_VALUE);
        meta.setMinWidth(0);
        meta.setWrapText(false);
        meta.setTextOverrun(OverrunStyle.ELLIPSIS);
        meta.setTooltip(new Tooltip(metaText));
        heading.setTooltip(new Tooltip(title));
        VBox text = new VBox(2, heading, meta);
        text.setMinWidth(0);
        text.setPrefWidth(0);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(text);
        row.getStyleClass().add("activity-item");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setMinWidth(0);
        row.setPrefWidth(0);
        row.setMaxWidth(Double.MAX_VALUE);
        return row;
    }

    private static Label emptyActivity(String text) {
        Label label = new Label(text);
        label.getStyleClass().addAll("empty-state-title", "activity-item");
        label.setWrapText(true);
        return label;
    }

    private void updateKpis(List<ProjectDashboardSummary> summaries) {
        int totalFiles = 0;
        int totalSignals = 0;
        long totalFrames = 0L;
        int completedRuns = 0;
        int runs = 0;
        int counted = 0;
        boolean anyUnavailable = false;
        for (ProjectDashboardSummary summary : summaries) {
            if (!summary.countsAvailable()) {
                anyUnavailable = true;
                continue;
            }
            counted++;
            totalFiles += summary.measurementFileCount();
            totalSignals += summary.signalCount();
            totalFrames += summary.canFrameCount();
            if (summary.lastProcessingStatus() != null && !summary.lastProcessingStatus().isBlank()) {
                runs++;
                if (ProcessingRunStatuses.isCompleted(summary.lastProcessingStatus())) {
                    completedRuns++;
                }
            }
        }
        if (projectsMetric != null) {
            projectsMetric.setValue(INT.format(summaries.size()));
        }
        if (counted == 0 && anyUnavailable) {
            setMetric(filesMetric, "—");
            setMetric(signalsMetric, "—");
            setMetric(decodeMetric, "—");
            setMetric(framesMetric, "—");
            return;
        }
        setMetric(filesMetric, INT.format(totalFiles));
        setMetric(signalsMetric, INT.format(totalSignals));
        setMetric(decodeMetric, runs == 0 ? "—" : completedRuns + " / " + runs);
        setMetric(framesMetric, formatCompact(totalFrames));
    }

    private static void installKpiTooltip(Node card, String text) {
        if (card != null) {
            Tooltip.install(card, new Tooltip(text));
        }
    }

    private static void setMetric(HomeMetricCard card, String value) {
        if (card != null) {
            card.setValue(value);
        }
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

    private void rebuildProjectsTable() {
        if (projectsTable == null || appService == null) {
            return;
        }
        markTab(recentTab);
        markTab(allTab);
        markTab(favoritesTab);

        boolean favoritesOnly = favoritesTab != null && favoritesTab.isSelected();
        boolean showAll = allTab != null && allTab.isSelected();

        List<ProjectDashboardSummary> filtered = new ArrayList<>();
        for (ProjectDashboardSummary summary : cachedSummaries) {
            if (favoritesOnly && !uiPreferences.isFavorite(summary.projectId())) {
                continue;
            }
            if (matchesSearch(summary)) {
                filtered.add(summary);
            }
        }
        int limit = showAll || favoritesOnly ? filtered.size() : Math.min(RECENT_LIMIT, filtered.size());

        projectsTable.getChildren().clear();
        projectsTable.getRowConstraints().clear();
        if (pageRoot != null && pageRoot.getStyleClass().contains(WorkspaceHomeLayout.STACKED)) {
            RecentProjectsTableLayout.applyCompact(projectsTable);
        } else {
            RecentProjectsTableLayout.apply(projectsTable);
        }

        boolean empty = filtered.isEmpty();
        if (projectsEmpty != null) {
            projectsEmpty.setVisible(empty);
            projectsEmpty.setManaged(empty);
            if (empty) {
                boolean searchActive = searchQuery != null && !searchQuery.isBlank();
                boolean catalogHasProjects = !cachedSummaries.isEmpty();
                if (projectsEmptyTitle != null) {
                    projectsEmptyTitle.setText(ProjectSearchEmptyCopy.title(
                            catalogHasProjects, searchActive, favoritesOnly));
                }
                if (projectsEmptyCopy != null) {
                    projectsEmptyCopy.setText(ProjectSearchEmptyCopy.body(
                            catalogHasProjects, searchActive, favoritesOnly));
                }
            }
        }
        if (tableScroll != null) {
            tableScroll.setVisible(!empty);
            tableScroll.setManaged(!empty);
        }
        if (browserPane != null) {
            VBox.setVgrow(browserPane, Priority.ALWAYS);
        }
        projectsTable.setVisible(!empty);
        projectsTable.setManaged(!empty);
        if (empty) {
            return;
        }
        addHeaderRow();
        String activeId = appService.currentProjectId();
        boolean durable = appService.isDurableNamedProject();
        for (int i = 0; i < limit; i++) {
            addProjectRow(i + 1, filtered.get(i), durable && filtered.get(i).projectId().equals(activeId));
        }
    }

    private void addHeaderRow() {
        RecentProjectsTableLayout.place(projectsTable, header("Project"), RecentProjectsTableLayout.COL_PROJECT, 0);
        RecentProjectsTableLayout.place(projectsTable, header("Files"), RecentProjectsTableLayout.COL_FILES, 0);
        RecentProjectsTableLayout.place(projectsTable, header("Type"), RecentProjectsTableLayout.COL_TYPES, 0);
        Label signalsHeader = header("Signals");
        signalsHeader.setTooltip(new Tooltip(SignalMetricPresenter.PROJECT_ROW_TOOLTIP));
        RecentProjectsTableLayout.place(projectsTable, signalsHeader, RecentProjectsTableLayout.COL_SIGNALS, 0);
        RecentProjectsTableLayout.place(projectsTable, header("Last Updated"), RecentProjectsTableLayout.COL_UPDATED, 0);
        RecentProjectsTableLayout.place(projectsTable, header("Next Step"), RecentProjectsTableLayout.COL_STAGE, 0);
        RecentProjectsTableLayout.place(projectsTable, header("Status"), RecentProjectsTableLayout.COL_STATUS, 0);
        RecentProjectsTableLayout.place(projectsTable, header("Actions"), RecentProjectsTableLayout.COL_ACTION, 0);
    }

    private static Label header(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("project-table-header-cell");
        label.setMaxWidth(Double.MAX_VALUE);
        label.setMinHeight(RecentProjectsTableLayout.HEADER_HEIGHT);
        label.setPrefHeight(RecentProjectsTableLayout.HEADER_HEIGHT);
        label.setMaxHeight(RecentProjectsTableLayout.HEADER_HEIGHT);
        return label;
    }

    private void addProjectRow(int gridRow, ProjectDashboardSummary project, boolean active) {
        ProjectJourneyState state = active
                ? appService.projectJourneyState()
                : ProjectJourneyStateResolver.resolve(ProjectJourneyStateResolver.factsFrom(project));

        HBox identity = projectIdentity(project, active);
        Label files = valueLabel(formatCount(project,
                project.measurementFileCount() + project.definitionFileCount()));
        Label type = valueLabel(project.countsAvailable()
                ? blankToDash(project.fileTypesLabel())
                : "—");
        Label signals = valueLabel(formatCount(project, project.signalCount()));
        signals.setTooltip(new Tooltip(SignalMetricPresenter.PROJECT_ROW_TOOLTIP));
        Label updated = valueLabel(formatWhen(project.updatedAt()));
        Label next = valueLabel(state.primaryActionLabel());
        next.getStyleClass().add("project-next-step");
        next.setCursor(Cursor.HAND);
        next.setOnMouseClicked(e -> openProject(project, active));
        Label status = HomeStatusBadge.create(state, project.countsAvailable());
        HBox actions = actionCell(project, active, state);

        for (Node node : List.of(identity, files, type, signals, updated, next, status, actions)) {
            if (node instanceof Region region) {
                region.setMinHeight(RecentProjectsTableLayout.ROW_HEIGHT);
                region.setPrefHeight(RecentProjectsTableLayout.ROW_HEIGHT);
                region.setMaxHeight(RecentProjectsTableLayout.ROW_HEIGHT);
            }
            node.getStyleClass().add("project-cell");
            boolean statusBadge = node.getStyleClass().contains("status-badge");
            if (active && !statusBadge) {
                node.getStyleClass().add("project-row-active");
            }
            if (active && node == identity) {
                node.getStyleClass().add("project-row-active-indicator");
            }
        }
        RecentProjectsTableLayout.place(projectsTable, identity, RecentProjectsTableLayout.COL_PROJECT, gridRow);
        RecentProjectsTableLayout.place(projectsTable, files, RecentProjectsTableLayout.COL_FILES, gridRow);
        RecentProjectsTableLayout.place(projectsTable, type, RecentProjectsTableLayout.COL_TYPES, gridRow);
        RecentProjectsTableLayout.place(projectsTable, signals, RecentProjectsTableLayout.COL_SIGNALS, gridRow);
        RecentProjectsTableLayout.place(projectsTable, updated, RecentProjectsTableLayout.COL_UPDATED, gridRow);
        RecentProjectsTableLayout.place(projectsTable, next, RecentProjectsTableLayout.COL_STAGE, gridRow);
        RecentProjectsTableLayout.place(projectsTable, status, RecentProjectsTableLayout.COL_STATUS, gridRow);
        RecentProjectsTableLayout.place(projectsTable, actions, RecentProjectsTableLayout.COL_ACTION, gridRow);
    }

    private HBox projectIdentity(ProjectDashboardSummary project, boolean active) {
        boolean favorite = uiPreferences.isFavorite(project.projectId());
        Button star = new Button();
        star.getStyleClass().add("favorite-button");
        if (favorite) {
            star.getStyleClass().add("favorite-button-active");
        }
        star.setGraphic(favorite ? StellarIcons.starFilled() : StellarIcons.starOutline());
        star.setTooltip(new Tooltip(favorite ? "Remove from favourites" : "Add to favourites"));
        star.setFocusTraversable(false);
        star.setMnemonicParsing(false);
        star.setOnAction(e -> {
            uiPreferences.toggleFavorite(project.projectId());
            rebuildProjectsTable();
        });
        star.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);

        Label name = new Label(project.name());
        name.getStyleClass().add("project-name");
        name.setMaxWidth(Double.MAX_VALUE);
        name.setTextOverrun(OverrunStyle.ELLIPSIS);
        name.setCursor(Cursor.HAND);
        HBox nameLine = new HBox(8, name);
        nameLine.setAlignment(Pos.CENTER_LEFT);
        if (active) {
            Label activeBadge = new Label("Active");
            activeBadge.getStyleClass().add("project-active-badge");
            activeBadge.setMinWidth(Region.USE_PREF_SIZE);
            activeBadge.setTextOverrun(OverrunStyle.CLIP);
            nameLine.getChildren().add(activeBadge);
        }
        Label meta = new Label(active ? "Active workspace" : "Saved project");
        meta.getStyleClass().add("project-meta");
        VBox text = new VBox(2, nameLine, meta);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setOnMouseClicked(e -> openProject(project, active));
        text.setCursor(Cursor.HAND);

        HBox identity = new HBox(8, star, text);
        identity.getStyleClass().addAll("project-row", "project-row-hover");
        identity.setAlignment(Pos.CENTER_LEFT);
        identity.setMaxWidth(Double.MAX_VALUE);
        return identity;
    }

    private HBox actionCell(ProjectDashboardSummary project, boolean active, ProjectJourneyState state) {
        String actionLabel = state.primaryActionLabel();
        Button open = new Button(actionLabel);
        open.getStyleClass().addAll("secondary-button", "project-row-open");
        open.setMinWidth(0);
        open.setMaxWidth(Double.MAX_VALUE);
        open.setTextOverrun(OverrunStyle.ELLIPSIS);
        open.setTooltip(new Tooltip(actionLabel + " — " + project.name()));
        open.setOnAction(e -> openProject(project, active));

        Button more = new Button("•••");
        more.getStyleClass().add("project-more-button");
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

    private void openProject(ProjectDashboardSummary project, boolean active) {
        if (shell == null || project == null) {
            return;
        }
        if (active) {
            shell.resumeActiveProject();
        } else {
            shell.requestOpenSavedProject(project.projectId());
        }
    }

    private boolean matchesSearch(ProjectDashboardSummary summary) {
        if (searchQuery.isBlank()) {
            return true;
        }
        if (summary.name() != null && summary.name().toLowerCase(Locale.ROOT).contains(searchQuery)) {
            return true;
        }
        return summary.fileTypesLabel() != null
                && summary.fileTypesLabel().toLowerCase(Locale.ROOT).contains(searchQuery);
    }

    private static Label valueLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("project-col-value");
        label.setMaxWidth(Double.MAX_VALUE);
        label.setTextOverrun(OverrunStyle.ELLIPSIS);
        return label;
    }

    private static String formatCount(ProjectDashboardSummary project, int value) {
        return project.countsAvailable() ? INT.format(value) : "—";
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String formatWhen(Instant updatedAt) {
        if (updatedAt == null) {
            return "—";
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate day = updatedAt.atZone(zone).toLocalDate();
        LocalDate today = LocalDate.now(zone);
        long days = ChronoUnit.DAYS.between(day, today);
        if (days == 0) {
            return "Today " + TIME_OF_DAY.withZone(zone).format(updatedAt);
        }
        if (days == 1) {
            return "Yesterday " + TIME_OF_DAY.withZone(zone).format(updatedAt);
        }
        return ABSOLUTE_DAY.withZone(zone).format(updatedAt);
    }

    private void wireTab(ToggleButton tab, ToggleGroup group, boolean selected) {
        if (tab == null) {
            return;
        }
        tab.setToggleGroup(group);
        tab.setSelected(selected);
        tab.setOnAction(e -> {
            tab.setSelected(true);
            rebuildProjectsTable();
        });
    }

    private static void markTab(ToggleButton tab) {
        if (tab == null) {
            return;
        }
        tab.getStyleClass().remove("project-tab-active");
        if (tab.isSelected()) {
            tab.getStyleClass().add("project-tab-active");
        }
    }

    private static void setVisible(Node node, boolean visible) {
        if (node == null) {
            return;
        }
        node.setVisible(visible);
        node.setManaged(visible);
    }

    VBox pageRootForTest() {
        return pageRoot;
    }

    boolean compactLayoutForTest() {
        return pageRoot != null && !pageRoot.getStyleClass().contains(WorkspaceHomeLayout.WIDE);
    }
}
