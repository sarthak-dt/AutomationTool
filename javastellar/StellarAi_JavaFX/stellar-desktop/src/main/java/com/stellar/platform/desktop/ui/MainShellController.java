package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.AnalysisStorageRequiredException;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.ProjectJourneyFacts;
import com.stellar.platform.desktop.presentation.ProjectJourneyState;
import com.stellar.platform.desktop.presentation.ProjectJourneyStateResolver;
import com.stellar.platform.desktop.presentation.ProjectSaveState;
import com.stellar.platform.desktop.presentation.ProjectSwitchTrace;
import com.stellar.platform.desktop.presentation.UnsavedLeaveChoice;
import com.stellar.platform.desktop.presentation.UnsavedLeaveIntent;
import com.stellar.platform.desktop.presentation.WorkspaceChromeStatus;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.storage.metadata.DuplicateProjectNameException;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import com.stellar.platform.storage.metadata.ProjectDashboardSummary;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Side;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.text.TextAlignment;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import javafx.util.Duration;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Owns Projects Home navigation, the active project workspace, project-state-aware navigation,
 * transient processing routes and resume resolution. There are no numbered workflow steps and no
 * hardcoded screen indexes: every destination is a {@link WorkspaceRoute}.
 */
public final class MainShellController {

    private static final System.Logger LOG = System.getLogger(MainShellController.class.getName());

    /** Tests set this so a switch is dispatched off the JavaFX thread even when isolation is on. */
    public static volatile boolean asyncProjectSwitchForTest;

    private final AtomicLong projectSwitchGeneration = new AtomicLong();
    private final AtomicReference<ProjectSwitchRequest> pendingProjectSwitch = new AtomicReference<>();
    private final AtomicBoolean projectSwitchPump = new AtomicBoolean();
    private final ProjectSwitchTrace.Observer projectSwitchObserver = this::onProjectSwitchStage;
    private volatile boolean projectSwitchActive;
    private volatile boolean shellDisposed;
    private StackPane projectSwitchPanel;
    private Label projectSwitchStatus;
    private Label projectSwitchDetail;
    private ProgressIndicator projectSwitchIndicator;
    private int projectSwitchHideCount;
    private int projectSwitchObserverInstalls;
    /** Project id last published into the shell. Stays put until activation finishes. */
    private String chromeProjectId = "";

    public static final double SIDEBAR_EXPANDED_WIDTH = 250;
    /** Compact icon rail used as the Signals workstation design target. */
    public static final double SIDEBAR_COLLAPSED_WIDTH = 72;

    @FXML
    private HBox projectSelector;
    @FXML
    private Label projectNameLabel;
    @FXML
    private StackPane brandMarkHost;
    @FXML
    private Button projectStatusButton;
    @FXML
    private Label projectStatusLabel;
    @FXML
    private Label projectStageLabel;
    @FXML
    private HBox globalProcessingBox;
    @FXML
    private Label globalProcessingLabel;
    @FXML
    private ProgressBar globalProcessingBar;
    @FXML
    private StackPane contentHost;
    @FXML
    private StackPane workspaceStack;
    @FXML
    private VBox sidebar;
    @FXML
    private Region sidebarSpacer;
    @FXML
    private VBox sidebarHealthCard;
    @FXML
    private Label sidebarHealthValue;
    @FXML
    private VBox projectNavBox;
    @FXML
    private VBox setupNavBox;
    @FXML
    private VBox analysisNavBox;
    @FXML
    private Label setupSectionLabel;
    @FXML
    private Label analysisSectionLabel;
    @FXML
    private Button navProjectsHome;
    @FXML
    private Button navDashboard;
    @FXML
    private Button navSources;
    @FXML
    private Button navDecode;
    @FXML
    private Button navSignals;
    @FXML
    private Button navGraphs;
    @FXML
    private Button navInvestigations;
    @FXML
    private Button navConfiguration;
    @FXML
    private Button navCollapse;
    @FXML
    private Button saveProjectButton;
    @FXML
    private Label saveStatusLabel;
    @FXML
    private Button retrySaveButton;
    @FXML
    private BorderPane root;
    @FXML
    private Button newProjectHeaderButton;
    @FXML
    private HBox topHeader;
    @FXML
    private StackPane headerSearchHost;
    @FXML
    private HBox headerActions;
    @FXML
    private TextField globalSearchField;
    @FXML
    private StackPane headerSearchIcon;
    @FXML
    private StackPane projectSelectorIcon;
    @FXML
    private Button headerSettingsButton;
    @FXML
    private VBox figmaNavBox;
    @FXML
    private HBox workspaceStatusBar;
    @FXML
    private Label statusWorkspaceLabel;
    @FXML
    private Label statusDatabaseLabel;
    @FXML
    private Label statusProjectLabel;
    @FXML
    private Label statusWorkflowLabel;

    private DesktopAppService appService;
    private final Map<DesktopViewHost, Node> pages = new EnumMap<>(DesktopViewHost.class);
    private final Map<DesktopViewHost, Object> controllers = new EnumMap<>(DesktopViewHost.class);
    private boolean sidebarCollapsed;
    /** Last non-analysis sidebar preference restored when leaving Signals/Graphs. */
    private boolean sidebarPreferenceCollapsed;
    private NavigationPage visiblePage = NavigationPage.HOME;
    private WorkspaceRoute visibleRoute = WorkspaceRoute.HOME;
    /** Bumps when a newer Graph navigation supersedes one that has not attached yet. */
    private int graphNavigationGeneration;
    /** Host replaced by the Graph opening frame and released on the following pulse. */
    private DesktopViewHost pendingGraphEvict;
    private int pendingGraphEvictGeneration;
    /** Sidebar open paints the waveform shell before the inspector skin. */
    private boolean deferGraphInspector;
    private Node deferredInspector;
    private javafx.scene.layout.Pane deferredInspectorParent;
    private boolean graphAnalysisFullScreen;
    private Predicate<String> stopProcessingDecision;
    private Function<UnsavedLeaveIntent, UnsavedLeaveChoice> unsavedLeaveDecision;
    private String newProjectNameForTest;
    private String lastLifecycleErrorKind;
    private String lastLifecycleError;
    private Runnable saveStateListener;
    private ProcessingWarningDrawer warningDrawer;

    @FXML
    private void initialize() {
        installNavChrome();
        installBrandMark();
        applySidebarCollapsed(false);
        projectNameLabel.setMinWidth(0);
        projectNameLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(projectNameLabel, Priority.SOMETIMES);
        projectNameLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        if (projectSelector != null) {
            projectSelector.setFocusTraversable(true);
            projectSelector.setAccessibleText("Switch recent projects");
            Tooltip.install(projectSelector, new Tooltip("Switch between recent saved projects"));
        }
        if (saveProjectButton != null) {
            saveProjectButton.setTooltip(new Tooltip("Save the active project (Ctrl+S)"));
        }
        if (headerActions != null) {
            headerActions.setMinWidth(Region.USE_PREF_SIZE);
            HBox.setHgrow(headerActions, Priority.NEVER);
        }
        if (headerSearchHost != null) {
            headerSearchHost.setMinWidth(0);
            HBox.setHgrow(headerSearchHost, Priority.SOMETIMES);
        }
        if (projectSelector != null) {
            HBox.setHgrow(projectSelector, Priority.SOMETIMES);
        }
        installSaveAccelerator();
        if (newProjectHeaderButton != null) {
            newProjectHeaderButton.setTooltip(new Tooltip("Create a new project"));
        }
        if (projectStatusButton != null) {
            projectStatusButton.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            projectStatusButton.setFocusTraversable(true);
            projectStatusButton.setTooltip(new Tooltip("Project status — click to continue where you left off"));
        }
        if (projectSelectorIcon != null) {
            projectSelectorIcon.getChildren().setAll(NavIcons.projectFiles());
        }
        if (headerSearchIcon != null) {
            headerSearchIcon.getChildren().setAll(StellarIcons.search());
        }
        if (headerSettingsButton != null) {
            headerSettingsButton.setGraphic(NavIcons.settings());
        }
        if (globalSearchField != null) {
            globalSearchField.setTooltip(new Tooltip(
                    "Filters saved projects on Workspace. When Signals is open, also filters the in-memory signal catalog."));
            globalSearchField.textProperty().addListener((o, a, b) -> applyGlobalSearch(b));
        }
    }

    public void init(DesktopAppService appService) throws IOException {
        this.appService = appService;
        installProjectSwitchObserver();
        if (appService != null) {
            saveStateListener = this::refreshSaveStatusOnFx;
            appService.addSaveStateListener(saveStateListener);
            appService.setProjectMetadataCacheListener(() -> Platform.runLater(this::refreshProjectChrome));
        }
        // Startup is intentionally light: only Workspace is materialized.
        ensureHostLoaded(DesktopViewHost.HOME);
        openMostRecentlyUpdatedProjectAtStartup();
        openRoute(WorkspaceRoute.HOME);
        scheduleOverlayProbeAutoOpen();
    }

    /**
     * If saved projects exist and none is already active, hydrate the most recently
     * updated project through the existing open lifecycle and remain on Workspace.
     */
    private void openMostRecentlyUpdatedProjectAtStartup() {
        if (appService == null || appService.isDurableNamedProject()) {
            return;
        }
        String projectId = appService.mostRecentlyUpdatedProjectId().orElse(null);
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        openSavedProject(projectId, false);
    }

