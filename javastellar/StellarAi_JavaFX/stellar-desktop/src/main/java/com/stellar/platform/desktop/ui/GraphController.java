package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.AnalysisMeasurementResolver;
import com.stellar.platform.desktop.presentation.AnalysisScope;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationState;
import com.stellar.platform.desktop.presentation.GraphSharedViewport;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.CategoricalSeriesSummary;
import com.stellar.platform.desktop.presentation.SignalInspectorPresentation;
import com.stellar.platform.desktop.presentation.SignalTraceLabels;
import com.stellar.platform.desktop.presentation.SignalBrowserFilter;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas;
import com.stellar.platform.desktop.ui.graph.EChartsAnalyticsPane;
import com.stellar.platform.desktop.ui.graph.EngineeringAnalysisPane;
import com.stellar.platform.desktop.ui.graph.EngineeringDiagnosticsPane;
import com.stellar.platform.desktop.ui.graph.GraphSeriesPalette;
import com.stellar.platform.desktop.ui.graph.OverlapMeasurementChooser;
import com.stellar.platform.desktop.ui.graph.SignalCategoryIndex;
import com.stellar.platform.desktop.ui.graph.map.MapRoutePane;
import com.stellar.platform.desktop.ui.graph.map.MapRouteRequest;
import com.stellar.platform.desktop.ui.graph.map.MapRouteState;
import com.stellar.platform.desktop.ui.graph.map.MapSelectionMode;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.cache.GraphQueryCache;
import com.stellar.platform.processing.graph.*;
import com.stellar.platform.processing.graph.timeline.RecordingDescriptor;
import com.stellar.platform.processing.graph.timeline.RecordingGroupIndex;
import com.stellar.platform.processing.graph.timeline.TimelineGroup;
import com.stellar.platform.processing.graph.timeline.TimelineViewportPlanner;
import com.stellar.platform.processing.graph.timeline.UnknownOrderLayout;
import com.stellar.platform.processing.map.time.ClockKind;
import com.stellar.platform.processing.map.time.RecordingClock;
import com.stellar.platform.processing.map.time.RecordingClockReader;
import com.stellar.platform.processing.map.time.TimeUnitKind;
import com.stellar.platform.processing.map.time.TimestampSource;
import com.stellar.platform.desktop.ui.graph.GraphOverviewMode;
import com.stellar.platform.desktop.ui.graph.TrackQueryWindow;
import com.stellar.platform.processing.time.MeasurementCursor;
import com.stellar.platform.processing.time.TimeSelection;
import com.stellar.platform.processing.time.SharedTimeSelectionModel;
import com.stellar.platform.desktop.presentation.AutomotiveSignalRecommender;
import com.stellar.platform.desktop.presentation.EngineerEventDocuments;
import com.stellar.platform.desktop.presentation.GraphInvestigationCodec;
import com.stellar.platform.desktop.presentation.InvestigationEventCodec;
import com.stellar.platform.desktop.ui.graph.GraphToolbarOverflow;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.geometry.Pos;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.stage.FileChooser;
import javafx.embed.swing.SwingFXUtils;
import javax.imageio.ImageIO;
import javafx.scene.shape.SVGPath;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Unified waveform + analytics workspace. Heavy I/O/LOD/cursor lookup stays off the FX thread. */
public final class GraphController implements AppServiceAware, Refreshable, DesktopPageLifecycle {
    private static final System.Logger LOG = System.getLogger(GraphController.class.getName());

    @FXML private AutomotiveSignalCanvas graphCanvas;
    private final OverlapMeasurementChooser overlapChooser = new OverlapMeasurementChooser();
    @FXML private EChartsAnalyticsPane analyticsPane;
    @FXML private EngineeringAnalysisPane engineeringPane;
    @FXML private MapRoutePane mapRoutePane;
    @FXML private ListView<SignalCatalogEntry> signalList;
    @FXML private TextField signalSearch;
    @FXML private FlowPane explorerFilterRow;
    @FXML private HBox signalPanelHeader;
    @FXML private Label signalPanelTitle;
    @FXML private Button collapseSignalPanelButton;
    @FXML private MenuButton selectionActionsButton;
    @FXML private MenuButton visibilityActionsButton;
    @FXML private MenuButton orderActionsButton;
    @FXML private MenuItem moveUpItem;
    @FXML private MenuItem moveDownItem;
    @FXML private ToggleButton filterAllButton, filterSelectedButton, filterDbcButton, filterNativeButton, filterFavoritesButton, filterRecommendedButton;
    @FXML private Label selectionLabel;
    @FXML private ComboBox<AnalysisScope> measurementCombo;
    @FXML private Label statusLabel, rangeLabel, cursorALabel, cursorBLabel, deltaLabel, liveCursorLabel, selectedTimeLabel;
    @FXML private TabPane analysisTabs;
    @FXML private ComboBox<String> trackDensity;
    @FXML private ToggleButton stackedLayoutButton, overlayLayoutButton;
    @FXML private HBox trackDensityBox;
    @FXML private HBox selectedSignalCards, graphMainRow, graphToolbar, graphPrimaryTools, graphSecondaryTools, graphFileStrip, graphLayoutModes, mapRouteTools;
    @FXML private VBox signalPanel;
    @FXML private Button focusGraphButton, panLeftButton, panRightButton, fitButton, zoomInButton, zoomOutButton, snapshotButton, addEventButton;
    @FXML private Button editEventButton, zoomEventButton, linkSnapshotButton, gpsEventButton, deleteEventButton;
    @FXML private FlowPane eventActionRow;
    @FXML private MenuButton eventActionMore;
    @FXML private Button mapFitRouteButton, mapClearSegmentButton;
    @FXML private ToggleButton mapSelectSegmentButton;
    @FXML private MenuButton toolbarMoreButton;
    @FXML private ScrollPane waveformScroll;
    @FXML private TableView<SummaryStatsRow> summaryStatsTable;
    @FXML private TableColumn<SummaryStatsRow,String> summarySignalColumn, summaryUnitColumn, summarySamplesColumn, summaryMinColumn, summaryMaxColumn, summaryMeanColumn, summaryRmsColumn;
    @FXML private TableView<CursorCompareRow> cursorCompareTable;
    @FXML private TableColumn<CursorCompareRow,String> compareSignalColumn, compareATimeColumn, compareAValueColumn, compareBTimeColumn, compareBValueColumn, compareDeltaTimeColumn, compareDeltaValueColumn;
    @FXML private javafx.scene.control.TabPane inspectorTabs;
    @FXML private VBox emptyStateBox;
    @FXML private Label graphEmptyTitle;
    @FXML private Label graphEmptyBody;
    @FXML private Label graphEmptyHint;
    @FXML private Button addSignalsToGraphButton;
    @FXML private Button resetViewButton, clearViewButton;
    @FXML private Button previousFileButton, nextFileButton, utcGroupButton, unknownGroupButton;
    @FXML private ToggleButton fileFocusToggle;
    @FXML private Label fileFocusLabel, timelineGroupLabel;
    @FXML private HBox fileFocusBar;
    @FXML private Label visibleCountLabel;
    @FXML private TextArea notesArea;
    @FXML private ListView<String> eventsList;
    @FXML private Label eventsEmptyLabel;
    private java.util.List<com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata> displayedBookmarks = java.util.List.of();
    private String selectedEventId;
    @FXML private VBox detailsInspector;
    @FXML private VBox inspectorPanel;
    @FXML private VBox inspectorRail;
    @FXML private Button inspectorRailButton;
    @FXML private Button inspectorHeaderToggleButton;
    @FXML private SplitPane graphWorkspaceSplit;
    private DesktopAppService appService; private MainShellController shell;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"stellar-graph-query");t.setDaemon(true);return t;});
    /** Full-series categorical pass. Never the JavaFX thread: a merged recording is read once per key. */
    private final ExecutorService categoricalScan=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"stellar-categorical-scan");t.setDaemon(true);return t;});
    private final java.util.concurrent.ConcurrentHashMap<SignalCatalogKey, java.util.Optional<CategoricalSeriesSummary.Result>> categoricalByKey=
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Set<SignalCatalogKey> categoricalPending=java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService viewportScheduler=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"stellar-graph-debounce");t.setDaemon(true);return t;});
    private volatile ScheduledFuture<?> pendingViewport, pendingHover;
    private volatile Future<?> pendingRender;
    private static final ThreadLocal<ViewportTrace> VIEWPORT_TRACE = new ThreadLocal<>();
    private long start,end,generation; private long dataStart=Long.MIN_VALUE,dataEnd=Long.MAX_VALUE; private Long cursorA,cursorB; private List<GraphRenderSeries> lastRendered=List.of();
    /** Shift+click, Ctrl+click, and the cursor buttons pin A and B. A plain click does not. */
    private boolean cursorAPinned;
    private boolean cursorBPinned;
    /** Sample chosen by the last plain click. Point events use it. Cursor A and B stay put. */
    private Long transientSampleNanos;
    private boolean sampleFromClick;
    private List<GraphLogicalTrack> lastTracks=List.of();
    /** In-memory file focus for recordings that do not share a verified clock. Not an analysis-scope change. */
    private List<FileSlot> fileSlots=List.of();
    private int focusedFileIndex;
    private boolean fileFocusActive;
    /** User asked for the optional single-file tool. Unknown clocks do not turn this on. */
    private volatile boolean fileFocusRequested;
    /** Serializes restored file focus with disableFileFocusForTest. */
    private final Object fileFocusLock = new Object();
    private volatile TimelineGroup preferredTimelineGroup;
    private volatile String pendingRestoredFocusId = "";
    private volatile UnknownOrderLayout activeUnknownLayout;
    private volatile com.stellar.platform.processing.graph.timeline.RecordingGroupIndex activeGroupIndex;
    private volatile MeasurementCursor pendingMapCursor;
    private volatile long lastViewportFxQueueMs;
    static final String UNKNOWN_ORDER_ANALYSIS_REFUSAL =
            "Recording order unknown. Layout slots are not sample timestamps, so analytics and engineering "
                    + "are not run across these recordings. Focus one recording to analyse that file on its stored clock.";
    private volatile boolean unknownOrderDomain;
    private long[] savedUtcViewport;
    private long[] savedUnknownViewport;
    private int utcGroupCount;
    private int unknownGroupCount;
    private boolean applyingTimelineChrome;
    private boolean applyingMapCursor;
    private Long cursorAAbsolute;
    private Long cursorBAbsolute;
    /** Display-domain cursor positions. Distinct from the verified UTC instant in {@link #cursorAAbsolute}. */
    private Long cursorADisplay;
    private Long cursorBDisplay;
    /** Comparable recordings share this UTC origin. The viewport is an offset from it, not an epoch. */
    private volatile boolean sharedDisplayDomain;
    private volatile long sharedOriginUtc;
    private volatile long sharedDisplayStart;
    private volatile long sharedDisplayEnd;
    private String cursorMeasurementId = "";
    private String clockDecisionToken="";
    private boolean clockDecisionComparable;
    private java.util.Map<SignalCatalogKey,String> analysisLabels=java.util.Map.of();
    private String analysisScopeNote="";
    /** One LOD cache for the graph session. Invalidated when project, scope, artifact or decoder generation changes. */
    /** One visible window of a 73-file project is a few hundred series. The cap still evicts. */
    private final GraphQueryCache graphQueryCache=new GraphQueryCache(1024);
    private String graphCacheToken="";
    private long graphCacheGeneration=1L;
    private final AtomicLong viewportRequests=new AtomicLong();
    private final AtomicLong viewportAccepted=new AtomicLong();
    private final AtomicLong viewportRejectedStale=new AtomicLong();
    /** Track indexes last submitted. A small scroll inside this window does not start another query. */
    private TrackQueryWindow.Range residentQuery;
    /** Time window last painted from a completed query. A new range must not keep those waveforms. */
    private long appliedViewportStart=Long.MIN_VALUE;
    private long appliedViewportEnd=Long.MIN_VALUE;
    private boolean adjustingTrackWindow;
    private List<SignalCatalogEntry> allSignals=List.of();
    private com.stellar.platform.desktop.ui.graph.SignalCategoryIndex signalCategories=
            com.stellar.platform.desktop.ui.graph.SignalCategoryIndex.EMPTY;
    private int signalCategoryRevision=Integer.MIN_VALUE;
    /** Tab bodies that are not selected. They stay alive, but out of the scene until opened. */
    private final java.util.Map<javafx.scene.control.Tab, Node> parkedTabContent=new java.util.IdentityHashMap<>();
    private java.util.Map<String, Integer> visibleCategoryCounts=java.util.Map.of();
    private boolean focusGraph=false;
    /** Inspector managed/visible state captured when entering Full Screen so Exit can restore it. */
    private Boolean inspectorOpenBeforeFullScreen;
    /** True when the user explicitly toggled Inspector while Full Screen was active. */
    private boolean inspectorToggledDuringFullScreen;
    /** SplitPane divider captured when Focus Mode hides the Signals explorer, restored on exit. */
    private double signalPanelDividerBeforeFullScreen=0.28;
    /** Session preference: Inspector open/collapsed. Not cleared by layout/tab/zoom changes. */
    private boolean inspectorVisiblePreference=true;
    /** Last expanded Inspector width (clamped). Restored on Show Inspector. */
    private double inspectorExpandedWidth=300;
    private static final double INSPECTOR_MIN_WIDTH=260;
    private static final double INSPECTOR_PREF_WIDTH=300;
    private static final double INSPECTOR_MAX_WIDTH=380;
    private final java.util.Set<String> collapsedSignalGroups=new java.util.HashSet<>();
    private final java.util.LinkedHashSet<SignalCatalogKey> selectedSignalKeys=new java.util.LinkedHashSet<>();
    private List<SignalCatalogEntry> explorerRows=List.of();
    private ExplorerFilter explorerFilter=ExplorerFilter.ALL;
    private String explorerSearchQuery="";
    private SignalCatalogKey focusedExplorerKey;
    private java.util.List<SignalCatalogKey> pinnedEventSignals;
    private java.util.List<SignalCatalogKey> eventCatalogOverride;
    private boolean applyingSignalFilter=false;
    private boolean applyingMembershipCommand=false;
    private int membershipCommandCount;
    private int selectedKeysRevision;
    private String lastExplorerPointerTarget="";
    private int checkboxActionCount;
    private boolean applyingLayoutChrome=false;
    private enum ExplorerFilter { ALL, SELECTED, DBC, NATIVE, FAVORITES, RECOMMENDED }
    /** Waveform viewport before a map range filter, so clearing the filter restores it rather than resetting zoom. */
    private Long viewportBeforeMapFilterStart, viewportBeforeMapFilterEnd;
    private String mapRouteSourceFile="";
    private String lastAnalysisMeasurementToken="";
    private boolean suppressMeasurementCombo;
    private boolean mapRouteStateRestored=false, restoringMapRouteState=false;
    private boolean restoringPresentation=false;
    private boolean chipStripVisibleBeforeFocus=true;
    private boolean fileStripVisibleBeforeFocus=true;
    private Runnable analysisSelectionDetach;
    /** Last GPS mapping revision pushed into the map pane; -1 forces a load on first activation. */
    private int appliedGpsMappingRevision=-1;
    private volatile ScheduledFuture<?> pendingMapRouteSave;
    private volatile ScheduledFuture<?> pendingInvestigationSave;
    private volatile Future<?> pendingCursors;
    private final java.util.Map<SignalCatalogKey, String> failedTraces = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.List<Runnable> listenerRemovers = new java.util.ArrayList<>();
    private int boundSessionGeneration;
    /** Pixel width of the last waveform query. Sub-pixel layout noise must not reload. */
    private int lastViewportQueryWidth=-1;
    private int graphHelpApplies;
    private javafx.event.EventHandler<javafx.scene.input.KeyEvent> escapeHandler;
    private javafx.scene.Scene escapeScene;

    @FXML private void initialize(){
        configureAnalysisToolbar();
        configureSignalSearch();
        configureFileStrip();
        configureExplorerFilters();
        signalList.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        signalList.setCellFactory(v->new ExplorerCell());
        if(moveUpItem!=null){
            moveUpItem.setAccelerator(new javafx.scene.input.KeyCodeCombination(
                    javafx.scene.input.KeyCode.UP, javafx.scene.input.KeyCombination.ALT_DOWN));
        }
        if(moveDownItem!=null){
            moveDownItem.setAccelerator(new javafx.scene.input.KeyCodeCombination(
                    javafx.scene.input.KeyCode.DOWN, javafx.scene.input.KeyCombination.ALT_DOWN));
        }
        refreshOrderActions();
        for(MenuButton menu: java.util.List.of(selectionActionsButton, visibilityActionsButton, orderActionsButton)){
            if(menu==null) continue;
            menu.setMinWidth(Region.USE_PREF_SIZE);
            menu.setMaxWidth(Region.USE_PREF_SIZE);
        }
        signalList.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, this::logExplorerPointer);
        signalList.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, this::logExplorerPointer);
        signalList.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, this::logExplorerPointer);
        javafx.collections.ListChangeListener<SignalCatalogEntry> focusListener=c->{
            if(applyingSignalFilter)return;
            SignalCatalogEntry focused=signalList.getSelectionModel().getSelectedItem();
            refreshOrderActions();
            if(focused!=null) showInspectorDetails(focused);
        };
        signalList.getSelectionModel().getSelectedItems().addListener(focusListener);
        listenerRemovers.add(()->signalList.getSelectionModel().getSelectedItems().removeListener(focusListener));
        graphCanvas.widthProperty().addListener((o,a,b)->{
            if(b==null) return;
            int next=Math.max(320,(int)Math.rint(b.doubleValue()));
            if(lastViewportQueryWidth>=0 && Math.abs(next-lastViewportQueryWidth)<4) return;
            requestViewport();
        });
        graphCanvas.setInteractionHandlers(this::zoomAt,this::panTo,(which,t)->{
            if("S".equals(which)) acceptSampleSelection(t);
            else setCursor(which,t);
        });
        graphCanvas.setMeasurementCursorHandler(this::setCursorFromHit);
        graphCanvas.setOverlapChoiceHandler(this::showOverlapChoices);
        graphCanvas.setOverlapDismissHandler(this::dismissOverlapChoices);
        graphCanvas.setSharedGapHandler(this::showUnownedCursor);
        graphCanvas.setLegendToggleHandler(this::toggleHidden);
        graphCanvas.setOverlayClickHandler(this::onEventOverlayClicked);
        graphCanvas.setFileNavigatorHandler(this::focusFileAt);
        installWaveformWheelZoom();
        analyticsPane.setDrillDownHandler(this::drillDown);
        engineeringPane.setDrillDownHandler(this::drillDown);
        engineeringPane.setBookmarkPersistHandler(row -> {
            if (appService != null) {
                appService.persistEngineeringBookmark(
                        row.signal(), row.type(), row.observation(), row.timestampNanos());
            }
        });
        engineeringPane.setRunHandler(this::runEngineeringAnalysis);
        engineeringPane.setOverlayHandler(visible -> {
            if (graphCanvas != null) {
                graphCanvas.setAnalysisOverlaysVisible(visible);
            }
        });
        engineeringPane.setExportHandler(path -> exportEngineeringAnalysis());
        engineeringPane.setAddToInvestigationHandler(this::addLastAnalysisToInvestigation);
        engineeringPane.advanced().setRunHandler(this::runAdvancedAnalytics);
        engineeringPane.advanced().setExportHandler(path -> exportAdvancedAnalytics());
        engineeringPane.advanced().setSaveDerivedHandler(this::saveAdvancedDerived);
        engineeringPane.advanced().setAddToInvestigationHandler(this::addLastAdvancedToInvestigation);
        engineeringPane.diagnostics().setRunHandler(this::runEngineeringDiagnostics);
        engineeringPane.diagnostics().setDryRunHandler(this::dryRunEngineeringDiagnostics);
        engineeringPane.diagnostics().setFindingHandler(this::focusDiagnosticFinding);
        engineeringPane.diagnostics().setAnnotateHandler(this::annotateDiagnosticFinding);
        engineeringPane.diagnostics().setEvidenceSignalsHandler(this::showDiagnosticEvidenceSignals);
        engineeringPane.diagnostics().setAddToInvestigationHandler(this::addFindingToInvestigation);
        engineeringPane.diagnostics().setExportHandler(path -> exportEngineeringDiagnostics());
        engineeringPane.diagnostics().setExportPackHandler(path -> exportDiagnosticRulePack());
        engineeringPane.diagnostics().setImportHandler(path -> importDiagnosticRulePack());
        engineeringPane.diagnostics().setBindHandler(this::bindDiagnosticRole);
        engineeringPane.diagnostics().setReviewHandler(this::saveDiagnosticReview);
        engineeringPane.diagnostics().setOverlayToggleHandler(this::toggleDiagnosticOverlays);
        engineeringPane.diagnostics().setHistoryHandler(this::loadHistoricDiagnosticRun);
        engineeringPane.diagnostics().setCompareHandler(this::compareHistoricDiagnosticRun);
        engineeringPane.diagnostics().setPreferenceHandler(this::saveDiagnosticPreference);
        engineeringPane.rules().setSaveHandler(this::saveCustomDiagnosticRule);
        engineeringPane.rules().setDeleteHandler(this::deleteCustomDiagnosticRule);
        if (analysisTabs != null) {
            disableEngineeringTab();
            analysisTabs.getSelectionModel().selectedIndexProperty().addListener((o, a, b) -> {
                int index=b.intValue();
                if(index>=0 && index<analysisTabs.getTabs().size()){
                    restoreParkedTab(analysisTabs.getTabs().get(index));
                }
                if(index==2 && engineeringPane!=null){
                    engineeringPane.diagnostics().refreshIfStale();
                }
                if(restoringPresentation) return;
                releaseFocusFromInactiveAnalysisTabs();
                updateActiveAnalysisPane();
                syncPresentationToInvestigation();
                persistInvestigationSoon();
            });
            updateActiveAnalysisPane();
            parkInactiveTabs(analysisTabs);
        }
        configureEventActions();
        if (mapRoutePane != null) {
            mapRoutePane.setStateChangedHandler(() -> {
                scheduleMapRouteStateSave();
                syncMapSelectSegmentToggle();
            });
            mapRoutePane.setRouteReadyHandler(this::onGpsRouteReady);
            mapRoutePane.setMeasurementCursorApplied(this::refreshInvestigationInspector);
            mapRoutePane.setConfigureMappingHandler(this::openGpsMappingConfiguration);
            mapRoutePane.setMappingPersistence(new MapRoutePane.RouteMappingPersistence() {
                @Override
                public java.util.List<com.stellar.platform.processing.map.GpsSignalMapping> loadProjectMappings() {
                    return appService == null ? java.util.List.of() : appService.loadAllGpsSignalMappings();
                }

                @Override
                public java.util.List<com.stellar.platform.processing.map.GpsSignalMapping> loadDefaults() {
                    return appService == null ? java.util.List.of() : appService.loadRouteConfigDefaults();
                }

                @Override
                public boolean saveProjectMappings(String sourceId, String fingerprint,
                        java.util.List<com.stellar.platform.processing.map.GpsSignalMapping> mappings) {
                    if (appService == null) {
                        return false;
                    }
                    boolean stored = appService.persistGpsSignalMappings(sourceId, fingerprint, mappings);
                    if (stored) {
                        appliedGpsMappingRevision = appService.gpsMappingRevision();
                    }
                    return stored;
                }

                @Override
                public com.stellar.platform.processing.drive.speed.SpeedSourceMapping loadSpeedMapping(String sourceId) {
                    return appService == null ? null : appService.loadVehicleSpeedMapping(sourceId);
                }

                @Override
                public void saveSpeedMapping(com.stellar.platform.processing.drive.speed.SpeedSourceMapping mapping) {
                    if (appService != null) {
                        appService.persistVehicleSpeedMapping(mapping);
                    }
                }
            });
            listenerRemovers.add(()->{
                mapRoutePane.setStateChangedHandler(null);
                mapRoutePane.setMeasurementCursorApplied(null);
                mapRoutePane.setConfigureMappingHandler(null);
                mapRoutePane.setMappingPersistence(null);
            });
        }
        if(notesArea!=null){
            notesArea.textProperty().addListener((o,a,b)->{
                if(restoringPresentation) return;
                GraphInvestigationState inv=investigation();
                if(inv!=null) inv.setNotes(b);
                persistInvestigationSoon();
            });
        }
        if(eventsList!=null){
            eventsList.setOnMouseClicked(e->{
                int index=eventsList.getSelectionModel().getSelectedIndex();
                if(index>=0) jumpToEventIndex(index);
            });
            eventsList.getSelectionModel().selectedIndexProperty().addListener((o, previous, index) -> {
                int selected = index == null ? -1 : index.intValue();
                if (selected >= 0 && selected < displayedBookmarks.size()) {
                    selectedEventId = displayedBookmarks.get(selected).bookmarkId();
                }
                refreshEventActions();
                paintEventOverlays();
            });
        }
        if(graphToolbar!=null){
            graphToolbar.widthProperty().addListener((o,a,b)->relayoutGraphToolbar());
            if(toolbarMoreButton!=null){
                toolbarMoreButton.showingProperty().addListener((o,was,showing)->{
                    if(Boolean.TRUE.equals(showing) && toolbarMoreButton.getTooltip()!=null){
                        toolbarMoreButton.getTooltip().hide();
                    }
                    if(Boolean.FALSE.equals(showing)){
                        relayoutGraphToolbar();
                    }
                });
            }
            javafx.application.Platform.runLater(this::relayoutGraphToolbar);
        }
        configureStatisticsTables();
        configureLayoutSelector();
        applyInspectorChrome(isInspectorOpen());
        if(trackDensity!=null){trackDensity.setItems(FXCollections.observableArrayList("Auto height","Compact","Comfortable"));trackDensity.getSelectionModel().select(2);onTrackDensityChanged();}
        graphCanvas.hoverTimestampProperty().addListener((o,oldValue,newValue)->updateLiveCursor(newValue));
        graphCanvas.hoverPointProperty().addListener((o,oldValue,newValue)->updateLiveCursorPoint(newValue));
        if (waveformScroll != null) {
            waveformScroll.viewportBoundsProperty().addListener((o, a, b) -> syncWaveformCanvasWindow());
            waveformScroll.vvalueProperty().addListener((o, a, b) -> syncWaveformCanvasWindow());
            // ScrollPaneSkin caches its viewport. Rebuilding that cache replays the graph
            // canvas command stream through the software renderer, including while the Map
            // tab is being selected. The canvas already owns its backing buffer.
            waveformScroll.skinProperty().addListener((obs, previous, skin) -> releaseWaveformViewportCache());
            releaseWaveformViewportCache();
        }
        if(selectedSignalCards!=null) selectedSignalCards.widthProperty().addListener((o,a,b)->rebuildSelectedSignalCards());
        if(inspectorPanel!=null){
            inspectorPanel.widthProperty().addListener((o,a,b)->{
                if(isInspectorOpen() && b!=null && b.doubleValue()>=INSPECTOR_MIN_WIDTH){
                    inspectorExpandedWidth=clampInspectorWidth(b.doubleValue());
                }
            });
        }
        if(measurementCombo!=null){
            measurementCombo.setConverter(new javafx.util.StringConverter<>() {
                @Override public String toString(AnalysisScope scope) {
                    return scope==null?"":scope.comboLabel();
                }
                @Override public AnalysisScope fromString(String string) { return null; }
            });
            measurementCombo.setButtonCell(measurementButtonCell());
            measurementCombo.setCellFactory(list->measurementButtonCell());
            measurementCombo.setMinWidth(160);
            measurementCombo.setPrefWidth(260);
            measurementCombo.setMaxWidth(320);
            HBox.setHgrow(measurementCombo, Priority.NEVER);
            measurementCombo.valueProperty().addListener((o,a,b)->{
                if(suppressMeasurementCombo || appService==null || b==null) return;
                if(b.equals(appService.analysisScope()) && b.isAll()==appService.isProjectWideAnalysis()) return;
                if(b.isSingle() && b.measurement().map(m->m.sourceId().equals(appService.activeMeasurementSourceId())).orElse(false)
                        && !appService.isProjectWideAnalysis()) return;
                appService.selectAnalysisScope(b);
                refresh();
            });
        }
    }
    @Override public void setAppService(DesktopAppService a,MainShellController s){
        appService=a;shell=s;
        if(appService!=null){
            appService.setPersistenceFlushHook(this::flushInvestigationForSave);
            bindAnalysisSelection();
        }
    }

    private void bindAnalysisSelection(){
        if(analysisSelectionDetach!=null){
            analysisSelectionDetach.run();
            analysisSelectionDetach=null;
        }
        if(appService==null) return;
        SharedTimeSelectionModel model=appService.analysisSelection();
        if(mapRoutePane!=null) mapRoutePane.useSharedSelection(model);
        analysisSelectionDetach=model.addListener(this::onSharedTimeSelectionChanged);
        listenerRemovers.add(()->{
            if(analysisSelectionDetach!=null){
                analysisSelectionDetach.run();
                analysisSelectionDetach=null;
            }
        });
    }

    void flushInvestigationForSave(){
        ScheduledFuture<?> previous=pendingInvestigationSave;
        if(previous!=null) previous.cancel(false);
        // Capture a consistent in-memory snapshot; SQLite write happens only on explicit Save.
        // Viewport is already written by commitViewport; do not clobber a later investigation
        // mutation with an auto-fit canvas range.
        syncPresentationToInvestigation();
    }

    @Override public void refresh(){
        if(appService==null)return;
        restoringPresentation=true;
        try{
            refreshWhileRestoring();
        }finally{
            restoringPresentation=false;
        }
    }

    private void refreshWhileRestoring(){
        var model=appService.signalsModel();
        String measurementToken=appService.analysisScopeToken();
        boolean measurementSwitched=!lastAnalysisMeasurementToken.isBlank()
                && !measurementToken.equals(lastAnalysisMeasurementToken);
        lastAnalysisMeasurementToken=measurementToken;
        if(measurementSwitched){
            generation++;
            explorerFilter=ExplorerFilter.ALL;
            cancelPendingGraphWork(true);
            if(mapRoutePane!=null){
                mapRoutePane.resetForFileChange();
            }
            mapRouteSourceFile="";
            mapRouteStateRestored=false;
            appliedGpsMappingRevision=-1;
            cursorA=null;
            cursorB=null;
            if(graphCanvas!=null) graphCanvas.setCursors(null,null);
            if(engineeringPane!=null){
                engineeringPane.clearViewportState();
                engineeringPane.showAnalysisReport("");
            }
        }
        // Analysis catalog is the active measurement only — never a concatenated project waveform.
        allSignals=appService.engineeringCatalog();
        installSignalCategories();
        var scope=appService.analysisScope();
        String measurementName=scope.comboLabel();
        applyMeasurementIdentity(measurementName, appService.isProjectWideAnalysis()
                ? measurementName
                : appService.activeMeasurementTooltip());
        String mapSource=mapRouteIdentity();
        if(mapRoutePane!=null&&!mapSource.equals(mapRouteSourceFile)){
            mapRoutePane.resetForFileChange();
            mapRouteSourceFile=mapSource;
            mapRouteStateRestored=false;
            appliedGpsMappingRevision=-1;
        }
        configureExplorerFilters();
        reconcileMembershipToCurrentCatalog("refreshWhileRestoring", measurementSwitched);
        applySignalFilter();
        GraphInvestigationState investigation=investigation();
        SignalCatalogKey restoreFocus=focusedExplorerKey!=null?focusedExplorerKey
                :(selectedSignalKeys.isEmpty()?null:selectedSignalKeys.getFirst());
        if(!measurementSwitched && investigation!=null && investigation.focusKey()!=null
                && selectedSignalKeys.contains(investigation.focusKey())){
            restoreFocus=investigation.focusKey();
        }
        focusExplorerRow(restoreFocus, false);
        if(investigation!=null){
            boundSessionGeneration=investigation.sessionGeneration();
            applyRestoredLayout(investigation);
            applyRestoredPresentation(investigation);
            if(notesArea!=null) notesArea.setText(investigation.notes());
            if(measurementSwitched){
                cursorA=null;
                cursorB=null;
                if(graphCanvas!=null) graphCanvas.setCursors(null,null);
            }else{
                cursorA=investigation.cursorA();
                cursorB=investigation.cursorB();
                if(graphCanvas!=null) graphCanvas.setCursors(cursorA,cursorB);
            }
        }
        updateSelectionLabel();
        rebuildSelectedSignalCards();
        updateEmptyState();
        if(appService.consumeAnalyzeHandoff() && analysisTabs!=null){
            analysisTabs.getSelectionModel().select(0);
            updateActiveAnalysisPane();
        }
        if(mapRoutePane!=null){
            mapRoutePane.setSelectedSignals(selectedKeys());
            mapRoutePane.setActiveMeasurementName(measurementName);
        }
        if(measurementSwitched){
            if(!selectedSignalKeys.isEmpty()){
                fitRange();
            }else{
                clearRenderedAnalysis();
            }
        }else if(investigation!=null && investigation.viewportInitialized()){
            start=investigation.viewportStart();
            end=investigation.viewportEnd();
            String domain=investigation.viewportDomain();
            String group=investigation.timelineGroup();
            if("FILE_FOCUS".equals(domain)){
                if("UNKNOWN_ORDER".equals(group)){
                    preferredTimelineGroup=com.stellar.platform.processing.graph.timeline.TimelineGroup.UNKNOWN_ORDER;
                }else if("VERIFIED_UTC".equals(group)){
                    preferredTimelineGroup=com.stellar.platform.processing.graph.timeline.TimelineGroup.VERIFIED_UTC;
                }
            }else if("UNKNOWN_ORDER".equals(domain)){
                preferredTimelineGroup=com.stellar.platform.processing.graph.timeline.TimelineGroup.UNKNOWN_ORDER;
                savedUnknownViewport=new long[]{start, end};
            }else if("VERIFIED_UTC".equals(domain)){
                preferredTimelineGroup=com.stellar.platform.processing.graph.timeline.TimelineGroup.VERIFIED_UTC;
                savedUtcViewport=new long[]{start, end};
            }
            if(investigation.fileFocusMeasurementId()!=null && !investigation.fileFocusMeasurementId().isBlank()){
                pendingRestoredFocusId=investigation.fileFocusMeasurementId();
            }
            refreshFullAnalysisRange();
            if(!selectedSignalKeys.isEmpty() && !GraphSharedViewport.isUsable(start,end,dataStart,dataEnd)){
                fitRange();
            }else{
                if(graphCanvas!=null) graphCanvas.prepareSharedViewport(start,end);
                requestViewport();
            }
        }else if(!selectedSignalKeys.isEmpty()){
            fitRange();
        }else{
            clearRenderedAnalysis();
        }
        restoreEngineeringBookmarks();
        if (engineeringPane != null) {
            engineeringPane.restoreAnalysisType(appService.lastEngineeringAnalysisType());
            if(measurementSwitched){
                engineeringPane.clearViewportState();
                engineeringPane.showAnalysisReport("");
            }
        }
        installEscapeHandler();
    }

    private void restoreEngineeringBookmarks() {
        if (appService == null) return;
        var stored = appService.listEngineeringBookmarks();
        if (engineeringPane != null) {
        List<EngineeringAnalysisPane.BookmarkRow> rows = stored.stream()
                .map(b -> new EngineeringAnalysisPane.BookmarkRow(
                        String.format("%.6f s", b.timestampNanos() / 1e9),
                        b.signalName(),
                        b.eventType(),
                        b.observation(),
                        b.timestampNanos()))
                .toList();
        engineeringPane.replaceBookmarks(rows);
        String report = appService.lastEngineeringAnalysisReport();
        if (report != null && !report.isBlank()) {
            engineeringPane.showAnalysisReport(report);
            applyAnalysisOverlays(appService.lastEngineeringAnalysis());
        }
        var advanced = appService.lastAdvancedAnalytics();
        if (advanced != null) {
            engineeringPane.advanced().show(advanced, appService.lastEngineeringAnalysisReport());
        }
        restoreEngineeringDiagnostics();
        }
        applyInvestigationEventMarkers(stored);
    }

    private void restoreEngineeringDiagnostics() {
        if (engineeringPane == null || appService == null) {
            return;
        }
        engineeringPane.diagnostics().setPacks(appService.diagnosticPacks());
        engineeringPane.diagnostics().setCatalog(appService.signalsModel().catalog().entries());
        engineeringPane.diagnostics().setBindings(
                appService.loadDiagnosticBindings(engineeringPane.diagnostics().pack().packId()));
        engineeringPane.diagnostics().setPreferences(
                appService.loadDiagnosticRulePreferences(engineeringPane.diagnostics().pack().packId()));
        engineeringPane.diagnostics().setReviews(appService.loadDiagnosticReviews());
        engineeringPane.diagnostics().setRunHistory(appService.diagnosticRunHistory());
        refreshDiagnosticScopeLabel();
        engineeringPane.rules().setPacks(appService.engineeringDiagnostics().builtInPack(),
                appService.projectCustomRulePack());
        var result = appService.lastEngineeringDiagnostics();
        if (result != null) {
            engineeringPane.diagnostics().showResult(result, appService.lastEngineeringDiagnosticsReport());
            applyDiagnosticOverlays(result);
        }
    }

    private void runEngineeringAnalysis(EngineeringAnalysisPane.RunSpec spec) {
        if (appService == null || spec == null) {
            return;
        }
        List<SignalCatalogKey> keys = appService.signalsModel().workspaceKeys();
        if (keys.isEmpty()) {
            engineeringPane.showAnalysisReport("Select signals in Graph before running analysis.");
            return;
        }
        if (unknownOrderLayoutViewport()) {
            engineeringPane.showAnalysisReport(UNKNOWN_ORDER_ANALYSIS_REFUSAL);
            return;
        }
        keys = physicalKeysForFileFocus(keys);
        if (keys.isEmpty()) {
            engineeringPane.showAnalysisReport("The focused recording has no samples for the selected signals.");
            return;
        }
        GraphInvestigationState inv = investigation();
        long viewportStart = inv == null ? start : inv.viewportStart();
        long viewportEnd = inv == null ? end : inv.viewportEnd();
        Long cursorANanos = inv == null ? cursorA : inv.cursorA();
        Long cursorBNanos = inv == null ? cursorB : inv.cursorB();
        com.stellar.platform.analysis.model.AnalysisType type;
        try {
            type = com.stellar.platform.analysis.model.AnalysisType.valueOf(spec.type());
        } catch (RuntimeException ex) {
            type = com.stellar.platform.analysis.model.AnalysisType.STATISTICS;
        }
        com.stellar.platform.analysis.model.TimeRangeSource range;
        try {
            range = com.stellar.platform.analysis.model.TimeRangeSource.valueOf(spec.range());
        } catch (RuntimeException ex) {
            range = com.stellar.platform.analysis.model.TimeRangeSource.VIEWPORT;
        }
        com.stellar.platform.analysis.model.AnalysisParameters params =
                com.stellar.platform.analysis.model.AnalysisParameters.empty();
        if (spec.threshold() != null) {
            params = params.withThreshold(
                    com.stellar.platform.analysis.model.ThresholdOperator.GREATER_THAN, spec.threshold());
        }
        if (spec.hysteresisExit() != null) {
            params = params.withHysteresisExit(spec.hysteresisExit());
        }
        if (type == com.stellar.platform.analysis.model.AnalysisType.DERIVED) {
            params = params.withDerivedOp("sub");
        }
        if (type == com.stellar.platform.analysis.model.AnalysisType.OPERATING_BAND && spec.threshold() != null) {
            params = params.withBand(spec.threshold(), spec.hysteresisExit() == null
                    ? spec.threshold() + 1 : spec.hysteresisExit());
        }
        com.stellar.platform.analysis.model.AlignmentPolicy align =
                type == com.stellar.platform.analysis.model.AnalysisType.COMPARISON
                        || type == com.stellar.platform.analysis.model.AnalysisType.CORRELATION
                        || type == com.stellar.platform.analysis.model.AnalysisType.DERIVED
                        || type == com.stellar.platform.analysis.model.AnalysisType.LAG
                ? com.stellar.platform.analysis.model.AlignmentPolicy.PREVIOUS_VALUE_HOLD
                : com.stellar.platform.analysis.model.AlignmentPolicy.EXACT_TIMESTAMP;
        com.stellar.platform.analysis.model.AnalysisRequest request =
                com.stellar.platform.analysis.model.AnalysisRequest.builder(type)
                        .projectId(appService.currentProjectId())
                        .signals(keys)
                        .rangeSource(range)
                        .requestedWindow(com.stellar.platform.analysis.model.TimeWindow.of(
                                viewportStart, viewportEnd))
                        .cursors(cursorANanos, cursorBNanos)
                        .alignmentPolicy(align)
                        .parameters(params)
                        .build();
        long my=generation;
        int session=boundSessionGeneration;
        worker.submit(() -> {
            try {
                var result = appService.runEngineeringAnalysis(request);
                String report = appService.lastEngineeringAnalysisReport();
                javafx.application.Platform.runLater(() -> {
                    if(!acceptViewportResult(my, session)) return;
                    engineeringPane.showAnalysisReport(report);
                    applyAnalysisOverlays(result);
                });
            } catch (RuntimeException ex) {
                javafx.application.Platform.runLater(() -> {
                    if(!acceptViewportResult(my, session)) return;
                    engineeringPane.showAnalysisReport(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                });
            }
        });
    }

    private void runAdvancedAnalytics(com.stellar.platform.desktop.ui.graph.AdvancedAnalyticsPane.AdvancedRunSpec spec) {
        if (appService == null || spec == null) {
            return;
        }
        List<SignalCatalogKey> keys = appService.signalsModel().workspaceKeys();
        if (keys.isEmpty()) {
            engineeringPane.advanced().show(null, "Select signals in Graph before running advanced analytics.");
            return;
        }
        if (unknownOrderLayoutViewport()) {
            engineeringPane.advanced().show(null, UNKNOWN_ORDER_ANALYSIS_REFUSAL);
            return;
        }
        keys = physicalKeysForFileFocus(keys);
        if (keys.isEmpty()) {
            engineeringPane.advanced().show(null, "The focused recording has no samples for the selected signals.");
            return;
        }
        GraphInvestigationState inv = investigation();
        long viewportStart = inv == null ? start : inv.viewportStart();
        long viewportEnd = inv == null ? end : inv.viewportEnd();
        Long cursorANanos = inv == null ? cursorA : inv.cursorA();
        Long cursorBNanos = inv == null ? cursorB : inv.cursorB();
        com.stellar.platform.analysis.model.TimeWindow window =
                com.stellar.platform.analysis.model.TimeWindow.of(viewportStart, viewportEnd);
        if ("CURSOR_A_TO_B".equals(spec.range()) && cursorANanos != null && cursorBNanos != null) {
            window = com.stellar.platform.analysis.model.TimeWindow.of(cursorANanos, cursorBNanos);
        }
        if (window.isUnbounded() || window.durationNanos() <= 0) {
            engineeringPane.advanced().show(null,
                    "Select a finite graph window or Cursor A→B. Frequency analysis will not load the full recording.");
            return;
        }
        String unit = keys.isEmpty() ? "" : unitFromSeries(keys.getFirst());
        var request = com.stellar.platform.analysis.advanced.AdvancedAnalyticsRequest.builder(spec.type())
                .projectId(appService.currentProjectId())
                .signals(keys)
                .window(window)
                .sampleHandling(spec.sampleHandling())
                .resampleMethod(spec.resampleMethod())
                .targetRateHz(spec.targetRateHz())
                .windowFunction(spec.window())
                .fftSize(spec.fftSize())
                .welchSegmentLength(spec.welchSegment())
                .welchOverlap(spec.welchOverlap())
                .lowCutoffHz(spec.lowCutoffHz())
                .highCutoffHz(spec.highCutoffHz())
                .filterOrder(spec.filterOrder())
                .filterPhaseMode(spec.phaseMode())
                .smoothWindow(spec.smoothWindow(), spec.smoothUnit())
                .alpha(spec.alpha())
                .crossingMode(spec.crossingMode())
                .windowedStatistic(spec.windowedStatistic())
                .envelopeMode(spec.envelopeMode())
                .maxLagNanos(spec.maxLagNanos())
                .sourceUnit(unit)
                .artifactGeneration(appService.diagnosticArtifactGeneration())
                .persistDerived(spec.persistDerived())
                .build();
        worker.submit(() -> {
            try {
                var result = appService.runAdvancedAnalytics(request);
                String text = appService.lastEngineeringAnalysisReport();
                javafx.application.Platform.runLater(() -> {
                    engineeringPane.advanced().show(result, text);
                    engineeringPane.showAnalysisReport(text);
                });
            } catch (RuntimeException ex) {
                javafx.application.Platform.runLater(() ->
                        engineeringPane.advanced().show(null,
                                ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            }
        });
    }

    private void exportAdvancedAnalytics() {
        if (appService == null || appService.lastAdvancedAnalytics() == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export advanced analytics CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        java.io.File file = chooser.showSaveDialog(graphCanvas == null ? null : graphCanvas.getScene().getWindow());
        if (file == null) {
            return;
        }
        var last = appService.lastAdvancedAnalytics();
        Path dest = file.toPath();
        if (last.spectrum() != null) {
            appService.exportAdvancedSpectrum(dest);
        } else if (last.psd() != null) {
            appService.exportAdvancedPsd(dest);
        } else if (last.lag() != null) {
            appService.exportAdvancedLag(dest);
        } else if (last.cycles() != null) {
            appService.exportAdvancedCycles(dest);
        } else if (last.derived() != null) {
            appService.exportAdvancedDerived(dest);
        }
        if (!last.peaks().isEmpty()) {
            appService.exportAdvancedPeaks(dest.resolveSibling(
                    dest.getFileName().toString().replace(".csv", "-peaks.csv")));
        }
    }

    private void saveAdvancedDerived() {
        if (appService == null) {
            return;
        }
        try {
            var result = appService.saveAdvancedDerived();
            engineeringPane.advanced().show(result,
                    appService.lastEngineeringAnalysisReport() + "\nDerived signal saved to Analysis Storage.");
        } catch (RuntimeException ex) {
            engineeringPane.advanced().show(null,
                    ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
    }

    private void applyAnalysisOverlays(com.stellar.platform.analysis.model.AnalysisResult result) {
        if (graphCanvas == null || result == null || !engineeringPane.overlaysVisible()) {
            if (graphCanvas != null) {
                graphCanvas.setAnalysisOverlays(List.of());
            }
            return;
        }
        List<AutomotiveSignalCanvas.AnalysisOverlay> overlays = result.findings().stream()
                .map(f -> new AutomotiveSignalCanvas.AnalysisOverlay(
                        f.startTimestampNanos(), f.endTimestampNanos(),
                        f.findingType().name().substring(0, Math.min(3, f.findingType().name().length()))))
                .toList();
        graphCanvas.setAnalysisOverlays(overlays);
        graphCanvas.setAnalysisOverlaysVisible(true);
    }

    private void runEngineeringDiagnostics(EngineeringDiagnosticsPane.RunSpec spec) {
        if (appService == null || spec == null) {
            return;
        }
        if (unknownOrderLayoutViewport()) {
            engineeringPane.diagnostics().showResult(null, UNKNOWN_ORDER_ANALYSIS_REFUSAL);
            return;
        }
        GraphInvestigationState inv = investigation();
        long viewportStart = inv == null ? start : inv.viewportStart();
        long viewportEnd = inv == null ? end : inv.viewportEnd();
        Long cursorANanos = inv == null ? cursorA : inv.cursorA();
        Long cursorBNanos = inv == null ? cursorB : inv.cursorB();
        com.stellar.platform.analysis.model.TimeRangeSource range;
        try {
            range = com.stellar.platform.analysis.model.TimeRangeSource.valueOf(spec.range());
        } catch (RuntimeException ex) {
            range = com.stellar.platform.analysis.model.TimeRangeSource.VIEWPORT;
        }
        com.stellar.platform.analysis.model.TimeWindow window =
                com.stellar.platform.analysis.model.TimeWindow.of(viewportStart, viewportEnd);
        if (range == com.stellar.platform.analysis.model.TimeRangeSource.CURSOR_A_TO_B
                && cursorANanos != null && cursorBNanos != null) {
            window = com.stellar.platform.analysis.model.TimeWindow.of(cursorANanos, cursorBNanos);
        } else if (range == com.stellar.platform.analysis.model.TimeRangeSource.FULL) {
            window = com.stellar.platform.analysis.model.TimeWindow.unbounded();
        }
        appService.persistDiagnosticBindings(spec.packId(), spec.bindings());
        appService.recordDiagnosticActivity(
                com.stellar.platform.storage.metadata.AuditEventType.DIAGNOSTICS_STARTED,
                spec.packId(), spec.range());
        var request = new com.stellar.platform.analysis.diagnostics.DiagnosticRunRequest(
                appService.currentProjectId(),
                null,
                engineeringPane.diagnostics().pack(),
                spec.enabledRuleIds(),
                range,
                window,
                spec.bindings(),
                appService.diagnosticArtifactGeneration(),
                "");
        worker.submit(() -> {
            try {
                var result = appService.runEngineeringDiagnostics(request);
                String report = appService.lastEngineeringDiagnosticsReport();
                javafx.application.Platform.runLater(() -> {
                    engineeringPane.diagnostics().showResult(result, report);
                    engineeringPane.diagnostics().setRunHistory(appService.diagnosticRunHistory());
                    applyDiagnosticOverlays(result);
                    var type = result.findings().isEmpty()
                            ? com.stellar.platform.storage.metadata.AuditEventType.DIAGNOSTICS_COMPLETED
                            : com.stellar.platform.storage.metadata.AuditEventType.DIAGNOSTICS_COMPLETED_WITH_FINDINGS;
                    appService.recordDiagnosticActivity(type, result.runId(),
                            result.findings().size() + " findings");
                });
            } catch (RuntimeException ex) {
                javafx.application.Platform.runLater(() -> {
                    engineeringPane.diagnostics().showResult(null,
                            ex.getMessage() == null ? ex.toString() : ex.getMessage());
                    appService.recordDiagnosticActivity(
                            com.stellar.platform.storage.metadata.AuditEventType.DIAGNOSTICS_FAILED,
                            spec.packId(), ex.getMessage());
                });
            }
        });
    }

    private void focusDiagnosticFinding(com.stellar.platform.analysis.diagnostics.DiagnosticFinding finding) {
        if (finding == null) {
            return;
        }
        var focus = com.stellar.platform.analysis.workflow.GraphFocusRange.around(finding);
        applyViewport(focus.startNanos(), focus.endNanos());
        applyDiagnosticOverlays(appService == null ? null : appService.lastEngineeringDiagnostics());
    }

    private void annotateDiagnosticFinding(com.stellar.platform.analysis.diagnostics.DiagnosticFinding finding) {
        if (appService != null && finding != null) {
            appService.createAnnotationFromFinding(finding);
        }
    }

    private void applyDiagnosticOverlays(com.stellar.platform.analysis.diagnostics.DiagnosticRunResult result) {
        if (graphCanvas == null || result == null || !engineeringPane.diagnostics().findingsVisible()) {
            return;
        }
        List<AutomotiveSignalCanvas.AnalysisOverlay> overlays = result.findings().stream()
                .map(f -> new AutomotiveSignalCanvas.AnalysisOverlay(
                        f.startTimestampNanos(), f.endTimestampNanos(),
                        f.severity().name().substring(0, Math.min(3, f.severity().name().length()))))
                .toList();
        graphCanvas.setAnalysisOverlays(overlays);
        graphCanvas.setAnalysisOverlaysVisible(true);
    }

    private void exportEngineeringDiagnostics() {
        if (appService == null || appService.lastEngineeringDiagnostics() == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export diagnostic summary CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        java.io.File file = chooser.showSaveDialog(graphCanvas == null ? null : graphCanvas.getScene().getWindow());
        if (file == null) {
            return;
        }
        appService.exportEngineeringDiagnostics(file.toPath());
    }

    private void exportDiagnosticRulePack() {
        if (appService == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export rule pack JSON");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        java.io.File file = chooser.showSaveDialog(graphCanvas == null ? null : graphCanvas.getScene().getWindow());
        if (file == null) {
            return;
        }
        appService.exportDiagnosticRulePack(file.toPath());
    }

    private void importDiagnosticRulePack() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Import local rule pack JSON");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        java.io.File file = chooser.showOpenDialog(graphCanvas == null ? null : graphCanvas.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            String json = java.nio.file.Files.readString(file.toPath());
            var preview = appService.previewDiagnosticPackImport(json);
            if (!preview.importable()) {
                String msg = preview.conflicts().isEmpty()
                        ? (preview.warnings().isEmpty() ? "Import rejected" : String.join("\n", preview.warnings()))
                        : String.join("\n", preview.conflicts());
                engineeringPane.diagnostics().showResult(null, "Import rejected:\n" + msg);
                return;
            }
            var pack = appService.importDiagnosticPack(json);
            engineeringPane.diagnostics().setPacks(appService.diagnosticPacks());
            engineeringPane.diagnostics().setPack(pack);
            engineeringPane.rules().setPacks(appService.engineeringDiagnostics().builtInPack(),
                    appService.projectCustomRulePack());
            engineeringPane.diagnostics().showResult(null,
                    "Imported " + pack.name() + " v" + pack.packVersion() + " (" + pack.rules().size() + " rules)");
        } catch (Exception ex) {
            engineeringPane.diagnostics().showResult(null,
                    "Import failed: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
        }
    }

    private void dryRunEngineeringDiagnostics(EngineeringDiagnosticsPane.RunSpec spec) {
        if (appService == null) {
            return;
        }
        if (unknownOrderLayoutViewport()) {
            engineeringPane.diagnostics().showResult(null, UNKNOWN_ORDER_ANALYSIS_REFUSAL);
            return;
        }
        refreshDiagnosticScopeLabel();
        var request = diagnosticRequest(spec);
        worker.submit(() -> {
            try {
                var result = appService.dryRunEngineeringDiagnostics(request);
                javafx.application.Platform.runLater(() ->
                        engineeringPane.diagnostics().showResult(null,
                                "Dry-run preview (not persisted)\nExpected findings: "
                                        + result.findings().size() + "\n"
                                        + result.message()));
            } catch (RuntimeException ex) {
                javafx.application.Platform.runLater(() ->
                        engineeringPane.diagnostics().showResult(null,
                                ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            }
        });
    }

    private com.stellar.platform.analysis.diagnostics.DiagnosticRunRequest diagnosticRequest(
            EngineeringDiagnosticsPane.RunSpec spec
    ) {
        GraphInvestigationState inv = appService == null ? null : appService.graphInvestigation();
        long viewportStart = inv == null ? start : inv.viewportStart();
        long viewportEnd = inv == null ? end : inv.viewportEnd();
        Long cursorANanos = inv == null ? cursorA : inv.cursorA();
        Long cursorBNanos = inv == null ? cursorB : inv.cursorB();
        com.stellar.platform.analysis.model.TimeRangeSource range;
        try {
            range = com.stellar.platform.analysis.model.TimeRangeSource.valueOf(spec.range());
        } catch (RuntimeException ex) {
            range = com.stellar.platform.analysis.model.TimeRangeSource.VIEWPORT;
        }
        com.stellar.platform.analysis.model.TimeWindow window =
                com.stellar.platform.analysis.model.TimeWindow.of(viewportStart, viewportEnd);
        if (range == com.stellar.platform.analysis.model.TimeRangeSource.CURSOR_A_TO_B
                && cursorANanos != null && cursorBNanos != null) {
            window = com.stellar.platform.analysis.model.TimeWindow.of(cursorANanos, cursorBNanos);
        } else if (range == com.stellar.platform.analysis.model.TimeRangeSource.FULL) {
            window = com.stellar.platform.analysis.model.TimeWindow.unbounded();
        }
        return new com.stellar.platform.analysis.diagnostics.DiagnosticRunRequest(
                appService.currentProjectId(),
                null,
                engineeringPane.diagnostics().pack(),
                spec.enabledRuleIds(),
                range,
                window,
                spec.bindings(),
                appService.diagnosticArtifactGeneration(),
                "");
    }

    private void refreshDiagnosticScopeLabel() {
        if (engineeringPane == null) {
            return;
        }
        GraphInvestigationState inv = appService == null ? null : appService.graphInvestigation();
        long a = inv == null ? start : inv.viewportStart();
        long b = inv == null ? end : inv.viewportEnd();
        String label = String.format(java.util.Locale.ROOT, "Viewport %.3f s – %.3f s", a / 1e9, b / 1e9);
        if (inv != null && inv.cursorA() != null && inv.cursorB() != null) {
            label += String.format(java.util.Locale.ROOT, "   Cursor A→B %.3f s – %.3f s",
                    inv.cursorA() / 1e9, inv.cursorB() / 1e9);
        }
        engineeringPane.diagnostics().setScopeWindow(
                com.stellar.platform.analysis.model.TimeWindow.of(a, b), label);
    }

    private void bindDiagnosticRole(String role, com.stellar.platform.domain.catalog.SignalCatalogKey key) {
        if (appService == null || role == null || key == null) {
            return;
        }
        engineeringPane.diagnostics().bindRole(role, key);
        appService.persistDiagnosticBindings(
                engineeringPane.diagnostics().pack().packId(), engineeringPane.diagnostics().bindings());
        appService.noteLogicalChange();
    }

    private void saveDiagnosticReview(
            com.stellar.platform.analysis.workflow.FindingReviewState state,
            String note
    ) {
        if (appService == null) {
            return;
        }
        engineeringPane.diagnostics().selectedFinding().ifPresent(f -> {
            appService.saveDiagnosticFindingReview(f.findingId(), state, note);
            engineeringPane.diagnostics().setReviews(appService.loadDiagnosticReviews());
        });
    }

    private void toggleDiagnosticOverlays(boolean visible) {
        if (graphCanvas == null) {
            return;
        }
        graphCanvas.setAnalysisOverlaysVisible(visible);
        if (visible) {
            applyDiagnosticOverlays(appService == null ? null : appService.lastEngineeringDiagnostics());
        }
    }

    private void loadHistoricDiagnosticRun(String runId) {
        if (appService == null || runId == null) {
            return;
        }
        var restored = appService.loadDiagnosticRun(runId);
        if (restored != null) {
            engineeringPane.diagnostics().showResult(restored, appService.lastEngineeringDiagnosticsReport());
            applyDiagnosticOverlays(restored);
        }
    }

    private void compareHistoricDiagnosticRun(String runId) {
        if (appService == null || runId == null) {
            return;
        }
        var historic = appService.loadDiagnosticRun(runId);
        var current = appService.lastEngineeringDiagnostics();
        var cmp = com.stellar.platform.analysis.workflow.RunComparison.compare(historic, current);
        engineeringPane.diagnostics().showResult(current,
                (cmp.versionsComparable() ? "" : cmp.warning() + "\n")
                        + "Persistent " + cmp.persistent().size()
                        + "   Resolved " + cmp.resolved().size()
                        + "   New " + cmp.added().size());
    }

    private void saveDiagnosticPreference(String ruleId, boolean enabled) {
        if (appService == null) {
            return;
        }
        appService.saveDiagnosticRulePreference(engineeringPane.diagnostics().pack().packId(), ruleId, enabled);
    }

    private void saveCustomDiagnosticRule(com.stellar.platform.analysis.rules.DiagnosticRuleDefinition rule) {
        if (appService == null) {
            return;
        }
        appService.saveCustomDiagnosticRule(rule);
        engineeringPane.rules().setPacks(appService.engineeringDiagnostics().builtInPack(),
                appService.projectCustomRulePack());
        engineeringPane.diagnostics().setPacks(appService.diagnosticPacks());
    }

    private void deleteCustomDiagnosticRule(String ruleId) {
        if (appService == null) {
            return;
        }
        appService.deleteCustomDiagnosticRule(ruleId);
        engineeringPane.rules().setPacks(appService.engineeringDiagnostics().builtInPack(),
                appService.projectCustomRulePack());
        engineeringPane.diagnostics().setPacks(appService.diagnosticPacks());
    }

    private void showDiagnosticEvidenceSignals(
            com.stellar.platform.analysis.diagnostics.DiagnosticFinding finding
    ) {
        if (appService == null || finding == null || finding.signalKeys().isEmpty()) {
            return;
        }
        appService.signalsModel().addToWorkspace(finding.signalKeys());
    }

    private void exportEngineeringAnalysis() {
        if (appService == null || appService.lastEngineeringAnalysis() == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export analysis summary CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        java.io.File file = chooser.showSaveDialog(graphCanvas == null ? null : graphCanvas.getScene().getWindow());
        if (file == null) {
            return;
        }
        appService.exportEngineeringAnalysis(file.toPath());
    }

    private void configureAnalysisToolbar(){
        // Vector graphics avoid missing-font/ellipsis glyphs on Windows tester machines.
        setVectorIcon(panLeftButton, "M14 4 L6 12 L14 20 M6 12 H20");
        setVectorIcon(panRightButton, "M10 4 L18 12 L10 20 M18 12 H4");
        setVectorIcon(fitButton, "M4 9 V4 H9 M15 4 H20 V9 M20 15 V20 H15 M9 20 H4 V15");
        setVectorIcon(zoomInButton, "M10 4 A6 6 0 1 0 10 16 A6 6 0 1 0 10 4 M14.5 14.5 L20 20 M10 7 V13 M7 10 H13");
        setVectorIcon(zoomOutButton, "M10 4 A6 6 0 1 0 10 16 A6 6 0 1 0 10 4 M14.5 14.5 L20 20 M7 10 H13");
        setVectorIcon(snapshotButton, "M5 7 H8 L9.5 5 H14.5 L16 7 H19 V18 H5 Z M12 9 A3 3 0 1 0 12 15 A3 3 0 1 0 12 9");
        setVectorIcon(resetViewButton, "M19 12 A7 7 0 1 1 14.2 6.2 M14 3 V7 H18");
        setVectorIcon(focusGraphButton, "M4 6 H20 V15 H4 Z M9 18 H15");
        setVectorIcon(addEventButton, "M7 3 H17 V20 L12 16 L7 20 Z M12 7 V13 M9 10 H15");
        setVectorIcon(toolbarMoreButton, "M6.2 12 A1.6 1.6 0 1 1 6 12 M12.2 12 A1.6 1.6 0 1 1 12 12 M18.2 12 A1.6 1.6 0 1 1 18 12");
        if(panLeftButton!=null) panLeftButton.setAccessibleText("Back");
        if(panRightButton!=null) panRightButton.setAccessibleText("Forward");
        if(toolbarMoreButton!=null) toolbarMoreButton.setAccessibleText("More");
        relayoutGraphToolbar();
    }

    private static void setVectorIcon(ButtonBase button,String path){
        if(button==null)return;
        SVGPath icon=new SVGPath(); icon.setContent(path); icon.getStyleClass().add("graph-tool-icon");
        button.setGraphic(icon); button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    }

    private void configureStatisticsTables(){
        if(summaryStatsTable!=null){
            summaryStatsTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
            bindCol(summarySignalColumn, SummaryStatsRow::signal);
            bindCol(summaryUnitColumn, SummaryStatsRow::unit);
            bindCol(summarySamplesColumn, SummaryStatsRow::samples);
            bindCol(summaryMinColumn, SummaryStatsRow::min);
            bindCol(summaryMaxColumn, SummaryStatsRow::max);
            bindCol(summaryMeanColumn, SummaryStatsRow::mean);
            bindCol(summaryRmsColumn, SummaryStatsRow::rms);
            summaryStatsTable.setPlaceholder(new Label("Select signals to see viewport statistics"));
            tipCol(summarySignalColumn, "Signal name");
            tipCol(summaryUnitColumn, "Engineering unit");
            tipCol(summarySamplesColumn, "Samples represented in the current viewport");
            tipCol(summaryMinColumn, "Minimum value in viewport");
            tipCol(summaryMaxColumn, "Maximum value in viewport");
            tipCol(summaryMeanColumn, "Average (arithmetic mean)");
            tipCol(summaryRmsColumn, "Root mean square");
        }
        if(cursorCompareTable!=null){
            cursorCompareTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
            bindCol(compareSignalColumn, CursorCompareRow::signal);
            bindCol(compareATimeColumn, CursorCompareRow::aTime);
            bindCol(compareAValueColumn, CursorCompareRow::aValue);
            bindCol(compareBTimeColumn, CursorCompareRow::bTime);
            bindCol(compareBValueColumn, CursorCompareRow::bValue);
            bindCol(compareDeltaTimeColumn, CursorCompareRow::deltaTime);
            bindCol(compareDeltaValueColumn, CursorCompareRow::deltaValue);
            cursorCompareTable.setPlaceholder(new Label("Place Cursor A or Cursor B to compare values."));
            tipCol(compareSignalColumn, "Signal name");
            tipCol(compareATimeColumn, "Cursor A timestamp");
            tipCol(compareAValueColumn, "Value at Cursor A");
            tipCol(compareBTimeColumn, "Cursor B timestamp");
            tipCol(compareBValueColumn, "Value at Cursor B");
            tipCol(compareDeltaTimeColumn, "Time difference between Cursor A and Cursor B");
            tipCol(compareDeltaValueColumn, "Value difference (B − A)");
        }
    }

    private static <T> void tipCol(TableColumn<T,?> column, String tip){
        if(column==null) return;
        Label header=new Label(column.getText());
        header.setTooltip(new Tooltip(tip));
        column.setGraphic(header);
        column.setText(null);
    }

    private static <T> void bindCol(TableColumn<T,String> column, java.util.function.Function<T,String> getter){
        if(column==null) return;
        column.setCellValueFactory(c->new javafx.beans.property.SimpleStringProperty(getter.apply(c.getValue())));
    }

    private void updateInspectorToggleChrome(){
        boolean open=isInspectorOpen();
        styleInspectorToggle(inspectorHeaderToggleButton, open);
        if(inspectorRailButton!=null){
            styleInspectorToggle(inspectorRailButton, false);
            inspectorRailButton.setAccessibleText("Show Inspector");
            Tooltip tip=inspectorRailButton.getTooltip();
            if(tip==null){
                tip=new Tooltip();
                inspectorRailButton.setTooltip(tip);
            }
            tip.setText("Show Inspector · restores the panel without leaving Full Screen");
        }
    }

    private void styleInspectorToggle(Button button, boolean open){
        if(button==null) return;
        button.setText(open?">":"<");
        button.setGraphic(null);
        try{
            javafx.scene.Node icon=open?StellarIcons.expandRight():StellarIcons.collapseLeft();
            button.setGraphic(icon);
            button.setText("");
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }catch(RuntimeException ignored){
            // Text chevron remains as accessible fallback when icon construction fails.
        }
        Tooltip tip=button.getTooltip();
        if(tip==null){
            tip=new Tooltip();
            button.setTooltip(tip);
        }
        tip.setText(open
                ?"Hide Inspector · keeps focus and analysis; expands the graph"
                :"Show Inspector · restores the panel without leaving Full Screen");
        button.setAccessibleText(open?"Hide Inspector":"Show Inspector");
    }

    private static double clampInspectorWidth(double width){
        return Math.max(INSPECTOR_MIN_WIDTH, Math.min(INSPECTOR_MAX_WIDTH, width));
    }

    private void configureLayoutSelector(){
        if(stackedLayoutButton==null||overlayLayoutButton==null)return;
        ToggleGroup group=new ToggleGroup();
        stackedLayoutButton.setToggleGroup(group);
        overlayLayoutButton.setToggleGroup(group);
        // Stacked tracks is the engineering-safe default: mixed units/ranges stay independently readable.
        stackedLayoutButton.setSelected(true);
        graphCanvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.STACKED);
        updateLayoutDependentControls(true);
        group.selectedToggleProperty().addListener((obs,oldToggle,newToggle)->{
            // Keep one mode selected at all times; clicking the active segment must not leave layout undefined.
            if(newToggle==null){
                if(oldToggle!=null)oldToggle.setSelected(true);
                return;
            }
            if(applyingLayoutChrome){
                return;
            }
            boolean stacked=newToggle==stackedLayoutButton;
            onLayoutModeChanged(stacked);
        });
    }

    /**
     * Layout mode changes composition only. A valid shared X viewport is preserved exactly;
     * only an invalid/degenerate viewport is recovered from the selected-visible full range.
     */
    private void onLayoutModeChanged(boolean stacked){
        if(graphCanvas!=null){
            graphCanvas.setLayoutMode(stacked
                    ?AutomotiveSignalCanvas.LayoutMode.STACKED
                    :AutomotiveSignalCanvas.LayoutMode.OVERLAY_MULTI_AXIS);
        }
        updateLayoutDependentControls(stacked);
        GraphInvestigationState inv=investigation();
        if(inv!=null) inv.setLayoutMode(stacked?GraphInvestigationState.STACKED:GraphInvestigationState.OVERLAY);
        rebuildSelectedSignalCards();
        persistInvestigationSoon();
        refreshFullAnalysisRange();
        ensureViewportValidOrRecover("LAYOUT_MODE");
    }

    private void updateLayoutDependentControls(boolean stacked){
        // Track height has meaning only for stacked tracks. Hiding it avoids a dead/disabled control in Overlay.
        setManagedVisible(trackDensityBox,stacked);
    }
    @FXML private void onTrackDensityChanged(){
        if(trackDensity==null)return; int i=trackDensity.getSelectionModel().getSelectedIndex();
        graphCanvas.setTrackDensity(i==1?AutomotiveSignalCanvas.TrackDensity.COMPACT:i==2?AutomotiveSignalCanvas.TrackDensity.COMFORTABLE:AutomotiveSignalCanvas.TrackDensity.AUTO);
        requestViewport();
    }


    @FXML private void onToggleFocusGraph(){
        focusGraph=!focusGraph;
        if(shell!=null) shell.setGraphAnalysisFullScreen(focusGraph);
        if(focusGraphButton!=null){
            focusGraphButton.setText(focusGraph?"Exit Full Screen":"Full Screen");
            if(focusGraphButton.getTooltip()!=null){
                focusGraphButton.getTooltip().setText(focusGraph
                        ?"Exit Full Screen · Esc also exits Focus Mode."
                        :"Full Screen · hide Signals, Inspector and project chrome. Esc exits.");
            }
        }
        if(focusGraph){
            inspectorOpenBeforeFullScreen=isInspectorOpen();
            inspectorToggledDuringFullScreen=false;
            chipStripVisibleBeforeFocus=selectedSignalCards==null||selectedSignalCards.isManaged();
            fileStripVisibleBeforeFocus=graphFileStrip==null||graphFileStrip.isManaged();
            hideSignalPanelForFocus();
            setInspectorVisible(false, false);
            applyFocusModeChrome(true);
        }else{
            applyFocusModeChrome(false);
            restoreSignalPanelAfterFocus();
            boolean restore=inspectorToggledDuringFullScreen
                    ? isInspectorOpen()
                    : (inspectorOpenBeforeFullScreen==null||inspectorOpenBeforeFullScreen);
            setInspectorVisible(restore, false);
            inspectorOpenBeforeFullScreen=null;
            inspectorToggledDuringFullScreen=false;
        }
        scheduleGraphLayoutRefresh(focusGraph?"fullscreen-enter":"fullscreen-exit");
    }

    /**
     * Parks the left Signals explorer without disposing it. Removing it from the SplitPane (rather
     * than only hiding it) is what gives the canvas the leftover width; a hidden SplitPane item
     * would still occupy a divider slot.
     */
    private void hideSignalPanelForFocus(){
        if(signalPanel==null||graphWorkspaceSplit==null){
            return;
        }
        if(graphWorkspaceSplit.getItems().contains(signalPanel)){
            double[] positions=graphWorkspaceSplit.getDividerPositions();
            if(positions.length>0){
                signalPanelDividerBeforeFullScreen=positions[0];
            }
            graphWorkspaceSplit.getItems().remove(signalPanel);
        }
        setManagedVisible(signalPanel, false);
    }

    private void restoreSignalPanelAfterFocus(){
        if(signalPanel==null||graphWorkspaceSplit==null){
            return;
        }
        if(signalPanelUserCollapsed){
            setSignalPanelCollapsed(true);
            return;
        }
        setManagedVisible(signalPanel, true);
        if(!graphWorkspaceSplit.getItems().contains(signalPanel)){
            graphWorkspaceSplit.getItems().add(0, signalPanel);
            graphWorkspaceSplit.setDividerPositions(signalPanelDividerBeforeFullScreen);
        }
    }

    private boolean signalPanelUserCollapsed;
    private double signalPanelDividerBeforeCollapse=0.28;
    private javafx.scene.layout.VBox signalPanelRail;

    @FXML private void onToggleSignalPanel(){
        setSignalPanelCollapsed(!signalPanelUserCollapsed);
    }

    /**
     * Layout-only. The explorer node stays alive and no viewport query is issued.
     */
    private void setSignalPanelCollapsed(boolean collapsed){
        if(signalPanel==null||graphWorkspaceSplit==null||graphMainRow==null){
            return;
        }
        signalPanelUserCollapsed=collapsed;
        if(collapsed){
            if(graphWorkspaceSplit.getItems().contains(signalPanel)){
                double[] positions=graphWorkspaceSplit.getDividerPositions();
                if(positions.length>0){
                    signalPanelDividerBeforeCollapse=positions[0];
                }
                graphWorkspaceSplit.getItems().remove(signalPanel);
            }
            ensureSignalPanelRail();
            if(!graphMainRow.getChildren().contains(signalPanelRail)){
                graphMainRow.getChildren().add(0, signalPanelRail);
            }
        }else{
            if(signalPanelRail!=null){
                graphMainRow.getChildren().remove(signalPanelRail);
            }
            setManagedVisible(signalPanel, true);
            if(!graphWorkspaceSplit.getItems().contains(signalPanel)){
                graphWorkspaceSplit.getItems().add(0, signalPanel);
                graphWorkspaceSplit.setDividerPositions(signalPanelDividerBeforeCollapse);
            }
        }
    }

    private void ensureSignalPanelRail(){
        if(signalPanelRail!=null) return;
        Button expand=new Button("\u203A");
        expand.getStyleClass().addAll("secondary-button","compact-button","signal-panel-collapse-button");
        expand.setTooltip(new Tooltip("Expand signals"));
        expand.setOnAction(e->setSignalPanelCollapsed(false));
        signalPanelRail=new javafx.scene.layout.VBox(expand);
        signalPanelRail.getStyleClass().addAll("signal-panel","signal-panel-collapsed","signal-panel-rail");
        signalPanelRail.setAlignment(Pos.TOP_CENTER);
        signalPanelRail.setMinWidth(36);
        signalPanelRail.setPrefWidth(36);
        signalPanelRail.setMaxWidth(36);
    }

    void collapseSignalPanelForTest(){ setSignalPanelCollapsed(true); }
    void expandSignalPanelForTest(){ setSignalPanelCollapsed(false); }
    boolean signalPanelCollapsedForTest(){ return signalPanelUserCollapsed; }
    boolean signalPanelAttachedForTest(){
        return graphWorkspaceSplit!=null && graphWorkspaceSplit.getItems().contains(signalPanel);
    }
    boolean signalPanelRailVisibleForTest(){
        return signalPanelRail!=null && graphMainRow!=null && graphMainRow.getChildren().contains(signalPanelRail);
    }
    javafx.scene.control.SplitPane graphWorkspaceSplitForTest(){ return graphWorkspaceSplit; }
    javafx.scene.layout.VBox graphCanvasColumnForTest(){
        if(graphWorkspaceSplit==null) return null;
        for(javafx.scene.Node item:graphWorkspaceSplit.getItems()){
            if(item!=signalPanel && item instanceof javafx.scene.layout.VBox box) return box;
        }
        return null;
    }

    /**
     * Explicit Inspector Hide/Show. Never clears focusedSignal, tab selection, or metadata.
     * Layout only: panel is a sibling column (not a SplitPane item) so width returns to Graph.
     */
    @FXML private void onToggleInspector(){
        boolean open=!isInspectorOpen();
        setInspectorVisible(open, true);
        if(focusGraph){
            inspectorToggledDuringFullScreen=true;
        }
        scheduleGraphLayoutRefresh(open?"inspector-show":"inspector-hide");
    }

    private boolean isInspectorOpen(){
        return inspectorPanel!=null && inspectorPanel.isVisible() && inspectorPanel.isManaged();
    }

    private void setInspectorVisible(boolean open, boolean userInitiated){
        if(open && inspectorPanel!=null && isInspectorOpen()){
            // Already open — keep remembered width.
            applyInspectorChrome(true);
            return;
        }
        if(!open && inspectorPanel!=null && isInspectorOpen()){
            double w=inspectorPanel.getWidth();
            if(w>=INSPECTOR_MIN_WIDTH){
                inspectorExpandedWidth=clampInspectorWidth(w);
            }
        }
        applyInspectorChrome(open);
        if(userInitiated){
            inspectorVisiblePreference=open;
            GraphInvestigationState inv=investigation();
            if(inv!=null){
                inv.setInspectorVisible(open);
                persistInvestigationSoon();
            }
            LOG.log(System.Logger.Level.DEBUG, ()->"Graph Inspector visible="+open+" focusGraph="+focusGraph
                    +" focused="+(focusedExplorerKey==null?"none":focusedExplorerKey.signalName()));
        }
    }

    private void applyInspectorChrome(boolean open){
        if(inspectorPanel!=null){
            if(open){
                double width=clampInspectorWidth(inspectorExpandedWidth<=0?INSPECTOR_PREF_WIDTH:inspectorExpandedWidth);
                inspectorPanel.setMinWidth(INSPECTOR_MIN_WIDTH);
                inspectorPanel.setPrefWidth(width);
                inspectorPanel.setMaxWidth(INSPECTOR_MAX_WIDTH);
            }
            setManagedVisible(inspectorPanel, open);
        }
        // Reveal rail only when collapsed and not in Focus Mode — Focus Mode owns the full width.
        setManagedVisible(inspectorRail, !open && inspectorPanel!=null && !focusGraph);
        updateInspectorToggleChrome();
    }

    /**
     * After JavaFX layout settles: resize canvas presentation only. Does not reset viewport/zoom.
     */
    private void scheduleGraphLayoutRefresh(String reason){
        Platform.runLater(()-> Platform.runLater(()->{
            if(disposed) return;
            if(graphMainRow!=null) graphMainRow.requestLayout();
            if(graphWorkspaceSplit!=null) graphWorkspaceSplit.requestLayout();
            rebuildSelectedSignalCards();
            syncWaveformCanvasWindow();
            // Re-render at the current start/end — presentation resize only.
            requestViewport();
            if(mapRoutePane!=null) mapRoutePane.notifyLayoutChanged(reason==null?"layout":reason);
        }));
    }

    /**
     * The waveform scroll pane owns the logical stack. The canvas texture stays the visible
     * window so a long recording or many tracks cannot allocate one backing store for all of it.
     */
    private void syncWaveformCanvasWindow() {
        if (disposed || waveformScroll == null || graphCanvas == null) {
            return;
        }
        javafx.geometry.Bounds bounds = waveformScroll.getViewportBounds();
        double viewportH = bounds == null ? 0 : bounds.getHeight();
        if (viewportH > 1) {
            graphCanvas.setViewportFloorHeight(Math.max(320, viewportH - 2));
        }
        double contentH = graphCanvas.getHeight();
        double range = Math.max(0, contentH - Math.max(0, viewportH));
        graphCanvas.setVisibleWindow(viewportH, waveformScroll.getVvalue() * range);
        if(adjustingTrackWindow || residentQuery==null || selectedSignalKeys.isEmpty()) return;
        TrackQueryWindow.Range next=currentTrackWindow(selectedKeys().size());
        if(!next.equals(residentQuery)) requestViewport();
    }

    private static void setManagedVisible(Node node,boolean visible){ if(node!=null){node.setManaged(visible);node.setVisible(visible);} }
    @FXML private void onSnapshot(){
        captureNamedSnapshot();
    }

    @FXML private void onCreateInvestigation(){
        if(appService==null) return;
        java.util.Optional<String> name=promptRequiredName(
                "Create Investigation", "Investigation Name *", "Enter investigation name", "Create");
        if(name.isEmpty()) return;
        createNamedInvestigation(name.get());
    }

    /** File Focus calculations stay on that recording. Logical project keys would mix overlapping clocks. */
    private java.util.List<SignalCatalogKey> physicalKeysForFileFocus(java.util.List<SignalCatalogKey> keys){
        if(!fileFocusActive || appService==null || keys==null || keys.isEmpty()) return keys==null?java.util.List.of():keys;
        String measurementId=focusedMeasurementId();
        if(measurementId==null || measurementId.isBlank()) return keys;
        var store=appService.signalsModel().catalogStore();
        if(!(store instanceof com.stellar.platform.processing.catalog.ProjectMergedSignalStore merged)) return keys;
        java.util.ArrayList<SignalCatalogKey> physical=new java.util.ArrayList<>();
        for(SignalCatalogKey key:keys){
            merged.physicalKeyFor(key, measurementId).ifPresent(physical::add);
        }
        return java.util.List.copyOf(physical);
    }

    @FXML private void onAddViewToEvidence(){
        if (mapTabActive()) {
            addRouteMapToInvestigation(null);
            return;
        }
        captureGraphSnapshot("Graph snapshot");
    }

    @FXML private void onSetInvestigationRange(){
        if(appService==null)return;
        var svc=appService.engineeringInvestigations();
        var current=svc.find(appService.metadataStore(), svc.activeInvestigationId()).orElse(null);
        if(current==null){
            if(statusLabel!=null) setGraphStatus("Create or open an investigation first.");
            return;
        }
        appService.setInvestigationRangeFromGraph(current);
        if(statusLabel!=null) setGraphStatus("Investigation range set from current view.");
    }

    @FXML private void onCaptureInvestigationSnapshot(){
        captureNamedSnapshot();
    }

    private void captureNamedSnapshot(){
        if(appService==null) return;
        if(!mapTabActive() && graphCanvas==null) return;
        java.util.Optional<String> name=promptRequiredName(
                "Capture Snapshot", "Snapshot Name *", "Enter snapshot name", "Capture");
        if(name.isEmpty()) return;
        if(mapTabActive()){
            addRouteMapToInvestigation(name.get());
            return;
        }
        captureGraphSnapshot(name.get());
    }

    private void createNamedInvestigation(String name){
        if(appService==null || validUserName(name)==null) return;
        try{
            var created=appService.createInvestigation(validUserName(name), "", "CURRENT", true);
            if(fileFocusActive){
                java.util.List<SignalCatalogKey> physical=physicalKeysForFileFocus(
                        appService.signalsModel().workspaceKeys());
                if(!physical.isEmpty()){
                    java.util.List<String> blobs=new java.util.ArrayList<>();
                    for(SignalCatalogKey key:physical){
                        blobs.add(com.stellar.platform.desktop.presentation.GraphInvestigationCodec.encodeKey(key));
                    }
                    created=appService.saveInvestigation(created.toBuilder().signalKeyBlobs(blobs).build());
                }
            }
            if(statusLabel!=null) setGraphStatus("Created investigation: "+created.name());
        }catch(RuntimeException ex){
            if(statusLabel!=null) setGraphStatus("Could not create investigation: "+human(ex));
        }
    }

    private void captureGraphSnapshot(String caption){
        if(appService==null||graphCanvas==null) return;
        String name=validUserName(caption);
        if(name==null && !"Graph snapshot".equals(caption)) return;
        try{
            Node target=graphCanvas.getParent()!=null?graphCanvas.getParent():graphCanvas;
            WritableImage image=target.snapshot(new SnapshotParameters(),null);
            java.awt.image.BufferedImage buf=SwingFXUtils.fromFXImage(image,null);
            java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();
            ImageIO.write(buf,"png",out);
            appService.attachGraphSnapshot(out.toByteArray(), name==null ? caption : name);
            if(statusLabel!=null) setGraphStatus("Graph snapshot added to investigation evidence.");
        }catch(Exception ex){
            if(statusLabel!=null) setGraphStatus("Graph snapshot failed: "+human(ex));
        }
    }

    private java.util.Optional<String> promptRequiredName(
            String title, String fieldLabel, String placeholder, String confirmText){
        Dialog<ButtonType> dialog=buildRequiredNameDialog(title, fieldLabel, placeholder, confirmText);
        java.util.Optional<ButtonType> result=dialog.showAndWait();
        if(result.isEmpty() || result.get().getButtonData()!=ButtonBar.ButtonData.OK_DONE) return java.util.Optional.empty();
        return java.util.Optional.ofNullable(validUserName(requiredNameField(dialog).getText()));
    }

    private Dialog<ButtonType> buildRequiredNameDialog(
            String title, String fieldLabel, String placeholder, String confirmText){
        Dialog<ButtonType> dialog=new Dialog<>();
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        ButtonType confirm=new ButtonType(confirmText, ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CANCEL, confirm);
        TextField name=new TextField("");
        name.setPromptText(placeholder);
        Label error=new Label();
        error.getStyleClass().add("form-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);
        dialog.getDialogPane().setContent(new VBox(8, new Label(fieldLabel), name, error));
        Button confirmButton=(Button)dialog.getDialogPane().lookupButton(confirm);
        if(confirmButton!=null){
            confirmButton.setDisable(true);
            confirmButton.addEventFilter(javafx.event.ActionEvent.ACTION, event->{
                if(validUserName(name.getText())==null){
                    showNameError(error, fieldLabel);
                    event.consume();
                }
            });
        }
        name.textProperty().addListener((obs, previous, value)->{
            boolean valid=validUserName(value)!=null;
            if(confirmButton!=null) confirmButton.setDisable(!valid);
            if(valid){
                error.setVisible(false);
                error.setManaged(false);
            }else if(value!=null && !value.isEmpty()){
                showNameError(error, fieldLabel);
            }else{
                error.setVisible(false);
                error.setManaged(false);
            }
        });
        return dialog;
    }

    private static void showNameError(Label error, String fieldLabel){
        String label=fieldLabel==null? "Name" : fieldLabel.replace("*", "").strip();
        error.setText(label+" must not be empty.");
        error.setVisible(true);
        error.setManaged(true);
    }

    private static String validUserName(String raw){
        if(raw==null) return null;
        String trimmed=raw.trim();
        return trimmed.isEmpty()? null : trimmed;
    }

    private static TextField requiredNameField(Dialog<ButtonType> dialog){
        if(dialog!=null && dialog.getDialogPane().getContent() instanceof VBox body){
            for(Node node: body.getChildren()){
                if(node instanceof TextField field) return field;
            }
        }
        return new TextField("");
    }

    Dialog<ButtonType> investigationNameDialogForTest(){
        return buildRequiredNameDialog("Create Investigation", "Investigation Name *", "Enter investigation name", "Create");
    }
    Dialog<ButtonType> snapshotNameDialogForTest(){
        return buildRequiredNameDialog("Capture Snapshot", "Snapshot Name *", "Enter snapshot name", "Capture");
    }
    String requiredNameTextForTest(Dialog<ButtonType> dialog){ return requiredNameField(dialog).getText(); }
    String requiredNamePromptForTest(Dialog<ButtonType> dialog){ return requiredNameField(dialog).getPromptText(); }
    void setRequiredNameTextForTest(Dialog<ButtonType> dialog, String text){ requiredNameField(dialog).setText(text); }
    boolean requiredNameConfirmDisabledForTest(Dialog<ButtonType> dialog){
        if(dialog==null) return true;
        for(ButtonType type: dialog.getDialogPane().getButtonTypes()){
            if(type.getButtonData()==ButtonBar.ButtonData.OK_DONE){
                Node node=dialog.getDialogPane().lookupButton(type);
                return !(node instanceof Button button) || button.isDisabled();
            }
        }
        return true;
    }
    String requiredNameErrorForTest(Dialog<ButtonType> dialog){
        if(dialog!=null && dialog.getDialogPane().getContent() instanceof VBox body){
            for(Node node: body.getChildren()){
                if(node instanceof Label label && label.getStyleClass().contains("form-error") && label.isVisible()){
                    return label.getText();
                }
            }
        }
        return "";
    }
    boolean commitCreateInvestigationForTest(String name){
        String valid=validUserName(name);
        if(valid==null || appService==null) return false;
        int before=appService.engineeringInvestigations().list(appService.metadataStore(), appService.currentProjectId()).size();
        createNamedInvestigation(valid);
        int after=appService.engineeringInvestigations().list(appService.metadataStore(), appService.currentProjectId()).size();
        return after==before+1;
    }
    java.util.List<String> toolbarMoreItemsForTest(){
        if(toolbarMoreButton==null) return java.util.List.of();
        java.util.ArrayList<String> labels=new java.util.ArrayList<>();
        for(javafx.scene.control.MenuItem item: toolbarMoreButton.getItems()){
            if(item.getText()!=null) labels.add(item.getText());
        }
        return labels;
    }
    boolean commitCaptureSnapshotForTest(String name){
        String valid=validUserName(name);
        if(valid==null || appService==null) return false;
        var before=appService.engineeringInvestigations().find(
                appService.metadataStore(), appService.engineeringInvestigations().activeInvestigationId());
        int count=before.map(inv->inv.evidence().size()).orElse(0);
        captureGraphSnapshot(valid);
        var after=appService.engineeringInvestigations().find(
                appService.metadataStore(), appService.engineeringInvestigations().activeInvestigationId());
        return after.isPresent() && after.get().evidence().size()==count+1;
    }

    private boolean mapTabActive() {
        return analysisTabs != null
                && analysisTabs.getSelectionModel().getSelectedIndex() == MAP_ROUTE_TAB_INDEX;
    }

    private void addRouteMapToInvestigation(String caption) {
        if (appService == null || mapRoutePane == null) {
            return;
        }
        mapRoutePane.captureForReport(capture -> {
            if (!capture.hasRoute()) {
                if (statusLabel != null) {
                    setGraphStatus("No route is available to add.");
                }
                return;
            }
            try {
                appService.attachRouteMap(capture.png(), capture.facts(), caption);
                if (statusLabel != null) {
                    setGraphStatus(capture.imageCaptured()
                            ? "Route map added to investigation evidence."
                            : "Route evidence added. Map preview unavailable for this report.");
                }
            } catch (Exception ex) {
                if (statusLabel != null) {
                    setGraphStatus("Route map was not added: " + human(ex));
                }
            }
        });
    }

    private void addLastAnalysisToInvestigation(){
        if(appService==null)return;
        var saved=appService.attachLastAnalysisToInvestigation();
        if(statusLabel!=null) setGraphStatus(saved==null
                ?"Create or open an investigation first."
                :"Analysis result added to investigation evidence.");
    }

    private void addLastAdvancedToInvestigation(){
        if(appService==null)return;
        var saved=appService.attachLastAdvancedToInvestigation();
        if(statusLabel!=null) setGraphStatus(saved==null
                ?"Create or open an investigation first."
                :"Frequency result added to investigation evidence.");
    }

    private void addFindingToInvestigation(com.stellar.platform.analysis.diagnostics.DiagnosticFinding finding){
        if(appService==null)return;
        var saved=appService.attachFindingToInvestigation(finding);
        if(statusLabel!=null) setGraphStatus(saved==null
                ?"Create or open an investigation first."
                :"Diagnostic finding added to investigation evidence.");
    }

    private static String human(Exception ex){
        String m=ex.getMessage();
        return m==null||m.isBlank()?"Unexpected error":m;
    }
    @FXML private void onSearchChanged(){
        explorerSearchQuery=signalSearch==null||signalSearch.getText()==null?"":signalSearch.getText();
        applySignalFilter();
    }
    @FXML private void onExplorerFilterAll(){ setExplorerFilter(ExplorerFilter.ALL); }
    @FXML private void onExplorerFilterRecommended(){ setExplorerFilter(ExplorerFilter.RECOMMENDED); }
    @FXML private void onExplorerFilterSelected(){ setExplorerFilter(ExplorerFilter.SELECTED); }
    @FXML private void onExplorerFilterDbc(){ setExplorerFilter(ExplorerFilter.DBC); }
    @FXML private void onExplorerFilterNative(){ setExplorerFilter(ExplorerFilter.NATIVE); }
    @FXML private void onExplorerFilterFavorites(){ setExplorerFilter(ExplorerFilter.FAVORITES); }
    /**
     * Graph Clear: release graph/analysis working-state memory only.
     * Does not delete lossless artifacts, SQLite catalog, or persisted bookmarks.
     */
    @FXML private void onClearSelection(){
        clearGraphWorkingState();
        setGraphStatus("Graph cleared · processed signal artifacts remain available");
    }

    /** Explicit Clear lifecycle used by UI and NFT tests. */
    void clearGraphWorkingState() {
        generation++;
        cancelPendingGraphWork(true);
        SignalsScreenModel model = signalsModel();
        if (model != null) {
            model.clearWorkspace();
        }
        GraphInvestigationState inv = investigation();
        if (inv != null) {
            inv.clearAnalysis();
            inv.setLiveViewportResults(0);
            inv.setLiveTraceModels(0);
        }
        selectedSignalKeys.clear();
        failedTraces.clear();
        if (signalList != null) signalList.getSelectionModel().clearSelection();
        cursorA = null;
        cursorB = null;
        if (graphCanvas != null) graphCanvas.setCursors(null, null);
        updateCursorLabels(null, null);
        if (notesArea != null && inv != null) notesArea.setText(inv.notes());
        clearRenderedAnalysis();
        applySignalFilter();
        updateSelectionLabel();
        rebuildSelectedSignalCards();
        updateEmptyState();
        persistInvestigationNow();
    }

    private void cancelPendingGraphWork(boolean interruptRender) {
        ScheduledFuture<?> viewport = pendingViewport;
        if (viewport != null) viewport.cancel(false);
        pendingViewport = null;
        ScheduledFuture<?> hover = pendingHover;
        if (hover != null) hover.cancel(false);
        pendingHover = null;
        Future<?> render = pendingRender;
        if (render != null) render.cancel(interruptRender);
        pendingRender = null;
        Future<?> cursors = pendingCursors;
        if (cursors != null) cursors.cancel(true);
        pendingCursors = null;
    }

    private void configureSignalSearch(){
        if(signalSearch==null || !(signalSearch.getParent() instanceof javafx.scene.layout.Pane parent)) return;
        if(parent.getStyleClass().contains("graph-search-wrap")) return;
        int index=parent.getChildren().indexOf(signalSearch);
        if(index<0) return;
        javafx.scene.layout.StackPane wrap=new javafx.scene.layout.StackPane();
        wrap.getStyleClass().add("graph-search-wrap");
        wrap.setMinHeight(28);
        wrap.setPrefHeight(28);
        wrap.setMaxHeight(28);
        parent.getChildren().remove(signalSearch);
        signalSearch.setMinHeight(28);
        signalSearch.setPrefHeight(28);
        signalSearch.setMaxHeight(28);
        signalSearch.setMaxWidth(Double.MAX_VALUE);
        SVGPath icon=new SVGPath();
        icon.setContent("M10 4 A5 5 0 1 0 10 14 A5 5 0 1 0 10 4 M13.5 13.5 L18 18");
        icon.getStyleClass().add("graph-search-icon");
        icon.setMouseTransparent(true);
        javafx.scene.layout.StackPane.setAlignment(icon, Pos.CENTER_LEFT);
        javafx.scene.layout.StackPane.setMargin(icon, new javafx.geometry.Insets(0, 0, 0, 8));
        wrap.getChildren().addAll(signalSearch, icon);
        parent.getChildren().add(index, wrap);
    }

    private void configureFileStrip(){
        if(graphFileStrip==null) return;
        for(Node child: graphFileStrip.getChildren()){
            if(!(child instanceof Label label)) continue;
            if("Active Measurement".equals(label.getText())){
                label.setMinWidth(Region.USE_PREF_SIZE);
                label.setMaxWidth(Region.USE_PREF_SIZE);
                label.setTextOverrun(OverrunStyle.CLIP);
            }
        }
    }

    private void retireHiddenExplorerFilters(){
        if(explorerFilter==ExplorerFilter.RECOMMENDED || explorerFilter==ExplorerFilter.NATIVE){
            explorerFilter=ExplorerFilter.ALL;
        }
    }

    private void configureExplorerFilters(){
        retireHiddenExplorerFilters();
        ToggleGroup group=new ToggleGroup();
        for(ToggleButton button:new ToggleButton[]{
                filterAllButton, filterRecommendedButton, filterSelectedButton, filterDbcButton, filterNativeButton, filterFavoritesButton}){
            if(button!=null){
                button.setToggleGroup(group);
                button.setMinWidth(Region.USE_PREF_SIZE);
                button.setMaxWidth(Region.USE_PREF_SIZE);
            }
        }
        if(explorerFilter==null) explorerFilter=ExplorerFilter.ALL;
        if(filterAllButton!=null) filterAllButton.setSelected(explorerFilter==ExplorerFilter.ALL);
        if(filterRecommendedButton!=null) filterRecommendedButton.setSelected(explorerFilter==ExplorerFilter.RECOMMENDED);
        if(filterSelectedButton!=null) filterSelectedButton.setSelected(explorerFilter==ExplorerFilter.SELECTED);
        if(filterDbcButton!=null) filterDbcButton.setSelected(explorerFilter==ExplorerFilter.DBC);
        if(filterNativeButton!=null) filterNativeButton.setSelected(explorerFilter==ExplorerFilter.NATIVE);
        if(filterFavoritesButton!=null) filterFavoritesButton.setSelected(explorerFilter==ExplorerFilter.FAVORITES);
        setManagedVisible(filterRecommendedButton, false);
        setManagedVisible(filterNativeButton, false);
        setManagedVisible(filterFavoritesButton, false);
    }

    private void setExplorerFilter(ExplorerFilter filter){
        explorerFilter=filter==null?ExplorerFilter.ALL:filter;
        if(filterAllButton!=null) filterAllButton.setSelected(explorerFilter==ExplorerFilter.ALL);
        if(filterRecommendedButton!=null) filterRecommendedButton.setSelected(explorerFilter==ExplorerFilter.RECOMMENDED);
        if(filterSelectedButton!=null) filterSelectedButton.setSelected(explorerFilter==ExplorerFilter.SELECTED);
        if(filterDbcButton!=null) filterDbcButton.setSelected(explorerFilter==ExplorerFilter.DBC);
        if(filterNativeButton!=null) filterNativeButton.setSelected(explorerFilter==ExplorerFilter.NATIVE);
        if(filterFavoritesButton!=null) filterFavoritesButton.setSelected(explorerFilter==ExplorerFilter.FAVORITES);
        applySignalFilter();
    }

    private void applySignalFilter(){
        String q=explorerSearchQuery;
        if(signalSearch!=null&&signalSearch.getText()!=null) q=signalSearch.getText();
        explorerSearchQuery=q;
        SignalsScreenModel model=signalsModel();
        SignalBrowserFilter.State state=switch(explorerFilter){
            case DBC -> new SignalBrowserFilter.State(SignalBrowserFilter.SourceFilter.DBC,"","","",false,false);
            case NATIVE -> new SignalBrowserFilter.State(SignalBrowserFilter.SourceFilter.MF4_NATIVE,"","","",false,false);
            case FAVORITES -> new SignalBrowserFilter.State(SignalBrowserFilter.SourceFilter.ALL,"","","",true,false);
            default -> SignalBrowserFilter.State.none();
        };
        java.util.Set<SignalCatalogKey> favorites=model==null?java.util.Set.of():model.favorites();
        List<SignalCatalogEntry> filtered=SignalBrowserFilter.apply(allSignals, q, state, favorites);
        if(explorerFilter==ExplorerFilter.SELECTED){
            java.util.Set<SignalCatalogKey> members=new java.util.LinkedHashSet<>(selectedSignalKeys);
            filtered=filtered.stream().filter(e->members.contains(e.key())).toList();
        }
        if(explorerFilter==ExplorerFilter.RECOMMENDED){
            java.util.Set<SignalCatalogKey> recommended=AutomotiveSignalRecommender.recommendedKeys(allSignals);
            filtered=filtered.stream().filter(e->recommended.contains(e.key())).toList();
        }
        filtered=filtered.stream()
                .sorted(java.util.Comparator
                        .comparingInt((SignalCatalogEntry e)->SignalCategoryIndex.rank(categoryOf(e)))
                        .thenComparingInt(e->e.key().channel())
                        .thenComparing(e->e.key().signalName(), String.CASE_INSENSITIVE_ORDER))
                .toList();
        explorerRows=List.copyOf(filtered);
        java.util.Map<String, Integer> counts=new java.util.HashMap<>();
        for(SignalCatalogEntry row:explorerRows){
            counts.merge(categoryOf(row), 1, Integer::sum);
        }
        visibleCategoryCounts=java.util.Map.copyOf(counts);
        if(signalList!=null){
            applyingSignalFilter=true;
            try{
                signalList.setItems(FXCollections.observableArrayList(explorerRows));
            }finally{applyingSignalFilter=false;}
        }
        updateSelectionLabel();
        updateEmptyState();
    }

    /**
     * Metadata/name driven automotive grouping. It never depends on a particular uploaded file,
     * CAN id, channel, DBC or exact signal name. Unknown signals remain visible under Other.
     */
    private String categoryOf(SignalCatalogEntry e){ return signalCategories.categoryOf(e); }

    /** Classification is derived from immutable catalog metadata. Skip it when the catalog generation is unchanged. */
    private void installSignalCategories(){
        int revision=appService==null||appService.signalsModel()==null
                ? Integer.MIN_VALUE : appService.signalsModel().catalogRevision();
        int size=allSignals==null?0:allSignals.size();
        if(revision==signalCategoryRevision && signalCategories.indexedSize()==size){
            return;
        }
        signalCategories=com.stellar.platform.desktop.ui.graph.SignalCategoryIndex.build(allSignals);
        signalCategoryRevision=revision;
    }

    private void rebuildSelectedSignalCards(){
        if(selectedSignalCards==null)return;
        selectedSignalCards.getChildren().clear();
        List<SignalCatalogEntry> selected=selectedEntries();
        if(selected.isEmpty())return;

        // Use the real allocated width. Never invent a 520px minimum: that was the source of
        // card overflow at narrow window sizes. Keep one stable row and reserve the last slot
        // for an explicit overflow summary only when the selected set cannot fit comfortably.
        double available=Math.max(1,selectedSignalCards.getWidth()-24);
        double minCard=168.0, gapSize=7.0;
        int capacity=Math.max(1,(int)Math.floor((available+gapSize)/(minCard+gapSize)));
        int visibleCards=selected.size();
        boolean overflow=selected.size()>capacity;
        if(overflow) visibleCards=Math.max(1,capacity-1);
        int slots=visibleCards+(overflow?1:0);
        double gap=Math.max(0,slots-1)*gapSize;
        double cardWidth=Math.max(96,Math.min(280,(available-gap)/Math.max(1,slots)));

        for(int i=0;i<visibleCards;i++){
            SignalCatalogEntry e=selected.get(i); final SignalCatalogKey key=e.key();
            GraphInvestigationState inv=investigation();
            int colorIndex=inv==null?i:inv.colorIndex(key);
            Label dot=new Label("●");
            dot.getStyleClass().add("graph-chip-dot");
            // Series color is chosen from GraphSeriesPalette at runtime. A fixed CSS class cannot
            // represent the index, so this fill stays programmatic. Hidden chips clear it below.
            dot.setStyle("-fx-text-fill: "+GraphSeriesPalette.cssAt(colorIndex)+";");
            String display=SignalTraceLabels.displayName(e,selected);
            Label title=new Label(display); title.getStyleClass().add("graph-chip-name");
            title.setTextOverrun(OverrunStyle.ELLIPSIS); title.setMinWidth(0); title.setMaxWidth(Double.MAX_VALUE);
            title.setTooltip(new Tooltip(display));
            Label value=new Label(latestRepresentedValue(key)); value.getStyleClass().add("graph-chip-value");
            value.setMinWidth(Region.USE_PREF_SIZE);
            Button close=new Button("×"); close.getStyleClass().addAll("graph-chip-close","compact-button");
            close.setTooltip(new Tooltip("Remove from analysis"));
            close.setOnAction(ev->removeFromAnalysis(key));
            boolean hidden=inv!=null&&inv.isHidden(key);
            if(hidden){
                dot.getStyleClass().add("graph-chip-dot-hidden");
                dot.setStyle("");
                title.setOpacity(0.55);
                value.setOpacity(0.55);
            }
            boolean outsideOverlay=inv!=null && !inv.stacked()
                    && selected.size()>TrackQueryWindow.OVERLAY_CAP
                    && i>=TrackQueryWindow.OVERLAY_CAP;
            Label outside=null;
            if(outsideOverlay){
                outside=new Label("Outside overlay");
                outside.getStyleClass().add("graph-chip-value");
                Tooltip.install(outside, new Tooltip(display+" stays selected. Switch to Stacked to plot it."));
            }
            HBox card=outside==null?new HBox(6,dot,title,value,close):new HBox(6,dot,title,outside,value,close);
            card.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(title,Priority.ALWAYS);
            card.getStyleClass().add("graph-signal-chip");
            card.setMinWidth(0); card.setPrefWidth(cardWidth); card.setMaxWidth(cardWidth); HBox.setHgrow(card,Priority.NEVER);
            selectedSignalCards.getChildren().add(card);
        }
        if(overflow){
            int hidden=selected.size()-visibleCards;
            Label moreTitle=new Label("+"+hidden+" more"); moreTitle.getStyleClass().add("graph-chip-name");
            HBox more=new HBox(moreTitle); more.setAlignment(Pos.CENTER); more.getStyleClass().addAll("graph-signal-chip","graph-more-chip");
            more.setMinWidth(0); more.setPrefWidth(cardWidth); more.setMaxWidth(cardWidth);
            GraphInvestigationState layout=investigation();
            boolean overlayLimited=layout!=null && !layout.stacked() && selected.size()>TrackQueryWindow.OVERLAY_CAP;
            String overflowNames=selected.stream().skip(visibleCards).map(x->x.key().signalName()).collect(java.util.stream.Collectors.joining("\n"));
            String overflowTip=overlayLimited
                    ? "Showing "+Math.min(TrackQueryWindow.OVERLAY_CAP, selected.size())+" of "+selected.size()
                        +" selected signals. Switch to Stacked to inspect:\n"
                        +selected.stream().skip(TrackQueryWindow.OVERLAY_CAP).map(x->x.key().signalName())
                            .collect(java.util.stream.Collectors.joining("\n"))
                    : overflowNames;
            Tooltip.install(more,new Tooltip(overflowTip));
            selectedSignalCards.getChildren().add(more);
        }
    }
    private List<SignalCatalogEntry> selectedEntries(){
        return selectedSignalKeys.stream().map(k->allSignals.stream().filter(e->e.key().equals(k)).findFirst().orElse(null)).filter(java.util.Objects::nonNull).toList();
    }
    private String latestRepresentedValue(SignalCatalogKey key){
        for(GraphLogicalTrack track:lastTracks){
            if(!track.logicalKey().equals(key)||track.segments().isEmpty()) continue;
            java.util.ArrayList<String> labels=new java.util.ArrayList<>();
            boolean numeric=false;
            GraphBucket latestNumeric=null;
            String unit="";
            for(GraphTrackSegment segment:track.segments()){
                GraphRenderSeries series=segment.series();
                if(series==null) continue;
                if(series.unit()!=null && !series.unit().isBlank()) unit=" "+series.unit();
                for(GraphBucket bucket:series.buckets()){
                    if(bucket.lastUsable() || bucket.minUsable() || bucket.maxUsable()){
                        numeric=true;
                        latestNumeric=bucket;
                    }
                    addChipLabel(labels, bucket.lastLabel());
                    addChipLabel(labels, bucket.categoricalLabel());
                }
            }
            String categorical=CategoricalSeriesSummary.chipText(labels);
            if(!categorical.isEmpty() && !numeric){
                return categorical;
            }
            if(latestNumeric!=null && Double.isFinite(latestNumeric.lastValue())){
                return String.format("%.6g%s", latestNumeric.lastValue(), unit);
            }
            if(!categorical.isEmpty()){
                return categorical;
            }
        }
        return "—";
    }
    private static void addChipLabel(java.util.List<String> labels, String label){
        if(label!=null && !label.isBlank()) labels.add(label);
    }
    private void updateSelectionLabel(){
        GraphInvestigationState inv=investigation();
        int hidden=0;
        int inCatalog=0;
        for(SignalCatalogKey key:selectedSignalKeys){
            if(!inCurrentCatalog(key)) continue;
            inCatalog++;
            if(inv!=null && inv.isHidden(key)) hidden++;
        }
        int visible=Math.max(0,inCatalog-hidden);
        String summary=selectedSignalKeys.size()+" selected / "+allSignals.size()+" signals";
        if(selectionLabel!=null) selectionLabel.setText(summary+(hidden==0?"":" · "+visible+" visible"));
        if(visibleCountLabel!=null){
            String countText=selectedSignalKeys.size()+" selected · "+visible+" visible / "+allSignals.size()+" signals";
            visibleCountLabel.setText(countText);
            showReadout(visibleCountLabel, countText);
        }
    }
    private void deselectSignal(SignalCatalogKey key){
        removeFromAnalysis(key);
    }
    @FXML private void onBack(){if(shell!=null)shell.show(NavigationPage.SIGNALS);}
    @FXML private void onAddSignalsToGraph(){
        if(signalSearch!=null){
            signalSearch.requestFocus();
        }
    }
    @FXML private void onFit(){fitRange();}
    @FXML private void onZoomIn(){zoom(0.5);}@FXML private void onZoomOut(){zoom(2.0);}
    @FXML private void onPanLeft(){panFraction(-0.25);}@FXML private void onPanRight(){panFraction(0.25);}
    @FXML private void onCursorA(){ placeCursorAtFraction("A", 0.5); }
    @FXML private void onCursorB(){ placeCursorAtFraction("B", 0.5); }
    @FXML private void onClearCursors(){
        cursorA=cursorB=null;
        cursorAPinned=cursorBPinned=false;
        transientSampleNanos=null;
        sampleFromClick=false;
        cursorAAbsolute=cursorBAbsolute=null;
        cursorADisplay=cursorBDisplay=null;
        cursorMeasurementId="";
        GraphInvestigationState inv=investigation();
        if(inv!=null) inv.clearCursors();
        if(graphCanvas!=null) graphCanvas.setSelectionLine(null);
        applyCursorVisuals();
        updateCursorLabels(null,null);
        updateSelectedTimeLabel();
        populateSummaryTable();
        persistInvestigationSoon();
    }
    private void fitRange(){
        if(!tryFitRange()){
            if(statusLabel!=null) setGraphStatus("No timestamped samples available");
        }
    }

    /**
     * Recomputes the selected-visible full analysis range into {@link #dataStart}/{@link #dataEnd}.
     * Does not modify the current shared viewport.
     * @return true when a catalog-backed full range was resolved
     */
    private boolean refreshFullAnalysisRange(){
        if(unknownOrderDomain && !fileFocusActive && unknownGroupCount>0){
            dataStart=0L;
            dataEnd=Math.multiplyExact((long)unknownGroupCount, UnknownOrderLayout.EQUAL_SLOT_SPAN);
            return dataEnd>dataStart;
        }
        if(sharedDisplayDomain && !fileFocusActive && sharedDisplayEnd>=sharedDisplayStart){
            dataStart=sharedDisplayStart;
            dataEnd=sharedDisplayEnd>sharedDisplayStart?sharedDisplayEnd:sharedDisplayStart+1;
            return true;
        }
        if(fileFocusActive && focusedFileIndex>=0 && focusedFileIndex<fileSlots.size()){
            FileSlot slot=fileSlots.get(focusedFileIndex);
            dataStart=slot.firstNanos();
            dataEnd=slot.lastNanos()<=slot.firstNanos()?slot.firstNanos()+1:slot.lastNanos();
            return dataEnd>=dataStart;
        }
        List<SignalCatalogEntry> selected=visibleEntries();
        if(selected.isEmpty()) selected=selectedEntries();
        Long min=null,max=null;
        for(SignalCatalogEntry e:selected){
            if(e.firstTimestampNanos()!=null&&(min==null||e.firstTimestampNanos()<min))min=e.firstTimestampNanos();
            if(e.lastTimestampNanos()!=null&&(max==null||e.lastTimestampNanos()>max))max=e.lastTimestampNanos();
        }
        if(min==null||max==null){
            return false;
        }
        dataStart=min;
        dataEnd=max>=min?max:min;
        GraphInvestigationState inv=investigation();
        if(inv!=null){
            if(dataEnd>dataStart){
                inv.captureFullAnalysisRange(dataStart,dataEnd);
            }else{
                long[] pad=GraphSharedViewport.singleTimestampDisplayRange(dataStart);
                inv.captureFullAnalysisRange(pad[0],pad[1]);
            }
        }
        return true;
    }

    /**
     * Fit the viewport to the currently selected (visible) signals' catalog time span.
     * @return true when a usable viewport was applied and a load was requested
     */
    private boolean tryFitRange(){
        if(!fitToFullRange("FIT_TO_FULL_RANGE")){
            return false;
        }
        requestViewport();
        return true;
    }

    /** Commits viewport to the selected-visible full analysis range without scheduling a load. */
    private boolean fitToFullRange(String reason){
        if(!refreshFullAnalysisRange()){
            return false;
        }
        long fitStart=dataStart;
        long fitEnd=dataEnd;
        if(fitEnd==fitStart){
            long[] pad=GraphSharedViewport.singleTimestampDisplayRange(fitStart);
            fitStart=pad[0];
            fitEnd=pad[1];
        }else if(fitEnd<fitStart){
            return false;
        }
        GraphInvestigationState inv=investigation();
        if(inv!=null){
            inv.captureDefaultViewport(fitStart,fitEnd);
        }
        commitViewport(reason,fitStart,fitEnd);
        return true;
    }

    private void zoom(double factor){
        zoomAt(factor, start + Math.max(1,end-start)/2);
    }
    private void zoomAt(double factor, long at){
        if(!GraphSharedViewport.isStructurallyValid(start,end))return;
        refreshFullAnalysisRange();
        long[] next=GraphSharedViewport.zoomAround(start,end,factor,at,dataStart,dataEnd);
        applyViewport(next[0],next[1]);
    }
    private void panFraction(double f){
        if(!GraphSharedViewport.isStructurallyValid(start,end))return;
        refreshFullAnalysisRange();
        long shift=(long)((end-start)*f);
        long[] next=GraphSharedViewport.panBy(start,end,shift,dataStart,dataEnd);
        applyViewport(next[0],next[1]);
    }
    private void panTo(long a,long b){applyViewport(a,b);}
    private void applyViewport(long a,long b){
        if(b<=a)return;
        refreshFullAnalysisRange();
        long[] placed=GraphSharedViewport.placeWindow(a,b,dataStart,dataEnd);
        long nextStart=placed[0];
        long nextEnd=placed[1];
        if(!GraphSharedViewport.isUsable(nextStart,nextEnd,dataStart,dataEnd)
                && dataEnd>dataStart && dataStart!=Long.MIN_VALUE && dataEnd!=Long.MAX_VALUE){
            // Refuse to commit a degenerate zoom/pan against multi-sample data.
            return;
        }
        commitViewport("USER_VIEWPORT",nextStart,nextEnd);
        requestViewport();
    }

    /**
     * The waveform sits in a scroll pane. A wheel gesture over the plot changes the time
     * window; it must not be swallowed as a scroll of the track list.
     */
    private void installWaveformWheelZoom(){
        if(waveformScroll==null||graphCanvas==null)return;
        waveformScroll.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, event->{
            Object target=event.getTarget();
            if(!(target instanceof Node node) || !ownedBy(node, graphCanvas))return;
            javafx.geometry.Point2D local=graphCanvas.sceneToLocal(node.localToScene(event.getX(), event.getY()));
            graphCanvas.acceptWheel(event.getDeltaY(), local.getX());
            event.consume();
        });
    }

    /**
     * Single writer for the shared X viewport. Logs only when the range actually changes.
     */
    private void commitViewport(String reason, long newStart, long newEnd){
        if(!GraphSharedViewport.isStructurallyValid(newStart,newEnd)){
            return;
        }
        if(newStart==start && newEnd==end){
            GraphInvestigationState inv=investigation();
            if(inv!=null) inv.setViewport(newStart,newEnd);
            if(graphCanvas!=null) graphCanvas.prepareSharedViewport(newStart,newEnd);
            return;
        }
        long oldStart=start, oldEnd=end;
        start=newStart;
        end=newEnd;
        GraphInvestigationState inv=investigation();
        if(inv!=null) inv.setViewport(start,end);
        if(graphCanvas!=null) graphCanvas.prepareSharedViewport(start,end);
        if(rangeLabel!=null) rangeLabel.setText(formatRange(start,end));
        persistInvestigationSoon();
        int selected=selectedSignalKeys.size();
        int visible=selectedKeys().size();
        String mode=inv==null?GraphInvestigationState.STACKED:inv.layoutMode();
        LOG.log(System.Logger.Level.INFO,
                ()->"Graph viewport changed: reason="+reason
                        +" old="+GraphSharedViewport.formatNanosRange(oldStart,oldEnd)
                        +" new="+GraphSharedViewport.formatNanosRange(newStart,newEnd)
                        +" layout="+mode
                        +" selected="+selected
                        +" visible="+visible);
    }

    /**
     * If the shared viewport is invalid/degenerate relative to selected-visible data, recover to
     * the full analysis range. Valid user zoom is left untouched.
     */
    private boolean ensureViewportValidOrRecover(String reason){
        refreshFullAnalysisRange();
        if(GraphSharedViewport.isUsable(start,end,dataStart,dataEnd)){
            if(graphCanvas!=null) graphCanvas.prepareSharedViewport(start,end);
            return false;
        }
        if(dataStart==Long.MIN_VALUE || (dataEnd<dataStart)){
            return false;
        }
        long recoverStart=dataStart;
        long recoverEnd=dataEnd;
        if(recoverEnd==recoverStart){
            long[] pad=GraphSharedViewport.singleTimestampDisplayRange(recoverStart);
            recoverStart=pad[0];
            recoverEnd=pad[1];
        }
        commitViewport(reason+"_RECOVER_FULL_RANGE",recoverStart,recoverEnd);
        return true;
    }

    private GraphQueryProvider provider(){return appService!=null&&appService.signalsModel().catalogStore() instanceof GraphQueryProvider p?p:null;}

    private GraphSeriesQuery analysisQuery(){
        GraphQueryProvider p=provider();
        if(p==null){
            return null;
        }
        GraphSeriesQuery raw=p.graphQuery();
        if(appService!=null && appService.isProjectWideAnalysis()){
            return new com.stellar.platform.processing.graph.PhysicalIdentityGraphSeriesQuery(raw);
        }
        String scope=appService==null?"":appService.activeMeasurementSourceId();
        if(scope==null||scope.isBlank()){
            return raw;
        }
        return new SourceScopedGraphSeriesQuery(raw, scope);
    }

    private void logGraphBindResolution(List<SignalCatalogKey> workspaceKeys, List<SignalCatalogKey> remapped){
        if(appService==null){
            return;
        }
        var ctx=appService.activeAnalysisMeasurement();
        var store=appService.signalsModel().catalogStore();
        int catalogSize=appService.signalsModel().catalog()==null?0:appService.signalsModel().catalog().size();
        String storeName=store==null?"none":store.getClass().getSimpleName();
        int members=store instanceof com.stellar.platform.processing.catalog.ProjectMergedSignalStore merged
                ? merged.memberStoreCount() : 0;
        LOG.log(System.Logger.Level.INFO, ()->"graph-bind"
                +" sourceId="+ctx.sourceId()
                +" fileName="+ctx.fileName()
                +" workspaceSelected="+appService.workspace().selectedMeasurement()
                .map(WorkspaceFile::id).orElse("")
                +" catalogSize="+catalogSize
                +" engineeringSize="+allSignals.size()
                +" graphAllSignals="+allSignals.size()
                +" workspaceKeys="+(workspaceKeys==null?0:workspaceKeys.size())
                +" selected="+selectedSignalKeys.size()
                +" remapped="+(remapped==null?0:remapped.size())
                +" store="+storeName
                +" members="+members
                +" sourceScope="+ctx.sourceId());
        for(SignalCatalogKey key:selectedSignalKeys){
            boolean resolved=false;
            int samples=0;
            try{
                samples=(int)Math.min(Integer.MAX_VALUE, allSignals.stream()
                        .filter(entry->entry.key().equals(key))
                        .mapToLong(SignalCatalogEntry::sampleCount)
                        .findFirst().orElse(0L));
                resolved=samples>0
                        || (store instanceof com.stellar.platform.processing.catalog.ProjectMergedSignalStore merged
                        && merged.hasResolvableSamples(key));
            }catch(RuntimeException ignored){
                resolved=false;
            }
            boolean storeResolved=resolved;
            int sampleCount=samples;
            LOG.log(System.Logger.Level.INFO, ()->"graph-key"
                    +" name="+key.signalName()
                    +" sourceFile="+key.sourceFile()
                    +" canonicalSourceId="+ctx.sourceId()
                    +" storeResolved="+storeResolved
                    +" sampleCount="+sampleCount);
        }
    }
    private List<SignalCatalogKey> analysisMembershipKeys(){
        return selectedSignalKeys.stream().filter(this::inCurrentCatalog).toList();
    }

    /**
     * Flat physical keys for test probes. The waveform itself groups these into logical tracks.
     */
    private List<SignalCatalogKey> plotKeys(){
        boolean projectWide=appService!=null && appService.isProjectWideAnalysis();
        com.stellar.platform.processing.catalog.ProjectMergedSignalStore merged=
                projectWide && appService.signalsModel().catalogStore()
                        instanceof com.stellar.platform.processing.catalog.ProjectMergedSignalStore store
                        ? store : null;
        return GraphMeasurementSeries.keysForPlot(
                analysisMembershipKeys(), selectedEntries(),
                merged==null?List.of():merged.physicalCatalogAll(), projectWide);
    }

    /**
     * Logical signals already selected. File expansion happens on the graph worker, not here.
     */
    private List<GraphMeasurementSeries.PlotGroup> plotGroupsOnWorker(
            List<SignalCatalogKey> membership,
            List<SignalCatalogEntry> entries,
            com.stellar.platform.processing.catalog.ProjectMergedSignalStore merged,
            boolean projectWide
    ){
        List<SignalCatalogEntry> physical=projectWide && merged!=null ? merged.physicalCatalogAll() : List.of();
        return GraphMeasurementSeries.groupsForPlot(membership, entries, physical, projectWide);
    }
    /** Visible selected keys (SELECTED − hidden). Used for map/cursors of plotted traces. */
    private List<SignalCatalogKey> selectedKeys(){
        List<SignalCatalogKey> membership=analysisMembershipKeys();
        GraphInvestigationState inv=investigation();
        return inv==null?membership:inv.visibleKeys(membership);
    }
    private void requestViewport(){
        if(appService==null || disposed || viewportScheduler.isShutdown()){
            return;
        }
        // Degenerate / near-zero viewports cannot plot multi-sample series. Recover from the
        // selected-visible full range instead of issuing empty queries that paint blank Overlay
        // tracks and "No samples in viewport" Stacked messages.
        if(!GraphSharedViewport.isUsable(start,end,dataStart,dataEnd)){
            if(selectedSignalKeys.isEmpty()){
                clearRenderedAnalysis();
                return;
            }
            if(!ensureViewportValidOrRecover("REQUEST_VIEWPORT")){
                if(!fitToFullRange("REQUEST_VIEWPORT_FIT")){
                    clearRenderedAnalysis();
                    if(statusLabel!=null) setGraphStatus("No timestamped samples available");
                    return;
                }
            }
            if(!GraphSharedViewport.isUsable(start,end,dataStart,dataEnd)){
                clearRenderedAnalysis();
                if(statusLabel!=null) setGraphStatus("No timestamped samples available");
                return;
            }
        }
        long my=++generation;
        viewportRequests.incrementAndGet();
        ScheduledFuture<?> old=pendingViewport; if(old!=null)old.cancel(false);
        Future<?> render=pendingRender; if(render!=null)render.cancel(true);
        if(Platform.isFxApplicationThread()){
            setGraphStatus("Loading waveform…");
            List<SignalCatalogKey> ordered=selectedKeys();
            if(graphCanvas!=null && !ordered.isEmpty()){
                boolean timeChanged=start!=appliedViewportStart || end!=appliedViewportEnd;
                installQueryPlaceholders(ordered, currentTrackWindow(ordered.size()), timeChanged);
            }
        }
        pendingViewport=viewportScheduler.schedule(()->Platform.runLater(()->loadViewport(my)),55,TimeUnit.MILLISECONDS);
    }
    private void loadViewport(long my){
        if(!acceptViewportResult(my, boundSessionGeneration)) return;
        GraphQueryProvider p=provider();
        if(p==null){if(statusLabel!=null)setGraphStatus("Full-resolution graph data is not available for this processing result.");clearRenderedAnalysis();return;}
        List<SignalCatalogKey> membership=analysisMembershipKeys();
        if(membership.isEmpty()){
            clearRenderedAnalysis();
            if(statusLabel!=null) setGraphStatus(selectedSignalKeys.isEmpty()
                    ?"No signals plotted yet."
                    :"All selected signals are hidden. Show a signal to plot.");
            if(graphCanvas!=null) graphCanvas.setEmptyHint(selectedSignalKeys.isEmpty()
                    ?"No signals plotted yet."
                    :"All selected signals are hidden. Show a signal to plot.");
            return;
        }
        if(selectedKeys().isEmpty()){
            // Membership exists but every selected signal is hidden — keep session, no plot.
            if(statusLabel!=null) setGraphStatus("All selected signals are hidden. Show a signal to plot.");
            if(graphCanvas!=null){
                graphCanvas.setEmptyHint("All selected signals are hidden. Show a signal to plot.");
                applyCanvasVisibility();
            }
            return;
        }
        List<SignalCatalogKey> orderedKeys=List.copyOf(selectedKeys());
        ScrollSlice slice=waveformSlice();
        boolean stacked=investigation()==null || investigation().stacked();
        TrackQueryWindow.Range queryWindow=stacked
                ? TrackQueryWindow.of(orderedKeys.size(), slice.offset(), slice.viewportHeight(), slice.stride(), TrackQueryWindow.OVERSCAN)
                : new TrackQueryWindow.Range(0, Math.min(orderedKeys.size(), TrackQueryWindow.OVERLAY_CAP));
        TrackQueryWindow.Range visibleCore=stacked
                ? TrackQueryWindow.of(orderedKeys.size(), slice.offset(), slice.viewportHeight(), slice.stride(), 0)
                : queryWindow;
        residentQuery=queryWindow;
        String residenceNote=residenceText(orderedKeys, queryWindow);
        boolean timeChanged=start!=appliedViewportStart || end!=appliedViewportEnd;
        installQueryPlaceholders(orderedKeys, queryWindow, timeChanged);
        setGraphStatus("Loading waveform…");
        int width=Math.max(320,graphCanvas==null?320:(int)Math.rint(graphCanvas.getWidth()));
        lastViewportQueryWidth=width;
        if(rangeLabel!=null) rangeLabel.setText(formatRange(start,end));
        applyCanvasPresentation();
        if(graphCanvas!=null) graphCanvas.prepareSharedViewport(start,end);
        int session=boundSessionGeneration;
        boolean projectWide=appService!=null && appService.isProjectWideAnalysis();
        com.stellar.platform.processing.catalog.ProjectMergedSignalStore merged=
                projectWide && appService.signalsModel().catalogStore()
                        instanceof com.stellar.platform.processing.catalog.ProjectMergedSignalStore store
                        ? store : null;
        List<SignalCatalogEntry> entrySnapshot=List.copyOf(selectedEntries());
        java.util.Map<String, java.nio.file.Path> pathSnapshot=java.util.Map.copyOf(measurementPaths());
        String cacheToken=graphCacheTokenOnFx();
        long queryStart=start;
        long queryEnd=end;
        GraphSeriesQuery analysisQuery=analysisQuery();
        long queryStartedNanos=System.nanoTime();
        int requestedFocus=focusedFileIndex;
        Future<?> previous=pendingRender; if(previous!=null)previous.cancel(true);
        pendingRender=worker.submit(()->{
            ViewportTrace trace=new ViewportTrace(queryStartedNanos);
            VIEWPORT_TRACE.set(trace);
            try{
            if(analysisQuery==null){
                return;
            }
            alignGraphCache(cacheToken);
            trace.mark("cacheAlign");
            GraphViewportEngine engine=new GraphViewportEngine(analysisQuery, 2, graphQueryCache);
            engine.setArtifactGeneration(graphCacheGeneration);
            java.util.ArrayList<GraphRenderSeries> rendered=new java.util.ArrayList<>();
            java.util.ArrayList<GraphLogicalTrack> builtTracks=new java.util.ArrayList<>();
            java.util.ArrayList<String> failures=new java.util.ArrayList<>();
            try{
                List<SignalCatalogEntry> physical=projectWide && merged!=null ? merged.physicalCatalogAll() : List.of();
                trace.mark("catalog");
                List<GraphMeasurementSeries.PlotGroup> groups=GraphMeasurementSeries.groupsForPlot(
                        orderedKeys, entrySnapshot, physical, projectWide);
                trace.mark("groups");
                List<FileSlot> slots=fileSlotsFrom(groups, physical, entrySnapshot);
                trace.mark("slots");
                int restoredFocus;
                synchronized(fileFocusLock){
                    restoredFocus=indexOfRestoredFocus(slots);
                    if(restoredFocus>=0) fileFocusRequested=true;
                }
                int focusIndex=restoredFocus>=0
                        ? restoredFocus
                        : (slots.isEmpty()?0:Math.max(0, Math.min(requestedFocus, slots.size()-1)));
                RecordingGroupIndex groupIndex=recordingGroupIndex(slots, pathSnapshot);
                activeGroupIndex=groupIndex;
                activeUnknownLayout=groupIndex.unknownLayout();
                trace.mark("clocks");
                TimelineGroup activeGroup=GraphOverviewMode.activeGroup(
                        groupIndex.size(TimelineGroup.VERIFIED_UTC),
                        groupIndex.size(TimelineGroup.UNKNOWN_ORDER),
                        preferredTimelineGroup);
                boolean focus=fileFocusRequested && !slots.isEmpty();
                SharedDisplayTimeline timeline=!focus && activeGroup==TimelineGroup.VERIFIED_UTC
                        ? groupIndex.utcTimeline() : null;
                if(timeline!=null && !sharedDisplayDomain){
                    Platform.runLater(()->adoptSharedDisplay(timeline, my, session));
                    return;
                }
                UnknownOrderLayout unknownLayout=!focus && activeGroup==TimelineGroup.UNKNOWN_ORDER
                        ? groupIndex.unknownLayout() : null;
                if(GraphOverviewMode.singleRecordingStoredClock(
                        groupIndex.size(TimelineGroup.VERIFIED_UTC),
                        groupIndex.size(TimelineGroup.UNKNOWN_ORDER), focus) && unknownLayout!=null){
                    boolean layoutDomain=unknownOrderDomain
                            || preferredTimelineGroup==TimelineGroup.UNKNOWN_ORDER;
                    long[] stored=GraphOverviewMode.storedWindowForSingleRecording(
                            unknownLayout, queryStart, queryEnd, layoutDomain);
                    boolean sameWindow=stored!=null && stored[0]==queryStart && stored[1]==queryEnd;
                    if(stored!=null && (layoutDomain || unknownOrderDomain || !sameWindow)){
                        long mappedStart=stored[0];
                        long mappedEnd=stored[1];
                        Platform.runLater(()->adoptStoredClock(mappedStart, mappedEnd, my, session));
                        return;
                    }
                    unknownLayout=null;
                }
                UnknownOrderLayout layoutForPlot=unknownLayout;
                if(layoutForPlot!=null && !unknownOrderDomain){
                    Platform.runLater(()->adoptUnknownOrder(layoutForPlot, my, session));
                    return;
                }
                if(timeline==null && sharedDisplayDomain && !focus){
                    Platform.runLater(()->{
                        if(!acceptViewportResult(my, session)) return;
                        leaveSharedDisplay();
                        if(fitToFullRange("STORED_CLOCK")) requestViewport();
                    });
                    return;
                }
                boolean comparable=timeline!=null;
                FileSlot focused=focus?slots.get(focusIndex):null;
                long qStart=queryStart;
                long qEnd=queryEnd;
                if(focus){
                    long[] window=GraphFileFocus.queryWindow(queryStart, queryEnd, focused.firstNanos(), focused.lastNanos());
                    qStart=window[0];
                    qEnd=window[1];
                }
                final long renderStart=qStart;
                final long renderEnd=qEnd;
                final boolean overviewUnknown=unknownLayout!=null;
                java.util.ArrayList<GraphMeasurementSeries.PlotGroup> tailGroups=new java.util.ArrayList<>();
                tailGroups.addAll(groupsForKeys(groups, orderedKeys, queryWindow.fromInclusive(), visibleCore.fromInclusive()));
                tailGroups.addAll(groupsForKeys(groups, orderedKeys, visibleCore.toExclusive(), queryWindow.toExclusive()));
                List<GraphMeasurementSeries.PlotGroup> coreGroups=groupsForKeys(
                        groups, orderedKeys, visibleCore.fromInclusive(), visibleCore.toExclusive());
                if(overviewUnknown){
                    appendUnknownTracks(engine, coreGroups, groupIndex, unknownLayout, renderStart, renderEnd, width, my, session,
                            rendered, builtTracks, failures);
                    if(!acceptViewportResult(my,session)) return;
                }else if(!plotGroups(engine, coreGroups, focus, focused, comparable, groupIndex, timeline,
                        renderStart, renderEnd, width, my, session, rendered, builtTracks, failures)){
                    return;
                }
                if(!tailGroups.isEmpty()){
                    if(!acceptViewportResult(my,session)) return;
                    publishVisibleTracks(my, session, composeTracks(orderedKeys, builtTracks, renderStart, renderEnd),
                            unloadedKeys(orderedKeys, builtTracks), renderStart, renderEnd, overviewUnknown, queryStartedNanos);
                    if(overviewUnknown){
                        appendUnknownTracks(engine, tailGroups, groupIndex, unknownLayout, renderStart, renderEnd, width, my, session,
                                rendered, builtTracks, failures);
                        if(!acceptViewportResult(my,session)) return;
                    }else if(!plotGroups(engine, tailGroups, focus, focused, comparable, groupIndex, timeline,
                            renderStart, renderEnd, width, my, session, rendered, builtTracks, failures)){
                        return;
                    }
                }
                for(int i=queryWindow.fromInclusive(); i<queryWindow.toExclusive() && i<orderedKeys.size(); i++){
                    SignalCatalogKey key=orderedKeys.get(i);
                    boolean found=false;
                    for(GraphLogicalTrack track:builtTracks){
                        if(key.equals(track.logicalKey())){ found=true; break; }
                    }
                    if(!found) builtTracks.add(placeholderTrack(key, renderStart, renderEnd));
                }
                trace.mark("tracks");
                List<GraphRenderSeries> snapshot=List.copyOf(rendered);
                List<GraphLogicalTrack> tracks=composeTracks(orderedKeys, builtTracks, renderStart, renderEnd);
                java.util.Set<SignalCatalogKey> unloaded=unloadedKeys(orderedKeys, builtTracks);
                List<FileSlot> slotSnapshot=List.copyOf(slots);
                int failureCount=failures.size();
                int appliedFocus=focusIndex;
                boolean appliedFileFocus=focus;
                boolean appliedUnknown=overviewUnknown;
                int appliedUtc=groupIndex.size(TimelineGroup.VERIFIED_UTC);
                int appliedUnknownCount=groupIndex.size(TimelineGroup.UNKNOWN_ORDER);
                long appliedStart=renderStart;
                long appliedEnd=renderEnd;
                Platform.runLater(()->{
                    lastViewportFxQueueMs=(System.nanoTime()-trace.last)/1_000_000L;
                    trace.mark("fxQueue");
                    if(!acceptViewportResult(my,session)) return;
                    lastRendered=snapshot;
                    lastTracks=tracks;
                    appliedViewportStart=appliedStart;
                    appliedViewportEnd=appliedEnd;
                    fileSlots=slotSnapshot;
                    fileFocusActive=appliedFileFocus;
                    focusedFileIndex=appliedFocus;
                    utcGroupCount=appliedUtc;
                    unknownGroupCount=appliedUnknownCount;
                    if(appliedFileFocus && appliedFocus<slotSnapshot.size()){
                        FileSlot slot=slotSnapshot.get(appliedFocus);
                        dataStart=slot.firstNanos();
                        dataEnd=slot.lastNanos()<=slot.firstNanos()?slot.firstNanos()+1:slot.lastNanos();
                        start=appliedStart;
                        end=appliedEnd;
                        if(rangeLabel!=null) rangeLabel.setText(formatRange(start, end));
                    }
                    applyFileFocusChrome();
                    GraphInvestigationState inv=investigation();
                    if(inv!=null){
                        inv.setLiveViewportResults(tracks.size());
                        inv.setLiveTraceModels(tracks.size());
                    }
                    rebuildSelectedSignalCards();
                    trace.mark("cards");
                    if(graphCanvas!=null){
                        graphCanvas.presentTogether(()->{
                            applyRenderedPresentation(tracks, inv);
                            graphCanvas.prepareSharedViewport(appliedStart, appliedEnd);
                            graphCanvas.setUnknownOrderOverview(appliedUnknown);
                            graphCanvas.setFileNavigator(appliedFileFocus?appliedFocus:-1, appliedFileFocus?slotSnapshot.size():0);
                            graphCanvas.setUnloadedKeys(unloaded);
                            graphCanvas.setLogicalTracks(tracks);
                            trace.mark("canvas");
                            applyCanvasVisibility();
                            if(appliedFileFocus){
                                cursorAAbsolute=null;
                                cursorBAbsolute=null;
                                cursorADisplay=null;
                                cursorBDisplay=null;
                                cursorMeasurementId="";
                            }
                            applyCursorVisuals();
                        });
                    }
                    trace.mark("present");
                    applyPendingMapCursor(appliedUnknown);
                    if(cursorA!=null||cursorB!=null) resolveCursors();
                    updateActiveAnalysisPane();
                    if(mapRoutePane!=null) mapRoutePane.setFilteredSeries(miniSeries(tracks), miniColors(tracks, inv));
                    trace.mark("map");
                    if(appliedFileFocus) publishGraphMeasurement(cursorA, cursorA!=null);
                    populateSummaryTable();
                    trace.mark("summary");
                    int segmentCount=tracks.stream().mapToInt(track->track.segments().size()).sum();
                    int bucketCount=snapshot.stream().mapToInt(series->series==null?0:series.buckets().size()).sum();
                    LOG.log(System.Logger.Level.INFO, ()->"graph-viewport tracks="+tracks.size()
                            +" segments="+segmentCount
                            +" buckets="+bucketCount
                            +" fileFocus="+(appliedFileFocus? (appliedFocus+1)+"/"+slotSnapshot.size() : "off")
                            +" domain="+(appliedUnknown?"unknown-order":sharedDisplayDomain?"shared-utc":"stored")
                            +" stage=ready"
                            +" elapsedMs="+((System.nanoTime()-queryStartedNanos)/1_000_000L)
                            +" queueMs="+trace.queuedMs
                            +" fxQueueMs="+lastViewportFxQueueMs
                            +" queryMs="+trace.queryMs
                            +" slowestQueryMs="+trace.slowestMs
                            +" queries="+trace.queries
                            +" cacheHit="+trace.hits
                            +" cacheMiss="+trace.misses
                            +" exactBuckets="+trace.exactBuckets
                            +" fullSeries="+trace.fullSeries
                            +" paintMs="+(graphCanvas==null?0:graphCanvas.lastRedrawMillis())
                            +" worker="+trace.thread
                            +" fx="+Thread.currentThread().getName()
                            +" stages=["+trace.text+"]"
                            +" width="+width
                            +" range="+appliedStart+".."+appliedEnd);
                    if(statusLabel!=null){
                        boolean anyBuckets=snapshot.stream().anyMatch(s->s!=null&&!s.buckets().isEmpty());
                        if(!anyBuckets && !snapshot.isEmpty()){
                            if(!GraphSharedViewport.isUsable(start,end,dataStart,dataEnd)){
                                setGraphStatus("Viewport invalid — recovering to full selected range");
                                ensureViewportValidOrRecover("EMPTY_QUERY");
                                requestViewport();
                                return;
                            }
                            diagnoseUnexpectedEmptyViewport(snapshot);
                        }
                        updateGraphHelpStatus(tracks.size(), failureCount, !anyBuckets, residenceNote);
                    }
                });
            }catch(java.util.concurrent.CancellationException ignored){
            }catch(RuntimeException ex){
                Platform.runLater(()->{
                    if(!acceptViewportResult(my,session)||statusLabel==null) return;
                    if(graphCanvas!=null){
                        graphCanvas.setUnloadedKeys(java.util.Set.of());
                        graphCanvas.setEmptyHint("Graph query failed: "+ex.getMessage());
                        graphCanvas.setLogicalTracks(List.of());
                    }
                    lastTracks=List.of();
                    setGraphStatus("Graph query failed: "+ex.getMessage());
                });
            }
            }finally{
                VIEWPORT_TRACE.remove();
            }
        });
    }

    private static void markViewport(String stage){
        ViewportTrace trace=VIEWPORT_TRACE.get();
        if(trace!=null) trace.mark(stage);
    }

    private static List<GraphRenderSeries> timedRender(
            GraphViewportEngine engine, GraphViewportRequest request, java.util.function.BooleanSupplier continueQuery){
        var before=engine.queryCache().stats();
        long started=System.nanoTime();
        List<GraphRenderSeries> rendered=engine.render(request, continueQuery);
        ViewportTrace trace=VIEWPORT_TRACE.get();
        if(trace!=null){
            long millis=(System.nanoTime()-started)/1_000_000L;
            var after=engine.queryCache().stats();
            trace.queries++;
            trace.queryMs+=millis;
            if(millis>trace.slowestMs) trace.slowestMs=millis;
            trace.hits+=(int)Math.max(0L, after.hits()-before.hits());
            trace.misses+=(int)Math.max(0L, after.misses()-before.misses());
            for(GraphRenderSeries series:rendered){
                trace.series++;
                if(series.fullResolution()) trace.fullSeries++;
                for(GraphBucket bucket:series.buckets()){
                    trace.buckets++;
                    if(bucket.sampleCount()==1 && bucket.firstTimestampNanos()==bucket.lastTimestampNanos()){
                        trace.exactBuckets++;
                    }
                }
            }
        }
        return rendered;
    }

    private static final class ViewportTrace {
        final String thread=Thread.currentThread().getName();
        final long origin=System.nanoTime();
        final long queuedMs;
        long last=origin;
        final StringBuilder text=new StringBuilder(256);
        int queries;
        long queryMs;
        long slowestMs;
        int hits;
        int misses;
        int series;
        int buckets;
        int exactBuckets;
        int fullSeries;

        ViewportTrace(long scheduledNanos){
            queuedMs=Math.max(0L, (origin-scheduledNanos)/1_000_000L);
        }

        void mark(String stage){
            long now=System.nanoTime();
            text.append(stage).append('=').append((now-last)/1_000_000L).append("ms ");
            last=now;
        }
    }

    private String graphCacheTokenOnFx(){
        if(appService==null) return "none";
        String project=appService.currentProjectId()==null?"":appService.currentProjectId();
        String scope=appService.isProjectWideAnalysis()
                ? "all:"+appService.analysisScope().measurementCount()
                : "file:"+appService.activeMeasurementSourceId();
        return project+"|"+scope+"|"+appService.diagnosticArtifactGeneration()
                +"|"+GraphSignalSessionStore.DECODER_GENERATION;
    }

    private void alignGraphCache(String token){
        if(token==null || token.equals(graphCacheToken)) return;
        graphQueryCache.clear();
        graphCacheToken=token;
        graphCacheGeneration++;
    }

    private static RecordingClock clockFor(String measurementId, java.util.Map<String, java.nio.file.Path> paths){
        if(measurementId==null || measurementId.isBlank() || paths==null) return null;
        java.nio.file.Path path=paths.get(measurementId);
        if(path==null) return null;
        return RecordingClockReader.read(path, measurementId);
    }

    private record FileSlot(String measurementId, String label, long firstNanos, long lastNanos) {}

    private static List<FileSlot> fileSlotsFrom(
            List<GraphMeasurementSeries.PlotGroup> groups,
            List<SignalCatalogEntry> physical,
            List<SignalCatalogEntry> selected){
        java.util.Map<SignalCatalogKey, SignalCatalogEntry> byKey=new java.util.HashMap<>();
        if(selected!=null){
            for(SignalCatalogEntry entry:selected){
                if(entry!=null && entry.key()!=null) byKey.putIfAbsent(entry.key(), entry);
            }
        }
        if(physical!=null){
            for(SignalCatalogEntry entry:physical){
                if(entry!=null && entry.key()!=null) byKey.put(entry.key(), entry);
            }
        }
        java.util.LinkedHashMap<String, long[]> span=new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> labels=new java.util.LinkedHashMap<>();
        for(GraphMeasurementSeries.PlotGroup group:groups){
            for(int i=0;i<group.physicalKeys().size();i++){
                SignalCatalogKey key=group.physicalKeys().get(i);
                String id=key.sourceFile()==null?"":key.sourceFile().trim();
                if(id.isEmpty() || com.stellar.platform.processing.catalog.ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equalsIgnoreCase(id)) continue;
                SignalCatalogEntry entry=byKey.get(key);
                if(entry==null || entry.firstTimestampNanos()==null || entry.lastTimestampNanos()==null) continue;
                long first=entry.firstTimestampNanos();
                long last=entry.lastTimestampNanos();
                long[] existing=span.get(id);
                if(existing==null){
                    span.put(id, new long[]{first, last});
                    String label=i<group.measurementLabels().size()?group.measurementLabels().get(i):id;
                    labels.put(id, label==null||label.isBlank()?id:label);
                }else{
                    existing[0]=Math.min(existing[0], first);
                    existing[1]=Math.max(existing[1], last);
                }
            }
        }
        java.util.ArrayList<FileSlot> slots=new java.util.ArrayList<>();
        for(var row:span.entrySet()){
            long[] range=row.getValue();
            slots.add(new FileSlot(row.getKey(), labels.get(row.getKey()), range[0], range[1]));
        }
        return List.copyOf(slots);
    }

    private boolean clocksComparable(List<FileSlot> slots, java.util.Map<String, java.nio.file.Path> paths){
        String token=slots.stream().map(FileSlot::measurementId).collect(java.util.stream.Collectors.joining("|"));
        if(token.equals(clockDecisionToken)) return clockDecisionComparable;
        boolean comparable=true;
        for(FileSlot slot:slots){
            RecordingClock clock=clockFor(slot.measurementId(), paths);
            if(clock==null || !clock.comparable()){
                comparable=false;
                break;
            }
        }
        clockDecisionToken=token;
        clockDecisionComparable=comparable;
        return comparable;
    }

    private static int indexOfMeasurement(GraphMeasurementSeries.PlotGroup group, String measurementId){
        for(int i=0;i<group.physicalKeys().size();i++){
            String id=group.physicalKeys().get(i).sourceFile();
            if(measurementId.equals(id)) return i;
        }
        return -1;
    }

    private SharedDisplayTimeline timelineFor(List<FileSlot> slots, java.util.Map<String, java.nio.file.Path> paths){
        java.util.ArrayList<SharedDisplayTimeline.Member> members=new java.util.ArrayList<>();
        for(FileSlot slot:slots){
            RecordingClock clock=clockFor(slot.measurementId(), paths);
            if(clock==null || !clock.comparable() || slot.lastNanos()<slot.firstNanos()) return null;
            members.add(new SharedDisplayTimeline.Member(
                    slot.measurementId(), clock.utcStartNanos(), slot.firstNanos(), slot.lastNanos()));
        }
        return members.isEmpty()?null:SharedDisplayTimeline.of(members);
    }

    private void adoptSharedDisplay(SharedDisplayTimeline timeline, long generation, int session){
        if(!acceptViewportResult(generation, session)) return;
        preferredTimelineGroup=TimelineGroup.VERIFIED_UTC;
        unknownOrderDomain=false;
        sharedOriginUtc=timeline.originUtcNanos();
        sharedDisplayStart=timeline.displayStart();
        sharedDisplayEnd=timeline.displayEnd();
        sharedDisplayDomain=true;
        fileFocusActive=false;
        dataStart=sharedDisplayStart;
        dataEnd=sharedDisplayEnd>sharedDisplayStart?sharedDisplayEnd:sharedDisplayStart+1;
        long fitStart=dataStart;
        long fitEnd=dataEnd;
        if(savedUtcViewport!=null && savedUtcViewport[1]>savedUtcViewport[0]
                && savedUtcViewport[0]>=dataStart && savedUtcViewport[1]<=dataEnd){
            fitStart=savedUtcViewport[0];
            fitEnd=savedUtcViewport[1];
        }
        commitViewport("SHARED_UTC", fitStart, fitEnd);
        publishTimelineContext();
        requestViewport();
    }

    private void adoptUnknownOrder(UnknownOrderLayout layout, long generation, int session){
        if(!acceptViewportResult(generation, session) || layout==null || layout.size()==0) return;
        preferredTimelineGroup=TimelineGroup.UNKNOWN_ORDER;
        activeUnknownLayout=layout;
        leaveSharedDisplay();
        unknownOrderDomain=true;
        fileFocusActive=false;
        unknownGroupCount=layout.size();
        dataStart=layout.layoutStart();
        dataEnd=layout.layoutEnd();
        long fitStart=dataStart;
        long fitEnd=dataEnd;
        if(savedUnknownViewport!=null && savedUnknownViewport[1]>savedUnknownViewport[0]
                && savedUnknownViewport[0]>=dataStart && savedUnknownViewport[1]<=dataEnd){
            fitStart=savedUnknownViewport[0];
            fitEnd=savedUnknownViewport[1];
        }
        commitViewport("UNKNOWN_ORDER", fitStart, fitEnd);
        publishTimelineContext();
        requestViewport();
    }

    /**
     * One recording keeps its sample timestamps. The equal-width slot is a file position and is
     * not written into the viewport that Engineering reads as duration.
     */
    private void adoptStoredClock(long mappedStart, long mappedEnd, long generation, int session){
        if(!acceptViewportResult(generation, session)) return;
        unknownOrderDomain=false;
        activeUnknownLayout=null;
        if(preferredTimelineGroup==TimelineGroup.UNKNOWN_ORDER){
            preferredTimelineGroup=null;
        }
        savedUnknownViewport=null;
        leaveSharedDisplay();
        publishTimelineContext();
        if(refreshFullAnalysisRange()
                && mappedEnd-mappedStart>UnknownOrderLayout.EQUAL_SLOT_SPAN
                && mappedStart<=dataStart+1 && mappedEnd>=dataEnd-1){
            if(fitToFullRange("STORED_CLOCK")) requestViewport();
            return;
        }
        if(mappedEnd>mappedStart && (mappedStart!=start || mappedEnd!=end)){
            if(refreshFullAnalysisRange()){
                long[] placed=GraphSharedViewport.placeWindow(mappedStart, mappedEnd, dataStart, dataEnd);
                commitViewport("STORED_CLOCK", placed[0], placed[1]);
            }else{
                commitViewport("STORED_CLOCK", mappedStart, mappedEnd);
            }
        }
        requestViewport();
    }

    private void appendUnknownTracks(
            GraphViewportEngine engine,
            List<GraphMeasurementSeries.PlotGroup> groups,
            RecordingGroupIndex groupIndex,
            UnknownOrderLayout layout,
            long renderStart,
            long renderEnd,
            int width,
            long generation,
            int session,
            java.util.List<GraphRenderSeries> rendered,
            java.util.List<GraphLogicalTrack> builtTracks,
            java.util.List<String> failures
    ){
        for(GraphMeasurementSeries.PlotGroup group:groups){
            if(!acceptViewportResult(generation, session)) return;
            TimelineViewportPlanner.Plan plan=planFor(
                    group, groupIndex, TimelineGroup.UNKNOWN_ORDER, renderStart, renderEnd);
            markViewport("plan");
            java.util.Set<String> visible=measurementIds(plan.visible());
            java.util.ArrayList<GraphTrackSegment> segments=new java.util.ArrayList<>();
            for(UnknownOrderLayout.Slot slot:layout.slots()){
                if(!acceptViewportResult(generation, session)) return;
                if(layout.storedQuery(slot, renderStart, renderEnd)==null) continue;
                String label=slot.sourceFile()==null?"":slot.sourceFile();
                if(!visible.contains(slot.measurementId())){
                    segments.add(emptyLayoutSegment(group, label, slot));
                    continue;
                }
                int match=indexOfMeasurement(group, slot.measurementId());
                if(match<0){
                    segments.add(emptyLayoutSegment(group, label, slot));
                    continue;
                }
                TimelineViewportPlanner.SourceQueryWindow window=windowFor(plan.visible(), slot.measurementId());
                long queryStart=window==null?slot.storedStartNanos():window.storedStartNanos();
                long queryEnd=window==null?slot.storedEndNanos():window.storedEndNanos();
                int before=segments.size();
                int pixels=com.stellar.platform.desktop.ui.graph.GraphOverviewMode.slotQueryPixels(
                        slot.layoutStart(), slot.layoutEnd(), renderStart, renderEnd, width);
                appendSegment(engine, group, match, queryStart, Math.max(queryStart, queryEnd), pixels,
                        generation, session, false, null, rendered, segments, failures);
                for(int s=before;s<segments.size();s++){
                    GraphTrackSegment segment=segments.get(s);
                    GraphRenderSeries series=segment.series();
                    GraphRenderSeries placed=new GraphRenderSeries(
                            series.key(), series.unit(), series.sourceSampleCount(), series.fullResolution(),
                            slot.storedStartNanos(), slot.storedEndNanos(), series.buckets());
                    segments.set(s, new GraphTrackSegment(placed, label.isBlank()?segment.measurementLabel():label,
                            null, slot.layoutStart(), slot.layoutEnd()));
                }
            }
            markViewport("visible");
            warmPrefetch(engine, group, plan, null, width, generation, session);
            markViewport("prefetch");
            if(segments.isEmpty()){
                segments.add(new GraphTrackSegment(new GraphRenderSeries(
                        group.logicalKey(), group.unit(), 0, false, renderStart, renderEnd, List.of()),
                        "", null));
            }
            builtTracks.add(new GraphLogicalTrack(group.logicalKey(), segments, false));
        }
    }

    private static GraphTrackSegment emptyLayoutSegment(
            GraphMeasurementSeries.PlotGroup group, String label, UnknownOrderLayout.Slot slot){
        return new GraphTrackSegment(new GraphRenderSeries(
                group.logicalKey(), group.unit(), 0, true,
                slot.storedStartNanos(), slot.storedEndNanos(), List.of()),
                label, null, slot.layoutStart(), slot.layoutEnd());
    }

    private static TimelineViewportPlanner.Plan planFor(
            GraphMeasurementSeries.PlotGroup group,
            RecordingGroupIndex index,
            TimelineGroup timelineGroup,
            long renderStart,
            long renderEnd
    ){
        java.util.Map<String, String> units=new java.util.LinkedHashMap<>();
        for(SignalCatalogKey key:group.physicalKeys()){
            if(key.sourceFile()!=null) units.put(key.sourceFile(), group.unit());
        }
        return TimelineViewportPlanner.plan(index, timelineGroup, renderStart, renderEnd,
                new TimelineViewportPlanner.LogicalSignalSelection(group.logicalKey().signalName(), group.unit()),
                units);
    }

    private static java.util.Set<String> measurementIds(List<TimelineViewportPlanner.SourceQueryWindow> windows){
        java.util.LinkedHashSet<String> ids=new java.util.LinkedHashSet<>();
        for(TimelineViewportPlanner.SourceQueryWindow window:windows) ids.add(window.measurementId());
        return ids;
    }

    private static TimelineViewportPlanner.SourceQueryWindow windowFor(
            List<TimelineViewportPlanner.SourceQueryWindow> windows, String measurementId){
        for(TimelineViewportPlanner.SourceQueryWindow window:windows){
            if(measurementId.equals(window.measurementId())) return window;
        }
        return null;
    }

    private static void appendMissingUtc(
            GraphMeasurementSeries.PlotGroup group,
            RecordingGroupIndex index,
            SharedDisplayTimeline timeline,
            java.util.Set<String> visible,
            long renderStart,
            long renderEnd,
            java.util.List<GraphTrackSegment> segments
    ){
        if(timeline==null || visible==null) return;
        for(RecordingDescriptor recording:index.verifiedUtc()){
            if(visible.contains(recording.measurementId())) continue;
            SharedDisplayTimeline.Member member=timeline.member(recording.measurementId());
            if(member==null || timeline.storedQuery(member, renderStart, renderEnd)==null) continue;
            int match=indexOfMeasurement(group, recording.measurementId());
            String label=match>=0 && match<group.measurementLabels().size()
                    ? group.measurementLabels().get(match) : recording.sourceFile();
            segments.add(new GraphTrackSegment(new GraphRenderSeries(
                    group.logicalKey(), group.unit(), 0, true,
                    member.storedStartNanos(), member.storedEndNanos(), List.of()),
                    label, timeline.displayOffsetNanos(member)));
        }
    }

    /** Cache at most the planner's adjacent windows. They are not drawn and are not a visible query. */
    private void warmPrefetch(
            GraphViewportEngine engine,
            GraphMeasurementSeries.PlotGroup group,
            TimelineViewportPlanner.Plan plan,
            SharedDisplayTimeline timeline,
            int width,
            long generation,
            int session
    ){
        if(plan==null || plan.prefetch().isEmpty()) return;
        int warmed=0;
        for(TimelineViewportPlanner.SourceQueryWindow window:plan.prefetch()){
            if(warmed>=TimelineViewportPlanner.MAX_PREFETCH || !acceptViewportResult(generation, session)) return;
            int match=indexOfMeasurement(group, window.measurementId());
            if(match<0) continue;
            int pixels=width;
            long origin=0L;
            if(plan.group()==TimelineGroup.UNKNOWN_ORDER){
                pixels=com.stellar.platform.desktop.ui.graph.GraphOverviewMode.slotQueryPixels(
                        0L, UnknownOrderLayout.EQUAL_SLOT_SPAN, plan.displayStart(), plan.displayEnd(), width);
            }else if(timeline!=null){
                SharedDisplayTimeline.Member member=timeline.member(window.measurementId());
                if(member!=null) origin=timeline.displayOffsetNanos(member);
            }
            try{
                timedRender(engine, new GraphViewportRequest(
                        List.of(group.physicalKeys().get(match)),
                        window.storedStartNanos(), window.storedEndNanos(), Math.max(1, pixels), origin),
                        ()->acceptViewportResult(generation, session)&&!Thread.currentThread().isInterrupted());
                warmed++;
            }catch(java.util.concurrent.CancellationException cancelled){
                throw cancelled;
            }catch(RuntimeException ignored){
                return;
            }
        }
    }

    private static RecordingGroupIndex recordingGroupIndex(
            List<FileSlot> slots, java.util.Map<String, java.nio.file.Path> paths){
        java.util.ArrayList<RecordingDescriptor> recordings=new java.util.ArrayList<>();
        for(FileSlot slot:slots){
            RecordingClock clock=clockFor(slot.measurementId(), paths);
            if(clock==null || clock.measurementId()==null || clock.measurementId().isBlank()){
                clock=RecordingClock.unordered(slot.measurementId(), TimestampSource.NONE,
                        ClockKind.RELATIVE_ONLY, TimeUnitKind.NANOSECONDS, "clock unavailable");
            }
            long end=Math.max(slot.firstNanos(), slot.lastNanos());
            recordings.add(new RecordingDescriptor(slot.measurementId(), slot.label(), clock, slot.firstNanos(), end));
        }
        return RecordingGroupIndex.of(recordings);
    }

    private void leaveSharedDisplay(){
        sharedDisplayDomain=false;
        sharedOriginUtc=0L;
        sharedDisplayStart=0L;
        sharedDisplayEnd=0L;
        cursorADisplay=null;
        cursorBDisplay=null;
    }

    /** @return false when this file has no comparable clock, so a shared timeline must not be used */
    private boolean appendSegment(
            GraphViewportEngine engine,
            GraphMeasurementSeries.PlotGroup group,
            int index,
            long renderStart,
            long renderEnd,
            int width,
            long generation,
            int session,
            boolean wantOrigin,
            SharedDisplayTimeline timeline,
            java.util.List<GraphRenderSeries> rendered,
            java.util.List<GraphTrackSegment> segments,
            java.util.List<String> failures
    ){
        SignalCatalogKey key=group.physicalKeys().get(index);
        String label=index<group.measurementLabels().size()?group.measurementLabels().get(index):"";
        try{
            long queryStart=renderStart;
            long queryEnd=renderEnd;
            long cacheOrigin=0L;
            Long displayOffset=null;
            boolean clockOk=true;
            if(wantOrigin){
                SharedDisplayTimeline.Member member=timeline==null?null:timeline.member(key.sourceFile());
                if(member==null){
                    clockOk=false;
                }else{
                    cacheOrigin=timeline.displayOffsetNanos(member);
                    displayOffset=cacheOrigin;
                    long[] stored=timeline.storedQuery(member, renderStart, renderEnd);
                    if(stored==null){
                        failedTraces.remove(key);
                        segments.add(new GraphTrackSegment(new GraphRenderSeries(
                                key, group.unit(), 0, false, member.storedStartNanos(), member.storedEndNanos(), List.of()),
                                label, displayOffset));
                        return true;
                    }
                    queryStart=stored[0];
                    queryEnd=stored[1];
                }
            }
            List<GraphRenderSeries> one=timedRender(engine,
                    new GraphViewportRequest(List.of(key), queryStart, queryEnd, width, cacheOrigin),
                    ()->acceptViewportResult(generation, session)&&!Thread.currentThread().isInterrupted());
            failedTraces.remove(key);
            for(GraphRenderSeries series:one){
                rendered.add(series);
                segments.add(new GraphTrackSegment(series, label, displayOffset));
            }
            return clockOk;
        }catch(java.util.concurrent.CancellationException cancelled){
            throw cancelled;
        }catch(RuntimeException ex){
            failedTraces.put(key, ex.getMessage()==null?"query failed":ex.getMessage());
            failures.add(key.signalName()+": "+ex.getMessage());
            if(renderEnd>=renderStart){
                segments.add(new GraphTrackSegment(new GraphRenderSeries(
                        key, group.unit(), 0, false, renderStart, renderEnd, List.of()), label, null));
            }
            return false;
        }
    }

    private static List<GraphRenderSeries> miniSeries(List<GraphLogicalTrack> tracks){
        java.util.ArrayList<GraphRenderSeries> out=new java.util.ArrayList<>();
        for(GraphLogicalTrack track:tracks){
            if(track.segments().isEmpty()) continue;
            GraphRenderSeries first=track.segments().getFirst().series();
            out.add(new GraphRenderSeries(
                    track.logicalKey(), first.unit(), first.sourceSampleCount(), first.fullResolution(),
                    first.viewportStartTimestampNanos(), first.viewportEndTimestampNanos(), first.buckets()));
        }
        return List.copyOf(out);
    }

    private static List<Integer> miniColors(List<GraphLogicalTrack> tracks, GraphInvestigationState inv){
        java.util.ArrayList<Integer> colors=new java.util.ArrayList<>();
        for(GraphLogicalTrack track:tracks){
            if(track.segments().isEmpty()) continue;
            colors.add(inv==null?colors.size():inv.colorIndex(track.logicalKey()));
        }
        return List.copyOf(colors);
    }

    @FXML private void onPreviousFile(){ moveFileFocus(-1); }
    @FXML private void onNextFile(){ moveFileFocus(1); }

    private void focusFileAt(int index){
        if(!fileFocusActive || fileSlots.isEmpty()) return;
        int next=Math.max(0, Math.min(index, fileSlots.size()-1));
        if(next==focusedFileIndex) return;
        focusedFileIndex=next;
        fitFocusedFile();
        publishGraphMeasurement(cursorA, cursorA!=null);
    }

    private void moveFileFocus(int delta){
        if(!fileFocusActive || fileSlots.isEmpty()) return;
        int next=Math.max(0, Math.min(fileSlots.size()-1, focusedFileIndex+delta));
        if(next==focusedFileIndex) return;
        focusedFileIndex=next;
        fitFocusedFile();
        publishGraphMeasurement(cursorA, cursorA!=null);
    }

    private void fitFocusedFile(){
        if(!fileFocusActive || focusedFileIndex<0 || focusedFileIndex>=fileSlots.size()) return;
        publishTimelineContext();
        FileSlot slot=fileSlots.get(focusedFileIndex);
        dataStart=slot.firstNanos();
        dataEnd=slot.lastNanos()<=slot.firstNanos()?slot.firstNanos()+1:slot.lastNanos();
        commitViewport("FILE_FOCUS", dataStart, dataEnd);
        requestViewport();
    }

    private void applyFileFocusChrome(){
        boolean multi=fileSlots.size()>1;
        if(fileFocusBar!=null){
            fileFocusBar.setVisible(multi);
            fileFocusBar.setManaged(multi);
        }
        boolean navigate=GraphOverviewMode.showFileNavigation(fileFocusActive, fileSlots.size());
        if(previousFileButton!=null){
            previousFileButton.setVisible(navigate);
            previousFileButton.setManaged(navigate);
        }
        if(nextFileButton!=null){
            nextFileButton.setVisible(navigate);
            nextFileButton.setManaged(navigate);
        }
        applyingTimelineChrome=true;
        try{
            if(fileFocusToggle!=null) fileFocusToggle.setSelected(fileFocusActive);
            if(utcGroupButton!=null) utcGroupButton.setDisable(utcGroupCount<=0);
            if(unknownGroupButton!=null) unknownGroupButton.setDisable(unknownGroupCount<=0);
        }finally{
            applyingTimelineChrome=false;
        }
        if(timelineGroupLabel!=null){
            String group=unknownOrderDomain?"UNKNOWN ORDER"
                    :sharedDisplayDomain?"VERIFIED UTC"
                    :fileFocusActive?"FILE FOCUS":"";
            timelineGroupLabel.setText(group);
        }
        if(!navigate){
            if(fileFocusLabel!=null) fileFocusLabel.setText("");
            return;
        }
        FileSlot slot=fileSlots.get(Math.max(0, Math.min(focusedFileIndex, fileSlots.size()-1)));
        GraphFileFocus focus=new GraphFileFocus(focusedFileIndex, fileSlots.size());
        if(fileFocusLabel!=null) fileFocusLabel.setText(focus.caption(slot.label()));
        if(previousFileButton!=null) previousFileButton.setDisable(!focus.hasPrevious());
        if(nextFileButton!=null) nextFileButton.setDisable(!focus.hasNext());
    }

    @FXML private void onShowUtcGroup(){ switchTimelineGroup(TimelineGroup.VERIFIED_UTC); }
    @FXML private void onShowUnknownGroup(){ switchTimelineGroup(TimelineGroup.UNKNOWN_ORDER); }

    @FXML private void onToggleFileFocus(){
        if(applyingTimelineChrome || fileFocusToggle==null) return;
        fileFocusRequested=fileFocusToggle.isSelected();
        if(fileFocusRequested){
            rememberViewport();
            unknownOrderDomain=false;
            leaveSharedDisplay();
            fileFocusActive=true;
            if(focusedFileIndex<0 || focusedFileIndex>=fileSlots.size()) focusedFileIndex=0;
            publishTimelineContext();
            fitFocusedFile();
            return;
        }
        fileFocusActive=false;
        publishTimelineContext();
        requestViewport();
    }

    private void switchTimelineGroup(TimelineGroup group){
        if(group==TimelineGroup.UNKNOWN_ORDER && unknownGroupCount==1 && utcGroupCount==0){
            return;
        }
        rememberViewport();
        preferredTimelineGroup=group;
        fileFocusRequested=false;
        fileFocusActive=false;
        unknownOrderDomain=false;
        leaveSharedDisplay();
        if(fileFocusToggle!=null){
            applyingTimelineChrome=true;
            try{ fileFocusToggle.setSelected(false); }
            finally{ applyingTimelineChrome=false; }
        }
        requestViewport();
    }

    private int indexOfRestoredFocus(List<FileSlot> slots){
        String id=pendingRestoredFocusId;
        if(id==null || id.isBlank() || slots==null) return -1;
        pendingRestoredFocusId="";
        for(int i=0;i<slots.size();i++){
            if(id.equalsIgnoreCase(slots.get(i).measurementId())) return i;
        }
        return -1;
    }

    private void publishTimelineContext(){
        GraphInvestigationState inv=investigation();
        if(inv==null) return;
        String group=preferredTimelineGroup==null?"":preferredTimelineGroup.name();
        if(unknownOrderDomain) group="UNKNOWN_ORDER";
        if(sharedDisplayDomain) group="VERIFIED_UTC";
        String file="";
        if(fileFocusActive && focusedFileIndex>=0 && focusedFileIndex<fileSlots.size()){
            file=fileSlots.get(focusedFileIndex).measurementId();
        }
        String domain=fileFocusActive && !file.isBlank() ? "FILE_FOCUS"
                : unknownOrderDomain ? "UNKNOWN_ORDER"
                : sharedDisplayDomain ? "VERIFIED_UTC" : "";
        inv.setTimelineContext(group, file, domain);
    }

    private boolean unknownOrderLayoutViewport(){
        return unknownOrderDomain && !fileFocusActive;
    }

    private boolean unknownOrderCrossFile(){
        if(!unknownOrderLayoutViewport()) return false;
        java.util.LinkedHashSet<String> ids=new java.util.LinkedHashSet<>();
        for(GraphLogicalTrack track:lastTracks){
            for(GraphTrackSegment segment:track.segments()){
                String id=segment.series().key()==null?"":segment.series().key().sourceFile();
                if(id!=null && !id.isBlank()) ids.add(id);
            }
        }
        return ids.size()>1;
    }

    private TimelineGroup ownerGroup(String measurementId){
        RecordingGroupIndex index=activeGroupIndex;
        if(index==null || measurementId==null || measurementId.isBlank()) return null;
        UnknownOrderLayout layout=index.unknownLayout();
        if(layout!=null){
            for(UnknownOrderLayout.Slot slot:layout.slots()){
                if(measurementId.equalsIgnoreCase(slot.measurementId())) return TimelineGroup.UNKNOWN_ORDER;
            }
        }
        if(index.utcTimeline()!=null && index.utcTimeline().member(measurementId)!=null){
            return TimelineGroup.VERIFIED_UTC;
        }
        return null;
    }

    private Long layoutForStored(String measurementId, long stored){
        UnknownOrderLayout layout=activeUnknownLayout!=null?activeUnknownLayout
                :activeGroupIndex==null?null:activeGroupIndex.unknownLayout();
        if(layout==null || measurementId==null) return null;
        for(UnknownOrderLayout.Slot slot:layout.slots()){
            if(!measurementId.equalsIgnoreCase(slot.measurementId())) continue;
            long span=slot.storedSpanNanos();
            double fraction=span<=0?0d:(stored-slot.storedStartNanos())/(double)span;
            if(fraction<0d) fraction=0d;
            if(fraction>1d) fraction=1d;
            long width=slot.layoutEnd()-slot.layoutStart();
            return slot.layoutStart()+(long)(width*fraction);
        }
        return null;
    }

    private void revealUnknownSlot(String measurementId){
        UnknownOrderLayout layout=activeUnknownLayout;
        if(layout==null || measurementId==null || !unknownOrderLayoutViewport()) return;
        for(UnknownOrderLayout.Slot slot:layout.slots()){
            if(!measurementId.equalsIgnoreCase(slot.measurementId())) continue;
            if(slot.layoutEnd()<start || slot.layoutStart()>end){
                commitViewport("GPS_FOCUS", slot.layoutStart(), slot.layoutEnd());
                requestViewport();
            }
            return;
        }
    }

    private void applyPendingMapCursor(boolean unknownOverview){
        MeasurementCursor pending=pendingMapCursor;
        if(pending==null || !pending.hasMeasurement() || fileFocusActive) return;
        pendingMapCursor=null;
        applyingMapCursor=true;
        try{
            cursorMeasurementId=pending.measurementId();
            cursorA=pending.relativeTimestampNanos();
            cursorAAbsolute=pending.absoluteTimestampNanos();
            if(unknownOverview){
                cursorADisplay=layoutForStored(pending.measurementId(), cursorA);
            }else if(sharedDisplayDomain && cursorAAbsolute!=null){
                try{
                    cursorADisplay=Math.subtractExact(cursorAAbsolute, sharedOriginUtc);
                }catch(ArithmeticException overflow){
                    cursorADisplay=null;
                }
            }
            applyCursorVisuals();
        }finally{
            applyingMapCursor=false;
        }
    }

    private void rememberViewport(){
        if(!GraphSharedViewport.isStructurallyValid(start, end)) return;
        if(unknownOrderDomain) savedUnknownViewport=new long[]{start, end};
        if(sharedDisplayDomain) savedUtcViewport=new long[]{start, end};
    }

    private void applyRenderedPresentation(List<GraphLogicalTrack> tracks, GraphInvestigationState inv){
        if(graphCanvas==null) return;
        java.util.Map<SignalCatalogKey,Integer> colors=new java.util.LinkedHashMap<>();
        java.util.Map<SignalCatalogKey,String> labels=new java.util.LinkedHashMap<>();
        boolean relative=false;
        for(GraphLogicalTrack track:tracks){
            int color=inv==null?colors.size():inv.colorIndex(track.logicalKey());
            colors.put(track.logicalKey(), color);
            labels.put(track.logicalKey(), track.logicalKey().signalName());
            if(track.segments().size()>1 && !track.sharedAbsoluteTimeline()) relative=true;
            for(GraphTrackSegment segment:track.segments()){
                String file=segment.measurementLabel();
                String name=track.logicalKey().signalName();
                labels.put(segment.series().key(), file==null||file.isBlank()?name:name+" · "+file);
            }
        }
        graphCanvas.setMeasurementRelativeTime(fileFocusActive || relative);
        graphCanvas.setTracePresentation(labels, colors);
        analysisLabels=java.util.Map.copyOf(labels);
        analysisScopeNote=fileFocusActive && focusedFileIndex>=0 && focusedFileIndex<fileSlots.size()
                ? fileSlots.get(focusedFileIndex).label() : "";
    }
    private void clearRenderedAnalysis(){
        lastRendered=List.of();
        lastTracks=List.of();
        fileFocusActive=false;
        analysisScopeNote="";
        applyFileFocusChrome();
        if(graphCanvas!=null) graphCanvas.setFileNavigator(-1, 0);
        if(graphCanvas!=null){
            if(selectedSignalKeys.isEmpty()){
                graphCanvas.setEmptyHint("No signals plotted yet.");
            }else if(!failedTraces.isEmpty()){
                graphCanvas.setEmptyHint("Selected signal data unavailable.");
            }else if(!selectedKeys().isEmpty()){
                graphCanvas.setEmptyHint("No tracks to display.");
            }else{
                graphCanvas.setEmptyHint("All selected signals are hidden. Show a signal to plot.");
            }
            graphCanvas.clearGraphData();
        }
        if(engineeringPane!=null) engineeringPane.clearViewportState();
        if(analyticsPane!=null) analyticsPane.clearChartData();
        if(summaryStatsTable!=null) summaryStatsTable.getItems().clear();
        if(cursorCompareTable!=null) cursorCompareTable.getItems().clear();
        if(selectedSignalCards!=null) selectedSignalCards.getChildren().clear();
    }

    /**
     * When catalog metadata says the signal has samples that intersect the shared viewport
     * but the query returned empty buckets, this is a state/query error — not a normal empty view.
     */
    private void diagnoseUnexpectedEmptyViewport(List<GraphRenderSeries> snapshot){
        for(GraphRenderSeries series:snapshot){
            if(series==null||!series.buckets().isEmpty()) continue;
            SignalCatalogEntry entry=allSignals.stream()
                    .filter(e->e.key().equals(series.key()))
                    .findFirst().orElse(null);
            if(entry==null) continue;
            Long first=entry.firstTimestampNanos();
            Long last=entry.lastTimestampNanos();
            long samples=entry.sampleCount();
            boolean intersects=first!=null&&last!=null&&last>=start&&first<=end;
            if(samples>0 && intersects){
                LOG.log(System.Logger.Level.WARNING, ()->"Graph empty query despite intersecting catalog range:"
                        +" signal="+series.key().signalName()
                        +" samples="+samples
                        +" signalRange="+first+".."+last
                        +" viewport="+GraphSharedViewport.formatNanosRange(start,end)
                        +" generation="+generation);
            }
        }
    }
    /**
     * Focus left inside a tab that was just deselected makes TabPane select that tab again.
     * Move focus to the tab header. Do not schedule a later selection change.
     */
    private void releaseFocusFromInactiveAnalysisTabs() {
        if (analysisTabs == null || analysisTabs.getScene() == null) {
            return;
        }
        Node owner = analysisTabs.getScene().getFocusOwner();
        int selected = analysisTabs.getSelectionModel().getSelectedIndex();
        if (owner == null || selected < 0 || !focusInsideInactiveAnalysisTab(owner, selected)) {
            return;
        }
        analysisTabs.requestFocus();
    }

    private boolean focusInsideInactiveAnalysisTab(Node owner, int selected) {
        for (int i = 0; i < analysisTabs.getTabs().size(); i++) {
            if (i == selected) {
                continue;
            }
            Node content = analysisTabs.getTabs().get(i).getContent();
            if (content != null && isNodeInside(content, owner)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNodeInside(Node parent, Node node) {
        Node current = node;
        while (current != null) {
            if (current == parent) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    /** Map Route's position in the analysis tabs; navigation from Configuration targets it. */
    private static final int MAP_ROUTE_TAB_INDEX=3;

    /** Keeps unselected tab bodies out of CSS and layout until the user opens them. */
    private void parkInactiveTabs(javafx.scene.control.TabPane tabs){
        if(tabs==null) return;
        javafx.scene.control.Tab selected=tabs.getSelectionModel().getSelectedItem();
        for(javafx.scene.control.Tab tab:tabs.getTabs()){
            Node content=tab.getContent();
            if(tab==selected || content==null) continue;
            parkedTabContent.put(tab, content);
            tab.setContent(null);
        }
    }

    private void restoreParkedTab(javafx.scene.control.Tab tab){
        if(tab==null) return;
        Node content=parkedTabContent.remove(tab);
        if(content==null || tab.getContent()!=null) return;
        tab.setContent(content);
        if(content.getParent()!=null) return;
        // The tab skin may have handled selection while the body was still parked.
        javafx.application.Platform.runLater(()->{
            if(content.getParent()!=null || tab.getContent()!=content) return;
            if(tab.getTabPane()==null || tab.getTabPane().getSelectionModel().getSelectedItem()!=tab) return;
            tab.setContent(null);
            tab.setContent(content);
        });
    }

    private void updateActiveAnalysisPane(){
        if(analysisTabs==null)return;
        int selected=analysisTabs.getSelectionModel().getSelectedIndex();
        boolean crossFileUnknown=unknownOrderCrossFile();
        if(selected==1){
            if(lastRendered.isEmpty() || crossFileUnknown){
                // Do not materialize WebKit merely to show an empty chart after Clear.
                analyticsPane.clearChartData();
            }else{
                analyticsPane.activate();
                analyticsPane.setScopedSeries(lastRendered, analysisLabels, analysisScopeNote);
            }
        }else{
            analyticsPane.deactivate();
            if(lastRendered.isEmpty() || crossFileUnknown) analyticsPane.clearChartData();
        }
        if(selected==2){
            if(crossFileUnknown) engineeringPane.showTimelineRefusal(UNKNOWN_ORDER_ANALYSIS_REFUSAL);
            else engineeringPane.setScopedSeries(lastRendered, analysisLabels, analysisScopeNote);
            if(appService!=null){
                engineeringPane.diagnostics().setCatalog(appService.signalsModel().catalog().entries());
            }
        }
        else engineeringPane.clearViewportState();
        if(mapRoutePane!=null){
            if(selected==MAP_ROUTE_TAB_INDEX) activateMapRoute();
            else mapRoutePane.deactivate();
        }
        applyAnalysisToolbar(selected);
        updateEmptyState();
        if(selected!=MAP_ROUTE_TAB_INDEX){
            applySharedAnalysisRangeToViews();
        }
    }

    private void applyAnalysisToolbar(int selected){
        boolean map=selected==MAP_ROUTE_TAB_INDEX;
        setToolbarManaged(graphLayoutModes, !map);
        setToolbarManaged(graphPrimaryTools, !map);
        setToolbarManaged(trackDensityBox, !map);
        setToolbarManaged(mapRouteTools, map);
        if(mapSelectSegmentButton!=null && mapRoutePane!=null){
            mapSelectSegmentButton.setSelected(mapRoutePane.selectSegmentActive());
        }
        relayoutGraphToolbar();
    }

    private static void setToolbarManaged(javafx.scene.Node node, boolean visible){
        if(node==null) return;
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void applySharedAnalysisRangeToViews(){
        if(appService==null) return;
        TimeSelection range=appService.analysisSelection().analysisRange();
        if(range.kind()!=TimeSelection.Kind.RANGE) return;
        if(viewportBeforeMapFilterStart==null){
            viewportBeforeMapFilterStart=start;
            viewportBeforeMapFilterEnd=end;
        }
        applyViewport(range.startNanos(), range.endNanos());
    }

    @FXML private void onMapFitRoute(){
        if(mapRoutePane!=null) mapRoutePane.fitRoute();
    }

    @FXML private void onMapSelectSegment(){
        if(mapRoutePane==null) return;
        boolean active=mapSelectSegmentButton!=null && mapSelectSegmentButton.isSelected();
        mapRoutePane.setSelectSegmentActive(active);
    }

    @FXML private void onMapClearSegment(){
        if(mapRoutePane==null) return;
        mapRoutePane.clearSegment();
        if(mapSelectSegmentButton!=null) mapSelectSegmentButton.setSelected(false);
    }

    /**
     * Hands Map Route the already-processed catalog and time-series query. Nothing here can reach a
     * measurement file, so opening the tab never re-parses MF4/ASC/BLF input.
     */
    private List<SignalCatalogEntry> mapCatalogForScope(boolean projectWide){
        if(projectWide){
            var store=appService.signalsModel().catalogStore();
            if(store instanceof com.stellar.platform.processing.catalog.ProjectMergedSignalStore merged){
                return merged.physicalCatalogAll();
            }
        }
        return appService.engineeringCatalog();
    }

    private Map<String, Path> measurementPaths() {
        Map<String, Path> paths = new LinkedHashMap<>();
        if (appService == null || appService.workspace() == null) {
            return paths;
        }
        for (WorkspaceFile file : appService.workspace().measurementFiles()) {
            if (file != null && file.id() != null && file.path() != null) {
                paths.put(file.id(), file.path());
            }
        }
        return paths;
    }

    private void activateMapRoute(){
        GraphQueryProvider p=provider();
        if(p==null||appService==null){ mapRoutePane.resetForFileChange(); return; }
        long from=dataStart==Long.MIN_VALUE?start:dataStart;
        long to=dataEnd==Long.MAX_VALUE?end:dataEnd;
        boolean projectWide=appService.isProjectWideAnalysis();
        List<SignalCatalogEntry> mapCatalog=mapCatalogForScope(projectWide);
        var measurement=appService.activeAnalysisMeasurement();
        if(mapCatalog.isEmpty() || (!projectWide && !measurement.isPresent())){
            mapRoutePane.showScopeUnavailable(measurement.sourceId(), measurement.fileName());
            return;
        }
        long catalogRevision=mapCatalog.stream().mapToLong(SignalCatalogEntry::sampleCount).sum()
                ^ ((long) mapCatalog.size() << 32);
        mapRoutePane.setSelectedSignals(selectedKeys());
        // Only re-read metadata when Configuration actually wrote a mapping; the pane then decides
        // whether the change invalidates the current route.
        int gpsRevision=appService.gpsMappingRevision();
        if(gpsRevision!=appliedGpsMappingRevision){
            appliedGpsMappingRevision=gpsRevision;
            mapRoutePane.setProjectMappings(appService.loadAllGpsSignalMappings());
            mapRoutePane.setDefaultMappings(appService.loadRouteConfigDefaults());
        }
        mapRouteSourceFile=mapRouteIdentity();
        mapRoutePane.setMeasurementPaths(measurementPaths());
        if(projectWide){
            mapRoutePane.activate(MapRouteRequest.projectWide(p.graphQuery(), mapCatalog,
                    appService.currentProjectId(), appService.analysisScope().measurementCount(),
                    catalogRevision, gpsRevision, from, to));
        }else{
            mapRoutePane.activate(new MapRouteRequest(p.graphQuery(),mapCatalog,
                    appService.currentProjectId(),measurement.sourceId(),measurement.fileName(),
                    catalogRevision,gpsRevision,from,to));
        }
        mapRoutePane.setFilteredSeries(miniSeries(lastTracks), miniColors(lastTracks, investigation()));
        restoreMapRouteState();
        mapRoutePane.notifyLayoutChanged("map-tab-activate");
    }

    /**
     * Reapplies the saved map selection for this measurement. Only timestamps are persisted, so the
     * pane re-resolves them against the rebuilt route rather than reloading stored coordinates.
     */
    private void restoreMapRouteState(){
        if(mapRouteStateRestored||appService==null) return;
        mapRouteStateRestored=true;
        appService.loadMapRouteState().ifPresent(saved->{
            MapSelectionMode mode;
            try{ mode=MapSelectionMode.valueOf(saved.mode()); }
            catch(IllegalArgumentException ex){ mode=MapSelectionMode.ALL_ROUTE; }
            restoringMapRouteState=true;
            try{
                mapRoutePane.restoreState(new MapRouteState(mode,saved.pointTimestampNanos(),
                        saved.rangeStartNanos(),saved.rangeEndNanos(),saved.autoSync()));
            }finally{ restoringMapRouteState=false; }
        });
    }

    /** GPS mapping is project configuration; Map Route only links to the canonical editor. */
    private void openGpsMappingConfiguration(){
        if(shell!=null) shell.openGpsMappingConfiguration(appService==null
                ? null
                : appService.activeAnalysisMeasurement());
    }

    /** Return leg of that link: Configuration sends the user here to look at the resulting route. */
    void selectMapRouteTab(){
        if(analysisTabs==null||analysisTabs.getTabs().size()<=MAP_ROUTE_TAB_INDEX) return;
        analysisTabs.getSelectionModel().select(MAP_ROUTE_TAB_INDEX);
    }

    /** Debounced so dragging the map timeline does not turn into a burst of SQLite writes. */
    private void scheduleMapRouteStateSave(){
        if(appService==null||mapRoutePane==null||restoringMapRouteState) return;
        MapRouteState state=mapRoutePane.captureState();
        ScheduledFuture<?> previous=pendingMapRouteSave;
        if(previous!=null) previous.cancel(false);
        pendingMapRouteSave=viewportScheduler.schedule(()->appService.persistMapRouteState(
                state.mode().name(),state.pointTimestampNanos(),
                state.aTimestampNanos(),state.bTimestampNanos(),
                state.autoSyncWithTimeline()),400,TimeUnit.MILLISECONDS);
    }

    private void syncMapSelectSegmentToggle(){
        if(mapSelectSegmentButton==null||mapRoutePane==null) return;
        boolean active=mapRoutePane.selectSegmentActive();
        if(mapSelectSegmentButton.isSelected()!=active){
            mapSelectSegmentButton.setSelected(active);
        }
    }

    /**
     * Map/timeline → waveform. A point moves cursor A; a range becomes the waveform viewport and
     * remembers the previous one so Clear Filter restores it instead of resetting zoom.
     */
    private void onSharedTimeSelectionChanged(TimeSelection selection){
        if(selection.origin().fromWaveform())return;
        MeasurementCursor captured=appService==null?MeasurementCursor.EMPTY:appService.analysisSelection().measurementCursor();
        long token=captured==null?-1L:captured.token();
        Runnable apply=()->{
            MeasurementCursor latest=appService==null?MeasurementCursor.EMPTY:appService.analysisSelection().measurementCursor();
            if(token>=0 && (latest==null || latest.token()!=token)) return;
            switch(selection.kind()){
                case POINT -> {
                    applyingMapCursor=true;
                    try{
                        if(latest!=null && latest.hasMeasurement()){
                            if(fileFocusActive) focusFileFromMap(latest.measurementId());
                            else cursorMeasurementId=latest.measurementId();
                        }
                        cursorA=latest!=null && latest.hasMeasurement()?latest.relativeTimestampNanos():selection.startNanos();
                        cursorAAbsolute=fileFocusActive||latest==null?null:latest.absoluteTimestampNanos();
                        cursorADisplay=null;
                        if(!fileFocusActive && latest!=null && latest.hasMeasurement()){
                            TimelineGroup owner=ownerGroup(latest.measurementId());
                            TimelineGroup active=unknownOrderDomain?TimelineGroup.UNKNOWN_ORDER
                                    :sharedDisplayDomain?TimelineGroup.VERIFIED_UTC:null;
                            if(owner!=null && active!=null && owner!=active){
                                pendingMapCursor=latest;
                                switchTimelineGroup(owner);
                                return;
                            }
                            if(cursorAAbsolute==null) cursorAAbsolute=displayAbsolute(latest.measurementId(), cursorA);
                            if(unknownOrderDomain){
                                cursorADisplay=layoutForStored(latest.measurementId(), cursorA);
                                revealUnknownSlot(latest.measurementId());
                            }else if(sharedDisplayDomain && cursorAAbsolute!=null){
                                try{
                                    cursorADisplay=Math.subtractExact(cursorAAbsolute, sharedOriginUtc);
                                }catch(ArithmeticException overflow){
                                    cursorADisplay=null;
                                }
                            }
                        }
                        applyCursorVisuals();
                        resolveCursors();
                        refreshInvestigationInspector();
                        updateSelectedTimeLabel();
                    }finally{
                        applyingMapCursor=false;
                    }
                }
                case RANGE -> {
                    if(unknownOrderLayoutViewport()) return;
                    if(viewportBeforeMapFilterStart==null){ viewportBeforeMapFilterStart=start; viewportBeforeMapFilterEnd=end; }
                    applyViewport(selection.startNanos(),selection.endNanos());
                }
                case NONE -> {
                    if(viewportBeforeMapFilterStart!=null){
                        applyViewport(viewportBeforeMapFilterStart,viewportBeforeMapFilterEnd);
                        viewportBeforeMapFilterStart=viewportBeforeMapFilterEnd=null;
                    }
                    cursorA=null; if(graphCanvas!=null) graphCanvas.setCursors(cursorA,cursorB); updateCursorLabels(null,null);
                    updateSelectedTimeLabel();
                    refreshInvestigationInspector();
                }
            }
        };
        if(Platform.isFxApplicationThread())apply.run(); else Platform.runLater(apply);
    }

    @Override public void onShow(){
        clearTemporaryLegendVisibility();
    }

    @Override public void onHide(){
        // Leaving Graph ends the temporary legend hide. Membership stays on the workspace.
        clearTemporaryLegendVisibility();
        flushInvestigationForSave();
        generation++;
        cancelPendingGraphWork(true);
        lastRendered=List.of();
        if(graphCanvas!=null) graphCanvas.clearGraphData();
        if(engineeringPane!=null){
            engineeringPane.clearViewportState();
        }
        if(summaryStatsTable!=null) summaryStatsTable.getItems().clear();
        if(cursorCompareTable!=null) cursorCompareTable.getItems().clear();
        if(analyticsPane!=null) analyticsPane.release();
        if(mapRoutePane!=null) mapRoutePane.deactivate();
        GraphInvestigationState inv=investigation();
        if(inv!=null){
            inv.setLiveViewportResults(0);
            inv.setLiveTraceModels(0);
        }
        if(shell!=null) shell.setGraphAnalysisFullScreen(false);
        if(focusGraph){
            applyFocusModeChrome(false);
            restoreSignalPanelAfterFocus();
            boolean restore=inspectorToggledDuringFullScreen
                    ? isInspectorOpen()
                    : (inspectorOpenBeforeFullScreen==null||inspectorOpenBeforeFullScreen);
            focusGraph=false;
            setInspectorVisible(restore, false);
            inspectorOpenBeforeFullScreen=null;
            inspectorToggledDuringFullScreen=false;
        }
        focusGraph=false;
        if(focusGraphButton!=null) focusGraphButton.setText("Full Screen");
        updateInspectorToggleChrome();
        uninstallEscapeHandler();
    }

    private boolean disposed;

    @Override public void dispose(){
        if(disposed){
            return;
        }
        disposed=true;
        dismissOverlapChoices();
        if(appService!=null) appService.setPersistenceFlushHook(null);
        for(Runnable remover:listenerRemovers){
            try{ remover.run(); }catch(RuntimeException ignored){}
        }
        listenerRemovers.clear();
        parkedTabContent.clear();
        onHide();
        if(graphCanvas!=null) graphCanvas.release();
        if(mapRoutePane!=null) mapRoutePane.dispose();
        FxExecutorRelease.shutdownAll(worker, categoricalScan, viewportScheduler);
    }

    private void applyFocusModeChrome(boolean focus){
        if(selectedSignalCards!=null){
            boolean show=!focus && chipStripVisibleBeforeFocus;
            selectedSignalCards.setVisible(show);
            selectedSignalCards.setManaged(show);
        }
        if(graphFileStrip!=null){
            boolean show=!focus && fileStripVisibleBeforeFocus;
            graphFileStrip.setVisible(show);
            graphFileStrip.setManaged(show);
        }
        if(graphSecondaryTools!=null && focus){
            graphSecondaryTools.setVisible(false);
            graphSecondaryTools.setManaged(false);
        }
        if(!focus){
            relayoutGraphToolbar();
        }
        if(focusGraphButton!=null){
            focusGraphButton.setText(focus?"Exit Focus Mode":"Full Screen");
        }
    }

    private javafx.scene.control.ListCell<AnalysisScope> measurementButtonCell(){
        return new javafx.scene.control.ListCell<>(){
            @Override protected void updateItem(AnalysisScope item, boolean empty){
                super.updateItem(item, empty);
                if(empty||item==null){
                    setText(null);
                    setTooltip(null);
                    return;
                }
                String label=item.comboLabel();
                setText(label);
                setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
                setTooltip(new Tooltip(label));
            }
        };
    }

    private void applyMeasurementIdentity(String fileName, String tooltip){
        if(measurementCombo==null) return;
        suppressMeasurementCombo=true;
        try{
            List<AnalysisScope> choices=appService==null?List.of():appService.analysisScopeChoices();
            measurementCombo.getItems().setAll(choices);
            AnalysisScope selected=null;
            if(appService!=null && appService.isProjectWideAnalysis()){
                selected=choices.stream().filter(AnalysisScope::isAll).findFirst().orElse(null);
            }else if(appService!=null){
                String sourceId=appService.activeMeasurementSourceId();
                String file=appService.activeMeasurementFileName();
                for(AnalysisScope choice:choices){
                    if(choice.isSingle() && choice.measurement()
                            .map(m->m.sourceId().equals(sourceId) || m.fileName().equalsIgnoreCase(file))
                            .orElse(false)){
                        selected=choice;
                        break;
                    }
                }
            }
            if(selected!=null){
                measurementCombo.getSelectionModel().select(selected);
            }
            String label=fileName==null||fileName.isBlank()?"—":fileName;
            String tip=tooltip==null||tooltip.isBlank()?label:tooltip;
            measurementCombo.setTooltip(new Tooltip(tip));
            measurementCombo.setMinWidth(160);
            measurementCombo.setPrefWidth(260);
            measurementCombo.setMaxWidth(320);
            HBox.setHgrow(measurementCombo, Priority.NEVER);
        }finally{
            suppressMeasurementCombo=false;
        }
    }

    private void relayoutGraphToolbar(){
        if(graphToolbar==null||toolbarMoreButton==null) return;
        java.util.List<GraphToolbarOverflow.OverflowAction> actions=java.util.List.of(
                new GraphToolbarOverflow.OverflowAction("nav","Signals", this::onBack),
                new GraphToolbarOverflow.OverflowAction("invest","Create Investigation", this::onCreateInvestigation),
                new GraphToolbarOverflow.OverflowAction("invest","Add View to Evidence", this::onAddViewToEvidence),
                new GraphToolbarOverflow.OverflowAction("invest","Set as Investigation Range", this::onSetInvestigationRange),
                new GraphToolbarOverflow.OverflowAction("invest","Capture Snapshot", this::onCaptureInvestigationSnapshot),
                new GraphToolbarOverflow.OverflowAction("view","Clear View", this::onClearSelection)
        );
        GraphToolbarOverflow.apply(graphToolbar, graphSecondaryTools, toolbarMoreButton, actions);
    }

    boolean toolbarOverflowVisibleForTest(){
        return toolbarMoreButton!=null && toolbarMoreButton.isVisible();
    }

    boolean secondaryToolbarManagedForTest(){
        return graphSecondaryTools!=null && graphSecondaryTools.isManaged();
    }

    void relayoutGraphToolbarForTest(){
        relayoutGraphToolbar();
    }

    private void disableEngineeringTab(){
        if(analysisTabs==null) return;
        for(Tab tab:analysisTabs.getTabs()){
            if(tab!=null && "Engineering".equals(tab.getText())){
                tab.setDisable(true);
                tab.setTooltip(new Tooltip("Engineering is temporarily unavailable."));
            }
        }
    }

    private void configureEventActions(){
        layoutEventActions();
        if(inspectorPanel!=null){
            inspectorPanel.widthProperty().addListener((obs, previous, width)->layoutEventActions());
        }
    }

    private void layoutEventActions(){
        if(eventActionRow==null || eventActionMore==null) return;
        double width=inspectorPanel==null?272:inspectorPanel.getWidth();
        if(width<=1 && inspectorPanel!=null) width=inspectorPanel.getPrefWidth();
        com.stellar.platform.desktop.ui.graph.GraphEventActions.layout(
                width, eventActionRow, eventActionMore, eventActionButtons());
    }

    private java.util.List<Button> eventActionButtons(){
        java.util.List<Button> buttons=new java.util.ArrayList<>();
        for(Button button:java.util.List.of(editEventButton, zoomEventButton, linkSnapshotButton, gpsEventButton, deleteEventButton)){
            if(button!=null) buttons.add(button);
        }
        return buttons;
    }

    @FXML private void onAddEvent(){
        Dialog<ButtonType> dialog=buildAddEventDialog();
        if(dialog==null) return;
        java.util.Optional<ButtonType> result=dialog.showAndWait();
        if(result.isEmpty()||result.get()!=ButtonType.OK) return;
        String saved=commitAddEvent(dialog);
        if(saved.isBlank()){
            if(statusLabel!=null) setGraphStatus("The event was not saved. A window needs cursor B after cursor A.");
            return;
        }
        selectedEventId=saved;
        finishGraphEvent(saved);
    }

    private void finishGraphEvent(String saved){
        var bookmark=appService==null?null:appService.findEngineeringBookmark(saved).orElse(null);
        boolean window=bookmark!=null && EngineerEventDocuments.endNanos(bookmark)!=null;
        if(window) jumpToTimestamp(bookmark.timestampNanos(), TimeSelection.Origin.EVENT);
        else clearTransientSelection();
        restoreEngineeringBookmarks();
    }

    @FXML private void onLinkSnapshot(){
        var selected=selectedManualEvent();
        if(selected==null||appService==null) return;
        var dialog=new GraphSnapshotLinkDialog(appService, selected.bookmarkId(), this::commitCaptureSnapshotForTest).build();
        dialog.showAndWait();
        restoreEngineeringBookmarks();
    }

    @FXML private void onGpsEvent(){
        var selected=selectedManualEvent();
        if(selected==null||appService==null) return;
        gpsDialog(selected.bookmarkId()).showAndWait();
        restoreEngineeringBookmarks();
    }

    @FXML private void onZoomToEvent(){
        var bookmark=selectedManualEvent();
        if(bookmark==null) return;
        zoomToBookmark(bookmark);
    }

    @FXML private void onEditEvent(){
        com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata selected=selectedManualEvent();
        if(selected==null||appService==null) return;
        var fresh=appService.findEngineeringBookmark(selected.bookmarkId());
        if(fresh.isEmpty()||!InvestigationEventCodec.isManual(fresh.get().eventType())){
            showEventFailure("This event is no longer available.");
            restoreEngineeringBookmarks();
            return;
        }
        var bookmark=fresh.get();
        Dialog<ButtonType> dialog=buildEventDialog(
                "Edit Event",
                InvestigationEventCodec.titleOf(bookmark.observation()),
                bookmark.eventType(),
                InvestigationEventCodec.noteOf(bookmark.observation()),
                bookmark.timestampNanos(),
                true);
        java.util.Optional<ButtonType> result=dialog.showAndWait();
        if(result.isEmpty()||result.get()!=ButtonType.OK) return;
        EventForm form=readEventForm(dialog);
        if(!commitEventEdit(bookmark.bookmarkId(), form.title, form.category, form.note)){
            showEventFailure("The event could not be updated.");
            restoreEngineeringBookmarks();
        }
    }

    @FXML private void onDeleteEvent(){
        com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata selected=selectedManualEvent();
        if(selected==null||appService==null) return;
        var fresh=appService.findEngineeringBookmark(selected.bookmarkId());
        if(fresh.isEmpty()||!InvestigationEventCodec.isManual(fresh.get().eventType())){
            showEventFailure("This event is no longer available.");
            restoreEngineeringBookmarks();
            return;
        }
        var bookmark=fresh.get();
        java.util.Optional<ButtonType> result=deleteConfirmation(bookmark).showAndWait();
        if(result.isEmpty()||result.get().getButtonData()!=ButtonBar.ButtonData.OK_DONE) return;
        if(!commitDelete(bookmark.bookmarkId())){
            showEventFailure("The event could not be deleted.");
            restoreEngineeringBookmarks();
        }
    }

    private Dialog<ButtonType> buildAddEventDialog(){
        Long pointTime=sampleFromClick && transientSampleNanos!=null ? transientSampleNanos : cursorA;
        if(pointTime==null && appService!=null && appService.analysisSelection().selection().kind()==TimeSelection.Kind.POINT){
            pointTime=appService.analysisSelection().selection().startNanos();
        }
        if(pointTime==null){
            if(statusLabel!=null) setGraphStatus("Click a plotted sample or set Cursor A before adding an event.");
            return null;
        }
        long start=cursorA!=null ? cursorA : pointTime;
        java.util.List<com.stellar.platform.domain.catalog.SignalCatalogKey> keys=java.util.List.copyOf(eventSignals());
        AutomotiveSignalCanvas.PlottedSample sample=plottedSample(pointTime, keys);
        if(graphCanvas!=null && graphCanvas.hasPlottedSeries() && sample==null){
            if(statusLabel!=null) setGraphStatus("Click a plotted sample to add an event. Empty areas are not saved.");
            return null;
        }
        Long end=cursorB;
        Dialog<ButtonType> dialog=new Dialog<>();
        dialog.setTitle("Add Event");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        ComboBox<String> shape=new ComboBox<>();
        shape.getItems().addAll("Point", "Time window");
        shape.setUserData("shape");
        shape.getSelectionModel().select(end==null ? "Point" : "Time window");
        TextField titleField=new TextField();
        titleField.setPromptText("Event title");
        ComboBox<String> category=new ComboBox<>();
        category.setUserData("category");
        category.getItems().addAll(InvestigationEventCodec.CATEGORIES);
        category.getSelectionModel().selectFirst();
        TextArea note=new TextArea();
        note.setPromptText("Optional note");
        note.setPrefRowCount(3);
        Label startLabel=new Label();
        startLabel.setDisable(true);
        Label valueLabel=new Label(sample==null?"":sample.valueText());
        valueLabel.setDisable(true);
        Label endLabel=new Label(end==null ? "Cursor B is not set." : exactTime(end));
        endLabel.setDisable(true);
        Label signalLabel=new Label(signalSummary(keys));
        signalLabel.setWrapText(true);
        VBox body=new VBox(8,
                new Label("Kind"), shape,
                new Label("Start"), startLabel,
                new Label("Value"), valueLabel,
                new Label("End"), endLabel,
                new Label("Signals"), signalLabel,
                new Label("Title"), titleField,
                new Label("Category"), category,
                new Label("Note"), note);
        dialog.getDialogPane().setContent(body);
        Button ok=(Button)dialog.getDialogPane().lookupButton(ButtonType.OK);
        Runnable guard=()->{
            boolean window="Time window".equals(shape.getValue());
            endLabel.setVisible(window);
            endLabel.setManaged(window);
            boolean showValue=!window && sample!=null;
            valueLabel.setVisible(showValue);
            valueLabel.setManaged(showValue);
            long shown=window || sample==null ? start : sample.timestampNanos();
            startLabel.setText(exactTime(shown));
            if(showValue){
                signalLabel.setText(sample.signalName());
            }else{
                signalLabel.setText(signalSummary(keys));
            }
            boolean invalid=window && (end==null || end.equals(start));
            if(ok!=null) ok.setDisable(invalid);
        };
        shape.valueProperty().addListener((obs, previous, value)->guard.run());
        guard.run();
        var props=dialog.getDialogPane().getProperties();
        props.put("stellar.event.start", start);
        props.put("stellar.event.end", end);
        props.put("stellar.event.keys", keys);
        props.put("stellar.event.sampleTime", sample==null?null:sample.timestampNanos());
        props.put("stellar.event.sampleKey", sample==null?null:sample.key());
        props.put("stellar.event.sampleText", sample==null?"":sample.valueText());
        props.put("stellar.event.sampleNumber", sample==null?null:sample.value());
        dialog.setResultConverter(button->button);
        return dialog;
    }

    private String commitAddEvent(Dialog<?> dialog){
        if(dialog==null || appService==null) return "";
        EventForm form=readEventForm(dialog);
        var props=dialog.getDialogPane().getProperties();
        long start=((Number)props.get("stellar.event.start")).longValue();
        Object rawEnd=props.get("stellar.event.end");
        Long end=rawEnd instanceof Number number ? number.longValue() : null;
        boolean window=false;
        if(dialog.getDialogPane().getContent() instanceof VBox body){
            for(Node node:body.getChildren()){
                if(node instanceof ComboBox<?> combo && "shape".equals(combo.getUserData())){
                    window="Time window".equals(String.valueOf(combo.getValue()));
                }
            }
        }
        if(window){
            if(end==null || end.equals(start)) return "";
            long lo=Math.min(start, end);
            long hi=Math.max(start, end);
            start=lo;
            end=hi;
        }else{
            end=null;
            Object sampleTime=props.get("stellar.event.sampleTime");
            if(sampleTime instanceof Number number) start=number.longValue();
        }
        @SuppressWarnings("unchecked")
        java.util.List<com.stellar.platform.domain.catalog.SignalCatalogKey> keys=
                (java.util.List<com.stellar.platform.domain.catalog.SignalCatalogKey>)props.get("stellar.event.keys");
        if(keys==null) keys=java.util.List.of();
        String sampleText="";
        Double sampleNumber=null;
        if(!window){
            Object sampleKey=props.get("stellar.event.sampleKey");
            if(sampleKey instanceof com.stellar.platform.domain.catalog.SignalCatalogKey key){
                keys=java.util.List.of(key);
            }
            Object text=props.get("stellar.event.sampleText");
            sampleText=text==null?"":String.valueOf(text);
            Object number=props.get("stellar.event.sampleNumber");
            if(number instanceof Number value && Double.isFinite(value.doubleValue())){
                sampleNumber=value.doubleValue();
            }
        }
        java.util.List<String> names=new java.util.ArrayList<>();
        java.util.List<String> encodings=new java.util.ArrayList<>();
        for(var key:keys){
            if(key==null) continue;
            names.add(signalLabel(key, keys));
            encodings.add(GraphInvestigationCodec.encodeKey(key));
        }
        String sourceId="";
        String sourceFile="";
        if(appService.workspace()!=null && appService.workspace().selectedMeasurement().isPresent()){
            var file=appService.workspace().selectedMeasurement().get();
            sourceId=file.id();
            sourceFile=file.fileName();
        }
        String heading=form.title==null||form.title.isBlank()?"Event":form.title.strip();
        return appService.saveGraphEvent("", heading, form.category, form.note, start, end,
                sourceId, sourceFile, names, encodings, sampleText, sampleNumber);
    }

    private AutomotiveSignalCanvas.PlottedSample plottedSample(
            long timestamp,
            java.util.List<com.stellar.platform.domain.catalog.SignalCatalogKey> keys
    ){
        if(graphCanvas==null) return null;
        String preferred=preferredEventSignal(keys);
        if(preferred!=null && !preferred.isBlank()){
            return graphCanvas.nearestPlottedSample(timestamp, preferred);
        }
        java.util.LinkedHashSet<String> names=new java.util.LinkedHashSet<>();
        if(keys!=null){
            for(var key:keys){
                if(key!=null && key.signalName()!=null && !key.signalName().isBlank()) names.add(key.signalName());
            }
        }
        if(names.isEmpty()) return graphCanvas.nearestPlottedSample(timestamp, "");
        AutomotiveSignalCanvas.PlottedSample best=null;
        long bestDelta=Long.MAX_VALUE;
        for(String name:names){
            AutomotiveSignalCanvas.PlottedSample sample=graphCanvas.nearestPlottedSample(timestamp, name);
            if(sample==null) continue;
            long delta=Math.abs(sample.timestampNanos()-timestamp);
            if(best==null || delta<bestDelta){
                best=sample;
                bestDelta=delta;
            }
        }
        return best;
    }

    private String preferredEventSignal(java.util.List<com.stellar.platform.domain.catalog.SignalCatalogKey> keys){
        if(graphCanvas!=null){
            var hover=graphCanvas.hoverPointProperty().get();
            if(hover!=null && hover.signalName()!=null && !hover.signalName().isBlank()) return hover.signalName();
        }
        if(focusedExplorerKey!=null && focusedExplorerKey.signalName()!=null && !focusedExplorerKey.signalName().isBlank()){
            return focusedExplorerKey.signalName();
        }
        if(keys!=null && keys.size()==1 && keys.get(0)!=null) return keys.get(0).signalName();
        return "";
    }

    private void zoomToBookmark(com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata bookmark){
        long startNanos=bookmark.timestampNanos();
        Long endNanos=EngineerEventDocuments.endNanos(bookmark);
        long viewStart;
        long viewEnd;
        if(endNanos!=null && endNanos>startNanos){
            long span=endNanos-startNanos;
            long pad=Math.max(span/20L, 1L);
            viewStart=startNanos-pad;
            viewEnd=endNanos+pad;
        }else{
            long half=Math.max((end-start)/8L, 1_000_000_000L);
            viewStart=startNanos-half;
            viewEnd=startNanos+half;
        }
        if(viewEnd<=viewStart) viewEnd=viewStart+1L;
        applyViewport(viewStart, viewEnd);
        jumpToTimestamp(startNanos, TimeSelection.Origin.EVENT);
    }

    private java.util.List<com.stellar.platform.domain.catalog.SignalCatalogKey> eventSignals(){
        if(pinnedEventSignals!=null) return pinnedEventSignals;
        java.util.List<com.stellar.platform.domain.catalog.SignalCatalogKey> visible=selectedKeys();
        if(!visible.isEmpty()) return visible;
        if(focusedExplorerKey!=null) return java.util.List.of(focusedExplorerKey);
        return java.util.List.of();
    }

    private static String signalLabel(
            com.stellar.platform.domain.catalog.SignalCatalogKey key,
            java.util.List<com.stellar.platform.domain.catalog.SignalCatalogKey> all
    ){
        int same=0;
        for(var candidate:all){
            if(candidate!=null && candidate.signalName().equals(key.signalName())) same++;
        }
        if(same<=1) return key.signalName();
        String extra=key.messageName()==null||key.messageName().isBlank()?key.sourceFile():key.messageName();
        return key.signalName()+" · "+extra;
    }

    private static String signalSummary(java.util.List<com.stellar.platform.domain.catalog.SignalCatalogKey> keys){
        if(keys==null || keys.isEmpty()) return "No graph signal is selected.";
        StringBuilder text=new StringBuilder();
        for(var key:keys){
            if(key==null) continue;
            if(!text.isEmpty()) text.append(", ");
            text.append(signalLabel(key, keys));
        }
        return text.isEmpty()?"No graph signal is selected.":text.toString();
    }

    private static String exactTime(long nanos){
        return com.stellar.platform.desktop.ui.graph.map.MapTimeFormat.withMillis(nanos)+" ("+nanos+" ns)";
    }

    private Dialog<ButtonType> buildEventDialog(
            String title,
            String titleText,
            String categoryValue,
            String noteText,
            Long timestampNanos,
            boolean edit){
        Dialog<ButtonType> dialog=new Dialog<>();
        dialog.setTitle(title);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField titleField=new TextField(titleText==null?"":titleText);
        titleField.setPromptText("Event title");
        ComboBox<String> category=new ComboBox<>();
        category.getItems().addAll(InvestigationEventCodec.CATEGORIES);
        String selectedCategory=InvestigationEventCodec.categoryOf(categoryValue);
        if(InvestigationEventCodec.isManual(categoryValue) && !category.getItems().contains(selectedCategory)){
            category.getItems().add(selectedCategory);
        }
        if(InvestigationEventCodec.isManual(categoryValue)){
            category.getSelectionModel().select(selectedCategory);
        }else{
            category.getSelectionModel().selectFirst();
        }
        TextArea note=new TextArea(noteText==null?"":noteText);
        note.setPromptText("Optional note");
        note.setPrefRowCount(3);
        VBox body=new VBox(8);
        if(edit && timestampNanos!=null){
            Label time=new Label(com.stellar.platform.desktop.ui.graph.map.MapTimeFormat.withMillis(timestampNanos));
            time.setDisable(true);
            body.getChildren().addAll(new Label("Time"), time);
        }
        body.getChildren().addAll(new Label("Title"), titleField, new Label("Category"), category, new Label("Note"), note);
        dialog.getDialogPane().setContent(body);
        dialog.setResultConverter(button->button);
        return dialog;
    }

    private EventForm readEventForm(Dialog<?> dialog){
        String title="";
        String category=InvestigationEventCodec.CATEGORIES[0];
        String note="";
        if(dialog.getDialogPane().getContent() instanceof VBox body){
            for(Node node:body.getChildren()){
                if(node instanceof TextField field) title=field.getText();
                else if(node instanceof ComboBox<?> combo && combo.getValue()!=null && !"shape".equals(combo.getUserData())){
                    category=String.valueOf(combo.getValue());
                }
                else if(node instanceof TextArea area) note=area.getText();
            }
        }
        return new EventForm(title, category, note);
    }

    private boolean commitEventEdit(String bookmarkId, String title, String category, String note){
        if(appService==null || !appService.updateInvestigationEvent(bookmarkId, title, category, note)) return false;
        selectedEventId=bookmarkId;
        restoreEngineeringBookmarks();
        return true;
    }

    private boolean commitDelete(String bookmarkId){
        if(appService==null) return false;
        var fresh=appService.findEngineeringBookmark(bookmarkId);
        if(fresh.isEmpty()||!InvestigationEventCodec.isManual(fresh.get().eventType())) return false;
        if(!appService.deleteInvestigationEvent(bookmarkId)) return false;
        if(bookmarkId.equals(selectedEventId)) selectedEventId=null;
        restoreEngineeringBookmarks();
        return true;
    }

    private Alert deleteConfirmation(com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata bookmark){
        String time=com.stellar.platform.desktop.ui.graph.map.MapTimeFormat.withMillis(bookmark.timestampNanos());
        String title=InvestigationEventCodec.titleOf(bookmark.observation());
        String category=InvestigationEventCodec.categoryOf(bookmark.eventType());
        Alert alert=new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Delete Event");
        alert.setHeaderText("Delete this event from the project?");
        alert.setContentText(time+"  "+title+"  · "+category
                +"\n\nThe selected manual event will be deleted from the project. "
                +"Measurement files, signals, and other events are kept.");
        ButtonType delete=new ButtonType("Delete", ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(ButtonType.CANCEL, delete);
        Button cancel=(Button)alert.getDialogPane().lookupButton(ButtonType.CANCEL);
        Button confirm=(Button)alert.getDialogPane().lookupButton(delete);
        if(cancel!=null) cancel.setDefaultButton(true);
        if(confirm!=null) confirm.setDefaultButton(false);
        return alert;
    }

    private void showEventFailure(String message){
        Alert alert=new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Event");
        alert.setHeaderText(message);
        alert.setContentText("The saved event was not changed.");
        alert.showAndWait();
    }

    private com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata selectedManualEvent(){
        if(eventsList==null) return null;
        int index=eventsList.getSelectionModel().getSelectedIndex();
        if(index<0 || index>=displayedBookmarks.size()) return null;
        var bookmark=displayedBookmarks.get(index);
        return InvestigationEventCodec.isManual(bookmark.eventType()) ? bookmark : null;
    }

    private void refreshEventActions(){
        boolean enabled=selectedManualEvent()!=null;
        int index=eventsList==null?-1:eventsList.getSelectionModel().getSelectedIndex();
        boolean generated=index>=0 && index<displayedBookmarks.size() && !enabled;
        Tooltip generatedTip=generated?new Tooltip("Generated bookmarks cannot be edited or deleted here."):null;
        tip(editEventButton, generatedTip, "Edit the selected event", enabled);
        tip(zoomEventButton, generatedTip, "Zoom to the selected event", enabled);
        tip(linkSnapshotButton, generatedTip, "Link a snapshot to the selected event", enabled);
        tip(gpsEventButton, generatedTip, "GPS evidence for the selected event", enabled);
        tip(deleteEventButton, generatedTip, "Delete the selected event", enabled);
        layoutEventActions();
    }

    private static void tip(Button button, Tooltip generatedTip, String fallback, boolean enabled){
        if(button==null) return;
        button.setDisable(!enabled);
        button.setTooltip(generatedTip==null?new Tooltip(fallback):generatedTip);
    }

    private record EventForm(String title, String category, String note) {}

    private void onEventOverlayClicked(AutomotiveSignalCanvas.AnalysisOverlay overlay){
        if(overlay==null) return;
        jumpToTimestamp(overlay.startNanos(), TimeSelection.Origin.EVENT);
    }

    private void paintEventOverlays(){
        java.util.List<AutomotiveSignalCanvas.AnalysisOverlay> overlays=new java.util.ArrayList<>();
        for(var bookmark:displayedBookmarks){
            Long end=EngineerEventDocuments.endNanos(bookmark);
            boolean window=end!=null && end>bookmark.timestampNanos();
            boolean selected=bookmark.bookmarkId().equals(selectedEventId);
            if(window){
                overlays.add(new AutomotiveSignalCanvas.AnalysisOverlay(
                        bookmark.timestampNanos(),
                        end,
                        InvestigationEventCodec.titleOf(bookmark.observation()),
                        selected));
            }else{
                String signal=bookmark.signalName()==null?"":bookmark.signalName();
                String valueText=EngineerEventDocuments.sampleText(bookmark);
                if(graphCanvas!=null){
                    String state=graphCanvas.stateLabelAt(signal, bookmark.timestampNanos());
                    if(state!=null && !state.isBlank() && !valueText.contains(state)){
                        valueText=valueText.isBlank()?state:valueText+" "+state;
                    }
                }
                overlays.add(AutomotiveSignalCanvas.AnalysisOverlay.point(
                        bookmark.timestampNanos(),
                        bookmark.bookmarkId(),
                        InvestigationEventCodec.titleOf(bookmark.observation()),
                        signal,
                        InvestigationEventCodec.noteOf(bookmark.observation()),
                        valueText,
                        com.stellar.platform.desktop.ui.graph.map.MapTimeFormat.withMillis(bookmark.timestampNanos()),
                        EngineerEventDocuments.sampleNumber(bookmark),
                        selected));
            }
        }
        if(graphCanvas!=null){
            graphCanvas.setAnalysisOverlays(overlays);
            graphCanvas.setAnalysisOverlaysVisible(true);
            releaseWaveformViewportCache();
        }
    }

    private java.util.Set<String> eventCatalogKeys(){
        java.util.Set<String> present=new java.util.LinkedHashSet<>();
        java.util.List<SignalCatalogKey> source=eventCatalogOverride!=null?eventCatalogOverride:java.util.List.of();
        if(eventCatalogOverride==null && allSignals!=null){
            for(var entry:allSignals){
                if(entry!=null && entry.key()!=null) present.add(GraphInvestigationCodec.encodeKey(entry.key()));
            }
            return present;
        }
        for(var key:source){
            if(key!=null) present.add(GraphInvestigationCodec.encodeKey(key));
        }
        return present;
    }

    private static String storedSignalSummary(com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata bookmark){
        var defined=EngineerEventDocuments.decode(bookmark);
        if(defined!=null && !defined.signals().isEmpty()){
            return String.join(", ", defined.signals());
        }
        return bookmark.signalName()==null?"":bookmark.signalName();
    }

    private static String snapshotListNote(com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata bookmark){
        var defined=EngineerEventDocuments.decode(bookmark);
        if(defined==null || defined.snapshotIds().isEmpty()) return "";
        String note=defined.snapshotNote();
        return note==null || note.isBlank() ? "Snapshot linked" : note;
    }

    private static String missingSignalNote(
            com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata bookmark,
            java.util.Set<String> catalogKeys
    ){
        java.util.List<String> keys=EngineerEventDocuments.signalKeys(bookmark);
        if(keys.isEmpty()){
            String legacy=bookmark.signalKey()==null?"":bookmark.signalKey();
            if(legacy.isBlank() || catalogKeys.contains(legacy) || !legacy.contains("\u001f")) return "";
            return "signal missing";
        }
        int missing=0;
        for(String key:keys){
            if(!catalogKeys.contains(key)) missing++;
        }
        if(missing==0) return "";
        return missing==1?"1 signal missing":missing+" signals missing";
    }

    private void jumpToEventIndex(int index){
        if(index<0 || index>=displayedBookmarks.size()) return;
        jumpToTimestamp(displayedBookmarks.get(index).timestampNanos(), TimeSelection.Origin.EVENT);
    }

    private void jumpToTimestamp(long timestampNanos, TimeSelection.Origin origin){
        cursorAPinned=true;
        sampleFromClick=false;
        cursorA=timestampNanos;
        if(graphCanvas!=null) graphCanvas.setCursors(cursorA,cursorB);
        GraphInvestigationState inv=investigation();
        if(inv!=null) inv.setCursorA(timestampNanos);
        resolveCursors();
        updateSelectedTimeLabel();
        refreshInvestigationInspector();
        if(appService!=null){
            appService.analysisSelection().selectPoint(timestampNanos, origin);
        }
    }

    private void applyInvestigationEventMarkers(java.util.List<com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata> stored){
        java.util.List<String> rows=new java.util.ArrayList<>();
        java.util.List<Long> mapTimes=new java.util.ArrayList<>();
        java.util.List<String> mapLabels=new java.util.ArrayList<>();
        java.util.List<String> mapSources=new java.util.ArrayList<>();
        java.util.List<String> mapFiles=new java.util.ArrayList<>();
        java.util.Set<String> catalogKeys=eventCatalogKeys();
        boolean routeChecked=mapRoutePane!=null && mapRoutePane.routeResolved();
        if(stored!=null){
            for(var bookmark:stored){
                String title=InvestigationEventCodec.titleOf(bookmark.observation());
                String category=InvestigationEventCodec.categoryOf(bookmark.eventType());
                Long end=EngineerEventDocuments.endNanos(bookmark);
                boolean window=end!=null && end>bookmark.timestampNanos();
                String when=com.stellar.platform.desktop.ui.graph.map.MapTimeFormat.withMillis(bookmark.timestampNanos());
                if(window){
                    when=when+" to "+com.stellar.platform.desktop.ui.graph.map.MapTimeFormat.withMillis(end);
                }
                String signals=storedSignalSummary(bookmark);
                String missing=catalogKeys.isEmpty()?"":missingSignalNote(bookmark, catalogKeys);
                String row=when+"  "+title+"  · "+category;
                if(!signals.isBlank()) row=row+"  · "+signals;
                String snapshots=snapshotListNote(bookmark);
                if(!snapshots.isBlank()) row=row+"  · "+snapshots;
                com.stellar.platform.desktop.presentation.GraphEventGps.Evidence gps=gpsEvidence(bookmark, routeChecked);
                if(!com.stellar.platform.desktop.presentation.GraphEventGps.NOT_REQUESTED.equals(gps.status())){
                    row=row+"  · "+gps.status();
                }
                if(!missing.isBlank()) row=row+"  · "+missing;
                rows.add(row);
                if(com.stellar.platform.desktop.presentation.GraphEventGps.MATCHED.equals(gps.status())){
                    mapTimes.add(gps.sampleTimestampNanos());
                    mapLabels.add(gpsMarkerLabel(title, gps));
                    mapSources.add(gps.sourceId()==null?"":gps.sourceId());
                    mapFiles.add(gps.sourceFile()==null?"":gps.sourceFile());
                }
            }
        }
        long[] timestamps=new long[mapTimes.size()];
        String[] labels=new String[mapLabels.size()];
        String[] sources=new String[mapSources.size()];
        String[] files=new String[mapFiles.size()];
        for(int i=0;i<mapTimes.size();i++){
            timestamps[i]=mapTimes.get(i);
            labels[i]=mapLabels.get(i);
            sources[i]=mapSources.get(i);
            files[i]=mapFiles.get(i);
        }
        if(mapRoutePane!=null){
            mapRoutePane.setEventMarkers(timestamps, labels, sources, files);
        }
        displayedBookmarks=stored==null?java.util.List.of():java.util.List.copyOf(stored);
        paintEventOverlays();
        if(eventsList!=null){
            String keep=selectedEventId;
            eventsList.getItems().setAll(rows);
            if(keep!=null){
                for(int n=0;n<displayedBookmarks.size();n++){
                    if(keep.equals(displayedBookmarks.get(n).bookmarkId())){
                        eventsList.getSelectionModel().select(n);
                        break;
                    }
                }
            }
        }
        refreshEventActions();
        boolean empty=rows.isEmpty();
        if(eventsEmptyLabel!=null){
            eventsEmptyLabel.setVisible(empty);
            eventsEmptyLabel.setManaged(empty);
        }
        if(eventsList!=null){
            eventsList.setVisible(!empty);
            eventsList.setManaged(!empty);
        }
    }

    private void updateSelectedTimeLabel(){
        if(selectedTimeLabel==null) return;
        if(sampleFromClick && transientSampleNanos!=null){
            showReadout(selectedTimeLabel, "Selected time: "+com.stellar.platform.desktop.ui.graph.map.MapTimeFormat.withMillis(transientSampleNanos));
            return;
        }
        Long timestamp=cursorA;
        if(timestamp==null){
            if(cursorAAbsolute!=null){
                showReadout(selectedTimeLabel, "Selected time: "+com.stellar.platform.desktop.ui.graph.map.MapTimeFormat.withMillis(cursorAAbsolute));
                return;
            }
            showReadout(selectedTimeLabel, "Selected time: —");
            return;
        }
        Long shown=cursorAAbsolute!=null?cursorAAbsolute:timestamp;
        showReadout(selectedTimeLabel, "Selected time: "+com.stellar.platform.desktop.ui.graph.map.MapTimeFormat.withMillis(shown));
    }

    private void refreshInvestigationInspector(){
        if(detailsInspector==null) return;
        detailsInspector.getChildren().clear();
        Long timestamp=cursorA;
        if(timestamp==null && appService!=null && appService.analysisSelection().selection().kind()==TimeSelection.Kind.POINT){
            timestamp=appService.analysisSelection().selection().startNanos();
        }
        boolean noOwner=appService!=null && appService.analysisSelection().explicitNoOwner();
        if(timestamp==null && !noOwner){
            if(!appendFocusedSignal()){
                Label empty=new Label("Select a time on the waveform or map to inspect values.");
                empty.getStyleClass().add("muted");
                empty.setWrapText(true);
                detailsInspector.getChildren().add(empty);
            }
            return;
        }
        if(noOwner){
            Label heading=new Label("Selected Time");
            heading.getStyleClass().add("subsection-title");
            detailsInspector.getChildren().add(heading);
            Long shown=cursorAAbsolute!=null?cursorAAbsolute:timestamp;
            if(shown!=null){
                detailsInspector.getChildren().add(inspectorLine("Time", com.stellar.platform.desktop.ui.graph.map.MapTimeFormat.withMillis(shown)));
            }
            Label none=new Label(com.stellar.platform.processing.time.MeasurementCursor.NO_MEASUREMENT_AT_TIME);
            none.getStyleClass().add("muted");
            none.setWrapText(true);
            detailsInspector.getChildren().add(none);
            return;
        }
        Label heading=new Label("Selected Time");
        heading.getStyleClass().add("subsection-title");
        detailsInspector.getChildren().add(heading);
        Long shownTime=cursorAAbsolute!=null?cursorAAbsolute:timestamp;
        detailsInspector.getChildren().add(inspectorLine("Time", com.stellar.platform.desktop.ui.graph.map.MapTimeFormat.withMillis(shownTime)));
        if(cursorAAbsolute!=null){
            detailsInspector.getChildren().add(inspectorLine("Sample time", com.stellar.platform.desktop.ui.graph.map.MapTimeFormat.withMillis(timestamp)));
        }
        String measurement=measurementLabel(cursorMeasurementId);
        if(measurement.isBlank()){
            measurement=fileFocusActive && focusedFileIndex>=0 && focusedFileIndex<fileSlots.size()
                    ? fileSlots.get(focusedFileIndex).label()
                    : (appService==null?"":appService.activeMeasurementFileName());
        }
        if(!measurement.isBlank()){
            detailsInspector.getChildren().add(inspectorLine("Measurement", measurement));
        }
        if(cursorCompareTable!=null){
            for(CursorCompareRow row:cursorCompareTable.getItems()){
                if(row==null) continue;
                detailsInspector.getChildren().add(inspectorLine(row.signal(), row.aValue()));
            }
        }
        if(mapRoutePane!=null){
            mapRoutePane.selectedGpsSummary().ifPresentOrElse(
                    gps->detailsInspector.getChildren().add(inspectorLine("GPS", gps)),
                    ()->{
                        if(mapRoutePane.sharedSelection().selection().kind()==TimeSelection.Kind.POINT){
                            Label none=new Label("No GPS sample available at this time");
                            none.getStyleClass().add("muted");
                            none.setWrapText(true);
                            detailsInspector.getChildren().add(none);
                        }
                    });
        }
        if(appService!=null){
            for(var bookmark:appService.listEngineeringBookmarks()){
                if(bookmark.timestampNanos()==timestamp){
                    detailsInspector.getChildren().add(inspectorLine("Event",
                            InvestigationEventCodec.titleOf(bookmark.observation())
                                    +" · "+InvestigationEventCodec.categoryOf(bookmark.eventType())));
                    break;
                }
            }
        }
        appendFocusedSignal();
    }

    /** Signal metadata and categorical inspection. Does not require a plotted numeric point. */
    private boolean appendFocusedSignal(){
        SignalCatalogEntry focused=null;
        if(focusedExplorerKey!=null){
            for(SignalCatalogEntry entry:allSignals){
                if(focusedExplorerKey.equals(entry.key())){
                    focused=entry;
                    break;
                }
            }
        }
        if(focused==null) return false;
        Label meta=new Label(SignalTraceLabels.displayName(focused, selectedEntries()));
        meta.getStyleClass().add("subsection-title");
        detailsInspector.getChildren().add(meta);
        if(failedTraces.containsKey(focused.key())){
            Label err=new Label("This trace failed to load: "+failedTraces.get(focused.key()));
            err.getStyleClass().add("muted"); err.setWrapText(true);
            detailsInspector.getChildren().add(err);
        }
        for(SignalInspectorPresentation.Section section:SignalInspectorPresentation.sections(focused)){
            Label sec=new Label(section.title()); sec.getStyleClass().add("muted");
            detailsInspector.getChildren().add(sec);
            for(SignalInspectorPresentation.Row row:section.rows()){
                detailsInspector.getChildren().add(inspectorLine(row.label(), row.value()));
            }
        }
        scheduleCategoricalSummary(focused.key());
        return true;
    }

    /**
     * A cursor click used to scan every sample of the focused signal on the JavaFX thread.
     * For a project-scoped series that reopened each file from the first byte on every page,
     * one click left the window Not Responding. The summary is cached and filled in afterwards.
     */
    private void scheduleCategoricalSummary(SignalCatalogKey key){
        if(key==null || detailsInspector==null) return;
        java.util.Optional<CategoricalSeriesSummary.Result> cached=categoricalByKey.get(key);
        if(cached!=null){
            cached.ifPresent(this::appendCategoricalSection);
            return;
        }
        SignalsScreenModel model=signalsModel();
        com.stellar.platform.processing.catalog.SignalSeriesStore store=model==null?null:model.catalogStore();
        if(store==null || !categoricalPending.add(key)) return;
        categoricalScan.execute(()->{
            CategoricalSeriesSummary.Result result;
            try{
                result=CategoricalSeriesSummary.scan(store, key);
            }catch(RuntimeException ex){
                categoricalPending.remove(key);
                return;
            }
            java.util.Optional<CategoricalSeriesSummary.Result> published=
                    result==null?java.util.Optional.empty():java.util.Optional.of(result);
            Platform.runLater(()->{
                categoricalPending.remove(key);
                if(disposed) return;
                categoricalByKey.put(key, published);
                if(key.equals(focusedExplorerKey)) refreshInvestigationInspector();
            });
        });
    }

    private void appendCategoricalSection(CategoricalSeriesSummary.Result categorical){
        if(categorical==null || detailsInspector==null) return;
        Label heading=new Label("Categorical");
        heading.getStyleClass().add("subsection-title");
        detailsInspector.getChildren().add(heading);
        detailsInspector.getChildren().add(inspectorLine("Label", categorical.labels()));
        detailsInspector.getChildren().add(inspectorLine("Samples", CategoricalSeriesSummary.countText(categorical.categoricalSamples())));
        if(!categorical.rawText().isBlank()){
            detailsInspector.getChildren().add(inspectorLine("Raw", categorical.rawText()));
        }
        if(!categorical.validityText().isBlank()){
            detailsInspector.getChildren().add(inspectorLine("Validity", categorical.validityText()));
        }
        detailsInspector.getChildren().add(inspectorLine("First sample",
                String.format(java.util.Locale.ROOT, "%.6f s", categorical.firstTimestampNanos()/1e9)));
        SignalCatalogKey key=focusedExplorerKey;
        if(key!=null && key.sourceFile()!=null && !key.sourceFile().isBlank()){
            detailsInspector.getChildren().add(inspectorLine("Measurement", key.sourceFile()));
        }
        if(categorical.changes()){
            Label note=new Label("Labels change over the recording. The list is a summary, not one instant.");
            note.getStyleClass().add("muted");
            note.setWrapText(true);
            detailsInspector.getChildren().add(note);
        }
    }

    private static Label inspectorLine(String label, String value){
        Label line=new Label(label+": "+(value==null?"—":value));
        line.setWrapText(true);
        return line;
    }

    // --- NFT / structural probes (package-visible) ---
    int selectedSignalCountForTest(){ return selectedSignalKeys.size(); }
    String explorerCountCaptionForTest(){
        return selectedSignalKeys.size()+" selected · "+selectedKeys().size()+" visible / "+allSignals.size()+" signals";
    }
    List<GraphRenderSeries> renderAnalysisTracksForTest(){
        GraphSeriesQuery query=analysisQuery();
        List<SignalCatalogKey> keys=plotKeys();
        if(query==null || keys.isEmpty()){
            return List.of();
        }
        long from=0L;
        long to=1L;
        if(refreshFullAnalysisRange()){
            from=dataStart;
            to=dataEnd<=dataStart?dataStart+1:dataEnd;
        }
        return new GraphViewportEngine(query).render(
                new GraphViewportRequest(keys, from, to, 640));
    }
    int lastRenderedCountForTest(){ return lastRendered.size(); }
    long generationForTest(){ return generation; }
    long viewportRequestsForTest(){ return viewportRequests.get(); }
    long viewportAcceptedForTest(){ return viewportAccepted.get(); }
    long viewportRejectedStaleForTest(){ return viewportRejectedStale.get(); }
    boolean hasPendingGraphWorkForTest(){
        Future<?> render=pendingRender;
        ScheduledFuture<?> viewport=pendingViewport;
        return (render!=null && !render.isDone()) || (viewport!=null && !viewport.isDone());
    }

    private void placeCursorAtFraction(String which, double fraction){
        SharedTimelineHit.Hit hit=sharedHitAtFraction(fraction);
        if(hit!=null){
            setCursorFromHit(which, hit);
            return;
        }
        long at=start+(end-start)/2;
        if(graphCanvas!=null && graphCanvas.hasPlottedSeries()){
            AutomotiveSignalCanvas.PlottedSample sample=graphCanvas.nearestPlottedSample(at, "");
            if(sample!=null) at=sample.timestampNanos();
        }
        setCursor(which, at);
    }

    private SharedTimelineHit.Hit sharedHitAtFraction(double fraction){
        for(GraphLogicalTrack track:lastTracks){
            if(!track.sharedAbsoluteTimeline() || track.segments().size()<2) continue;
            long min=Long.MAX_VALUE;
            long max=Long.MIN_VALUE;
            java.util.ArrayList<SharedTimelineHit.Segment> segments=new java.util.ArrayList<>();
            for(GraphTrackSegment segment:track.segments()){
                long startNanos=Long.MAX_VALUE;
                long endNanos=Long.MIN_VALUE;
                for(GraphBucket bucket:segment.series().buckets()){
                    startNanos=Math.min(startNanos, bucket.firstTimestampNanos());
                    endNanos=Math.max(endNanos, bucket.lastTimestampNanos());
                }
                if(startNanos>endNanos || segment.absoluteOriginNanos()==null) continue;
                min=Math.min(min, GraphTrackArrangement.addSaturating(segment.absoluteOriginNanos(), startNanos));
                max=Math.max(max, GraphTrackArrangement.addSaturating(segment.absoluteOriginNanos(), endNanos));
                String id=segment.series().key()==null?"":segment.series().key().sourceFile();
                segments.add(new SharedTimelineHit.Segment(
                        id, track.logicalKey().signalName(), startNanos, endNanos, segment.absoluteOriginNanos()));
            }
            if(segments.isEmpty() || min>max) return null;
            return SharedTimelineHit.select(SharedTimelineHit.absoluteAt(min, max, fraction), segments).orElse(null);
        }
        return null;
    }

    private void showOverlapChoices(AutomotiveSignalCanvas.OverlapPick pick) {
        if (pick == null || pick.choices().size() < 2) {
            return;
        }
        overlapChooser.show(graphCanvas, pick.screenX(), pick.screenY(), pick.choices(),
                choice -> setCursorFromHit(pick.which(), choice.hit()));
    }

    private void dismissOverlapChoices() {
        overlapChooser.dismiss();
    }

    private void showUnownedCursor(long displayTimestampNanos){
        cursorA=null;
        cursorADisplay=displayTimestampNanos;
        cursorAAbsolute=verifiedAbsolute(displayTimestampNanos);
        cursorMeasurementId="";
        applyCursorVisuals();
        updateSelectedTimeLabel();
        if(appService!=null && cursorAAbsolute!=null){
            appService.analysisSelection().selectNoOwner(cursorAAbsolute, TimeSelection.Origin.WAVEFORM);
        }
        refreshInvestigationInspector();
    }

    private void setCursorFromHit(String which, SharedTimelineHit.Hit hit){
        if(hit==null) return;
        boolean selection="S".equals(which);
        if(selection && cursorAPinned){
            transientSampleNanos=hit.storedTimestampNanos();
            sampleFromClick=true;
            long display=hit.absoluteTimestampNanos()==null?hit.storedTimestampNanos():hit.absoluteTimestampNanos();
            if(graphCanvas!=null) graphCanvas.setSelectionLine(display);
            updateSelectedTimeLabel();
            return;
        }
        String cursor=selection?"A":which;
        if(selection){
            cursorAPinned=false;
            transientSampleNanos=hit.storedTimestampNanos();
            sampleFromClick=true;
            if(graphCanvas!=null) graphCanvas.setSelectionLine(null);
        }else if("A".equals(cursor)){
            cursorAPinned=true;
            sampleFromClick=false;
        }else{
            cursorBPinned=true;
        }
        long stored=hit.storedTimestampNanos();
        Long absolute=hit.absoluteTimestampNanos();
        long display=absolute==null?stored:absolute;
        Long epoch=absolute==null||!sharedDisplayDomain?null:verifiedAbsolute(display);
        if("A".equals(cursor)){
            cursorA=stored;
            cursorADisplay=unknownOrderLayoutViewport()
                    ? layoutForStored(hit.measurementId(), stored) : display;
            cursorAAbsolute=epoch;
            cursorMeasurementId=hit.measurementId();
        }else{
            cursorB=stored;
            cursorBDisplay=display;
            cursorBAbsolute=epoch;
        }
        GraphInvestigationState inv=investigation();
        if(inv!=null){
            if("A".equals(cursor)) inv.setCursorA(stored); else inv.setCursorB(stored);
        }
        applyCursorVisuals();
        resolveCursors();
        updateSelectedTimeLabel();
        persistInvestigationSoon();
        if("A".equals(cursor)) publishGraphMeasurement(stored, epoch, hit.measurementId(), true);
        refreshInvestigationInspector();
    }

    private void applyCursorVisuals(){
        if(graphCanvas==null) return;
        graphCanvas.setCursors(cursorA, cursorB);
        graphCanvas.setSharedCursorDisplays(cursorADisplay, cursorBDisplay);
    }

    private Long verifiedAbsolute(long displayNanos){
        if(!sharedDisplayDomain) return displayNanos;
        return GraphTrackArrangement.addSaturating(sharedOriginUtc, displayNanos);
    }

    private Long displayAbsolute(String measurementId, long stored){
        if(measurementId==null || measurementId.isBlank()) return null;
        for(GraphLogicalTrack track:lastTracks){
            for(GraphTrackSegment segment:track.segments()){
                String id=segment.series().key()==null?"":segment.series().key().sourceFile();
                if(measurementId.equalsIgnoreCase(id) && segment.absoluteOriginNanos()!=null){
                    long display=GraphTrackArrangement.addSaturating(segment.absoluteOriginNanos(), stored);
                    return sharedDisplayDomain?GraphTrackArrangement.addSaturating(sharedOriginUtc, display):display;
                }
            }
        }
        return null;
    }

    private String measurementLabel(String measurementId){
        if(measurementId==null || measurementId.isBlank()) return "";
        for(GraphLogicalTrack track:lastTracks){
            for(GraphTrackSegment segment:track.segments()){
                String id=segment.series().key()==null?"":segment.series().key().sourceFile();
                if(measurementId.equalsIgnoreCase(id) && segment.measurementLabel()!=null && !segment.measurementLabel().isBlank()){
                    return segment.measurementLabel();
                }
            }
        }
        return "";
    }

    private void acceptSampleSelection(long nanos){
        transientSampleNanos=nanos;
        sampleFromClick=true;
        if(cursorAPinned){
            if(graphCanvas!=null) graphCanvas.setSelectionLine(nanos);
            updateSelectedTimeLabel();
            return;
        }
        if(graphCanvas!=null) graphCanvas.setSelectionLine(null);
        setCursor("A", nanos, false);
    }

    /** Drops the click line left behind by a point event. Pinned Cursor A and B stay. */
    private void clearTransientSelection(){
        transientSampleNanos=null;
        sampleFromClick=false;
        if(graphCanvas!=null) graphCanvas.setSelectionLine(null);
        if(cursorAPinned){
            updateSelectedTimeLabel();
            return;
        }
        cursorA=null;
        cursorAAbsolute=null;
        cursorADisplay=null;
        GraphInvestigationState inv=investigation();
        if(inv!=null) inv.setCursorA(null);
        applyCursorVisuals();
        updateCursorLabels(null, null);
        updateSelectedTimeLabel();
        persistInvestigationSoon();
        refreshInvestigationInspector();
    }

    private void setCursor(String which,long requested){
        setCursor(which, requested, true);
    }

    private void setCursor(String which,long requested, boolean pin){
        if("A".equals(which)){
            cursorAPinned=pin;
            if(pin) sampleFromClick=false;
        }else{
            cursorBPinned=true;
        }
        if(!fileFocusActive){
            cursorMeasurementId="";
            if("A".equals(which)){ cursorAAbsolute=null; cursorADisplay=null; }
            else { cursorBAbsolute=null; cursorBDisplay=null; }
        }else if("A".equals(which)){
            cursorMeasurementId=focusedMeasurementId();
        }
        if("A".equals(which))cursorA=requested;else cursorB=requested;
        GraphInvestigationState inv=investigation();
        if(inv!=null){
            if("A".equals(which)) inv.setCursorA(requested); else inv.setCursorB(requested);
        }
        applyCursorVisuals();
        resolveCursors();
        updateSelectedTimeLabel();
        persistInvestigationSoon();
        if(appService!=null&&"A".equals(which)){
            publishGraphMeasurement(requested, null, cursorMeasurementId, true);
        }
        refreshInvestigationInspector();
    }

    private void focusFileFromMap(String measurementId){
        if(!fileFocusActive || fileSlots.isEmpty() || measurementId==null || measurementId.isBlank()) return;
        int index=-1;
        for(int i=0;i<fileSlots.size();i++){
            if(measurementId.equalsIgnoreCase(fileSlots.get(i).measurementId())){ index=i; break; }
        }
        if(index<0 || index==focusedFileIndex) return;
        focusedFileIndex=index;
        fitFocusedFile();
        applyFileFocusChrome();
    }

    private void publishGraphMeasurement(Long timestamp, boolean placeMarker){
        publishGraphMeasurement(timestamp, null, "", placeMarker);
    }

    private void publishGraphMeasurement(Long timestamp, Long absolute, String measurementId, boolean placeMarker){
        if(applyingMapCursor || appService==null) return;
        String id=fileFocusActive?focusedMeasurementId():measurementId;
        if(id==null || id.isBlank()) id=focusedMeasurementId();
        if(id.isBlank()) return;
        long relative=timestamp==null?0L:timestamp;
        Long verified=fileFocusActive?null:absolute;
        boolean comparable=verified!=null;
        appService.analysisSelection().selectMeasurementPoint(
                id, relative, verified, comparable, placeMarker, TimeSelection.Origin.WAVEFORM);
    }

    private String focusedMeasurementId(){
        if(fileFocusActive && focusedFileIndex>=0 && focusedFileIndex<fileSlots.size()){
            return fileSlots.get(focusedFileIndex).measurementId();
        }
        if(fileSlots.size()==1) return fileSlots.getFirst().measurementId();
        return "";
    }
    private void resolveCursors(){
        GraphQueryProvider p=provider();if(p==null)return;List<SignalCatalogKey> keys=selectedKeys();if(keys.isEmpty())return;
        long a=cursorA==null?Long.MIN_VALUE:cursorA,b=cursorB==null?Long.MIN_VALUE:cursorB,span=Math.max(1,end-start);
        long width=graphCanvas==null?320:Math.max(320,(long)graphCanvas.getWidth());
        long tolerance=Math.max(1,span/width*3),my=generation; int session=boundSessionGeneration;
        Future<?> previous=pendingCursors; if(previous!=null) previous.cancel(true);
        java.util.Map<SignalCatalogKey,SignalCatalogKey> queryKeys=new java.util.LinkedHashMap<>();
        for(SignalCatalogKey key:keys){
            SignalCatalogKey queryKey=key;
            if(fileFocusActive){
                GraphRenderSeries focused=renderedFor(key);
                if(focused!=null && focused.key()!=null) queryKey=focused.key();
            }else if(cursorMeasurementId!=null && !cursorMeasurementId.isBlank()){
                GraphRenderSeries owned=renderedForMeasurement(key, cursorMeasurementId);
                if(owned!=null && owned.key()!=null) queryKey=owned.key();
            }
            queryKeys.put(key, queryKey);
        }
        pendingCursors=worker.submit(()->{
            java.util.ArrayList<SummaryStatsRow> summary=new java.util.ArrayList<>(keys.size());
            java.util.ArrayList<CursorCompareRow> compare=new java.util.ArrayList<>(keys.size());
            GraphCursorSample leadA=null,leadB=null;
            for(int i=0;i<keys.size();i++){
                if(!acceptViewportResult(my,session)) return;
                SignalCatalogKey key=keys.get(i);
                SignalCatalogKey queryKey=queryKeys.getOrDefault(key, key);
                GraphCursorSample sa=a==Long.MIN_VALUE?null:p.graphQuery().nearest(queryKey,a,tolerance);
                GraphCursorSample sb=b==Long.MIN_VALUE?null:p.graphQuery().nearest(queryKey,b,tolerance);
                if(i==0){leadA=sa;leadB=sb;}
                String unit=sa!=null?sa.unit():(sb!=null?sb.unit():"");
                Stats stats=statsFor(key);
                summary.add(new SummaryStatsRow(displayName(key),unit.isBlank()?unitFromSeries(key):unit,stats.samples(),stats.min(),stats.max(),stats.average(),stats.rms()));
                compare.add(new CursorCompareRow(displayName(key),time(sa),value(sa),time(sb),value(sb),deltaTime(sa,sb),deltaValue(sa,sb)));
            }
            GraphCursorSample fa=leadA,fb=leadB;
            boolean hasCursor=cursorA!=null||cursorB!=null;
            Platform.runLater(()->{
                if(!acceptViewportResult(my,session)) return;
                updateCursorLabels(fa,fb);
                if(summaryStatsTable!=null) summaryStatsTable.setItems(FXCollections.observableArrayList(summary));
                if(cursorCompareTable!=null){
                    cursorCompareTable.setItems(hasCursor
                            ?FXCollections.observableArrayList(compare)
                            :FXCollections.observableArrayList());
                }
            });
        });
    }
    private static String time(GraphCursorSample s){return s==null?"—":String.format("%.6f s",s.timestampNanos()/1e9);}
    private static String value(GraphCursorSample s){
        if(s==null) return "—";
        String state=com.stellar.platform.domain.EnumeratedState.codeAndText(
                s.rawValue(), null, s.physicalValue(), s.textualValue());
        if(state!=null) return state;
        String label=com.stellar.platform.domain.CategoricalValue.label(
                com.stellar.platform.domain.SignalValidity.VALID, s.physicalValue(), s.textualValue());
        return label!=null?label:fmt(s.physicalValue());
    }
    private static String deltaTime(GraphCursorSample a,GraphCursorSample b){return a==null||b==null?"—":String.format("%+.6f s",(b.timestampNanos()-a.timestampNanos())/1e9);}
    private static String deltaValue(GraphCursorSample a,GraphCursorSample b){
        if(a==null||b==null) return "—";
        if(com.stellar.platform.domain.CategoricalValue.isCategorical(com.stellar.platform.domain.SignalValidity.VALID, a.physicalValue(), a.textualValue())
                || com.stellar.platform.domain.CategoricalValue.isCategorical(com.stellar.platform.domain.SignalValidity.VALID, b.physicalValue(), b.textualValue())){
            return "—";
        }
        return fmt(b.physicalValue()-a.physicalValue());
    }
    public record SummaryStatsRow(String signal,String unit,String samples,String min,String max,String mean,String rms){}
    public record CursorCompareRow(String signal,String aTime,String aValue,String bTime,String bValue,String deltaTime,String deltaValue){}
    /** @deprecated kept for binary test compatibility; prefer SummaryStatsRow / CursorCompareRow */
    @Deprecated public record CursorRow(String signal,String current,String unit,String min,String max,String average,String rms,String aTime,String a,String bTime,String b,String deltaTime,String delta){}

    private record ScrollSlice(double offset, double viewportHeight, double stride) {}

    private ScrollSlice waveformSlice(){
        double viewportH=0;
        double offset=0;
        double stride=graphCanvas==null?140d:graphCanvas.nominalTrackStride();
        if(waveformScroll!=null){
            javafx.geometry.Bounds bounds=waveformScroll.getViewportBounds();
            viewportH=bounds==null?0:bounds.getHeight();
            double contentH=graphCanvas==null?0:graphCanvas.getHeight();
            double range=Math.max(0, contentH-Math.max(0, viewportH));
            offset=waveformScroll.getVvalue()*range;
        }
        return new ScrollSlice(offset, viewportH, stride);
    }

    private TrackQueryWindow.Range currentTrackWindow(int trackCount){
        boolean stacked=investigation()==null || investigation().stacked();
        if(!stacked){
            return new TrackQueryWindow.Range(0, Math.min(trackCount, TrackQueryWindow.OVERLAY_CAP));
        }
        ScrollSlice slice=waveformSlice();
        return TrackQueryWindow.of(trackCount, slice.offset(), slice.viewportHeight(), slice.stride(), TrackQueryWindow.OVERSCAN);
    }

    private String residenceText(List<SignalCatalogKey> ordered, TrackQueryWindow.Range window){
        int total=ordered==null?0:ordered.size();
        if(window==null || total<=0) return "";
        int shown=window.size();
        boolean stacked=investigation()==null || investigation().stacked();
        if(!stacked && total>shown){
            String first=signalNameAt(ordered, window.fromInclusive());
            String last=signalNameAt(ordered, window.toExclusive()-1);
            return " · Showing "+shown+" of "+total+" selected signals ("+first+" through "+last+")"
                    +". Switch to Stacked to inspect the rest";
        }
        if(shown<total){
            return " · showing "+(window.fromInclusive()+1)+"–"+window.toExclusive()+" of "+total;
        }
        return "";
    }

    private static String signalNameAt(List<SignalCatalogKey> ordered, int index){
        if(ordered==null || index<0 || index>=ordered.size()) return "";
        String name=ordered.get(index).signalName();
        return name==null?"":name;
    }

    private void installQueryPlaceholders(List<SignalCatalogKey> ordered, TrackQueryWindow.Range window, boolean timeChanged){
        if(graphCanvas==null || ordered==null || ordered.isEmpty()) return;
        adjustingTrackWindow=true;
        try{
            List<GraphLogicalTrack> seed=retainedTracks(ordered, window, timeChanged);
            long from=start;
            long to=end<=start?start+1:end;
            graphCanvas.presentTogether(()->{
                graphCanvas.setUnloadedKeys(unloadedKeys(ordered, seed));
                graphCanvas.setLogicalTracks(composeTracks(ordered, seed, from, to));
            });
        }finally{
            adjustingTrackWindow=false;
        }
    }

    private List<GraphLogicalTrack> retainedTracks(List<SignalCatalogKey> ordered, TrackQueryWindow.Range window, boolean timeChanged){
        if(timeChanged || window==null) return List.of();
        java.util.ArrayList<GraphLogicalTrack> kept=new java.util.ArrayList<>();
        for(GraphLogicalTrack track:lastTracks){
            int index=ordered.indexOf(track.logicalKey());
            if(index>=window.fromInclusive() && index<window.toExclusive() && trackHasSamples(track)){
                kept.add(track);
            }
        }
        return kept;
    }

    private static boolean trackHasSamples(GraphLogicalTrack track){
        for(GraphTrackSegment segment:track.segments()){
            if(segment.series()!=null && !segment.series().buckets().isEmpty()) return true;
        }
        return false;
    }

    private static List<GraphMeasurementSeries.PlotGroup> groupsForKeys(
            List<GraphMeasurementSeries.PlotGroup> groups,
            List<SignalCatalogKey> ordered,
            int from,
            int to
    ){
        if(groups==null || ordered==null || ordered.isEmpty() || to<=from) return List.of();
        int start=Math.max(0, Math.min(from, ordered.size()));
        int end=Math.max(start, Math.min(to, ordered.size()));
        java.util.Map<SignalCatalogKey, GraphMeasurementSeries.PlotGroup> byKey=new java.util.LinkedHashMap<>();
        for(GraphMeasurementSeries.PlotGroup group:groups){
            if(group!=null && group.logicalKey()!=null) byKey.put(group.logicalKey(), group);
        }
        java.util.ArrayList<GraphMeasurementSeries.PlotGroup> out=new java.util.ArrayList<>();
        for(int i=start;i<end;i++){
            GraphMeasurementSeries.PlotGroup group=byKey.get(ordered.get(i));
            if(group!=null) out.add(group);
        }
        return out;
    }

    private static GraphLogicalTrack placeholderTrack(SignalCatalogKey key, long from, long to){
        long end=to<=from?from+1:to;
        GraphRenderSeries series=new GraphRenderSeries(key, "", 0, false, from, end, List.of());
        return new GraphLogicalTrack(key, List.of(new GraphTrackSegment(series, "", null)), false);
    }

    private static List<GraphLogicalTrack> composeTracks(
            List<SignalCatalogKey> ordered,
            List<GraphLogicalTrack> built,
            long from,
            long to
    ){
        java.util.Map<SignalCatalogKey, GraphLogicalTrack> byKey=new java.util.LinkedHashMap<>();
        if(built!=null){
            for(GraphLogicalTrack track:built){
                if(track!=null) byKey.put(track.logicalKey(), track);
            }
        }
        java.util.ArrayList<GraphLogicalTrack> out=new java.util.ArrayList<>();
        if(ordered!=null){
            for(SignalCatalogKey key:ordered){
                if(key==null) continue;
                GraphLogicalTrack track=byKey.get(key);
                out.add(track==null?placeholderTrack(key, from, to):track);
            }
        }
        return List.copyOf(out);
    }

    private static java.util.Set<SignalCatalogKey> unloadedKeys(List<SignalCatalogKey> ordered, List<GraphLogicalTrack> built){
        java.util.Set<SignalCatalogKey> have=new java.util.HashSet<>();
        if(built!=null){
            for(GraphLogicalTrack track:built){
                if(track!=null) have.add(track.logicalKey());
            }
        }
        java.util.LinkedHashSet<SignalCatalogKey> unloaded=new java.util.LinkedHashSet<>();
        if(ordered!=null){
            for(SignalCatalogKey key:ordered){
                if(key!=null && !have.contains(key)) unloaded.add(key);
            }
        }
        return unloaded;
    }

    private void publishVisibleTracks(
            long my,
            int session,
            List<GraphLogicalTrack> partial,
            java.util.Set<SignalCatalogKey> unloaded,
            long renderStart,
            long renderEnd,
            boolean unknownOrder,
            long startedNanos
    ){
        Platform.runLater(()->{
            if(!acceptViewportResult(my, session) || graphCanvas==null) return;
            graphCanvas.setUnloadedKeys(unloaded);
            graphCanvas.prepareSharedViewport(renderStart, renderEnd);
            graphCanvas.setUnknownOrderOverview(unknownOrder);
            graphCanvas.setLogicalTracks(partial);
            setGraphStatus("Loading waveform…");
            LOG.log(System.Logger.Level.INFO, ()->"graph-viewport stage=visible tracks="+partial.size()
                    +" elapsedMs="+((System.nanoTime()-startedNanos)/1_000_000L)
                    +" range="+renderStart+".."+renderEnd);
        });
    }

    private boolean plotGroups(
            GraphViewportEngine engine,
            List<GraphMeasurementSeries.PlotGroup> groups,
            boolean focus,
            FileSlot focused,
            boolean comparable,
            RecordingGroupIndex groupIndex,
            SharedDisplayTimeline timeline,
            long renderStart,
            long renderEnd,
            int width,
            long my,
            int session,
            java.util.List<GraphRenderSeries> rendered,
            java.util.List<GraphLogicalTrack> builtTracks,
            java.util.List<String> failures
    ){
        if(groups==null) return true;
        for(GraphMeasurementSeries.PlotGroup group:groups){
            if(!acceptViewportResult(my, session)) return false;
            java.util.ArrayList<GraphTrackSegment> segments=new java.util.ArrayList<>();
            boolean everyClockComparable=!focus && comparable && group.physicalKeys().size()>1;
            if(focus){
                int match=indexOfMeasurement(group, focused.measurementId());
                if(match<0){
                    segments.add(new GraphTrackSegment(new GraphRenderSeries(
                            group.logicalKey(), group.unit(), 0, false, renderStart, renderEnd, List.of()),
                            focused.label(), null));
                }else{
                    appendSegment(engine, group, match, renderStart, renderEnd, width, my, session,
                            false, null, rendered, segments, failures);
                }
                if(!segments.isEmpty()){
                    builtTracks.add(new GraphLogicalTrack(group.logicalKey(), segments, false));
                }
                continue;
            }
            TimelineViewportPlanner.Plan utcPlan=timeline==null?null:planFor(
                    group, groupIndex, TimelineGroup.VERIFIED_UTC, renderStart, renderEnd);
            java.util.Set<String> utcVisible=utcPlan==null?null:measurementIds(utcPlan.visible());
            for(int i=0;i<group.physicalKeys().size();i++){
                if(!acceptViewportResult(my, session)) return false;
                String measurementId=group.physicalKeys().get(i).sourceFile();
                if(timeline!=null && (utcVisible==null || !utcVisible.contains(measurementId))){
                    continue;
                }
                if(!appendSegment(engine, group, i, renderStart, renderEnd, width, my, session,
                        everyClockComparable, timeline, rendered, segments, failures)){
                    everyClockComparable=false;
                }
            }
            if(timeline!=null){
                appendMissingUtc(group, groupIndex, timeline, utcVisible, renderStart, renderEnd, segments);
                warmPrefetch(engine, group, utcPlan, timeline, width, my, session);
            }
            if(segments.isEmpty()){
                segments.add(new GraphTrackSegment(new GraphRenderSeries(
                        group.logicalKey(), group.unit(), 0, false, renderStart, renderEnd, List.of()),
                        "", null));
            }
            if(!everyClockComparable){
                java.util.ArrayList<GraphTrackSegment> relative=new java.util.ArrayList<>(segments.size());
                for(GraphTrackSegment segment:segments){
                    relative.add(new GraphTrackSegment(segment.series(), segment.measurementLabel(), null));
                }
                segments=relative;
            }
            if(!segments.isEmpty()){
                builtTracks.add(new GraphLogicalTrack(group.logicalKey(), segments, everyClockComparable));
            }
        }
        return true;
    }

    private static final String GRAPH_HELP_CHROME =
            " · drag=pan · click=select sample · wheel=zoom · Shift+click=Cursor A · Ctrl+click=Cursor B";

    /** Static graph chrome. Pointer, hover, and waveform repaint must not call this. */
    private void updateGraphHelpStatus(long tracks, int failed){
        updateGraphHelpStatus(tracks, failed, false);
    }

    private void updateGraphHelpStatus(long tracks, int failed, boolean emptyViewport){
        updateGraphHelpStatus(tracks, failed, emptyViewport, "");
    }

    private void updateGraphHelpStatus(long tracks, int failed, boolean emptyViewport, String residence){
        String fail=failed<=0?"":" · "+failed+" failed";
        String group=unknownOrderDomain?" · UNKNOWN ORDER":sharedDisplayDomain?" · VERIFIED UTC":"";
        String empty=emptyViewport?" · empty":"";
        String note=residence==null?"":residence;
        setGraphStatus(tracks+" track(s)"+note+GRAPH_HELP_CHROME+group+empty+fail);
    }

    private void setGraphStatus(String text){
        if(statusLabel==null || text==null || text.equals(statusLabel.getText())) return;
        if(text.contains("track(s)") && text.contains(GRAPH_HELP_CHROME)) graphHelpApplies++;
        statusLabel.setText(text);
    }

    private void updateLiveCursor(Long timestampNanos){
        if(liveCursorLabel==null)return;
        if(timestampNanos==null) showReadout(liveCursorLabel, "Pointer: Hover waveform for value");
    }
    private void updateLiveCursorPoint(AutomotiveSignalCanvas.HoverPoint point){
        if(liveCursorLabel==null)return;
        if(point==null){showReadout(liveCursorLabel, "Pointer: Hover waveform for values");return;}
        Long raw=categoricalRaw(point);
        showReadout(liveCursorLabel, com.stellar.platform.desktop.presentation.SampleDisplay.provenance(
                point.signalName(), point.measurement(), point.unit(), point.value(),
                point.categoricalLabel(), point.pointerTimestampNanos(), point.timestampNanos(),
                point.bucketStartNanos(), point.bucketEndNanos(), point.bucketSampleCount(),
                point.valueRole(), point.validityName(), raw));
        scheduleHoverTable(point.timestampNanos());
    }
    private Long categoricalRaw(AutomotiveSignalCanvas.HoverPoint point){
        if(point.categoricalLabel()==null || point.categoricalLabel().isBlank()) return null;
        SignalsScreenModel model=signalsModel();
        com.stellar.platform.processing.catalog.SignalSeriesStore store=model==null?null:model.catalogStore();
        if(store==null) return null;
        String shown=point.signalName()==null?"":point.signalName();
        java.util.LinkedHashSet<SignalCatalogKey> keys=new java.util.LinkedHashSet<>();
        for(GraphRenderSeries series:lastRendered){
            if(matchesShownTrace(series.key(), shown)) keys.add(series.key());
        }
        com.stellar.platform.domain.catalog.SignalCatalog catalog=store.snapshot();
        if(catalog!=null){
            for(SignalCatalogEntry entry:catalog.entries()){
                if(matchesShownTrace(entry.key(), shown)) keys.add(entry.key());
            }
        }
        for(SignalCatalogKey candidate:keys){
            Long raw=rawAt(store, candidate, point.timestampNanos());
            if(raw!=null) return raw;
        }
        return null;
    }
    /** Hover text may be a disambiguated trace label, not the stored signal name. */
    private boolean matchesShownTrace(SignalCatalogKey key, String shown){
        if(key==null || shown==null || shown.isBlank()) return false;
        if(shown.equals(key.signalName()) || shown.equals(displayName(key))) return true;
        return shown.startsWith(key.signalName()+" \u00b7 ");
    }
    private static Long rawAt(com.stellar.platform.processing.catalog.SignalSeriesStore store, SignalCatalogKey key, long timestampNanos){
        java.util.List<com.stellar.platform.domain.catalog.SignalSamplePoint> page=
                store.queryTimestampRange(key, timestampNanos, timestampNanos, 1);
        if(page.isEmpty() || page.getFirst().timestampNanos()!=timestampNanos){
            long index=store.findSampleIndexAtOrAfter(key, timestampNanos);
            if(index<0 || index>Integer.MAX_VALUE) return null;
            page=store.querySamples(key, (int)index, 1);
        }
        if(page.isEmpty() || page.getFirst().timestampNanos()!=timestampNanos) return null;
        return page.getFirst().rawValue();
    }
    private void scheduleHoverTable(long timestamp){
        ScheduledFuture<?> old=pendingHover;if(old!=null)old.cancel(false);
        long my=generation;
        pendingHover=viewportScheduler.schedule(()->populateHoverTable(timestamp,my),75,TimeUnit.MILLISECONDS);
    }
    private void populateHoverTable(long timestamp,long my){
        // Hover updates live cursor chrome only; Statistics tables refresh from viewport/cursors.
        if(my!=generation) return;
        populateSummaryTable();
        if(cursorA!=null||cursorB!=null) resolveCursors();
    }
    private void populateSummaryTable(){
        java.util.ArrayList<SummaryStatsRow> summary=new java.util.ArrayList<>();
        for(SignalCatalogKey key:selectedKeys()){
            Stats stats=statsFor(key);
            summary.add(new SummaryStatsRow(displayName(key),unitFromSeries(key),stats.samples(),stats.min(),stats.max(),stats.average(),stats.rms()));
        }
        if(summaryStatsTable!=null) summaryStatsTable.setItems(FXCollections.observableArrayList(summary));
        if(cursorCompareTable!=null && cursorA==null && cursorB==null){
            cursorCompareTable.setItems(FXCollections.observableArrayList());
        }
    }
    private String unitFromSeries(SignalCatalogKey key){
        GraphRenderSeries series=renderedFor(key);
        return series==null||series.unit()==null?"":series.unit();
    }
    private GraphRenderSeries renderedForMeasurement(SignalCatalogKey key, String measurementId){
        if(measurementId==null || measurementId.isBlank()) return null;
        for(GraphLogicalTrack track:lastTracks){
            boolean sameSignal=key!=null && (key.equals(track.logicalKey()) || track.segments().stream().anyMatch(segment->key.equals(segment.series().key())));
            if(!sameSignal && key!=null) continue;
            for(GraphTrackSegment segment:track.segments()){
                String id=segment.series().key()==null?"":segment.series().key().sourceFile();
                if(measurementId.equalsIgnoreCase(id)) return segment.series();
            }
        }
        return null;
    }

    private GraphRenderSeries renderedFor(SignalCatalogKey key){
        if(key==null) return null;
        for(GraphLogicalTrack track:lastTracks){
            if(key.equals(track.logicalKey()) && !track.segments().isEmpty()) return track.segments().getFirst().series();
        }
        for(GraphRenderSeries series:lastRendered) if(key.equals(series.key())) return series;
        return null;
    }
    private Stats statsFor(SignalCatalogKey key){
        GraphRenderSeries matched=renderedFor(key);
        if(matched!=null){
            return statsOf(matched);
        }
        return new Stats("—","—","—","—","—");
    }
    private Stats statsOf(GraphRenderSeries s){
        java.util.DoubleSummaryStatistics ds=new java.util.DoubleSummaryStatistics(); double squares=0; long count=0; long samples=0;
        for(GraphBucket b:s.buckets()){
            samples+=b.sampleCount();
            double[] values={b.firstValue(),b.minValue(),b.maxValue(),b.lastValue()};
            com.stellar.platform.domain.SignalValidity[] states={
                    b.firstValidity(),b.minValidity(),b.maxValidity(),b.lastValidity()};
            for(int i=0;i<values.length;i++){
                if(states[i]!=null && states[i].usableWith(values[i])){
                    ds.accept(values[i]);squares+=values[i]*values[i];count++;
                }
            }
        }
        if(count>0)return new Stats(Long.toString(samples),fmt(ds.getMin()),fmt(ds.getMax()),fmt(ds.getAverage()),fmt(Math.sqrt(squares/count)));
        return new Stats("—","—","—","—","—");
    }
    private static String fmt(double v){
        if(!Double.isFinite(v)) return "—";
        double abs=Math.abs(v);
        if(abs!=0 && (abs>=1e6 || abs<1e-3)) return String.format("%.4e",v);
        if(abs>=100) return String.format("%.2f",v);
        if(abs>=1) return String.format("%.4f",v);
        return String.format("%.6g",v);
    }
    private record Stats(String samples,String min,String max,String average,String rms){}
    private void updateCursorLabels(GraphCursorSample a,GraphCursorSample b){
        showReadout(cursorALabel, a==null?"A: —":"A: "+formatPoint(a));
        showReadout(cursorBLabel, b==null?"B: —":"B: "+formatPoint(b));
        if(a!=null&&b!=null){
            showReadout(deltaLabel, String.format("ΔT %.6f s   ΔValue %+.6f %s",(b.timestampNanos()-a.timestampNanos())/1e9,b.physicalValue()-a.physicalValue(),a.unit()));
        }else{
            showReadout(deltaLabel, "Δ: —");
        }
    }

    /** Visible text may ellipsize. The tooltip keeps the full readout. */
    private static void showReadout(Label label, String text){
        if(label==null || text==null) return;
        if(!text.equals(label.getText())) label.setText(text);
        Tooltip tip=label.getTooltip();
        if(tip==null){
            tip=new Tooltip(text);
            tip.setWrapText(true);
            tip.setMaxWidth(480);
            label.setTooltip(tip);
        }else if(!text.equals(tip.getText())){
            tip.setText(text);
        }
    }
    private void drillDown(long t){
        long span=Math.max(1,end-start),half=Math.max(1,span/20);
        cursorA=t;analysisTabs.getSelectionModel().select(0);
        applyViewport(t-half,t+half);resolveCursors();
    }
    private static String formatPoint(GraphCursorSample s){
        String label=com.stellar.platform.domain.CategoricalValue.label(
                com.stellar.platform.domain.SignalValidity.VALID, s.physicalValue(), s.textualValue());
        if(label!=null){
            String file=s.key()==null||s.key().sourceFile()==null?"":s.key().sourceFile();
            return String.format("%.6f s  %s%s", s.timestampNanos()/1e9, label, file.isBlank()?"":"  "+file);
        }
        return String.format("%.6f s  %.6f %s",s.timestampNanos()/1e9,s.physicalValue(),s.unit());
    }
    private String formatRange(long a,long b){
        if(unknownOrderDomain && !fileFocusActive){
            long span=UnknownOrderLayout.EQUAL_SLOT_SPAN;
            long first=Math.max(0L, a/span);
            long last=b>a && b%span==0L ? (b/span)-1L : Math.max(first, b/span);
            long total=Math.max(unknownGroupCount, (int)Math.min(Integer.MAX_VALUE, last+1));
            last=Math.min(Math.max(first, last), Math.max(0, total-1));
            return "Slots "+(first+1)+"–"+(last+1)+" of "+total+" · not elapsed time";
        }
        return String.format("%.3f s — %.3f s",a/1e9,b/1e9);
    }

    private GraphInvestigationState investigation(){
        return appService==null?null:appService.graphInvestigation();
    }
    private SignalsScreenModel signalsModel(){
        return appService==null?null:appService.signalsModel();
    }

    boolean acceptViewportResult(long resultGeneration,int resultSession){
        if(disposed) return false;
        GraphInvestigationState inv=investigation();
        int liveSession=inv==null?boundSessionGeneration:inv.sessionGeneration();
        boolean accept=resultGeneration==generation && resultSession==liveSession;
        if(accept) viewportAccepted.incrementAndGet();
        else viewportRejectedStale.incrementAndGet();
        return accept;
    }

    private List<SignalCatalogEntry> visibleEntries(){
        java.util.Set<SignalCatalogKey> visible=new java.util.LinkedHashSet<>(selectedKeys());
        return selectedEntries().stream().filter(e->visible.contains(e.key())).toList();
    }

    private String displayName(SignalCatalogKey key){
        return SignalTraceLabels.displayName(key, selectedEntries());
    }

    private void applyCanvasPresentation(){
        if(graphCanvas==null) return;
        java.util.Map<SignalCatalogKey,Integer> colors=new java.util.LinkedHashMap<>();
        GraphInvestigationState inv=investigation();
        for(SignalCatalogKey key:selectedSignalKeys){
            colors.put(key, inv==null?colors.size():inv.colorIndex(key));
        }
        java.util.Map<SignalCatalogKey,String> labels=new java.util.LinkedHashMap<>(
                SignalTraceLabels.labels(selectedEntries()));
        graphCanvas.setTracePresentation(labels, colors);
    }

    private void applyRestoredLayout(GraphInvestigationState inv){
        if(inv==null) return;
        boolean stacked=inv.stacked();
        applyingLayoutChrome=true;
        try{
            if(stackedLayoutButton!=null) stackedLayoutButton.setSelected(stacked);
            if(overlayLayoutButton!=null) overlayLayoutButton.setSelected(!stacked);
            if(graphCanvas!=null){
                graphCanvas.setLayoutMode(stacked?AutomotiveSignalCanvas.LayoutMode.STACKED:AutomotiveSignalCanvas.LayoutMode.OVERLAY_MULTI_AXIS);
            }
            updateLayoutDependentControls(stacked);
        }finally{
            applyingLayoutChrome=false;
        }
    }

    private void applyRestoredPresentation(GraphInvestigationState inv){
        if(inv==null) return;
        if(analysisTabs!=null){
            int tab=Math.min(inv.analysisTab(), Math.max(0, analysisTabs.getTabs().size()-1));
            Tab chosen=analysisTabs.getTabs().get(tab);
            if(chosen!=null && chosen.isDisable()){
                analysisTabs.getSelectionModel().select(0);
            }else{
                analysisTabs.getSelectionModel().select(tab);
            }
            updateActiveAnalysisPane();
        }
        setInspectorVisible(inv.inspectorVisible(), false);
    }

    private void syncPresentationToInvestigation(){
        GraphInvestigationState inv=investigation();
        if(inv==null) return;
        if(analysisTabs!=null){
            inv.setAnalysisTab(analysisTabs.getSelectionModel().getSelectedIndex());
        }
        inv.setInspectorVisible(isInspectorOpen());
        if(focusedExplorerKey!=null){
            inv.setFocus(focusedExplorerKey);
        }
    }

    private void updateEmptyState(){
        boolean empty=selectedSignalKeys.isEmpty();
        boolean map=analysisTabs!=null && analysisTabs.getSelectionModel().getSelectedIndex()==MAP_ROUTE_TAB_INDEX;
        setManagedVisible(emptyStateBox, empty && !map);
        if(empty){
            if(statusLabel!=null) setGraphStatus("No signals plotted yet.");
            if(graphCanvas!=null) graphCanvas.setEmptyHint("No signals plotted yet.");
        }else if(!failedTraces.isEmpty() && lastRendered.isEmpty()){
            if(statusLabel!=null) setGraphStatus("Selected signal data unavailable.");
            if(graphCanvas!=null) graphCanvas.setEmptyHint("Selected signal data unavailable.");
        }
    }

    private void persistInvestigationSoon(){
        if(appService==null || restoringPresentation) return;
        // Debounce dirty marking for high-frequency viewport changes; durable write is Save-only.
        ScheduledFuture<?> previous=pendingInvestigationSave;
        if(previous!=null) previous.cancel(false);
        pendingInvestigationSave=viewportScheduler.schedule(()->{
            if(appService!=null) appService.noteGraphLogicalChange();
        },400,TimeUnit.MILLISECONDS);
    }

    private void persistInvestigationNow(){
        if(appService==null || restoringPresentation) return;
        syncPresentationToInvestigation();
        appService.noteGraphLogicalChange();
    }

    private void installEscapeHandler(){
        if(graphCanvas==null || graphCanvas.getScene()==null){
            if(graphCanvas!=null){
                graphCanvas.sceneProperty().addListener((o,a,b)->{
                    if(b!=null) installEscapeHandler();
                });
            }
            return;
        }
        uninstallEscapeHandler();
        escapeHandler=event->{
            if(event.getCode()==javafx.scene.input.KeyCode.ESCAPE && focusGraph){
                onToggleFocusGraph();
                event.consume();
            }
        };
        escapeScene=graphCanvas.getScene();
        escapeScene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, escapeHandler);
    }

    private void uninstallEscapeHandler(){
        if(escapeHandler!=null && escapeScene!=null){
            escapeScene.removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, escapeHandler);
        }
        escapeScene=null;
        escapeHandler=null;
    }

    @FXML private void onResetView(){
        refreshFullAnalysisRange();
        if(fitToFullRange("RESET_VIEW")){
            requestViewport();
            return;
        }
        GraphInvestigationState inv=investigation();
        if(inv!=null && inv.resetView()){
            commitViewport("RESET_VIEW_DEFAULT",inv.viewportStart(),inv.viewportEnd());
            requestViewport();
            return;
        }
        if(statusLabel!=null) setGraphStatus("No timestamped samples available");
    }

    @FXML private void onShowAll(){
        GraphInvestigationState inv=investigation();
        if(inv==null) return;
        for(SignalCatalogKey key:selectedSignalKeys) inv.show(key);
        if(signalList!=null) signalList.refresh();
        updateSelectionLabel();
        rebuildSelectedSignalCards();
        applyCanvasVisibility();
        updateEmptyStateForVisibility();
        persistInvestigationSoon();
    }

    @FXML private void onHideAll(){
        GraphInvestigationState inv=investigation();
        if(inv==null) return;
        for(SignalCatalogKey key:selectedSignalKeys) inv.hide(key);
        if(signalList!=null) signalList.refresh();
        updateSelectionLabel();
        rebuildSelectedSignalCards();
        applyCanvasVisibility();
        updateEmptyStateForVisibility();
        persistInvestigationSoon();
    }

    /**
     * Adds every currently filtered explorer row to analysis membership, then requests one viewport.
     * Show All remains visibility-only.
     */
    @FXML private void onSelectAllFiltered(){
        int before=selectedSignalKeys.size();
        boolean changed=false;
        SignalsScreenModel model=signalsModel();
        GraphInvestigationState inv=investigation();
        membershipCommandCount++;
        for(SignalCatalogEntry entry:java.util.List.copyOf(explorerRows)){
            SignalCatalogEntry canonical=canonicalMembershipEntry(entry);
            if(canonical==null||canonical.key()==null) continue;
            SignalCatalogKey key=canonical.key();
            if(selectedSignalKeys.contains(key)) continue;
            if(model!=null && !model.addPhysicalToWorkspace(key)) continue;
            selectedSignalKeys.add(key);
            if(inv!=null){
                inv.show(key);
                inv.colorIndex(key);
            }
            changed=true;
        }
        if(changed) bumpSelectedKeysRevision("selectAllFiltered", before);
        if(signalList!=null) signalList.refresh();
        updateSelectionLabel();
        refreshMembershipPresentation();
        rebuildSelectedSignalCards();
        updateEmptyState();
        if(mapRoutePane!=null) mapRoutePane.setSelectedSignals(selectedKeys());
        if(changed) requestViewport();
        persistInvestigationSoon();
    }

    /**
     * Removes currently filtered explorer rows from analysis membership once.
     * Hide All remains visibility-only and is not called here.
     */
    @FXML private void onDeselectAllFiltered(){
        java.util.ArrayList<SignalCatalogKey> keys=new java.util.ArrayList<>();
        for(SignalCatalogEntry entry:explorerRows){
            SignalCatalogEntry canonical=canonicalMembershipEntry(entry);
            if(canonical==null||canonical.key()==null) continue;
            if(selectedSignalKeys.contains(canonical.key())) keys.add(canonical.key());
        }
        if(keys.isEmpty()) return;
        membershipCommandCount++;
        generation++;
        cancelPendingGraphWork(true);
        SignalsScreenModel model=signalsModel();
        if(model!=null) model.removePhysicalKeys(keys);
        int before=selectedSignalKeys.size();
        java.util.HashSet<SignalCatalogKey> drop=new java.util.HashSet<>(keys);
        selectedSignalKeys.removeAll(drop);
        bumpSelectedKeysRevision("deselectAllFiltered", before);
        GraphInvestigationState inv=investigation();
        if(inv!=null){
            for(SignalCatalogKey key:keys) inv.forgetTrace(key);
        }
        for(SignalCatalogKey key:keys) failedTraces.remove(key);
        lastRendered=lastRendered.stream().filter(series->!drop.contains(series.key())).toList();
        if(inv!=null){
            inv.setLiveViewportResults(lastRendered.size());
            inv.setLiveTraceModels(lastRendered.size());
        }
        if(signalList!=null) signalList.refresh();
        refreshMembershipPresentation();
        rebuildSelectedSignalCards();
        updateEmptyState();
        if(mapRoutePane!=null) mapRoutePane.setSelectedSignals(selectedKeys());
        if(selectedSignalKeys.isEmpty()){
            clearRenderedAnalysis();
            if(statusLabel!=null) setGraphStatus("No signals plotted yet.");
        }else{
            applyCanvasVisibility();
            requestViewport();
        }
        persistInvestigationSoon();
    }

    @FXML private void onMoveUp(){ moveFocused(-1); }
    @FXML private void onMoveDown(){ moveFocused(1); }

    private void refreshOrderActions(){
        SignalCatalogEntry focused=signalList==null?null:signalList.getSelectionModel().getSelectedItem();
        if(moveUpItem!=null) moveUpItem.setDisable(!canMove(focused, -1));
        if(moveDownItem!=null) moveDownItem.setDisable(!canMove(focused, 1));
    }

    private boolean canMove(SignalCatalogEntry entry, int delta){
        if(entry==null || entry.key()==null || delta==0) return false;
        SignalsScreenModel model=signalsModel();
        if(model==null) return false;
        java.util.List<SignalCatalogKey> keys=model.workspaceKeys();
        int index=keys.indexOf(entry.key());
        if(index<0) return false;
        int target=index+delta;
        return target>=0 && target<keys.size();
    }

    private void moveFocused(int delta){
        SignalCatalogEntry focused=signalList==null?null:signalList.getSelectionModel().getSelectedItem();
        SignalsScreenModel model=signalsModel();
        if(focused==null||model==null) return;
        if(!model.moveWorkspaceKey(focused.key(), delta)) return;
        selectedSignalKeys.clear();
        selectedSignalKeys.addAll(model.workspaceKeys());
        applySignalFilter();
        rebuildSelectedSignalCards();
        refreshOrderActions();
        requestViewport();
        persistInvestigationSoon();
    }

    private void clearTemporaryLegendVisibility(){
        GraphInvestigationState inv=investigation();
        if(inv!=null) inv.clearTemporaryVisibility();
    }

    private void toggleHidden(SignalCatalogKey key){
        setSignalVisible(key, investigation()!=null&&investigation().isHidden(key));
    }

    /**
     * VISIBILITY only. Does not change analysis membership or checkbox selection.
     * Prefer composition update over re-query when series are already loaded.
     */
    private void setSignalVisible(SignalCatalogKey key, boolean visible){
        GraphInvestigationState inv=investigation();
        if(inv==null||key==null) return;
        if(visible) inv.show(key); else inv.hide(key);
        if(signalList!=null) signalList.refresh();
        updateSelectionLabel();
        rebuildSelectedSignalCards();
        boolean haveSeries=lastRendered.stream().anyMatch(s->s.key().equals(key));
        if(haveSeries || !visible){
            // Hide/show from existing session series — do not reset viewport or re-query.
            applyCanvasVisibility();
            updateEmptyStateForVisibility();
        }else{
            requestViewport();
        }
        persistInvestigationSoon();
    }

    private void applyCanvasVisibility(){
        GraphInvestigationState inv=investigation();
        if(graphCanvas==null) return;
        java.util.Set<SignalCatalogKey> hidden=inv==null?java.util.Set.of():inv.hiddenKeys();
        if(hidden.isEmpty() || lastRendered.isEmpty()){
            graphCanvas.setHiddenKeys(hidden);
            return;
        }
        java.util.LinkedHashSet<SignalCatalogKey> expanded=new java.util.LinkedHashSet<>(hidden);
        for(GraphRenderSeries series:lastRendered){
            for(SignalCatalogKey key:hidden){
                if(hidesPlottedSeries(key, series.key())) expanded.add(series.key());
            }
        }
        graphCanvas.setHiddenKeys(expanded);
    }

    /** A project-logical hide covers every file of that signal. A physical hide covers that file only. */
    private static boolean hidesPlottedSeries(SignalCatalogKey hidden, SignalCatalogKey plotted){
        if(hidden==null || plotted==null) return false;
        if(hidden.equals(plotted)) return true;
        String source=hidden.sourceFile()==null?"":hidden.sourceFile().trim();
        boolean logical=source.isEmpty()
                || com.stellar.platform.processing.catalog.ProjectMergedSignalStore.PROJECT_SCOPE_SOURCE.equalsIgnoreCase(source);
        if(!logical) return false;
        return hidden.signalName().equals(plotted.signalName())
                && hidden.sourceType()==plotted.sourceType()
                && hidden.messageName().equals(plotted.messageName())
                && hidden.canId()==plotted.canId()
                && hidden.channel()==plotted.channel();
    }

    private void updateEmptyStateForVisibility(){
        if(selectedSignalKeys.isEmpty()) return;
        if(selectedKeys().isEmpty()){
            if(statusLabel!=null) setGraphStatus("All selected signals are hidden. Show a signal to plot.");
            if(graphCanvas!=null) graphCanvas.setEmptyHint("All selected signals are hidden. Show a signal to plot.");
        }else if(statusLabel!=null && lastRendered!=null){
            long visibleTracks=lastTracks.stream()
                    .filter(track->selectedKeys().contains(track.logicalKey()))
                    .count();
            updateGraphHelpStatus(visibleTracks, 0);
        }
    }

    /**
     * Context-menu "Focus Signal": inspector/context focus only.
     * Does not hide other selected traces or rebuild membership/viewport.
     */
    private void focusSignal(SignalCatalogKey key){
        if(key==null) return;
        focusExplorerRow(key, true);
    }

    /**
     * REMOVE FROM ANALYSIS (chip × / uncheck). Membership + checkbox off.
     * Does NOT dispose catalog/store/samples — only analysis membership and render series.
     * Distinct from {@link #setSignalVisible} (Hide/Show / legend).
     */
    private void removeFromAnalysis(SignalCatalogKey key){
        if(key==null) return;
        java.util.ArrayList<SignalCatalogKey> order=new java.util.ArrayList<>(selectedSignalKeys);
        int removedIndex=order.indexOf(key);
        boolean wasFocused=key.equals(focusedExplorerKey);
        GraphInvestigationState inv=investigation();
        if(inv!=null && key.equals(inv.focusKey())) wasFocused=true;
        boolean wasHidden=inv!=null&&inv.isHidden(key);

        // Invalidate in-flight queries so a stale callback cannot re-apply empty/partial series
        // after membership has already changed (deselect → reselect race).
        generation++;
        cancelPendingGraphWork(true);

        SignalsScreenModel model=signalsModel();
        if(model!=null) model.removeFromWorkspace(key);
        int selectedBefore=selectedSignalKeys.size();
        selectedSignalKeys.remove(key);
        bumpSelectedKeysRevision("removeFromAnalysis", selectedBefore);
        if(inv!=null) inv.forgetTrace(key);
        failedTraces.remove(key);
        lastRendered=lastRendered.stream().filter(s->!s.key().equals(key)).toList();
        if(inv!=null){
            inv.setLiveViewportResults(lastRendered.size());
            inv.setLiveTraceModels(lastRendered.size());
        }
        LOG.log(System.Logger.Level.INFO, ()->"graph-membership-off key="+catalogKeyIdentity(key)
                +" wasHidden="+wasHidden
                +" selected="+selectedSignalKeys.size()
                +" viewport="+GraphSharedViewport.formatNanosRange(start,end)
                +" generation="+generation
                +" command="+membershipCommandCount);

        scheduleExplorerChromeRefresh();
        refreshMembershipPresentation();
        rebuildSelectedSignalCards();
        updateEmptyState();
        if(mapRoutePane!=null) mapRoutePane.setSelectedSignals(selectedKeys());

        if(wasFocused){
            if(selectedSignalKeys.isEmpty()){
                focusedExplorerKey=null;
                if(inv!=null) inv.clearFocus();
                clearInspectorEmpty();
            }else{
                int nextIndex=Math.min(Math.max(removedIndex,0), selectedSignalKeys.size()-1);
                java.util.ArrayList<SignalCatalogKey> remaining=new java.util.ArrayList<>(selectedSignalKeys);
                focusExplorerRow(remaining.get(nextIndex), true);
            }
        }

        if(selectedSignalKeys.isEmpty()){
            // Preserve controller viewport for a later reselect; drop only render composition.
            clearRenderedAnalysis();
            if(statusLabel!=null) setGraphStatus("No signals plotted yet.");
        }else{
            applyCanvasVisibility();
            if(graphCanvas!=null && !lastTracks.isEmpty()){
                graphCanvas.prepareSharedViewport(start,end);
                graphCanvas.setLogicalTracks(lastTracks);
                applyCanvasVisibility();
            }
            requestViewport();
        }
        persistInvestigationSoon();
    }

    private void clearInspectorEmpty(){
        if(detailsInspector==null) return;
        detailsInspector.getChildren().clear();
        Label empty=new Label("Select an active signal to inspect metadata.");
        empty.getStyleClass().add("muted");
        empty.setWrapText(true);
        detailsInspector.getChildren().add(empty);
    }

    private void retryTrace(SignalCatalogKey key){
        failedTraces.remove(key);
        requestViewport();
    }

    private ContextMenu activeSignalMenu(SignalCatalogEntry entry){
        ContextMenu menu=new ContextMenu();
        if(entry==null) return menu;
        SignalCatalogKey key=entry.key();
        GraphInvestigationState inv=investigation();
        boolean hidden=inv!=null&&inv.isHidden(key);
        MenuItem vis=new MenuItem(hidden?"Show":"Hide");
        vis.setOnAction(e->toggleHidden(key));
        MenuItem focus=new MenuItem("Focus Signal");
        focus.setOnAction(e->focusSignal(key));
        MenuItem details=new MenuItem("Show Details");
        details.setOnAction(e->showInspectorDetails(entry));
        MenuItem retry=new MenuItem("Retry");
        retry.setOnAction(e->retryTrace(key));
        retry.setDisable(!failedTraces.containsKey(key));
        MenuItem remove=new MenuItem("Remove from Analysis");
        remove.setOnAction(e->removeFromAnalysis(key));
        MenuItem up=new MenuItem("Move Up");
        up.setDisable(!canMove(entry, -1));
        up.setOnAction(e->{
            if(signalList!=null) signalList.getSelectionModel().select(entry);
            onMoveUp();
        });
        MenuItem down=new MenuItem("Move Down");
        down.setDisable(!canMove(entry, 1));
        down.setOnAction(e->{
            if(signalList!=null) signalList.getSelectionModel().select(entry);
            onMoveDown();
        });
        menu.getItems().addAll(vis,focus,details,retry,new SeparatorMenuItem(),up,down,remove);
        return menu;
    }

    private void showInspectorDetails(SignalCatalogEntry entry){
        SignalsScreenModel model=signalsModel();
        if(entry!=null){
            if(model!=null) model.setFocusedSignalKey(entry.key());
            focusedExplorerKey=entry.key();
        }
        refreshInvestigationInspector();
    }

    /**
     * Sets FOCUSED state for Inspector / row chrome. Never filters VISIBLE/SELECTED render keys.
     */
    private void focusExplorerRow(SignalCatalogKey key, boolean updateInspector){
        focusedExplorerKey=key;
        SignalsScreenModel model=signalsModel();
        if(model!=null) model.setFocusedSignalKey(key);
        GraphInvestigationState inv=investigation();
        // Mirror inspector focus into investigation for persistence/Inspector consumers only.
        // visibleKeys() deliberately ignores focusKey so multi-signal plots stay intact.
        if(inv!=null){
            if(key==null) inv.clearFocus();
            else inv.setFocus(key);
        }
        if(signalList==null||key==null) return;
        applyingSignalFilter=true;
        try{
            for(int i=0;i<signalList.getItems().size();i++){
                SignalCatalogEntry row=signalList.getItems().get(i);
                if(row!=null&&key.equals(row.key())){
                    signalList.getSelectionModel().clearAndSelect(i);
                    signalList.getFocusModel().focus(i);
                    signalList.scrollTo(i);
                    if(updateInspector) showInspectorDetails(row);
                    break;
                }
            }
        }finally{applyingSignalFilter=false;}
    }

    private void setAnalysisMembership(SignalCatalogEntry entry, boolean member){
        if(entry==null || applyingMembershipCommand) return;
        applyingMembershipCommand=true;
        membershipCommandCount++;
        try{
            SignalCatalogEntry canonical=canonicalMembershipEntry(entry);
            if(canonical==null){
                LOG.log(System.Logger.Level.WARNING, ()->"GRAPH-MEMBERSHIP-INVARIANT"
                        +" rejected unscoped key="+catalogKeyIdentity(entry.key())
                        +" catalog="+allSignals.size()
                        +" generation="+generation);
                scheduleExplorerChromeRefresh();
                return;
            }
            SignalCatalogKey key=canonical.key();
            boolean already=selectedSignalKeys.contains(key);
            LOG.log(System.Logger.Level.INFO, ()->"graph-membership-command requested="+member
                    +" already="+already
                    +" key="+catalogKeyIdentity(key)
                    +" selectedBefore="+selectedSignalKeys.size()
                    +" generation="+generation
                    +" command="+membershipCommandCount);
            if(member==already){
                if(member){
                    LOG.log(System.Logger.Level.INFO, ()->"GRAPH-MEMBERSHIP-INVARIANT"
                            +" idempotent add ignored key="+catalogKeyIdentity(key));
                }
                scheduleExplorerChromeRefresh();
                return;
            }
            int selectedBefore=selectedSignalKeys.size();
            SignalsScreenModel model=signalsModel();
            if(member){
                boolean firstSignal=selectedSignalKeys.isEmpty();
                long preservedStart=start, preservedEnd=end;
                if(model!=null) model.addPhysicalToWorkspace(key);
                // Keep the exact explorer identity. Do not replace the selected set from a
                // remapped workspace snapshot — that unchecks the clicked row after refresh.
                selectedSignalKeys.add(key);
                bumpSelectedKeysRevision("setAnalysisMembership.add", selectedBefore);
                GraphInvestigationState inv=investigation();
                // Reselect always restores VISIBLE=true. Stale hidden from a prior membership must
                // not survive checkbox OFF → ON (Hide while selected is a different path).
                if(inv!=null){
                    inv.show(key);
                    inv.colorIndex(key); // keep/assign stable analysis colour
                }
                LOG.log(System.Logger.Level.INFO, ()->"graph-membership-on key="+catalogKeyIdentity(key)
                        +" first="+firstSignal
                        +" selectedAfter="+selectedSignalKeys.size()
                        +" viewport="+GraphSharedViewport.formatNanosRange(preservedStart,preservedEnd)
                        +" generation="+generation
                        +" command="+membershipCommandCount);

                refreshMembershipPresentation();
                rebuildSelectedSignalCards();
                updateEmptyState();
                if(mapRoutePane!=null) mapRoutePane.setSelectedSignals(selectedKeys());
                refreshFullAnalysisRange();
                if(firstSignal){
                    // Reuse preserved viewport when it still intersects the newly selected signal;
                    // otherwise fit to that signal's full range (never leave an irrelevant stale window).
                    if(GraphSharedViewport.isUsable(preservedStart,preservedEnd,dataStart,dataEnd)){
                        commitViewport("RESELECT_REUSE_VIEWPORT",preservedStart,preservedEnd);
                        requestViewport();
                    }else if(!tryFitRange()){
                        if(statusLabel!=null) setGraphStatus("No timestamped samples available");
                    }
                }else if(!isViewportUsableForSelection()){
                    fitRange();
                }else{
                    requestViewport();
                }
                persistInvestigationSoon();
            }else{
                removeFromAnalysis(key);
            }
            scheduleExplorerChromeRefresh();
        }finally{
            applyingMembershipCommand=false;
        }
    }

    private void scheduleExplorerChromeRefresh(){
        if(signalList==null) return;
        Platform.runLater(()->{
            if(signalList!=null) signalList.refresh();
        });
    }

    /**
     * Scope-boundary reconciliation. Merged All Measurements membership is the logical
     * PROJECT key. A single measurement resolves that identity to its physical series.
     * Keys with no compatible row stay parked so a later scope can restore them.
     */
    private void reconcileMembershipToCurrentCatalog(String source, boolean scopeSwitched){
        SignalsScreenModel model=signalsModel();
        List<SignalCatalogKey> previousSelected=List.copyOf(selectedSignalKeys);
        List<SignalCatalogKey> workspace=model==null?List.of():model.workspaceKeys();
        List<SignalCatalogKey> preferred=!previousSelected.isEmpty()?previousSelected:workspace;
        List<SignalCatalogKey> remapped=reconcilePreferredMembership(preferred, model);
        if(scopeSwitched){
            logScopeTransition(source, preferred, remapped, workspace);
        }else{
            logGraphBindResolution(preferred, remapped);
        }
        long preferredCount=preferred.stream().filter(java.util.Objects::nonNull).distinct().count();
        if(allSignals.isEmpty() && preferredCount>0){
            LOG.log(System.Logger.Level.INFO, ()->"graph-membership-reconcile-wait"
                    +" source="+source
                    +" preferred="+preferredCount
                    +" catalog=0");
            replaceSelectedMembership(source, preferred.stream().filter(java.util.Objects::nonNull).distinct().toList());
            return;
        }
        if(remapped.size()!=preferredCount){
            LOG.log(System.Logger.Level.INFO, ()->"graph-membership-reconcile-drop"
                    +" source="+source
                    +" preferred="+preferred.size()
                    +" remapped="+remapped.size()
                    +" catalog="+allSignals.size()
                    +" projectWide="+(appService!=null&&appService.isProjectWideAnalysis()));
        }
        replaceSelectedMembership(source, remapped);
        if(model!=null){
            model.replacePhysicalWorkspace(remapped);
        }
    }

    /**
     * Current-catalog identity first. A merged store reconciles physical and logical keys.
     * A key with no compatible visible row stays parked so a later scope can restore it.
     */
    private List<SignalCatalogKey> reconcilePreferredMembership(
            List<SignalCatalogKey> preferred,
            SignalsScreenModel model
    ){
        java.util.LinkedHashSet<SignalCatalogKey> out=new java.util.LinkedHashSet<>();
        if(preferred==null){
            return List.of();
        }
        boolean merged=model!=null && model.catalogStore()
                instanceof com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
        for(SignalCatalogKey key:preferred){
            if(key==null) continue;
            var found=com.stellar.platform.desktop.presentation.MeasurementCatalogScope.reconcileVisibleScope(
                    key, allSignals, merged);
            if(found!=null){
                out.add(found.key());
            }else if(isRetainableSelection(key)){
                out.add(key);
                LOG.log(System.Logger.Level.INFO, ()->"GRAPH-SCOPE-PARK "+catalogKeyIdentity(key));
            }
        }
        return List.copyOf(out);
    }

    private static boolean isRetainableSelection(SignalCatalogKey key){
        if(key==null || key.signalName()==null || key.signalName().isBlank()) return false;
        String source=key.sourceFile()==null?"":key.sourceFile().trim();
        return !source.isEmpty();
    }

    private boolean inCurrentCatalog(SignalCatalogKey key){
        if(key==null) return false;
        for(SignalCatalogEntry entry:allSignals){
            if(entry!=null && key.equals(entry.key())) return true;
        }
        return false;
    }

    private void logScopeTransition(
            String source,
            List<SignalCatalogKey> preferred,
            List<SignalCatalogKey> remapped,
            List<SignalCatalogKey> workspace
    ){
        String scope=appService==null?"":appService.analysisScopeToken();
        LOG.log(System.Logger.Level.INFO, ()->"GRAPH-SCOPE-TRANSITION"
                +" source="+source
                +" scope="+scope
                +" generation="+generation
                +" preferred="+preferred.size()
                +" remapped="+remapped.size()
                +" workspace="+workspace.size()
                +" catalog="+allSignals.size()
                +" chips="+selectedEntries().size()
                +" tracks="+lastRendered.size()
                +" focus="+catalogKeyIdentity(focusedExplorerKey));
        for(SignalCatalogKey key:preferred){
            LOG.log(System.Logger.Level.INFO, ()->"GRAPH-SCOPE-TRANSITION-OLD "+catalogKeyIdentity(key));
        }
        for(SignalCatalogKey key:remapped){
            LOG.log(System.Logger.Level.INFO, ()->"GRAPH-SCOPE-TRANSITION-NEW "+catalogKeyIdentity(key));
        }
        for(SignalCatalogKey key:allSignals.stream().map(SignalCatalogEntry::key).toList()){
            boolean selected=remapped.contains(key);
            if(selected){
                LOG.log(System.Logger.Level.INFO, ()->"GRAPH-SCOPE-TRANSITION-CHECKED "+catalogKeyIdentity(key));
            }
        }
        java.util.LinkedHashSet<SignalCatalogKey> remappedSet=new java.util.LinkedHashSet<>(remapped);
        if(remappedSet.size()!=remapped.size()){
            LOG.log(System.Logger.Level.WARNING, ()->"GRAPH-MEMBERSHIP-INVARIANT duplicate remapped keys source="+source);
        }
        logGraphBindResolution(preferred, remapped);
    }

    private void replaceSelectedMembership(String source, List<SignalCatalogKey> next){
        int before=selectedSignalKeys.size();
        java.util.LinkedHashSet<SignalCatalogKey> unique=new java.util.LinkedHashSet<>();
        if(next!=null){
            int raw=0;
            for(SignalCatalogKey key:next){
                if(key==null) continue;
                raw++;
                unique.add(key);
            }
            final int supplied=raw;
            final int collapsed=unique.size();
            if(collapsed!=supplied){
                LOG.log(System.Logger.Level.WARNING, ()->"GRAPH-MEMBERSHIP-INVARIANT"
                        +" source="+source
                        +" duplicate add collapsed "+supplied+"→"+collapsed);
            }
        }
        selectedSignalKeys.clear();
        selectedSignalKeys.addAll(unique);
        bumpSelectedKeysRevision(source, before);
    }

    private void bumpSelectedKeysRevision(String source, int beforeCount){
        int after=selectedSignalKeys.size();
        int from=selectedKeysRevision;
        selectedKeysRevision++;
        if(after<beforeCount){
            LOG.log(System.Logger.Level.INFO, ()->"GRAPH-SIGNAL-STATE-REVERT"
                    +" revision="+from+"→"+selectedKeysRevision
                    +" source="+source
                    +" selected="+beforeCount+"→"+after);
        }
    }

    private void logExplorerPointer(javafx.scene.input.MouseEvent event){
        if(event==null) return;
        javafx.scene.Node picked=event.getPickResult()==null?null:event.getPickResult().getIntersectedNode();
        Object target=event.getTarget();
        lastExplorerPointerTarget=describePointerNode(picked!=null?picked:(target instanceof javafx.scene.Node n?n:null));
        SignalCatalogKey key=explorerKeyFromNode(picked!=null?picked:(target instanceof javafx.scene.Node n?n:null));
        CheckBox box=key==null?null:findExplorerMembershipCheck(key);
        LOG.log(System.Logger.Level.INFO, ()->"GRAPH-SIGNAL-POINTER"
                +" event="+event.getEventType()
                +" target="+describePointerNode(target instanceof javafx.scene.Node n?n:null)
                +" currentTarget="+describePointerNode(event.getSource() instanceof javafx.scene.Node n?n:null)
                +" pick="+describePointerNode(picked)
                +" consumed="+event.isConsumed()
                +" x="+String.format(java.util.Locale.ROOT,"%.1f",event.getX())
                +" y="+String.format(java.util.Locale.ROOT,"%.1f",event.getY())
                +" key="+(key==null?"null":catalogKeyIdentity(key))
                +" checkboxSelected="+(box==null?"n/a":box.isSelected())
                +" membership="+selectedSignalKeys.size());
    }

    private static String describePointerNode(javafx.scene.Node node){
        if(node==null) return "null";
        String id=node.getId();
        return node.getClass().getSimpleName()+(id==null||id.isBlank()?"":"#"+id);
    }

    private SignalCatalogKey explorerKeyFromNode(javafx.scene.Node node){
        javafx.scene.Node current=node;
        while(current!=null){
            if(current instanceof ExplorerCell cell && cell.getItem()!=null){
                return cell.getItem().key();
            }
            current=current.getParent();
        }
        return null;
    }

    private SignalCatalogEntry canonicalMembershipEntry(SignalCatalogEntry entry){
        if(entry==null || entry.key()==null) return null;
        for(SignalCatalogEntry candidate:allSignals){
            if(candidate!=null && entry.key().equals(candidate.key())){
                return candidate;
            }
        }
        SignalsScreenModel model=signalsModel();
        boolean merged=model!=null && model.catalogStore()
                instanceof com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
        return com.stellar.platform.desktop.presentation.MeasurementCatalogScope.reconcileVisibleScope(
                entry.key(), allSignals, merged);
    }

    private static String catalogKeyIdentity(SignalCatalogKey key){
        if(key==null) return "null";
        return key.signalName()+"|"+key.sourceType()+"|"+key.messageName()+"|"+key.canId()
                +"|"+key.channel()+"|"+key.sourceFile()+"|"+key.sourceDatabase();
    }

    /**
     * True when the current [start,end] can display samples for at least one selected signal.
     * Rejects empty/inverted ranges and sub-millisecond windows against multi-second series.
     */
    private boolean isViewportUsableForSelection(){
        refreshFullAnalysisRange();
        return GraphSharedViewport.isUsable(start,end,dataStart,dataEnd);
    }

    /**
     * Update selection chrome after checkbox membership changes without rebuilding the ListView
     * (except when the Selected filter is active — unchecked rows leave that filtered set).
     */
    private void refreshMembershipPresentation(){
        if(explorerFilter==ExplorerFilter.SELECTED){
            applySignalFilter();
        }else{
            updateSelectionLabel();
            updateEmptyState();
        }
    }

    private void handleExplorerSingleClick(SignalCatalogEntry entry){
        if(entry==null) return;
        focusExplorerRow(entry.key(), true);
    }

    private void handleExplorerDoubleClick(SignalCatalogEntry entry){
        if(entry==null) return;
        if(!selectedSignalKeys.contains(entry.key())){
            setAnalysisMembership(entry, true);
        }
        focusExplorerRow(entry.key(), true);
        scrollFocusedTrackIntoView(entry.key());
    }

    private void scrollFocusedTrackIntoView(SignalCatalogKey key){
        if(key==null||waveformScroll==null||!selectedSignalKeys.contains(key)) return;
        List<SignalCatalogKey> visible=selectedKeys();
        int index=visible.indexOf(key);
        if(index<0) return;
        double total=Math.max(1, visible.size());
        waveformScroll.setVvalue(index/(total-1==0?1:total-1));
    }

    Class<?> addSignalsPickerTypeForTest(){ return null; }
    void hideTraceForTest(SignalCatalogKey key){ setSignalVisible(key, false); }
    void showTraceForTest(SignalCatalogKey key){ setSignalVisible(key, true); }
    void removeTraceForTest(SignalCatalogKey key){ removeFromAnalysis(key); }
    void closeChipForTest(SignalCatalogKey key){ removeFromAnalysis(key); }
    void legendToggleForTest(SignalCatalogKey key){ toggleHidden(key); }
    boolean isSelectedForTest(SignalCatalogKey key){ return key!=null&&selectedSignalKeys.contains(key); }
    boolean isVisibleForTest(SignalCatalogKey key){
        GraphInvestigationState inv=investigation();
        return key!=null&&selectedSignalKeys.contains(key)&&(inv==null||!inv.isHidden(key));
    }
    MapRoutePane mapRoutePaneForTest(){ return mapRoutePane; }
    void selectMapRouteTabForTest(){ selectMapRouteTab(); }
    void enterFullScreenForTest(){ if(!focusGraph) onToggleFocusGraph(); }
    void exitFullScreenForTest(){ if(focusGraph) onToggleFocusGraph(); }
    boolean inspectorManagedForTest(){ return inspectorPanel!=null&&inspectorPanel.isManaged(); }
    boolean inspectorVisibleForTest(){ return inspectorPanel!=null&&inspectorPanel.isVisible(); }
    boolean inspectorRailManagedForTest(){ return inspectorRail!=null&&inspectorRail.isManaged(); }
    boolean signalPanelManagedForTest(){ return signalPanel!=null&&signalPanel.isManaged()
            && graphWorkspaceSplit!=null && graphWorkspaceSplit.getItems().contains(signalPanel); }
    boolean chipStripManagedForTest(){ return selectedSignalCards!=null&&selectedSignalCards.isManaged(); }
    boolean fileStripManagedForTest(){ return graphFileStrip!=null&&graphFileStrip.isManaged(); }
    Long cursorAForTest(){ return cursorA; }
    String cursorMeasurementIdForTest(){ return cursorMeasurementId==null?"":cursorMeasurementId; }
    long lastViewportFxQueueMsForTest(){ return lastViewportFxQueueMs; }
    String graphCacheStatsForTest(){
        var stats=graphQueryCache.stats();
        return "size="+stats.size()+" hits="+stats.hits()+" misses="+stats.misses()+" evictions="+stats.evictions();
    }
    void selectWaveformTabForTest(){
        if(analysisTabs!=null) analysisTabs.getSelectionModel().select(0);
    }
    void enableFileFocusForTest(){
        if(fileFocusToggle!=null && !fileFocusToggle.isSelected()) fileFocusToggle.setSelected(true);
        onToggleFileFocus();
    }
    void selectAnalysisTabForTest(int index){
        if(analysisTabs!=null) analysisTabs.getSelectionModel().select(index);
    }
    boolean engineeringTabDisabledForTest(){
        if(analysisTabs==null) return false;
        for(Tab tab:analysisTabs.getTabs()){
            if(tab!=null && "Engineering".equals(tab.getText())) return tab.isDisable();
        }
        return false;
    }
    void layoutEventActionsForTest(double width){
        com.stellar.platform.desktop.ui.graph.GraphEventActions.layout(
                width, eventActionRow, eventActionMore, eventActionButtons());
    }
    MenuButton eventActionMoreForTest(){ return eventActionMore; }
    void installPlottedSeriesForTest(java.util.List<GraphRenderSeries> series, long viewStart, long viewEnd){
        if(graphCanvas==null) return;
        graphCanvas.prepareSharedViewport(viewStart, viewEnd);
        graphCanvas.setSeries(series);
        graphCanvas.flushDeferredPaintForTest();
    }
    void flushPointMarkersForTest(){
        if(graphCanvas==null) return;
        graphCanvas.flushDeferredPaintForTest();
        graphCanvas.flushPointMarkersForTest();
    }
    java.util.List<javafx.scene.Node> pointMarkerNodesForTest(){
        return graphCanvas==null?java.util.List.of():graphCanvas.pointMarkerNodesForTest();
    }
    EngineeringAnalysisPane engineeringPaneForTest(){ return engineeringPane; }
    String analyticsModelForTest(){
        return analyticsPane==null ? "" : analyticsPane.modelForTest();
    }
    String fileFocusLabelForTest(){
        return fileFocusLabel==null || fileFocusLabel.getText()==null ? "" : fileFocusLabel.getText();
    }
    int fileSlotCountForTest(){ return fileSlots.size(); }
    /** Moves File Focus onto the recording whose label or id contains the fragment. */
    boolean focusRecordingNamedForTest(String fragment){
        if(fragment==null || fragment.isBlank() || !fileFocusActive || fileSlots.isEmpty()) return false;
        for(int i=0;i<fileSlots.size();i++){
            FileSlot slot=fileSlots.get(i);
            boolean label=slot.label()!=null && slot.label().contains(fragment);
            boolean id=slot.measurementId()!=null && slot.measurementId().contains(fragment);
            if(!label && !id) continue;
            if(i!=focusedFileIndex) focusFileAt(i);
            return true;
        }
        return false;
    }
    void disableFileFocusForTest(){
        boolean reload;
        synchronized(fileFocusLock){
            reload=(pendingRestoredFocusId!=null && !pendingRestoredFocusId.isBlank())
                    || fileFocusRequested || fileFocusActive;
            pendingRestoredFocusId="";
            fileFocusRequested=false;
            fileFocusActive=false;
        }
        if(fileFocusToggle!=null && fileFocusToggle.isSelected()){
            fileFocusToggle.setSelected(false);
            onToggleFileFocus();
            return;
        }
        if(reload) requestViewport();
    }
    String focusedMeasurementIdForTest(){ return focusedMeasurementId(); }
    void selectCursorAForTest(long timestampNanos){ setCursor("A", timestampNanos); }
    boolean categoricalScanPendingForTest(SignalCatalogKey key){ return key!=null && categoricalPending.contains(key); }
    boolean categoricalCachedForTest(SignalCatalogKey key){ return key!=null && categoricalByKey.containsKey(key); }
    java.util.List<String> eventRowsForTest(){
        return eventsList==null?java.util.List.of():java.util.List.copyOf(eventsList.getItems());
    }
    void reloadEventsForTest(){ restoreEngineeringBookmarks(); }
    Dialog<ButtonType> addEventDialogForTest(){ return buildAddEventDialog(); }
    private com.stellar.platform.desktop.presentation.GraphEventGps.Evidence gpsEvidence(
            com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata bookmark, boolean routeChecked
    ){
        var defined=EngineerEventDocuments.decode(bookmark);
        String file=defined==null||defined.sourceFile()==null?"":defined.sourceFile();
        var view=mapRoutePane==null
                ? com.stellar.platform.desktop.presentation.GraphEventGps.RouteView.none()
                : mapRoutePane.routeForEvent(bookmark.sourceId(), file);
        return com.stellar.platform.desktop.presentation.GraphEventGps.revalidate(bookmark, view, routeChecked);
    }

    private static String gpsMarkerLabel(
            String title, com.stellar.platform.desktop.presentation.GraphEventGps.Evidence evidence
    ){
        String how=com.stellar.platform.desktop.presentation.GraphEventGps.KIND_WINDOW.equals(evidence.kind())
                ? "first GNSS sample in the window"
                : "nearest GNSS sample";
        return title+"\n"+com.stellar.platform.desktop.ui.graph.map.MapTimeFormat.withMillis(evidence.sampleTimestampNanos())
                +" · "+how;
    }

    private com.stellar.platform.desktop.presentation.GraphEventGps.RouteView gpsRoute(String bookmarkId){
        if(mapRoutePane==null||appService==null){
            return com.stellar.platform.desktop.presentation.GraphEventGps.RouteView.none();
        }
        var bookmark=appService.findEngineeringBookmark(bookmarkId).orElse(null);
        if(bookmark==null) return com.stellar.platform.desktop.presentation.GraphEventGps.RouteView.none();
        var defined=EngineerEventDocuments.decode(bookmark);
        String file=defined==null||defined.sourceFile()==null?"":defined.sourceFile();
        return mapRoutePane.routeForEvent(bookmark.sourceId(), file);
    }

    private long pendingGpsFocus=Long.MIN_VALUE;

    private void onGpsRouteReady(){
        restoreEngineeringBookmarks();
        if(pendingGpsFocus!=Long.MIN_VALUE && mapRoutePane!=null){
            long sample=pendingGpsFocus;
            pendingGpsFocus=Long.MIN_VALUE;
            mapRoutePane.focusExactSample(sample);
        }
    }

    private void showGpsSample(long sampleNanos){
        if(mapRoutePane!=null && mapRoutePane.focusExactSample(sampleNanos)){
            showMapTabWithoutReload();
            return;
        }
        pendingGpsFocus=sampleNanos;
        if(analysisTabs!=null) analysisTabs.getSelectionModel().select(MAP_ROUTE_TAB_INDEX);
    }

    private void showMapTabWithoutReload(){
        if(analysisTabs==null || analysisTabs.getSelectionModel().getSelectedIndex()==MAP_ROUTE_TAB_INDEX) return;
        restoringPresentation=true;
        try{
            analysisTabs.getSelectionModel().select(MAP_ROUTE_TAB_INDEX);
        }finally{
            restoringPresentation=false;
        }
        applyAnalysisToolbar(MAP_ROUTE_TAB_INDEX);
    }

    private Dialog<ButtonType> gpsDialog(String bookmarkId){
        GraphEventGpsDialog gps=new GraphEventGpsDialog(
                appService, bookmarkId, ()->gpsRoute(bookmarkId),
                ()->mapRoutePane!=null && mapRoutePane.routeResolved(), this::showGpsSample);
        return gps.build();
    }

    Dialog<ButtonType> linkSnapshotDialogForTest(String bookmarkId){
        GraphSnapshotLinkDialog link=new GraphSnapshotLinkDialog(appService, bookmarkId, this::commitCaptureSnapshotForTest);
        Dialog<ButtonType> dialog=link.build();
        if(dialog!=null) dialog.getDialogPane().getProperties().put("stellar.snapshot.link", link);
        return dialog;
    }
    private static GraphSnapshotLinkDialog linkDialog(Dialog<?> dialog){
        if(dialog==null) return null;
        Object value=dialog.getDialogPane().getProperties().get("stellar.snapshot.link");
        return value instanceof GraphSnapshotLinkDialog link ? link : null;
    }
    void selectLinkSnapshotForTest(Dialog<?> dialog, String snapshotId){
        GraphSnapshotLinkDialog link=linkDialog(dialog);
        if(link!=null) link.selectForTest(snapshotId);
    }
    boolean linkPreviewReadyForTest(Dialog<?> dialog){
        GraphSnapshotLinkDialog link=linkDialog(dialog);
        return link!=null && link.previewReadyForTest();
    }
    String linkDetailForTest(Dialog<?> dialog){
        GraphSnapshotLinkDialog link=linkDialog(dialog);
        return link==null ? "" : link.detailForTest();
    }
    boolean linkDisabledForTest(Dialog<?> dialog){
        GraphSnapshotLinkDialog link=linkDialog(dialog);
        return link==null || link.linkDisabledForTest();
    }
    String commitLinkSnapshotForTest(Dialog<?> dialog){
        GraphSnapshotLinkDialog link=linkDialog(dialog);
        return link==null ? "The event was not found." : link.commitLinkForTest();
    }
    String commitUnlinkSnapshotForTest(Dialog<?> dialog){
        GraphSnapshotLinkDialog link=linkDialog(dialog);
        return link==null ? "The event was not found." : link.commitUnlinkForTest();
    }
    String commitExcludeSnapshotForTest(Dialog<?> dialog){
        GraphSnapshotLinkDialog link=linkDialog(dialog);
        return link==null ? "" : link.commitExcludeForTest();
    }
    String commitIncludeSnapshotForTest(Dialog<?> dialog){
        GraphSnapshotLinkDialog link=linkDialog(dialog);
        return link==null ? "" : link.commitIncludeForTest();
    }
    Dialog<ButtonType> gpsDialogForTest(String bookmarkId){
        return gpsDialog(bookmarkId);
    }
    private static GraphEventGpsDialog gpsDialog(Dialog<?> dialog){
        if(dialog==null) return null;
        Object value=dialog.getDialogPane().getProperties().get("stellar.gps.link");
        return value instanceof GraphEventGpsDialog gps ? gps : null;
    }
    String gpsDetailForTest(Dialog<?> dialog){
        GraphEventGpsDialog gps=gpsDialog(dialog);
        return gps==null ? "" : gps.detailForTest();
    }
    boolean gpsLinkDisabledForTest(Dialog<?> dialog){
        GraphEventGpsDialog gps=gpsDialog(dialog);
        return gps==null || gps.linkDisabledForTest();
    }
    void requestGpsForTest(Dialog<?> dialog){
        GraphEventGpsDialog gps=gpsDialog(dialog);
        if(gps!=null) gps.requestForTest();
    }
    String commitGpsLinkForTest(Dialog<?> dialog){
        GraphEventGpsDialog gps=gpsDialog(dialog);
        return gps==null ? "The event was not found." : gps.commitLinkForTest();
    }
    String commitGpsRemoveForTest(Dialog<?> dialog){
        GraphEventGpsDialog gps=gpsDialog(dialog);
        return gps==null ? "The event was not found." : gps.commitRemoveForTest();
    }
    boolean showGpsOnMapForTest(Dialog<?> dialog){
        GraphEventGpsDialog gps=gpsDialog(dialog);
        return gps!=null && gps.commitShowForTest();
    }
    int mapSelectedIndexForTest(){
        return mapRoutePane==null ? -1 : mapRoutePane.selectedIndexForTest();
    }
    void installGpsRouteForTest(String measurementId, String fileName, com.stellar.platform.processing.map.MapRoute route){
        if(mapRoutePane!=null) mapRoutePane.installRouteForTest(measurementId, fileName, route);
    }
    void installProjectRoutesForTest(com.stellar.platform.processing.map.ProjectRoute route){
        if(mapRoutePane!=null) mapRoutePane.installProjectRoutesForTest(route);
    }
    String mapEventLabelForTest(int position){
        return mapRoutePane==null ? "" : mapRoutePane.eventMarkerLabelForTest(position);
    }
    String commitAddEventForTest(Dialog<?> dialog){
        String saved=commitAddEvent(dialog);
        if(!saved.isBlank()){
            selectedEventId=saved;
            finishGraphEvent(saved);
        }
        return saved;
    }
    void pinEventSignalsForTest(java.util.List<SignalCatalogKey> keys){
        pinnedEventSignals=keys==null?null:java.util.List.copyOf(keys);
    }
    void setEventCatalogForTest(java.util.List<SignalCatalogKey> keys){
        eventCatalogOverride=keys==null?null:java.util.List.copyOf(keys);
    }
    void selectCursorBForTest(long timestampNanos){ setCursor("B", timestampNanos); }
    void zoomToSelectedEventForTest(){
        var bookmark=selectedManualEvent();
        if(bookmark!=null) zoomToBookmark(bookmark);
    }
    boolean overlaySelectedForTest(String bookmarkId){
        if(graphCanvas==null || bookmarkId==null) return false;
        var overlays=graphCanvas.analysisOverlaysForTest();
        for(int i=0;i<displayedBookmarks.size() && i<overlays.size();i++){
            if(bookmarkId.equals(displayedBookmarks.get(i).bookmarkId())) return overlays.get(i).selected();
        }
        return false;
    }
    boolean editEventEnabledForTest(){ return editEventButton!=null && !editEventButton.isDisabled(); }
    boolean deleteEventEnabledForTest(){ return deleteEventButton!=null && !deleteEventButton.isDisabled(); }
    void selectEventIndexForTest(int index){
        if(eventsList!=null) eventsList.getSelectionModel().select(index);
    }
    String selectedEventIdForTest(){
        var selected=selectedManualEvent();
        return selected==null?null:selected.bookmarkId();
    }
    Dialog<ButtonType> editDialogForTest(int index){
        selectEventIndexForTest(index);
        var bookmark=selectedManualEvent();
        if(bookmark==null) return null;
        return buildEventDialog(
                "Edit Event",
                InvestigationEventCodec.titleOf(bookmark.observation()),
                bookmark.eventType(),
                InvestigationEventCodec.noteOf(bookmark.observation()),
                bookmark.timestampNanos(),
                true);
    }
    String[] eventFormValuesForTest(Dialog<ButtonType> dialog){
        EventForm form=readEventForm(dialog);
        String time="";
        if(dialog!=null && dialog.getDialogPane().getContent() instanceof VBox body){
            for(Node node:body.getChildren()){
                if(node instanceof Label label && label.isDisabled()) time=label.getText();
            }
        }
        return new String[]{form.title, form.category, form.note, time};
    }
    boolean commitEventEditForTest(String bookmarkId, boolean accepted, String title, String category, String note){
        if(!accepted) return false;
        return commitEventEdit(bookmarkId, title, category, note);
    }
    Alert deleteConfirmationForTest(int index){
        selectEventIndexForTest(index);
        var bookmark=selectedManualEvent();
        return bookmark==null?null:deleteConfirmation(bookmark);
    }
    boolean commitDeleteForTest(String bookmarkId){ return commitDelete(bookmarkId); }
    java.util.List<String> overlayLabelsForTest(){
        if(graphCanvas==null) return java.util.List.of();
        return graphCanvas.analysisOverlaysForTest().stream()
                .map(AutomotiveSignalCanvas.AnalysisOverlay::label).toList();
    }
    java.util.List<Long> overlayTimesForTest(){
        if(graphCanvas==null) return java.util.List.of();
        return graphCanvas.analysisOverlaysForTest().stream()
                .map(AutomotiveSignalCanvas.AnalysisOverlay::startNanos).toList();
    }
    boolean overlapChooserShowingForTest() {
        return overlapChooser.showing();
    }

    java.util.List<String> overlapChoiceLabelsForTest() {
        return overlapChooser.labels();
    }

    void chooseOverlapForTest(String measurementId) {
        overlapChooser.choose(measurementId);
    }

    String inspectorTextForTest(){
        if(detailsInspector==null) return "";
        StringBuilder text=new StringBuilder();
        for(javafx.scene.Node node:detailsInspector.getChildren()){
            if(node instanceof Label label){
                if(text.length()>0) text.append('\n');
                text.append(label.getText());
            }
        }
        return text.toString();
    }
    String sourceFileLabelForTest(){
        if(measurementCombo==null) return "";
        AnalysisScope selected=measurementCombo.getValue();
        if(selected!=null) return selected.comboLabel();
        return appService==null?"":appService.analysisScope().comboLabel();
    }
    String mapRouteSourceFileForTest(){ return mapRouteSourceFile; }

    private String mapRouteIdentity(){
        return mapRouteIdentity(appService);
    }

    private static String mapRouteIdentity(DesktopAppService service){
        if(service==null) return "";
        if(service.isProjectWideAnalysis()) return service.analysisScopeToken();
        return service.activeMeasurementSourceId();
    }
    List<SignalCatalogEntry> graphCatalogForTest(){ return allSignals; }
    void selectMeasurementForTest(String fileId){
        if(appService==null || fileId==null) return;
        appService.selectMeasurement(fileId);
        refresh();
    }
    void selectAnalysisScopeForTest(AnalysisScope scope){
        if(appService==null || scope==null) return;
        appService.selectAnalysisScope(scope);
        refresh();
    }
    List<String> measurementComboLabelsForTest(){
        if(measurementCombo==null) return List.of();
        return measurementCombo.getItems().stream().map(AnalysisScope::comboLabel).toList();
    }
    void pressEscapeForTest(){
        if(focusGraph) onToggleFocusGraph();
    }
    void setInspectorOpenForTest(boolean open){ setInspectorVisible(open, false); }
    void toggleInspectorForTest(){ onToggleInspector(); }
    boolean inspectorOpenForTest(){ return isInspectorOpen(); }
    boolean focusGraphForTest(){ return focusGraph; }
    boolean inspectorToggledDuringFullScreenForTest(){ return inspectorToggledDuringFullScreen; }
    double graphCanvasPanelWidthForTest(){
        if(graphWorkspaceSplit==null||graphWorkspaceSplit.getItems().size()<2) return 0;
        Node pane=graphWorkspaceSplit.getItems().get(1);
        return pane instanceof Region r ? r.getWidth() : pane.getLayoutBounds().getWidth();
    }
    int analysisTabIndexForTest(){
        return analysisTabs==null? -1 : analysisTabs.getSelectionModel().getSelectedIndex();
    }
    double canvasHeightForTest(){ return graphCanvas==null?0:graphCanvas.getHeight(); }
    double canvasWidthForTest(){ return graphCanvas==null?0:graphCanvas.getWidth(); }
    int canvasPaintCountForTest(){ return graphCanvas==null?0:graphCanvas.paintCountForTest(); }
    String paintDiagnosticsForTest(){ return graphCanvas==null?"":graphCanvas.paintDiagnosticsForTest(); }
    /** The idle budget counts layout and data paints. Crosshair motion stays available in the product. */
    void holdWaveformPointerForTest(boolean hold){ if(graphCanvas!=null) graphCanvas.setMouseTransparent(hold); }
    double[] innerCanvasSizeForTest(){
        return graphCanvas==null?new double[]{0,0,0}:graphCanvas.innerCanvasSizeForTest();
    }
    double[] plotBoundsForTest(){
        return graphCanvas==null?new double[]{0,0,0,0,0,0}:graphCanvas.plotBoundsForTest();
    }
    double waveformViewportHeightForTest(){
        if(waveformScroll==null) return 0;
        javafx.geometry.Bounds bounds=waveformScroll.getViewportBounds();
        return bounds==null?0:bounds.getHeight();
    }
    double analyticsPaneHeightForTest(){ return analyticsPane==null?0:analyticsPane.getHeight(); }
    int analyticsLayoutCountForTest(){ return analyticsPane==null?0:analyticsPane.layoutCountForTest(); }
    double engineeringPaneHeightForTest(){ return engineeringPane==null?0:engineeringPane.getHeight(); }
    int analysisTabCountForTest(){
        return analysisTabs==null? 0 : analysisTabs.getTabs().size();
    }
    String analysisTabTextForTest(int index){
        if(analysisTabs==null || index<0 || index>=analysisTabs.getTabs().size()) return "";
        return analysisTabs.getTabs().get(index).getText();
    }
    int inspectorTabIndexForTest(){
        return inspectorTabs==null? -1 : inspectorTabs.getSelectionModel().getSelectedIndex();
    }
    void selectInspectorTabForTest(int index){
        if(inspectorTabs!=null) inspectorTabs.getSelectionModel().select(index);
    }
    void resetViewForTest(){ onResetView(); }
    void addWorkspaceKeysPreservingViewForTest(List<SignalCatalogKey> keys){
        long s=start,e=end; Long a=cursorA,b=cursorB;
        SignalsScreenModel model=signalsModel();
        if(model!=null) model.addToWorkspace(keys);
        selectedSignalKeys.clear();
        if(model!=null) selectedSignalKeys.addAll(model.workspaceKeys());
        start=s; end=e; cursorA=a; cursorB=b;
        GraphInvestigationState inv=investigation();
        if(inv!=null && e>s) inv.setViewport(s,e);
        if(inv!=null){ inv.setCursorA(a); inv.setCursorB(b); }
    }
    void bindMembershipForTest(DesktopAppService service){
        appService=service;
        if(service!=null){
            boundSessionGeneration=service.graphInvestigation().sessionGeneration();
            lastAnalysisMeasurementToken=service.analysisScopeToken();
            allSignals=service.engineeringCatalog();
            installSignalCategories();
            selectedSignalKeys.clear();
            reconcileMembershipToCurrentCatalog("bindMembership", false);
            mapRouteSourceFile=mapRouteIdentity(service);
            explorerFilter=ExplorerFilter.ALL;
            configureExplorerFilters();
            applySignalFilter();
        }
    }

    void applyActiveMeasurementCatalogForTest(){
        if(appService==null) return;
        String token=appService.analysisScopeToken();
        boolean switched=!lastAnalysisMeasurementToken.isBlank() && !token.equals(lastAnalysisMeasurementToken);
        lastAnalysisMeasurementToken=token;
        if(switched){
            generation++;
            explorerFilter=ExplorerFilter.ALL;
            if(mapRoutePane!=null) mapRoutePane.resetForFileChange();
            cursorA=null;
            cursorB=null;
        }
        allSignals=appService.engineeringCatalog();
        installSignalCategories();
        reconcileMembershipToCurrentCatalog("applyActiveMeasurementCatalog", switched);
        mapRouteSourceFile=mapRouteIdentity();
        configureExplorerFilters();
        applySignalFilter();
    }
    String explorerFilterForTest(){ return explorerFilter.name(); }
    /** Simulates Graphs → Signals → Graphs without recreating the controller/session. */
    void simulateNavigateAwayAndBackForTest(){
        onHide();
        refresh();
    }
    void setLayoutModeForTest(boolean stacked){
        onLayoutModeChanged(stacked);
        // Keep toggle chrome consistent when FXML controls are present in UI tests.
        applyRestoredLayout(investigation());
    }
    String layoutModeForTest(){
        GraphInvestigationState inv=investigation();
        return inv==null?GraphInvestigationState.STACKED:inv.layoutMode();
    }
    SignalCatalogKey investigationFocusForTest(){
        GraphInvestigationState inv=investigation();
        return inv==null?null:inv.focusKey();
    }
    void focusSignalForTest(SignalCatalogKey key){ focusSignal(key); }
    void requestViewportForTest(){ requestViewport(); }
    AutomotiveSignalCanvas canvasForTest(){ return graphCanvas; }
    double waveformScrollValueForTest(){ return waveformScroll==null?0:waveformScroll.getVvalue(); }
    Boolean waveformViewportCachedForTest(){
        javafx.scene.Node viewport=waveformViewport();
        return viewport==null?null:viewport.isCache();
    }
    private void releaseWaveformViewportCache(){
        javafx.scene.Node viewport=waveformViewport();
        if(viewport!=null && viewport.isCache()) viewport.setCache(false);
    }
    private javafx.scene.Node waveformViewport(){
        return waveformScroll==null?null:waveformScroll.lookup(".viewport");
    }
    javafx.scene.Node waveformScrollForTest(){ return waveformScroll; }
    void applyGraphHelpForTest(int tracks){ updateGraphHelpStatus(tracks, 0); }
    int graphHelpApplyCountForTest(){ return graphHelpApplies; }
    String graphStatusForTest(){ return statusLabel==null?"":statusLabel.getText(); }
    String timelineGroupForTest(){
        if(unknownOrderDomain) return "UNKNOWN_ORDER";
        if(sharedDisplayDomain) return "VERIFIED_UTC";
        if(fileFocusActive) return "FILE_FOCUS";
        return "STORED";
    }
    boolean fileFocusActiveForTest(){ return fileFocusActive; }
    /** Drops a restored single-file view so an overview assertion starts from Unknown Order. */
    void leaveFileFocusForTest(){
        if(fileFocusToggle!=null) fileFocusToggle.setSelected(false);
        pendingRestoredFocusId="";
        fileFocusRequested=false;
        fileFocusActive=false;
        publishTimelineContext();
        requestViewport();
    }
    boolean previousFileVisibleForTest(){ return previousFileButton!=null && previousFileButton.isVisible(); }
    String timelineGroupLabelForTest(){ return timelineGroupLabel==null?"":timelineGroupLabel.getText(); }
    String rangeTextForTest(){ return rangeLabel==null?"":rangeLabel.getText(); }
    int logicalTrackCountForTest(){ return lastTracks.size(); }
    int segmentCountForTest(int track){
        return track<0||track>=lastTracks.size()?0:lastTracks.get(track).segments().size();
    }
    /** Segments whose current viewport is one bucket per stored sample. */
    int fullResolutionSegmentCountForTest(){
        int count=0;
        for(GraphLogicalTrack track:lastTracks){
            for(var segment:track.segments()){
                if(segment.series()!=null && segment.series().fullResolution()) count++;
            }
        }
        return count;
    }
    AutomotiveSignalCanvas graphCanvasForTest(){ return graphCanvas; }
    String liveCursorForTest(){ return liveCursorLabel==null?"":liveCursorLabel.getText(); }
    boolean tryFitRangeForTest(){ return tryFitRange(); }
    void zoomInForTest(){ onZoomIn(); }
    void fitForTest(){ onFit(); }

    /** Reissues the current viewport so a repeated view can be timed against the LOD cache. */
    void reloadViewportForTest(){ requestViewport(); }
    void scrollWaveformForTest(double vvalue){
        if(waveformScroll!=null) waveformScroll.setVvalue(Math.max(0, Math.min(1, vvalue)));
    }
    void zoomOutForTest(){ onZoomOut(); }
    void panLeftForTest(){ onPanLeft(); }
    void panRightForTest(){ onPanRight(); }
    List<SignalCatalogKey> plotKeysForTest(){ return plotKeys(); }
    void setViewportForTest(long s,long e){
        commitViewport("TEST",s,e);
    }
    long viewportStartForTest(){ return start; }
    long viewportEndForTest(){ return end; }
    Long cursorBForTest(){ return cursorB; }
    void setCursorsForTest(Long a,Long b){ cursorA=a; cursorB=b; GraphInvestigationState inv=investigation(); if(inv!=null){ inv.setCursorA(a); inv.setCursorB(b);} }
    List<SignalCatalogKey> visibleKeysForTest(){ return selectedKeys(); }
    List<String> cursorLabelsForTest(){
        return selectedKeys().stream().map(this::displayName).toList();
    }
    int sessionGenerationForTest(){ return investigation()==null?boundSessionGeneration:investigation().sessionGeneration(); }
    int liveTraceModelsForTest(){ return investigation()==null?lastRendered.size():investigation().liveTraceModels(); }
    int liveViewportResultsForTest(){ return investigation()==null?lastRendered.size():investigation().liveViewportResults(); }
    void bumpGenerationForTest(){ generation++; }
    String displayNameForTest(SignalCatalogKey key){ return displayName(key); }
    void applyMembershipFromWorkspaceForTest(){
        SignalsScreenModel model=signalsModel();
        if(model==null) return;
        selectedSignalKeys.clear();
        selectedSignalKeys.addAll(model.workspaceKeys());
        applySignalFilter();
    }
    List<SignalCatalogEntry> explorerRowsForTest(){ return explorerRows; }
    void selectAllFilteredForTest(){ onSelectAllFiltered(); }
    void deselectAllFilteredForTest(){ onDeselectAllFiltered(); }
    boolean collapseButtonInHeaderForTest(){
        return signalPanelHeader!=null && collapseSignalPanelButton!=null
                && signalPanelHeader.getChildren().contains(collapseSignalPanelButton)
                && signalPanelTitle!=null && "Signals to Plot".equals(signalPanelTitle.getText());
    }
    java.util.List<String> explorerFilterLabelsForTest(){
        java.util.ArrayList<String> labels=new java.util.ArrayList<>();
        if(filterAllButton!=null && filterAllButton.isManaged()) labels.add(filterAllButton.getText());
        if(filterRecommendedButton!=null && filterRecommendedButton.isManaged()) labels.add(filterRecommendedButton.getText());
        if(filterSelectedButton!=null && filterSelectedButton.isManaged()) labels.add(filterSelectedButton.getText());
        if(filterDbcButton!=null && filterDbcButton.isManaged()) labels.add(filterDbcButton.getText());
        if(filterNativeButton!=null && filterNativeButton.isManaged()) labels.add(filterNativeButton.getText());
        return labels;
    }
    boolean bulkActionsReadableForTest(){
        return menuLabels(selectionActionsButton).containsAll(java.util.List.of("Select All", "Deselect All", "Clear All"))
                && menuLabels(visibilityActionsButton).containsAll(java.util.List.of("Show All", "Hide All"))
                && menuLabels(orderActionsButton).containsAll(java.util.List.of("Move Up", "Move Down"))
                && filterAllButton!=null
                && filterAllButton.getWidth()+0.5>=filterAllButton.prefWidth(-1)
                && "Select".equals(selectionActionsButton.getText())
                && "Show".equals(visibilityActionsButton.getText())
                && selectionActionsButton.getWidth()+0.5>=selectionActionsButton.prefWidth(-1)
                && visibilityActionsButton.getWidth()+0.5>=visibilityActionsButton.prefWidth(-1);
    }
    private static java.util.List<String> menuLabels(MenuButton menu){
        if(menu==null) return java.util.List.of();
        java.util.ArrayList<String> labels=new java.util.ArrayList<>();
        for(MenuItem item: menu.getItems()){
            if(item.getText()!=null) labels.add(item.getText());
        }
        return labels;
    }
    void selectExplorerRowForTest(int index){
        if(signalList==null || index<0 || index>=signalList.getItems().size()) return;
        signalList.getSelectionModel().select(index);
        refreshOrderActions();
    }
    void fireExplorerMenuForTest(String menu, String item){
        MenuButton host=switch(menu==null?"":menu){
            case "visibility" -> visibilityActionsButton;
            case "order" -> orderActionsButton;
            default -> selectionActionsButton;
        };
        if(host==null) return;
        for(MenuItem entry: host.getItems()){
            if(item.equals(entry.getText())){
                entry.fire();
                return;
            }
        }
    }
    boolean orderActionEnabledForTest(String label){
        MenuItem item="Move Down".equals(label)?moveDownItem:moveUpItem;
        return item!=null && !item.isDisable();
    }
    void searchExplorerForTest(String query){
        explorerSearchQuery=query==null?"":query;
        if(signalSearch!=null) signalSearch.setText(explorerSearchQuery);
        applySignalFilter();
    }
    void setExplorerFilterForTest(String filter){
        setExplorerFilter(switch(filter==null?"":filter.toLowerCase(java.util.Locale.ROOT)){
            case "selected" -> ExplorerFilter.SELECTED;
            case "dbc" -> ExplorerFilter.DBC;
            case "native" -> ExplorerFilter.NATIVE;
            case "recommended" -> ExplorerFilter.RECOMMENDED;
            case "favorites" -> ExplorerFilter.FAVORITES;
            default -> ExplorerFilter.ALL;
        });
    }
    /** Legacy saved Recommended/Native selections must not stay active behind a hidden chip. */
    void restoreExplorerFilterForTest(String saved){
        explorerFilter=switch(saved==null?"":saved.toLowerCase(java.util.Locale.ROOT)){
            case "selected" -> ExplorerFilter.SELECTED;
            case "dbc" -> ExplorerFilter.DBC;
            case "native" -> ExplorerFilter.NATIVE;
            case "recommended" -> ExplorerFilter.RECOMMENDED;
            case "favorites" -> ExplorerFilter.FAVORITES;
            default -> ExplorerFilter.ALL;
        };
        configureExplorerFilters();
        applySignalFilter();
    }
    void toggleMembershipForTest(SignalCatalogKey key, boolean member){
        SignalCatalogEntry entry=allSignals.stream().filter(e->e.key().equals(key)).findFirst().orElse(null);
        setAnalysisMembership(entry, member);
    }
    int membershipCommandCountForTest(){ return membershipCommandCount; }
    void resetMembershipCommandCountForTest(){ membershipCommandCount=0; }
    List<SignalCatalogKey> selectedMembershipForTest(){ return List.copyOf(selectedSignalKeys); }
    boolean explorerCheckboxSelectedForTest(SignalCatalogKey key){
        CheckBox box=findExplorerMembershipCheck(key);
        if(box!=null) return box.isSelected();
        return isSelectedForTest(key);
    }
    List<SignalCatalogKey> selectedChipKeysForTest(){
        return selectedEntries().stream().map(SignalCatalogEntry::key).toList();
    }
    List<SignalCatalogKey> renderedTrackKeysForTest(){
        return lastRendered.stream().map(GraphRenderSeries::key).toList();
    }
    int renderedBucketCountForTest(){
        int count=0;
        for(GraphRenderSeries series:lastRendered){
            if(series!=null) count+=series.buckets().size();
        }
        return count;
    }
    long renderedSourceSampleCountForTest(){
        long count=0;
        for(GraphRenderSeries series:lastRendered){
            if(series!=null) count+=series.sourceSampleCount();
        }
        return count;
    }
    int membershipCountForKeyForTest(SignalCatalogKey key){
        if(key==null) return 0;
        int n=0;
        for(SignalCatalogKey selected:selectedSignalKeys){
            if(key.equals(selected)) n++;
        }
        return n;
    }
    int checkedExplorerRowCountForTest(){
        return (int) explorerRows.stream().filter(e->e!=null&&selectedSignalKeys.contains(e.key())).count();
    }
    javafx.scene.control.ListView<SignalCatalogEntry> signalListForTest(){ return signalList; }
    void fireExplorerCheckboxForTest(SignalCatalogKey key){
        CheckBox box=findExplorerMembershipCheck(key);
        if(box!=null){
            box.fire();
            return;
        }
        SignalCatalogEntry entry=allSignals.stream().filter(e->e.key().equals(key)).findFirst().orElse(null);
        setAnalysisMembership(entry, !selectedSignalKeys.contains(key));
    }
    void fireExplorerCheckboxMouseGestureForTest(SignalCatalogKey key){
        if(signalList!=null && key!=null){
            for(int i=0;i<signalList.getItems().size();i++){
                SignalCatalogEntry row=signalList.getItems().get(i);
                if(row!=null && key.equals(row.key())){
                    signalList.scrollTo(i);
                    signalList.applyCss();
                    signalList.layout();
                    break;
                }
            }
        }
        CheckBox box=findExplorerMembershipCheck(key);
        if(box==null){
            fireExplorerCheckboxForTest(key);
            return;
        }
        box.applyCss();
        box.layout();
        javafx.scene.input.MouseEvent press=explorerCheckboxMouse(box, javafx.scene.input.MouseEvent.MOUSE_PRESSED, true);
        javafx.scene.input.MouseEvent release=explorerCheckboxMouse(box, javafx.scene.input.MouseEvent.MOUSE_RELEASED, false);
        javafx.scene.input.MouseEvent clicked=explorerCheckboxMouse(box, javafx.scene.input.MouseEvent.MOUSE_CLICKED, false);
        box.fireEvent(press);
        box.fireEvent(release);
        box.fireEvent(clicked);
    }
    private static javafx.scene.input.MouseEvent explorerCheckboxMouse(
            CheckBox box, javafx.event.EventType<javafx.scene.input.MouseEvent> type, boolean primaryDown){
        return new javafx.scene.input.MouseEvent(
                box, box, type,
                6, 6, 6, 6,
                javafx.scene.input.MouseButton.PRIMARY, 1,
                false, false, false, false, primaryDown, false, false, false, false, false,
                new javafx.scene.input.PickResult(box, 6, 6));
    }
    void clickWaveformAtXForTest(double x){
        if(graphCanvas==null) return;
        if(graphCanvas.getWidth()<200){
            graphCanvas.resize(800,400);
        }
        graphCanvas.applyCss();
        graphCanvas.layout();
        graphCanvas.clickPrimaryAtXForTest(x);
    }
    long waveformTimestampAtXForTest(double x){
        if(graphCanvas==null) return 0L;
        if(graphCanvas.getWidth()<200){
            graphCanvas.resize(800,400);
            graphCanvas.layout();
        }
        return graphCanvas.timestampAtForTest(x);
    }
    String lastExplorerPointerTargetForTest(){ return lastExplorerPointerTarget; }
    int checkboxActionCountForTest(){ return checkboxActionCount; }
    void resetCheckboxActionCountForTest(){ checkboxActionCount=0; }
    int selectedKeysRevisionForTest(){ return selectedKeysRevision; }
    void simulateExplorerRowClickOnCheckboxForTest(SignalCatalogKey key){
        CheckBox box=findExplorerMembershipCheck(key);
        if(box==null || signalList==null) return;
        javafx.scene.Node target=box.getChildrenUnmodifiable().isEmpty()?box:box.getChildrenUnmodifiable().getFirst();
        javafx.scene.input.MouseEvent clicked=new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                4,4,4,4,
                javafx.scene.input.MouseButton.PRIMARY,1,
                false,false,false,false,true,false,false,false,false,false,
                null);
        javafx.event.Event.fireEvent(target, clicked);
    }
    private CheckBox findExplorerMembershipCheck(SignalCatalogKey key){
        if(signalList==null || key==null) return null;
        for(javafx.scene.Node node:signalList.lookupAll(".graph-explorer-check")){
            if(!(node instanceof CheckBox box)) continue;
            javafx.scene.Node parent=box.getParent();
            while(parent!=null && !(parent instanceof ExplorerCell)){
                parent=parent.getParent();
            }
            if(parent instanceof ExplorerCell cell && cell.getItem()!=null && key.equals(cell.getItem().key())){
                return box;
            }
        }
        return null;
    }
    void singleClickExplorerForTest(SignalCatalogKey key){
        SignalCatalogEntry entry=allSignals.stream().filter(e->e.key().equals(key)).findFirst().orElse(null);
        handleExplorerSingleClick(entry);
    }
    void doubleClickExplorerForTest(SignalCatalogKey key){
        SignalCatalogEntry entry=allSignals.stream().filter(e->e.key().equals(key)).findFirst().orElse(null);
        handleExplorerDoubleClick(entry);
    }
    SignalCatalogKey focusedExplorerKeyForTest(){ return focusedExplorerKey; }
    /** Drives the same inspector focus as selecting the signal in the graph explorer. */
    void focusSignalByNameForTest(String name){
        if(name==null || name.isBlank()) return;
        SignalsScreenModel model=signalsModel();
        com.stellar.platform.processing.catalog.SignalSeriesStore store=model==null?null:model.catalogStore();
        SignalCatalogEntry fallback=null;
        for(SignalCatalogEntry entry:allSignals){
            if(entry==null || entry.signalName()==null || !entry.signalName().contains(name)) continue;
            if(fallback==null) fallback=entry;
            if(store!=null && store.retainedSampleCount(entry.key())>0){
                focusExplorerRow(entry.key(), true);
                return;
            }
        }
        if(fallback!=null) focusExplorerRow(fallback.key(), true);
    }
    String sourceBadgeForTest(SignalCatalogEntry entry){
        return entry==null?"":SignalBrowserFilter.sourceBadge(entry.sourceType());
    }
    String sourceTooltipForTest(SignalCatalogEntry entry){
        return entry==null?"":"Source: "+entry.sourceType().name();
    }
    String nameTooltipForTest(SignalCatalogEntry entry){
        return entry==null?"":entry.signalName();
    }
    boolean addSignalsPopupRequiredForTest(){ return false; }

    private final class ExplorerCell extends ListCell<SignalCatalogEntry> {
        private final CheckBox membership=new CheckBox();
        private final Region swatch=new Region();
        private final Label name=new Label();
        private final Label badge=new Label();
        private final Label meta=new Label();
        private final Button visibility=new Button();
        private final Label count=new Label();
        private final Label chevron=new Label();
        private final HBox header=new HBox(6);
        private final HBox row=new HBox(6);
        private final VBox body=new VBox(0);
        private final VBox cellBox=new VBox(0,header,body);
        private final Tooltip nameTip=new Tooltip();
        private final Tooltip metaTip=new Tooltip();
        private final Tooltip badgeTip=new Tooltip();
        private final Tooltip cellTip=new Tooltip();
        private boolean syncing;
        private SignalCatalogKey boundKey;
        private boolean memberShown;
        private boolean hiddenShown;
        private boolean graphicInstalled;
        private boolean rowCollapsed;
        private String shownBadgeClass="";
        private String shownSwatch="";
        private String shownGroup="";

        ExplorerCell(){
            membership.getStyleClass().add("graph-explorer-check");
            swatch.getStyleClass().add("graph-explorer-swatch");
            swatch.setMinSize(8,8);
            swatch.setPrefSize(8,8);
            swatch.setMaxSize(8,8);
            name.getStyleClass().add("graph-explorer-name");
            name.setTooltip(nameTip);
            meta.setTooltip(metaTip);
            badge.setTooltip(badgeTip);
            setTooltip(cellTip);
            name.setMinWidth(0);
            name.setPrefWidth(0);
            name.setMaxWidth(Double.MAX_VALUE);
            name.setTextOverrun(OverrunStyle.ELLIPSIS);
            badge.getStyleClass().add("source-badge");
            badge.setMinWidth(Region.USE_PREF_SIZE);
            badge.setMaxWidth(Region.USE_PREF_SIZE);
            meta.getStyleClass().add("graph-explorer-meta");
            meta.setMinWidth(0);
            meta.setPrefWidth(0);
            meta.setMaxWidth(Double.MAX_VALUE);
            meta.setTextOverrun(OverrunStyle.ELLIPSIS);
            visibility.getStyleClass().addAll("secondary-button","compact-button","graph-explorer-visibility");
            visibility.setMinWidth(Region.USE_PREF_SIZE);
            visibility.setMaxWidth(Region.USE_PREF_SIZE);
            VBox text=new VBox(0, name, meta);
            text.getStyleClass().add("graph-explorer-text");
            text.setMinWidth(0);
            text.setPrefWidth(0);
            text.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(text, Priority.ALWAYS);
            header.setAlignment(Pos.CENTER_LEFT);
            header.getStyleClass().add("graph-category-header-row");
            Label spacer=new Label(); spacer.setMaxWidth(Double.MAX_VALUE); HBox.setHgrow(spacer,Priority.ALWAYS);
            count.getStyleClass().add("graph-category-count");
            chevron.getStyleClass().add("graph-category-chevron");
            Label title=new Label();
            title.getStyleClass().add("graph-category-title");
            header.getChildren().addAll(title,count,spacer,chevron);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("graph-browser-signal-row");
            row.getChildren().addAll(membership,swatch,text,badge,visibility);
            body.getChildren().add(row);
            header.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED,ev->{
                if(ev.getButton()!=javafx.scene.input.MouseButton.PRIMARY||getItem()==null)return;
                String group=categoryOf(getItem());
                if(collapsedSignalGroups.contains(group))collapsedSignalGroups.remove(group);else collapsedSignalGroups.add(group);
                signalList.refresh(); ev.consume();
            });
            membership.setOnAction(ev->{
                if(syncing||applyingMembershipCommand||getItem()==null) return;
                ev.consume();
                checkboxActionCount++;
                SignalCatalogEntry item=getItem();
                boolean requested=membership.isSelected();
                int before=selectedSignalKeys.size();
                int visibleBefore=selectedKeys().size();
                int revisionBefore=selectedKeysRevision;
                LOG.log(System.Logger.Level.INFO, ()->"GRAPH-SIGNAL-CLICK"
                        +" key="+catalogKeyIdentity(item.key())
                        +" eventTarget="+describePointerNode(ev.getTarget() instanceof javafx.scene.Node n?n:membership)
                        +" checkboxAction=true"
                        +" beforeSelected="+before
                        +" requested="+requested
                        +" generation="+generation);
                setAnalysisMembership(item, requested);
                LOG.log(System.Logger.Level.INFO, ()->"GRAPH-SIGNAL-CLICK"
                        +" key="+catalogKeyIdentity(item.key())
                        +" checkboxAction=true"
                        +" beforeSelected="+before
                        +" afterSelected="+selectedSignalKeys.size()
                        +" workspaceRevision="+revisionBefore+"→"+selectedKeysRevision
                        +" visible="+visibleBefore+"→"+selectedKeys().size()
                        +" focus="+catalogKeyIdentity(focusedExplorerKey)
                        +" eventConsumed=true");
            });
            // Do not consume PRESSED/RELEASED on the CheckBox itself — that runs before
            // CheckBoxSkin/ButtonBehavior and prevents onAction from firing.
            visibility.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, javafx.event.Event::consume);
            row.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED,ev->{
                if(ev.getButton()!=javafx.scene.input.MouseButton.PRIMARY||isEmpty()||getItem()==null)return;
                if(ownedBy(ev.getTarget(), membership)||ownedBy(ev.getTarget(), visibility)){
                    ev.consume();
                    return;
                }
                if(ev.getClickCount()>=2) handleExplorerDoubleClick(getItem());
                else handleExplorerSingleClick(getItem());
                ev.consume();
            });
            visibility.setOnAction(ev->{
                if(getItem()!=null) toggleHidden(getItem().key());
            });
        }

        @Override protected void updateItem(SignalCatalogEntry e,boolean empty){
            super.updateItem(e,empty);
            if(empty||e==null){
                boundKey=null;
                memberShown=false;
                hiddenShown=false;
                graphicInstalled=false;
                rowCollapsed=false;
                shownGroup="";
                shownBadgeClass="";
                shownSwatch="";
                syncing=true;
                membership.setSelected(false);
                syncing=false;
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            String group=categoryOf(e);
            boolean first=getIndex()==0||!categoryOf(signalList.getItems().get(getIndex()-1)).equals(group);
            boolean searching=explorerSearchQuery!=null&&!explorerSearchQuery.isBlank();
            boolean collapsed=!searching&&collapsedSignalGroups.contains(group);
            if(!group.equals(shownGroup)){
                Label title=(Label)header.getChildren().getFirst();
                title.setText(group.toUpperCase(java.util.Locale.ROOT));
                Integer grouped=visibleCategoryCounts.get(group);
                count.setText("("+(grouped==null?0:grouped)+")");
                shownGroup=group;
            }
            String chevronText=collapsed?"⌄":"⌃";
            if(!chevronText.equals(chevron.getText())) chevron.setText(chevronText);
            if(header.isVisible()!=first){
                header.setVisible(first);
                header.setManaged(first);
            }
            boolean showBody=!collapsed;
            if(body.isVisible()!=showBody){
                body.setVisible(showBody);
                body.setManaged(showBody);
            }
            boolean hideRow=collapsed&&!first;
            if(hideRow!=rowCollapsed){
                setPrefHeight(hideRow?0:Region.USE_COMPUTED_SIZE);
                setMinHeight(hideRow?0:Region.USE_COMPUTED_SIZE);
                setMaxHeight(hideRow?0:Region.USE_COMPUTED_SIZE);
                rowCollapsed=hideRow;
            }
            boolean member=selectedSignalKeys.contains(e.key());
            GraphInvestigationState inv=investigation();
            boolean hidden=inv!=null&&inv.isHidden(e.key());
            syncing=true;
            if(membership.isSelected()!=member){
                membership.setSelected(member);
            }
            syncing=false;
            boolean menuStale=boundKey==null||!boundKey.equals(e.key())||member!=memberShown||hidden!=hiddenShown;
            boundKey=e.key();
            memberShown=member;
            hiddenShown=hidden;
            name.setText(e.signalName());
            String provenance=explorerTooltip(e);
            nameTip.setText(provenance);
            metaTip.setText(provenance);
            cellTip.setText(provenance);
            String badgeText=SignalBrowserFilter.sourceBadge(e.sourceType());
            if(!badgeText.equals(badge.getText())) badge.setText(badgeText);
            String badgeClass=SignalBrowserFilter.sourceStyleClass(e.sourceType());
            if(!badgeClass.equals(shownBadgeClass)){
                badge.getStyleClass().setAll("source-badge", badgeClass);
                shownBadgeClass=badgeClass;
            }
            String sourceTip="Source: "+e.sourceType().name();
            if(!sourceTip.equals(badgeTip.getText())) badgeTip.setText(sourceTip);
            String metaLine=metadataLine(e);
            if(!metaLine.equals(meta.getText())) meta.setText(metaLine);
            String swatchStyle="";
            if(member){
                int color=inv==null?0:inv.colorIndex(e.key());
                swatchStyle="-fx-background-color: "+GraphSeriesPalette.cssAt(color)+";";
            }
            if(!swatchStyle.equals(shownSwatch)){
                swatch.setStyle(swatchStyle);
                shownSwatch=swatchStyle;
            }
            double opacity=member&&hidden?0.45:1.0;
            if(name.getOpacity()!=opacity) name.setOpacity(opacity);
            String visibilityText=hidden?"Show":"Hide";
            if(!visibilityText.equals(visibility.getText())) visibility.setText(visibilityText);
            if(visibility.isVisible()!=member){
                visibility.setVisible(member);
                visibility.setManaged(member);
            }
            boolean focused=e.key().equals(focusedExplorerKey);
            row.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"),focused);
            if(menuStale) setContextMenu(member?activeSignalMenu(e):catalogSignalMenu(e));
            if(!graphicInstalled){
                setGraphic(cellBox);
                setTooltip(cellTip);
                graphicInstalled=true;
            }
        }
    }

    private static boolean ownedBy(Object target, Node owner){
        if(!(target instanceof Node node) || owner==null) return false;
        Node current=node;
        while(current!=null){
            if(current==owner) return true;
            current=current.getParent();
        }
        return false;
    }

    private ContextMenu catalogSignalMenu(SignalCatalogEntry entry){
        ContextMenu menu=new ContextMenu();
        if(entry==null) return menu;
        MenuItem add=new MenuItem("Add to Analysis");
        add.setOnAction(e->setAnalysisMembership(entry,true));
        MenuItem details=new MenuItem("Show Details");
        details.setOnAction(e->handleExplorerSingleClick(entry));
        menu.getItems().addAll(add,details);
        return menu;
    }

    private String explorerTooltip(SignalCatalogEntry e){
        if(e==null) return "";
        String file=e.sourceFile();
        if((file==null||file.isBlank()) && e.key()!=null) file=e.key().sourceFile();
        return e.signalName()
                +(e.unit()==null||e.unit().isBlank()?"":" ["+e.unit()+"]")
                +"\n"+metadataLine(e)
                +"\nSource: "+e.sourceType().name()
                +(file==null||file.isBlank()?"":"\nFile: "+file)
                +(e.messageName()==null||e.messageName().isBlank()?"":"\nMessage: "+e.messageName());
    }

    private String metadataLine(SignalCatalogEntry e){
        java.util.ArrayList<String> parts=new java.util.ArrayList<>();
        String owner=e.sourceFile();
        if(owner==null||owner.isBlank()){
            owner=e.key()==null?"":e.key().sourceFile();
        }
        if(owner!=null&&!owner.isBlank()){
            parts.add(owner);
        }
        if(e.messageName()!=null&&!e.messageName().isBlank()) parts.add(e.messageName());
        if(e.unit()!=null&&!e.unit().isBlank()) parts.add(e.unit());
        parts.add(e.sampleCount()+" samples");
        return String.join(" · ", parts);
    }
}
