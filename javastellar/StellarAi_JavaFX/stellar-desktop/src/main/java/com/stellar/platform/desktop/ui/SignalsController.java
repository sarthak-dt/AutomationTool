package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.AlignedSampleRow;
import com.stellar.platform.desktop.presentation.AnalysisMeasurementResolver;
import com.stellar.platform.desktop.presentation.AnalysisScope;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.MeasurementCatalogScope;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.ProcessingStartOptions;
import com.stellar.platform.desktop.presentation.ProjectProcessingContext;
import com.stellar.platform.desktop.presentation.SignalsProcessingContext;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.desktop.ui.signals.FocusModeOverlaySupport;
import com.stellar.platform.desktop.ui.signals.SignalBrowserPane;
import com.stellar.platform.desktop.ui.signals.SignalInspectorPane;
import com.stellar.platform.desktop.ui.signals.SignalsLayoutMetrics;
import com.stellar.platform.desktop.ui.signals.SignalsWorkspaceMode;
import com.stellar.platform.desktop.ui.signals.WorkspaceFocusMode;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.map.GpsSignalDiscoveryService;
import com.stellar.platform.processing.map.GpsSignalMapping;
import com.stellar.platform.processing.map.GpsSignalRole;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Thin Signals screen coordinator: Browser | Active Workspace | Inspector.
 */
public final class SignalsController implements AppServiceAware, Refreshable, DesktopPageLifecycle {

    private static final System.Logger LOG = System.getLogger(SignalsController.class.getName());

    @FXML
    private VBox signalsRoot;
    @FXML
    private HBox processingContextBar;
    @FXML
    private HBox contextMeasurementCard;
    @FXML
    private VBox contextSourceIconHost;
    @FXML
    private VBox contextDbcIconHost;
    @FXML
    private VBox contextChannelsIconHost;
    @FXML
    private Label contextSourceLabel;
    @FXML
    private Label contextDbcCaption;
    @FXML
    private Label contextDbcLabel;
    @FXML
    private Label contextChannelsLabel;
    @FXML
    private Label contextModeLabel;
    @FXML
    private Label contextModeBadge;
    @FXML
    private Label contextSignalCountLabel;
    @FXML
    private HBox contextActions;
    @FXML
    private VBox updateBanner;
    @FXML
    private Label updateBannerTitle;
    @FXML
    private Label updateBannerDetail;
    @FXML
    private ProgressBar updateProgressBar;
    @FXML
    private Button updateCancelButton;
    @FXML
    private Button updateDetailsButton;
    @FXML
    private Button updateRetryButton;
    @FXML
    private SignalBrowserPane signalBrowser;
    @FXML
    private ActiveSignalWorkspacePane workspacePane;
    @FXML
    private SignalInspectorPane inspectorPane;
    @FXML
    private HBox signalsWorkbench;
    @FXML
    private StackPane signalsWorkbenchHost;

    private DesktopAppService appService;
    private MainShellController shell;
    /** Retained for selection/layout tests that reflect into these fields. */
    private TableView<SignalCatalogEntry> catalogTable;
    private TextField searchField;
    private TableView<AlignedSampleRow> sampleTable;
    private Label selectedTitleLabel;
    private Label selectedSummaryLabel;
    private double restoredInspectorWidth = SignalsLayoutMetrics.INSPECTOR_PREF_WIDTH;
    private WorkspaceFocusMode focusMode = WorkspaceFocusMode.NORMAL;
    private boolean sidebarWasCollapsed;
    private BorderPane focusContentFrame;
    private BorderPane leftDrawer;
    private BorderPane rightDrawer;
    private Button leftEdgeButton;
    private Button rightEdgeButton;
    private Button paletteAddButton;
    private Label paletteSelectionLabel;
    private boolean disposed;
    private final EventHandler<KeyEvent> searchHotkey = e -> {
        if (e.getCode() == KeyCode.ESCAPE && focusMode == WorkspaceFocusMode.FOCUS) {
            if (isPaletteOpen() || isDetailsOpen()) {
                closeFocusDrawers();
                e.consume();
                return;
            }
        }
        if (e.isControlDown() && e.getCode() == KeyCode.F) {
            if (focusMode == WorkspaceFocusMode.FOCUS) {
                openSignalPalette();
            } else {
                signalBrowser.focusSearch();
            }
            e.consume();
        }
    };
    private Scene searchHotkeyScene;
    private final GpsSignalDiscoveryService gpsDiscovery = new GpsSignalDiscoveryService();
    static final String MAP_ROUTE_DISABLED_TIP = "Map Route requires GPS-capable measurement data.";
    private final ChangeListener<Scene> sceneHotkeyBinder = (obs, previous, scene) -> {
        DesktopSceneGraphRelease.log(
                "scene " + DesktopSceneGraphRelease.identity(previous) + " -> " + DesktopSceneGraphRelease.identity(scene),
                this,
                signalsRoot);
        attachSearchHotkey(scene);
    };

    @FXML
    private void initialize() {
        DesktopSceneGraphRelease.log("create", this, signalsRoot);
        installContextChrome();
        if (workspacePane != null && contextActions != null) {
            workspacePane.mountContextActions(contextActions);
        }
        installFocusOverlay();
        if (workspacePane != null) {
            workspacePane.setOnFocusModeChanged(this::applyFocusMode);
            workspacePane.setOnAddSignalsRequest(this::openSignalPalette);
            workspacePane.setOnSignalDetailsRequest(this::openSignalDetailsDrawer);
            workspacePane.setOnAnalyzeSelectedRequest(this::onAnalyzeSelected);
            workspacePane.setOnMapRouteRequest(this::onOpenMapRoute);
        }
        configureResponsiveLayout();
        if (signalsWorkbench != null) {
            signalsWorkbench.widthProperty().addListener((obs, previous, next) -> {
                if (next != null) {
                    applyWorkspaceMode(next.doubleValue());
                }
            });
        }
    }