    /**
     * Temporary compositing probe only. Opens Test1 / Bengaluru and Map Route so OSM fill can be
     * observed without clicking. No-op unless {@code stellar.map.overlayProbe} is {@code none} or
     * {@code route}.
     */
    private void scheduleOverlayProbeAutoOpen() {
        String probe = System.getProperty("stellar.map.overlayProbe", "").trim().toLowerCase();
        String tileProbe = System.getProperty("stellar.map.tileProbe", "").trim();
        if (!"none".equals(probe) && !"route".equals(probe) && tileProbe.isEmpty()) {
            return;
        }
        Thread starter = new Thread(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            javafx.application.Platform.runLater(this::openOverlayProbeProject);
        }, "overlay-probe-auto-open");
        starter.setDaemon(true);
        starter.start();
    }

    private void openOverlayProbeProject() {
        if (appService == null) {
            return;
        }
        String targetId = null;
        String targetName = "";
        for (ProjectDashboardSummary project : appService.projectDashboardSummaries()) {
            String name = project.name() == null ? "" : project.name();
            String lower = name.toLowerCase();
            if (lower.equals("test1") || lower.contains("bengaluru") || lower.contains("test 1")) {
                targetId = project.projectId();
                targetName = name;
                break;
            }
        }
        if (targetId == null) {
            LOG.log(System.Logger.Level.WARNING,
                    "OVERLAY-PROBE Test1/Bengaluru project not in dashboard; not auto-opening");
            return;
        }
        LOG.log(System.Logger.Level.WARNING, "OVERLAY-PROBE auto-open project=" + targetName);
        if (!targetId.equals(appService.currentProjectId()) || !appService.isDurableNamedProject()) {
            openSavedProject(targetId, true, this::openMapRoute);
            return;
        }
        openMapRoute();
    }

    @FXML
    private void onProjectSelectorClicked(MouseEvent event) {
        showProjectSwitcher();
        if (event != null) event.consume();
    }

    /** Projects Home / header entry: create project via existing New Project flow. */
    public void requestNewProject() {
        onNewProject();
    }

    /** Workspace Quick Action: open the existing saved-project switcher. */
    public void requestOpenExistingProject() {
        showProjectSwitcher();
    }

    /** Projects Home: open a saved project and resume at its meaningful workspace. */
    public void requestOpenSavedProject(String projectId) {
        openSavedProject(projectId);
    }

    /**
     * Projects Home: rename. Uses existing {@link DesktopAppService#renameProject(String)} on the
     * active project. If another saved project is selected, it is opened first (same discard/save gate).
     */
    public void requestRenameProject(String projectId) {
        if (appService == null || projectId == null || projectId.isBlank()) {
            return;
        }
        if (!projectId.equals(appService.currentProjectId())) {
            openSavedProject(projectId, true, this::finishRenameAfterSwitch);
            return;
        }
        finishRenameAfterSwitch();
    }

    private void finishRenameAfterSwitch() {
        onRenameProject();
        refreshHost(DesktopViewHost.PROJECTS_HOME);
        refreshHost(DesktopViewHost.HOME);
    }

    /** Projects Home: delete via existing confirmation + {@link DesktopAppService#deleteProject(String)}. */
    public void requestDeleteProject(String projectId) {
        onDeleteProject(projectId);
        refreshHost(DesktopViewHost.PROJECTS_HOME);
        refreshHost(DesktopViewHost.HOME);
    }

    /** Open the active project at its resolved resume destination. */
    public void resumeActiveProject() {
        if (appService == null) {
            return;
        }
        openRoute(appService.resolveResumeRoute());
    }

    private void showProjectSwitcher() {
        if (appService == null || projectSelector == null) {
            return;
        }
        appService.loadProjectDashboardSummaries(this::showProjectSwitcherMenu);
    }

    private void showProjectSwitcherMenu(List<ProjectDashboardSummary> projects) {
        if (projectSelector == null || projectSelector.getScene() == null || projectSelector.getScene().getWindow() == null) {
            return;
        }
        List<ProjectDashboardSummary> rows = projects == null ? List.of() : projects;
        ContextMenu menu = new ContextMenu();
        if (rows.isEmpty()) {
            MenuItem empty = new MenuItem("No saved projects");
            empty.setDisable(true);
            menu.getItems().add(empty);
            MenuItem create = new MenuItem("New Project…");
            create.setOnAction(e -> requestNewProject());
            menu.getItems().add(create);
        } else {
            boolean switchLocked = projectSwitchActive;
            String activeId = switchLocked ? chromeProjectId : appService.currentProjectId();
            boolean durable = switchLocked
                    ? activeId != null && !activeId.isBlank()
                    : appService.isDurableNamedProject();
            if (durable) {
                MenuItem close = new MenuItem("Close Project");
                close.setDisable(switchLocked);
                close.setOnAction(e -> closeActiveProject());
                menu.getItems().add(close);
            }
            int limit = Math.min(10, rows.size());
            for (int i = 0; i < limit; i++) {
                ProjectDashboardSummary project = rows.get(i);
                boolean active = durable && project.projectId().equals(activeId);
                MenuItem item = new MenuItem((active ? "● " : "  ") + project.name());
                item.setOnAction(e -> requestOpenSavedProject(project.projectId()));
                menu.getItems().add(item);
            }
        }
        menu.show(projectSelector, Side.BOTTOM, 0, 4);
    }

    /**
     * Hydrate the saved project, then resolve where it should actually open.
     * There is deliberately no Projects Home bounce in between.
     */
    private void openSavedProject(String projectId) {
        openSavedProject(projectId, true, null);
    }

    private void openSavedProject(String projectId, boolean resumeWorkspace) {
        openSavedProject(projectId, resumeWorkspace, null);
    }

    private void openSavedProject(String projectId, boolean resumeWorkspace, Runnable afterSuccess) {
        if (!mayLeaveCurrentProject(UnsavedLeaveIntent.SWITCH, "Stop & Switch")) {
            return;
        }
        if (projectSwitchLeavesFxThread()) {
            dispatchProjectSwitch(projectId, resumeWorkspace, afterSuccess);
            return;
        }
        completeProjectSwitchOnFx(
                openSavedProjectOnCaller(projectId), resumeWorkspace, afterSuccess, projectSwitchGeneration.get());
    }

    private boolean projectSwitchLeavesFxThread() {
        if (!javafx.application.Platform.isFxApplicationThread()) {
            return false;
        }
        if (asyncProjectSwitchForTest) {
            return true;
        }
        return !Boolean.getBoolean("stellarai.test.isolate");
    }

    private void dispatchProjectSwitch(String projectId, boolean resumeWorkspace, Runnable afterSuccess) {
        long generation = projectSwitchGeneration.incrementAndGet();
        long started = ProjectSwitchTrace.now();
        pendingProjectSwitch.set(new ProjectSwitchRequest(projectId, resumeWorkspace, afterSuccess, generation));
        evictAnalysisHosts();
        showProjectSwitchPending();
        ProjectSwitchTrace.stage(generation, "switch-requested", started, 0, false);
        if (projectSwitchPump.compareAndSet(false, true)) {
            appService.submitProjectSwitch(this::pumpProjectSwitches);
        }
    }

    private void pumpProjectSwitches() {
        try {
            while (true) {
                ProjectSwitchRequest request = pendingProjectSwitch.getAndSet(null);
                if (request == null) {
                    return;
                }
                long started = ProjectSwitchTrace.now();
                DesktopAppService.ProjectOpenResult result;
                try {
                    result = appService.openSavedProject(
                            request.projectId(),
                            request.generation(),
                            () -> projectSwitchGeneration.get() != request.generation());
                } catch (Throwable ex) {
                    LOG.log(System.Logger.Level.ERROR, "Open project handler failed for switch " + request.generation(), ex);
                    result = new DesktopAppService.ProjectOpenResult(false, "", List.of(userFacingMessage(ex)));
                }
                if (projectSwitchGeneration.get() != request.generation() || pendingProjectSwitch.get() != null) {
                    ProjectSwitchTrace.stage(request.generation(), "publish-skipped", started, 0, true);
                    continue;
                }
                CountDownLatch applied = new CountDownLatch(1);
                DesktopAppService.ProjectOpenResult published = result;
                javafx.application.Platform.runLater(() -> {
                    try {
                        if (!projectSwitchStillCurrent(request.generation())) {
                            return;
                        }
                        completeProjectSwitchOnFx(
                                published, request.resumeWorkspace(), request.afterSuccess(), request.generation());
                        if (projectSwitchStillCurrent(request.generation())) {
                            ProjectSwitchTrace.stage(request.generation(), "ui-ready", started, 0, false);
                        }
                    } finally {
                        applied.countDown();
                    }
                });
                try {
                    if (!applied.await(5, TimeUnit.MINUTES)) {
                        LOG.log(System.Logger.Level.WARNING, "Project switch UI apply timed out gen=" + request.generation());
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } finally {
            projectSwitchPump.set(false);
            if (pendingProjectSwitch.get() != null && projectSwitchPump.compareAndSet(false, true)) {
                appService.submitProjectSwitch(this::pumpProjectSwitches);
            }
        }
    }

    private DesktopAppService.ProjectOpenResult openSavedProjectOnCaller(String projectId) {
        long started = ProjectSwitchTrace.now();
        try {
            evictAnalysisHosts();
            ProjectSwitchTrace.stage(0L, "evict", started, 0, false);
            return appService.openSavedProject(projectId);
        } catch (Throwable ex) {
            LOG.log(System.Logger.Level.ERROR, "Open project handler failed", ex);
            return new DesktopAppService.ProjectOpenResult(false, "", List.of(userFacingMessage(ex)));
        }
    }

    private void showProjectSwitchPending() {
        projectSwitchActive = true;
        if (contentHost == null) {
            applyProjectSwitchCommandLock();
            return;
        }
        ensureProjectSwitchPanel();
        projectSwitchStatus.setText("Opening project…");
        projectSwitchDetail.setText("");
        projectSwitchDetail.setVisible(false);
        projectSwitchDetail.setManaged(false);
        projectSwitchPanel.setAccessibleText("Opening project…");
        projectSwitchIndicator.setAccessibleText("Opening project…");
        hideWarningDrawer();
        contentHost.getChildren().setAll(projectSwitchPanel);
        applyProjectSwitchCommandLock();
        parkFocusOnShell();
    }

    /**
     * Analysis controllers are disposed at switch start, so the previous graph is not kept
     * underneath the card. Header, navigation and status chrome stay in place.
     */
    private void ensureProjectSwitchPanel() {
        if (projectSwitchPanel != null) {
            return;
        }
        projectSwitchIndicator = new ProgressIndicator();
        projectSwitchIndicator.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        projectSwitchIndicator.setMinSize(36, 36);
        projectSwitchIndicator.setPrefSize(36, 36);
        projectSwitchIndicator.setMaxSize(36, 36);
        projectSwitchIndicator.setFocusTraversable(false);
        projectSwitchIndicator.setMouseTransparent(true);
        projectSwitchIndicator.getStyleClass().add("project-switch-indicator");
        projectSwitchIndicator.setAccessibleRole(AccessibleRole.PROGRESS_INDICATOR);
        projectSwitchIndicator.setAccessibleText("Opening project…");

        Label title = new Label("Opening project");
        title.getStyleClass().add("project-switch-title");
        title.setWrapText(true);
        title.setTextAlignment(TextAlignment.CENTER);
        title.setAlignment(Pos.CENTER);
        title.setMaxWidth(340);
        title.setFocusTraversable(false);

        projectSwitchStatus = new Label("Opening project…");
        projectSwitchStatus.getStyleClass().add("project-switch-status");
        projectSwitchStatus.setWrapText(true);
        projectSwitchStatus.setTextAlignment(TextAlignment.CENTER);
        projectSwitchStatus.setAlignment(Pos.CENTER);
        projectSwitchStatus.setMaxWidth(340);
        projectSwitchStatus.setFocusTraversable(false);
        projectSwitchStatus.setAccessibleRole(AccessibleRole.TEXT);
        projectSwitchStatus.setAccessibleText("Opening project…");

        projectSwitchDetail = new Label();
        projectSwitchDetail.getStyleClass().add("project-switch-detail");
        projectSwitchDetail.setWrapText(true);
        projectSwitchDetail.setTextAlignment(TextAlignment.CENTER);
        projectSwitchDetail.setAlignment(Pos.CENTER);
        projectSwitchDetail.setMaxWidth(340);
        projectSwitchDetail.setFocusTraversable(false);
        projectSwitchDetail.setVisible(false);
        projectSwitchDetail.setManaged(false);
        projectSwitchDetail.setAccessibleRole(AccessibleRole.TEXT);

        VBox card = new VBox(8, projectSwitchIndicator, title, projectSwitchStatus, projectSwitchDetail);
        card.setAlignment(Pos.CENTER);
        card.setFillWidth(true);
        card.setMinWidth(0);
        card.setPrefWidth(360);
        card.setMaxWidth(420);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setPadding(new Insets(24, 28, 24, 28));
        card.setFocusTraversable(false);
        card.getStyleClass().add("project-switch-card");

        projectSwitchPanel = new StackPane(card);
        projectSwitchPanel.setAlignment(Pos.CENTER);
        projectSwitchPanel.setMinSize(0, 0);
        projectSwitchPanel.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        projectSwitchPanel.setPickOnBounds(true);
        projectSwitchPanel.setFocusTraversable(false);
        projectSwitchPanel.getStyleClass().add("project-switch-panel");
        projectSwitchPanel.setAccessibleRole(AccessibleRole.TEXT);
        projectSwitchPanel.setAccessibleText("Opening project…");
        StackPane.setAlignment(card, Pos.CENTER);
    }

    private void parkFocusOnShell() {
        if (projectSelector == null || projectSelector.getScene() == null) {
            return;
        }
        Node focus = projectSelector.getScene().getFocusOwner();
        if (focus == null || focus.getScene() == null || isInsideProjectSwitch(focus)) {
            projectSelector.requestFocus();
        }
    }

    private boolean isInsideProjectSwitch(Node node) {
        Node current = node;
        while (current != null) {
            if (current == projectSwitchPanel) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private void applyProjectSwitchCommandLock() {
        setDisabled(saveProjectButton, true);
        setDisabled(retrySaveButton, true);
        setDisabled(newProjectHeaderButton, true);
        setDisabled(projectStatusButton, true);
        setDisabled(navSources, true);
        setDisabled(navDecode, true);
        setDisabled(navSignals, true);
        setDisabled(navGraphs, true);
        setDisabled(navInvestigations, true);
    }

    private static void setDisabled(Node node, boolean disabled) {
        if (node != null) {
            node.setDisable(disabled);
        }
    }

    private void onProjectSwitchStage(long generation, String stage, boolean cancelled) {
        if (shellDisposed || cancelled || generation <= 0L) {
            return;
        }
        String text = projectSwitchStatusForStage(stage);
        if (text == null) {
            return;
        }
        Runnable apply = () -> applyProjectSwitchStatus(generation, text);
        if (javafx.application.Platform.isFxApplicationThread()) {
            apply.run();
        } else {
            javafx.application.Platform.runLater(apply);
        }
    }

    private void applyProjectSwitchStatus(long generation, String text) {
        if (shellDisposed || !projectSwitchActive || generation != projectSwitchGeneration.get()) {
            return;
        }
        if (projectSwitchStatus == null) {
            return;
        }
        if ("Opening project…".equals(text)) {
            projectSwitchStatus.setText(text);
            projectSwitchStatus.setAccessibleText(text);
            projectSwitchDetail.setText("");
            projectSwitchDetail.setVisible(false);
            projectSwitchDetail.setManaged(false);
        } else {
            projectSwitchDetail.setText(text);
            projectSwitchDetail.setAccessibleText(text);
            projectSwitchDetail.setVisible(true);
            projectSwitchDetail.setManaged(true);
        }
        if (projectSwitchPanel != null) {
            projectSwitchPanel.setAccessibleText(text);
        }
        if (projectSwitchIndicator != null) {
            projectSwitchIndicator.setAccessibleText(text);
        }
    }

    static String projectSwitchStatusForStage(String stage) {
        if (stage == null) {
            return null;
        }
        return switch (stage) {
            case "switch-requested" -> "Opening project…";
            case "metadata" -> "Loading project metadata";
            case "dbc" -> "Restoring measurements and definitions";
            case "artifacts" -> "Restoring saved analysis";
            case "graph", "graph-restore-skipped", "open-complete" -> "Preparing workspace";
            default -> null;
        };
    }

    private void installProjectSwitchObserver() {
        if (projectSwitchObserverInstalls > 0) {
            return;
        }
        ProjectSwitchTrace.setObserver(projectSwitchObserver);
        projectSwitchObserverInstalls++;
    }

    private boolean projectSwitchStillCurrent(long generation) {
        return generation == projectSwitchGeneration.get() && pendingProjectSwitch.get() == null;
    }

    private void hideProjectSwitchLoading() {
        boolean showing = projectSwitchActive
                || (contentHost != null && projectSwitchPanel != null
                && contentHost.getChildren().contains(projectSwitchPanel));
        if (!showing) {
            return;
        }
        projectSwitchActive = false;
        if (contentHost != null && projectSwitchPanel != null) {
            contentHost.getChildren().remove(projectSwitchPanel);
        }
        projectSwitchHideCount++;
    }

    boolean projectSwitchActiveForTest() {
        return projectSwitchActive;
    }

    boolean projectSwitchOverlayVisibleForTest() {
        return projectSwitchPanel != null
                && projectSwitchPanel.getScene() != null
                && projectSwitchPanel.isVisible()
                && contentHost != null
                && contentHost.getChildren().contains(projectSwitchPanel);
    }

    String projectSwitchStatusTextForTest() {
        return projectSwitchStatus == null ? "" : projectSwitchStatus.getText();
    }

    String projectSwitchDetailTextForTest() {
        return projectSwitchDetail == null || !projectSwitchDetail.isVisible() ? "" : projectSwitchDetail.getText();
    }

    int projectSwitchHideCountForTest() {
        return projectSwitchHideCount;
    }

    int projectSwitchObserverInstallsForTest() {
        return projectSwitchObserverInstalls;
    }

    /** Restores the shell observer after another controller replaced it. Does not count as a new install. */
    void ensureProjectSwitchObserverForTest() {
        ProjectSwitchTrace.setObserver(projectSwitchObserver);
    }

    long projectSwitchGenerationForTest() {
        return projectSwitchGeneration.get();
    }

    private void completeProjectSwitchOnFx(
            DesktopAppService.ProjectOpenResult result,
            boolean resumeWorkspace,
            Runnable afterSuccess,
            long generation
    ) {
        if (!projectSwitchStillCurrent(generation)) {
            return;
        }
        if (result == null) {
            hideProjectSwitchLoading();
            if (appService != null) {
                openRoute(WorkspaceRoute.HOME);
            }
            return;
        }
        if (!result.opened()) {
            if (result.warnings().contains("SWITCH_SUPERSEDED")) {
                return;
            }
            hideProjectSwitchLoading();
            recordLifecycleFailure("OPEN", result.warnings().isEmpty()
                    ? "The saved project could not be loaded."
                    : String.join("\n", result.warnings()));
            showLifecycleError("Open Project", "Could not open project",
                    lastLifecycleError == null ? "The saved project could not be loaded." : lastLifecycleError);
            openRoute(WorkspaceRoute.HOME);
            return;
        }
        hideProjectSwitchLoading();
        try {
            if (resumeWorkspace) {
                onProjectHydrated();
            } else {
                refreshAll();
                openRoute(WorkspaceRoute.HOME);
            }
        } catch (Throwable ex) {
            LOG.log(System.Logger.Level.ERROR, "Opened project but routing failed", ex);
            recordLifecycleFailure("OPEN_ROUTE", ex);
            showLifecycleError("Open Project",
                    "Project was loaded but the workspace could not be opened",
                    userFacingMessage(ex));
            return;
        }
        if (!projectSwitchStillCurrent(generation)) {
            return;
        }
        if (!result.warnings().isEmpty()) {
            var presentation = com.stellar.platform.desktop.presentation.ProjectOpenUserMessage
                    .fromWarnings(result.projectName(), result.warnings());
            for (String detail : presentation.technicalDetails()) {
                LOG.log(System.Logger.Level.INFO, "Open project detail: " + detail);
            }
            if (presentation.showDialog() && !suppressModalDialogsForTest()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("Project Opened");
                alert.setHeaderText(presentation.header());
                alert.setContentText(presentation.body());
                alert.showAndWait();
            }
        }
        if (afterSuccess != null && projectSwitchStillCurrent(generation)) {
            afterSuccess.run();
        }
    }

    private record ProjectSwitchRequest(
            String projectId,
            boolean resumeWorkspace,
            Runnable afterSuccess,
            long generation
    ) {
    }

    /** New Project goes straight to Sources — never through Projects Home. */
    @FXML
    private void onNewProject() {
        if (projectSwitchActive) {
            return;
        }
        if (!mayLeaveCurrentProject(UnsavedLeaveIntent.SWITCH, "Stop & Switch")) {
            return;
        }
        if (!ensureAnalysisStorageConfigured()) {
            return;
        }
        if (newProjectNameForTest != null) {
            applyCreatedProject(newProjectNameForTest);
            return;
        }
        promptAndCreateProject();
    }

    /**
     * Shared gate for every New Project entry point. Prompts Configuration when storage is missing.
     */
    boolean ensureAnalysisStorageConfigured() {
        if (appService == null) {
            return false;
        }
        try {
            appService.analysisStorage().requireValidForProjectCreation();
            return true;
        } catch (AnalysisStorageRequiredException ex) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Analysis Storage required");
            alert.setHeaderText("Analysis storage must be configured before creating a project.");
            alert.setContentText(ex.getMessage());
            ButtonType configure = new ButtonType("Configure Storage", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(configure, cancel);
            Optional<ButtonType> choice = alert.showAndWait();
            if (choice.isPresent() && choice.get() == configure) {
                openRoute(WorkspaceRoute.CONFIGURATION);
            }
            return false;
        }
    }

    /**
     * Authoritative create used by both the header "+ New Project" button and the
     * Projects Home "Start Project" tile.
     */
    void applyCreatedProject(String name) {
        if (!ensureAnalysisStorageConfigured()) {
            return;
        }
        try {
            evictAnalysisHosts();
            appService.newProject(name);
        } catch (AnalysisStorageRequiredException ex) {
            ensureAnalysisStorageConfigured();
            return;
        } catch (DuplicateProjectNameException | IllegalArgumentException ex) {
            recordLifecycleFailure("CREATE", ex);
            showProjectNameError(ex.getMessage());
            return;
        } catch (Throwable ex) {
            LOG.log(System.Logger.Level.ERROR, "New project failed", ex);
            recordLifecycleFailure("CREATE", ex);
            showLifecycleError("New Project", "Could not create project", userFacingMessage(ex));
            return;
        }
        try {
            onProjectCreated();
        } catch (Throwable ex) {
            LOG.log(System.Logger.Level.ERROR, "Project was created but routing failed", ex);
            recordLifecycleFailure("CREATE_ROUTE", ex);
            showLifecycleError("New Project",
                    "Project was created but the workspace could not be opened",
                    userFacingMessage(ex));
        }
    }

    /** A freshly created project always starts at Sources — never at Projects Home. */
    void onProjectCreated() {
        refreshAll();
        openRoute(WorkspaceRoute.SOURCES);
    }

    /**
     * A hydrated project opens at the destination its state actually supports — never at
     * Projects Home and never blindly at its last visible page.
     */
    void onProjectHydrated() {
        refreshAll();
        openRoute(appService.resolveResumeRoute());
    }

    @FXML
    private void onRetrySave() {
        onSaveProject();
    }

    /** Close the active project and return to Projects Home. */
    public boolean closeActiveProject() {
        if (projectSwitchActive) {
            return false;
        }
        if (appService == null || !appService.isDurableNamedProject()) {
            openRoute(WorkspaceRoute.HOME);
            return true;
        }
        if (!confirmStopProcessingIfActive("Stop & Close")) {
            return false;
        }
        if (!confirmUnsavedChanges(UnsavedLeaveIntent.CLOSE)) {
            return false;
        }
        evictAnalysisHosts();
        appService.closeProject();
        refreshAll();
        openRoute(WorkspaceRoute.HOME);
        return true;
    }

    @FXML
    private void onSaveProject() {
        if (projectSwitchActive || appService == null) {
            return;
        }
        try {
            DesktopAppService.ProjectSaveResult result = appService.saveProject();
            if (!result.saved() && "NAME_REQUIRED".equals(result.message())) {
                TextInputDialog dialog = new TextInputDialog("");
                dialog.setTitle("Save Project");
                dialog.setHeaderText("Enter a unique project name");
                dialog.setContentText("Project name:");
                Optional<String> name = dialog.showAndWait();
                if (name.isEmpty()) {
                    return;
                }
                result = appService.saveProject(name.get());
            }
            if (result.saved()) {
                showSubtleSaveFeedback();
                refreshChrome();
                refreshHost(DesktopViewHost.PROJECTS_HOME);
                refreshHost(DesktopViewHost.HOME);
            } else if (!"NAME_REQUIRED".equals(result.message())) {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Save Project");
                alert.setHeaderText("Project could not be saved.\nYour source files were not modified.");
                alert.setContentText(String.join("\n", result.warnings()));
                alert.showAndWait();
            }
        } catch (DuplicateProjectNameException | IllegalArgumentException ex) {
            showProjectNameError(ex.getMessage());
        }
    }

    private void onRenameProject() {
        if (projectSwitchActive || appService == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog(appService.workspace().projectName());
        dialog.setTitle("Rename Project");
        dialog.setHeaderText("Rename Project");
        dialog.setContentText("Project name:");
        Optional<String> name = dialog.showAndWait();
        name.ifPresent(n -> {
            try {
                appService.renameProject(n);
                refreshChrome();
                showSubtleSaveFeedback();
                refreshHost(DesktopViewHost.PROJECTS_HOME);
            } catch (DuplicateProjectNameException | IllegalArgumentException ex) {
                showProjectNameError(ex.getMessage());
            }
        });
    }

    private void onDeleteProject(String projectId) {
        if (projectSwitchActive || appService == null) {
            return;
        }
        String projectName = appService.savedProjects().stream()
                .filter(p -> p.projectId().equals(projectId))
                .map(com.stellar.platform.storage.metadata.ProjectMetadata::name)
                .findFirst()
                .orElse(appService.workspace().projectName());

        ButtonType deleteType = new ButtonType("Delete Project", ButtonBar.ButtonData.OTHER);
        ButtonType cancelType = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Project?");
        confirm.setHeaderText("Delete Project?");
        confirm.setContentText(
                "You are about to permanently delete:\n\n"
                        + projectName + "\n\n"
                        + "This removes the StellarAI project and its saved analysis.\n\n"
                        + "Original source files are not deleted.\n"
                        + "MF4, ASC, BLF and DBC originals stay where they are.\n\n"
                        + "This action cannot be undone.");
        confirm.getButtonTypes().setAll(cancelType, deleteType);
        confirm.getDialogPane().lookupButton(cancelType).requestFocus();

        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() != deleteType) {
            return;
        }

        DesktopAppService.ProjectDeleteResult result = appService.deleteProject(projectId);
        if (!result.deleted()) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Delete Project");
            alert.setHeaderText("Project could not be deleted completely");
            alert.setContentText(result.warnings().isEmpty()
                    ? "The project was not removed."
                    : String.join("\n", result.warnings()));
            alert.showAndWait();
        } else if (!result.warnings().isEmpty()) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Delete Project");
            alert.setHeaderText("Project metadata removed with warnings");
            alert.setContentText(String.join("\n", result.warnings()));
            alert.showAndWait();
        }
        refreshAll();
        openRoute(WorkspaceRoute.HOME);
    }

    private void promptAndCreateProject() {
        TextInputDialog dialog = new TextInputDialog("");
        dialog.setTitle("New Project");
        dialog.setHeaderText("Create a new working project");
        dialog.setContentText("Project name:");
        Label inlineError = new Label();
        inlineError.setWrapText(true);
        inlineError.getStyleClass().add("form-error");
        inlineError.setManaged(false);
        inlineError.setVisible(false);
        VBox content = new VBox(8);
        Label prompt = new Label("Project name:");
        content.getChildren().addAll(prompt, dialog.getEditor(), inlineError);
        dialog.getDialogPane().setContent(content);
        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        if (ok != null) {
            ok.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                String candidate = dialog.getEditor().getText();
                try {
                    com.stellar.platform.storage.metadata.ProjectNameNormalizer.requireDisplayName(candidate);
                    if (appService != null && appService.projectNameTaken(candidate)) {
                        inlineError.setText("A project with this name already exists.");
                        inlineError.setVisible(true);
                        inlineError.setManaged(true);
                        event.consume();
                    }
                } catch (IllegalArgumentException ex) {
                    inlineError.setText(ex.getMessage());
                    inlineError.setVisible(true);
                    inlineError.setManaged(true);
                    event.consume();
                }
            });
        }
        Optional<String> name = dialog.showAndWait();
        name.ifPresent(this::applyCreatedProject);
    }

    private void recordLifecycleFailure(String kind, Throwable error) {
        recordLifecycleFailure(kind, userFacingMessage(error));
    }

    private void recordLifecycleFailure(String kind, String message) {
        lastLifecycleErrorKind = kind;
        lastLifecycleError = message;
    }

    private void showLifecycleError(String title, String header, String message) {
        if (suppressModalDialogsForTest()) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(message == null || message.isBlank()
                ? "An unexpected error occurred. See the application log for details."
                : message);
        alert.showAndWait();
    }

    private static String userFacingMessage(Throwable error) {
        if (error == null) {
            return "An unexpected error occurred.";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.toString() : message;
    }

    public void setNewProjectNameForTest(String name) {
        this.newProjectNameForTest = name;
    }

    public String lastLifecycleErrorForTest() {
        return lastLifecycleError;
    }

    public String lastLifecycleErrorKindForTest() {
        return lastLifecycleErrorKind;
    }

    public String projectNameChromeForTest() {
        return projectNameLabel == null ? "" : projectNameLabel.getText();
    }

    public String statusProjectTextForTest() {
        return statusProjectLabel == null ? "" : statusProjectLabel.getText();
    }

    public boolean sourcesNavVisibleForTest() {
        return navSources != null && navSources.isVisible();
    }

    public boolean decodeNavVisibleForTest() {
        return navDecode != null && navDecode.isVisible();
    }

    public boolean signalsNavVisibleForTest() {
        return navSignals != null && navSignals.isVisible();
    }

    public boolean graphsNavVisibleForTest() {
        return navGraphs != null && navGraphs.isVisible();
    }

    public boolean signalsNavDisabledForTest() {
        return navSignals == null || navSignals.isDisabled();
    }

    public boolean graphsNavDisabledForTest() {
        return navGraphs == null || navGraphs.isDisabled();
    }

    /**
     * Headless shell tests inject a name and assert {@link #lastLifecycleErrorKind};
     * a real {@code Alert.showAndWait()} blocks the shared FX pulse once any Stage exists.
     */
    private boolean suppressModalDialogsForTest() {
        return newProjectNameForTest != null || asyncProjectSwitchForTest;
    }

    private void showProjectNameError(String message) {
        if (suppressModalDialogsForTest()) {
            return;
        }
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Project Name");
        alert.setHeaderText(null);
        alert.setContentText(message == null || message.isBlank()
                ? DuplicateProjectNameException.friendlyMessage("")
                : message);
        alert.showAndWait();
    }

    private void showSubtleSaveFeedback() {
        if (projectNameLabel == null
                || projectNameLabel.getScene() == null
                || projectNameLabel.getScene().getWindow() == null) {
            return;
        }
        String stamp = appService.lastSavedAt()
                .map(t -> DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(t))
                .orElse("");
        Tooltip tip = new Tooltip(stamp.isEmpty() ? "Project saved" : "Project saved at " + stamp);
        Tooltip.install(projectNameLabel, tip);
        tip.show(projectNameLabel,
                projectNameLabel.localToScreen(0, 0) == null ? 0 : projectNameLabel.localToScreen(0, 0).getX(),
                projectNameLabel.localToScreen(0, 0) == null ? 0 : projectNameLabel.localToScreen(0, 0).getY() + 24);
        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
        pause.setOnFinished(e -> {
            tip.hide();
            if (projectNameLabel != null) {
                Tooltip nameTip = new Tooltip(projectNameLabel.getText());
                projectNameLabel.setTooltip(nameTip);
                if (projectSelector != null) {
                    Tooltip.install(projectSelector, nameTip);
                }
            }
        });
        pause.play();
    }

    /**
     * Everything the desktop needs to agree to before the window closes: an active run cannot
     * survive exit, so it is stopped cooperatively first and then durable project state is saved.
     * Application close always saves (no Discard/Cancel prompt) and then allows the process to exit.
     */
    public boolean confirmExitRequest() {
        if (projectSwitchActive) {
            projectSwitchGeneration.incrementAndGet();
            return true;
        }
        if (!confirmStopProcessingIfActive("Stop Processing & Exit")) {
            return false;
        }
        return saveAllForApplicationExit();
    }

    /**
     * Persist the active durable project before the JVM exits. Untitled / home sessions are a no-op.
     * Save failures offer Force Exit vs Cancel so the engineer is never locked in the app.
     */
    boolean saveAllForApplicationExit() {
        if (appService == null || !appService.isDurableNamedProject()) {
            return true;
        }
        try {
            appService.flushPendingLogicalPersistence();
            DesktopAppService.ProjectSaveResult result = appService.saveProjectDataOnly();
            if (result.saved()) {
                refreshChrome();
                return true;
            }
            if ("NAME_REQUIRED".equals(result.message())) {
                // Durable named projects should not hit this; treat as non-blocking for exit.
                return true;
            }
            return askForceExitAfterSaveFailure(result);
        } catch (RuntimeException ex) {
            DesktopAppService.ProjectSaveResult failed = new DesktopAppService.ProjectSaveResult(
                    false,
                    "Project could not be saved.\nYour source files were not modified.",
                    List.of(ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            return askForceExitAfterSaveFailure(failed);
        }
    }

    private boolean askForceExitAfterSaveFailure(DesktopAppService.ProjectSaveResult result) {
        ButtonType forceExit = new ButtonType("Exit Without Saving", ButtonBar.ButtonData.LEFT);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Save Failed");
        alert.setHeaderText("Project could not be saved before exit");
        String detail = result == null || result.warnings().isEmpty()
                ? "An unexpected error occurred while saving."
                : String.join("\n", result.warnings());
        alert.setContentText(detail + "\n\nYour source measurement/DBC files were not modified.");
        alert.getButtonTypes().setAll(forceExit, cancel);
        alert.getDialogPane().lookupButton(cancel).requestFocus();
        return alert.showAndWait().filter(choice -> choice == forceExit).isPresent();
    }

    /**
     * Creating or opening a project when none is active must not ask Save & Switch,
     * dirty-project, or processing-stop questions.
     */
    private boolean mayLeaveCurrentProject(UnsavedLeaveIntent intent, String stopLabel) {
        if (appService == null || !appService.isDurableNamedProject()) {
            return true;
        }
        return confirmStopProcessingIfActive(stopLabel) && confirmUnsavedChanges(intent);
    }

    public boolean confirmStopProcessingIfActive(String stopLabel) {
        if (appService == null || !appService.isDurableNamedProject() || !appService.isPlanExecutionActive()) {
            return true;
        }
        String label = stopLabel == null || stopLabel.isBlank() ? "Stop Processing" : stopLabel;
        boolean stop = stopProcessingDecision == null
                ? askStopProcessing(label)
                : stopProcessingDecision.test(label);
        if (!stop) {
            return false;
        }
        appService.cancelProcessing();
        return true;
    }

    /** Answers the "stop the active run?" question without showing a modal dialog. */
    public void setStopProcessingDecisionForTest(Predicate<String> decision) {
        this.stopProcessingDecision = decision;
    }

    private boolean askStopProcessing(String stopLabel) {
        ButtonType stop = new ButtonType(stopLabel, ButtonBar.ButtonData.YES);
        ButtonType keepRunning = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Processing In Progress");
        alert.setHeaderText("Processing is still running");
        alert.setContentText("""
                Processing only runs while StellarAI is open.

                Completed results will be preserved. Pending work can be processed later.""");
        alert.getButtonTypes().setAll(stop, keepRunning);
        alert.getDialogPane().lookupButton(keepRunning).requestFocus();
        return alert.showAndWait().filter(choice -> choice == stop).isPresent();
    }

    public boolean confirmDiscardOrSaveIfNeeded() {
        return confirmUnsavedChanges(UnsavedLeaveIntent.CLOSE);
    }

    public boolean confirmUnsavedChanges(UnsavedLeaveIntent intent) {
        UnsavedLeaveIntent effective = intent == null ? UnsavedLeaveIntent.CLOSE : intent;
        if (appService == null || !appService.isDurableNamedProject() || !appService.hasUnsavedChanges()) {
            return true;
        }
        UnsavedLeaveChoice decided = unsavedLeaveDecision == null
                ? askUnsavedChanges(effective)
                : unsavedLeaveDecision.apply(effective);
        if (decided == null || decided == UnsavedLeaveChoice.CANCEL) {
            return false;
        }
        if (decided == UnsavedLeaveChoice.DISCARD) {
            appService.discardUnsavedLogicalChanges();
            return true;
        }
        onSaveProject();
        return !appService.hasUnsavedChanges()
                || appService.projectSaveState() == ProjectSaveState.CLEAN;
    }

    public void setUnsavedLeaveDecisionForTest(Function<UnsavedLeaveIntent, UnsavedLeaveChoice> decision) {
        this.unsavedLeaveDecision = decision;
    }

    private UnsavedLeaveChoice askUnsavedChanges(UnsavedLeaveIntent intent) {
        ButtonType save = new ButtonType(intent.saveLabel(), ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType(intent.discardLabel(), ButtonBar.ButtonData.NO);
        ButtonType cancel = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Unsaved Project Changes");
        alert.setHeaderText("Save changes to \"" + appService.workspace().projectName() + "\"?");
        alert.setContentText("There are unpersisted project changes.");
        alert.getButtonTypes().setAll(save, discard, cancel);
        alert.getDialogPane().lookupButton(cancel).requestFocus();
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == cancel) {
            return UnsavedLeaveChoice.CANCEL;
        }
        if (choice.get() == save) {
            return UnsavedLeaveChoice.SAVE;
        }
        return UnsavedLeaveChoice.DISCARD;
    }

    @FXML
    private void onNavHome() {
        openRoute(WorkspaceRoute.HOME);
    }

    @FXML
    private void onNavProjectsHome() {
        openRoute(WorkspaceRoute.PROJECTS_HOME);
    }

    /** Graphs when analysis data exists; otherwise resume the real project workspace. */
    public void openEngineeringAnalysis() {
        if (appService != null && appService.isDurableNamedProject()
                && appService.projectJourneyFacts().analysisDataReady()) {
            openRoute(WorkspaceRoute.GRAPHS);
            return;
        }
        if (appService != null && appService.isDurableNamedProject()) {
            openRoute(appService.resolveResumeRoute());
            return;
        }
        openRoute(WorkspaceRoute.HOME);
    }

    public void requestOpenLogFile() {
        if (appService == null || !appService.isDurableNamedProject()) {
            requestNewProject();
            return;
        }
        openRoute(WorkspaceRoute.SOURCES);
        Object controller = controllers.get(DesktopViewHost.PROJECT_FILES);
        if (controller instanceof ProjectFilesController projectFiles) {
            projectFiles.requestAddMeasurement();
        }
    }

    public void requestImportDbc() {
        if (appService == null || !appService.isDurableNamedProject()) {
            requestNewProject();
            return;
        }
        openRoute(WorkspaceRoute.SOURCES);
        Object controller = controllers.get(DesktopViewHost.PROJECT_FILES);
        if (controller instanceof ProjectFilesController projectFiles) {
            projectFiles.requestAddDefinition();
        }
    }

    ProjectFilesController projectFilesControllerForTest() {
        ensureHostLoaded(DesktopViewHost.PROJECT_FILES);
        Object controller = controllers.get(DesktopViewHost.PROJECT_FILES);
        return controller instanceof ProjectFilesController projectFiles ? projectFiles : null;
    }

    GraphController graphControllerForTest() {
        ensureHostLoaded(DesktopViewHost.GRAPHS);
        Object controller = controllers.get(DesktopViewHost.GRAPHS);
        return controller instanceof GraphController graph ? graph : null;
    }

    InvestigationController investigationControllerForTest() {
        ensureHostLoaded(DesktopViewHost.INVESTIGATIONS);
        Object controller = controllers.get(DesktopViewHost.INVESTIGATIONS);
        return controller instanceof InvestigationController investigations ? investigations : null;
    }

    private void applyGlobalSearch(String query) {
        String text = query == null ? "" : query;
        Object home = controllers.get(DesktopViewHost.HOME);
        if (home instanceof HomeDashboardController dashboard) {
            dashboard.applySearch(text);
        }
        Object projects = controllers.get(DesktopViewHost.PROJECTS_HOME);
        if (projects instanceof DashboardController projectsHome && projectsHome.searchFieldForTest() != null) {
            projectsHome.searchFieldForTest().setText(text);
        }
        Object signals = controllers.get(DesktopViewHost.SIGNALS);
        if (signals instanceof SignalsController signalsPage) {
            signalsPage.applyHeaderSearch(text);
        }
    }

    @FXML
    private void onNavSources() {
        openRoute(WorkspaceRoute.SOURCES);
    }

    @FXML
    private void onNavDecode() {
        openRoute(WorkspaceRoute.DECODE);
    }

    @FXML
    private void onNavSignals() {
        openRoute(WorkspaceRoute.SIGNALS);
    }

    @FXML
    private void onNavGraphs() {
        openGraphsFromNavigation();
    }

    /**
     * Sidebar Graph navigation paints an opening frame before FXML and skin work.
     * Programmatic {@link #openRoute(WorkspaceRoute)} stays synchronous so callers
     * that need the controller in the same call still receive it.
     */
    void openGraphsFromNavigation() {
        if (appService == null || projectSwitchActive) {
            return;
        }
        if (pages.containsKey(DesktopViewHost.GRAPHS)) {
            openRoute(WorkspaceRoute.GRAPHS);
            return;
        }
        int generation = ++graphNavigationGeneration;
        showGraphOpeningFeedback(generation);
        javafx.scene.Scene scene = contentHost == null ? null : contentHost.getScene();
        if (scene == null || scene.getWindow() == null || !scene.getWindow().isShowing()) {
            Platform.runLater(() -> finishGraphOpen(generation));
            return;
        }
        scene.addPostLayoutPulseListener(new Runnable() {
            @Override
            public void run() {
                scene.removePostLayoutPulseListener(this);
                Platform.runLater(() -> finishGraphOpen(generation));
            }
        });
    }

    private void showGraphOpeningFeedback(int generation) {
        pendingGraphEvict = DesktopViewHost.of(visiblePage);
        pendingGraphEvictGeneration = generation;
        if (pendingGraphEvict == DesktopViewHost.GRAPHS) {
            pendingGraphEvict = null;
        }
        Label opening = new Label("Opening Graph\u2026");
        opening.getStyleClass().add("section-title");
        StackPane placeholder = new StackPane(opening);
        placeholder.setUserData("graph-opening");
        placeholder.getStyleClass().add("graph-page");
        contentHost.getChildren().setAll(placeholder);
        NavigationPage previousPage = visiblePage;
        visiblePage = NavigationPage.GRAPHS;
        visibleRoute = WorkspaceRoute.GRAPHS;
        appService.navigate(NavigationPage.GRAPHS);
        appService.rememberMeaningfulWorkspace(WorkspaceRoute.GRAPHS);
        applyAnalysisCompactNav(previousPage, NavigationPage.GRAPHS);
        markNav(visiblePage);
        refreshChrome();
    }

    private void finishGraphOpen(int generation) {
        if (generation != graphNavigationGeneration || visibleRoute != WorkspaceRoute.GRAPHS) {
            releaseAbandonedGraphEvict(generation);
            return;
        }
        DesktopViewHost evict = pendingGraphEvictGeneration == generation ? pendingGraphEvict : null;
        if (pendingGraphEvictGeneration == generation) {
            pendingGraphEvict = null;
        }
        if (evict != null && evict != DesktopViewHost.GRAPHS && evict.releasesSceneGraphOnLeave()) {
            evictHost(evict);
        } else if (evict != null && evict != DesktopViewHost.GRAPHS) {
            lifecycle(evict, DesktopPageLifecycle::onHide);
        }
        if (generation != graphNavigationGeneration || visibleRoute != WorkspaceRoute.GRAPHS) {
            return;
        }
        deferGraphInspector = true;
        try {
            openRoute(WorkspaceRoute.GRAPHS);
        } finally {
            deferGraphInspector = false;
        }
        if (generation != graphNavigationGeneration || visibleRoute != WorkspaceRoute.GRAPHS) {
            deferredInspector = null;
            deferredInspectorParent = null;
            evictHost(DesktopViewHost.GRAPHS);
            return;
        }
        javafx.scene.Scene scene = contentHost == null ? null : contentHost.getScene();
        if (scene == null || deferredInspector == null) {
            reattachDeferredInspector();
            return;
        }
        scene.addPostLayoutPulseListener(new Runnable() {
            @Override
            public void run() {
                scene.removePostLayoutPulseListener(this);
                Platform.runLater(MainShellController.this::reattachDeferredInspector);
            }
        });
    }

    private void detachInspectorForFirstFrame() {
        Node page = pages.get(DesktopViewHost.GRAPHS);
        if (!(page instanceof javafx.scene.Parent parent)) {
            return;
        }
        Node inspector = parent.lookup("#inspectorPanel");
        if (inspector == null || !(inspector.getParent() instanceof javafx.scene.layout.Pane pane)) {
            return;
        }
        deferredInspector = inspector;
        deferredInspectorParent = pane;
        pane.getChildren().remove(inspector);
    }

    private void reattachDeferredInspector() {
        Node inspector = deferredInspector;
        javafx.scene.layout.Pane parent = deferredInspectorParent;
        deferredInspector = null;
        deferredInspectorParent = null;
        if (inspector == null || parent == null || inspector.getParent() != null) {
            return;
        }
        if (visibleRoute != WorkspaceRoute.GRAPHS || !pages.containsKey(DesktopViewHost.GRAPHS)) {
            return;
        }
        parent.getChildren().add(inspector);
    }

    private void releaseAbandonedGraphEvict(int generation) {
        if (pendingGraphEvictGeneration != generation) {
            return;
        }
        DesktopViewHost abandoned = pendingGraphEvict;
        pendingGraphEvict = null;
        if (abandoned != null
                && abandoned != DesktopViewHost.of(visiblePage)
                && abandoned.releasesSceneGraphOnLeave()) {
            evictHost(abandoned);
        }
    }

    boolean graphOpeningVisibleForTest() {
        return contentHost != null && contentHost.getChildren().stream()
                .anyMatch(node -> "graph-opening".equals(node.getUserData()));
    }

    GraphController loadedGraphControllerForTest() {
        Object controller = controllers.get(DesktopViewHost.GRAPHS);
        return controller instanceof GraphController graph ? graph : null;
    }

    @FXML
    private void onNavInvestigations() {
        openRoute(WorkspaceRoute.INVESTIGATIONS);
    }

    @FXML
    private void onNavConfiguration() {
        openRoute(WorkspaceRoute.CONFIGURATION);
    }

    @FXML
    private void onAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("About StellarAI");
        alert.setHeaderText(DesktopVersion.windowTitle());
        alert.setContentText("Version " + DesktopVersion.displayVersion()
                + "\nProject schema v" + SqliteProjectMetadataStore.currentSchemaVersion()
                + "\nLocal Windows desktop. Core processing does not require internet, PostgreSQL, or stellar-server."
                + "\nProduction formats: MF4 + DBC. ASC is present. BLF is experimental.");
        alert.showAndWait();
    }

    /** Opens Configuration (Analysis Storage) from Projects Home / New Project prompts. */
    public void openConfigurationPage() {
        openRoute(WorkspaceRoute.CONFIGURATION);
    }

    /**
     * Opens Configuration with the GPS &amp; Map section in view. Map Route delegates GPS mapping
     * here rather than keeping a second editor of its own.
     */
    public void openGpsMappingConfiguration() {
        openGpsMappingConfiguration(appService == null ? null : appService.activeAnalysisMeasurement());
    }

    public void openGpsMappingConfiguration(
            com.stellar.platform.desktop.presentation.AnalysisMeasurementContext measurement
    ) {
        openRoute(WorkspaceRoute.CONFIGURATION);
        Object controller = controllers.get(DesktopViewHost.CONFIGURATION);
        if (controller instanceof ConfigurationController configuration) {
            configuration.focusGpsMappingSection(measurement);
        }
    }

    /**
     * Opens Map Route so the user can see the route their GPS mapping produces. The return leg of
     * {@link #openGpsMappingConfiguration()}, so configuring and checking is one loop.
     */
    public void openMapRoute() {
        openRoute(WorkspaceRoute.GRAPHS);
        Object controller = controllers.get(DesktopViewHost.GRAPHS);
        if (controller instanceof GraphController graph) {
            graph.selectMapRouteTab();
        }
    }

    @FXML
    private void onProjectStatusClicked() {
        if (appService != null && appService.projectJourneyState().readyWithWarnings()) {
            toggleWarningDrawer();
            return;
        }
        resumeActiveProject();
    }

    private void toggleWarningDrawer() {
        ensureWarningDrawer();
        if (warningDrawer.isVisible()) {
            hideWarningDrawer();
            return;
        }
        warningDrawer.show(appService.processingWarningReport());
        warningDrawer.setVisible(true);
        warningDrawer.setManaged(true);
    }

    private void hideWarningDrawer() {
        if (warningDrawer == null) {
            return;
        }
        warningDrawer.setVisible(false);
        warningDrawer.setManaged(false);
    }

    private void ensureWarningDrawer() {
        if (warningDrawer != null) {
            return;
        }
        warningDrawer = new ProcessingWarningDrawer(this::hideWarningDrawer);
        StackPane host = workspaceStack != null ? workspaceStack : contentHost;
        if (host != null) {
            StackPane.setAlignment(warningDrawer, Pos.CENTER_RIGHT);
            host.getChildren().add(warningDrawer);
        }
    }

    @FXML
    private void onToggleSidebar() {
        setSidebarCollapsed(!sidebarCollapsed);
        if (!visiblePage.analysisPage()) {
            sidebarPreferenceCollapsed = sidebarCollapsed;
        }
    }

    public boolean isSidebarCollapsed() {
        return sidebarCollapsed;
    }

    public void setSidebarCollapsed(boolean collapsed) {
        applySidebarCollapsed(collapsed);
    }

    /**
     * Analysis-focused graph mode: hides global project chrome, not just the OS window.
     * Active Signals / Graph / Inspector stay with {@link GraphController}.
     */
    public void setGraphAnalysisFullScreen(boolean full) {
        graphAnalysisFullScreen = full;
        setChromeManagedVisible(topHeader, !full);
        setChromeManagedVisible(sidebar, !full);
        setChromeManagedVisible(workspaceStatusBar, !full);
    }

    public boolean isGraphAnalysisFullScreen() {
        return graphAnalysisFullScreen;
    }

    private static void setChromeManagedVisible(Node node, boolean visible) {
        if (node != null) {
            node.setManaged(visible);
            node.setVisible(visible);
        }
    }

    /** Legacy page-level entry retained for controllers that navigate to a single destination. */
    void show(NavigationPage page) {
        openRoute(WorkspaceRoute.forPage(page));
    }

    /**
     * The single navigation entry point. Transient processing routes reuse the existing
     * Processing view host; they never become sidebar entries.
     */
    public void openRoute(WorkspaceRoute route) {
        if (route == null || appService == null || projectSwitchActive) {
            return;
        }
        if (route == WorkspaceRoute.DECODE && appService.projectJourneyFacts().directOnlyWorkflow()) {
            route = WorkspaceRoute.SOURCES;
        }
        NavigationPage page = route.page();
        DesktopViewHost host = DesktopViewHost.of(page);
        DesktopViewHost previousHost = DesktopViewHost.of(visiblePage);
        ensureHostLoaded(host);
        if (previousHost != host && previousHost.releasesSceneGraphOnLeave()) {
            evictHost(previousHost);
        } else if (previousHost != host) {
            lifecycle(previousHost, DesktopPageLifecycle::onHide);
        }
        NavigationPage previousPage = visiblePage;
        appService.navigate(page);
        appService.rememberMeaningfulWorkspace(route);
        if (deferGraphInspector && host == DesktopViewHost.GRAPHS) {
            detachInspectorForFirstFrame();
        }
        contentHost.getChildren().setAll(pages.get(host));
        visiblePage = page;
        visibleRoute = route;
        applyAnalysisCompactNav(previousPage, page);
        applyProjectFilesWorkspace(route);
        applyProcessingRoute(route);
        markNav(visiblePage);
        lifecycle(host, DesktopPageLifecycle::onShow);
        refreshHost(host);
        refreshChrome();
    }

    /** Current workspace destination, including transient processing routes. */
    public WorkspaceRoute visibleRoute() {
        return visibleRoute;
    }

    public NavigationPage visiblePage() {
        return visiblePage;
    }

    /**
     * Project Files owns Sources and Decode as two navigation destinations in one view.
     * It reports its active workspace here so the sidebar stays truthful.
     */
    void onProjectFilesWorkspaceChanged(NavigationPage page) {
        if (appService == null
                || (page != NavigationPage.SOURCES && page != NavigationPage.DECODE)
                || DesktopViewHost.of(visiblePage) != DesktopViewHost.PROJECT_FILES) {
            return;
        }
        visiblePage = page;
        visibleRoute = WorkspaceRoute.forPage(page);
        appService.navigate(page);
        markNav(page);
        refreshChrome();
    }

    /**
     * Signals/Graphs open with the compact icon rail so the workspace receives remaining width.
     * Expand/Collapse remains available; leaving analysis restores the previous preference.
     */
    private void applyAnalysisCompactNav(NavigationPage previous, NavigationPage page) {
        if (page.analysisPage() && !previous.analysisPage()) {
            sidebarPreferenceCollapsed = sidebarCollapsed;
            applySidebarCollapsed(true);
        } else if (!page.analysisPage() && previous.analysisPage()) {
            applySidebarCollapsed(sidebarPreferenceCollapsed);
        }
    }

    /**
     * Review &amp; Process, Processing Resume and Processing Result share one controller, so the
     * route has to say which of them is being opened.
     */
    private void applyProcessingRoute(WorkspaceRoute route) {
        if (!route.transientProcessingRoute()) {
            return;
        }
        Object controller = controllers.get(DesktopViewHost.PROCESSING);
        if (controller instanceof ProcessingController processing) {
            processing.prepareForRoute(route);
        }
    }

    private void applyProjectFilesWorkspace(WorkspaceRoute route) {
        if (route != WorkspaceRoute.SOURCES && route != WorkspaceRoute.DECODE) {
            return;
        }
        Object controller = controllers.get(DesktopViewHost.PROJECT_FILES);
        if (!(controller instanceof ProjectFilesController projectFiles)) {
            return;
        }
        if (route == WorkspaceRoute.SOURCES) {
            projectFiles.openSourcesWorkspace();
        } else {
            projectFiles.openDecodeWorkspace();
        }
    }

    void refreshAll() {
        for (DesktopViewHost host : pages.keySet()) {
            refreshHost(host);
        }
        refreshChrome();
    }

    /**
     * Live processing progress: refresh the Processing page and chrome only.
     */
    void refreshProcessingLive() {
        refreshHost(DesktopViewHost.PROCESSING);
        refreshChrome();
    }

    /** Terminal/run-state chrome only — does not rebuild the Processing host. */
    void refreshProcessingChrome() {
        refreshChrome();
    }

    /**
     * Primary processing UI tick: progress chrome, optional auto-nav to Signals,
     * and live refresh of Processing / Project Files.
     */
    void onProcessingUiTick() {
        boolean autoNav = appService.consumeAutoNavigateToSignals();
        refreshChrome();
        refreshHost(DesktopViewHost.PROCESSING);
        refreshHost(DesktopViewHost.PROJECT_FILES);
        if (autoNav) {
            openRoute(WorkspaceRoute.SIGNALS);
            return;
        }
        if (appService.currentPage() == NavigationPage.SIGNALS) {
            refreshHost(DesktopViewHost.SIGNALS);
        }
    }

    private void refreshChrome() {
        if (projectSwitchActive) {
            applyProjectSwitchCommandLock();
            return;
        }
        boolean durable = appService.isDurableNamedProject();
        chromeProjectId = durable ? appService.currentProjectId() : "";
        String full = durable ? appService.workspace().projectName() : "No active project";
        ProjectSaveState state = appService.projectSaveState();
        projectNameLabel.setText(full);
        Tooltip nameTip = new Tooltip(full);
        projectNameLabel.setTooltip(nameTip);
        if (projectSelector != null) {
            Tooltip.install(projectSelector, nameTip);
        }
        if (newProjectHeaderButton != null) {
            newProjectHeaderButton.setDisable(false);
        }
        if (saveProjectButton != null) {
            boolean showSave = durable;
            saveProjectButton.setVisible(showSave);
            saveProjectButton.setManaged(showSave);
            boolean dirty = state == ProjectSaveState.DIRTY || state == ProjectSaveState.SAVE_FAILED;
            saveProjectButton.setDisable(state == ProjectSaveState.SAVING);
            saveProjectButton.getStyleClass().remove("header-save-button-dirty");
            if (dirty) {
                saveProjectButton.getStyleClass().add("header-save-button-dirty");
            }
            saveProjectButton.setText(dirty ? "Save*" : "Save");
        }
        applySaveStatusChrome(state);
        applyProjectStateChrome();
        updateGlobalProcessingIndicator();
        refreshWorkspaceStatusBar();
    }

    private void refreshSaveStatusOnFx() {
        if (projectSwitchActive) {
            return;
        }
        if (javafx.application.Platform.isFxApplicationThread()) {
            if (appService != null) {
                applySaveStatusChrome(appService.projectSaveState());
            }
        } else {
            javafx.application.Platform.runLater(this::refreshSaveStatusOnFx);
        }
    }

    private void applySaveStatusChrome(ProjectSaveState state) {
        if (saveStatusLabel != null) {
            String text = appService == null ? "" : appService.saveStatusLabel();
            saveStatusLabel.setText(text);
            saveStatusLabel.getStyleClass().remove("save-status-failed");
            boolean failed = state == ProjectSaveState.SAVE_FAILED;
            if (failed) {
                saveStatusLabel.getStyleClass().add("save-status-failed");
            }
            boolean show = appService != null && appService.isDurableNamedProject() && !text.isBlank();
            saveStatusLabel.setVisible(show);
            saveStatusLabel.setManaged(show);
            if (appService != null && show) {
                saveStatusLabel.setTooltip(new Tooltip(appService.saveStatusTooltip()));
            } else {
                saveStatusLabel.setTooltip(null);
            }
        }
        if (retrySaveButton != null) {
            boolean showRetry = state == ProjectSaveState.SAVE_FAILED;
            retrySaveButton.setVisible(showRetry);
            retrySaveButton.setManaged(showRetry);
            retrySaveButton.setDisable(false);
        }
    }

    private void installSaveAccelerator() {
        javafx.scene.Node host = root != null ? root : saveProjectButton;
        if (host == null) {
            return;
        }
        if (host.getScene() != null) {
            bindSaveAccelerator(host.getScene());
        }
        host.sceneProperty().addListener((o, oldScene, scene) -> {
            if (scene != null) {
                bindSaveAccelerator(scene);
            }
        });
    }

    private void bindSaveAccelerator(javafx.scene.Scene scene) {
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN),
                this::onSaveProject);
    }

    /**
     * One project-state read drives the status chip and every navigation availability rule.
     */
    private void applyProjectStateChrome() {
        ProjectJourneyFacts facts = appService.projectJourneyFacts();
        ProjectJourneyState journeyState = ProjectJourneyStateResolver.resolve(facts);
        boolean projectOpen = facts.projectOpen();

        String health = chromeHealth(projectOpen);
        if (projectStatusLabel != null) {
            projectStatusLabel.setText(health);
            projectStatusLabel.getStyleClass().removeAll(
                    "badge-idle", "badge-running", "badge-ready", "badge-warning", "badge-invalid", "badge-attention");
            if (projectOpen) {
                projectStatusLabel.getStyleClass().add(journeyState.statusLabel().badgeStyleClass());
            }
        }
        if (projectStageLabel != null) {
            projectStageLabel.setText(projectOpen ? visibleRoute.label() : "");
        }
        if (projectStatusButton != null) {
            projectStatusButton.setDisable(!projectOpen);
            projectStatusButton.getStyleClass().removeAll(
                    "workflow-status-button-ready",
                    "workflow-status-button-warning",
                    "workflow-status-button-failed",
                    "workflow-status-button-attention");
            if (projectOpen) {
                String badge = journeyState.statusLabel().badgeStyleClass();
                if ("badge-ready".equals(badge)) {
                    projectStatusButton.getStyleClass().add("workflow-status-button-ready");
                } else if ("badge-warning".equals(badge)) {
                    projectStatusButton.getStyleClass().add("workflow-status-button-warning");
                } else if ("badge-invalid".equals(badge)) {
                    projectStatusButton.getStyleClass().add("workflow-status-button-failed");
                } else if ("badge-attention".equals(badge)) {
                    projectStatusButton.getStyleClass().add("workflow-status-button-attention");
                }
            }
            String tooltip = !projectOpen
                    ? WorkspaceChromeStatus.NEED_PROJECT
                    : (journeyState.readyWithWarnings()
                    ? "View processing warnings"
                    : journeyState.primaryActionLabel());
            projectStatusButton.setTooltip(new Tooltip(tooltip));
        }
        if (sidebarHealthValue != null) {
            sidebarHealthValue.setText(health);
        }

        boolean analysisAvailable = projectOpen && facts.analysisDataReady();
        boolean decodeApplicable = projectOpen && facts.decodeWorkspaceApplicable();
        setNavAvailable(navSources, true, projectOpen);
        setNavAvailable(navDecode, true, decodeApplicable);
        setNavAvailable(navSignals, true, analysisAvailable);
        setNavAvailable(navGraphs, true, analysisAvailable);
        setNavAvailable(navInvestigations, true, analysisAvailable);
        setNavAvailable(navConfiguration, true, true);
        setNavAvailable(navDashboard, true, true);
        setNavAvailable(navProjectsHome, false, false);
        setSectionVisible(setupSectionLabel, setupNavBox, true);
        setSectionVisible(analysisSectionLabel, analysisNavBox, true);
        if (projectNavBox != null) {
            projectNavBox.setVisible(true);
            projectNavBox.setManaged(true);
        }
        refreshNavTooltips();
    }

    private void setSectionVisible(Label heading, VBox box, boolean visible) {
        boolean showHeading = visible && !sidebarCollapsed;
        if (heading != null) {
            heading.setVisible(showHeading);
            heading.setManaged(showHeading);
        }
        if (box != null) {
            box.setVisible(visible);
            box.setManaged(visible);
        }
    }

    private static void setNavAvailable(Button button, boolean visible, boolean enabled) {
        if (button == null) {
            return;
        }
        button.setVisible(visible);
        button.setManaged(visible);
        button.setDisable(!enabled);
        button.getStyleClass().remove("sidebar-item-unavailable");
        if (visible && !enabled) {
            button.getStyleClass().add("sidebar-item-unavailable");
        }
    }

    /** Recompute sidebar availability and status chrome from current journey facts. */
    public void refreshProjectChrome() {
        refreshChrome();
    }

    public boolean decodeNavDisabledForTest() {
        return navDecode == null || navDecode.isDisabled();
    }

    /** Thin bottom status strip — real workspace / project state only. */
    public void refreshWorkspaceStatusBar() {
        boolean projectOpen = appService != null && appService.projectJourneyFacts().projectOpen();
        int saved = appService == null ? 0 : appService.savedProjectCount();
        String health = chromeHealth(projectOpen);
        if (statusWorkspaceLabel != null) {
            statusWorkspaceLabel.setText("Local Workspace");
        }
        if (statusDatabaseLabel != null) {
            statusDatabaseLabel.setText(WorkspaceChromeStatus.readiness(
                    projectOpen, saved, health, processingNow()));
        }
        if (statusProjectLabel != null) {
            String projectName = WorkspaceChromeStatus.projectName(
                    projectOpen, appService == null ? "" : appService.workspace().projectName());
            statusProjectLabel.setMinWidth(0);
            statusProjectLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            statusProjectLabel.setText(projectName);
            if (statusProjectLabel.getTooltip() == null) {
                statusProjectLabel.setTooltip(new Tooltip(projectName));
            } else {
                statusProjectLabel.getTooltip().setText(projectName);
            }
        }
        if (statusWorkflowLabel != null) {
            statusWorkflowLabel.setMinWidth(Region.USE_PREF_SIZE);
            statusWorkflowLabel.setText(health);
        }
    }

    private String chromeHealth(boolean projectOpen) {
        if (appService == null) {
            return WorkspaceChromeStatus.NO_ACTIVE_PROJECT;
        }
        return WorkspaceChromeStatus.chip(
                projectOpen, appService.projectHealthDisplay(), processingNow());
    }

    private boolean processingNow() {
        return appService != null && appService.compactProgress().running();
    }

    private void updateGlobalProcessingIndicator() {
        if (globalProcessingBox == null || appService == null) {
            return;
        }
        var progress = appService.compactProgress();
        boolean show = progress.running();
        globalProcessingBox.setVisible(show);
        globalProcessingBox.setManaged(show);
        if (!show) {
            return;
        }
        int pct = (int) Math.round(Math.max(0.0, Math.min(1.0, progress.progressFraction())) * 100.0);
        globalProcessingLabel.setText("Processing " + pct + "%");
        double p = progress.progressFraction();
        globalProcessingBar.setProgress(p <= 0.0 ? ProgressBar.INDETERMINATE_PROGRESS : p);
    }

    /** Navigate to the DBC Mapping workspace, which is subordinate to Decode. */
    public void openDbcMappingWorkspace() {
        openDbcMappingWorkspace(null);
    }

    /**
     * Navigate to the DBC Mapping workspace, optionally focusing a source-channel.
     */
    public void openDbcMappingWorkspace(com.stellar.platform.desktop.project.SourceChannelKey channel) {
        openRoute(WorkspaceRoute.DECODE);
        Object controller = controllers.get(DesktopViewHost.PROJECT_FILES);
        if (controller instanceof ProjectFilesController projectFiles) {
            projectFiles.openDbcMappingWorkspace(channel);
        }
    }

    private void installBrandMark() {
        if (brandMarkHost == null) {
            return;
        }
        brandMarkHost.getChildren().setAll(StellarIcons.brandMark());
    }

    /** Status chip replaced the six-step workflow control in the application header. */
    public boolean hasProjectStatusChipForTest() {
        return projectStatusButton != null
                && projectStatusButton.getStyleClass().contains("workflow-status-button");
    }

    public String projectStatusTextForTest() {
        return projectStatusLabel == null ? "" : projectStatusLabel.getText();
    }

    public String projectStageTextForTest() {
        return projectStageLabel == null ? "" : projectStageLabel.getText();
    }

    /** Sources workspace inside Project Files. */
    public void openSourcesWorkspace() {
        openRoute(WorkspaceRoute.SOURCES);
    }

    /**
     * Source Inventory is a subordinate workspace of Decode, not a navigation destination.
     */
    public void openSourceInventoryWorkspace() {
        openRoute(WorkspaceRoute.DECODE);
        Object controller = controllers.get(DesktopViewHost.PROJECT_FILES);
        if (controller instanceof ProjectFilesController projectFiles) {
            projectFiles.openSourceInventoryWorkspace();
        }
    }

    private void refreshHost(DesktopViewHost host) {
        Object controller = controllers.get(host);
        if (controller instanceof Refreshable r) {
            r.refresh();
        }
    }

    private void markNav(NavigationPage page) {
        setActive(navDashboard, page == NavigationPage.HOME);
        setActive(navProjectsHome, page == NavigationPage.PROJECTS_HOME);
        setActive(navSources, page == NavigationPage.SOURCES);
        setActive(navDecode, page == NavigationPage.DECODE);
        setActive(navSignals, page == NavigationPage.SIGNALS);
        setActive(navGraphs, page == NavigationPage.GRAPHS);
        setActive(navInvestigations, page == NavigationPage.INVESTIGATIONS);
        setActive(navConfiguration, page == NavigationPage.CONFIGURATION);
    }

    private static void setActive(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.getStyleClass().remove("sidebar-item-selected");
        button.getStyleClass().remove("active");
        if (active) {
            button.getStyleClass().add("sidebar-item-selected");
        }
    }

    private void installNavChrome() {
        navDashboard.setGraphic(NavIcons.dashboard());
        navProjectsHome.setGraphic(NavIcons.projects());
        navSources.setGraphic(NavIcons.projectFiles());
        navDecode.setGraphic(NavIcons.processing());
        navSignals.setGraphic(NavIcons.signals());
        navGraphs.setGraphic(NavIcons.diagnostics());
        if (navInvestigations != null) {
            navInvestigations.setGraphic(NavIcons.investigations());
        }
        if (navConfiguration != null) {
            navConfiguration.setGraphic(NavIcons.settings());
            navConfiguration.setAlignment(Pos.CENTER_LEFT);
            navConfiguration.setGraphicTextGap(10);
            navConfiguration.setFocusTraversable(true);
        }
        for (Button button : navItems()) {
            button.setAlignment(Pos.CENTER_LEFT);
            button.setGraphicTextGap(10);
            button.setFocusTraversable(true);
        }
        navCollapse.setAlignment(Pos.CENTER_LEFT);
        navCollapse.setGraphicTextGap(10);
        navCollapse.setFocusTraversable(true);
        refreshNavTooltips();
    }

    private void applySidebarCollapsed(boolean collapsed) {
        sidebarCollapsed = collapsed;
        sidebar.getStyleClass().removeAll("sidebar-collapsed", "sidebar-expanded");
        if (!sidebar.getStyleClass().contains("sidebar")) {
            sidebar.getStyleClass().add("sidebar");
        }
        sidebar.getStyleClass().add(collapsed ? "sidebar-collapsed" : "sidebar-expanded");
        double width = collapsed ? SIDEBAR_COLLAPSED_WIDTH : SIDEBAR_EXPANDED_WIDTH;
        sidebar.setMinWidth(width);
        sidebar.setPrefWidth(width);
        sidebar.setMaxWidth(width);
        ContentDisplay display = collapsed ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT;
        for (Button button : navItems()) {
            button.setContentDisplay(display);
        }
        if (navConfiguration != null) {
            navConfiguration.setContentDisplay(display);
        }
        navCollapse.setContentDisplay(display);
        for (Label heading : List.of(setupSectionLabel, analysisSectionLabel)) {
            if (heading == null) {
                continue;
            }
            heading.setVisible(!collapsed);
            heading.setManaged(!collapsed);
        }
        if (sidebarHealthCard != null) {
            sidebarHealthCard.setVisible(false);
            sidebarHealthCard.setManaged(false);
        }
        navCollapse.setText(collapsed ? "" : "Collapse");
        navCollapse.setGraphic(collapsed ? NavIcons.expand() : NavIcons.collapse());
        refreshNavTooltips();
    }

    private void refreshNavTooltips() {
        boolean projectOpen = appService != null && appService.projectJourneyFacts().projectOpen();
        ProjectJourneyFacts facts = appService == null ? null : appService.projectJourneyFacts();
        ProjectJourneyState journey = appService == null ? null : appService.projectJourneyState();
        boolean decodeApplicable = facts != null && facts.decodeWorkspaceApplicable();
        boolean analysisAvailable = facts != null && facts.analysisDataReady();
        String journeyAction = WorkspaceChromeStatus.gatedActionReason(
                projectOpen, journey == null ? null : journey.primaryActionLabel());
        installTip(navDashboard, NavigationPage.HOME, null);
        installTip(navProjectsHome, NavigationPage.PROJECTS_HOME, null);
        installTip(navSources, NavigationPage.SOURCES, projectOpen ? null : WorkspaceChromeStatus.NEED_PROJECT);
        installTip(navDecode, NavigationPage.DECODE, projectOpen && decodeApplicable ? null : journeyAction);
        installTip(navSignals, NavigationPage.SIGNALS, projectOpen && analysisAvailable ? null : journeyAction);
        installTip(navGraphs, NavigationPage.GRAPHS, projectOpen && analysisAvailable ? null : journeyAction);
        installTip(navInvestigations, NavigationPage.INVESTIGATIONS,
                projectOpen && analysisAvailable ? null : journeyAction);
        installTip(navConfiguration, NavigationPage.CONFIGURATION, null);
        Tooltip collapseTip = new Tooltip(sidebarCollapsed ? "Expand navigation" : "Collapse navigation");
        navCollapse.setTooltip(collapseTip);
    }

    private void installTip(Button button, NavigationPage page, String unavailable) {
        if (button == null) {
            return;
        }
        if (unavailable != null && !unavailable.isBlank() && button.isDisable()) {
            button.setTooltip(new Tooltip(sidebarCollapsed ? page.label() + " — " + unavailable : unavailable));
        } else if (sidebarCollapsed) {
            button.setTooltip(new Tooltip(page.label()));
        } else {
            button.setTooltip(null);
        }
    }

    private List<Button> navItems() {
        java.util.ArrayList<Button> items = new java.util.ArrayList<>();
        for (Button button : new Button[] {
                navDashboard, navProjectsHome,
                navSources, navDecode, navSignals, navGraphs, navInvestigations}) {
            if (button != null) {
                items.add(button);
            }
        }
        return items;
    }

    /** Bottom utility nav (always available; not gated by project journey). */
    private List<Button> utilityNavItems() {
        return navConfiguration == null ? List.of() : List.of(navConfiguration);
    }

    private void loadHost(DesktopViewHost host) throws IOException {
        FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource(host.resource()));
        Node node = loader.load();
        DesktopWindowLayout.stretchToHost(node);
        Object controller = loader.getController();
        if (controller instanceof AppServiceAware aware) {
            aware.setAppService(appService, this);
        }
        pages.put(host, node);
        controllers.put(host, controller);
    }

    private void ensureHostLoaded(DesktopViewHost host) {
        if (pages.containsKey(host)) return;
        try {
            loadHost(host);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load desktop page " + host, e);
        }
    }

    private void lifecycle(DesktopViewHost host, java.util.function.Consumer<DesktopPageLifecycle> action) {
        Object controller = controllers.get(host);
        if (controller instanceof DesktopPageLifecycle lifecycle) action.accept(lifecycle);
    }

    /**
     * Drops a previously shown analysis host so its scene graph can become unreachable.
     * Lightweight engineering state stays on {@link DesktopAppService}.
     */
    private void evictHost(DesktopViewHost host) {
        if (host == null || !host.releasesSceneGraphOnLeave()) {
            return;
        }
        Node node = pages.get(host);
        Object controller = controllers.get(host);
        DesktopSceneGraphRelease.log("evict-begin " + host, controller, node);
        DesktopSceneGraphRelease.hideOwnedPopups(node);
        DesktopSceneGraphRelease.releaseFocusFrom(node);
        if (controller instanceof DesktopPageLifecycle lifecycle) {
            lifecycle.dispose();
        } else if (controller instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
        // Skins / popups can steal focus back during dispose; release again while attached.
        DesktopSceneGraphRelease.hideOwnedPopups(node);
        DesktopSceneGraphRelease.releaseFocusFrom(node);
        pages.remove(host);
        controllers.remove(host);
        if (node != null) {
            contentHost.getChildren().remove(node);
        }
        DesktopSceneGraphRelease.log("evict-done " + host, controller, node);
    }

    /** Persist-and-release Signals/Graphs UI before the active project identity changes. */
    private void evictAnalysisHosts() {
        evictHost(DesktopViewHost.SIGNALS);
        evictHost(DesktopViewHost.GRAPHS);
    }

    public void dispose() {
        shellDisposed = true;
        projectSwitchGeneration.incrementAndGet();
        ProjectSwitchTrace.clearObserver(projectSwitchObserver);
        projectSwitchActive = false;
        if (appService != null && saveStateListener != null) {
            appService.removeSaveStateListener(saveStateListener);
            saveStateListener = null;
        }
        for (Object controller : List.copyOf(controllers.values())) {
            if (controller instanceof DesktopPageLifecycle lifecycle) lifecycle.dispose();
            else if (controller instanceof AutoCloseable closeable) {
                try { closeable.close(); } catch (Exception ignored) { }
            }
        }
        pages.clear();
        controllers.clear();
        contentHost.getChildren().clear();
    }

    int loadedPageCountForTest() { return pages.size(); }

    boolean pageLoadedForTest(NavigationPage page) { return pages.containsKey(DesktopViewHost.of(page)); }

    Node pageNodeForTest(NavigationPage page) {
        return pages.get(DesktopViewHost.of(page));
    }

    Object controllerForTest(NavigationPage page) {
        return controllers.get(DesktopViewHost.of(page));
    }

    Map<NavigationPage, Object> controllersForTest() {
        Map<NavigationPage, Object> byPage = new LinkedHashMap<>();
        for (NavigationPage page : NavigationPage.values()) {
            Object controller = controllers.get(DesktopViewHost.of(page));
            if (controller != null) {
                byPage.put(page, controller);
            }
        }
        return Map.copyOf(byPage);
    }

    void bindServiceForChromeTest(DesktopAppService service) {
        this.appService = service;
        refreshChrome();
    }

    boolean saveActionVisibleForTest() {
        return saveProjectButton != null && saveProjectButton.isVisible() && saveProjectButton.isManaged();
    }

    Button saveProjectButtonForTest() {
        return saveProjectButton;
    }

    HBox headerActionsForTest() {
        return headerActions;
    }

    List<Button> permanentNavButtonsForTest() {
        java.util.ArrayList<Button> items = new java.util.ArrayList<>();
        for (Button button : new Button[] {navSources, navDecode, navSignals, navGraphs, navInvestigations}) {
            if (button != null) {
                items.add(button);
            }
        }
        return items;
    }

    String statusReadinessForTest() {
        return statusDatabaseLabel == null ? "" : statusDatabaseLabel.getText();
    }

    String statusProjectForTest() {
        return statusProjectLabel == null ? "" : statusProjectLabel.getText();
    }

    String statusWorkflowForTest() {
        return statusWorkflowLabel == null ? "" : statusWorkflowLabel.getText();
    }
}
