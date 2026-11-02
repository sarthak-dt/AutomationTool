package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.desktop.presentation.GpsCatalogWindow;
import com.stellar.platform.desktop.presentation.MeasurementCatalogScope;
import com.stellar.platform.desktop.ui.control.GpsSignalMappingModal;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.GraphSeriesQuery;
import com.stellar.platform.processing.map.GpsDetectionResult;
import com.stellar.platform.processing.map.GpsMappingDraft;
import com.stellar.platform.processing.map.GpsMappingPolicy;
import com.stellar.platform.processing.map.GpsMappingScope;
import com.stellar.platform.processing.map.GpsSemanticResolver;
import com.stellar.platform.processing.map.GpsSignalDiscoveryService;
import com.stellar.platform.processing.map.GpsSignalMapping;
import com.stellar.platform.processing.map.GpsSignalRole;
import com.stellar.platform.processing.map.GpsSignalRoleResolver;
import com.stellar.platform.processing.map.MapRoute;
import com.stellar.platform.processing.map.MeasurementGpsSync;
import com.stellar.platform.processing.map.MapRouteCacheKey;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.processing.map.MapRouteService;
import com.stellar.platform.processing.map.ProjectRoute;
import com.stellar.platform.processing.map.ProjectRouteAssembler;
import com.stellar.platform.processing.map.time.RecordingClockReader;
import com.stellar.platform.processing.map.time.RouteChronology;
import com.stellar.platform.processing.map.RouteSegment;
import com.stellar.platform.processing.map.RouteMovement;
import com.stellar.platform.processing.drive.DriveMotionMetrics;
import com.stellar.platform.processing.drive.DriveSegmentAnalysisService;
import com.stellar.platform.processing.drive.DriveSegmentSelection;
import com.stellar.platform.processing.drive.DriveSegmentSummary;
import com.stellar.platform.processing.drive.DriveSignalIssue;
import com.stellar.platform.processing.drive.speed.SpeedSourceMapping;
import com.stellar.platform.processing.drive.speed.SpeedSourceResolution;
import com.stellar.platform.processing.map.SignalSnapshotService;
import com.stellar.platform.processing.map.SignalSnapshotValue;
import com.stellar.platform.processing.time.SharedTimeSelectionModel;
import com.stellar.platform.processing.time.TimeSelection;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Map Route tab of the Signal Plot workspace.
 *
 * <p>Everything expensive is deferred: the pane is a bare placeholder until {@link #activate} runs
 * for the first time, and route preparation, GPS role resolution, range metrics and signal
 * snapshots all execute on a private background thread. The JavaFX thread only receives finished,
 * immutable results.
 *
 * <p>The pane owns no time filter of its own. It publishes into and observes the shared
 * {@link SharedTimeSelectionModel}, which is what keeps Waveforms, Analytics, Engineering and the
 * map on one selection instead of four competing ones.
 */
public final class MapRoutePane extends BorderPane {

    private static final Logger LOG = Logger.getLogger(MapRoutePane.class.getName());
    private static final long SNAPSHOT_TOLERANCE_NANOS = 2_000_000_000L;
    private static final int MAX_SNAPSHOT_SIGNALS = 12;
    /**
     * Temporary compositing probe. Empty = production. {@code none} keeps only the Leaflet
     * WebView; {@code route} puts RouteCanvas back over it. Set {@code stellar.map.overlayProbe}.
     */
    private static final String OVERLAY_PROBE = System.getProperty("stellar.map.overlayProbe", "");
    /** Smallest map stage that still shows a usable Leaflet/route canvas. */
    public static final double MAP_MIN_USABLE_HEIGHT = 320;
    private static final double ROUTE_DATA_COLLAPSED_MAX = 88;
    private static final double ROUTE_DATA_EXPANDED_MAX = 200;

    private SharedTimeSelectionModel sharedSelection;
    private Runnable sharedSelectionUnsubscribe;

    private ExecutorService worker;
    private MapRouteService routeService;
    private SignalSnapshotService snapshotService;
    private GraphSeriesQuery query;

    private boolean uiBuilt;
    private boolean disposed;
    private String activeSignature = "";
    private long generation;
    private long staleDiscards;

    private MapRoute route = MapRoute.emptyRoute();
    private ProjectRoute projectRoute = ProjectRoute.empty();
    private RouteSegment selectedSegment;
    private Map<GpsSignalRole, SignalCatalogKey> gpsRoles = Map.of();
    private GpsDetectionResult detection = GpsDetectionResult.none("");
    private final GpsSignalDiscoveryService gpsDiscovery = new GpsSignalDiscoveryService();
    private List<GpsSignalMapping> projectMappings = List.of();
    private List<GpsSignalMapping> defaultMappings = List.of();
    /**
     * Roles the current route was built from, cached so a tab re-activation does not re-run the
     * mapping policy. Cleared whenever Configuration changes the persisted mapping.
     */
    private final EnumMap<GpsSignalRole, SignalCatalogKey> resolvedOverrides = new EnumMap<>(GpsSignalRole.class);
    private boolean resolvedUserConfirmed;
    private boolean forceRefresh;
    private boolean persistedMissing;
    private String persistedMissingMessage = "";
    private GpsMappingPolicy.Origin mappingOrigin = GpsMappingPolicy.Origin.UNRESOLVED;
    private String schemaFingerprint = "";
    /** Measurement this route resolves its GPS mapping for; see {@link GpsMappingScope}. */
    private String mappingSourceId = "";
    private List<SignalCatalogEntry> mappingCatalog = List.of();
    private SpeedSourceMapping persistedSpeed;
    private String droppedRolesMessage = "";
    private Runnable configureMappingHandler = () -> { };
    private RouteMappingPersistence mappingPersistence = RouteMappingPersistence.NONE;
    private GpsSignalMappingModal mappingEditor;
    private StackPane mappingSheet;
    private boolean mappingSaveInFlight;
    private String lastConfiguredSourceId = "";
    private int mappingEditorGeneration;
    private final MapProviderResolver mapProviderResolver;
    private MapLocalAssetServer mapAssetServer;
    private MapProviderSelection activeBasemap = MapProviderSelection.neutral("Initializing");
    private List<SignalCatalogEntry> catalogSnapshot = List.of();
    private List<SignalCatalogKey> selectedSignals = List.of();
    private MapRouteRequest activeRequest;

    private MapSelectionMode mode = MapSelectionMode.ALL_ROUTE;
    private int selectedIndex = -1;
    private int indexA = -1;
    private int indexB = -1;
    private final DriveSegmentSelection segment = new DriveSegmentSelection();
    private final DriveSegmentAnalysisService segmentAnalysis = new DriveSegmentAnalysisService();
    private DriveSegmentSummary segmentSummary = DriveSegmentSummary.empty();
    private DriveSegmentAnalysisService.DriveSegmentAnalysisRequest lastAnalysisRequest;
    private long lastAnalyzedStart = Long.MIN_VALUE;
    private long lastAnalyzedEnd = Long.MIN_VALUE;
    private boolean awaitingDifferentB;
    private boolean autoSync = true;
    private String activeMeasurementName = "";
    private String gpsUnavailableMessage;
    private Runnable measurementCursorApplied;
    private long[] eventTimestamps = new long[0];
    private String[] eventMarkerLabels = new String[0];
    private String[] eventSourceIds = new String[0];
    private String[] eventSourceFiles = new String[0];
    private boolean routeResolved;
    private String routeLatitudeChannel = "";
    private String routeLongitudeChannel = "";
    private Runnable routeReady;
    private boolean notifyingRouteReady;

    // --- UI (created lazily) ---
    private RouteCanvas mapCanvas;
    private LeafletBasemapView basemapView;
    private RouteTimelineBar timeline;
    private MiniTrackCanvas miniTracks;
    private StackPane mapStack;
    private VBox emptyState;
    private Label emptyHeadline;
    private Label emptyDetail;
    private Label emptyDiagnostic;
    private Button emptyAction;
    private VBox loadingState;
    private Label loadingLabel;
    private Label mappingMeasurementValue;
    private Label mappingLatitudeValue;
    private Label mappingLongitudeValue;
    private Label mappingAltitudeValue;
    private Label mappingStatusValue;
    private Button mappingEditButton;
    /** Mirrors the installed {@link MapBackgroundProvider} so a redraw cannot swap it every pulse. */
    private boolean routeCanvasTransparent = true;
    private VBox detailBody;
    private Label detailTitle;
    private Label detailBadge;
    private HBox summaryTiles;
    private Label filterStateLabel;
    private Label filterDetailLabel;
    private Button clearFilterButton;
    private Button copyButton;
    private CheckBox autoSyncToggle;
    private Label statusLabel;
    private Map<String, Path> measurementPaths = Map.of();
    private String chronologyStatus = "";
    private boolean routeIsolated;
    private Label filteredDataTitle;
    private Label filteredDataBadge;
    private VBox filteredDataSection;
    private Button routeDetailsToggle;
    private boolean routeDetailsExpanded;
    private SplitPane mapWorkspaceSplit;
    private ScrollPane railScroll;
    private final PauseTransition layoutResync = new PauseTransition(Duration.millis(80));
    private VBox segmentBody;
    private Label segmentTitle;
    private Label segmentBadge;
    private Label segmentHeadline;
    private VBox segmentIssueList;
    private Hyperlink segmentIssuesLink;
    private Button setAButton;
    private Button setBButton;
    private ToggleButton singlePointButton;
    private ToggleButton rangeButton;
    private ToggleButton allRouteButton;
    /** Restore requested before the route finished preparing; replayed once coordinates exist. */
    private MapRouteState pendingRestore;
    private Runnable stateChangedHandler = () -> { };

    public MapRoutePane() {
        this(new SharedTimeSelectionModel(), new MapProviderResolver());
    }

    public MapRoutePane(SharedTimeSelectionModel sharedSelection) {
        this(sharedSelection, new MapProviderResolver());
    }

    MapRoutePane(SharedTimeSelectionModel sharedSelection, MapProviderResolver mapProviderResolver) {
        this.sharedSelection = sharedSelection == null ? new SharedTimeSelectionModel() : sharedSelection;
        this.mapProviderResolver = mapProviderResolver == null ? new MapProviderResolver() : mapProviderResolver;
        getStyleClass().add("map-route-pane");
        setMinSize(0, 0);
    }

    public SharedTimeSelectionModel sharedSelection() {
        return sharedSelection;
    }

    /**
     * Rebinds this pane to the application-wide investigation cursor. Layout and route geometry
     * are unchanged.
     */
    public void useSharedSelection(SharedTimeSelectionModel model) {
        if (model == null || model == sharedSelection) {
            return;
        }
        if (sharedSelectionUnsubscribe != null) {
            sharedSelectionUnsubscribe.run();
            sharedSelectionUnsubscribe = null;
        }
        sharedSelection = model;
        if (uiBuilt) {
            sharedSelectionUnsubscribe = sharedSelection.addListener(this::onSharedSelectionChanged);
        }
    }

    public void setActiveMeasurementName(String fileName) {
        activeMeasurementName = fileName == null ? "" : fileName;
        if (uiBuilt) {
            refreshMappingCard();
        }
    }

    public void setMeasurementCursorApplied(Runnable listener) {
        measurementCursorApplied = listener;
    }

    public void setEventTimestamps(long[] timestamps) {
        setEventMarkers(timestamps, new String[timestamps == null ? 0 : timestamps.length], new String[0], new String[0]);
    }

    /** Pins only timestamps that are stored GNSS samples. A nearby sample is not substituted. */
    public void setEventMarkers(long[] timestamps, String[] labels) {
        setEventMarkers(timestamps, labels, new String[0], new String[0]);
    }

    public void setEventMarkers(long[] timestamps, String[] labels, String[] sourceIds, String[] sourceFiles) {
        eventTimestamps = timestamps == null ? new long[0] : timestamps.clone();
        eventMarkerLabels = labels == null ? new String[eventTimestamps.length] : labels.clone();
        eventSourceIds = sourceIds == null ? new String[0] : sourceIds.clone();
        eventSourceFiles = sourceFiles == null ? new String[0] : sourceFiles.clone();
        refreshEventMarkers();
    }

    public boolean focusExactSample(long timestampNanos) {
        if (projectRoute != null) {
            for (com.stellar.platform.processing.map.RouteSegment segment : projectRoute.gpsSegments()) {
                int found = com.stellar.platform.desktop.presentation.GraphEventGps.exactIndex(
                        segment.route().model(), timestampNanos);
                if (found < 0) {
                    continue;
                }
                if (projectRoute.gpsSegments().size() > 1) {
                    showOnly(segment);
                }
                selectIndex(found, false);
                return true;
            }
        }
        int index = com.stellar.platform.desktop.presentation.GraphEventGps.exactIndex(
                route == null ? MapRouteModel.empty() : route.model(), timestampNanos);
        if (index < 0) {
            return false;
        }
        selectIndex(index, false);
        return true;
    }

    public com.stellar.platform.desktop.presentation.GraphEventGps.RouteView routeForEvent(String sourceId, String sourceFile) {
        String eventId = sourceId == null ? "" : sourceId.trim();
        String eventFile = sourceFile == null ? "" : sourceFile;
        if (projectRoute != null) {
            for (com.stellar.platform.processing.map.RouteSegment segment : projectRoute.gpsSegments()) {
                if (segmentMatches(segment, eventId, eventFile)) {
                    return routeView(segment.route().model(), segment.measurementId(), segment.displayName(), "");
                }
            }
        }
        boolean eventBlank = eventId.isBlank()
                && com.stellar.platform.desktop.presentation.GraphEventGps.fileName(eventFile).isBlank();
        if (eventBlank && projectRoute != null && projectRoute.gpsSegments().size() > 1 && !routeIsolated) {
            return new com.stellar.platform.desktop.presentation.GraphEventGps.RouteView(
                    MapRouteModel.empty(), "", "",
                    "More than one route is on the map and this event has no measurement. "
                            + "A location was not chosen from another file.");
        }
        if (eventBlank && route != null) {
            return routeView(
                    route.model(),
                    mappingSourceId == null ? "" : mappingSourceId,
                    activeMeasurementName == null ? "" : activeMeasurementName,
                    "");
        }
        if (!eventBlank && routeResolved) {
            return new com.stellar.platform.desktop.presentation.GraphEventGps.RouteView(
                    MapRouteModel.empty(), "", "",
                    "This event and the GPS route are not the same measurement. "
                            + "Their clocks were not compared. No location was invented.");
        }
        return com.stellar.platform.desktop.presentation.GraphEventGps.RouteView.none();
    }

    public boolean routeResolved() {
        return routeResolved;
    }

    public void setRouteReadyHandler(Runnable handler) {
        routeReady = handler;
    }

    public void installRouteForTest(String measurementId, String fileName, MapRoute prepared) {
        ensureUiBuilt();
        mappingSourceId = measurementId == null ? "" : measurementId;
        activeMeasurementName = fileName == null ? "" : fileName;
        routeLatitudeChannel = "LATITUDE";
        routeLongitudeChannel = "LONGITUDE";
        applyPreparedRoute(prepared == null ? MapRoute.emptyRoute() : prepared);
    }

    public void installProjectRoutesForTest(com.stellar.platform.processing.map.ProjectRoute assembled) {
        ensureUiBuilt();
        routeLatitudeChannel = "LATITUDE";
        routeLongitudeChannel = "LONGITUDE";
        applyPreparedProjectRoute(assembled);
    }

    private com.stellar.platform.desktop.presentation.GraphEventGps.RouteView routeView(
            MapRouteModel model, String sourceId, String fileName, String refusal
    ) {
        return new com.stellar.platform.desktop.presentation.GraphEventGps.RouteView(
                model, sourceId, fileName, refusal, latitudeChannel(), longitudeChannel());
    }

    private String latitudeChannel() {
        SignalCatalogKey lat = resolvedRole(GpsSignalRole.LATITUDE);
        if (lat != null && lat.signalName() != null && !lat.signalName().isBlank()) {
            return lat.signalName();
        }
        return routeLatitudeChannel;
    }

    private String longitudeChannel() {
        SignalCatalogKey lon = resolvedRole(GpsSignalRole.LONGITUDE);
        if (lon != null && lon.signalName() != null && !lon.signalName().isBlank()) {
            return lon.signalName();
        }
        return routeLongitudeChannel;
    }

    public String eventMarkerLabelForTest(int position) {
        return mapCanvas == null ? "" : mapCanvas.eventLabelForTest(position);
    }

    private boolean segmentMatches(
            com.stellar.platform.processing.map.RouteSegment segment, String eventId, String eventFile
    ) {
        if (segment == null) {
            return false;
        }
        if (!eventId.isBlank() && eventId.equals(segment.measurementId())) {
            return true;
        }
        String left = com.stellar.platform.desktop.presentation.GraphEventGps.fileName(eventFile);
        String right = com.stellar.platform.desktop.presentation.GraphEventGps.fileName(segment.displayName());
        return !left.isBlank() && left.equalsIgnoreCase(right);
    }

    private void notifyRouteReady() {
        if (routeReady == null || notifyingRouteReady) {
            return;
        }
        notifyingRouteReady = true;
        try {
            routeReady.run();
        } finally {
            notifyingRouteReady = false;
        }
    }

    public java.util.Optional<String> selectedGpsSummary() {
        MapRouteModel model = route.model();
        if (selectedIndex < 0 || model.isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(String.format("%.6f, %.6f",
                model.latitude(selectedIndex), model.longitude(selectedIndex)));
    }

    public void fitRoute() {
        if (mapCanvas != null && !route.isEmpty()) {
            mapCanvas.fitRoute();
            syncBasemapViewport();
        }
    }

    /**
     * Captures the live configured basemap and route canvas after they have settled.
     * The callback runs on the JavaFX thread. No measurement file is reread.
     */
    public void captureForReport(java.util.function.Consumer<RouteMapCapture> callback) {
        java.util.function.Consumer<RouteMapCapture> done = callback == null ? result -> { } : callback;
        ensureUiBuilt();
        if (route == null || route.isEmpty()) {
            done.accept(RouteMapCapture.noRoute());
            return;
        }
        fitRoute();
        awaitReportCapture(0, done);
    }

    private void awaitReportCapture(int attempt, java.util.function.Consumer<RouteMapCapture> done) {
        boolean settled = reportCaptureReady() && attempt >= 2;
        if (settled || attempt >= 16) {
            finishReportCapture(settled, done);
            return;
        }
        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));
        pause.setOnFinished(event -> awaitReportCapture(attempt + 1, done));
        pause.play();
    }

    private boolean reportCaptureReady() {
        if (mapStack == null || mapStack.getWidth() < 32 || mapStack.getHeight() < 32) {
            return false;
        }
        if (basemapView == null) {
            return true;
        }
        if (basemapView.readinessFailed()) {
            return true;
        }
        if (!basemapView.isReady()) {
            return false;
        }
        BasemapTileHealth health = basemapView.tileHealth();
        return health == BasemapTileHealth.TILES_HEALTHY
                || health == BasemapTileHealth.TILES_PARTIALLY_LOADED
                || health == BasemapTileHealth.NEUTRAL_FALLBACK
                || health == BasemapTileHealth.TILE_PROVIDER_FAILED
                || basemapView.tileLoadCompleted();
    }

    private void finishReportCapture(boolean settled, java.util.function.Consumer<RouteMapCapture> done) {
        com.stellar.platform.analysis.investigation.report.RouteMapSnapshot facts = routeFacts(false);
        if (!settled || mapStack == null) {
            LOG.warning("Route map snapshot was not taken because the basemap was not ready");
            done.accept(new RouteMapCapture(null, facts, "Map preview unavailable for this report."));
            return;
        }
        boolean chrome = true;
        boolean controls = mapCanvas != null && mapCanvas.overlayControlsVisible();
        boolean emptyVisible = emptyState != null && emptyState.isVisible();
        boolean loadingVisible = loadingState != null && loadingState.isVisible();
        try {
            if (basemapView != null) {
                basemapView.setReportChromeVisible(false);
                chrome = false;
            }
            if (mapCanvas != null) {
                mapCanvas.hideTooltip();
                mapCanvas.setOverlayControlsVisible(false);
            }
            if (emptyState != null) {
                emptyState.setVisible(false);
                emptyState.setManaged(false);
            }
            if (loadingState != null) {
                loadingState.setVisible(false);
                loadingState.setManaged(false);
            }
            javafx.scene.image.WritableImage image = mapStack.snapshot(new javafx.scene.SnapshotParameters(), null);
            java.awt.image.BufferedImage buffered = javafx.embed.swing.SwingFXUtils.fromFXImage(image, null);
            if (usableMapImage(buffered)) {
                RouteMapCapture captured = pngCapture(buffered);
                restoreReportChrome(!chrome, controls, emptyVisible, loadingVisible);
                done.accept(captured);
                return;
            }
            LOG.warning("Route map node snapshot had no basemap content; trying the visible map");
            boolean restoreChrome = !chrome;
            chrome = true;
            javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(80));
            pause.setOnFinished(event -> {
                try {
                    java.awt.image.BufferedImage screen = captureVisibleMap();
                    if (usableMapImage(screen)) {
                        done.accept(pngCapture(screen));
                    } else {
                        LOG.warning("Route map snapshot was blank after the basemap reported ready");
                        done.accept(new RouteMapCapture(null, facts, "Map preview unavailable for this report."));
                    }
                } catch (RuntimeException | java.io.IOException ex) {
                    LOG.warning("Route map snapshot failed: " + ex.getMessage());
                    done.accept(new RouteMapCapture(null, facts, "Map preview unavailable for this report."));
                } finally {
                    restoreReportChrome(restoreChrome, controls, emptyVisible, loadingVisible);
                }
            });
            pause.play();
        } catch (RuntimeException | java.io.IOException ex) {
            LOG.warning("Route map snapshot failed: " + ex.getMessage());
            done.accept(new RouteMapCapture(null, facts, "Map preview unavailable for this report."));
            restoreReportChrome(!chrome, controls, emptyVisible, loadingVisible);
        }
    }

    private RouteMapCapture pngCapture(java.awt.image.BufferedImage buffered) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(buffered, "png", out);
        return new RouteMapCapture(out.toByteArray(), routeFacts(true), "");
    }

    private void restoreReportChrome(boolean showBasemapChrome, boolean controls, boolean emptyVisible, boolean loadingVisible) {
        if (basemapView != null && showBasemapChrome) {
            basemapView.setReportChromeVisible(true);
        }
        if (mapCanvas != null) {
            mapCanvas.setOverlayControlsVisible(controls);
        }
        if (emptyState != null) {
            emptyState.setVisible(emptyVisible);
            emptyState.setManaged(emptyVisible);
        }
        if (loadingState != null) {
            loadingState.setVisible(loadingVisible);
            loadingState.setManaged(loadingVisible);
        }
    }

    private java.awt.image.BufferedImage captureVisibleMap() {
        if (mapStack == null || mapStack.getScene() == null || mapStack.getScene().getWindow() == null) {
            return null;
        }
        javafx.stage.Window window = mapStack.getScene().getWindow();
        if (!window.isShowing() || !window.isFocused() || java.awt.GraphicsEnvironment.isHeadless()) {
            return null;
        }
        javafx.geometry.Bounds bounds = mapStack.localToScreen(mapStack.getBoundsInLocal());
        if (bounds == null || bounds.getWidth() < 32 || bounds.getHeight() < 32) {
            return null;
        }
        try {
            java.awt.Rectangle rect = new java.awt.Rectangle(
                    (int) Math.floor(bounds.getMinX()),
                    (int) Math.floor(bounds.getMinY()),
                    Math.max(1, (int) Math.ceil(bounds.getWidth())),
                    Math.max(1, (int) Math.ceil(bounds.getHeight())));
            return new java.awt.Robot().createScreenCapture(rect);
        } catch (java.awt.AWTException | RuntimeException ex) {
            LOG.warning("Route map screen capture failed: " + ex.getMessage());
            return null;
        }
    }

    private static boolean usableMapImage(java.awt.image.BufferedImage image) {
        if (image == null || image.getWidth() < 32 || image.getHeight() < 32) {
            return false;
        }
        int samples = 0;
        int colored = 0;
        long sum = 0;
        long sumSq = 0;
        int step = Math.max(4, Math.min(image.getWidth(), image.getHeight()) / 80);
        for (int y = 0; y < image.getHeight(); y += step) {
            for (int x = 0; x < image.getWidth(); x += step) {
                int rgb = image.getRGB(x, y);
                int alpha = (rgb >>> 24) & 0xff;
                if (alpha < 16) {
                    continue;
                }
                int red = (rgb >> 16) & 0xff;
                int green = (rgb >> 8) & 0xff;
                int blue = rgb & 0xff;
                int lum = (red * 3 + green * 6 + blue) / 10;
                sum += lum;
                sumSq += (long) lum * lum;
                samples++;
                if (red < 245 || green < 245 || blue < 245) {
                    colored++;
                }
            }
        }
        if (samples < 16) {
            return false;
        }
        double mean = sum / (double) samples;
        double variance = (sumSq / (double) samples) - (mean * mean);
        return colored * 20 >= samples || variance > 80;
    }

    private com.stellar.platform.analysis.investigation.report.RouteMapSnapshot routeFacts(boolean image) {
        com.stellar.platform.processing.map.MapRouteModel model = route.model();
        if (model == null || model.isEmpty()) {
            return null;
        }
        Double distance = model.totalDistanceMeters() > 0 ? model.totalDistanceMeters() : null;
        return new com.stellar.platform.analysis.investigation.report.RouteMapSnapshot(
                model.firstTimestampNanos(), model.lastTimestampNanos(), model.size(), distance,
                model.minLatitude(), model.maxLatitude(), model.minLongitude(), model.maxLongitude(), image);
    }

    public void setSelectSegmentActive(boolean active) {
        ensureUiBuilt();
        if (active) {
            applyMode(MapSelectionMode.A_B_RANGE, true);
        } else {
            segment.exitSelectMode();
            if (indexA >= 0 && indexB >= 0 && !segment.fullDrive()) {
                mode = MapSelectionMode.A_B_RANGE;
            } else {
                mode = MapSelectionMode.ALL_ROUTE;
            }
            mapCanvas.setMode(mode);
            refreshDetails();
        }
    }

    public boolean selectSegmentActive() {
        return segment.selectMode();
    }

    public void clearSegment() {
        applyFullDrive(true);
    }

    public void setAAtSelectedPoint() {
        if (selectedIndex < 0 || route.isEmpty()) {
            return;
        }
        long timestamp = route.model().timestampNanos(selectedIndex);
        logGpsSample("Set A", selectedIndex);
        segment.setA(selectedIndex, timestamp);
        awaitingDifferentB = false;
        if (segment.complete()) {
            syncSegmentIndices(true);
            statusLabel.setText("Point A set at the selected sample");
        } else {
            paintPendingA();
            statusLabel.setText("Point A set · click a different route sample to set B");
            refreshDetails();
        }
    }

    public void setBAtSelectedPoint() {
        if (selectedIndex < 0 || route.isEmpty()) {
            return;
        }
        long timestamp = route.model().timestampNanos(selectedIndex);
        logGpsSample("Set B", selectedIndex);
        if (!segment.setB(selectedIndex, timestamp)) {
            awaitingDifferentB = true;
            statusLabel.setText("Select a different end point");
            refreshDetails();
            return;
        }
        awaitingDifferentB = false;
        syncSegmentIndices(true);
        statusLabel.setText("Point B set at the selected sample");
    }

    public DriveSegmentSummary segmentSummaryForTest() {
        return segmentSummary;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Prepares (or reuses) the route for the active measurement. Safe to call on every tab
     * activation: an unchanged measurement short-circuits without touching the signal store.
     */
    /** Measurement id to source path. Used only to read a recording-start header, not samples. */
    public void setMeasurementPaths(Map<String, Path> paths) {
        if (paths == null || paths.isEmpty()) {
            measurementPaths = Map.of();
            return;
        }
        Map<String, Path> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Path> entry : paths.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        measurementPaths = Map.copyOf(copy);
    }

    public void activate(MapRouteRequest request) {
        if (request == null || disposed) {
            return;
        }
        ensureUiBuilt();
        resumeRouteSurfaces();
        boolean hasGeometry = !route.isEmpty() || (projectRoute != null && !projectRoute.isEmpty());
        if (request.signature().equals(activeSignature) && hasGeometry && !forceRefresh) {
            LOG.info(() -> "map-reactivate reused routes="
                    + (projectRoute == null ? 0 : projectRoute.gpsCapableCount()));
            // The route geometry is already current. A synchronous WebView resize here
            // blocks the JavaFX thread for seconds even though nothing was reloaded.
            requestLayout();
            if (basemapView != null) {
                basemapView.scheduleInvalidateSize("tab-reactivate");
            }
            return;
        }
        LOG.info(() -> "map-activate reload projectWide=" + request.projectWide());
        forceRefresh = false;
        activeSignature = request.signature();
        activeRequest = request;
        catalogSnapshot = List.copyOf(request.catalog());
        mappingSourceId = request.projectWide() ? "" : request.sourceId();
        if (!request.projectWide() && !mappingSourceId.isBlank()) {
            lastConfiguredSourceId = mappingSourceId;
        }
        if (catalogSnapshot.isEmpty() || (!request.projectWide() && mappingSourceId.isBlank())) {
            showScopeUnavailable(request.sourceId(), request.fileName());
            return;
        }
        if (request.projectWide()) {
            activateProjectWide(request);
            return;
        }
        chronologyStatus = "";
        mappingCatalog = catalogSnapshot;
        schemaFingerprint = GpsSemanticResolver.schemaFingerprint(mappingCatalog);
        persistedSpeed = mappingPersistence.loadSpeedMapping(mappingSourceId);
        query = request.query();
        routeService = new MapRouteService(request.query());
        snapshotService = new SignalSnapshotService(request.query());
        if (basemapView != null) {
            basemapView.prepareForNewMeasurement();
        }
        activeBasemap = MapProviderSelection.neutral("Preparing route");
        showLoading("Loading GPS data...");
        statusLabel.setText("Loading GPS data...");

        long my = ++generation;
        String discoveryGeneration = request.signature() + '|' + mappingSourceId;
        Map<GpsSignalRole, SignalCatalogKey> activateOverrides = resolveGpsOverrides(request);
        List<SignalCatalogEntry> discoveryCatalog = mappingCatalog;
        executor().submit(() -> {
            try {
                // Complete catalog for the displayed measurement — never Graph checkbox membership.
                GpsDetectionResult detected = gpsDiscovery.discover(
                        discoveryCatalog, discoveryGeneration, activateOverrides);
                Map<GpsSignalRole, SignalCatalogKey> roles = new EnumMap<>(GpsSignalRole.class);
                roles.putAll(GpsSignalDiscoveryService.toRoleMap(detected));
                roles.putAll(activateOverrides);
                MapRoute prepared;
                if (GpsSignalRoleResolver.hasRoute(roles)) {
                    MapRouteCacheKey cacheKey = new MapRouteCacheKey(
                            request.projectId(), request.fileId(), request.processingRevision(), roles);
                    long[] gpsWindow = gpsReadWindow(discoveryCatalog);
                    prepared = routeService.prepare(cacheKey, gpsWindow[0], gpsWindow[1]);
                } else {
                    prepared = new MapRoute(MapRouteModel.empty());
                }
                Platform.runLater(() -> {
                    if (my != generation) {
                        staleDiscards++;
                        return;
                    }
                    detection = detected;
                    gpsRoles = roles;
                    if (!detected.hasReliableRoute()) {
                        LOG.info(() -> "Map Route GPS discovery failed resolver="
                                + com.stellar.platform.processing.map.GpsSignalDiscoveryService.resolverLocation()
                                + " scanned=" + detected.scannedSignals()
                                + " latCandidates=" + detected.latitudeCandidates()
                                + " lonCandidates=" + detected.longitudeCandidates()
                                + " confidence=" + detected.confidence()
                                + " catalog=" + discoveryCatalog.size());
                    }
                    applyPreparedRoute(prepared);
                    notifyLayoutChanged("route-ready");
                });
            } catch (RuntimeException ex) {
                Platform.runLater(() -> {
                    if (my == generation) {
                        hideLoading();
                        showEmptyState("GPS route unavailable",
                                ex.getMessage() == null ? "Route could not be prepared." : ex.getMessage());
                    }
                });
            }
        });
    }

    private void activateProjectWide(MapRouteRequest request) {
        mappingCatalog = catalogSnapshot;
        schemaFingerprint = "";
        persistedSpeed = null;
        query = request.query();
        routeService = new MapRouteService(request.query());
        snapshotService = new SignalSnapshotService(
                new com.stellar.platform.processing.graph.PhysicalIdentityGraphSeriesQuery(request.query()));
        selectedSegment = null;
        if (basemapView != null) {
            basemapView.prepareForNewMeasurement();
        }
        activeBasemap = MapProviderSelection.neutral("Preparing routes");
        showLoading("Loading GPS data...");
        statusLabel.setText("Loading GPS data...");
        long my = ++generation;
        List<MeasurementCatalogScope.Ref> refs = MeasurementCatalogScope.measurements(catalogSnapshot);
        Map<String, Path> paths = measurementPaths;
        List<GpsSignalMapping> mappings = projectMappings;
        List<GpsSignalMapping> defaults = defaultMappings;
        executor().submit(() -> {
            try {
                List<ProjectRouteAssembler.PreparedMeasurement> prepared = new ArrayList<>();
                for (MeasurementCatalogScope.Ref ref : refs) {
                    List<SignalCatalogEntry> scoped = GpsMappingScope.catalogFor(ref.identity(), catalogSnapshot);
                    if (scoped.isEmpty()) {
                        scoped = MeasurementCatalogScope.filter(catalogSnapshot, ref.identity(), ref.displayName());
                    }
                    Map<GpsSignalRole, SignalCatalogKey> overrides = resolveOverridesFor(
                            ref.identity(), ref.displayName(), scoped, mappings, defaults);
                    GpsDetectionResult detected = gpsDiscovery.discover(
                            scoped, request.signature() + '|' + ref.identity(), overrides);
                    Map<GpsSignalRole, SignalCatalogKey> roles = new EnumMap<>(GpsSignalRole.class);
                    roles.putAll(GpsSignalDiscoveryService.toRoleMap(detected));
                    roles.putAll(overrides);
                    MapRoute built;
                    if (GpsSignalRoleResolver.hasRoute(roles)) {
                        MapRouteCacheKey cacheKey = new MapRouteCacheKey(
                                request.projectId(), ref.identity(), request.processingRevision(), roles);
                        long[] gpsWindow = gpsReadWindow(scoped);
                        built = routeService.prepare(cacheKey, gpsWindow[0], gpsWindow[1]);
                    } else {
                        built = MapRoute.emptyRoute();
                    }
                    prepared.add(new ProjectRouteAssembler.PreparedMeasurement(
                            ref.identity(), ref.displayName(), built));
                }
                List<String> ids = new ArrayList<>();
                for (ProjectRouteAssembler.PreparedMeasurement row : prepared) {
                    ids.add(row.measurementId());
                }
                RouteChronology.Result chronology = RouteChronology.order(
                        prepared, RecordingClockReader.readAll(ids, paths));
                ProjectRoute assembled = ProjectRouteAssembler.assemble(chronology.measurements(), refs.size());
                Platform.runLater(() -> {
                    if (my != generation) {
                        staleDiscards++;
                        return;
                    }
                    applyPreparedProjectRoute(assembled, chronology.status());
                    notifyLayoutChanged("project-route-ready");
                });
            } catch (RuntimeException ex) {
                Platform.runLater(() -> {
                    if (my == generation) {
                        hideLoading();
                        showEmptyState("GPS route unavailable",
                                ex.getMessage() == null ? "Project routes could not be prepared." : ex.getMessage());
                    }
                });
            }
        });
    }

    /**
     * GPS sample reads use the measurement catalog extent. The graph viewport and the selected
     * engineering-signal span are not sample counts and must not drop a fix the report still has.
     */
    private static long[] gpsReadWindow(List<SignalCatalogEntry> measurementCatalog) {
        return GpsCatalogWindow.inclusive(measurementCatalog);
    }

    private Map<GpsSignalRole, SignalCatalogKey> resolveOverridesFor(
            String sourceId,
            String fileName,
            List<SignalCatalogEntry> scoped,
            List<GpsSignalMapping> mappings,
            List<GpsSignalMapping> defaults
    ) {
        GpsMappingPolicy.Resolution policy = GpsMappingPolicy.resolve(
                sourceId,
                GpsSemanticResolver.schemaFingerprint(scoped),
                scoped,
                GpsMappingScope.mappingsForMeasurement(sourceId, fileName, mappings),
                defaults);
        return policy.hasRouteOverrides() ? policy.overrides() : Map.of();
    }

    /**
     * Called when another analysis tab takes over. Invalidates in-flight GPS preparation so a
     * background result cannot paint this map, and stops canvas uploads until {@link #activate}.
     */
    public void deactivate() {
        if (disposed) {
            return;
        }
        generation++;
        layoutResync.stop();
        suspendRouteSurfaces();
    }

    private void suspendRouteSurfaces() {
        if (mapCanvas != null) {
            mapCanvas.hideTooltip();
            mapCanvas.suspendPainting();
        }
        if (timeline != null) {
            timeline.suspendPainting();
        }
        if (miniTracks != null) {
            miniTracks.suspendPainting();
        }
    }

    private void resumeRouteSurfaces() {
        if (mapCanvas != null) {
            mapCanvas.resumePainting();
        }
        if (timeline != null) {
            timeline.resumePainting();
        }
        if (miniTracks != null) {
            miniTracks.resumePainting();
        }
    }

    /**
     * Must be called after Inspector collapse/expand, Full Screen, and stage resize so Leaflet
     * recalculates its container size without recreating the WebView.
     */
    public void notifyLayoutChanged(String reason) {
        requestLayout();
        if (mapWorkspaceSplit != null) {
            mapWorkspaceSplit.requestLayout();
        }
        String label = reason == null ? "layout" : reason;
        if (basemapView != null) {
            basemapView.resyncAfterChrome(label);
        }
        scheduleBasemapResync(label + "-settled");
    }

    /**
     * Leaflet must see settled JavaFX bounds. Coalesce resize/focus/details pulses so the
     * route is not rebuilt — only the existing WebView is invalidated.
     */
    private void scheduleBasemapResync(String reason) {
        if (disposed || basemapView == null) {
            return;
        }
        long scheduledGeneration = generation;
        layoutResync.setOnFinished(e -> {
            if (!disposed && scheduledGeneration == generation && basemapView != null) {
                basemapView.resyncAfterChrome(reason);
            }
        });
        layoutResync.playFromStart();
    }

    /** Drops all route state so a new measurement can never be shown against old map data. */
    public void resetForFileChange() {
        generation++;
        activeSignature = "";
        route = MapRoute.emptyRoute();
        projectRoute = ProjectRoute.empty();
        selectedSegment = null;
        gpsRoles = Map.of();
        detection = GpsDetectionResult.none("");
        resolvedOverrides.clear();
        resolvedUserConfirmed = false;
        persistedMissing = false;
        persistedMissingMessage = "";
        droppedRolesMessage = "";
        mappingOrigin = GpsMappingPolicy.Origin.UNRESOLVED;
        pendingRestore = null;
        selectedIndex = -1;
        indexA = -1;
        indexB = -1;
        segment.applyFullDrive(-1, -1);
        lastAnalyzedStart = Long.MIN_VALUE;
        lastAnalyzedEnd = Long.MIN_VALUE;
        segmentSummary = DriveSegmentSummary.empty();
        if (routeService != null) {
            routeService.clear();
        }
        gpsDiscovery.invalidate();
        activeBasemap = MapProviderSelection.neutral("No measurement loaded");
        if (uiBuilt) {
            closeMappingEditor();
            mapCanvas.setRoute(null);
            timeline.clearSelection();
            miniTracks.clear();
            if (basemapView != null) {
                basemapView.prepareForNewMeasurement();
                basemapView.applyNeutral("Open a processed measurement to see its route.");
                applyRouteCanvasBackground(false);
            }
            hideLoading();
            refreshMappingCard();
            showEmptyState("No measurement loaded", "Open a processed measurement to see its route.");
            refreshDetails();
        }
    }

    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        generation++;
        layoutResync.stop();
        suspendRouteSurfaces();
        if (sharedSelectionUnsubscribe != null) {
            sharedSelectionUnsubscribe.run();
            sharedSelectionUnsubscribe = null;
        }
        if (routeService != null) {
            routeService.clear();
            routeService = null;
        }
        if (uiBuilt) {
            closeMappingEditor();
            if (mappingEditor != null) {
                mappingEditor.detachOverlay();
            }
            if (mapCanvas != null) {
                mapCanvas.dispose();
            }
            if (basemapView != null) {
                basemapView.dispose();
            }
            if (timeline != null) {
                timeline.dispose();
            }
            if (miniTracks != null) {
                miniTracks.release();
            }
        }
        if (mapAssetServer != null) {
            mapAssetServer.close();
            mapAssetServer = null;
        }
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
        route = MapRoute.emptyRoute();
        snapshotService = null;
        query = null;
    }

    // ------------------------------------------------------------------
    // Inputs from the workspace
    // ------------------------------------------------------------------

    /** Signals chosen in the existing left panel drive what the detail panels show. */
    public void setSelectedSignals(List<SignalCatalogKey> keys) {
        selectedSignals = keys == null ? List.of() : List.copyOf(keys);
        if (uiBuilt && selectedIndex >= 0) {
            refreshDetails();
        }
    }

    /**
     * Viewport series already rendered by the waveform pipeline for the active range. Reused for
     * the A→B mini-trends so the map keeps no second copy of signal data.
     */
    public void setFilteredSeries(List<GraphRenderSeries> series) {
        setFilteredSeries(series, List.of());
    }

    public void setFilteredSeries(List<GraphRenderSeries> series, List<Integer> colorIndexes) {
        if (uiBuilt) {
            miniTracks.setSeries(series, colorIndexes);
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public MapRouteState captureState() {
        MapRouteModel model = route.model();
        return new MapRouteState(
                mode,
                selectedIndex >= 0 && selectedIndex < model.size() ? model.timestampNanos(selectedIndex) : null,
                segment.complete() ? segment.startTimestampNanos() : null,
                segment.complete() ? segment.endTimestampNanos() : null,
                autoSync);
    }

    /** Notified after any user-visible change to the persistable state. */
    public void setStateChangedHandler(Runnable handler) {
        this.stateChangedHandler = handler == null ? () -> { } : handler;
    }

    /** Navigates to the canonical GPS mapping editor in Configuration. */
    public void setConfigureMappingHandler(Runnable handler) {
        this.configureMappingHandler = handler == null ? () -> { } : handler;
    }

    /**
     * Persistence used by the in-place Map Route editor. The same store Configuration writes to,
     * so a save here is a save there.
     */
    public void setMappingPersistence(RouteMappingPersistence persistence) {
        this.mappingPersistence = persistence == null ? RouteMappingPersistence.NONE : persistence;
    }

    /** Product-wide fallback mappings, consulted only when the project configured nothing. */
    public void setDefaultMappings(List<GpsSignalMapping> mappings) {
        List<GpsSignalMapping> next = mappings == null ? List.of() : List.copyOf(mappings);
        boolean changed = !sameMappings(defaultMappings, next);
        defaultMappings = next;
        if (!changed) {
            return;
        }
        invalidateResolvedMapping();
        if (activeRequest != null) {
            activate(activeRequest);
        }
    }

    /**
     * Installs the project's persisted GPS mappings, from Configuration or from project load.
     *
     * <p>A changed mapping marks the current route dirty and rebuilds it if this pane already has a
     * request. Without that, an activation whose {@link MapRouteRequest#signature()} is unchanged
     * would short-circuit and keep showing a route built from the previous mapping.
     */
    public void setProjectMappings(List<GpsSignalMapping> mappings) {
        List<GpsSignalMapping> next = mappings == null ? List.of() : List.copyOf(mappings);
        boolean changed = !sameMappings(projectMappings, next);
        projectMappings = next;
        if (!changed) {
            return;
        }
        invalidateResolvedMapping();
        if (activeRequest != null) {
            activate(activeRequest);
        }
    }

    public String schemaFingerprint() {
        return schemaFingerprint;
    }

    /** Role assignments the current route was built from, for status reporting. */
    public Map<GpsSignalRole, SignalCatalogKey> resolvedGpsRoles() {
        EnumMap<GpsSignalRole, SignalCatalogKey> out = new EnumMap<>(GpsSignalRole.class);
        out.putAll(gpsRoles);
        out.putAll(resolvedOverrides);
        return out;
    }

    /** Identity comparison that ignores persistence timestamps. */
    private static boolean sameMappings(List<GpsSignalMapping> a, List<GpsSignalMapping> b) {
        if (a.size() != b.size()) {
            return false;
        }
        Set<String> left = new LinkedHashSet<>();
        for (GpsSignalMapping mapping : a) {
            left.add(mappingIdentity(mapping));
        }
        for (GpsSignalMapping mapping : b) {
            if (!left.contains(mappingIdentity(mapping))) {
                return false;
            }
        }
        return true;
    }

    private static String mappingIdentity(GpsSignalMapping mapping) {
        return mapping.sourceId() + '|' + mapping.schemaFingerprint() + '|' + mapping.role()
                + '|' + mapping.mode() + '|' + mapping.requestedSignalName() + '|' + mapping.scope()
                + '|' + mapping.userConfirmed();
    }

    /**
     * Re-resolves persisted timestamps against the rebuilt route. Route preparation is asynchronous,
     * so a restore arriving first is replayed once coordinates are available.
     */
    public void restoreState(MapRouteState state) {
        if (state == null) {
            return;
        }
        ensureUiBuilt();
        autoSync = state.autoSyncWithTimeline();
        autoSyncToggle.setSelected(autoSync);
        applyMode(state.mode(), false);
        if (route.model().isEmpty()) {
            pendingRestore = state;
            return;
        }
        pendingRestore = null;
        applySelectionFromState(state);
    }

    private void applySelectionFromState(MapRouteState state) {
        MapRouteModel model = route.model();
        if (state.mode() == MapSelectionMode.A_B_RANGE
                && state.aTimestampNanos() != null && state.bTimestampNanos() != null
                && !state.aTimestampNanos().equals(state.bTimestampNanos())) {
            int restoredA = model.nearestIndexByTime(state.aTimestampNanos());
            int restoredB = model.nearestIndexByTime(state.bTimestampNanos());
            if (restoredA != restoredB) {
                segment.setA(restoredA, model.timestampNanos(restoredA));
                segment.setB(restoredB, model.timestampNanos(restoredB));
                mode = MapSelectionMode.A_B_RANGE;
                syncSegmentIndices(true);
            } else {
                applyFullDrive(false);
            }
        } else {
            applyFullDrive(false);
        }
        if (state.mode() == MapSelectionMode.SINGLE_POINT && state.pointTimestampNanos() != null) {
            mode = MapSelectionMode.SINGLE_POINT;
            if (singlePointButton != null) {
                selectedButton(mode).setSelected(true);
            }
            mapCanvas.setMode(mode);
            timeline.setMode(mode);
            selectIndex(model.nearestIndexByTime(state.pointTimestampNanos()), true);
        }
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private void ensureUiBuilt() {
        if (uiBuilt) {
            return;
        }
        uiBuilt = true;

        mapCanvas = new RouteCanvas(new TransparentMapBackground());
        mapCanvas.setPointPickedHandler(this::onPointPicked);
        mapCanvas.setSegmentPickedHandler(this::onSegmentPicked);
        mapCanvas.setHoverHandler(this::onHover);
        mapCanvas.setViewportChangedHandler(this::syncBasemapViewport);

        try {
            mapAssetServer = new MapLocalAssetServer();
        } catch (java.io.IOException ex) {
            mapAssetServer = null;
            statusLabel.setText("Offline map server unavailable · online/neutral basemap only");
        }

        basemapView = new LeafletBasemapView();
        if (mapAssetServer != null) {
            basemapView.setProbeAssetBaseUrl(mapAssetServer.baseUrl());
        }
        basemapView.setReadyHandler(this::onBasemapReady);
        basemapView.setTileStateHandler(this::refreshBasemapStatus);
        basemapView.setMaxZoomHandler(this::onBasemapMaxZoomChanged);
        mapCanvas.setProviderMaxZoom(basemapView.activeMaxZoom());
        basemapView.setModeFailedHandler(this::onBasemapModeFailed);
        // Navigation is owned by the route canvas, so the WebView never receives the pointer.
        basemapView.setMouseTransparent(true);

        timeline = new RouteTimelineBar();
        timeline.setPointDraggedHandler(this::onTimelinePoint);
        timeline.setRangeDraggedHandler(this::onTimelineRange);

        miniTracks = new MiniTrackCanvas();

        emptyState = buildEmptyState();
        loadingState = buildLoadingState();
        mapStack = new StackPane(basemapView, mapCanvas, emptyState, loadingState);
        mapStack.getStyleClass().add("map-route-stage");
        mapStack.setMinHeight(MAP_MIN_USABLE_HEIGHT);
        mapStack.setPrefHeight(MAP_MIN_USABLE_HEIGHT);
        mapStack.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(mapStack, Priority.ALWAYS);
        emptyState.setVisible(false);
        emptyState.setManaged(false);
        applyOverlayProbe();
        mapStack.heightProperty().addListener((o, a, height) -> {
            if (height != null && height.doubleValue() > 0) {
                scheduleBasemapResync("map-stage-resize");
            }
        });
        widthProperty().addListener((o, a, w) -> scheduleBasemapResync("pane-resize"));
        heightProperty().addListener((o, a, h) -> scheduleBasemapResync("pane-resize"));
        visibleProperty().addListener((o, a, visible) -> {
            if (Boolean.TRUE.equals(visible)) {
                notifyLayoutChanged("pane-visible");
            }
        });

        setTop(null);
        mapCanvas.setOverlayControlsVisible(false);

        Region timelineSection = (Region) buildTimelineSection();
        timelineSection.setMinHeight(Region.USE_PREF_SIZE);
        timelineSection.setMaxHeight(Region.USE_PREF_SIZE);

        Node routeData = buildFilteredDataSection();
        applyRouteDataBounds();

        VBox mapColumn = new VBox(6, mapStack, timelineSection, routeData);
        mapColumn.getStyleClass().add("map-route-column");
        mapColumn.setMinWidth(0);
        mapColumn.setMinHeight(0);
        mapColumn.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(mapStack, Priority.ALWAYS);
        HBox.setHgrow(mapColumn, Priority.ALWAYS);
        SplitPane.setResizableWithParent(mapColumn, true);

        Node modeBar = buildModeBar();
        modeBar.setVisible(false);
        modeBar.setManaged(false);

        railScroll = new ScrollPane(buildDetailRail());
        railScroll.setFitToWidth(true);
        railScroll.setFitToHeight(false);
        railScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        railScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        railScroll.getStyleClass().add("map-route-rail-scroll");
        railScroll.setMinWidth(232);
        railScroll.setPrefWidth(268);
        railScroll.setMaxWidth(320);
        railScroll.setMinHeight(0);
        railScroll.setPrefHeight(Region.USE_COMPUTED_SIZE);
        railScroll.setMaxHeight(Double.MAX_VALUE);
        SplitPane.setResizableWithParent(railScroll, false);

        mapWorkspaceSplit = new SplitPane(mapColumn, railScroll);
        mapWorkspaceSplit.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        mapWorkspaceSplit.getStyleClass().add("map-route-workspace");
        mapWorkspaceSplit.setDividerPositions(0.78);
        mapWorkspaceSplit.setMinWidth(0);
        mapWorkspaceSplit.setMinHeight(0);
        mapWorkspaceSplit.setMaxHeight(Double.MAX_VALUE);
        mappingSheet = buildMappingSheet();
        setCenter(mapWorkspaceSplit);
        setMaxHeight(Double.MAX_VALUE);
        setBottom(buildStatusBar());

        sharedSelectionUnsubscribe = sharedSelection.addListener(this::onSharedSelectionChanged);
        applyMode(MapSelectionMode.ALL_ROUTE, false);
    }

    private Node buildModeBar() {
        Label caption = new Label("Selection Mode:");
        caption.getStyleClass().add("map-route-mode-caption");

        ToggleGroup group = new ToggleGroup();
        singlePointButton = modeButton(MapSelectionMode.SINGLE_POINT, group, "map-route-segment-left");
        rangeButton = modeButton(MapSelectionMode.A_B_RANGE, group, "map-route-segment-middle");
        allRouteButton = modeButton(MapSelectionMode.ALL_ROUTE, group, "map-route-segment-right");

        HBox segmented = new HBox(0, singlePointButton, rangeButton, allRouteButton);
        segmented.getStyleClass().add("map-route-segmented");
        segmented.setAlignment(Pos.CENTER_LEFT);

        HBox bar = new HBox(10, caption, segmented);
        bar.getStyleClass().add("map-route-mode-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private ToggleButton modeButton(MapSelectionMode target, ToggleGroup group, String edgeStyle) {
        ToggleButton button = new ToggleButton(target.label());
        button.getStyleClass().addAll("map-route-segment", edgeStyle);
        button.setToggleGroup(group);
        button.setFocusTraversable(false);
        // A segmented control must always show the active mode; ignore clicks that would clear it.
        button.setOnAction(e -> {
            if (!button.isSelected()) {
                button.setSelected(true);
                return;
            }
            applyMode(target, true);
        });
        return button;
    }

    private Node buildTimelineSection() {
        Label caption = new Label("Time (Local)");
        caption.getStyleClass().add("map-route-timeline-caption");
        VBox box = new VBox(2, caption, timeline);
        box.getStyleClass().add("map-route-timeline-section");
        return box;
    }

    private Node buildFilteredDataSection() {
        filteredDataTitle = new Label("Route Data");
        filteredDataTitle.getStyleClass().add("map-route-section-title");
        filteredDataBadge = new Label("Full measurement");
        filteredDataBadge.getStyleClass().add("map-route-badge");
        routeDetailsToggle = new Button("Show Details");
        routeDetailsToggle.getStyleClass().addAll("secondary-button", "compact-button");
        routeDetailsToggle.setOnAction(e -> toggleRouteDetails());

        Region headerGrow = new Region();
        HBox.setHgrow(headerGrow, Priority.ALWAYS);
        HBox header = new HBox(8, filteredDataTitle, filteredDataBadge, headerGrow, routeDetailsToggle);
        header.setAlignment(Pos.CENTER_LEFT);

        summaryTiles = new HBox(0);
        summaryTiles.getStyleClass().add("map-route-tiles");

        filteredDataSection = new VBox(8, header, summaryTiles, miniTracks);
        filteredDataSection.getStyleClass().add("map-route-filtered-section");
        filteredDataSection.setMinHeight(0);
        applyRouteDataBounds();
        return filteredDataSection;
    }

    private void toggleRouteDetails() {
        routeDetailsExpanded = !routeDetailsExpanded;
        applyRouteDataBounds();
        notifyLayoutChanged(routeDetailsExpanded ? "route-details-show" : "route-details-hide");
    }

    private void applyRouteDataBounds() {
        if (filteredDataSection == null) {
            return;
        }
        if (miniTracks != null) {
            miniTracks.setVisible(routeDetailsExpanded);
            miniTracks.setManaged(routeDetailsExpanded);
        }
        filteredDataSection.setMaxHeight(routeDetailsExpanded ? ROUTE_DATA_EXPANDED_MAX : ROUTE_DATA_COLLAPSED_MAX);
        if (routeDetailsToggle != null) {
            routeDetailsToggle.setText(routeDetailsExpanded ? "Hide Details" : "Show Details");
        }
    }

    private Node buildDetailRail() {
        detailTitle = new Label("Selected Point");
        detailTitle.getStyleClass().add("map-route-section-title");
        detailBadge = new Label("No selection");
        detailBadge.getStyleClass().add("map-route-badge");
        detailBody = new VBox(0);
        detailBody.getStyleClass().add("map-route-detail-body");

        copyButton = new Button("Copy to Clipboard");
        copyButton.getStyleClass().addAll("secondary-button", "compact-button");
        copyButton.setMaxWidth(Double.MAX_VALUE);
        copyButton.setOnAction(e -> copyDetails());
        copyButton.setDisable(true);

        setAButton = new Button("Set A at selected point");
        setAButton.getStyleClass().addAll("secondary-button", "compact-button");
        setAButton.setMaxWidth(Double.MAX_VALUE);
        setAButton.setOnAction(e -> setAAtSelectedPoint());
        setBButton = new Button("Set B at selected point");
        setBButton.getStyleClass().addAll("secondary-button", "compact-button");
        setBButton.setMaxWidth(Double.MAX_VALUE);
        setBButton.setOnAction(e -> setBAtSelectedPoint());

        VBox detailCard = card(new VBox(6, new HBox(8, detailTitle), detailBadge, detailBody,
                setAButton, setBButton, copyButton));

        segmentTitle = new Label("Segment Analysis");
        segmentTitle.getStyleClass().add("map-route-section-title");
        segmentBadge = new Label("Full Drive");
        segmentBadge.getStyleClass().add("map-route-badge");
        segmentHeadline = new Label("Start → End");
        segmentHeadline.getStyleClass().add("map-route-segment-headline");
        segmentHeadline.setWrapText(true);
        segmentBody = new VBox(2);
        segmentBody.getStyleClass().add("map-route-detail-body");
        segmentIssueList = new VBox(2);
        segmentIssueList.setVisible(false);
        segmentIssueList.setManaged(false);
        segmentIssuesLink = new Hyperlink("Open detailed analysis");
        segmentIssuesLink.getStyleClass().add("map-route-health-link");
        segmentIssuesLink.setOnAction(e -> {
            boolean show = !segmentIssueList.isVisible();
            segmentIssueList.setVisible(show);
            segmentIssueList.setManaged(show);
        });
        VBox segmentCard = card(new VBox(6,
                new HBox(8, segmentTitle, segmentBadge),
                segmentHeadline,
                segmentBody,
                segmentIssuesLink,
                segmentIssueList));

        filterStateLabel = new Label("No filter active");
        filterStateLabel.getStyleClass().add("map-route-filter-state");
        filterDetailLabel = new Label("All waveform, statistics and analytics views show the full measurement.");
        filterDetailLabel.getStyleClass().add("map-route-hint");
        filterDetailLabel.setWrapText(true);
        clearFilterButton = new Button("Clear Filter");
        clearFilterButton.getStyleClass().addAll("secondary-button", "compact-button");
        clearFilterButton.setMaxWidth(Double.MAX_VALUE);
        clearFilterButton.setDisable(true);
        clearFilterButton.setOnAction(e -> clearSegment());

        autoSyncToggle = new CheckBox("Auto sync with timeline");
        autoSyncToggle.setSelected(true);
        autoSyncToggle.setOnAction(e -> {
            autoSync = autoSyncToggle.isSelected();
            if (autoSync) {
                pushSelectionToSharedModel();
            }
            stateChangedHandler.run();
        });

        Label filterTitle = new Label("Linked Signal Filter");
        filterTitle.getStyleClass().add("map-route-section-title");
        VBox filterCard = card(new VBox(6, filterTitle, filterStateLabel, filterDetailLabel, clearFilterButton));
        Label syncTitle = new Label("Map-to-Signal Sync");
        syncTitle.getStyleClass().add("map-route-section-title");
        VBox syncCard = card(new VBox(6, syncTitle, autoSyncToggle));

        VBox rail = new VBox(10, buildMappingCard(), filterCard, syncCard, segmentCard, detailCard);
        rail.getStyleClass().add("map-route-rail");
        rail.setMinWidth(210);
        rail.setPrefWidth(248);
        rail.setMaxWidth(300);
        return rail;
    }

    private Node buildStatusBar() {
        statusLabel = new Label("Open a processed measurement to see its route.");
        statusLabel.getStyleClass().add("map-route-status");
        statusLabel.setWrapText(true);
        HBox bar = new HBox(statusLabel);
        bar.getStyleClass().add("map-route-status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private VBox buildEmptyState() {
        emptyHeadline = new Label("GPS route unavailable");
        emptyHeadline.getStyleClass().add("map-route-empty-title");
        emptyDetail = new Label("No reliable GPS coordinate signals were detected in this measurement.");
        emptyDetail.getStyleClass().add("map-route-empty-detail");
        emptyDetail.setWrapText(true);
        emptyDiagnostic = new Label();
        emptyDiagnostic.getStyleClass().add("map-route-empty-detail");
        emptyDiagnostic.setWrapText(true);
        emptyDiagnostic.setVisible(false);
        emptyDiagnostic.setManaged(false);
        emptyAction = new Button("Configure GPS");
        emptyAction.getStyleClass().addAll("primary-button", "compact-button");
        emptyAction.setOnAction(e -> openMappingEditor());

        VBox box = new VBox(10, emptyHeadline, emptyDetail, emptyDiagnostic, emptyAction);
        box.getStyleClass().add("map-route-empty");
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(16));
        return box;
    }

    private VBox buildLoadingState() {
        loadingLabel = new Label("Loading GPS data...");
        loadingLabel.getStyleClass().add("map-route-empty-title");
        VBox box = new VBox(loadingLabel);
        box.getStyleClass().add("map-route-loading");
        box.setAlignment(Pos.CENTER);
        box.setMouseTransparent(true);
        box.setVisible(false);
        box.setManaged(false);
        return box;
    }

    /**
     * Read-only mapping summary. Editing and persisting GPS roles is Configuration's job — this
     * pane only reports which signals the route was actually built from.
     */
    private Node buildMappingCard() {
        Label title = new Label("GPS & Route");
        title.getStyleClass().add("map-route-section-title");

        mappingMeasurementValue = mappingValueLabel();
        mappingLatitudeValue = mappingValueLabel();
        mappingLongitudeValue = mappingValueLabel();
        mappingAltitudeValue = mappingValueLabel();
        mappingStatusValue = mappingValueLabel();
        mappingStatusValue.getStyleClass().add("map-route-mapping-status");

        mappingEditButton = new Button("Change Mapping");
        mappingEditButton.getStyleClass().addAll("secondary-button", "compact-button");
        mappingEditButton.setMaxWidth(Double.MAX_VALUE);
        mappingEditButton.setOnAction(e -> openMappingEditor());

        VBox body = new VBox(6,
                title,
                mappingRow("Measurement", mappingMeasurementValue),
                mappingRow("Latitude", mappingLatitudeValue),
                mappingRow("Longitude", mappingLongitudeValue),
                mappingRow("Altitude", mappingAltitudeValue),
                mappingRow("Status", mappingStatusValue),
                mappingEditButton);
        return card(body);
    }

    private StackPane buildMappingSheet() {
        mappingEditor = new GpsSignalMappingModal();
        mappingEditor.setSaveHandler(this::saveMappingEditor);
        mappingEditor.setCancelHandler(this::closeMappingEditor);
        mappingEditor.setRecommendedHandler(this::fillRecommendedMapping);
        mappingEditor.setFullConfigHandler(() -> {
            closeMappingEditor();
            configureMappingHandler.run();
        });
        return mappingEditor;
    }

    private void openMappingEditor() {
        ensureUiBuilt();
        mappingSaveInFlight = false;
        if (mappingEditor != null) {
            mappingEditor.setBusy(false);
        }
        List<MeasurementCatalogScope.Ref> refs = MeasurementCatalogScope.measurements(catalogSnapshot);
        String selected = resolveEditorSource(refs);
        mappingEditor.setMeasurementChangeHandler(this::onEditorMeasurementChosen);
        mappingEditor.setMeasurements(toMeasurementOptions(refs), selected, requireMeasurementChoice());
        applyEditorDraft(selected, ++mappingEditorGeneration);
        mappingEditor.showOnApplicationOverlay(this);
        applyOverlayProbe();
    }

    private boolean requireMeasurementChoice() {
        return activeRequest != null && activeRequest.projectWide()
                && (lastConfiguredSourceId.isBlank() || !containsSource(lastConfiguredSourceId));
    }

    private String resolveEditorSource(List<MeasurementCatalogScope.Ref> refs) {
        if (activeRequest != null && !activeRequest.projectWide() && !activeRequest.sourceId().isBlank()) {
            return activeRequest.sourceId();
        }
        if (containsSource(lastConfiguredSourceId, refs)) {
            return lastConfiguredSourceId;
        }
        if (selectedSegment != null && containsSource(selectedSegment.measurementId(), refs)) {
            return selectedSegment.measurementId();
        }
        return "";
    }

    private void onEditorMeasurementChosen(String sourceId) {
        lastConfiguredSourceId = sourceId == null ? "" : sourceId;
        applyEditorDraft(lastConfiguredSourceId, ++mappingEditorGeneration);
    }

    private void applyEditorDraft(String sourceId, int generation) {
        if (mappingEditor == null || generation != mappingEditorGeneration) {
            return;
        }
        String projectId = activeRequest == null ? "" : activeRequest.projectId();
        if (sourceId == null || sourceId.isBlank()) {
            mappingEditor.setPreviewRoute(MapRoute.emptyRoute());
            mappingEditor.setDraft(GpsMappingDraft.resolve(
                    projectId, "", List.of(), projectMappings, defaultMappings));
            return;
        }
        MeasurementCatalogScope.Ref ref = findSource(sourceId);
        List<SignalCatalogEntry> scoped = GpsMappingScope.catalogFor(sourceId, catalogSnapshot);
        if (scoped.isEmpty() && ref != null) {
            scoped = MeasurementCatalogScope.filter(catalogSnapshot, sourceId, ref.displayName());
        }
        mappingEditor.setPreviewRoute(previewFor(sourceId));
        mappingEditor.setDraft(GpsMappingDraft.resolve(
                projectId, sourceId, scoped, projectMappings, defaultMappings));
    }

    private MapRoute previewFor(String sourceId) {
        if (projectRoute != null) {
            for (RouteSegment segment : projectRoute.segments()) {
                if (sourceId.equals(segment.measurementId())) {
                    return segment.route();
                }
            }
        }
        if (!route.isEmpty() && sourceId.equals(mappingSourceId)) {
            return route;
        }
        return MapRoute.emptyRoute();
    }

    private static List<GpsSignalMappingModal.MeasurementOption> toMeasurementOptions(
            List<MeasurementCatalogScope.Ref> refs
    ) {
        List<GpsSignalMappingModal.MeasurementOption> options = new ArrayList<>();
        for (MeasurementCatalogScope.Ref ref : refs) {
            if (ref != null && !ref.identity().isBlank()) {
                options.add(new GpsSignalMappingModal.MeasurementOption(ref.identity(), ref.displayName()));
            }
        }
        return options;
    }

    private boolean containsSource(String sourceId) {
        return containsSource(sourceId, MeasurementCatalogScope.measurements(catalogSnapshot));
    }

    private static boolean containsSource(String sourceId, List<MeasurementCatalogScope.Ref> refs) {
        if (sourceId == null || sourceId.isBlank() || refs == null) {
            return false;
        }
        for (MeasurementCatalogScope.Ref ref : refs) {
            if (sourceId.equals(ref.identity())) {
                return true;
            }
        }
        return false;
    }

    private MeasurementCatalogScope.Ref findSource(String sourceId) {
        for (MeasurementCatalogScope.Ref ref : MeasurementCatalogScope.measurements(catalogSnapshot)) {
            if (sourceId.equals(ref.identity())) {
                return ref;
            }
        }
        return null;
    }

    private void closeMappingEditor() {
        mappingSaveInFlight = false;
        if (mappingEditor != null) {
            mappingEditor.hidePickers();
            mappingEditor.setBusy(false);
            mappingEditor.hideOverlay();
        }
        if (mapCanvas != null) {
            mapCanvas.requestFocus();
        }
        notifyLayoutChanged("mapping-editor-closed");
    }

    private void fillRecommendedMapping() {
        if (mappingEditor == null || mappingEditor.draft() == null) {
            return;
        }
        mappingEditor.draft().autoDetect();
        mappingEditor.applyDraft();
    }

    private void saveMappingEditor() {
        if (mappingSaveInFlight || mappingEditor == null || !mappingEditor.isVisible()) {
            return;
        }
        GpsMappingDraft draft = mappingEditor == null ? null : mappingEditor.draft();
        if (draft == null || !draft.requiredRolesConfigured()) {
            if (mappingEditor != null) {
                mappingEditor.setStatus("Latitude and Longitude are required to draw the route.");
            }
            return;
        }
        mappingSaveInFlight = true;
        mappingEditor.setBusy(true);
        try {
            lastConfiguredSourceId = draft.sourceId();
            boolean stored = mappingPersistence.saveProjectMappings(
                    draft.sourceId(), draft.schemaFingerprint(), draft.toMappings(Instant.now()));
            if (!stored) {
                mappingEditor.setStatus("Mapping could not be saved. Your edits are still here.");
                return;
            }
            setProjectMappings(mappingPersistence.loadProjectMappings());
            closeMappingEditor();
            refreshMappingCard();
        } catch (RuntimeException ex) {
            mappingEditor.setStatus(ex.getMessage() == null
                    ? "Mapping could not be saved. Your edits are still here."
                    : ex.getMessage());
        } finally {
            mappingSaveInFlight = false;
            if (mappingEditor != null && mappingEditor.isVisible()) {
                mappingEditor.setBusy(false);
            }
        }
    }

    private static Label mappingValueLabel() {
        Label label = new Label("Not mapped");
        label.getStyleClass().add("map-route-mapping-value");
        label.setWrapText(true);
        return label;
    }

    private static VBox mappingRow(String caption, Label value) {
        Label name = new Label(caption);
        name.getStyleClass().add("map-route-detail-name");
        return new VBox(1, name, value);
    }

    /**
     * Pushes the route canvas viewport into Leaflet. The canvas is the single source of truth for
     * the visible geographic extent, and it only has one before a route exists.
     */
    private void syncBasemapViewport() {
        if (basemapView == null || mapCanvas == null) {
            return;
        }
        if (route != null && !route.isEmpty()) {
            basemapView.syncViewport(mapCanvas.viewport());
        }
        applyRouteCanvasBackground(basemapView.mode() != MapBasemapMode.NEUTRAL);
    }

    /**
     * The route canvas must clear rather than fill when a live basemap is behind it. Swapping the
     * provider triggers a redraw, so an unchanged mode must not reinstall it.
     */
    private void applyRouteCanvasBackground(boolean transparent) {
        if (transparent == routeCanvasTransparent) {
            return;
        }
        routeCanvasTransparent = transparent;
        mapCanvas.setBackground(transparent ? new TransparentMapBackground() : new NeutralMapBackground());
    }

    private void resolveAndApplyBasemap() {
        if (basemapView == null) {
            return;
        }
        if (MapTileProbe.grid()) {
            return;
        }
        if (MapTileProbe.leafletLocalTiles() && mapAssetServer != null) {
            applyRouteCanvasBackground(true);
            basemapView.applyOnline(new MapTileProvider() {
                @Override public String displayName() { return "local-probe"; }
                @Override public String tileUrlTemplate() { return mapAssetServer.probeTileTemplate(); }
                @Override public String attributionHtml() { return "synthetic probe tiles"; }
                @Override public int maxZoom() { return 19; }
            });
            syncBasemapViewport();
            basemapView.scheduleInvalidateSize("tile-probe-local");
            return;
        }
        boolean hasRoute = route != null && !route.isEmpty();
        double south = hasRoute ? route.model().minLatitude() : -85;
        double west = hasRoute ? route.model().minLongitude() : -180;
        double north = hasRoute ? route.model().maxLatitude() : 85;
        double east = hasRoute ? route.model().maxLongitude() : 180;
        MapProviderSelection selection;
        if (basemapView.onlineTilesFailed()) {
            String failedId = activeBasemap != null && activeBasemap.onlineProvider() != null
                    ? activeBasemap.onlineProvider().providerId()
                    : null;
            selection = mapProviderResolver.afterOnlineFailure(failedId, south, west, north, east);
        } else {
            selection = mapProviderResolver.resolve(true, hasRoute, south, west, north, east);
        }
        applyBasemapSelection(selection);
    }

    private void applyBasemapSelection(MapProviderSelection selection) {
        if (basemapView == null || selection == null) {
            return;
        }
        activeBasemap = selection;
        switch (selection.mode()) {
            case ONLINE -> {
                MapTileProvider provider = selection.onlineProvider();
                if (provider == null) {
                    basemapView.applyNeutral(MapBasemapUserMessages.MAP_UNAVAILABLE);
                    applyRouteCanvasBackground(false);
                    break;
                }
                applyRouteCanvasBackground(true);
                basemapView.applyOnline(provider);
            }
            case OFFLINE_PMTILES -> {
                OfflineMapPack pack = selection.offlinePack();
                if (pack == null || mapAssetServer == null) {
                    basemapView.applyNeutral("Offline map not available for this area.");
                    applyRouteCanvasBackground(false);
                    break;
                }
                String url = mapAssetServer.registerPack(pack);
                applyRouteCanvasBackground(true);
                basemapView.applyOfflinePmtiles(url, pack.attribution(), pack.maxZoomOrDefault());
            }
            case NEUTRAL -> {
                String message = selection.reason() == null || selection.reason().isBlank()
                        ? "Map background could not be loaded."
                        : selection.reason();
                basemapView.applyNeutral(message);
                applyRouteCanvasBackground(false);
            }
        }
        syncBasemapViewport();
        basemapView.scheduleInvalidateSize("basemap-mode");
    }

    /**
     * ONLINE → matching OFFLINE PMTiles → NEUTRAL. The route overlay is untouched by the fallback,
     * so GPS stays on screen whatever the basemap does.
     */
    private void onBasemapModeFailed(MapBasemapMode failed) {
        if (MapTileProbe.suppressFallback()) {
            return;
        }
        if (failed == MapBasemapMode.ONLINE) {
            boolean hasRoute = route != null && !route.isEmpty();
            double south = hasRoute ? route.model().minLatitude() : -85;
            double west = hasRoute ? route.model().minLongitude() : -180;
            double north = hasRoute ? route.model().maxLatitude() : 85;
            double east = hasRoute ? route.model().maxLongitude() : 180;
            String failedId = activeBasemap != null && activeBasemap.onlineProvider() != null
                    ? activeBasemap.onlineProvider().providerId()
                    : null;
            applyBasemapSelection(mapProviderResolver.afterOnlineFailure(
                    failedId, south, west, north, east));
        } else if (failed == MapBasemapMode.OFFLINE_PMTILES) {
            boolean hasRoute = route != null && !route.isEmpty();
            double south = hasRoute ? route.model().minLatitude() : -85;
            double west = hasRoute ? route.model().minLongitude() : -180;
            double north = hasRoute ? route.model().maxLatitude() : 85;
            double east = hasRoute ? route.model().maxLongitude() : 180;
            applyBasemapSelection(mapProviderResolver.afterOfflineFailure(south, west, north, east));
        } else {
            // Leaflet itself never became usable, so there is no backdrop to be transparent over:
            // the canvas has to paint its own background or the route would vanish with the tiles.
            applyRouteCanvasBackground(false);
        }
        refreshBasemapStatus();
    }

    /** Keeps the status line honest about whether tiles actually arrived. */
    private void refreshBasemapStatus() {
        if (!uiBuilt || basemapView == null || statusLabel == null) {
            return;
        }
        if (route == null || route.isEmpty()) {
            return;
        }
        switch (basemapView.tileHealth()) {
            case TILES_LOADING, LEAFLET_READY -> {
                setStatusFallbackStyle(false);
                statusLabel.setText(MapBasemapUserMessages.LOADING);
            }
            case TILES_PARTIALLY_LOADED -> {
                setStatusFallbackStyle(false);
                statusLabel.setText(basemapView.tileLoadCompleted()
                        ? MapBasemapUserMessages.MAP_UNAVAILABLE
                        : MapBasemapUserMessages.LOADING);
            }
            case TILE_PROVIDER_FAILED -> {
                setStatusFallbackStyle(false);
                statusLabel.setText(MapBasemapUserMessages.MAP_UNAVAILABLE);
            }
            case NEUTRAL_FALLBACK -> {
                setStatusFallbackStyle(false);
                statusLabel.setText(neutralStatusText());
            }
            case TILES_HEALTHY -> applyRouteStatusText();
        }
    }

    private String neutralStatusText() {
        if (activeBasemap != null && activeBasemap.standardFallback()) {
            return MapBasemapUserMessages.STANDARD_FALLBACK;
        }
        String reason = activeBasemap == null ? "" : activeBasemap.reason();
        if (MapBasemapUserMessages.MAP_UNAVAILABLE.equals(reason)
                || MapBasemapUserMessages.STANDARD_FALLBACK.equals(reason)) {
            return reason;
        }
        return MapBasemapUserMessages.MAP_UNAVAILABLE;
    }

    /** Support/development snapshot of the basemap pipeline plus route size. Not shown in the UI. */
    public String mapDiagnostics() {
        if (basemapView == null) {
            return "basemap=not-initialized routeSamples=" + route.model().size();
        }
        int canvasW = mapCanvas == null ? 0 : (int) Math.round(mapCanvas.getWidth());
        int canvasH = mapCanvas == null ? 0 : (int) Math.round(mapCanvas.getHeight());
        int paneW = (int) Math.round(getWidth());
        int paneH = (int) Math.round(getHeight());
        return basemapView.diagnostics().describe()
                + " overlayProbe=" + overlayProbeMode()
                + " routeCanvasVisible=" + (mapCanvas != null && mapCanvas.isVisible())
                + " pane=" + paneW + "x" + paneH
                + " routeCanvas=" + canvasW + "x" + canvasH
                + " visible=" + isVisible()
                + " scene=" + (getScene() != null)
                + " routeSamples=" + route.model().size();
    }

    private static String overlayProbeMode() {
        if (OVERLAY_PROBE == null || OVERLAY_PROBE.isBlank()) {
            return "";
        }
        return OVERLAY_PROBE.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Temporary A/B for WebView compositing. Production leaves {@code stellar.map.overlayProbe}
     * unset, so this is a no-op.
     */
    private void applyOverlayProbe() {
        String probe = overlayProbeMode();
        if (probe.isEmpty() || !uiBuilt) {
            if (MapTileProbe.grid() && uiBuilt) {
                hideOverlaysForTileGridProbe();
            }
            return;
        }
        boolean webViewOnly = "none".equals(probe);
        boolean routeOnly = "route".equals(probe);
        if (!webViewOnly && !routeOnly) {
            return;
        }
        if (mapCanvas != null) {
            mapCanvas.setVisible(routeOnly);
            mapCanvas.setMouseTransparent(webViewOnly);
        }
        if (emptyState != null) {
            emptyState.setVisible(false);
            emptyState.setManaged(false);
        }
        if (loadingState != null) {
            loadingState.setVisible(false);
            loadingState.setManaged(false);
        }
        if (mappingEditor != null) {
            mappingEditor.hideOverlay();
        }
        if (basemapView != null) {
            basemapView.suppressJavaFxOverlayChrome(true);
        }
        dumpOverlayProbe("apply");
    }

    private void hideOverlaysForTileGridProbe() {
        if (mapCanvas != null) {
            mapCanvas.setVisible(false);
            mapCanvas.setMouseTransparent(true);
        }
        if (emptyState != null) {
            emptyState.setVisible(false);
            emptyState.setManaged(false);
        }
        if (loadingState != null) {
            loadingState.setVisible(false);
            loadingState.setManaged(false);
        }
        if (mappingEditor != null) {
            mappingEditor.hideOverlay();
        }
        if (basemapView != null) {
            basemapView.suppressJavaFxOverlayChrome(true);
        }
    }

    private void scheduleOverlayProbeDump() {
        if (overlayProbeMode().isEmpty()) {
            return;
        }
        PauseTransition later = new PauseTransition(Duration.seconds(3));
        later.setOnFinished(e -> dumpOverlayProbe("t+3s"));
        later.play();
        PauseTransition settled = new PauseTransition(Duration.seconds(8));
        settled.setOnFinished(e -> dumpOverlayProbe("t+8s"));
        settled.play();
    }

    private void dumpOverlayProbe(String when) {
        if (overlayProbeMode().isEmpty()) {
            return;
        }
        String line = Instant.now() + " " + when + " " + mapDiagnostics();
        LOG.warning("OVERLAY-PROBE " + line);
        try {
            if (!com.stellar.platform.storage.analysis.AnalysisStorageRoots.isConfigured()) {
                return;
            }
            Path dir = com.stellar.platform.storage.analysis.AnalysisStorageRoots.logsRoot();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("overlay-probe.log"), line + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ex) {
            LOG.fine(() -> "overlay probe log write failed: " + ex.getMessage());
        }
    }

    private static VBox card(VBox content) {
        content.getStyleClass().add("map-route-card");
        return content;
    }

    // ------------------------------------------------------------------
    // Route lifecycle
    // ------------------------------------------------------------------

    private void applyPreparedRoute(MapRoute prepared) {
        projectRoute = ProjectRoute.empty();
        selectedSegment = null;
        route = prepared;
        routeResolved = true;
        selectedIndex = -1;
        indexA = -1;
        indexB = -1;
        lastAnalyzedStart = Long.MIN_VALUE;
        lastAnalyzedEnd = Long.MIN_VALUE;
        mapCanvas.setRoute(prepared, activeMeasurementName);
        refreshEventMarkers();
        timeline.clearSelection();

        hideLoading();
        refreshMappingCard();
        if (prepared.isEmpty()) {
            applyEmptyGpsState();
            resolveAndApplyBasemap();
            refreshDetails();
            notifyRouteReady();
            return;
        }
        hideEmptyState();
        MapRouteModel model = prepared.model();
        timeline.setRange(model.firstTimestampNanos(), model.lastTimestampNanos());
        mapCanvas.fitRoute();
        resolveAndApplyBasemap();
        syncBasemapViewport();
        applyRouteStatusText();
        refreshBasemapStatus();
        if (pendingRestore != null) {
            MapRouteState replay = pendingRestore;
            pendingRestore = null;
            applySelectionFromState(replay);
        } else {
            applyFullDrive(true);
        }
        applyOverlayProbe();
        scheduleOverlayProbeDump();
        notifyRouteReady();
    }

    private void applyPreparedProjectRoute(ProjectRoute assembled) {
        applyPreparedProjectRoute(assembled, chronologyStatus);
    }

    private void applyPreparedProjectRoute(ProjectRoute assembled, String chronology) {
        chronologyStatus = chronology == null ? "" : chronology;
        long applyStarted = System.nanoTime();
        projectRoute = assembled == null ? ProjectRoute.empty() : assembled;
        routeResolved = true;
        selectedSegment = null;
        List<RouteSegment> gps = projectRoute.gpsSegments();
        route = gps.isEmpty() ? MapRoute.emptyRoute() : gps.getFirst().route();
        selectedIndex = -1;
        indexA = -1;
        indexB = -1;
        lastAnalyzedStart = Long.MIN_VALUE;
        lastAnalyzedEnd = Long.MIN_VALUE;
        // Full Drive on the first segment only clears a leftover A→B selection.
        // Distance for All Measurements is the project total, not this segment.
        if (!route.isEmpty()) {
            MapRouteModel first = route.model();
            segment.applyFullDrive(0, first.size() - 1, first.firstTimestampNanos(), first.lastTimestampNanos());
        }
        if (mapCanvas != null) {
            mapCanvas.setSegments(gps);
        }
        routeIsolated = false;
        refreshEventMarkers();
        timeline.clearSelection();
        hideLoading();
        refreshMappingCard();
        if (gps.isEmpty()) {
            applyEmptyGpsState();
            if (emptyAction != null) {
                emptyAction.setText("Configure GPS");
            }
            resolveAndApplyBasemap();
            refreshDetails();
            notifyRouteReady();
            return;
        }
        hideEmptyState();
        if (mapCanvas != null) {
            mapCanvas.fitRoute();
        }
        resolveAndApplyBasemap();
        syncBasemapViewport();
        applyRouteStatusText();
        refreshBasemapStatus();
        applyOverlayProbe();
        scheduleOverlayProbeDump();
        com.stellar.platform.processing.time.MeasurementCursor cursor = sharedSelection.measurementCursor();
        if (cursor != null && cursor.hasMeasurement()) {
            applyMeasurementCursor(cursor);
        }
        LOG.info(() -> "project-route-applied routes=" + gps.size()
                + " fxApplyMs=" + ((System.nanoTime() - applyStarted) / 1_000_000L)
                + " routePaintMs=" + (mapCanvas == null ? 0 : mapCanvas.lastRedrawMillis()));
        refreshDetails();
        notifyRouteReady();
    }

    public void showScopeUnavailable(String sourceId, String fileName) {
        ensureUiBuilt();
        generation++;
        activeSignature = "";
        activeRequest = null;
        mappingSourceId = sourceId == null ? "" : sourceId;
        mappingCatalog = List.of();
        hideLoading();
        String detail = "sourceId=" + (sourceId == null || sourceId.isBlank() ? "absent" : sourceId)
                + " fileName=" + (fileName == null || fileName.isBlank() ? "absent" : fileName);
        showEmptyState(
                "Location data could not be resolved for the active measurement.",
                detail);
        statusLabel.setText("No GPS route · signal analysis is unaffected");
    }

    private void showEmptyState(String headline, String detail) {
        if (emptyAction != null) {
            emptyAction.setVisible(false);
            emptyAction.setManaged(false);
        }
        showEmptyState(headline, detail, "");
    }

    private void showEmptyState(String headline, String detail, String diagnostic) {
        if (emptyHeadline != null) {
            emptyHeadline.setText(headline == null ? "GPS route unavailable" : headline);
        }
        if (emptyDetail != null) {
            emptyDetail.setText(detail == null ? "" : detail);
        }
        if (emptyDiagnostic != null) {
            boolean show = diagnostic != null && !diagnostic.isBlank();
            emptyDiagnostic.setText(show ? diagnostic : "");
            emptyDiagnostic.setVisible(show);
            emptyDiagnostic.setManaged(show);
        }
        emptyState.setVisible(true);
        emptyState.setManaged(true);
        applyOverlayProbe();
    }

    private void applyEmptyGpsState() {
        String headline = "GPS setup required";
        String detail = "Stellar could not confidently identify the location signals.";
        String action = "Configure GPS";
        String diagnostic = "";
        if (persistedMissing) {
            detail = persistedMissingMessage.isBlank()
                    ? "Previously mapped GPS signal is no longer available."
                    : persistedMissingMessage;
        } else if (gpsRoles.get(GpsSignalRole.LATITUDE) != null
                && gpsRoles.get(GpsSignalRole.LONGITUDE) == null) {
            detail = "Longitude signal could not be identified.";
            action = "Configure GPS";
        } else if (gpsRoles.get(GpsSignalRole.LONGITUDE) != null
                && gpsRoles.get(GpsSignalRole.LATITUDE) == null) {
            detail = "Latitude signal could not be identified.";
            action = "Configure GPS";
        } else if (GpsSignalRoleResolver.hasRoute(gpsRoles)) {
            headline = "No valid GPS coordinates";
            detail = "No valid GPS coordinates are available for the current selection.";
            action = "Configure GPS";
        } else if (detection != null && detection.summary() != null && !detection.summary().isBlank()
                && !detection.hasReliableRoute()) {
            if (detection.latitudeCandidates() > 0 && detection.longitudeCandidates() == 0) {
                detail = "Longitude signal could not be identified.";
                action = "Configure GPS";
            } else if (detection.latitudeCandidates() == 0 && detection.longitudeCandidates() > 0) {
                detail = "Latitude signal could not be identified.";
                action = "Configure GPS";
            }
        }
        if (detection != null && detection.scannedSignals() > 0) {
            diagnostic = "Scanned " + detection.scannedSignals() + " signals"
                    + " · lat candidates=" + detection.latitudeCandidates()
                    + " · lon candidates=" + detection.longitudeCandidates()
                    + " · confidence=" + detection.confidence();
        }
        if (emptyAction != null) {
            emptyAction.setText(action);
            emptyAction.setVisible(true);
            emptyAction.setManaged(true);
        }
        showEmptyState(headline, detail, diagnostic);
        statusLabel.setText("No GPS route · signal analysis is unaffected");
    }

    /**
     * Applies the stored mapping policy once per measurement and caches the outcome, so repeated
     * activations are free. {@link #invalidateResolvedMapping()} drops the cache when Configuration
     * changes the mapping.
     */
    private Map<GpsSignalRole, SignalCatalogKey> resolveGpsOverrides(MapRouteRequest request) {
        EnumMap<GpsSignalRole, SignalCatalogKey> merged = new EnumMap<>(GpsSignalRole.class);
        if (!resolvedOverrides.isEmpty()) {
            merged.putAll(resolvedOverrides);
            persistedMissing = false;
            return merged;
        }
        GpsMappingPolicy.Resolution policy = GpsMappingPolicy.resolve(
                mappingSourceId,
                schemaFingerprint,
                mappingCatalog,
                GpsMappingScope.mappingsForMeasurement(
                        mappingSourceId,
                        activeRequest == null ? "" : activeRequest.fileName(),
                        projectMappings),
                defaultMappings);
        persistedMissing = policy.persistedMissing();
        persistedMissingMessage = policy.statusMessage();
        droppedRolesMessage = policy.optionalRolesDropped() ? policy.statusMessage() : "";
        mappingOrigin = policy.origin();
        if (policy.hasRouteOverrides()) {
            merged.putAll(policy.overrides());
            resolvedOverrides.putAll(policy.overrides());
            resolvedUserConfirmed = policy.origin() == GpsMappingPolicy.Origin.USER_CONFIRMED
                    || policy.origin() == GpsMappingPolicy.Origin.SCHEMA_REUSED
                    || policy.origin() == GpsMappingPolicy.Origin.PRODUCT_DEFAULT;
        }
        return merged;
    }

    /** Reflects the roles the route was actually built from; Configuration owns editing. */
    private void refreshMappingCard() {
        if (mappingLatitudeValue == null) {
            return;
        }
        if (activeRequest != null && activeRequest.projectWide()) {
            mappingMeasurementValue.setText("All Measurements");
            mappingLatitudeValue.setText(projectRoute.measurementCount() + " total · "
                    + projectRoute.gpsCapableCount() + " GPS-capable");
            mappingLongitudeValue.setText(projectRoute.routeSegmentCount() + " route segments");
            mappingAltitudeValue.setText(String.format("%,d GPS samples · %s",
                    projectRoute.gpsSampleCount(),
                    formatAggregateKilometers(projectRoute.totalDistanceMeters())));
            mappingStatusValue.setText(projectMappingStatusText());
            mappingEditButton.setText("Configure GPS");
            mappingEditButton.setDisable(false);
            return;
        }
        mappingEditButton.setDisable(false);
        SignalCatalogKey lat = resolvedRole(GpsSignalRole.LATITUDE);
        SignalCatalogKey lon = resolvedRole(GpsSignalRole.LONGITUDE);
        SignalCatalogKey alt = resolvedRole(GpsSignalRole.ALTITUDE);
        mappingMeasurementValue.setText(!activeMeasurementName.isBlank()
                ? activeMeasurementName
                : (mappingSourceId.isBlank() ? "Active measurement" : mappingSourceId));
        mappingLatitudeValue.setText(signalNameOf(lat));
        mappingLongitudeValue.setText(signalNameOf(lon));
        mappingAltitudeValue.setText(signalNameOf(alt));
        boolean routable = lat != null && lon != null;
        mappingStatusValue.setText(mappingStatusText(routable));
        mappingEditButton.setText(routable ? "Change Mapping" : "Configure GPS");
    }

    private String projectMappingStatusText() {
        int total = projectRoute.segments().size();
        if (total == 0) {
            return "Independent segments · no file-to-file connections";
        }
        long gps = projectRoute.segments().stream().filter(RouteSegment::hasRoute).count();
        String status = compactProjectGpsStatus(total, gps);
        if (chronologyStatus != null && !chronologyStatus.isBlank()) {
            status = status + "\n" + chronologyStatus;
        }
        return status;
    }

    public static String compactProjectGpsStatus(int segmentCount, long gpsCapable) {
        if (segmentCount <= 0) {
            return "Independent segments · no file-to-file connections";
        }
        String status = gpsCapable == segmentCount
                ? "GPS detected"
                : gpsCapable + " of " + segmentCount + " GPS-capable";
        return status + "\nIndependent segments · no file-to-file connections";
    }

    private void refreshEventMarkers() {
        if (mapCanvas == null) {
            return;
        }
        if (eventTimestamps.length == 0) {
            mapCanvas.setEventIndices(new int[0]);
            return;
        }
        double[] latitudes = new double[eventTimestamps.length];
        double[] longitudes = new double[eventTimestamps.length];
        String[] labels = new String[eventTimestamps.length];
        int n = 0;
        for (int position = 0; position < eventTimestamps.length; position++) {
            long timestamp = eventTimestamps[position];
            String sourceId = position < eventSourceIds.length ? eventSourceIds[position] : "";
            String sourceFile = position < eventSourceFiles.length ? eventSourceFiles[position] : "";
            SamplePin pin = pinFor(timestamp, sourceId, sourceFile);
            if (pin == null) {
                continue;
            }
            latitudes[n] = pin.latitude();
            longitudes[n] = pin.longitude();
            labels[n] = position < eventMarkerLabels.length ? eventMarkerLabels[position] : "";
            n++;
        }
        mapCanvas.setEventPositions(
                n == latitudes.length ? latitudes : java.util.Arrays.copyOf(latitudes, n),
                n == longitudes.length ? longitudes : java.util.Arrays.copyOf(longitudes, n),
                n == labels.length ? labels : java.util.Arrays.copyOf(labels, n));
    }

    private SamplePin pinFor(long timestamp, String sourceId, String sourceFile) {
        if (projectRoute != null) {
            for (com.stellar.platform.processing.map.RouteSegment segment : projectRoute.gpsSegments()) {
                if (!sourceId.isBlank() || !com.stellar.platform.desktop.presentation.GraphEventGps.fileName(sourceFile).isBlank()) {
                    if (!segmentMatches(segment, sourceId, sourceFile)) {
                        continue;
                    }
                }
                SamplePin pin = pinOn(segment.route().model(), timestamp);
                if (pin != null) {
                    return pin;
                }
            }
        }
        return pinOn(model(), timestamp);
    }

    private MapRouteModel model() {
        return route == null ? MapRouteModel.empty() : route.model();
    }

    private static SamplePin pinOn(MapRouteModel model, long timestamp) {
        int index = com.stellar.platform.desktop.presentation.GraphEventGps.exactIndex(model, timestamp);
        if (index < 0) {
            return null;
        }
        return new SamplePin(model.latitude(index), model.longitude(index));
    }

    private record SamplePin(double latitude, double longitude) {
    }

    private SignalCatalogKey resolvedRole(GpsSignalRole role) {
        return firstNonNull(resolvedOverrides.get(role), gpsRoles.get(role));
    }

    private String mappingStatusText(boolean routable) {
        if (persistedMissing) {
            return "Mapped signal no longer exists";
        }
        if (!routable) {
            return "GPS setup required";
        }
        String status = switch (mappingOrigin) {
            case USER_CONFIRMED -> "Location configured";
            case SCHEMA_REUSED -> "Reused from a compatible measurement";
            case PRODUCT_DEFAULT -> "Using the product default";
            case AUTO, UNRESOLVED -> "Location detected";
        };
        // A dropped optional role is worth saying out loud; it never invalidates the route.
        return droppedRolesMessage.isBlank() ? status : status + " · " + droppedRolesMessage;
    }

    private static String signalNameOf(SignalCatalogKey key) {
        return key == null ? "Not mapped" : key.signalName();
    }

    private void applyRouteStatusText() {
        if (activeRequest != null && activeRequest.projectWide()) {
            setStatusFallbackStyle(false);
            String text = String.format(
                    "All Measurements · %d measurements · %d GPS-capable · %d segments · %,d GPS samples · %s",
                    projectRoute.measurementCount(),
                    projectRoute.gpsCapableCount(),
                    projectRoute.routeSegmentCount(),
                    projectRoute.gpsSampleCount(),
                    formatAggregateKilometers(projectRoute.totalDistanceMeters()));
            if (chronologyStatus != null && !chronologyStatus.isBlank()) {
                text = text + "\n" + chronologyStatus;
            }
            statusLabel.setText(text);
            return;
        }
        MapRouteModel model = route.model();
        if (model.isEmpty()) {
            return;
        }
        if (activeBasemap != null && activeBasemap.standardFallback()) {
            setStatusFallbackStyle(true);
            statusLabel.setText(MapBasemapUserMessages.STANDARD_FALLBACK);
            statusLabel.setTooltip(new Tooltip(MapBasemapUserMessages.STANDARD_FALLBACK_HINT));
            return;
        }
        setStatusFallbackStyle(false);
        String backdrop = activeBasemap == null || activeBasemap.englishCompliant()
                ? "map"
                : activeBasemap.reason();
        SignalCatalogKey lat = resolvedRole(GpsSignalRole.LATITUDE);
        SignalCatalogKey lon = resolvedRole(GpsSignalRole.LONGITUDE);
        String pair = lat != null && lon != null
                ? lat.signalName() + " / " + lon.signalName()
                : "GPS";
        String altitudeNote = gpsRoles.get(GpsSignalRole.ALTITUDE) == null
                ? " · Altitude signal not detected." : "";
        String originNote = mappingOrigin == GpsMappingPolicy.Origin.USER_CONFIRMED
                || mappingOrigin == GpsMappingPolicy.Origin.SCHEMA_REUSED
                ? " · Restored mapping" : "";
        // A recording with no extent is valid data presented honestly, not a route to inspect.
        RouteMovement movement = RouteMovement.classify(model);
        if (!movement.isNormal()) {
            statusLabel.setText(String.format(
                    "%s · %,d GPS samples · %s · %s%s",
                    movement.message(), model.size(), pair, backdrop, originNote));
            return;
        }
        statusLabel.setText(String.format(
                "%,d GPS samples · %.2f km · %s · %s%s%s · click the route to inspect a location",
                model.size(), model.totalDistanceMeters() / 1000.0, pair, backdrop, altitudeNote, originNote));
    }

    private void setStatusFallbackStyle(boolean fallback) {
        if (statusLabel == null) {
            return;
        }
        statusLabel.getStyleClass().remove("map-route-status-fallback");
        if (fallback) {
            statusLabel.getStyleClass().add("map-route-status-fallback");
        } else {
            statusLabel.setTooltip(null);
        }
    }

    private void onBasemapReady() {
        syncBasemapViewport();
        refreshBasemapStatus();
    }

    /**
     * The overlay's zoom ceiling belongs to whichever provider is active, so it has to follow
     * provider changes and fallbacks rather than being fixed once at construction.
     */
    private void onBasemapMaxZoomChanged(int maxZoom) {
        if (mapCanvas == null) {
            return;
        }
        mapCanvas.setProviderMaxZoom(maxZoom);
        syncBasemapViewport();
    }

    /**
     * Drops resolution state so the next activation re-derives roles from the current mappings.
     * The route cache is keyed by roles, so a changed mapping can never return a cached route.
     */
    private void invalidateResolvedMapping() {
        resolvedOverrides.clear();
        resolvedUserConfirmed = false;
        persistedMissing = false;
        persistedMissingMessage = "";
        droppedRolesMessage = "";
        mappingOrigin = GpsMappingPolicy.Origin.UNRESOLVED;
        gpsDiscovery.invalidate();
        if (routeService != null) {
            routeService.clear();
        }
        forceRefresh = true;
    }

    private static SignalCatalogKey firstNonNull(SignalCatalogKey a, SignalCatalogKey b) {
        return a != null ? a : b;
    }

    private void hideEmptyState() {
        emptyState.setVisible(false);
        emptyState.setManaged(false);
        applyOverlayProbe();
        notifyLayoutChanged("empty-hidden");
    }

    private void showLoading(String message) {
        if (loadingLabel != null) {
            loadingLabel.setText(message == null ? "Loading GPS data..." : message);
        }
        if (loadingState != null) {
            loadingState.setVisible(true);
            loadingState.setManaged(true);
        }
        if (emptyState != null) {
            emptyState.setVisible(false);
            emptyState.setManaged(false);
        }
        applyOverlayProbe();
    }

    private void hideLoading() {
        boolean wasShowing = loadingState != null && loadingState.isVisible();
        if (loadingState != null) {
            loadingState.setVisible(false);
            loadingState.setManaged(false);
        }
        if (wasShowing) {
            applyOverlayProbe();
            notifyLayoutChanged("loading-hidden");
        }
    }

    // ------------------------------------------------------------------
    // Mode + selection
    // ------------------------------------------------------------------

    private void applyMode(MapSelectionMode target, boolean userInitiated) {
        mode = target;
        if (singlePointButton != null) {
            selectedButton(target).setSelected(true);
        }
        mapCanvas.setMode(target);
        timeline.setMode(target);

        if (target == MapSelectionMode.ALL_ROUTE) {
            applyFullDrive(userInitiated);
            return;
        }
        if (target == MapSelectionMode.A_B_RANGE) {
            segment.enterSelectMode();
        } else {
            segment.exitSelectMode();
            if (segment.complete()) {
                indexA = segment.markerAIndex();
                indexB = segment.markerBIndex();
                if (mapCanvas != null) {
                    mapCanvas.setRangeIndices(indexA, indexB);
                    mapCanvas.setMode(segment.fullDrive() ? MapSelectionMode.ALL_ROUTE : MapSelectionMode.A_B_RANGE);
                }
            }
        }
        refreshDetails();
        if (userInitiated) {
            statusLabel.setText(switch (target) {
                case SINGLE_POINT -> "Selected Point · click the route to inspect one timestamp";
                case A_B_RANGE -> "Select Segment · first click sets A, second click sets B";
                case ALL_ROUTE -> "Full Drive · Start → End is the active analysis segment";
            });
        }
    }

    private ToggleButton selectedButton(MapSelectionMode target) {
        return switch (target) {
            case SINGLE_POINT -> singlePointButton;
            case A_B_RANGE -> rangeButton;
            case ALL_ROUTE -> allRouteButton;
        };
    }

    /** Restores every independent GPS polyline and one start plus one end for that display order. */
    public void showAllRoutes() {
        if (projectRoute == null || mapCanvas == null) {
            return;
        }
        List<RouteSegment> gps = projectRoute.gpsSegments();
        routeIsolated = false;
        selectedSegment = null;
        mapCanvas.setSegments(gps);
        mapCanvas.fitRoute();
        applyRouteStatusText();
        syncBasemapViewport();
    }

    /** One recording: its own route, and START/END only when it has a real journey. */
    public void showOnly(RouteSegment segment) {
        if (segment == null || !segment.hasRoute() || mapCanvas == null) {
            return;
        }
        routeIsolated = true;
        selectedSegment = segment;
        route = segment.route();
        activeMeasurementName = segment.displayName();
        mappingSourceId = segment.measurementId();
        mapCanvas.setRoute(segment.route(), segment.displayName());
        mapCanvas.fitRoute();
        MapRouteModel model = segment.route().model();
        if (statusLabel != null) {
            String ends = model.size() <= 1
                    ? "one GPS sample"
                    : MapTimeFormat.withMillis(model.firstTimestampNanos())
                    + " – " + MapTimeFormat.withMillis(model.lastTimestampNanos());
            statusLabel.setText(segment.displayName() + " · " + model.size() + " GPS samples · " + ends);
        }
        syncBasemapViewport();
    }

    private void onSegmentPicked(RouteSegment segment, int index) {
        if (segment == null || !segment.hasRoute()) {
            return;
        }
        selectedSegment = segment;
        route = segment.route();
        mappingSourceId = segment.measurementId();
        activeMeasurementName = segment.displayName();
        mappingCatalog = GpsMappingScope.catalogFor(segment.measurementId(), catalogSnapshot);
        persistedSpeed = mappingPersistence.loadSpeedMapping(mappingSourceId);
        if (mapCanvas != null) {
            mapCanvas.showSegmentSample(segment, index);
        }
        onPointPicked(index);
    }

    private void onPointPicked(int index) {
        if (route.isEmpty()) {
            return;
        }
        MapRouteModel model = route.model();
        if (index < 0 || index >= model.size()) {
            return;
        }
        logGpsSample("route click", index);
        DriveSegmentSelection.ClickResult result = segment.clickNearestSample(index, model.timestampNanos(index));
        switch (result) {
            case A_COMMITTED -> {
                awaitingDifferentB = false;
                paintPendingA();
                statusLabel.setText("Point A set · click a different route sample to set B");
                refreshDetails();
            }
            case WAITING_FOR_DIFFERENT_B -> {
                awaitingDifferentB = true;
                statusLabel.setText("Select a different end point");
                refreshDetails();
            }
            case SEGMENT_COMMITTED -> {
                awaitingDifferentB = false;
                syncSegmentIndices(true);
                statusLabel.setText("Segment A → B set · analysis views share this interval");
            }
            case POINT_ONLY, IGNORED -> {
            }
        }
        selectIndex(index, true);
    }

    private void paintPendingA() {
        indexA = segment.markerAIndex();
        indexB = -1;
        if (mapCanvas != null) {
            mapCanvas.setRangeIndices(indexA, -1);
        }
        if (indexA >= 0) {
            timeline.setRangeSelection(route.model().timestampNanos(indexA), null);
        }
    }

    private void selectIndex(int index, boolean publish) {
        MapRouteModel model = route.model();
        if (index < 0 || index >= model.size()) {
            return;
        }
        selectedIndex = index;
        mapCanvas.setSelectedIndex(index);
        timeline.setPoint(model.timestampNanos(index));
        if (publish && autoSync) {
            gpsUnavailableMessage = null;
            String measurementId = selectedSegment == null ? "" : selectedSegment.measurementId();
            sharedSelection.selectMeasurementPoint(
                    measurementId, model.timestampNanos(index), null, false, true, TimeSelection.Origin.MAP);
        }
        if (mapCanvas != null) {
            mapCanvas.revealSelected();
        }
        refreshDetails();
    }

    private void applyFullDrive(boolean publish) {
        MapRouteModel model = route.model();
        if (model.isEmpty()) {
            return;
        }
        mode = MapSelectionMode.ALL_ROUTE;
        segment.applyFullDrive(0, model.size() - 1, model.firstTimestampNanos(), model.lastTimestampNanos());
        awaitingDifferentB = false;
        if (singlePointButton != null) {
            selectedButton(mode).setSelected(true);
        }
        mapCanvas.setMode(mode);
        timeline.setMode(mode);
        syncSegmentIndices(publish);
        if (statusLabel != null) {
            statusLabel.setText("Full Drive · Start → End is the active analysis segment");
        }
    }

    private void syncSegmentIndices(boolean publish) {
        MapRouteModel model = route.model();
        if (!segment.complete() || model.isEmpty()) {
            return;
        }
        DriveSegmentSelection.ActiveSegment active = segment.activeSegment();
        indexA = active.startIndex();
        indexB = active.endIndex();
        mapCanvas.setRangeIndices(indexA, indexB);
        mapCanvas.setMode(segment.fullDrive() ? MapSelectionMode.ALL_ROUTE : MapSelectionMode.A_B_RANGE);
        timeline.setRangeSelection(active.startTimestampNanos(), active.endTimestampNanos());
        logCommittedSegment(active);
        if (publish && autoSync) {
            sharedSelection.selectRange(
                    active.startTimestampNanos(), active.endTimestampNanos(), TimeSelection.Origin.MAP_SEGMENT);
        }
        scheduleSegmentAnalysis();
        refreshDetails();
        stateChangedHandler.run();
    }

    private void applyRangeSelection(boolean publish) {
        if (indexA >= 0 && indexB >= 0 && indexA != indexB && !route.isEmpty()) {
            MapRouteModel model = route.model();
            segment.setA(indexA, model.timestampNanos(indexA));
            segment.setB(indexB, model.timestampNanos(indexB));
        }
        syncSegmentIndices(publish);
    }

    private void pushSelectionToSharedModel() {
        MapRouteModel model = route.model();
        if (model.isEmpty()) {
            return;
        }
        if (indexA >= 0 && indexB >= 0) {
            sharedSelection.selectRange(
                    model.timestampNanos(Math.min(indexA, indexB)),
                    model.timestampNanos(Math.max(indexA, indexB)),
                    TimeSelection.Origin.MAP_SEGMENT);
        }
        if (selectedIndex >= 0) {
            sharedSelection.selectPoint(model.timestampNanos(selectedIndex), TimeSelection.Origin.MAP);
        }
    }

    /** Waveform/timeline → map. Ignores the echo of the map's own publication. */
    private void onSharedSelectionChanged(TimeSelection selection) {
        if (!uiBuilt || selection.origin().fromMap() || !autoSync) {
            return;
        }
        com.stellar.platform.processing.time.MeasurementCursor captured = sharedSelection.measurementCursor();
        long token = captured == null ? -1L : captured.token();
        Runnable apply = () -> {
            com.stellar.platform.processing.time.MeasurementCursor latest = sharedSelection.measurementCursor();
            if (token >= 0 && (latest == null || latest.token() != token)) {
                return;
            }
            if (selection.kind() == TimeSelection.Kind.POINT && latest != null && latest.hasMeasurement()) {
                applyMeasurementCursor(latest);
                return;
            }
            if (selection.kind() == TimeSelection.Kind.POINT && sharedSelection.explicitNoOwner()) {
                clearUnownedCursor();
                return;
            }
            MapRouteModel model = route.model();
            switch (selection.kind()) {
                case POINT -> {
                    gpsUnavailableMessage = null;
                    if (model.isEmpty()) {
                        gpsUnavailableMessage = "No GPS sample available at this time";
                        selectedIndex = -1;
                        if (mapCanvas != null) {
                            mapCanvas.setSelectedIndex(-1);
                        }
                        if (statusLabel != null) {
                            statusLabel.setText(gpsUnavailableMessage);
                        }
                        refreshPointDetails();
                        return;
                    }
                    selectIndex(model.nearestIndexByTime(selection.startNanos()), false);
                    if (mapCanvas != null) {
                        mapCanvas.revealSelected();
                    }
                }
                case RANGE -> {
                    if (model.isEmpty()) {
                        return;
                    }
                    if (selection.durationNanos() <= 0
                            || selection.startNanos() == selection.endNanos()) {
                        return;
                    }
                    if (segment.phase() == DriveSegmentSelection.Phase.SEGMENT_SELECTED
                            || segment.phase() == DriveSegmentSelection.Phase.WAITING_FOR_B) {
                        return;
                    }
                    int restoredA = model.nearestIndexByTime(selection.startNanos());
                    int restoredB = model.nearestIndexByTime(selection.endNanos());
                    if (restoredA == restoredB) {
                        return;
                    }
                    segment.setA(restoredA, model.timestampNanos(restoredA));
                    if (!segment.setB(restoredB, model.timestampNanos(restoredB))) {
                        return;
                    }
                    mode = MapSelectionMode.A_B_RANGE;
                    syncSegmentIndices(false);
                }
                case NONE -> applyFullDrive(false);
            }
        };
        if (Platform.isFxApplicationThread()) {
            apply.run();
        } else {
            Platform.runLater(apply);
        }
    }

    /**
     * Shared-timeline gap. The marker goes away. Route segments stay on the map.
     */
    private void clearUnownedCursor() {
        selectedSegment = null;
        selectedIndex = -1;
        gpsUnavailableMessage = com.stellar.platform.processing.time.MeasurementCursor.NO_MEASUREMENT_AT_TIME;
        if (mapCanvas != null) {
            mapCanvas.showSegmentSample(null, -1);
        }
        if (statusLabel != null) {
            statusLabel.setText(gpsUnavailableMessage);
        }
        refreshPointDetails();
        notifyMeasurementCursorApplied();
    }

    /**
     * Graph file focus → this measurement's GPS samples only.
     * A file with no route clears the marker and does not keep the previous file's point.
     */
    private void applyMeasurementCursor(com.stellar.platform.processing.time.MeasurementCursor cursor) {
        if (cursor == null || !cursor.hasMeasurement() || projectRoute == null) {
            return;
        }
        MeasurementGpsSync.Match match = cursor.placeMarker()
                ? MeasurementGpsSync.resolve(projectRoute, cursor.measurementId(), cursor.relativeTimestampNanos())
                : focusWithoutMarker(cursor.measurementId());
        RouteSegment segment = MeasurementGpsSync.segment(projectRoute, cursor.measurementId());
        if (match.status() != MeasurementGpsSync.Status.MATCHED) {
            selectedSegment = segment != null && segment.hasRoute() ? segment : null;
            selectedIndex = -1;
            gpsUnavailableMessage = "No GPS samples in " + (match.displayName().isBlank() ? cursor.measurementId() : match.displayName());
            if (mapCanvas != null) {
                mapCanvas.showSegmentSample(null, -1);
            }
            if (statusLabel != null) {
                statusLabel.setText(gpsUnavailableMessage);
            }
            refreshPointDetails();
            notifyMeasurementCursorApplied();
            return;
        }
        if (!cursor.placeMarker()) {
            selectedSegment = segment;
            route = segment.route();
            activeMeasurementName = segment.displayName();
            selectedIndex = -1;
            gpsUnavailableMessage = null;
            if (mapCanvas != null) {
                mapCanvas.showSegmentSample(null, -1);
            }
            if (statusLabel != null) {
                statusLabel.setText(segment.displayName());
            }
            refreshPointDetails();
            notifyMeasurementCursorApplied();
            return;
        }
        selectedSegment = segment;
        route = segment.route();
        activeMeasurementName = segment.displayName();
        gpsUnavailableMessage = null;
        selectedIndex = match.sampleIndex();
        if (mapCanvas != null) {
            mapCanvas.showSegmentSample(segment, match.sampleIndex());
            mapCanvas.revealSelected();
        }
        if (timeline != null) {
            timeline.setPoint(match.sampleTimestampNanos());
        }
        if (statusLabel != null) {
            statusLabel.setText(segment.displayName() + " · " + MapTimeFormat.withMillis(match.sampleTimestampNanos()));
        }
        refreshPointDetails();
        notifyMeasurementCursorApplied();
    }

    private void notifyMeasurementCursorApplied() {
        if (measurementCursorApplied != null) {
            measurementCursorApplied.run();
        }
    }

    private MeasurementGpsSync.Match focusWithoutMarker(String measurementId) {
        RouteSegment segment = MeasurementGpsSync.segment(projectRoute, measurementId);
        if (segment == null || !segment.hasRoute()) {
            String label = segment == null ? measurementId : segment.displayName();
            return MeasurementGpsSync.Match.unmatched(MeasurementGpsSync.Status.MISSING_GPS, measurementId, label);
        }
        return new MeasurementGpsSync.Match(
                MeasurementGpsSync.Status.MATCHED, segment.measurementId(), segment.displayName(), -1, 0L, Double.NaN, Double.NaN);
    }

    private void onTimelinePoint(long timestampNanos) {
        MapRouteModel model = route.model();
        if (model.isEmpty()) {
            return;
        }
        selectIndex(model.nearestIndexByTime(timestampNanos), true);
    }

    private void onTimelineRange(long startNanos, long endNanos) {
        MapRouteModel model = route.model();
        if (model.isEmpty()) {
            return;
        }
        int restoredA = model.nearestIndexByTime(startNanos);
        int restoredB = model.nearestIndexByTime(endNanos);
        if (restoredA == restoredB || startNanos == endNanos) {
            awaitingDifferentB = true;
            statusLabel.setText("Select a different end point");
            refreshDetails();
            return;
        }
        segment.setA(restoredA, model.timestampNanos(restoredA));
        if (!segment.setB(restoredB, model.timestampNanos(restoredB))) {
            awaitingDifferentB = true;
            statusLabel.setText("Select a different end point");
            refreshDetails();
            return;
        }
        awaitingDifferentB = false;
        syncSegmentIndices(true);
    }

    private void onHover(Integer index) {
        MapRouteModel model = mapCanvas.hoverModel();
        if (model == null) {
            model = route.model();
        }
        if (index == null || model.isEmpty() || index < 0 || index >= model.size()) {
            mapCanvas.hideTooltip();
            return;
        }
        StringBuilder text = new StringBuilder(MapTimeFormat.withMillis(model.timestampNanos(index)));
        text.append("\nLatitude   ").append(String.format("%.7f", model.latitude(index)));
        text.append("\nLongitude  ").append(String.format("%.7f", model.longitude(index)));
        double altitude = model.altitude(index);
        if (!Double.isNaN(altitude)) {
            text.append("\nAltitude   ").append(String.format("%.2f m", altitude));
        }
        mapCanvas.showTooltip(text.toString(), index);
    }

    // ------------------------------------------------------------------
    // Detail panels
    // ------------------------------------------------------------------

    private void refreshDetails() {
        if (!uiBuilt) {
            return;
        }
        refreshPointDetails();
        renderSegmentSummary(segmentSummary);
        if (setAButton != null) {
            boolean hasPoint = selectedIndex >= 0;
            setAButton.setDisable(!hasPoint);
            setBButton.setDisable(!hasPoint);
        }
        stateChangedHandler.run();
    }

    private void scheduleSegmentAnalysis() {
        MapRouteModel model = route.model();
        if (model.isEmpty() || !segment.complete()) {
            return;
        }
        DriveSegmentSelection.ActiveSegment active = segment.activeSegment();
        if (active.zeroLength()) {
            return;
        }
        int fromIndex = active.startIndex();
        int toIndex = active.endIndex();
        long start = active.startTimestampNanos();
        long end = active.endTimestampNanos();
        if (start == lastAnalyzedStart && end == lastAnalyzedEnd) {
            renderSegmentSummary(segmentSummary);
            return;
        }
        lastAnalyzedStart = start;
        lastAnalyzedEnd = end;
        long my = generation;
        boolean userDefined = segment.userDefined();
        MapRoute snapshotRoute = route;
        GraphSeriesQuery snapshotQuery = query;
        List<SignalCatalogEntry> catalog = mappingCatalog.isEmpty() ? catalogSnapshot : mappingCatalog;
        List<SignalCatalogKey> selected = selectedSignals;
        int events = eventTimestamps.length;
        SpeedSourceMapping speedMapping = persistedSpeed;
        var request = new DriveSegmentAnalysisService.DriveSegmentAnalysisRequest(
                snapshotRoute.model(), fromIndex, toIndex, start, end, userDefined,
                routeService == null ? null : routeService.options(),
                snapshotQuery, catalog, selected, null, events, mappingSourceId, speedMapping);
        lastAnalysisRequest = request;
        LOG.info(() -> String.format(
                "DriveSegmentAnalysisService request start=%.3f s end=%.3f s duration=%.3f s "
                        + "indexA=%d indexB=%d selectedPoint=%s analysisRange=%s",
                start / 1e9, end / 1e9, (end - start) / 1e9,
                fromIndex, toIndex,
                selectedPointLog(),
                analysisRangeLog()));
        executor().submit(() -> {
            DriveSegmentSummary summary;
            try {
                summary = segmentAnalysis.analyze(request);
            } catch (RuntimeException ex) {
                LOG.warning(() -> "DriveSegmentAnalysisService failed; motion still uses the canonical route: "
                        + ex.getMessage());
                summary = new DriveSegmentSummary(
                        active.fullDrive(), start, end,
                        DriveMotionMetrics.compute(snapshotRoute.model(), fromIndex, toIndex, null,
                                request.options()),
                        null, null, null, events);
            }
            DriveSegmentSummary published = summary;
            Platform.runLater(() -> {
                if (my != generation
                        || fromIndex != segment.lowIndex()
                        || toIndex != segment.highIndex()
                        || start != segment.startTimestampNanos()
                        || end != segment.endTimestampNanos()) {
                    return;
                }
                segmentSummary = published;
                if (published.speed() != null && published.speed().mapping() != null) {
                    persistedSpeed = published.speed().mapping();
                    mappingPersistence.saveSpeedMapping(persistedSpeed);
                }
                renderSegmentSummary(published);
            });
        });
    }

    /** Same kilometre text the GPS & Route summary uses for a project total. */
    static String formatAggregateKilometers(double meters) {
        return String.format("%.2f km", meters / 1000.0);
    }

    private boolean projectAggregateVisible() {
        return activeRequest != null
                && activeRequest.projectWide()
                && !projectRoute.isEmpty()
                && !segment.userDefined();
    }

    /**
     * All Measurements distance is {@link ProjectRoute#totalDistanceMeters()}: the sum of
     * independent GPS segments. Start, end, and duration stay off this card so a gap between
     * files is not presented as continuous driving time.
     */
    private void renderProjectAggregateDistance() {
        segmentBadge.setText("Full Drive");
        segmentHeadline.setText("Independent segments");
        LinkedHashMap<String, String> rows = new LinkedHashMap<>();
        rows.put("Distance", formatAggregateKilometers(projectRoute.totalDistanceMeters()));
        segmentBody.getChildren().setAll(metricRows(rows));
        segmentIssueList.getChildren().clear();
        segmentIssuesLink.setVisible(false);
        segmentIssuesLink.setManaged(false);
    }

    private void renderSegmentSummary(DriveSegmentSummary summary) {
        if (segmentBody == null) {
            return;
        }
        if (projectAggregateVisible()) {
            renderProjectAggregateDistance();
            return;
        }
        if (route.isEmpty()) {
            segmentBadge.setText("No route");
            segmentHeadline.setText("Start → End");
            segmentBody.getChildren().setAll(hint("A GPS route is required for segment analysis."));
            return;
        }
        if (segment.phase() == DriveSegmentSelection.Phase.WAITING_FOR_B) {
            long aTs = segment.timestampA();
            segmentBadge.setText("A → B");
            String aLine = aTs == Long.MIN_VALUE ? "A  —" : "A  " + MapTimeFormat.withMillis(aTs);
            String hintText = awaitingDifferentB
                    ? "Select a different end point"
                    : "Click a different route sample to set B";
            segmentHeadline.setText(aLine + "\nB  —\nDuration  —");
            segmentBody.getChildren().setAll(hint(hintText));
            return;
        }
        if (segment.phase() == DriveSegmentSelection.Phase.WAITING_FOR_A) {
            segmentBadge.setText("A → B");
            segmentHeadline.setText("Click the route to set point A");
            segmentBody.getChildren().setAll(hint("First click sets A, second click sets B."));
            return;
        }
        if (!segment.complete()) {
            segmentBadge.setText("No route");
            segmentHeadline.setText("Start → End");
            segmentBody.getChildren().setAll(hint("A GPS route is required for segment analysis."));
            return;
        }
        DriveSegmentSelection.ActiveSegment active = segment.activeSegment();
        boolean full = active.fullDrive();
        segmentBadge.setText(full ? "Full Drive" : "A → B");
        String aLabel = full ? "Start" : "A";
        String bLabel = full ? "End" : "B";
        String duration = MapTimeFormat.duration(active.durationNanos());
        segmentHeadline.setText(
                aLabel + "  " + MapTimeFormat.withMillis(active.startTimestampNanos())
                        + "\n" + bLabel + "  " + MapTimeFormat.withMillis(active.endTimestampNanos())
                        + "\nDuration  " + duration);

        boolean summaryMatches = summary.startTimestampNanos() == active.startTimestampNanos()
                && summary.endTimestampNanos() == active.endTimestampNanos();
        DriveSegmentSummary shown = summaryMatches ? summary : DriveSegmentSummary.empty();
        DriveMotionMetrics motion = DriveMotionMetrics.compute(
                route.model(),
                active.startIndex(),
                active.endIndex(),
                null,
                routeService == null ? null : routeService.options());
        String distance = motion.range().distanceMeters() > 0
                ? String.format("%.1f km", motion.range().distanceKilometers())
                : "—";

        LinkedHashMap<String, String> rows = new LinkedHashMap<>();
        rows.put("Distance", distance);
        if (motion.range().averageSpeedKph() != null) {
            rows.put("Average Speed", String.format("%.1f km/h", motion.range().averageSpeedKph()));
        }
        if (motion.movingAverageSpeedKph() != null) {
            rows.put("Moving Average", String.format("%.1f km/h", motion.movingAverageSpeedKph()));
        }
        SpeedSourceResolution speed = summaryMatches ? shown.speed() : null;
        String maximumTooltip = null;
        if (speed != null && speed.available()) {
            rows.put("Maximum Speed", String.format("%.1f km/h", speed.maximumKph().getAsDouble()));
            maximumTooltip = speed.provenanceText();
        } else if (summaryMatches) {
            rows.put("Maximum Speed", "Not available");
            maximumTooltip = speed == null
                    ? "Maximum Speed is not available."
                    : speed.provenanceText();
        }
        LinkedHashMap<String, String> tooltips = new LinkedHashMap<>();
        if (maximumTooltip != null) {
            tooltips.put("Maximum Speed", maximumTooltip);
        }
        if (motion.stoppedDurationNanos() > 0) {
            rows.put("Stopped Time", MapTimeFormat.duration(motion.stoppedDurationNanos()));
        }
        if (shown.braking().present()) {
            rows.put(shown.braking().eventLabel(), String.valueOf(shown.braking().eventCount()));
            if (shown.braking().classification()
                    == com.stellar.platform.processing.drive.DriveBrakingSummary.Classification.MEASURED
                    && shown.braking().activeNanos() > 0) {
                rows.put("Brake-active time", String.format("%.1f s", shown.braking().activeSeconds()));
            }
            if (shown.braking().harshEventCount() != null && shown.braking().harshEventCount() > 0) {
                rows.put("Harsh Braking", String.valueOf(shown.braking().harshEventCount()));
            }
        }
        if (shown.fuel().displayable()) {
            rows.put("Fuel Used", String.format("%.2f L", shown.fuel().fuelUsedLiters()));
            if (shown.fuel().fuelEconomyKmPerLiter() != null) {
                rows.put("Fuel Economy", String.format("%.1f km/L", shown.fuel().fuelEconomyKmPerLiter()));
            }
        } else if (summaryMatches) {
            rows.put("Fuel Used", "Not available");
        }
        if (summaryMatches) {
            rows.put("Signal Issues", String.valueOf(shown.health().count()));
            if (shown.eventCount() > 0) {
                rows.put("Events", String.valueOf(shown.eventCount()));
            }
        }
        segmentBody.getChildren().setAll(metricRows(rows, tooltips));
        List<Node> issueNodes = new ArrayList<>();
        if (summaryMatches) {
            for (DriveSignalIssue issue : shown.health().issues()) {
                Label line = new Label(issue.signalName() + "\n  " + issue.evidence());
                line.getStyleClass().add("map-route-hint");
                line.setWrapText(true);
                issueNodes.add(line);
            }
        }
        segmentIssueList.getChildren().setAll(issueNodes);
        segmentIssuesLink.setVisible(!issueNodes.isEmpty());
        segmentIssuesLink.setManaged(!issueNodes.isEmpty());
        if (issueNodes.isEmpty()) {
            segmentIssueList.setVisible(false);
            segmentIssueList.setManaged(false);
        }
    }

    private List<Node> metricRows(Map<String, String> rows) {
        return metricRows(rows, Map.of());
    }

    private List<Node> metricRows(Map<String, String> rows, Map<String, String> tooltips) {
        List<Node> children = new ArrayList<>(rows.size());
        for (Map.Entry<String, String> entry : rows.entrySet()) {
            Node row = metricRow(entry.getKey(), entry.getValue());
            String tip = tooltips == null ? null : tooltips.get(entry.getKey());
            if (tip != null && !tip.isBlank()) {
                Tooltip tooltip = new Tooltip(tip);
                tooltip.setWrapText(true);
                tooltip.setMaxWidth(320);
                Tooltip.install(row, tooltip);
            }
            children.add(row);
        }
        return children;
    }

    private Node metricRow(String name, String value) {
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("map-route-detail-name");
        nameLabel.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("map-route-detail-value");
        HBox row = new HBox(6, nameLabel, spacer, valueLabel);
        row.getStyleClass().add("map-route-detail-row");
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void refreshPointDetails() {
        detailTitle.setText("Selected Point");
        MapRouteModel model = route.model();
        if (selectedIndex < 0 || model.isEmpty()) {
            detailBadge.setText(gpsUnavailableMessage == null ? "No point selected" : "No GPS");
            detailBody.getChildren().setAll(hint(gpsUnavailableMessage == null
                    ? "Click the route to inspect one timestamp."
                    : gpsUnavailableMessage));
            copyButton.setDisable(true);
            return;
        }
        long timestamp = model.timestampNanos(selectedIndex);
        detailBadge.setText(MapTimeFormat.withMillis(timestamp));
        copyButton.setDisable(false);

        Map<String, String> rows = new LinkedHashMap<>();
        if (selectedSegment != null) {
            rows.put("Measurement", selectedSegment.displayName());
        }
        rows.put("Latitude", String.format("%.7f", model.latitude(selectedIndex)));
        rows.put("Longitude", String.format("%.7f", model.longitude(selectedIndex)));
        double altitude = model.altitude(selectedIndex);
        if (!Double.isNaN(altitude)) {
            rows.put("Altitude", String.format("%.2f m", altitude));
        }
        renderRows(rows);
        loadSnapshot(timestamp, rows);
    }

    /**
     * Resolves the remaining signal values off the FX thread and merges them into the panel.
     * Only the currently selected signals plus resolved GPS roles are consulted.
     */
    private void loadSnapshot(long timestampNanos, Map<String, String> baseRows) {
        SignalSnapshotService service = snapshotService;
        if (service == null) {
            return;
        }
        List<SignalCatalogKey> keys = snapshotKeys();
        if (keys.isEmpty()) {
            return;
        }
        long my = generation;
        int requestedIndex = selectedIndex;
        executor().submit(() -> {
            List<SignalSnapshotValue> values =
                    service.snapshotAt(timestampNanos, keys, null, SNAPSHOT_TOLERANCE_NANOS);
            Platform.runLater(() -> {
                if (my != generation || requestedIndex != selectedIndex) {
                    return;
                }
                Map<String, String> merged = new LinkedHashMap<>(baseRows);
                for (SignalSnapshotValue value : values) {
                    merged.put(value.key().signalName(), formatValue(value));
                }
                renderRows(merged);
            });
        });
    }

    /** GPS roles first (they give the location context), then the engineer's chosen signals. */
    private List<SignalCatalogKey> snapshotKeys() {
        List<SignalCatalogKey> keys = new ArrayList<>();
        Map<GpsSignalRole, SignalCatalogKey> roles = new EnumMap<>(GpsSignalRole.class);
        roles.putAll(gpsRoles);
        for (GpsSignalRole role : List.of(GpsSignalRole.GPS_SPEED, GpsSignalRole.COURSE,
                GpsSignalRole.SATELLITE_COUNT, GpsSignalRole.GPS_QUALITY)) {
            SignalCatalogKey key = roles.get(role);
            if (key != null) {
                keys.add(key);
            }
        }
        String owner = selectedSegment == null ? mappingSourceId : selectedSegment.measurementId();
        for (SignalCatalogKey key : selectedSignals) {
            if (key == null || keys.contains(key) || keys.size() >= MAX_SNAPSHOT_SIGNALS) {
                continue;
            }
            if (owner != null && !owner.isBlank()
                    && key.sourceFile() != null && !key.sourceFile().isBlank()
                    && !key.sourceFile().equalsIgnoreCase(owner)
                    && !fileNameEquals(key.sourceFile(), owner)) {
                continue;
            }
            keys.add(key);
        }
        return keys;
    }

    private static boolean fileNameEquals(String pathOrName, String identity) {
        if (pathOrName == null || identity == null) {
            return false;
        }
        String left = pathOrName.replace('\\', '/');
        String right = identity.replace('\\', '/');
        int slash = left.lastIndexOf('/');
        String file = slash >= 0 ? left.substring(slash + 1) : left;
        return file.equalsIgnoreCase(right) || left.equalsIgnoreCase(right);
    }

    private void renderRows(Map<String, String> rows) {
        List<Node> children = new ArrayList<>(rows.size());
        for (Map.Entry<String, String> entry : rows.entrySet()) {
            Label name = new Label(entry.getKey());
            name.getStyleClass().add("map-route-detail-name");
            name.setMinWidth(Region.USE_PREF_SIZE);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Label value = new Label(entry.getValue());
            value.getStyleClass().add("map-route-detail-value");
            HBox row = new HBox(6, name, spacer, value);
            row.getStyleClass().add("map-route-detail-row");
            row.setAlignment(Pos.CENTER_LEFT);
            children.add(row);
        }
        detailBody.getChildren().setAll(children);
    }

    private void renderTiles(Map<String, String> rows) {
        List<Node> tiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : rows.entrySet()) {
            Label caption = new Label(entry.getKey());
            caption.getStyleClass().add("map-route-tile-caption");
            Label value = new Label(entry.getValue());
            value.getStyleClass().add("map-route-tile-value");
            VBox tile = new VBox(2, caption, value);
            tile.getStyleClass().add("map-route-tile");
            HBox.setHgrow(tile, Priority.ALWAYS);
            tiles.add(tile);
        }
        summaryTiles.getChildren().setAll(tiles);
    }

    private void copyDetails() {
        StringBuilder text = new StringBuilder(detailTitle.getText()).append('\n');
        for (Node node : detailBody.getChildren()) {
            if (node instanceof HBox row && row.getChildren().size() == 3
                    && row.getChildren().get(0) instanceof Label name
                    && row.getChildren().get(2) instanceof Label value) {
                text.append(name.getText()).append('\t').append(value.getText()).append('\n');
            }
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text.toString());
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("Selection details copied to clipboard");
    }

    private void logGpsSample(String reason, int index) {
        MapRouteModel model = route.model();
        if (model.isEmpty() || index < 0 || index >= model.size()) {
            return;
        }
        LOG.info(() -> String.format(
                "DriveSegment %s index=%d timestamp=%.3f s lat=%.7f lon=%.7f selectedPoint=%s analysisRange=%s",
                reason,
                index,
                model.timestampNanos(index) / 1e9,
                model.latitude(index),
                model.longitude(index),
                selectedPointLog(),
                analysisRangeLog()));
    }

    private void logCommittedSegment(DriveSegmentSelection.ActiveSegment active) {
        MapRouteModel model = route.model();
        int a = active.startIndex();
        int b = active.endIndex();
        String aGps = (a >= 0 && a < model.size())
                ? String.format("lat=%.7f lon=%.7f", model.latitude(a), model.longitude(a))
                : "n/a";
        String bGps = (b >= 0 && b < model.size())
                ? String.format("lat=%.7f lon=%.7f", model.latitude(b), model.longitude(b))
                : "n/a";
        LOG.info(() -> String.format(
                "DriveSegment committed A index=%d t=%.3f s %s | B index=%d t=%.3f s %s | duration=%.3f s | selectedPoint=%s | analysisRange=%s",
                a, active.startTimestampNanos() / 1e9, aGps,
                b, active.endTimestampNanos() / 1e9, bGps,
                active.durationNanos() / 1e9,
                selectedPointLog(),
                analysisRangeLog()));
    }

    private String selectedPointLog() {
        if (selectedIndex < 0 || route.isEmpty() || selectedIndex >= route.model().size()) {
            return "none";
        }
        return String.format("%.3f s", route.model().timestampNanos(selectedIndex) / 1e9);
    }

    private String analysisRangeLog() {
        TimeSelection range = sharedSelection.analysisRange();
        if (range.kind() != TimeSelection.Kind.RANGE) {
            return range.kind().name();
        }
        return String.format("[%.3f s, %.3f s] dur=%.3f s",
                range.startNanos() / 1e9, range.endNanos() / 1e9, range.durationNanos() / 1e9);
    }

    private static Label hint(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("map-route-hint");
        label.setWrapText(true);
        return label;
    }

    private static String formatValue(SignalSnapshotValue value) {
        if (value.textualValue() != null && !value.textualValue().isBlank()) {
            return value.textualValue();
        }
        String unit = value.unit() == null || value.unit().isBlank() ? "" : " " + value.unit();
        double v = value.physicalValue();
        double magnitude = Math.abs(v);
        String number = magnitude >= 1000 || (magnitude > 0 && magnitude < 0.001)
                ? String.format("%.4g", v)
                : String.format("%.3f", v);
        return number + unit;
    }

    private ExecutorService executor() {
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "stellar-map-route");
                thread.setDaemon(true);
                return thread;
            });
        }
        return worker;
    }

    // --- structural probes for tests ---
    boolean uiBuiltForTest() {
        return uiBuilt;
    }

    long generationForTest() {
        return generation;
    }

    public long staleDiscardsForTest() {
        return staleDiscards;
    }

    boolean routeCanvasSuspendedForTest() {
        return mapCanvas != null && mapCanvas.paintingSuspendedForTest();
    }

    public void ensureUiBuiltForTest() {
        ensureUiBuilt();
    }

    MapSelectionMode modeForTest() {
        return mode;
    }

    public int selectedIndexForTest() {
        return selectedIndex;
    }

    int[] rangeIndicesForTest() {
        return new int[]{indexA, indexB};
    }

    int[] canvasRangeIndicesForTest() {
        return mapCanvas == null ? new int[]{-1, -1} : mapCanvas.rangeIndicesForTest();
    }

    DriveSegmentSelection.Phase segmentPhaseForTest() {
        return segment.phase();
    }

    String segmentHeadlineForTest() {
        return segmentHeadline == null ? "" : segmentHeadline.getText();
    }

    String segmentBodyTextForTest() {
        if (segmentBody == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (javafx.scene.Node node : segmentBody.getChildren()) {
            appendLabeledText(node, text);
        }
        return text.toString();
    }

    void renderSegmentSummaryForTest(DriveSegmentSummary summary) {
        renderSegmentSummary(summary);
    }

    void presentRoutesForTest(MapRouteRequest request, ProjectRoute assembled, MapRoute single) {
        ensureUiBuilt();
        activeRequest = request;
        if (request != null && request.projectWide()) {
            applyPreparedProjectRoute(assembled == null ? ProjectRoute.empty() : assembled);
        } else {
            applyPreparedRoute(single == null ? MapRoute.emptyRoute() : single);
        }
    }

    private static void appendLabeledText(javafx.scene.Node node, StringBuilder text) {
        if (node instanceof Label label) {
            text.append(label.getText()).append('\n');
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                appendLabeledText(child, text);
            }
        }
    }

    String segmentBadgeForTest() {
        return segmentBadge == null ? "" : segmentBadge.getText();
    }

    DriveSegmentAnalysisService.DriveSegmentAnalysisRequest lastAnalysisRequestForTest() {
        return lastAnalysisRequest;
    }

    long[] committedTimestampsForTest() {
        return new long[]{segment.startTimestampNanos(), segment.endTimestampNanos()};
    }

    public int routeSizeForTest() {
        return route.model().size();
    }

    boolean emptyStateVisibleForTest() {
        return uiBuilt && emptyState.isVisible();
    }

    void pickForTest(int index) {
        onPointPicked(index);
    }

    void setAutoSyncForTest(boolean value) {
        autoSync = value;
        if (uiBuilt) {
            autoSyncToggle.setSelected(value);
        }
    }

    void applyModeForTest(MapSelectionMode target) {
        ensureUiBuilt();
        applyMode(target, true);
    }

    LeafletBasemapView basemapViewForTest() {
        return basemapView;
    }

    String emptyHeadlineForTest() {
        return emptyHeadline == null ? "" : emptyHeadline.getText();
    }

    public RouteCanvas routeCanvasForTest() {
        return mapCanvas;
    }

    public String routeStatusForTest() {
        return statusLabel == null ? "" : statusLabel.getText();
    }

    public boolean loadingVisibleForTest() {
        return loadingState != null && loadingState.isVisible();
    }

    public boolean routeIsolatedForTest() {
        return routeIsolated;
    }

    public String selectedSampleForTest() {
        if (selectedSegment == null || !selectedSegment.hasRoute() || selectedIndex < 0) {
            return "";
        }
        MapRouteModel model = selectedSegment.route().model();
        if (selectedIndex >= model.size()) {
            return "";
        }
        return selectedSegment.measurementId() + "\t" + model.timestampNanos(selectedIndex)
                + "\t" + model.latitude(selectedIndex) + "\t" + model.longitude(selectedIndex);
    }

    public String selectedMeasurementIdForTest() {
        return selectedSegment == null ? "" : selectedSegment.measurementId();
    }

    public java.util.List<RouteSegment> gpsSegmentsForTest() {
        return projectRoute == null ? java.util.List.of() : projectRoute.gpsSegments();
    }

    public String mappingStatusForTest() {
        return mappingStatusValue == null ? "" : mappingStatusValue.getText();
    }

    String mappingLatitudeForTest() {
        return mappingLatitudeValue == null ? "" : mappingLatitudeValue.getText();
    }

    String mappingLongitudeForTest() {
        return mappingLongitudeValue == null ? "" : mappingLongitudeValue.getText();
    }

    String mappingAltitudeForTest() {
        return mappingAltitudeValue == null ? "" : mappingAltitudeValue.getText();
    }

    String mappingSourceForTest() {
        return mappingSourceId;
    }

    double routeCanvasZoomForTest() {
        return mapCanvas == null ? 0 : mapCanvas.viewport().leafletZoom();
    }

    String routeCanvasBackgroundForTest() {
        return mapCanvas == null ? "" : mapCanvas.backgroundName();
    }

    MapBasemapMode basemapModeForTest() {
        return basemapView == null ? MapBasemapMode.NEUTRAL : basemapView.mode();
    }

    MapBasemapCompliance basemapComplianceForTest() {
        return activeBasemap == null ? MapBasemapCompliance.NEUTRAL : activeBasemap.compliance();
    }

    String basemapProviderIdForTest() {
        if (activeBasemap == null || activeBasemap.onlineProvider() == null) {
            return "";
        }
        return activeBasemap.onlineProvider().providerId();
    }

    /** Drives the same fallback path a real tile failure takes, without needing the network. */
    void failBasemapModeForTest(MapBasemapMode failed) {
        onBasemapModeFailed(failed);
    }

    String statusTextForTest() {
        return statusLabel == null ? "" : statusLabel.getText();
    }

    void openMappingEditorForTest() {
        openMappingEditor();
    }

    ProjectRoute projectRouteForTest() {
        return projectRoute;
    }

    RouteSegment selectedSegmentForTest() {
        return selectedSegment;
    }

    boolean mappingEditorOpenForTest() {
        return mappingSheet != null && mappingSheet.isVisible();
    }

    void closeMappingEditorForTest() {
        closeMappingEditor();
    }

    public double mapStageHeightForTest() {
        return mapStack == null ? 0 : mapStack.getHeight();
    }

    public boolean railScrollableForTest() {
        return railScroll != null && railScroll.isVisible() && railScroll.getVbarPolicy() == ScrollPane.ScrollBarPolicy.AS_NEEDED;
    }

    public boolean routeDataVisibleForTest() {
        return filteredDataSection != null && filteredDataSection.isVisible() && filteredDataSection.isManaged();
    }

    public boolean routeDetailsToggleReachableForTest() {
        return routeDetailsToggle != null && routeDetailsToggle.isVisible() && routeDetailsToggle.isManaged();
    }

    public boolean routeDetailsExpandedForTest() {
        return routeDetailsExpanded;
    }

    public void toggleRouteDetailsForTest() {
        toggleRouteDetails();
    }

    public SplitPane workspaceSplitForTest() {
        return mapWorkspaceSplit;
    }

    double[] mapSurfaceSizesForTest() {
        ensureUiBuilt();
        return new double[] {
                mapCanvas.getWidth(),
                mapCanvas.getHeight(),
                basemapView.getWidth(),
                basemapView.getHeight(),
                basemapView.webViewForTest().getWidth(),
                basemapView.webViewForTest().getHeight()
        };
    }

    boolean mappingEditorVisibleForTest() {
        return mappingSheet != null && mappingSheet.isVisible();
    }

    GpsSignalMappingModal mappingEditorForTest() {
        ensureUiBuilt();
        return mappingEditor;
    }

    boolean mappingEditEnabledForTest() {
        ensureUiBuilt();
        return mappingEditButton != null && mappingEditButton.isVisible() && !mappingEditButton.isDisabled();
    }

    /**
     * Shared persistence for Map Route's compact editor and Configuration. Implemented by the
     * desktop service so neither UI owns a second mapping store.
     */
    public interface RouteMappingPersistence {
        RouteMappingPersistence NONE = new RouteMappingPersistence() {
            @Override
            public List<GpsSignalMapping> loadProjectMappings() {
                return List.of();
            }

            @Override
            public List<GpsSignalMapping> loadDefaults() {
                return List.of();
            }

            @Override
            public boolean saveProjectMappings(String sourceId, String fingerprint, List<GpsSignalMapping> mappings) {
                return false;
            }

            @Override
            public SpeedSourceMapping loadSpeedMapping(String sourceId) {
                return null;
            }

            @Override
            public void saveSpeedMapping(SpeedSourceMapping mapping) {
            }
        };

        List<GpsSignalMapping> loadProjectMappings();

        List<GpsSignalMapping> loadDefaults();

        /**
         * @return {@code true} only when the write completed; the editor stays open on {@code false}
         */
        boolean saveProjectMappings(String sourceId, String fingerprint, List<GpsSignalMapping> mappings);

        default SpeedSourceMapping loadSpeedMapping(String sourceId) {
            return null;
        }

        default void saveSpeedMapping(SpeedSourceMapping mapping) {
        }
    }
}