    private void installContextChrome() {
        if (contextSourceIconHost != null) {
            contextSourceIconHost.getChildren().setAll(StellarIcons.file());
        }
        if (contextDbcIconHost != null) {
            contextDbcIconHost.getChildren().setAll(StellarIcons.database());
        }
        if (contextChannelsIconHost != null) {
            contextChannelsIconHost.getChildren().setAll(StellarIcons.channel());
        }
        if (contextMeasurementCard != null) {
            contextMeasurementCard.setFocusTraversable(true);
            contextMeasurementCard.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && signalBrowser != null) {
                    signalBrowser.showMeasurementChooser(contextMeasurementCard);
                }
            });
            contextMeasurementCard.setOnKeyPressed(event -> {
                if ((event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE)
                        && signalBrowser != null) {
                    signalBrowser.showMeasurementChooser(contextMeasurementCard);
                    event.consume();
                }
            });
            Tooltip.install(contextMeasurementCard, new Tooltip("Choose measurement"));
        }
    }

    @Override
    public void setAppService(DesktopAppService appService, MainShellController shell) {
        this.appService = appService;
        this.shell = shell;
        catalogTable = signalBrowser.catalogTable();
        searchField = signalBrowser.searchField();
        sampleTable = workspacePane.table();
        selectedTitleLabel = workspacePane.titleLabel();
        selectedSummaryLabel = new Label("");
        selectedSummaryLabel.setVisible(false);
        selectedSummaryLabel.setManaged(false);

        signalBrowser.setModel(appService.signalsModel());
        workspacePane.setModel(appService.signalsModel());
        inspectorPane.setModel(appService.signalsModel());
        workspacePane.setBrowserSelectionSupplier(
                () -> List.copyOf(catalogTable.getSelectionModel().getSelectedItems()));
        signalBrowser.setAddToWorkspaceListener(entries -> {
            workspacePane.addEntriesAfterPaint(entries);
            if (focusMode == WorkspaceFocusMode.FOCUS) {
                closePaletteDrawer();
            }
        });
        signalBrowser.setRemoveFromWorkspaceListener(entries -> {
            if (entries == null || entries.isEmpty()) {
                return;
            }
            workspacePane.removePhysicalKeysAfterPaint(
                    entries.stream().map(SignalCatalogEntry::key).toList());
        });
        signalBrowser.setOnSelectAll(this::selectAllFilteredSignals);
        signalBrowser.setOnDeselectAll(this::deselectAllFilteredSignals);
        signalBrowser.setOnCollapseRequested(this::toggleSignalBrowser);
        signalBrowser.setMeasurementScopeListener(this::onBrowserMeasurementScopeChanged);
        signalBrowser.setSelectionListener(selected -> {
            refreshPaletteSelectionLabel();
            if (!selected.isEmpty()) {
                // Focus for Inspector only — does not add to workspace.
                appService.signalsModel().setFocusedSignalKey(selected.getLast().key());
                workspacePane.reloadFocusedSignalIfNeeded();
                inspectorPane.refresh();
            }
        });
        workspacePane.setOnFocusChanged(inspectorPane::refresh);
        workspacePane.setOnWorkspaceChanged(() -> {
            workspacePane.refreshFocusPresentation();
            inspectorPane.refresh();
            signalBrowser.refreshFavoritePresentation();
            refreshPaletteSelectionLabel();
            refreshAnalyzeAvailability();
        });
        workspacePane.setOnFocusModeChanged(this::applyFocusMode);
        workspacePane.setOnAddSignalsRequest(this::openSignalPalette);
        workspacePane.setOnSignalDetailsRequest(this::openSignalDetailsDrawer);
        workspacePane.setOnAnalyzeSelectedRequest(this::onAnalyzeSelected);
        workspacePane.setOnMapRouteRequest(this::onOpenMapRoute);
        refreshAnalyzeAvailability();
        signalBrowser.setOnDisplayedChanged(this::refreshProcessingContext);
        inspectorPane.setCollapseListener(this::onInspectorCollapsed);

        signalBrowser.sceneProperty().addListener(sceneHotkeyBinder);
        attachSearchHotkey(signalBrowser.getScene());
    }

    @Override
    public void onShow() {
        if (disposed || signalBrowser == null) {
            return;
        }
        DesktopSceneGraphRelease.log("onShow", this, signalsRoot);
        attachSearchHotkey(signalBrowser.getScene());
    }

    @Override
    public void onHide() {
        DesktopSceneGraphRelease.log("onHide", this, signalsRoot);
        detachSearchHotkey();
        if (focusMode == WorkspaceFocusMode.FOCUS) {
            applyFocusMode(WorkspaceFocusMode.NORMAL);
        }
        if (signalBrowser != null) {
            signalBrowser.onHide();
        }
        if (workspacePane != null) {
            workspacePane.onHide();
        }
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        DesktopSceneGraphRelease.log("dispose", this, signalsRoot);
        if (appService != null) {
            appService.signalsModel().invalidatePageScopedLoads();
        }
        Node releaseRoot = signalsRoot != null ? signalsRoot : signalBrowser;
        DesktopSceneGraphRelease.hideOwnedPopups(releaseRoot);
        DesktopSceneGraphRelease.releaseFocusFrom(releaseRoot);
        onHide();
        if (signalBrowser != null) {
            signalBrowser.sceneProperty().removeListener(sceneHotkeyBinder);
            signalBrowser.dispose();
        }
        if (workspacePane != null) {
            workspacePane.dispose();
        }
        if (inspectorPane != null) {
            inspectorPane.dispose();
        }
        if (workspacePane != null) {
            workspacePane.setOnFocusModeChanged(null);
            workspacePane.setOnAddSignalsRequest(null);
            workspacePane.setOnSignalDetailsRequest(null);
            workspacePane.setOnAnalyzeSelectedRequest(null);
            workspacePane.setOnFocusChanged(null);
            workspacePane.setOnWorkspaceChanged(null);
            workspacePane.setBrowserSelectionSupplier(null);
        }
        if (signalBrowser != null) {
            signalBrowser.setAddToWorkspaceListener(null);
            signalBrowser.setRemoveFromWorkspaceListener(null);
            signalBrowser.setSelectionListener(null);
            signalBrowser.setOnDisplayedChanged(null);
        }
        if (inspectorPane != null) {
            inspectorPane.setCollapseListener(null);
        }
        appService = null;
        shell = null;
    }

    boolean disposedForTest() {
        return disposed;
    }

    VBox signalsRootForTest() {
        return signalsRoot;
    }

    SignalBrowserPane browserPaneForTest() {
        return signalBrowser;
    }

    ActiveSignalWorkspacePane workspacePaneForTest() {
        return workspacePane;
    }

    SignalInspectorPane inspectorPaneForTest() {
        return inspectorPane;
    }

    Scene searchHotkeySceneForTest() {
        return searchHotkeyScene;
    }

    private void attachSearchHotkey(Scene scene) {
        detachSearchHotkey();
        if (scene != null && !disposed) {
            searchHotkeyScene = scene;
            scene.addEventFilter(KeyEvent.KEY_PRESSED, searchHotkey);
        }
    }

    private void detachSearchHotkey() {
        if (searchHotkeyScene != null) {
            searchHotkeyScene.removeEventFilter(KeyEvent.KEY_PRESSED, searchHotkey);
            searchHotkeyScene = null;
        }
    }

    @FXML
    private void onCancelReprocess() {
        if (appService != null) {
            appService.cancelProcessing();
        }
    }

    @FXML
    private void onAnalyzeSelected() {
        if (appService == null || shell == null) {
            return;
        }
        // Analyze Selected must never enter or remain in Signals Focus Table.
        if (focusMode == WorkspaceFocusMode.FOCUS) {
            if (workspacePane != null) {
                workspacePane.setFocusMode(WorkspaceFocusMode.NORMAL);
            } else {
                applyFocusMode(WorkspaceFocusMode.NORMAL);
            }
        }
        if (appService.signalsModel().workspaceKeys().isEmpty() && catalogTable != null) {
            List<SignalCatalogEntry> selected = List.copyOf(catalogTable.getSelectionModel().getSelectedItems());
            if (!selected.isEmpty()) {
                appService.signalsModel().addToWorkspace(selected.stream().map(SignalCatalogEntry::key).toList());
            }
        }
        if (!bindAnalysisMeasurementFromSelection()) {
            refreshAnalyzeAvailability();
            return;
        }
        appService.markAnalyzeHandoff();
        shell.openRoute(WorkspaceRoute.GRAPHS);
    }

    /**
     * Map Route follows the Signals measurement chooser. It does not require Latitude or Longitude
     * in the workspace, and it does not rebind the analysis measurement from the selected signals.
     */
    private void onOpenMapRoute() {
        if (appService == null || shell == null || workspacePane == null || !workspacePane.mapRouteEnabledForTest()) {
            return;
        }
        MeasurementCatalogScope.Ref scope = signalBrowser == null ? null : signalBrowser.selectedMeasurementRef();
        if (scope == null || scope.allMeasurements()) {
            appService.selectAnalysisScope(AnalysisScope.allMeasurements(
                    appService.workspace().measurementFiles().size()));
        } else {
            appService.selectAnalysisScope(AnalysisScope.single(scope.identity(), scope.displayName()));
        }
        shell.openMapRoute();
    }

    private void onBrowserMeasurementScopeChanged(com.stellar.platform.desktop.presentation.MeasurementCatalogScope.Ref ref) {
        if (appService == null || ref == null) {
            refreshAnalyzeAvailability();
            return;
        }
        if (!ref.allMeasurements()) {
            selectWorkspaceMeasurement(ref.identity(), ref.displayName());
        }
        refreshProcessingContext();
        refreshAnalyzeAvailability();
    }

    private boolean bindAnalysisMeasurementFromSelection() {
        if (appService == null) {
            return false;
        }
        AnalysisMeasurementResolver.Resolution resolved = resolveAnalysisMeasurement();
        if (!resolved.canAnalyze()) {
            if (workspacePane != null) {
                workspacePane.showAnalyzeBlocked(resolved.explanation());
            }
            return false;
        }
        String current = appService.workspace().selectedMeasurement().map(WorkspaceFile::id).orElse("");
        if (!resolved.measurementId().equals(current)) {
            appService.selectMeasurement(resolved.measurementId());
        }
        List<SignalCatalogKey> requested = appService.signalsModel().workspaceKeys();
        List<SignalCatalogKey> physical = AnalysisMeasurementResolver.remapToCatalog(
                requested,
                appService.engineeringCatalog(resolved.context()));
        long distinct = requested.stream().filter(java.util.Objects::nonNull).distinct().count();
        if (physical.size() != distinct) {
            String reason = "These signals could not be resolved on "
                    + resolved.measurementFileName()
                    + ". Choose that measurement in the browser, then Analyze.";
            if (workspacePane != null) {
                workspacePane.showAnalyzeBlocked(reason);
                workspacePane.setAnalyzeEnabled(false, reason);
            }
            return false;
        }
        if (!physical.equals(requested)) {
            appService.signalsModel().clearWorkspace();
            appService.signalsModel().addToWorkspace(physical);
        }
        return true;
    }

    private AnalysisMeasurementResolver.Resolution resolveAnalysisMeasurement() {
        if (appService == null) {
            return new AnalysisMeasurementResolver.Resolution("", "", List.of(), false, true);
        }
        return AnalysisMeasurementResolver.resolve(
                appService.signalsModel().workspaceKeys(),
                appService.signalsModel().catalog().entries(),
                appService.workspace().selectedMeasurement(),
                appService.workspace().measurementFiles(),
                signalBrowser == null ? null : signalBrowser.selectedMeasurementRef(),
                appService.signalsModel().catalogStore()
        );
    }

    private void refreshAnalyzeAvailability() {
        if (workspacePane == null || appService == null) {
            return;
        }
        refreshMapRouteAvailability();
        if (appService.signalsModel().workspaceKeys().isEmpty()) {
            String reason = "Add at least one signal to the active workspace first. "
                    + "canAnalyze=false reason=EMPTY_WORKSPACE restoredSelected="
                    + appService.workspace().selectedMeasurement()
                    .map(file -> file.id() + "/" + file.fileName()).orElse("none")
                    + " identities=[] workspaceKeys=0";
            LOG.log(System.Logger.Level.INFO, reason);
            workspacePane.setAnalyzeEnabled(false, "Add at least one signal to the active workspace first.");
            return;
        }
        try {
            AnalysisMeasurementResolver.Resolution resolved = resolveAnalysisMeasurement();
            var ctx = resolved.canAnalyze() ? resolved.context() : appService.activeAnalysisMeasurement();
            List<SignalCatalogEntry> engineeringCatalog = appService.engineeringCatalog(ctx);
            int engineering = engineeringCatalog.size();
            boolean enabled = resolved.canAnalyze();
            String explanation = resolved.explanation();
            if (enabled) {
                List<SignalCatalogKey> requested = appService.signalsModel().workspaceKeys();
                int physical = AnalysisMeasurementResolver.remapToCatalog(requested, engineeringCatalog).size();
                long distinct = requested.stream().filter(java.util.Objects::nonNull).distinct().count();
                if (physical != distinct) {
                    enabled = false;
                    explanation = "These signals could not be resolved on the selected measurement. "
                            + "Choose one measurement in the browser, then Analyze. "
                            + explanation;
                }
            }
            final boolean analyzeEnabled = enabled;
            final String analyzeExplanation = explanation;
            LOG.log(System.Logger.Level.INFO, () -> analyzeExplanation
                    + " activeSourceId=" + ctx.sourceId()
                    + " activeFileName=" + ctx.fileName()
                    + " engineeringSize=" + engineering
                    + " catalogSize=" + appService.signalsModel().catalog().size());
            workspacePane.setAnalyzeEnabled(analyzeEnabled, analyzeExplanation);
        } catch (RuntimeException ex) {
            String reason = "canAnalyze=false reason=RESOLVE_FAILED "
                    + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
            LOG.log(System.Logger.Level.WARNING, reason, ex);
            workspacePane.setAnalyzeEnabled(false, reason);
        }
    }

    private void refreshMapRouteAvailability() {
        boolean enabled = false;
        try {
            enabled = gpsRouteResolvable();
        } catch (RuntimeException ex) {
            LOG.log(System.Logger.Level.WARNING, "Map Route availability failed: " + ex.getMessage(), ex);
        }
        workspacePane.setMapRouteEnabled(enabled, enabled
                ? "Open Map Route for the current measurement context."
                : MAP_ROUTE_DISABLED_TIP);
    }

    private boolean gpsRouteResolvable() {
        if (appService == null || appService.workspace().measurementFiles().isEmpty()) {
            return false;
        }
        List<SignalCatalogEntry> catalog = mapCatalogForSignalsScope();
        if (catalog.isEmpty()) {
            return false;
        }
        if (gpsDiscovery.discover(catalog).hasReliableRoute()) {
            return true;
        }
        return persistedGpsPair(catalog);
    }

    private List<SignalCatalogEntry> mapCatalogForSignalsScope() {
        var model = appService.signalsModel();
        MeasurementCatalogScope.Ref scope = signalBrowser == null ? null : signalBrowser.selectedMeasurementRef();
        if (model.catalogStore() instanceof ProjectMergedSignalStore merged) {
            if (scope != null && !scope.allMeasurements()) {
                return merged.physicalCatalog(scope.identity(), scope.displayName());
            }
            return merged.physicalCatalogAll();
        }
        List<SignalCatalogEntry> entries = model.catalog().entries();
        if (scope != null && !scope.allMeasurements()) {
            return MeasurementCatalogScope.filter(entries, scope);
        }
        return entries;
    }

    private boolean persistedGpsPair(List<SignalCatalogEntry> catalog) {
        MeasurementCatalogScope.Ref scope = signalBrowser == null ? null : signalBrowser.selectedMeasurementRef();
        boolean latitude = false;
        boolean longitude = false;
        for (GpsSignalMapping mapping : appService.loadAllGpsSignalMappings()) {
            if (mapping.role() != GpsSignalRole.LATITUDE && mapping.role() != GpsSignalRole.LONGITUDE) {
                continue;
            }
            if (scope != null && !scope.allMeasurements() && !mappingMatchesScope(mapping, scope)) {
                continue;
            }
            if (mapping.role() == GpsSignalRole.LATITUDE) {
                latitude = true;
            } else {
                longitude = true;
            }
        }
        return latitude && longitude && !catalog.isEmpty();
    }

    private static boolean mappingMatchesScope(GpsSignalMapping mapping, MeasurementCatalogScope.Ref scope) {
        String source = mapping.sourceId() == null ? "" : mapping.sourceId();
        if (source.isBlank()) {
            return true;
        }
        return source.equalsIgnoreCase(scope.identity()) || source.equalsIgnoreCase(scope.displayName());
    }

    private void selectWorkspaceMeasurement(String identity, String displayName) {
        if (appService == null) {
            return;
        }
        for (WorkspaceFile file : appService.workspace().measurementFiles()) {
            if ((identity != null && identity.equalsIgnoreCase(file.id()))
                    || (displayName != null && displayName.equalsIgnoreCase(file.fileName()))) {
                String current = appService.workspace().selectedMeasurement().map(WorkspaceFile::id).orElse("");
                if (!file.id().equals(current)) {
                    appService.selectMeasurement(file.id());
                }
                return;
            }
        }
    }

    @FXML
    private void onViewProcessingDetails() {
        if (shell != null) {
            shell.openRoute(WorkspaceRoute.PROCESSING_RESULT);
        }
    }

    @FXML
    private void onRetryReprocess() {
        if (appService == null || shell == null) {
            return;
        }
        appService.startLocalProcessing(
                Platform::runLater,
                shell::onProcessingUiTick,
                ProcessingStartOptions.reprocessFromSignals()
        );
        refreshUpdateBanner();
    }

    private void onInspectorCollapsed(boolean collapsed) {
        if (inspectorPane == null || focusMode == WorkspaceFocusMode.FOCUS) {
            return;
        }
        if (!suppressDetailsPin) {
            detailsExplicit = true;
        }
        if (collapsed) {
            restoredInspectorWidth = Math.max(
                    SignalsLayoutMetrics.INSPECTOR_MIN_WIDTH,
                    inspectorPane.getWidth() > 40 ? inspectorPane.getWidth() : inspectorPane.getPrefWidth());
            inspectorPane.setMinWidth(SignalsLayoutMetrics.DETAILS_RAIL_WIDTH);
            inspectorPane.setPrefWidth(SignalsLayoutMetrics.DETAILS_RAIL_WIDTH);
            inspectorPane.setMaxWidth(SignalsLayoutMetrics.DETAILS_RAIL_WIDTH);
        } else {
            double w = restoredInspectorWidth > 40
                    ? restoredInspectorWidth
                    : SignalsLayoutMetrics.INSPECTOR_PREF_WIDTH;
            inspectorPane.setMinWidth(SignalsLayoutMetrics.INSPECTOR_MIN_WIDTH);
            inspectorPane.setPrefWidth(w);
            inspectorPane.setMaxWidth(SignalsLayoutMetrics.INSPECTOR_MAX_WIDTH);
        }
    }

    private void installFocusOverlay() {
        if (signalsWorkbenchHost == null || signalsWorkbench == null) {
            return;
        }
        // Base layer: BorderPane so edge affordances reserve width instead of
        // covering workspace content (which previously clipped the title/table).
        focusContentFrame = new BorderPane();
        focusContentFrame.getStyleClass().add("signals-focus-frame");
        focusContentFrame.setMinWidth(0);
        focusContentFrame.setMinHeight(0);
        focusContentFrame.setMaxWidth(Double.MAX_VALUE);
        focusContentFrame.setMaxHeight(Double.MAX_VALUE);
        signalsWorkbenchHost.getChildren().remove(signalsWorkbench);
        focusContentFrame.setCenter(signalsWorkbench);
        signalsWorkbenchHost.getChildren().add(focusContentFrame);
        StackPane.setAlignment(focusContentFrame, Pos.TOP_LEFT);

        leftDrawer = FocusModeOverlaySupport.createLeftDrawer("Add Signals", this::closePaletteDrawer);
        rightDrawer = FocusModeOverlaySupport.createRightDrawer("Signal Details", this::closeDetailsDrawer);

        paletteSelectionLabel = new Label("Selected: 0");
        paletteSelectionLabel.getStyleClass().add("muted");
        paletteAddButton = new Button("Add Selected");
        paletteAddButton.getStyleClass().addAll("primary-button", "compact-button");
        paletteAddButton.setMinHeight(SignalsLayoutMetrics.CONTROL_HEIGHT);
        paletteAddButton.setPrefHeight(SignalsLayoutMetrics.CONTROL_HEIGHT);
        paletteAddButton.setOnAction(e -> addPaletteSelection());
        HBox paletteFooter = new HBox(10, paletteSelectionLabel, new Region(), paletteAddButton);
        HBox.setHgrow(paletteFooter.getChildren().get(1), Priority.ALWAYS);
        paletteFooter.setAlignment(Pos.CENTER_LEFT);
        paletteFooter.setPadding(new javafx.geometry.Insets(8, 10, 10, 10));
        leftDrawer.setBottom(paletteFooter);

        leftEdgeButton = FocusModeOverlaySupport.createEdgeButton("+", "Add Signals", "focus-edge-left");
        rightEdgeButton = FocusModeOverlaySupport.createEdgeButton("i", "Signal Details", "focus-edge-right");
        leftEdgeButton.setOnAction(e -> openSignalPalette());
        rightEdgeButton.setOnAction(e -> openSignalDetailsDrawer());

        // Drawers are true overlays; edge buttons live in the BorderPane frame.
        FocusModeOverlaySupport.alignOverlay(signalsWorkbenchHost, leftDrawer, Pos.CENTER_LEFT);
        FocusModeOverlaySupport.alignOverlay(signalsWorkbenchHost, rightDrawer, Pos.CENTER_RIGHT);
        focusContentFrame.toBack();
    }

    /**
     * Advanced Focus Mode is presentation-only: side panes hide, table expands,
     * selected signals / paging / display state are preserved.
     */
    private void applyFocusMode(WorkspaceFocusMode mode) {
        WorkspaceFocusMode next = mode == null ? WorkspaceFocusMode.NORMAL : mode;
        if (focusMode == next) {
            syncFocusChrome();
            return;
        }
        focusMode = next;
        if (focusMode == WorkspaceFocusMode.FOCUS) {
            enterFocusMode();
        } else {
            exitFocusMode();
        }
        syncFocusChrome();
    }

    private void enterFocusMode() {
        closeFocusDrawers();
        if (shell != null) {
            sidebarWasCollapsed = shell.isSidebarCollapsed();
            shell.setSidebarCollapsed(true);
        }
        if (inspectorPane != null && inspectorPane.isCollapsed()) {
            inspectorPane.setCollapsed(false);
        }
        parkSidePanes();
        if (signalsWorkbench != null) {
            signalsWorkbench.getStyleClass().add("signals-workbench-focus");
            // Center must be allowed to shrink so the edge slots keep their width.
            signalsWorkbench.setMinWidth(0);
        }
    }

    private void exitFocusMode() {
        closeFocusDrawers();
        restoreSidePanes();
        if (shell != null) {
            shell.setSidebarCollapsed(sidebarWasCollapsed);
        }
        if (signalsWorkbench != null) {
            signalsWorkbench.getStyleClass().remove("signals-workbench-focus");
            signalsWorkbench.setMinWidth(Region.USE_COMPUTED_SIZE);
        }
        configureResponsiveLayout();
    }

    private void parkSidePanes() {
        if (signalsWorkbench == null) {
            return;
        }
        signalsWorkbench.getChildren().remove(signalBrowser);
        signalsWorkbench.getChildren().remove(inspectorPane);
        FocusModeOverlaySupport.setDrawerContent(leftDrawer, signalBrowser);
        FocusModeOverlaySupport.setDrawerContent(rightDrawer, inspectorPane);
        prepareBrowserForDrawer(false);
        prepareInspectorForDrawer(false);
        FocusModeOverlaySupport.showDrawer(leftDrawer, false);
        FocusModeOverlaySupport.showDrawer(rightDrawer, false);
        if (!signalsWorkbench.getChildren().contains(workspacePane)) {
            signalsWorkbench.getChildren().add(workspacePane);
        }
        HBox.setHgrow(workspacePane, Priority.ALWAYS);
        workspacePane.setMaxWidth(Double.MAX_VALUE);
    }

    private void restoreSidePanes() {
        FocusModeOverlaySupport.showDrawer(leftDrawer, false);
        FocusModeOverlaySupport.showDrawer(rightDrawer, false);
        leftDrawer.setCenter(null);
        rightDrawer.setCenter(null);
        if (signalsWorkbench == null) {
            return;
        }
        signalsWorkbench.getChildren().setAll(signalBrowser, workspacePane, inspectorPane);
        prepareBrowserForWorkbench();
        prepareInspectorForWorkbench();
        HBox.setHgrow(workspacePane, Priority.ALWAYS);
        applySignalBrowserCollapsed();
    }

    private boolean signalBrowserCollapsed;
    private VBox signalBrowserRail;
    private SignalsWorkspaceMode workspaceMode = SignalsWorkspaceMode.WIDE;
    /** Set only when the engineer opens or closes Signal Details. Auto policy applies until then. */
    private boolean detailsExplicit;
    private boolean suppressDetailsPin;

    private void toggleSignalBrowser() {
        signalBrowserCollapsed = !signalBrowserCollapsed;
        applySignalBrowserCollapsed();
    }

    /**
     * Layout-only. The browser node is parked, not rebuilt, so catalog rows and samples stay put.
     */
    private void applySignalBrowserCollapsed() {
        if (signalsWorkbench == null || signalBrowser == null || workspacePane == null) {
            return;
        }
        if (focusMode == WorkspaceFocusMode.FOCUS) {
            return;
        }
        if (signalBrowserCollapsed) {
            ensureSignalBrowserRail();
            signalsWorkbench.getChildren().remove(signalBrowser);
            if (!signalsWorkbench.getChildren().contains(signalBrowserRail)) {
                signalsWorkbench.getChildren().add(0, signalBrowserRail);
            }
        } else if (signalBrowserRail != null) {
            signalsWorkbench.getChildren().remove(signalBrowserRail);
            if (!signalsWorkbench.getChildren().contains(signalBrowser)) {
                int workspaceIndex = signalsWorkbench.getChildren().indexOf(workspacePane);
                int index = workspaceIndex < 0 ? 0 : workspaceIndex;
                signalsWorkbench.getChildren().add(index, signalBrowser);
            }
        }
        HBox.setHgrow(workspacePane, Priority.ALWAYS);
        workspacePane.setMaxWidth(Double.MAX_VALUE);
    }

    private void ensureSignalBrowserRail() {
        if (signalBrowserRail != null) {
            return;
        }
        Button expand = new Button("\u203A");
        expand.getStyleClass().addAll("secondary-button", "compact-button", "signal-panel-collapse-button");
        expand.setTooltip(new Tooltip("Expand signal browser"));
        expand.setOnAction(e -> toggleSignalBrowser());
        signalBrowserRail = new VBox(expand);
        signalBrowserRail.getStyleClass().addAll("signal-panel", "signal-panel-collapsed", "signal-panel-rail");
        signalBrowserRail.setAlignment(Pos.TOP_CENTER);
        signalBrowserRail.setMinWidth(36);
        signalBrowserRail.setPrefWidth(36);
        signalBrowserRail.setMaxWidth(36);
    }

    public void collapseSignalBrowserForTest() {
        signalBrowserCollapsed = true;
        applySignalBrowserCollapsed();
    }

    public void expandSignalBrowserForTest() {
        signalBrowserCollapsed = false;
        applySignalBrowserCollapsed();
    }

    public boolean signalBrowserCollapsedForTest() {
        return signalBrowserCollapsed;
    }

    public boolean signalBrowserAttachedForTest() {
        return signalsWorkbench != null && signalsWorkbench.getChildren().contains(signalBrowser);
    }

    public SignalBrowserPane browserForCollapseTest() {
        return signalBrowser;
    }

    public ActiveSignalWorkspacePane workspaceForCollapseTest() {
        return workspacePane;
    }

    public HBox workbenchForCollapseTest() {
        return signalsWorkbench;
    }

    private void selectAllFilteredSignals() {
        if (signalBrowser == null || workspacePane == null) {
            return;
        }
        List<SignalCatalogKey> keys = signalBrowser.filteredEntries().stream()
                .map(SignalCatalogEntry::key)
                .toList();
        workspacePane.addPhysicalKeysAfterPaint(keys);
    }

    private void deselectAllFilteredSignals() {
        if (signalBrowser == null || workspacePane == null) {
            return;
        }
        List<SignalCatalogKey> keys = signalBrowser.filteredEntries().stream()
                .map(SignalCatalogEntry::key)
                .toList();
        workspacePane.removePhysicalKeysAfterPaint(keys);
    }

    private void openSignalPalette() {
        if (focusMode != WorkspaceFocusMode.FOCUS) {
            return;
        }
        closeDetailsDrawer();
        FocusModeOverlaySupport.setDrawerContent(leftDrawer, signalBrowser);
        prepareBrowserForDrawer(true);
        FocusModeOverlaySupport.showDrawer(leftDrawer, true);
        refreshPaletteSelectionLabel();
        signalBrowser.focusSearch();
        syncFocusChrome();
    }

    private void openSignalDetailsDrawer() {
        if (focusMode != WorkspaceFocusMode.FOCUS) {
            return;
        }
        closePaletteDrawer();
        FocusModeOverlaySupport.setDrawerContent(rightDrawer, inspectorPane);
        prepareInspectorForDrawer(true);
        inspectorPane.refresh();
        FocusModeOverlaySupport.showDrawer(rightDrawer, true);
        syncFocusChrome();
    }

    private void closePaletteDrawer() {
        FocusModeOverlaySupport.showDrawer(leftDrawer, false);
        prepareBrowserForDrawer(false);
        syncFocusChrome();
    }

    private void closeDetailsDrawer() {
        FocusModeOverlaySupport.showDrawer(rightDrawer, false);
        prepareInspectorForDrawer(false);
        syncFocusChrome();
    }

    private void closeFocusDrawers() {
        closePaletteDrawer();
        closeDetailsDrawer();
    }

    private void addPaletteSelection() {
        if (catalogTable == null) {
            return;
        }
        List<SignalCatalogEntry> selected = List.copyOf(catalogTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            return;
        }
        workspacePane.addEntries(selected);
        inspectorPane.refresh();
        signalBrowser.refreshFavoritePresentation();
        closePaletteDrawer();
    }

    private void refreshPaletteSelectionLabel() {
        if (paletteSelectionLabel == null || catalogTable == null) {
            return;
        }
        int n = catalogTable.getSelectionModel().getSelectedItems().size();
        paletteSelectionLabel.setText("Selected: " + n);
        if (paletteAddButton != null) {
            paletteAddButton.setText(n == 1 ? "Add 1 Signal" : "Add " + n + " Signals");
            paletteAddButton.setDisable(n == 0);
        }
    }

    private void prepareBrowserForDrawer(boolean visible) {
        if (signalBrowser == null) {
            return;
        }
        signalBrowser.setVisible(visible);
        signalBrowser.setManaged(visible);
        signalBrowser.setMinWidth(SignalsLayoutMetrics.FOCUS_PALETTE_WIDTH - 2);
        signalBrowser.setPrefWidth(SignalsLayoutMetrics.FOCUS_PALETTE_WIDTH - 2);
        signalBrowser.setMaxWidth(SignalsLayoutMetrics.FOCUS_PALETTE_WIDTH - 2);
        signalBrowser.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(signalBrowser, Priority.ALWAYS);
    }

    private void prepareInspectorForDrawer(boolean visible) {
        if (inspectorPane == null) {
            return;
        }
        if (inspectorPane.isCollapsed()) {
            inspectorPane.setCollapsed(false);
        }
        inspectorPane.setVisible(visible);
        inspectorPane.setManaged(visible);
        inspectorPane.setMinWidth(SignalsLayoutMetrics.FOCUS_DETAILS_WIDTH - 2);
        inspectorPane.setPrefWidth(SignalsLayoutMetrics.FOCUS_DETAILS_WIDTH - 2);
        inspectorPane.setMaxWidth(SignalsLayoutMetrics.FOCUS_DETAILS_WIDTH - 2);
        inspectorPane.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(inspectorPane, Priority.ALWAYS);
    }

    private void prepareBrowserForWorkbench() {
        signalBrowser.setVisible(true);
        signalBrowser.setManaged(true);
        applyBrowserChrome(workspaceMode);
        HBox.setHgrow(signalBrowser, Priority.NEVER);
    }

    private void applyBrowserChrome(SignalsWorkspaceMode mode) {
        if (signalBrowser == null) {
            return;
        }
        double min = SignalsLayoutMetrics.BROWSER_MIN_WIDTH;
        double pref = SignalsLayoutMetrics.BROWSER_PREF_WIDTH;
        double max = SignalsLayoutMetrics.BROWSER_MAX_WIDTH;
        if (mode == SignalsWorkspaceMode.STANDARD) {
            min = SignalsLayoutMetrics.BROWSER_STANDARD_MIN_WIDTH;
            pref = SignalsLayoutMetrics.BROWSER_STANDARD_PREF_WIDTH;
            max = SignalsLayoutMetrics.BROWSER_STANDARD_MAX_WIDTH;
        } else if (mode == SignalsWorkspaceMode.COMPACT) {
            min = SignalsLayoutMetrics.BROWSER_COMPACT_MIN_WIDTH;
            pref = SignalsLayoutMetrics.BROWSER_COMPACT_PREF_WIDTH;
            max = SignalsLayoutMetrics.BROWSER_COMPACT_MAX_WIDTH;
        }
        signalBrowser.setMinWidth(min);
        signalBrowser.setPrefWidth(pref);
        signalBrowser.setMaxWidth(max);
    }

    private void prepareInspectorForWorkbench() {
        inspectorPane.setVisible(true);
        inspectorPane.setManaged(true);
        inspectorPane.setMinWidth(SignalsLayoutMetrics.INSPECTOR_MIN_WIDTH);
        inspectorPane.setPrefWidth(SignalsLayoutMetrics.INSPECTOR_PREF_WIDTH);
        inspectorPane.setMaxWidth(SignalsLayoutMetrics.INSPECTOR_MAX_WIDTH);
        HBox.setHgrow(inspectorPane, Priority.NEVER);
    }

    private boolean isPaletteOpen() {
        return leftDrawer != null && leftDrawer.isVisible();
    }

    private boolean isDetailsOpen() {
        return rightDrawer != null && rightDrawer.isVisible();
    }

    private void syncFocusChrome() {
        boolean focus = focusMode == WorkspaceFocusMode.FOCUS;
        boolean palette = isPaletteOpen();
        boolean details = isDetailsOpen();
        if (processingContextBar != null) {
            processingContextBar.getStyleClass().remove("signals-context-bar-focus");
            if (focus) {
                processingContextBar.getStyleClass().add("signals-context-bar-focus");
                processingContextBar.setMinHeight(44);
                processingContextBar.setPrefHeight(46);
                processingContextBar.setMaxHeight(48);
            } else {
                processingContextBar.setMinHeight(44);
                processingContextBar.setPrefHeight(48);
                processingContextBar.setMaxHeight(52);
            }
        }
        if (focusContentFrame != null) {
            if (focus && leftEdgeButton != null && !palette) {
                leftEdgeButton.setVisible(true);
                leftEdgeButton.setManaged(true);
                focusContentFrame.setLeft(leftEdgeButton);
            } else if (focusContentFrame.getLeft() != null) {
                focusContentFrame.setLeft(null);
                if (leftEdgeButton != null) {
                    leftEdgeButton.setVisible(false);
                    leftEdgeButton.setManaged(false);
                }
            }
            if (focus && rightEdgeButton != null && !details) {
                rightEdgeButton.setVisible(true);
                rightEdgeButton.setManaged(true);
                focusContentFrame.setRight(rightEdgeButton);
            } else if (focusContentFrame.getRight() != null) {
                focusContentFrame.setRight(null);
                if (rightEdgeButton != null) {
                    rightEdgeButton.setVisible(false);
                    rightEdgeButton.setManaged(false);
                }
            }
        }
        if (leftDrawer != null && leftDrawer.isVisible()) {
            leftDrawer.toFront();
        }
        if (rightDrawer != null && rightDrawer.isVisible()) {
            rightDrawer.toFront();
        }
        if (focusContentFrame != null) {
            focusContentFrame.toBack();
        }
    }

    WorkspaceFocusMode focusMode() {
        return focusMode;
    }

    boolean isFocusPaletteOpen() {
        return isPaletteOpen();
    }

    boolean isFocusDetailsOpen() {
        return isDetailsOpen();
    }

    private void configureResponsiveLayout() {
        if (signalsWorkbenchHost != null) {
            VBox.setVgrow(signalsWorkbenchHost, Priority.ALWAYS);
            signalsWorkbenchHost.setMaxWidth(Double.MAX_VALUE);
            signalsWorkbenchHost.setMaxHeight(Double.MAX_VALUE);
        }
        if (focusContentFrame != null) {
            focusContentFrame.setMaxWidth(Double.MAX_VALUE);
            focusContentFrame.setMaxHeight(Double.MAX_VALUE);
            StackPane.setAlignment(focusContentFrame, Pos.TOP_LEFT);
        }
        if (focusMode == WorkspaceFocusMode.FOCUS) {
            if (workspacePane != null) {
                workspacePane.setMinWidth(SignalsLayoutMetrics.WORKSPACE_MIN_WIDTH);
                workspacePane.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(workspacePane, Priority.ALWAYS);
            }
            if (signalsWorkbench != null) {
                signalsWorkbench.setFillHeight(true);
                for (var child : signalsWorkbench.getChildren()) {
                    if (child instanceof Region region) {
                        region.setMinHeight(0);
                        region.setMaxHeight(Double.MAX_VALUE);
                    }
                }
            }
            syncFocusChrome();
            return;
        }
        if (signalBrowser != null) {
            prepareBrowserForWorkbench();
        }
        if (workspacePane != null) {
            workspacePane.setMinWidth(SignalsLayoutMetrics.WORKSPACE_MIN_WIDTH);
            workspacePane.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(workspacePane, Priority.ALWAYS);
        }
        if (inspectorPane != null && !inspectorPane.isCollapsed()) {
            prepareInspectorForWorkbench();
        }
        if (signalsWorkbench != null) {
            signalsWorkbench.setFillHeight(true);
            for (var child : signalsWorkbench.getChildren()) {
                if (child instanceof Region region) {
                    region.setMinHeight(0);
                    region.setMaxHeight(Double.MAX_VALUE);
                }
            }
        }
    }

    /**
     * Chooses wide, standard, or compact from the workbench's available width.
     * Empty Signal Details collapses below the wide breakpoint unless the engineer opened it.
     * Layout only: no catalog or sample reload.
     */
    private void applyWorkspaceMode(double contentWidth) {
        if (contentWidth < 1 || focusMode == WorkspaceFocusMode.FOCUS) {
            return;
        }
        SignalsWorkspaceMode mode = SignalsWorkspaceMode.forContentWidth(contentWidth);
        workspaceMode = mode;
        if (!signalBrowserCollapsed) {
            applyBrowserChrome(mode);
        }
        if (workspacePane != null) {
            boolean wide = mode == SignalsWorkspaceMode.WIDE;
            workspacePane.setMinWidth(wide ? SignalsLayoutMetrics.WORKSPACE_MIN_WIDTH : 0);
            workspacePane.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(workspacePane, Priority.ALWAYS);
        }
        if (inspectorPane == null || detailsExplicit) {
            return;
        }
        boolean showDetails = mode == SignalsWorkspaceMode.WIDE;
        if (inspectorPane.isCollapsed() == !showDetails) {
            return;
        }
        suppressDetailsPin = true;
        try {
            inspectorPane.setCollapsed(!showDetails);
        } finally {
            suppressDetailsPin = false;
        }
    }

    public SignalsWorkspaceMode workspaceModeForTest() {
        return workspaceMode;
    }

    public void applyWorkspaceWidthForTest(double contentWidth) {
        applyWorkspaceMode(contentWidth);
    }

    /** Invoked by selection tests that still call onSearch reflectively. */
    private void onSearch() {
        signalBrowser.runFilterNow();
    }

    /**
     * Header search forwards to the already-loaded Signals catalog filter.
     * Does not open Signals, hydrate files, or parse measurement data.
     */
    void applyHeaderSearch(String query) {
        if (searchField == null) {
            return;
        }
        String text = query == null ? "" : query;
        if (!text.equals(searchField.getText())) {
            searchField.setText(text);
        }
    }

    @Override
    public void refresh() {
        if (disposed || appService == null) {
            return;
        }
        refreshUpdateBanner();
        signalBrowser.refreshFromModel();
        com.stellar.platform.desktop.presentation.MeasurementCatalogScope.Ref scope =
                signalBrowser.selectedMeasurementRef();
        if (scope != null && !scope.allMeasurements()) {
            selectWorkspaceMeasurement(scope.identity(), scope.displayName());
        }
        refreshProcessingContext();
        // Avoid stomping the workspace table while a preserve-reprocess is running.
        ProjectProcessingContext.CompactProgressSnapshot progress =
                appService == null ? null : appService.compactProgress();
        boolean freezeWorkspace = progress != null && progress.running() && progress.showUpdateBanner();
        if (!freezeWorkspace) {
            workspacePane.reload(false);
        }
        inspectorPane.refresh();
        refreshAnalyzeAvailability();
    }

    private void refreshProcessingContext() {
        if (appService == null) {
            return;
        }
        SignalsProcessingContext ctx = appService.signalsModel().processingContext();
        com.stellar.platform.desktop.presentation.MeasurementCatalogScope.Ref scope =
                signalBrowser == null
                        ? com.stellar.platform.desktop.presentation.MeasurementCatalogScope.Ref.all(0)
                        : signalBrowser.selectedMeasurementRef();
        boolean scoped = scope != null && !scope.allMeasurements();
        com.stellar.platform.desktop.presentation.AppliedDbcPresentation.Summary applied =
                appliedDbcForScope(scope);
        String source = scoped
                ? scope.comboLabel()
                : (ctx.sourceFile().isBlank() ? "\u2014" : ctx.sourceFile());
        String dbc = applied.value().isBlank() ? "\u2014" : applied.value();
        String channels = applied.jobProvenance()
                ? applied.channelsLabel()
                : (ctx.channelsSummary() == null || ctx.channelsSummary().isBlank()
                ? "\u2014"
                : ctx.channelsSummary());

        setTruncated(contextSourceLabel, source);
        if (contextDbcCaption != null) {
            contextDbcCaption.setText(applied.caption());
        }
        setTruncated(contextDbcLabel, dbc, applied.detail());
        setTruncated(contextChannelsLabel, channels);
        if (contextModeLabel != null) {
            contextModeLabel.setText("Mode: " + ctx.mode());
        }
        if (contextModeBadge != null) {
            String mode = ctx.mode() == null || ctx.mode().isBlank() ? "LOCAL" : ctx.mode();
            contextModeBadge.setText(mode);
            contextModeBadge.setWrapText(false);
            contextModeBadge.setTextOverrun(OverrunStyle.CLIP);
            contextModeBadge.setEllipsisString("");
            contextModeBadge.setMinWidth(Region.USE_PREF_SIZE);
            contextModeBadge.setTooltip(new Tooltip(mode));
        }
        int catalogSize = appService.signalsModel().catalog().size();
        if (contextSignalCountLabel != null) {
            String countText = signalBrowser == null
                    ? catalogSize + " signals"
                    : signalBrowser.countTextForTest();
            contextSignalCountLabel.setText(countText);
            contextSignalCountLabel.setTooltip(new Tooltip(countText));
        }
        if (processingContextBar != null) {
            processingContextBar.setVisible(true);
            processingContextBar.setManaged(true);
        }
        workspacePane.updateAnalysisContext(ctx, catalogSize);
    }

    private static void setTruncated(Label label, String fullText) {
        setTruncated(label, fullText, fullText);
    }

    private static void setTruncated(Label label, String fullText, String tooltip) {
        if (label == null) {
            return;
        }
        label.setText(fullText);
        label.setMinWidth(0);
        label.setPrefWidth(0);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setWrapText(false);
        label.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        String tip = tooltip == null || tooltip.isBlank() ? fullText : tooltip;
        label.setTooltip(new Tooltip(tip));
    }

    private com.stellar.platform.desktop.presentation.AppliedDbcPresentation.Summary appliedDbcForScope(
            com.stellar.platform.desktop.presentation.MeasurementCatalogScope.Ref scope
    ) {
        boolean scoped = scope != null && !scope.allMeasurements();
        java.util.List<com.stellar.platform.domain.processing.plan.IndependentJobResult> jobs =
                successfulProcessingResults();
        com.stellar.platform.desktop.presentation.AppliedDbcPresentation.Summary fromJobs =
                com.stellar.platform.desktop.presentation.AppliedDbcPresentation.fromResults(
                        jobs, scoped ? scope.identity() : "");
        if (scoped && fromJobs.consideredJobs() == 0) {
            fromJobs = com.stellar.platform.desktop.presentation.AppliedDbcPresentation.fromResults(
                    jobs, scope.displayName());
        }
        if (fromJobs.consideredJobs() > 0) {
            return fromJobs;
        }
        java.util.List<com.stellar.platform.domain.catalog.SignalCatalogEntry> entries;
        var store = appService.signalsModel().catalogStore();
        if (scoped && store instanceof com.stellar.platform.processing.catalog.ProjectMergedSignalStore merged) {
            entries = merged.physicalCatalog(scope.identity(), scope.displayName());
        } else if (appService.signalsModel().catalog() != null) {
            entries = appService.signalsModel().catalog().entries();
        } else {
            entries = java.util.List.of();
        }
        if (!entries.isEmpty()) {
            return com.stellar.platform.desktop.presentation.AppliedDbcPresentation.fromEntries(entries);
        }
        SignalsProcessingContext ctx = appService.signalsModel().processingContext();
        return com.stellar.platform.desktop.presentation.AppliedDbcPresentation.fromContextLabel(ctx.dbcFile());
    }

    private java.util.List<com.stellar.platform.domain.processing.plan.IndependentJobResult> successfulProcessingResults() {
        java.util.List<com.stellar.platform.domain.processing.plan.IndependentJobResult> registry =
                appService.processingResultRegistry().all().stream()
                        .filter(com.stellar.platform.domain.processing.plan.IndependentJobResult::successful)
                        .toList();
        if (!registry.isEmpty()) {
            return registry;
        }
        return appService.processingSelectionModel().executionJobs().stream()
                .map(row -> row.result().orElse(null))
                .filter(java.util.Objects::nonNull)
                .filter(com.stellar.platform.domain.processing.plan.IndependentJobResult::successful)
                .toList();
    }

    public String dbcCaptionForTest() {
        return contextDbcCaption == null ? "" : contextDbcCaption.getText();
    }

    public String dbcValueForTest() {
        return contextDbcLabel == null ? "" : contextDbcLabel.getText();
    }

    public String dbcDetailForTest() {
        if (contextDbcLabel == null || contextDbcLabel.getTooltip() == null) {
            return "";
        }
        return contextDbcLabel.getTooltip().getText();
    }

    public String measurementCardForTest() {
        return contextSourceLabel == null ? "" : contextSourceLabel.getText();
    }

    public String channelsCardForTest() {
        return contextChannelsLabel == null ? "" : contextChannelsLabel.getText();
    }

    public String signalCountForTest() {
        return contextSignalCountLabel == null ? "" : contextSignalCountLabel.getText();
    }

    public java.util.List<String> measurementOptionsForTest() {
        return signalBrowser == null ? java.util.List.of() : signalBrowser.measurementOptionsForTest();
    }

    public void selectMeasurementLabelForTest(String label) {
        if (signalBrowser != null) {
            signalBrowser.selectMeasurementForTest(label);
        }
    }

    public AnalysisMeasurementResolver.Resolution analysisResolutionForTest() {
        return resolveAnalysisMeasurement();
    }

    void refreshUpdateBanner() {
        if (appService == null || updateBanner == null) {
            return;
        }
        ProjectProcessingContext.CompactProgressSnapshot progress = appService.compactProgress();
        boolean showRunning = progress.showUpdateBanner();
        boolean showFailure = progress.reprocessFailure();
        boolean show = showRunning || showFailure;
        updateBanner.setVisible(show);
        updateBanner.setManaged(show);
        if (!show) {
            return;
        }
        if (showRunning) {
            updateBannerTitle.setText("Viewing current result: " + progress.viewingResultChannels());
            updateBannerDetail.setText("Updating to: " + progress.updatingToChannels());
            double p = progress.progressFraction();
            updateProgressBar.setProgress(p <= 0.0 ? ProgressBar.INDETERMINATE_PROGRESS : p);
            updateCancelButton.setVisible(true);
            updateCancelButton.setManaged(true);
            updateRetryButton.setVisible(false);
            updateRetryButton.setManaged(false);
        } else {
            updateBannerTitle.setText(progress.failureMessage());
            updateBannerDetail.setText("Previous result remains available for analysis.");
            updateProgressBar.setProgress(0);
            updateCancelButton.setVisible(false);
            updateCancelButton.setManaged(false);
            updateRetryButton.setVisible(true);
            updateRetryButton.setManaged(true);
        }
    }
}
