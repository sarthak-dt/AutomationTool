package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.desktop.DesktopVersion;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import netscape.javascript.JSObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Embedded Leaflet basemap behind the route canvas.
 *
 * <p>Leaflet 1.9.4 and the PMTiles client are packaged locally (no CDN). Online OSM-compatible
 * tiles are used when healthy; otherwise a local PMTiles pack; otherwise a neutral backdrop while
 * the Java route canvas continues to draw GPS.
 *
 * <p>One WebView and one Leaflet map instance live for the whole pane lifecycle. Readiness is a
 * strict sequence rather than a race: the document loads, the JavaFX container reports a usable
 * layout, only then is {@code L.map} created against a sized DOM element, and only then are the
 * provider and route bounds applied. Nothing here sleeps — every step is driven by a WebEngine
 * state change, a size property change, or a coalesced JavaFX pulse.
 */
public final class LeafletBasemapView extends StackPane {

    private static final Logger LOG = Logger.getLogger(LeafletBasemapView.class.getName());

    /** Below this the DOM container cannot produce a meaningful projection. */
    private static final int MIN_MAP_PIXELS = 8;
    /** Visible-tile errors at or above this count fail the provider rather than claiming Online. */
    private static final int VISIBLE_TILE_FAIL_LIMIT = 4;
    /** Shown whenever the backdrop is gone but the GPS route is not. */
    static final String BASEMAP_UNAVAILABLE = "Basemap unavailable — GPS route remains available.";
    /** Bounded backstop for the pulse where the WebView size has not propagated yet. */
    private static final int MAX_DEFERRED_RETRIES = 12;

    private final WebView webView = new WebView();
    private final Label statusBanner = new Label();
    private final Label attribution = new Label();
    private boolean suppressJavaFxOverlayChrome;
    private String probeAssetBaseUrl = "";

    private boolean htmlReady;
    private boolean leafletReady;
    private boolean leafletInitRequested;
    /** JS Leaflet map creations observed by Java (first successful ready). Not ready-callback count. */
    private int leafletInitCount;
    /** Every {@code stellarBridge.leafletReady} delivery, including recovery replays. */
    private int leafletReadyCallbackCount;
    private MapBasemapMode mode = MapBasemapMode.NEUTRAL;
    private boolean onlineTilesFailed;
    private Runnable readyHandler = () -> { };
    private Runnable tileStateHandler = () -> { };
    private Consumer<MapBasemapMode> modeFailedHandler = m -> { };
    private java.util.function.IntConsumer maxZoomHandler = z -> { };
    private int activeMaxZoom = MapScale.DEFAULT_MAX_ZOOM;
    private MapViewState pendingView;
    private final List<Runnable> pendingReadyActions = new ArrayList<>();
    private final LeafletMapResizeCoalescer resizeCoalescer = new LeafletMapResizeCoalescer();
    private final LeafletReadinessWatchdog readinessWatchdog = new LeafletReadinessWatchdog();
    private boolean readinessFailed;
    private boolean readinessRetried;
    private int deferredSizeRetries;
    private int invalidateRequests;
    private int onlineProviderApplies;
    private int providerGeneration;
    private boolean pendingInvalidate;
    private boolean domSizeValid;
    private int lastDomWidth;
    private int lastDomHeight;
    private boolean tileLayerPresent;
    private boolean tileLayerLoading;
    private boolean firstTileLoaded;
    private boolean tileLayerLoadCompleted;
    private int tileErrorCount;
    private BasemapTileHealth tileHealth = BasemapTileHealth.NEUTRAL_FALLBACK;
    private boolean disposed;
    /** Last online tile URL Java asked JS to apply; same-URL reapply must not bump generation. */
    private String appliedOnlineUrl;
    /** Display name only — never a URL or API key. */
    private String onlineProviderName;
    /** True after a JS snapshot proved tiles healthy while LiveConnect still had firstTile=false. */
    private boolean adoptedTileHealthFromJs;

    private final ChangeListener<Number> containerSizeListener =
            (o, a, b) -> onContainerSizeChanged("layout");
    private final ChangeListener<Boolean> visibleListener = (o, a, visible) -> {
        if (Boolean.TRUE.equals(visible)) {
            onContainerSizeChanged("became-visible");
        }
    };
    private final ChangeListener<javafx.scene.Scene> sceneListener = (o, a, scene) -> {
        if (scene != null) {
            onContainerSizeChanged("scene");
        }
    };

    public LeafletBasemapView() {
        getStyleClass().add("map-leaflet-basemap");
        setMinSize(0, 0);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        webView.setContextMenuEnabled(false);
        webView.setMouseTransparent(true);
        webView.setFocusTraversable(false);
        webView.setMinSize(0, 0);
        webView.prefWidthProperty().bind(widthProperty());
        webView.prefHeightProperty().bind(heightProperty());
        webView.maxWidthProperty().bind(widthProperty());
        webView.maxHeightProperty().bind(heightProperty());

        statusBanner.getStyleClass().add("map-basemap-offline");
        statusBanner.setVisible(false);
        statusBanner.setManaged(false);
        statusBanner.setMouseTransparent(true);
        statusBanner.setWrapText(true);
        statusBanner.setMaxWidth(460);
        StackPane.setAlignment(statusBanner, Pos.TOP_CENTER);
        StackPane.setMargin(statusBanner, new Insets(10, 10, 0, 10));

        attribution.getStyleClass().add("map-basemap-attribution");
        attribution.setMouseTransparent(true);
        StackPane.setAlignment(attribution, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(attribution, new Insets(0, 8, 8, 8));

        getChildren().addAll(webView, statusBanner, attribution);
        // The WebView's own measured size is authoritative: it is what the DOM will be sized to.
        webView.widthProperty().addListener(containerSizeListener);
        webView.heightProperty().addListener(containerSizeListener);
        widthProperty().addListener(containerSizeListener);
        heightProperty().addListener(containerSizeListener);
        visibleProperty().addListener(visibleListener);
        sceneProperty().addListener(sceneListener);
        if (!MapTileProbe.gridLocal()) {
            loadEngine();
        }
    }

    /** Opt-in local synthetic tiles. Must be called before grid-local HTML is loaded. */
    void setProbeAssetBaseUrl(String baseUrl) {
        probeAssetBaseUrl = baseUrl == null ? "" : baseUrl;
        if (MapTileProbe.gridLocal() && !htmlReady && !disposed) {
            loadEngine();
        }
    }

    public void setReadyHandler(Runnable handler) {
        this.readyHandler = handler == null ? () -> { } : handler;
    }

    /** Notified when tile evidence changes (first tile, stall, error) so status text can follow. */
    public void setTileStateHandler(Runnable handler) {
        this.tileStateHandler = handler == null ? () -> { } : handler;
    }

    /** Called when online tiles fail or offline pack cannot load — parent may fall back. */
    public void setModeFailedHandler(Consumer<MapBasemapMode> handler) {
        this.modeFailedHandler = handler == null ? m -> { } : handler;
        if (readinessFailed) {
            // The assets can already have been found missing during construction, before the parent
            // had a chance to register: report it now instead of losing the only notification.
            this.modeFailedHandler.accept(MapBasemapMode.NEUTRAL);
        }
    }

    private boolean statusBannerWasManaged;

    /** Hides attribution and status text so a report snapshot is the map canvas only. */
    public void setReportChromeVisible(boolean visible) {
        attribution.setVisible(visible);
        attribution.setManaged(visible);
        if (!visible) {
            statusBannerWasManaged = statusBanner.isManaged();
            statusBanner.setVisible(false);
            statusBanner.setManaged(false);
        } else if (statusBannerWasManaged) {
            statusBanner.setVisible(true);
            statusBanner.setManaged(true);
        }
    }

    public boolean isReady() {
        return leafletReady;
    }

    public boolean onlineTilesFailed() {
        return onlineTilesFailed;
    }

    /** True only when the visible tile set finished loading without a failing provider. */
    public boolean tilesLoaded() {
        return tileHealth == BasemapTileHealth.TILES_HEALTHY;
    }

    public BasemapTileHealth tileHealth() {
        return tileHealth;
    }

    /** True after Leaflet reports the current layer finished (success or mixed tile errors). */
    public boolean tileLoadCompleted() {
        return tileLayerLoadCompleted;
    }

    public MapBasemapMode mode() {
        return mode;
    }

    public String providerDisplayName() {
        return switch (mode) {
            case ONLINE -> onlineProviderName == null || onlineProviderName.isBlank()
                    ? "Online street map"
                    : onlineProviderName;
            case OFFLINE_PMTILES -> "Offline map";
            case NEUTRAL -> "Neutral";
        };
    }

    /**
     * Clears sticky online-tile failure so a new measurement/project can try ONLINE again.
     * Does not recreate the WebView.
     */
    public void prepareForNewMeasurement() {
        onlineTilesFailed = false;
        pendingView = null;
        hideBanner();
    }

    public void applyOnline(MapTileProvider provider) {
        if (provider == null) {
            applyNeutral(MapBasemapUserMessages.MAP_UNAVAILABLE);
            return;
        }
        onlineTilesFailed = false;
        mode = MapBasemapMode.ONLINE;
        hideBanner();
        MapTileProvider p = provider;
        setActiveMaxZoom(p.maxZoom());
        attribution.setText(stripHtml(p.attributionHtml()));
        showAttributionLabel(true);
        onlineProviderName = p.displayName();
        onlineProviderApplies++;
        String url = p.tileUrlTemplate();
        if (url.equals(appliedOnlineUrl)) {
            runWhenReady(() -> {
                adoptTileHealthFromJs("same-url-reapply");
                if (tileHealth != BasemapTileHealth.TILES_HEALTHY) {
                    scheduleTileHealthAdopt("same-url-reapply");
                }
            });
            LOG.fine(() -> "Basemap ONLINE reapply · same URL · generation " + providerGeneration
                    + " · " + p.providerId() + " · " + p.displayName());
            return;
        }
        appliedOnlineUrl = url;
        tileHealth = BasemapTileHealth.TILES_LOADING;
        firstTileLoaded = false;
        tileLayerLoadCompleted = false;
        tileLayerPresent = false;
        tileLayerLoading = false;
        tileErrorCount = 0;
        int generation = ++providerGeneration;
        runWhenReady(() -> {
            runScript(String.format(Locale.ROOT,
                    "window.stellarSetOnlineProvider('%s',%d,'%s',%d);",
                    escapeJs(p.tileUrlTemplate()),
                    p.maxZoom(),
                    escapeJs(p.attributionHtml()),
                    generation));
            scheduleTileHealthAdopt("provider-apply");
        });
        LOG.fine(() -> "Basemap ONLINE · gen " + generation + " · " + p.providerId()
                + " · " + p.displayName());
    }

    public void applyOfflinePmtiles(String packHttpUrl, String attributionText) {
        applyOfflinePmtiles(packHttpUrl, attributionText, MapScale.DEFAULT_MAX_ZOOM);
    }

    public void applyOfflinePmtiles(String packHttpUrl, String attributionText, int maxZoom) {
        onlineTilesFailed = false;
        mode = MapBasemapMode.OFFLINE_PMTILES;
        hideBanner();
        setActiveMaxZoom(maxZoom < 1 ? MapScale.DEFAULT_MAX_ZOOM : maxZoom);
        attribution.setText(attributionText == null ? "© Offline map pack" : attributionText);
        showAttributionLabel(true);
        tileHealth = BasemapTileHealth.TILES_LOADING;
        appliedOnlineUrl = null;
        int generation = ++providerGeneration;
        runWhenReady(() -> runScript(String.format(Locale.ROOT,
                "window.stellarSetOfflinePmtiles('%s','%s',%d);",
                escapeJs(packHttpUrl),
                escapeJs(attribution.getText()),
                generation)));
        LOG.fine(() -> "Basemap OFFLINE_PMTILES · " + packHttpUrl);
    }

    public void applyNeutral(String message) {
        mode = MapBasemapMode.NEUTRAL;
        tileHealth = BasemapTileHealth.NEUTRAL_FALLBACK;
        // Keeping the same ceiling across fallback means the route overlay does not change scale
        // just because the backdrop disappeared.
        setActiveMaxZoom(MapScale.DEFAULT_MAX_ZOOM);
        showBanner(message == null || message.isBlank()
                ? "Basemap unavailable — GPS route remains available."
                : message);
        showAttributionLabel(false);
        appliedOnlineUrl = null;
        int generation = ++providerGeneration;
        runWhenReady(() -> runScript("window.stellarClearBasemap(" + generation + ");"));
        LOG.fine(() -> "Basemap NEUTRAL · " + message);
    }

    /**
     * Pushes the shared {@link MapCamera} — integer Web Mercator zoom plus centre — into Leaflet.
     *
     * <p>The canvas has already floored the desired fit zoom so JavaFX WebView never sees a
     * fractional CSS scale. Both renderers consume that same integer; Leaflet is not asked to
     * {@code fitBounds}.
     */
    public void syncViewport(MapViewport viewport) {
        if (disposed || viewport == null) {
            return;
        }
        if (viewport.width() < MIN_MAP_PIXELS || viewport.height() < MIN_MAP_PIXELS) {
            return;
        }
        MapViewState state = MapViewState.of(viewport, activeMaxZoom);
        if (state == null) {
            return;
        }
        pendingView = state;
        applyPendingView();
    }

    /** Maximum zoom the active provider can represent; the route canvas must not exceed it. */
    public int activeMaxZoom() {
        return activeMaxZoom;
    }

    /** Notified when the active provider's maximum zoom changes, including on fallback. */
    public void setMaxZoomHandler(java.util.function.IntConsumer handler) {
        this.maxZoomHandler = handler == null ? z -> { } : handler;
    }

    private void setActiveMaxZoom(int maxZoom) {
        int bounded = Math.max(1, maxZoom);
        if (bounded == activeMaxZoom) {
            return;
        }
        activeMaxZoom = bounded;
        maxZoomHandler.accept(bounded);
    }

    public void invalidateSize(String reason) {
        if (disposed) {
            return;
        }
        if (!htmlReady) {
            pendingInvalidate = true;
            return;
        }
        int width = containerWidthPixels();
        int height = containerHeightPixels();
        if (width < MIN_MAP_PIXELS || height < MIN_MAP_PIXELS) {
            pendingInvalidate = true;
            domSizeValid = false;
            if (deferredSizeRetries < MAX_DEFERRED_RETRIES) {
                deferredSizeRetries++;
                Platform.runLater(() -> invalidateSize(reason + "-deferred"));
            }
            return;
        }
        if (!leafletReady) {
            pendingInvalidate = true;
            initLeaflet(width, height);
            return;
        }
        deferredSizeRetries = 0;
        pendingInvalidate = false;
        domSizeValid = true;
        lastDomWidth = width;
        lastDomHeight = height;
        invalidateRequests++;
        LOG.fine(() -> "Leaflet invalidateSize · " + reason + " · " + width + "x" + height);
        runScript(String.format(Locale.ROOT, "window.stellarResizeMap(%d,%d);", width, height));
        // Re-apply after the container changed size: the canvas re-fits to the new pixel size and
        // Leaflet has to adopt the new scale rather than keeping the one it had.
        applyPendingView();
    }

    public void scheduleInvalidateSize(String reason) {
        if (disposed) {
            return;
        }
        resizeCoalescer.schedule(() -> invalidateSize(reason == null ? "layout" : reason));
    }

    /**
     * Overlay chrome (mapping editor, tab switch, Focus Mode) can leave the WebView unpainted.
     * One invalidate now (including a 1px nudge so a same-size overlay close actually repaints),
     * then one coalesced pulse after layout settles — not a retry loop.
     */
    public void resyncAfterChrome(String reason) {
        if (disposed) {
            return;
        }
        String label = reason == null ? "chrome" : reason;
        LOG.fine(() -> "Basemap resync · " + label + " · " + diagnostics().describe());
        int width = containerWidthPixels();
        int height = containerHeightPixels();
        if (leafletReady && width >= MIN_MAP_PIXELS && height >= MIN_MAP_PIXELS) {
            // Same-size invalidateSize does not restore pixels a StackPane overlay punched out.
            runScript(String.format(Locale.ROOT, "window.stellarResizeMap(%d,%d);",
                    Math.max(MIN_MAP_PIXELS, width - 1), height));
            invalidateRequests++;
        }
        scheduleInvalidateSize(label + "-settled");
        if (mode != MapBasemapMode.NEUTRAL && tileHealth != BasemapTileHealth.TILES_HEALTHY) {
            adoptTileHealthFromJs("chrome-resync");
        }
    }

    /** Development/support snapshot. Never rendered into the production UI. */
    public MapBasemapDiagnostics diagnostics() {
        int[] dom = queryDomSize();
        return new MapBasemapDiagnostics(
                webView.getEngine().getLoadWorker().getState().name(),
                htmlReady,
                leafletReady,
                (int) Math.round(webView.getWidth()),
                (int) Math.round(webView.getHeight()),
                dom[0],
                dom[1],
                leafletInitCount,
                mode.name() + (mode == MapBasemapMode.NEUTRAL ? "" : " · " + providerDisplayName()),
                tileLayerPresent,
                firstTileLoaded,
                tileLayerLoadCompleted,
                tileErrorCount,
                invalidateRequests,
                leafletViewportSnapshot()
                        + (pendingView == null ? " pendingView=no" : " pendingView=yes"),
                tileHealth.name(),
                leafletReadyCallbackCount);
    }

    public void dispose() {
        disposed = true;
        htmlReady = false;
        leafletReady = false;
        leafletInitRequested = false;
        pendingReadyActions.clear();
        pendingView = null;
        readinessWatchdog.cancel();
        webView.widthProperty().removeListener(containerSizeListener);
        webView.heightProperty().removeListener(containerSizeListener);
        widthProperty().removeListener(containerSizeListener);
        heightProperty().removeListener(containerSizeListener);
        visibleProperty().removeListener(visibleListener);
        sceneProperty().removeListener(sceneListener);
        webView.prefWidthProperty().unbind();
        webView.prefHeightProperty().unbind();
        webView.maxWidthProperty().unbind();
        webView.maxHeightProperty().unbind();
        try {
            webView.getEngine().load(null);
        } catch (RuntimeException ignored) {
            // tearing down
        }
    }

    // ------------------------------------------------------------------
    // Readiness sequence
    // ------------------------------------------------------------------

    private void loadEngine() {
        WebEngine engine = webView.getEngine();
        try {
            engine.setUserAgent("StellarAI/" + DesktopVersion.displayVersion()
                    + " (JavaFX WebView; +https://stellarai.local)");
        } catch (RuntimeException ex) {
            LOG.fine(() -> "WebView user agent not applied: " + ex.getMessage());
        }
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED) {
                if (disposed) {
                    return;
                }
                htmlReady = true;
                try {
                    JSObject window = (JSObject) engine.executeScript("window");
                    window.setMember("stellarBridge", new MapJsBridge());
                } catch (RuntimeException ex) {
                    LOG.warning(() -> "Map JS bridge failed: " + ex.getMessage());
                }
                // Leaflet must not be created before the container has a usable layout.
                scheduleInvalidateSize("html-loaded");
            } else if (state == Worker.State.FAILED) {
                applyNeutral("Map render error — route data is still available.");
                modeFailedHandler.accept(MapBasemapMode.NEUTRAL);
            }
        });
        engine.setOnError(event -> LOG.warning(() -> "WebView map error: " + event.getMessage()));
        String html;
        try {
            html = MapTileProbe.grid() ? diagnosticGridHtml() : basemapHtml();
        } catch (RuntimeException ex) {
            // A packaging fault must not take the whole Map Route workspace down with it: the route
            // itself is drawn in Java and does not need Leaflet at all.
            LOG.warning(() -> "Map web assets unavailable: " + ex.getMessage());
            readinessFailed = true;
            applyNeutral(BASEMAP_UNAVAILABLE);
            return;
        }
        engine.loadContent(html);
    }

    private void onContainerSizeChanged(String reason) {
        // A real size change re-arms deferral: a container that was collapsed for a long time
        // must still be able to initialize once it is finally laid out.
        deferredSizeRetries = 0;
        scheduleInvalidateSize(reason);
    }

    private void initLeaflet(int width, int height) {
        if (disposed || leafletReady || !htmlReady) {
            return;
        }
        lastDomWidth = width;
        lastDomHeight = height;
        boolean replay = leafletInitRequested;
        if (!leafletInitRequested) {
            leafletInitRequested = true;
            LOG.fine(() -> "Leaflet init · " + width + "x" + height);
            // Armed only now: a container that is merely still collapsed is waiting, not failing.
            readinessWatchdog.arm(this::onReadinessTimeout);
        } else {
            LOG.fine(() -> "Leaflet init replay · " + width + "x" + height);
        }
        adoptJsReadiness(width, height, replay);
    }

    /**
     * Leaflet was asked to start and never answered. The route is Java-side and unaffected, so the
     * workspace drops to the neutral backdrop and says so rather than showing "Loading map…" forever.
     */
    private void onReadinessTimeout() {
        onReadinessTimeout(false);
    }

    private void onReadinessTimeout(boolean forced) {
        if (disposed || leafletReady) {
            return;
        }
        leafletInitRequested = false;
        if (!forced) {
            int width = containerWidthPixels();
            int height = containerHeightPixels();
            if (width < MIN_MAP_PIXELS || height < MIN_MAP_PIXELS) {
                pendingInvalidate = true;
                LOG.fine("Leaflet init timed out while collapsed; waiting for layout");
                return;
            }
            if (!readinessRetried) {
                readinessRetried = true;
                pendingInvalidate = true;
                LOG.fine("Leaflet init timed out; retrying against the current layout");
                scheduleInvalidateSize("readiness-retry");
                return;
            }
            // JS may already have a usable map while LiveConnect dropped leafletReady.
            // The script return value is the source of truth — do not wait for another pulse.
            if (adoptJsReadiness(width, height, true)) {
                return;
            }
        }
        if (MapTileProbe.suppressFallback()) {
            LOG.warning("TILE-PROBE: suppressing readiness fallback · " + diagnostics().describe());
            dumpTileAudit("readiness-timeout-suppressed");
            return;
        }
        failReadinessToNeutral();
    }

    /**
     * Runs {@code stellarInitMap} and, when JS reports a usable map, marks Java ready on this
     * thread. LiveConnect {@code leafletReady} is best-effort; the real-desktop miss was that JS
     * had a map and Java never received the callback.
     */
    private boolean adoptJsReadiness(int width, int height, boolean replay) {
        if (disposed || !htmlReady) {
            return false;
        }
        try {
            Object raw = webView.getEngine().executeScript(String.format(Locale.ROOT,
                    "window.stellarInitMap(%d,%d);", width, height));
            if (!jsTruthy(raw)) {
                return false;
            }
            if (leafletReady) {
                return true;
            }
            if (replay) {
                LOG.fine("Leaflet map already existed; readiness callback replayed");
            } else {
                LOG.fine("Leaflet JS reported a usable map; adopting ready");
            }
            handleLeafletReadyFromJs();
            return true;
        } catch (RuntimeException ex) {
            LOG.fine(() -> "Leaflet readiness adopt failed: " + ex.getMessage());
            return false;
        }
    }

    private void failReadinessToNeutral() {
        readinessFailed = true;
        LOG.warning("Leaflet did not become ready within " + LeafletReadinessWatchdog.TIMEOUT.toSeconds()
                + "s · falling back to neutral basemap");
        applyNeutral(BASEMAP_UNAVAILABLE);
        modeFailedHandler.accept(MapBasemapMode.NEUTRAL);
        tileStateHandler.run();
    }

    /** True when the basemap gave up: Leaflet never started, or its assets are not available. */
    public boolean readinessFailed() {
        return readinessFailed;
    }

    /**
     * The WebView's measured size lags the parent by one layout pass on the very first pulse, so
     * the parent's width is accepted as an equivalent signal.
     */
    private int containerWidthPixels() {
        return (int) Math.round(Math.max(webView.getWidth(), getWidth()));
    }

    private int containerHeightPixels() {
        return (int) Math.round(Math.max(webView.getHeight(), getHeight()));
    }

    private void runWhenReady(Runnable action) {
        if (action == null) {
            return;
        }
        if (leafletReady) {
            action.run();
        } else {
            pendingReadyActions.add(action);
        }
    }

    private void applyPendingView() {
        if (disposed || pendingView == null || !leafletReady || !domSizeValid) {
            return;
        }
        MapViewState v = pendingView;
        runScript(String.format(Locale.ROOT, "window.stellarSetView(%s,%s,%s);",
                v.centerLatitude(), v.centerLongitude(), v.zoom()));
    }

    private void onLeafletReadyFromJs() {
        Platform.runLater(this::handleLeafletReadyFromJs);
    }

    private void handleLeafletReadyFromJs() {
        if (disposed) {
            return;
        }
        leafletReadyCallbackCount++;
        if (leafletReady) {
            LOG.fine("Leaflet map already existed; readiness callback replayed");
            flushPendingViewAfterReady("ready-replay");
            if (mode != MapBasemapMode.NEUTRAL && tileHealth != BasemapTileHealth.TILES_HEALTHY) {
                adoptTileHealthFromJs("ready-replay");
            }
            return;
        }
        leafletReady = true;
        if (leafletInitCount == 0) {
            leafletInitCount = Math.max(1, queryJsMapInitCount());
        }
        readinessWatchdog.cancel();
        readinessFailed = false;
        if (!tileLayerPresent && mode != MapBasemapMode.NEUTRAL) {
            tileHealth = BasemapTileHealth.LEAFLET_READY;
        }
        lastDomWidth = Math.max(lastDomWidth, containerWidthPixels());
        lastDomHeight = Math.max(lastDomHeight, containerHeightPixels());
        domSizeValid = lastDomWidth >= MIN_MAP_PIXELS && lastDomHeight >= MIN_MAP_PIXELS;
        LOG.fine(() -> "Leaflet ready · " + diagnostics().describe());
        List<Runnable> queued = List.copyOf(pendingReadyActions);
        pendingReadyActions.clear();
        for (Runnable pending : queued) {
            pending.run();
        }
        flushPendingViewAfterReady(pendingInvalidate ? "pending-ready" : "leaflet-ready");
        readyHandler.run();
        if (mode != MapBasemapMode.NEUTRAL) {
            adoptTileHealthFromJs("leaflet-ready");
            scheduleTileHealthAdopt("leaflet-ready");
        }
        if (MapTileProbe.enabled()) {
            dumpTileAudit("leaflet-ready");
            scheduleTileAuditDumps();
        }
    }

    /**
     * One invalidate (so Leaflet matches the JavaFX size) then the pending canvas view.
     * Provider scripts are flushed before this on first ready so tiles aim at Bengaluru, not 20,0.
     */
    private void flushPendingViewAfterReady(String reason) {
        pendingInvalidate = false;
        invalidateSize(reason);
        applyPendingView();
        int width = containerWidthPixels();
        int height = containerHeightPixels();
        if (Math.abs(width - lastDomWidth) > 8 || Math.abs(height - lastDomHeight) > 8) {
            scheduleInvalidateSize("ready-size-converge");
        }
    }

    // ------------------------------------------------------------------
    // Tile lifecycle
    // ------------------------------------------------------------------

    private void onTileEventFromJs(String kind, int generation, int errorCount) {
        Platform.runLater(() -> {
            if (disposed || generation != providerGeneration) {
                return;
            }
            tileErrorCount = Math.max(0, errorCount);
            switch (kind) {
                case "providerApplied" -> {
                    tileLayerPresent = true;
                    tileLayerLoading = false;
                    firstTileLoaded = false;
                    tileLayerLoadCompleted = false;
                    tileHealth = BasemapTileHealth.TILES_LOADING;
                }
                case "basemapCleared" -> {
                    tileLayerPresent = false;
                    tileLayerLoading = false;
                    firstTileLoaded = false;
                    tileLayerLoadCompleted = false;
                    if (mode != MapBasemapMode.NEUTRAL) {
                        tileHealth = BasemapTileHealth.LEAFLET_READY;
                    }
                }
                case "layerLoading" -> {
                    tileLayerLoading = true;
                    if (tileHealth != BasemapTileHealth.TILES_HEALTHY) {
                        tileHealth = BasemapTileHealth.TILES_LOADING;
                    }
                }
                case "firstTile" -> {
                    firstTileLoaded = true;
                    if (!tileLayerLoadCompleted) {
                        tileHealth = BasemapTileHealth.TILES_PARTIALLY_LOADED;
                    }
                }
                case "layerLoaded" -> {
                    tileLayerLoadCompleted = true;
                    if (tileErrorCount == 0) {
                        tileHealth = BasemapTileHealth.TILES_HEALTHY;
                        hideBanner();
                    } else if (tileErrorCount >= VISIBLE_TILE_FAIL_LIMIT) {
                        failVisibleTilesUnlessJsHealthy(kind);
                        return;
                    } else {
                        tileHealth = BasemapTileHealth.TILES_PARTIALLY_LOADED;
                    }
                    adoptTileHealthFromJs("layer-loaded");
                }
                case "tileError" -> {
                    if (!firstTileLoaded || tileErrorCount >= VISIBLE_TILE_FAIL_LIMIT) {
                        failVisibleTilesUnlessJsHealthy(kind);
                        return;
                    }
                    tileHealth = BasemapTileHealth.TILES_PARTIALLY_LOADED;
                    adoptTileHealthFromJs("tile-error");
                }
                case "tileStalled" -> {
                    handleTileStallFromJs();
                    return;
                }
                default -> {
                    return;
                }
            }
            tileStateHandler.run();
        });
    }

    private void handleTileStallFromJs() {
        TileHealthSnapshot snapshot = queryTileHealthSnapshot();
        if (applyAuthoritativeSnapshot(snapshot, "tile-stall")) {
            return;
        }
        if (snapshot != null && snapshot.generation() == providerGeneration && snapshot.partiallyLoaded()) {
            tileHealth = BasemapTileHealth.TILES_PARTIALLY_LOADED;
            if (snapshot.validNaturalWidth() > 0) {
                firstTileLoaded = true;
            }
            tileStateHandler.run();
            return;
        }
        failVisibleTiles("tileStalled");
    }

    private void failVisibleTilesUnlessJsHealthy(String kind) {
        TileHealthSnapshot snapshot = queryTileHealthSnapshot();
        if (applyAuthoritativeSnapshot(snapshot, kind)) {
            return;
        }
        failVisibleTiles(kind);
    }

    private void failVisibleTiles(String kind) {
        if (MapTileProbe.suppressFallback()) {
            LOG.warning(() -> "TILE-PROBE: suppressing tile fallback kind=" + kind
                    + " · " + diagnostics().describe());
            dumpTileAudit("tile-fail-suppressed-" + kind);
            tileStateHandler.run();
            return;
        }
        tileHealth = BasemapTileHealth.TILE_PROVIDER_FAILED;
        LOG.fine(() -> "Basemap " + mode + " " + kind + " · errors=" + tileErrorCount);
        if (mode == MapBasemapMode.ONLINE) {
            onlineTilesFailed = true;
        }
        tileStateHandler.run();
        modeFailedHandler.accept(mode);
    }

    private void onOfflineFailedFromJs(String detail) {
        Platform.runLater(() -> {
            if (disposed) {
                return;
            }
            LOG.fine(() -> "Offline PMTiles failed: " + detail);
            modeFailedHandler.accept(MapBasemapMode.OFFLINE_PMTILES);
        });
    }

    // ------------------------------------------------------------------
    // Chrome + scripting
    // ------------------------------------------------------------------

    /**
     * Temporary compositing probe. Does not change default production behaviour.
     * When suppressed, JavaFX labels stacked on the WebView are not shown.
     */
    void suppressJavaFxOverlayChrome(boolean suppress) {
        suppressJavaFxOverlayChrome = suppress;
        if (suppress) {
            showAttributionLabel(false);
            hideBanner();
        }
    }

    private void showAttributionLabel(boolean show) {
        boolean visible = show && !suppressJavaFxOverlayChrome;
        attribution.setVisible(visible);
        attribution.setManaged(visible);
    }

    private void showBanner(String text) {
        if (suppressJavaFxOverlayChrome) {
            return;
        }
        statusBanner.setText(text);
        statusBanner.setVisible(true);
        statusBanner.setManaged(true);
    }

    private void hideBanner() {
        statusBanner.setVisible(false);
        statusBanner.setManaged(false);
    }

    private void runScript(String script) {
        if (disposed || !htmlReady) {
            return;
        }
        try {
            webView.getEngine().executeScript(script);
        } catch (RuntimeException ex) {
            LOG.fine(() -> "Map script failed: " + ex.getMessage());
        }
    }

    private int[] queryDomSize() {
        String[] parts = queryDiagnosticsParts();
        if (parts.length >= 4) {
            try {
                return new int[]{Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim())};
            } catch (NumberFormatException ignored) {
                // fall through to last applied size
            }
        }
        return new int[]{lastDomWidth, lastDomHeight};
    }

    /**
     * Leaflet geographic viewport plus getSize(), for support logs only.
     * pendingView is the JavaFX canvas contract; the suffix is what Leaflet actually holds.
     */
    private String leafletViewportSnapshot() {
        String canvas = pendingView == null ? "none" : pendingView.describe();
        String[] parts = queryDiagnosticsParts();
        if (parts.length >= 19) {
            return canvas + " leafletSize=" + parts[11] + "x" + parts[12]
                    + " center=" + parts[13] + "," + parts[14]
                    + " zoom=" + parts[10]
                    + " bounds=" + parts[15] + "," + parts[16] + "," + parts[17] + "," + parts[18];
        }
        if (parts.length >= 11) {
            return canvas + " zoom=" + parts[10];
        }
        return canvas;
    }

    private String[] queryDiagnosticsParts() {
        if (disposed || !htmlReady) {
            return new String[0];
        }
        try {
            Object raw = webView.getEngine().executeScript("window.stellarMapDiagnostics();");
            return String.valueOf(raw).split("\\|");
        } catch (RuntimeException ex) {
            LOG.fine(() -> "Map diagnostics unavailable: " + ex.getMessage());
            return new String[0];
        }
    }

    private int queryJsMapInitCount() {
        String[] parts = queryDiagnosticsParts();
        if (parts.length == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void scheduleTileHealthAdopt(String reason) {
        if (disposed) {
            return;
        }
        PauseTransition early = new PauseTransition(Duration.millis(400));
        early.setOnFinished(e -> adoptTileHealthFromJs(reason + "-400ms"));
        early.play();
        PauseTransition later = new PauseTransition(Duration.seconds(2));
        later.setOnFinished(e -> adoptTileHealthFromJs(reason + "-2s"));
        later.play();
    }

    private void adoptTileHealthFromJs(String reason) {
        if (disposed || !htmlReady || mode == MapBasemapMode.NEUTRAL) {
            return;
        }
        applyAuthoritativeSnapshot(queryTileHealthSnapshot(), reason);
    }

    /**
     * JS DOM/Leaflet state is authoritative. LiveConnect misses must not keep Java in LOADING
     * or trigger a false provider failure.
     *
     * @return {@code true} when a healthy snapshot was adopted
     */
    private boolean applyAuthoritativeSnapshot(TileHealthSnapshot snapshot, String reason) {
        if (snapshot == null) {
            return false;
        }
        if (snapshot.generation() != providerGeneration) {
            LOG.fine(() -> "Ignoring tile health snapshot gen=" + snapshot.generation()
                    + " current=" + providerGeneration + " · " + reason);
            return false;
        }
        if (snapshot.healthy()) {
            boolean missedLiveConnect = !firstTileLoaded
                    || tileHealth != BasemapTileHealth.TILES_HEALTHY;
            firstTileLoaded = true;
            tileLayerPresent = true;
            tileLayerLoadCompleted = true;
            tileLayerLoading = false;
            tileHealth = BasemapTileHealth.TILES_HEALTHY;
            hideBanner();
            if (missedLiveConnect) {
                adoptedTileHealthFromJs = true;
                LOG.fine("Adopting tile health from JS snapshot; LiveConnect event was missed");
            }
            LOG.fine(() -> "Tile health HEALTHY from JS snapshot · " + reason
                    + " · current=" + snapshot.currentTiles());
            tileStateHandler.run();
            return true;
        }
        if (tileHealth == BasemapTileHealth.TILES_HEALTHY
                || tileHealth == BasemapTileHealth.NEUTRAL_FALLBACK) {
            return false;
        }
        if (snapshot.providerFailed()) {
            // Opportunistic adopts must not fail the provider: LiveConnect can be late while
            // erroring tiles are still in flight. The stall watchdog is the bounded failure path.
            return false;
        }
        if (tileHealth == BasemapTileHealth.TILE_PROVIDER_FAILED) {
            return false;
        }
        if (snapshot.partiallyLoaded()) {
            firstTileLoaded = firstTileLoaded || snapshot.validNaturalWidth() > 0 || snapshot.tileFirst();
            tileLayerPresent = true;
            tileHealth = BasemapTileHealth.TILES_PARTIALLY_LOADED;
            if (snapshot.tileDone()) {
                tileLayerLoadCompleted = true;
            }
            tileStateHandler.run();
            return false;
        }
        if (snapshot.loading() && tileHealth != BasemapTileHealth.TILES_PARTIALLY_LOADED) {
            tileLayerPresent = snapshot.layerExists();
            tileHealth = BasemapTileHealth.TILES_LOADING;
        }
        return false;
    }

    private TileHealthSnapshot queryTileHealthSnapshot() {
        if (disposed || !htmlReady) {
            return null;
        }
        try {
            Object raw = webView.getEngine().executeScript(
                    "window.stellarTileHealthSnapshot ? window.stellarTileHealthSnapshot() : ''");
            return TileHealthSnapshot.parse(String.valueOf(raw));
        } catch (RuntimeException ex) {
            LOG.fine(() -> "Tile health snapshot unavailable: " + ex.getMessage());
            return null;
        }
    }

    private static String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("<[^>]+>", "").replace("&copy;", "©").trim();
    }

    private static boolean jsTruthy(Object raw) {
        return Boolean.TRUE.equals(raw) || "true".equalsIgnoreCase(String.valueOf(raw));
    }

    private static String escapeJs(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", "");
    }

    /**
     * Centre and integer zoom for Leaflet, derived from the shared {@link MapCamera}.
     */
    record MapViewState(double centerLatitude, double centerLongitude, double zoom) {

        static MapViewState of(MapViewport viewport, int providerMaxZoom) {
            MapCamera camera = MapCamera.of(viewport);
            double latitude = camera.centerLatitude();
            double longitude = camera.centerLongitude();
            int zoom = Math.min(providerMaxZoom, camera.zoom());
            if (!finite(latitude) || !finite(longitude)) {
                return null;
            }
            return new MapViewState(
                    Math.max(-85.0, Math.min(85.0, latitude)),
                    Math.max(-180.0, Math.min(180.0, longitude)),
                    Math.max(MapScale.MIN_ZOOM, zoom));
        }

        private static boolean finite(double value) {
            return !Double.isNaN(value) && !Double.isInfinite(value);
        }

        String describe() {
            return String.format(Locale.ROOT, "lat%.5f lon%.5f z%.4f", centerLatitude, centerLongitude, zoom);
        }
    }

    static String basemapHtml() {
        String leafletCss = MapWebAssets.leafletCss();
        String leafletJs = MapWebAssets.leafletJs();
        String pmtilesJs = MapWebAssets.pmtilesJs();
        // Concatenate — do not use formatted()/printf: Leaflet CSS contains '%'.
        return "<!DOCTYPE html>\n"
                + "<html><head>\n"
                + "<meta charset=\"utf-8\"/>\n"
                + "<style>\n"
                + leafletCss
                + "\nhtml,body,#map{margin:0;padding:0;width:100%;height:100%;overflow:hidden;background:"
                + MapPalette.BASEMAP_HTML_BACKGROUND + ";}\n"
                + "html,body{position:absolute;inset:0;}\n"
                + ".leaflet-control-attribution{font-size:10px;}\n"
                + "</style>\n"
                + "<script>" + leafletJs + "</script>\n"
                + "<script>" + pmtilesJs + "</script>\n"
                + "</head><body>\n"
                + "<div id=\"map\"></div>\n"
                + "<script>\n"
                + "var MIN_MAP_PX = " + MIN_MAP_PIXELS + ";\n"
                + "var TILE_ERROR_LIMIT = 4;\n"
                + "var TILE_STALL_MS = " + (MapTileProbe.suppressFallback() ? 3_600_000 : 12_000) + ";\n"
                + "var map = null;\n"
                + "var baseLayer = null;\n"
                + "var currentTileUrl = null;\n"
                + "var tileErrors = 0;\n"
                + "var tileLoading = false;\n"
                + "var tileFirst = false;\n"
                + "var tileDone = false;\n"
                + "var stallTimer = null;\n"
                + "var providerGen = 0;\n"
                + "var pendingView = null;\n"
                + "var mapInitCount = 0;\n"
                + "var STELLAR_WEBVIEW_2D = true;\n"
                + "function notifyTiles(kind) {\n"
                + "  if (window.stellarBridge && window.stellarBridge.tileEvent) {\n"
                + "    try { window.stellarBridge.tileEvent(kind, providerGen, tileErrors); } catch (e) {}\n"
                + "  }\n"
                + "}\n"
                + "function resetTileState() {\n"
                + "  tileErrors = 0; tileLoading = false; tileFirst = false; tileDone = false;\n"
                + "  if (stallTimer) { window.clearTimeout(stallTimer); stallTimer = null; }\n"
                + "}\n"
                + "function armStallWatchdog(layer) {\n"
                + "  stallTimer = window.setTimeout(function() {\n"
                + "    stallTimer = null;\n"
                + "    if (layer === baseLayer && !tileDone) { notifyTiles('tileStalled'); }\n"
                + "  }, TILE_STALL_MS);\n"
                + "}\n"
                + "function bindTileEvents(layer) {\n"
                + "  layer.on('loading', function() {\n"
                + "    if (layer !== baseLayer || tileLoading) return;\n"
                + "    tileLoading = true; notifyTiles('layerLoading');\n"
                + "  });\n"
                + "  layer.on('tileload', function() {\n"
                + "    if (layer !== baseLayer || tileFirst) return;\n"
                + "    tileFirst = true; notifyTiles('firstTile');\n"
                + "  });\n"
                + "  layer.on('load', function() {\n"
                + "    if (layer !== baseLayer || tileDone) return;\n"
                + "    tileDone = true; notifyTiles('layerLoaded');\n"
                + "  });\n"
                + "  layer.on('tileerror', function() {\n"
                + "    if (layer !== baseLayer) return;\n"
                + "    tileErrors++;\n"
                + "    if (tileErrors === TILE_ERROR_LIMIT) { notifyTiles('tileError'); }\n"
                + "  });\n"
                + "}\n"
                + "function sizeMapElement(w, h) {\n"
                + "  var el = document.getElementById('map');\n"
                + "  if (!el || !(w > 0) || !(h > 0)) return;\n"
                + "  el.style.width = w + 'px';\n"
                + "  el.style.height = h + 'px';\n"
                + "  document.documentElement.style.width = w + 'px';\n"
                + "  document.documentElement.style.height = h + 'px';\n"
                + "  document.body.style.width = w + 'px';\n"
                + "  document.body.style.height = h + 'px';\n"
                + "}\n"
                + "function mapDomSizeValid() {\n"
                + "  if (!map) return false;\n"
                + "  var size = map.getSize();\n"
                + "  return !!size && size.x >= MIN_MAP_PX && size.y >= MIN_MAP_PX;\n"
                + "}\n"
                + "function mapContainerUsable() {\n"
                + "  var el = document.getElementById('map');\n"
                + "  return !!el && el.clientWidth >= MIN_MAP_PX && el.clientHeight >= MIN_MAP_PX;\n"
                + "}\n"
                + "function mapIsUsable() {\n"
                + "  return !!map && mapContainerUsable() && mapDomSizeValid();\n"
                + "}\n"
                + "function notifyLeafletReadyIfUsable() {\n"
                + "  if (!mapIsUsable()) return false;\n"
                + "  if (window.stellarBridge && window.stellarBridge.leafletReady) {\n"
                + "    try { window.stellarBridge.leafletReady(); } catch (e) {}\n"
                + "  }\n"
                + "  return true;\n"
                + "}\n"
                + "window.stellarMap = {\n"
                + "  invalidateSize: function(w, h) {\n"
                + "    if (!map) return;\n"
                + "    try {\n"
                + "      sizeMapElement(w, h);\n"
                + "      map.invalidateSize(true);\n"
                + "      applyPendingView();\n"
                + "    } catch (e) {}\n"
                + "  }\n"
                + "};\n"
                + "window.stellarInitMap = function(w, h) {\n"
                + "  sizeMapElement(w, h);\n"
                + "  if (map) {\n"
                + "    try { map.invalidateSize(true); } catch (e) {}\n"
                + "    return notifyLeafletReadyIfUsable();\n"
                + "  }\n"
                + "  if (!(w >= MIN_MAP_PX) || !(h >= MIN_MAP_PX) || !mapContainerUsable()) {\n"
                + "    return false;\n"
                + "  }\n"
                + "  mapInitCount++;\n"
                + "  if (window.L && L.Browser) {\n"
                + "    L.Browser.any3d = false;\n"
                + "    L.Browser.webkit3d = false;\n"
                + "    L.Browser.gecko3d = false;\n"
                + "  }\n"
                + "  map = L.map('map', {zoomControl:false, attributionControl:true, dragging:false,\n"
                + "    scrollWheelZoom:false, doubleClickZoom:false, boxZoom:false, keyboard:false,\n"
                + "    zoomSnap:1, zoomDelta:1, zoomAnimation:false, fadeAnimation:false,\n"
                + "    markerZoomAnimation:false});\n"
                + "  map.setView([20,0], 2);\n"
                + "  try { map.invalidateSize(true); } catch (e) {}\n"
                + "  return notifyLeafletReadyIfUsable();\n"
                + "};\n"
                + "window.stellarResizeMap = function(w, h) {\n"
                + "  window.stellarMap.invalidateSize(w, h);\n"
                + "};\n"
                + "window.stellarClearBasemap = function(gen) {\n"
                + "  if (typeof gen === 'number') { providerGen = gen; }\n"
                + "  resetTileState();\n"
                + "  currentTileUrl = null;\n"
                + "  if (map && baseLayer) { try { map.removeLayer(baseLayer); } catch (e) {} }\n"
                + "  baseLayer = null;\n"
                + "  notifyTiles('basemapCleared');\n"
                + "};\n"
                + "window.stellarSetOnlineProvider = function(url, maxZoom, attribution, gen) {\n"
                + "  if (!map) return;\n"
                + "  providerGen = gen || 0;\n"
                + "  if (baseLayer && currentTileUrl === url) {\n"
                + "    if (typeof gen === 'number') { providerGen = gen; }\n"
                + "    return;\n"
                + "  }\n"
                + "  window.stellarClearBasemap();\n"
                + "  currentTileUrl = url;\n"
                + "  baseLayer = L.tileLayer(url, { maxZoom: maxZoom || 19, tileSize: 256, attribution: attribution || '' });\n"
                + "  bindTileEvents(baseLayer);\n"
                + "  notifyTiles('providerApplied');\n"
                + "  baseLayer.addTo(map);\n"
                + "  armStallWatchdog(baseLayer);\n"
                + "};\n"
                + "window.stellarSetOfflinePmtiles = function(url, attribution, gen) {\n"
                + "  providerGen = gen || 0;\n"
                + "  window.stellarClearBasemap();\n"
                + "  if (!map || !window.pmtiles) {\n"
                + "    if (window.stellarBridge && window.stellarBridge.offlineFailed) {\n"
                + "      window.stellarBridge.offlineFailed('pmtiles-unavailable');\n"
                + "    }\n"
                + "    return;\n"
                + "  }\n"
                + "  var requestedGen = providerGen;\n"
                + "  try {\n"
                + "    var p = new pmtiles.PMTiles(url);\n"
                + "    p.getHeader().then(function(h) {\n"
                + "      try {\n"
                + "        if (requestedGen !== providerGen) { return; }\n"
                + "        if (h.tileType === 1) {\n"
                + "          if (window.stellarBridge && window.stellarBridge.offlineFailed) {\n"
                + "            window.stellarBridge.offlineFailed('vector-pmtiles-unsupported');\n"
                + "          }\n"
                + "          return;\n"
                + "        }\n"
                + "        baseLayer = pmtiles.leafletRasterLayer(p, {\n"
                + "          attribution: attribution || '',\n"
                + "          maxNativeZoom: h.maxZoom\n"
                + "        });\n"
                + "        bindTileEvents(baseLayer);\n"
                + "        notifyTiles('providerApplied');\n"
                + "        baseLayer.addTo(map);\n"
                + "        armStallWatchdog(baseLayer);\n"
                + "      } catch (err) {\n"
                + "        if (window.stellarBridge && window.stellarBridge.offlineFailed) {\n"
                + "          window.stellarBridge.offlineFailed(String(err));\n"
                + "        }\n"
                + "      }\n"
                + "    }).catch(function(err) {\n"
                + "      if (window.stellarBridge && window.stellarBridge.offlineFailed) {\n"
                + "        window.stellarBridge.offlineFailed(String(err));\n"
                + "      }\n"
                + "    });\n"
                + "  } catch (err) {\n"
                + "    if (window.stellarBridge && window.stellarBridge.offlineFailed) {\n"
                + "      window.stellarBridge.offlineFailed(String(err));\n"
                + "    }\n"
                + "  }\n"
                + "};\n"
                + "window.stellarSetView = function(lat, lon, zoom) {\n"
                + "  if (!map) return;\n"
                + "  pendingView = [lat, lon, Math.round(zoom)];\n"
                + "  applyPendingView();\n"
                + "};\n"
                // Shared integer camera: the canvas has already chosen the WebView-safe zoom.
                + "function applyPendingView() {\n"
                + "  if (!map || !pendingView) return;\n"
                + "  try {\n"
                + "    map.invalidateSize(true);\n"
                + "    if (!mapDomSizeValid()) return;\n"
                + "    map.setView(L.latLng(pendingView[0], pendingView[1]), pendingView[2],\n"
                + "      {animate:false, reset:true});\n"
                + "  } catch (e) {}\n"
                + "}\n"
                + "window.stellarMapDiagnostics = function() {\n"
                + "  var layers = 0;\n"
                + "  if (map) { map.eachLayer(function() { layers++; }); }\n"
                + "  var el = document.getElementById('map');\n"
                + "  var size = map ? map.getSize() : {x:0,y:0};\n"
                + "  var c = map ? map.getCenter() : null;\n"
                + "  var b = map ? map.getBounds() : null;\n"
                + "  return (mapInitCount + '|' + layers + '|' + (el ? el.clientWidth : 0)\n"
                + "    + '|' + (el ? el.clientHeight : 0) + '|' + (currentTileUrl ? '1' : '0')\n"
                + "    + '|' + (tileLoading ? '1' : '0') + '|' + (tileFirst ? '1' : '0')\n"
                + "    + '|' + (tileDone ? '1' : '0') + '|' + tileErrors + '|' + providerGen\n"
                + "    + '|' + (map ? map.getZoom() : -1)\n"
                + "    + '|' + (size.x || 0) + '|' + (size.y || 0)\n"
                + "    + '|' + (c ? c.lat : 0) + '|' + (c ? c.lng : 0)\n"
                + "    + '|' + (b ? b.getSouth() : 0) + '|' + (b ? b.getWest() : 0)\n"
                + "    + '|' + (b ? b.getNorth() : 0) + '|' + (b ? b.getEast() : 0));\n"
                + "};\n"
                + "window.stellarTileHealthSnapshot = function() {\n"
                + "  var current=0, complete=0, valid=0, failed=0, visible=0;\n"
                + "  var mapEl = document.getElementById('map');\n"
                + "  var mapRect = mapEl ? mapEl.getBoundingClientRect() : {left:0,top:0,right:0,bottom:0};\n"
                + "  if (baseLayer && baseLayer._tiles) {\n"
                + "    for (var k in baseLayer._tiles) {\n"
                + "      var rec = baseLayer._tiles[k];\n"
                + "      if (!rec || !rec.current) continue;\n"
                + "      current++;\n"
                + "      var el = rec.el;\n"
                + "      if (!el) { failed++; continue; }\n"
                + "      var done = !!el.complete;\n"
                + "      var nw = el.naturalWidth || 0;\n"
                + "      if (done) complete++;\n"
                + "      if (done && nw > 0) valid++;\n"
                + "      if (done && nw === 0) failed++;\n"
                + "      var r = el.getBoundingClientRect();\n"
                + "      var intersects = !(r.right < mapRect.left || r.left > mapRect.right\n"
                + "        || r.bottom < mapRect.top || r.top > mapRect.bottom);\n"
                + "      if (intersects && nw > 0) visible++;\n"
                + "    }\n"
                + "  }\n"
                + "  var usable = !!map && mapIsUsable();\n"
                + "  return providerGen + '|' + (baseLayer ? 1 : 0) + '|' + current + '|' + complete\n"
                + "    + '|' + valid + '|' + failed + '|' + visible\n"
                + "    + '|' + (tileFirst ? 1 : 0) + '|' + (tileDone ? 1 : 0) + '|' + (usable ? 1 : 0);\n"
                + "};\n"
                + tileAuditJs()
                + "</script>\n"
                + "</body></html>\n";
    }

    private static String tileAuditJs() {
        return ""
                + "window.stellarTileAudit = function() {\n"
                + "  var size = map ? map.getSize() : {x:0,y:0};\n"
                + "  var c = map ? map.getCenter() : null;\n"
                + "  var b = map ? map.getBounds() : null;\n"
                + "  var mapEl = document.getElementById('map');\n"
                + "  var mapRect = mapEl ? mapEl.getBoundingClientRect() : {left:0,top:0,right:0,bottom:0};\n"
                + "  var imgs = document.querySelectorAll('img.leaflet-tile');\n"
                + "  var tiles = [];\n"
                + "  var completeN=0, decodedN=0, visibleN=0, failedN=0, offN=0;\n"
                + "  var maxTiles = Math.min(imgs.length, 24);\n"
                + "  for (var i=0;i<imgs.length;i++) {\n"
                + "    var el = imgs[i];\n"
                + "    var r = el.getBoundingClientRect();\n"
                + "    var cs = window.getComputedStyle(el);\n"
                + "    var intersects = !(r.right < mapRect.left || r.left > mapRect.right || r.bottom < mapRect.top || r.top > mapRect.bottom);\n"
                + "    var complete = !!el.complete;\n"
                + "    var nw = el.naturalWidth||0;\n"
                + "    if (complete) completeN++;\n"
                + "    if (nw>0) decodedN++;\n"
                + "    if (complete && nw===0) failedN++;\n"
                + "    if (!intersects) offN++;\n"
                + "    var vis = intersects && cs.visibility!=='hidden' && cs.display!=='none' && cs.opacity!=='0';\n"
                + "    if (vis && nw>0) visibleN++;\n"
                + "    if (i>=maxTiles) continue;\n"
                + "    var coords='';\n"
                + "    if (baseLayer && baseLayer._tiles) {\n"
                + "      for (var k in baseLayer._tiles) {\n"
                + "        var rec = baseLayer._tiles[k];\n"
                + "        if (rec && rec.el===el) { coords=k; break; }\n"
                + "      }\n"
                + "    }\n"
                + "    var current=null;\n"
                + "    if (coords && baseLayer._tiles[coords]) current=!!baseLayer._tiles[coords].current;\n"
                + "    tiles.push({src:(el.src||'').substring(0,140),complete:complete,nw:nw,nh:el.naturalHeight||0,\n"
                + "      cssW:cs.width,cssH:cs.height,opacity:cs.opacity,vis:cs.visibility,disp:cs.display,\n"
                + "      cls:String(el.className),x:Math.round(r.x),y:Math.round(r.y),w:Math.round(r.width),h:Math.round(r.height),\n"
                + "      transform:el.style.transform||'',left:el.style.left||'',top:el.style.top||'',\n"
                + "      intersects:intersects,coords:coords,current:current});\n"
                + "  }\n"
                + "  var internal=0, currentKeys=0, levels=0;\n"
                + "  if (baseLayer && baseLayer._tiles) {\n"
                + "    for (var tk in baseLayer._tiles) { internal++; if (baseLayer._tiles[tk] && baseLayer._tiles[tk].current) currentKeys++; }\n"
                + "  }\n"
                + "  if (baseLayer && baseLayer._levels) { for (var lk in baseLayer._levels) levels++; }\n"
                + "  var tilePane = document.querySelector('.leaflet-tile-pane');\n"
                + "  var mapPane = document.querySelector('.leaflet-map-pane');\n"
                + "  var levelTf=[];\n"
                + "  var zels = document.querySelectorAll('.leaflet-tile-pane .leaflet-zoom-animated, .leaflet-tile-pane .leaflet-zoom-hide');\n"
                + "  for (var zi=0; zi<zels.length && zi<8; zi++) {\n"
                + "    levelTf.push({cls:zels[zi].className, transform:zels[zi].style.transform||'', left:zels[zi].style.left||'', top:zels[zi].style.top||''});\n"
                + "  }\n"
                + "  return JSON.stringify({map:{center:c?{lat:c.lat,lng:c.lng}:null, zoom:map?map.getZoom():null,\n"
                + "      size:{x:size.x||0,y:size.y||0},\n"
                + "      bounds:b?{s:b.getSouth(),w:b.getWest(),n:b.getNorth(),e:b.getEast()}:null},\n"
                + "    layer:{exists:!!baseLayer, url:currentTileUrl||'', internalTiles:internal, currentTiles:currentKeys, levels:levels,\n"
                + "      tilePaneChildren: tilePane?tilePane.childElementCount:0},\n"
                + "    panes:{mapTransform: mapPane?(mapPane.style.transform||''):'', tileTransform: tilePane?(tilePane.style.transform||''):'', levels:levelTf},\n"
                + "    jsFlags:{tileFirst:!!tileFirst,tileDone:!!tileDone,tileLoading:!!tileLoading,tileErrors:tileErrors,providerGen:providerGen},\n"
                + "    summary:{dom:imgs.length, complete:completeN, decoded:decodedN, visible:visibleN, failed:failedN, offscreen:offN, expected:internal, current:currentKeys},\n"
                + "    tiles:tiles});\n"
                + "};\n"
                + "window.stellarGridAudit = window.stellarGridAudit || function() { return JSON.stringify({grid:false}); };\n";
    }

    private String diagnosticGridHtml() {
        boolean osm = MapTileProbe.gridOsm();
        StringBuilder images = new StringBuilder();
        int index = 0;
        if (osm) {
            int z = 11;
            for (int y = 947; y <= 950; y++) {
                for (int x = 1464; x <= 1467; x++) {
                    String url = "https://tile.openstreetmap.org/" + z + "/" + x + "/" + y + ".png";
                    images.append(gridImageTag(index++, url, z + "/" + x + "/" + y));
                }
            }
        } else {
            String base = probeAssetBaseUrl;
            int z = 11;
            int i = 0;
            for (int y = 947; y <= 950; y++) {
                for (int x = 1464; x <= 1467; x++) {
                    String url = base + "/probe/" + z + "/" + x + "/" + y + ".png";
                    images.append(gridImageTag(i++, url, z + "/" + x + "/" + y));
                }
            }
        }
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><style>\n"
                + "html,body{margin:0;padding:8px;background:#222;color:#eee;font:12px monospace;}\n"
                + ".grid{display:grid;grid-template-columns:repeat(4,256px);grid-gap:4px;}\n"
                + ".cell{width:256px;height:256px;background:#444;position:relative;overflow:hidden;}\n"
                + ".cell img{width:256px;height:256px;display:block;border:0;}\n"
                + ".cell span{position:absolute;left:4px;top:4px;background:#000a;padding:2px 4px;}\n"
                + "</style></head><body>\n"
                + "<div id=\"status\">grid loading…</div>\n"
                + "<div class=\"grid\">" + images + "</div>\n"
                + "<script>\n"
                + "var results=[];\n"
                + "function note(i,url,kind,img){\n"
                + "  results[i]={i:i,url:url,kind:kind,complete:!!(img&&img.complete),nw:img?img.naturalWidth:0,nh:img?img.naturalHeight:0};\n"
                + "  var ok=results.filter(function(r){return r&&r.nw>0;}).length;\n"
                + "  var err=results.filter(function(r){return r&&r.kind==='error';}).length;\n"
                + "  document.getElementById('status').textContent='loaded '+ok+'/16 errors '+err+'/16';\n"
                + "}\n"
                + "window.stellarGridAudit=function(){\n"
                + "  var imgs=document.querySelectorAll('img');\n"
                + "  var rows=[];\n"
                + "  for(var i=0;i<imgs.length;i++){\n"
                + "    var el=imgs[i];\n"
                + "    rows.push({src:el.getAttribute('data-url'),complete:!!el.complete,nw:el.naturalWidth||0,nh:el.naturalHeight||0,err:el.getAttribute('data-err')==='1'});\n"
                + "  }\n"
                + "  return JSON.stringify({grid:true,count:rows.length,decoded:rows.filter(function(r){return r.nw>0;}).length,errors:rows.filter(function(r){return r.err;}).length,images:rows});\n"
                + "};\n"
                + "window.stellarInitMap=function(){ return true; };\n"
                + "window.stellarMapDiagnostics=function(){ return '1|0|256|256|1|0|1|1|0|0|11|256|256|13.12|77.62|13|77|13.3|77.9'; };\n"
                + "window.stellarTileHealthSnapshot=function(){ return '0|0|0|0|0|0|0|0|0|0'; };\n"
                + "</script></body></html>\n";
    }

    private static String gridImageTag(int index, String url, String label) {
        String escaped = url.replace("&", "&amp;").replace("\"", "&quot;");
        return "<div class=\"cell\"><span>" + label + "</span>"
                + "<img data-url=\"" + escaped + "\" src=\"" + escaped + "\""
                + " onload=\"this.setAttribute('data-err','0');note(" + index + ",this.getAttribute('data-url'),'load',this)\""
                + " onerror=\"this.setAttribute('data-err','1');note(" + index + ",this.getAttribute('data-url'),'error',this)\""
                + "/></div>";
    }

    void dumpTileAudit(String when) {
        if (!MapTileProbe.enabled() || disposed) {
            return;
        }
        try {
            String script = MapTileProbe.grid()
                    ? "window.stellarGridAudit ? window.stellarGridAudit() : '{}'"
                    : "window.stellarTileAudit ? window.stellarTileAudit() : '{}'";
            Object raw = htmlReady ? webView.getEngine().executeScript(script) : "(html not ready)";
            String ua = webView.getEngine().getUserAgent();
            String line = Instant.now() + " " + when
                    + " probe=" + MapTileProbe.mode()
                    + " ua=" + ua
                    + " java=" + diagnostics().describe()
                    + " audit=" + raw;
            LOG.warning("TILE-AUDIT " + line);
            if (!com.stellar.platform.storage.analysis.AnalysisStorageRoots.isConfigured()) {
                return;
            }
            Path dir = com.stellar.platform.storage.analysis.AnalysisStorageRoots.logsRoot();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("tile-audit.log"), line + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ex) {
            LOG.warning("TILE-AUDIT failed: " + ex.getMessage());
        }
    }

    private void scheduleTileAuditDumps() {
        PauseTransition t3 = new PauseTransition(Duration.seconds(3));
        t3.setOnFinished(e -> dumpTileAudit("t+3s"));
        t3.play();
        PauseTransition t8 = new PauseTransition(Duration.seconds(8));
        t8.setOnFinished(e -> dumpTileAudit("t+8s"));
        t8.play();
    }

    boolean htmlReadyForTest() {
        return htmlReady;
    }

    int invalidateRequestsForTest() {
        return invalidateRequests;
    }

    int onlineProviderAppliesForTest() {
        return onlineProviderApplies;
    }

    int leafletInitCountForTest() {
        return leafletInitCount;
    }

    int leafletReadyCallbackCountForTest() {
        return leafletReadyCallbackCount;
    }

    /**
     * Java forgot {@code leafletReady} while the JS map remains. Mirrors the real-desktop miss.
     */
    void forgetLeafletReadyForTest() {
        leafletReady = false;
        leafletInitRequested = false;
        readinessRetried = false;
        readinessFailed = false;
    }

    void requestLeafletInitForTest() {
        initLeaflet(containerWidthPixels(), containerHeightPixels());
    }

    void readinessTimeoutUnforcedForTest() {
        onReadinessTimeout(false);
    }

    void seedPendingViewForTest(double latitude, double longitude, double zoom) {
        pendingView = new MapViewState(latitude, longitude, zoom);
        applyPendingView();
    }

    void deliverLeafletReadyFromJsForTest() {
        handleLeafletReadyFromJs();
    }

    int resizeGenerationForTest() {
        return resizeCoalescer.generation();
    }

    boolean domSizeValidForTest() {
        return domSizeValid;
    }

    int providerGenerationForTest() {
        return providerGeneration;
    }

    void beginOnlineGenerationForTest() {
        mode = MapBasemapMode.ONLINE;
        onlineTilesFailed = false;
        tileHealth = BasemapTileHealth.TILES_LOADING;
        firstTileLoaded = false;
        tileLayerLoadCompleted = false;
        tileLayerPresent = true;
        tileLayerLoading = true;
        tileErrorCount = 0;
        appliedOnlineUrl = "test://tiles/{z}/{x}/{y}.png";
        providerGeneration++;
        onlineProviderApplies++;
    }

    void adoptTileHealthSnapshotForTest(String snapshot) {
        TileHealthSnapshot parsed = TileHealthSnapshot.parse(snapshot);
        if (parsed != null && parsed.providerFailed()) {
            failVisibleTiles("js-snapshot");
            return;
        }
        applyAuthoritativeSnapshot(parsed, "test");
    }

    boolean firstTileLoadedForTest() {
        return firstTileLoaded;
    }

    boolean adoptedTileHealthFromJsForTest() {
        return adoptedTileHealthFromJs;
    }

    String pendingViewForTest() {
        return pendingView == null ? "" : pendingView.describe();
    }

    boolean readinessFailedForTest() {
        return readinessFailed;
    }

    void failReadinessForTest() {
        onReadinessTimeout(true);
    }

    WebView webViewForTest() {
        return webView;
    }

    void runScriptForTest(String script) {
        runScript(script);
    }

    void tileEventForTest(String kind, int generation, int errorCount) {
        onTileEventFromJs(kind, generation, errorCount);
    }

    /** JS-callable bridge; public methods required by WebKit. */
    public final class MapJsBridge {
        public void leafletReady() {
            onLeafletReadyFromJs();
        }

        /** Coalesced tile lifecycle signal; only first occurrences cross the bridge. */
        public void tileEvent(String kind, int generation, int errorCount) {
            onTileEventFromJs(kind == null ? "" : kind, generation, errorCount);
        }

        public void offlineFailed(String detail) {
            onOfflineFailedFromJs(detail == null ? "" : detail);
        }
    }
}
