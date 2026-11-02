package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.desktop.ui.canvas.CanvasRenderSupport;
import com.stellar.platform.desktop.ui.canvas.CanvasRenderSupport.PaintGate;
import com.stellar.platform.processing.map.MapRoute;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.processing.map.RouteSegment;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Single-canvas route renderer.
 *
 * <p>No JavaFX node is created per GPS sample: the whole route is one batched path on one
 * {@link Canvas}, plus a fixed set of drawn markers (start, end, A, B, selected) and exactly one
 * reusable tooltip. A 1M-sample route therefore costs the same number of scene-graph nodes as an
 * empty one.
 *
 * <p>Rendering uses {@code RouteLodIndex} decimation driven by the current degrees-per-pixel, so
 * the number of stroked segments is bounded by screen resolution rather than sample count. Picking
 * always resolves through {@code RouteSpatialIndex} back to an <em>original</em> sample index.
 */
public final class RouteCanvas extends Region {

    private static final double FIT_MARGIN = 34;
    private static final double CLICK_SLOP_PIXELS = 4;
    /** Hover is a nearby sample, not the nearest point anywhere on the canvas. */
    private static final double HOVER_RADIUS_PX = 16;
    private static final double PIN_OUTER = 8.5;
    private static final double PIN_INNER = 6.5;
    /** Screen pixels. Recalculated from the current projection on every paint. */
    private static final double MARKER_COLLISION_PX = 18;
    private static final long HOVER_THROTTLE_NANOS = 30_000_000L;
    /** Above this pixel gap between decimated points, sparse sample dots become useful. */
    private static final double DOT_VISIBILITY_PIXELS = 14;
    private static final int MAX_DOTS = 400;

    private static final Color ROUTE_ACTIVE = MapPalette.ROUTE_ACTIVE;
    private static final Color ROUTE_SUBDUED = MapPalette.ROUTE_SUBDUED;
    private static final Color ROUTE_CASING = MapPalette.ROUTE_CASING;
    private static final Color DOT_FILL = MapPalette.DOT_FILL;
    private static final Color START_FILL = MapPalette.START_FILL;
    private static final Color END_FILL = MapPalette.END_FILL;
    private static final Color AB_FILL = MapPalette.AB_FILL;
    private static final Color SELECTED_FILL = MapPalette.SELECTED_FILL;
    private static final Color LABEL_TEXT = MapPalette.LABEL_TEXT;
    private static final Color SCALE_TEXT = MapPalette.SCALE_TEXT;
    private static final Color[] SEGMENT_COLORS = {
            Color.web("#2563EB"),
            Color.web("#059669"),
            Color.web("#D97706"),
            Color.web("#7C3AED"),
            Color.web("#DB2777"),
            Color.web("#0F766E")
    };
    private static final Font MARKER_FONT = Font.font(com.stellar.platform.desktop.ui.theme.StellarTheme.FONT_UI_FAMILY, FontWeight.BOLD, 10);
    private static final Font SCALE_FONT = Font.font(com.stellar.platform.desktop.ui.theme.StellarTheme.FONT_UI_FAMILY, 10);

    private final Text textMeasure = new Text();
    private final Canvas canvas = new Canvas();
    private final MapViewport viewport = new MapViewport();
    private final Label hoverInfo = new Label();
    private final VBox controls = new VBox(4);
    private MapBackgroundProvider background;

    private MapRoute route;
    private String routeMeasurementName = "";
    private List<RouteSegment> overlaySegments = List.of();
    /** One recording uses its own ends. All Measurements uses one collection start and end. */
    private EndpointPolicy endpointPolicy = EndpointPolicy.OWN_ROUTE;

    public enum EndpointPolicy {
        /** Polylines only. No start, end, or numbered pins. */
        NONE,
        /** The one recording on the canvas. */
        OWN_ROUTE,
        /** Every independent recording. Not used for All Measurements. */
        EACH_ROUTE,
        /** First and last sample of the displayed collection. Routes stay disconnected. */
        GLOBAL
    }
    private RouteSegment selectedSegment;
    private BiConsumer<RouteSegment, Integer> segmentPickedHandler = (s, i) -> { };
    private MapSelectionMode mode = MapSelectionMode.ALL_ROUTE;
    private int selectedIndex = -1;
    private int indexA = -1;
    private int indexB = -1;
    private int[] eventIndices = new int[0];
    private double[] eventLatitudes = new double[0];
    private double[] eventLongitudes = new double[0];
    private String[] eventLabels = new String[0];

    private IntConsumer pointPickedHandler = index -> { };
    private Consumer<Integer> hoverHandler = index -> { };
    private Runnable viewportChangedHandler = () -> { };

    private final PaintGate paintGate = new PaintGate(canvas);
    private boolean fitPending = true;
    private MapRouteModel hoverModel;
    private final List<double[]> labelBoxes = new ArrayList<>();
    private final List<EndpointMark> endpointMarks = new ArrayList<>();
    private List<PaintedMarker> paintedMarkers = List.of();
    private double dragAnchorX;
    private double dragAnchorY;
    private double pressX;
    private double pressY;
    private boolean dragging;
    private long lastHoverNanos;

    public RouteCanvas() {
        this(new NeutralMapBackground());
    }

    public RouteCanvas(MapBackgroundProvider background) {
        this.background = background == null ? new NeutralMapBackground() : background;
        getStyleClass().add("map-route-canvas");
        setMinSize(0, 0);
        textMeasure.setFont(MARKER_FONT);

        hoverInfo.getStyleClass().add("map-route-tooltip");
        hoverInfo.setVisible(false);
        hoverInfo.setMouseTransparent(true);
        hoverInfo.setWrapText(true);
        hoverInfo.setMaxWidth(220);

        controls.getStyleClass().add("map-route-controls");
        controls.getChildren().addAll(
                controlButton("+", "Zoom in", () -> zoomByLevels(1)),
                controlButton("−", "Zoom out", () -> zoomByLevels(-1)),
                controlButton("⤢", "Fit route", this::fitRoute));

        getChildren().addAll(canvas, controls, hoverInfo);
        setOverlayControlsVisible(false);

        canvas.setOnMousePressed(this::onPressed);
        canvas.setOnMouseDragged(this::onDragged);
        canvas.setOnMouseReleased(this::onReleased);
        canvas.setOnMouseMoved(this::onMoved);
        canvas.setOnMouseExited(e -> hideTooltip());
        canvas.setOnScroll(this::onScroll);
        sceneProperty().addListener((obs, was, now) -> {
            if (now != null) {
                requestRedraw();
            }
        });
    }

    /** Stops paints while another analysis tab is showing. A later {@link #resumePainting()} draws once. */
    public void suspendPainting() {
        paintGate.suspend();
    }

    public void resumePainting() {
        paintGate.resume(this::redraw);
    }

    boolean paintingSuspendedForTest() {
        return paintGate.isSuspended();
    }

    public void setPointPickedHandler(IntConsumer handler) {
        this.pointPickedHandler = handler == null ? index -> { } : handler;
    }

    public void setSegmentPickedHandler(BiConsumer<RouteSegment, Integer> handler) {
        this.segmentPickedHandler = handler == null ? (s, i) -> { } : handler;
    }

    public void setHoverHandler(Consumer<Integer> handler) {
        this.hoverHandler = handler == null ? index -> { } : handler;
    }

    public void setViewportChangedHandler(Runnable handler) {
        this.viewportChangedHandler = handler == null ? () -> { } : handler;
    }

    /** Swaps the cartographic backdrop without recreating the route canvas. */
    public void setBackground(MapBackgroundProvider next) {
        if (background != null) {
            background.dispose();
        }
        background = next == null ? new NeutralMapBackground() : next;
        requestRedraw();
    }

    public MapViewport viewport() {
        return viewport;
    }

    public String backgroundName() {
        return background.displayName();
    }

    /** Installs a new route and re-frames it; the previous route's indexes are dropped. */
    public void setRoute(MapRoute route) {
        setRoute(route, "");
    }

    /** Installs one recording. Other recordings' markers are removed. */
    public void setRoute(MapRoute route, String measurementName) {
        this.route = route;
        this.routeMeasurementName = measurementName == null ? "" : measurementName;
        this.overlaySegments = List.of();
        this.endpointPolicy = EndpointPolicy.OWN_ROUTE;
        this.selectedSegment = null;
        this.selectedIndex = -1;
        this.indexA = -1;
        this.indexB = -1;
        this.fitPending = true;
        hideTooltip();
        requestRedraw();
    }

    /**
     * Independent file routes, in the order already chosen for display.
     * More than one route gets one START and one END for that order. Routes are not joined.
     */
    public void setSegments(List<RouteSegment> segments) {
        List<RouteSegment> next = new ArrayList<>();
        if (segments != null) {
            for (RouteSegment segment : segments) {
                if (segment != null && segment.hasRoute()) {
                    next.add(segment);
                }
            }
        }
        this.overlaySegments = List.copyOf(next);
        this.routeMeasurementName = "";
        this.endpointPolicy = next.size() > 1 ? EndpointPolicy.GLOBAL : EndpointPolicy.OWN_ROUTE;
        this.route = next.isEmpty() ? MapRoute.emptyRoute() : next.getFirst().route();
        this.selectedSegment = null;
        this.selectedIndex = -1;
        this.indexA = -1;
        this.indexB = -1;
        this.fitPending = true;
        hideTooltip();
        requestRedraw();
    }

    public void setEndpointPolicy(EndpointPolicy policy) {
        endpointPolicy = policy == null ? EndpointPolicy.NONE : policy;
        requestRedraw();
    }

    public EndpointPolicy endpointPolicyForTest() {
        return endpointPolicy;
    }

    public int overlayCountForTest() {
        return overlaySegments.size();
    }

    public void setOverlayControlsVisible(boolean visible) {
        controls.setVisible(visible);
        controls.setManaged(visible);
    }

    public boolean overlayControlsVisible() {
        return controls.isVisible();
    }

    public void setMode(MapSelectionMode mode) {
        this.mode = mode == null ? MapSelectionMode.ALL_ROUTE : mode;
        requestRedraw();
    }

    public void setSelectedIndex(int index) {
        if (selectedIndex != index) {
            selectedIndex = index;
            requestRedraw();
        }
    }

    /**
     * Marker for one measurement. Other route segments stay drawn.
     * A null segment clears the marker and does not substitute another file.
     */
    public void showSegmentSample(RouteSegment segment, int index) {
        selectedSegment = segment;
        if (segment != null && segment.hasRoute()) {
            route = segment.route();
        }
        selectedIndex = segment == null ? -1 : index;
        requestRedraw();
    }

    public void revealSelected() {
        if (route == null || route.isEmpty() || selectedIndex < 0 || selectedIndex >= route.model().size()) {
            return;
        }
        MapRouteModel model = route.model();
        if (viewport.reveal(model.latitude(selectedIndex), model.longitude(selectedIndex), 48)) {
            requestRedraw();
        }
    }

    public void setEventIndices(int[] indices) {
        eventIndices = indices == null ? new int[0] : indices.clone();
        eventLatitudes = new double[0];
        eventLongitudes = new double[0];
        eventLabels = new String[0];
        requestRedraw();
    }

    public void setEventPositions(double[] latitudes, double[] longitudes, String[] labels) {
        int count = latitudes == null ? 0 : latitudes.length;
        eventLatitudes = latitudes == null ? new double[0] : latitudes.clone();
        eventLongitudes = longitudes == null ? new double[0] : longitudes.clone();
        eventLabels = labels == null ? new String[count] : labels.clone();
        eventIndices = new int[0];
        requestRedraw();
    }

    String eventLabelForTest(int position) {
        return position >= 0 && position < eventLabels.length ? eventLabels[position] : "";
    }

    public void setRangeIndices(int indexA, int indexB) {
        if (this.indexA != indexA || this.indexB != indexB) {
            this.indexA = indexA;
            this.indexB = indexB;
            requestRedraw();
        }
    }

    int[] rangeIndicesForTest() {
        return new int[]{indexA, indexB};
    }

    public void clearSelection() {
        selectedIndex = -1;
        indexA = -1;
        indexB = -1;
        hideTooltip();
        requestRedraw();
    }

    public void fitRoute() {
        if (!overlaySegments.isEmpty()) {
            List<MapRouteModel> models = new ArrayList<>();
            for (RouteSegment segment : overlaySegments) {
                models.add(segment.route().model());
            }
            viewport.fitAll(models, FIT_MARGIN);
            fitPending = false;
            requestRedraw();
            return;
        }
        if (route == null || route.isEmpty()) {
            return;
        }
        viewport.fit(route.model(), FIT_MARGIN);
        requestRedraw();
    }

    public void zoom(double factor) {
        viewport.zoomCentered(factor);
        requestRedraw();
    }

    public void zoomByLevels(int delta) {
        viewport.zoomByLevels(delta);
        requestRedraw();
    }

    /**
     * Caps how far the overlay may zoom in, so it never draws detail the active basemap provider
     * cannot match. Applied on provider changes and on fallback.
     */
    public void setProviderMaxZoom(int maxZoom) {
        viewport.setProviderMaxZoom(maxZoom);
        requestRedraw();
    }

    /** Releases handlers and drops any queued paint. Does not clear the canvas: a shutdown clear forces a texture upload. */
    public void dispose() {
        paintGate.dispose();
        route = null;
        overlaySegments = List.of();
        selectedSegment = null;
        hideTooltip();
        canvas.setOnMousePressed(null);
        canvas.setOnMouseDragged(null);
        canvas.setOnMouseReleased(null);
        canvas.setOnMouseMoved(null);
        canvas.setOnMouseExited(null);
        canvas.setOnScroll(null);
        pointPickedHandler = index -> { };
        hoverHandler = index -> { };
        background.dispose();
    }

    @Override
    protected void layoutChildren() {
        Insets insets = getInsets();
        double x = insets.getLeft();
        double y = insets.getTop();
        double w = finiteLayout(getWidth() - insets.getLeft() - insets.getRight());
        double h = finiteLayout(getHeight() - insets.getTop() - insets.getBottom());
        boolean geometryChanged = CanvasRenderSupport.place(canvas, x, y, w, h);
        if (canvas.getWidth() >= 1 && canvas.getHeight() >= 1) {
            viewport.resize(canvas.getWidth(), canvas.getHeight());
        }

        controls.autosize();
        controls.relocate(x + 12, y + 12);
        if (hoverInfo.isVisible()) {
            hoverInfo.autosize();
        }
        if (geometryChanged || paintGate.isDirty()) {
            redraw();
        }
    }

    /** A collapsed region keeps a 1px canvas. Non-finite layout does not. */
    private static double finiteLayout(double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return Math.max(1, value);
    }

    private void requestRedraw() {
        // Coalesce bursts of pan/zoom/selection events into a single paint per pulse.
        paintGate.request(this::redraw);
    }

    private long lastRedrawNanos;

    public long lastRedrawMillis() {
        return lastRedrawNanos / 1_000_000L;
    }

    private void redraw() {
        if (!paintGate.begin()) {
            return;
        }
        long paintStarted = System.nanoTime();
        try {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext gc = canvas.getGraphicsContext2D();
        viewport.resize(w, h);
        labelBoxes.clear();
        endpointMarks.clear();
        paintedMarkers = List.of();
        if (fitPending) {
            if (!overlaySegments.isEmpty()) {
                List<MapRouteModel> models = new ArrayList<>();
                for (RouteSegment segment : overlaySegments) {
                    models.add(segment.route().model());
                }
                viewport.fitAll(models, FIT_MARGIN);
            } else if (route != null && !route.isEmpty()) {
                viewport.fit(route.model(), FIT_MARGIN);
            }
            fitPending = false;
        }
        background.paint(gc, viewport);
        if (!overlaySegments.isEmpty()) {
            for (int i = 0; i < overlaySegments.size(); i++) {
                RouteSegment segment = overlaySegments.get(i);
                MapRoute overlay = segment.route();
                MapRouteModel model = overlay.model();
                int[] indices = overlay.lod().indices(viewport.degreesPerPixel());
                Color color = SEGMENT_COLORS[i % SEGMENT_COLORS.length];
                strokeRoute(gc, model, indices, ROUTE_CASING, 6.0, -1, -1);
                strokeRoute(gc, model, indices, color, 3.2, -1, -1);
            }
            paintEndpointMarkers(gc);
            if (selectedSegment != null && selectedIndex >= 0) {
                drawSelected(gc, selectedSegment.route().model(), selectedIndex);
            }
            drawScaleBar(gc, w, h);
            viewportChangedHandler.run();
            return;
        }
        if (route == null || route.isEmpty()) {
            viewportChangedHandler.run();
            return;
        }
        MapRouteModel model = route.model();
        int[] indices = route.lod().indices(viewport.degreesPerPixel());
        boolean fullDrive = indexA == 0 && indexB == model.size() - 1;
        boolean rangeActive = indexA >= 0 && indexB >= 0 && !fullDrive;
        int rangeLow = rangeActive ? Math.min(indexA, indexB) : -1;
        int rangeHigh = rangeActive ? Math.max(indexA, indexB) : -1;

        strokeRoute(gc, model, indices, ROUTE_CASING, 6.0, -1, -1);
        strokeRoute(gc, model, indices, rangeActive ? ROUTE_SUBDUED : ROUTE_ACTIVE, 3.2, -1, -1);
        if (rangeActive) {
            strokeRoute(gc, model, indices, ROUTE_ACTIVE, 4.4, rangeLow, rangeHigh);
        }
        drawSparseDots(gc, model, indices);
        drawMarkers(gc, model);
        drawScaleBar(gc, w, h);
        viewportChangedHandler.run();
        } finally {
            lastRedrawNanos = System.nanoTime() - paintStarted;
        }
    }

    /**
     * Strokes the decimated route as one batched path, restarting at GPS gaps so an outlier is
     * never drawn as a road.
     */
    private void strokeRoute(GraphicsContext gc, MapRouteModel model, int[] indices,
                             Color color, double lineWidth, int limitLow, int limitHigh) {
        gc.setStroke(color);
        gc.setLineWidth(lineWidth);
        gc.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        gc.setLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        gc.beginPath();
        boolean open = false;
        int previous = -1;
        for (int index : indices) {
            if (limitLow >= 0 && (index < limitLow || index > limitHigh)) {
                open = false;
                previous = index;
                continue;
            }
            double x = viewport.x(model.longitude(index));
            double y = viewport.y(model.latitude(index));
            boolean broken = previous >= 0 && model.hasBreakBetween(previous, index);
            if (!open || broken) {
                gc.moveTo(x, y);
                open = true;
            } else {
                gc.lineTo(x, y);
            }
            previous = index;
        }
        gc.stroke();
    }

    /** Sample dots appear only when zoomed in far enough that they add information. */
    private void drawSparseDots(GraphicsContext gc, MapRouteModel model, int[] indices) {
        if (indices.length < 2) {
            return;
        }
        double first = viewport.x(model.longitude(indices[0]));
        double last = viewport.x(model.longitude(indices[indices.length - 1]));
        double firstY = viewport.y(model.latitude(indices[0]));
        double lastY = viewport.y(model.latitude(indices[indices.length - 1]));
        double averageGap = Math.hypot(last - first, lastY - firstY) / indices.length;
        if (averageGap < DOT_VISIBILITY_PIXELS) {
            return;
        }
        int step = Math.max(1, indices.length / MAX_DOTS);
        gc.setFill(DOT_FILL);
        for (int i = 0; i < indices.length; i += step) {
            double x = viewport.x(model.longitude(indices[i]));
            double y = viewport.y(model.latitude(indices[i]));
            gc.fillOval(x - 2.2, y - 2.2, 4.4, 4.4);
        }
    }

    private void drawMarkers(GraphicsContext gc, MapRouteModel model) {
        drawEventMarkers(gc, model);
        paintEndpointMarkers(gc);
        boolean fullDrive = indexA == 0 && indexB == model.size() - 1;
        if (indexA >= 0 && indexB >= 0 && !fullDrive) {
            drawPin(gc, model, indexA, AB_FILL, "A");
            drawPin(gc, model, indexB, AB_FILL, "B");
        } else if (indexA >= 0 && indexB < 0) {
            drawPin(gc, model, indexA, AB_FILL, "A");
        }
        if (selectedIndex >= 0) {
            drawSelected(gc, model, selectedIndex);
        }
    }

    private void drawEventMarkers(GraphicsContext gc, MapRouteModel model) {
        gc.setFont(MARKER_FONT);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.CENTER);
        if (eventLatitudes.length > 0) {
            int count = Math.min(eventLatitudes.length, eventLongitudes.length);
            for (int position = 0; position < count; position++) {
                drawEventPin(gc, eventLongitudes[position], eventLatitudes[position],
                        position < eventLabels.length ? eventLabels[position] : "");
            }
            return;
        }
        for (int index : eventIndices) {
            if (index < 0 || index >= model.size()) {
                continue;
            }
            drawEventPin(gc, model.longitude(index), model.latitude(index), "");
        }
    }

    private void drawEventPin(GraphicsContext gc, double longitude, double latitude, String label) {
        double x = viewport.x(longitude);
        double y = viewport.y(latitude);
        gc.setFill(Color.web("#0E7490"));
        gc.fillRect(x - 4.5, y - 4.5, 9, 9);
        if (label == null || label.isBlank()) {
            return;
        }
        gc.setFill(LABEL_TEXT);
        String[] lines = label.split("\n", 2);
        gc.fillText(lines[0], x + 8, y - 7);
        if (lines.length > 1) {
            gc.fillText(lines[1], x + 8, y + 7);
        }
    }

    /**
     * One disc per screen cluster. Each segment keeps its own start and end sample; only the
     * painted disc is shared when those samples project onto the same pixels.
     */
    private void paintEndpointMarkers(GraphicsContext gc) {
        List<EndpointMark> refs = collectEndpoints();
        endpointMarks.clear();
        endpointMarks.addAll(refs);
        paintedMarkers = clusterByScreen(refs);
        for (PaintedMarker marker : paintedMarkers) {
            if (marker.x() < -PIN_OUTER || marker.y() < -36
                    || marker.x() > canvas.getWidth() + PIN_OUTER
                    || marker.y() > canvas.getHeight() + PIN_OUTER) {
                continue;
            }
            drawCluster(gc, marker);
        }
    }

    private List<EndpointMark> collectEndpoints() {
        List<EndpointMark> refs = new ArrayList<>();
        if (endpointPolicy == EndpointPolicy.NONE) {
            return refs;
        }
        if (!overlaySegments.isEmpty()) {
            if (endpointPolicy == EndpointPolicy.GLOBAL) {
                // First and last sample of the displayed list. Routes stay disconnected.
                RouteSegment first = overlaySegments.getFirst();
                RouteSegment last = overlaySegments.getLast();
                addRole(refs, 0, first.displayName(), first.route().model(), "START", true);
                if (overlaySegments.size() > 1 || last.route().model().size() > 1) {
                    addRole(refs, overlaySegments.size() - 1, last.displayName(), last.route().model(), "END", false);
                }
                return refs;
            }
            if (endpointPolicy == EndpointPolicy.EACH_ROUTE || overlaySegments.size() == 1) {
                for (int i = 0; i < overlaySegments.size(); i++) {
                    RouteSegment segment = overlaySegments.get(i);
                    addEndpointPair(refs, i, segment.displayName(), segment.route().model());
                }
            }
            return refs;
        }
        if (route != null && !route.isEmpty()) {
            addEndpointPair(refs, -1, routeMeasurementName, route.model());
        }
        return refs;
    }

    private static void addEndpointPair(List<EndpointMark> refs, int ordinal, String measurementName, MapRouteModel model) {
        if (model == null || model.size() == 0) {
            return;
        }
        if (model.size() == 1) {
            refs.add(mark(ordinal, measurementName, "POINT", model, 0));
            return;
        }
        int last = model.size() - 1;
        refs.add(mark(ordinal, measurementName, "START", model, 0));
        refs.add(mark(ordinal, measurementName, "END", model, last));
    }

    private static void addRole(List<EndpointMark> refs, int ordinal, String measurementName,
                                MapRouteModel model, String role, boolean start) {
        if (model == null || model.size() == 0) {
            return;
        }
        int index = start ? 0 : model.size() - 1;
        refs.add(mark(ordinal, measurementName, role, model, index));
    }

    private static boolean isStartRole(String role) {
        return role != null && role.endsWith("START");
    }

    private static boolean isEndRole(String role) {
        return role != null && role.endsWith("END");
    }

    private static EndpointMark mark(int ordinal, String measurementName, String role, MapRouteModel model, int index) {
        long timestamp = model.timestampNanos(index);
        return new EndpointMark(ordinal, measurementName, role, model.latitude(index), model.longitude(index),
                timestamp >= 0 ? timestamp : null);
    }

    /** Groups by projected pixels. The same GPS coordinate stays one cluster at every zoom. */
    private List<PaintedMarker> clusterByScreen(List<EndpointMark> refs) {
        List<PaintedMarker> groups = new ArrayList<>();
        for (EndpointMark ref : refs) {
            double x = viewport.x(ref.longitude());
            double y = viewport.y(ref.latitude());
            int found = -1;
            for (int i = 0; i < groups.size(); i++) {
                PaintedMarker group = groups.get(i);
                if (Math.hypot(group.x() - x, group.y() - y) <= MARKER_COLLISION_PX) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                groups.add(new PaintedMarker(x, y, List.of(ref)));
                continue;
            }
            List<EndpointMark> next = new ArrayList<>(groups.get(found).endpoints().size() + 1);
            next.addAll(groups.get(found).endpoints());
            next.add(ref);
            double sx = 0;
            double sy = 0;
            for (EndpointMark endpoint : next) {
                sx += viewport.x(endpoint.longitude());
                sy += viewport.y(endpoint.latitude());
            }
            groups.set(found, new PaintedMarker(sx / next.size(), sy / next.size(), List.copyOf(next)));
        }
        return List.copyOf(groups);
    }

    private void drawCluster(GraphicsContext gc, PaintedMarker marker) {
        labelBoxes.add(new double[]{
                marker.x() - PIN_OUTER, marker.y() - PIN_OUTER, PIN_OUTER * 2, PIN_OUTER * 2});
        List<EndpointMark> endpoints = marker.endpoints();
        if (endpoints.size() == 1) {
            EndpointMark only = endpoints.getFirst();
            Color color = isEndRole(only.role()) ? END_FILL
                    : "POINT".equals(only.role()) ? Color.web("#0f8a96") : START_FILL;
            fillDisc(gc, marker.x(), marker.y(), color);
            if (!placeMarkerLabel(gc, marker.x(), marker.y(), color, "POINT".equals(only.role()) ? "GPS" : only.role())) {
                if (!"POINT".equals(only.role())) {
                    drawGlyph(gc, marker.x(), marker.y(), isEndRole(only.role()) ? "E" : "S");
                }
            }
            return;
        }
        if (singlePoint(endpoints)) {
            fillSplitDisc(gc, marker.x(), marker.y());
            if (!placeMarkerLabel(gc, marker.x(), marker.y(), START_FILL, "START/END")) {
                drawGlyph(gc, marker.x(), marker.y(), "•");
            }
            return;
        }
        boolean anyStart = false;
        boolean anyEnd = false;
        for (EndpointMark endpoint : endpoints) {
            if (isStartRole(endpoint.role())) {
                anyStart = true;
            }
            if (isEndRole(endpoint.role())) {
                anyEnd = true;
            }
        }
        if (anyStart && anyEnd) {
            fillSplitDisc(gc, marker.x(), marker.y());
        } else {
            fillDisc(gc, marker.x(), marker.y(), anyEnd ? END_FILL : START_FILL);
        }
        drawGlyph(gc, marker.x(), marker.y(), "");
    }

    private static boolean singlePoint(List<EndpointMark> endpoints) {
        if (endpoints.size() != 2) {
            return false;
        }
        EndpointMark first = endpoints.get(0);
        EndpointMark second = endpoints.get(1);
        return first.segmentOrdinal() == second.segmentOrdinal()
                && first.latitude() == second.latitude()
                && first.longitude() == second.longitude();
    }

    private void fillDisc(GraphicsContext gc, double x, double y, Color color) {
        gc.setFill(Color.WHITE);
        gc.fillOval(x - PIN_OUTER, y - PIN_OUTER, PIN_OUTER * 2, PIN_OUTER * 2);
        gc.setFill(color);
        gc.fillOval(x - PIN_INNER, y - PIN_INNER, PIN_INNER * 2, PIN_INNER * 2);
        gc.setStroke(Color.web("#102033"));
        gc.setLineWidth(1);
        gc.strokeOval(x - PIN_OUTER, y - PIN_OUTER, PIN_OUTER * 2, PIN_OUTER * 2);
    }

    private void fillSplitDisc(GraphicsContext gc, double x, double y) {
        gc.setFill(Color.WHITE);
        gc.fillOval(x - PIN_OUTER, y - PIN_OUTER, PIN_OUTER * 2, PIN_OUTER * 2);
        gc.setFill(START_FILL);
        gc.fillArc(x - PIN_INNER, y - PIN_INNER, PIN_INNER * 2, PIN_INNER * 2, 90, 180, ArcType.ROUND);
        gc.setFill(END_FILL);
        gc.fillArc(x - PIN_INNER, y - PIN_INNER, PIN_INNER * 2, PIN_INNER * 2, 270, 180, ArcType.ROUND);
        gc.setStroke(Color.web("#102033"));
        gc.setLineWidth(1);
        gc.strokeOval(x - PIN_OUTER, y - PIN_OUTER, PIN_OUTER * 2, PIN_OUTER * 2);
    }

    private void drawGlyph(GraphicsContext gc, double x, double y, String glyph) {
        gc.setFill(Color.WHITE);
        gc.setFont(MARKER_FONT);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.CENTER);
        gc.fillText(glyph, x, y + 0.5);
        gc.setTextAlign(TextAlignment.LEFT);
        gc.setTextBaseline(VPos.BASELINE);
    }

    private PaintedMarker markerAt(double screenX, double screenY) {
        PaintedMarker best = null;
        double bestDistance = MARKER_COLLISION_PX;
        for (PaintedMarker marker : paintedMarkers) {
            double distance = Math.hypot(marker.x() - screenX, marker.y() - screenY);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = marker;
            }
        }
        return best;
    }

    private void showMarkerDetail(PaintedMarker marker) {
        hoverModel = null;
        hoverInfo.setText(endpointDetail(marker.endpoints()));
        hoverInfo.setVisible(true);
        hoverInfo.toFront();
        hoverInfo.autosize();
        double[] placed = placeTooltip(marker.x(), marker.y(), hoverInfo.getWidth(), hoverInfo.getHeight(),
                getWidth(), getHeight());
        hoverInfo.relocate(placed[0], placed[1]);
    }

    static String endpointDetail(List<EndpointMark> endpoints) {
        StringBuilder text = new StringBuilder();
        if (endpoints.size() > 1 && !singlePoint(endpoints)) {
            text.append("Shared point");
        }
        for (EndpointMark endpoint : endpoints) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(endpoint.role());
            String name = compactName(endpoint.measurementName());
            if (!name.isEmpty()) {
                text.append("  ").append(name);
            }
            text.append('\n').append(String.format(Locale.ROOT, "Latitude   %.7f", endpoint.latitude()));
            text.append('\n').append(String.format(Locale.ROOT, "Longitude  %.7f", endpoint.longitude()));
            if (endpoint.timestampNanos() != null && endpoint.timestampNanos() >= 0) {
                text.append('\n').append(MapTimeFormat.withMillis(endpoint.timestampNanos()));
            }
        }
        return text.toString();
    }

    static String compactName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String trimmed = name.trim();
        if (trimmed.length() <= 42) {
            return trimmed;
        }
        return trimmed.substring(0, 39) + "...";
    }

    private void drawPin(GraphicsContext gc, MapRouteModel model, int index, Color fill, String label) {
        if (index < 0 || index >= model.size()) {
            return;
        }
        double x = viewport.x(model.longitude(index));
        double y = viewport.y(model.latitude(index));
        if (x < -PIN_OUTER || y < -36 || x > canvas.getWidth() + PIN_OUTER || y > canvas.getHeight() + PIN_OUTER) {
            return;
        }
        gc.setFill(Color.WHITE);
        gc.fillOval(x - PIN_OUTER, y - PIN_OUTER, PIN_OUTER * 2, PIN_OUTER * 2);
        gc.setFill(fill);
        gc.fillOval(x - PIN_INNER, y - PIN_INNER, PIN_INNER * 2, PIN_INNER * 2);
        gc.setStroke(Color.color(0, 0, 0, 0.28));
        gc.setLineWidth(1);
        gc.strokeOval(x - PIN_OUTER, y - PIN_OUTER, PIN_OUTER * 2, PIN_OUTER * 2);

        gc.setFont(MARKER_FONT);
        boolean labeled = placeMarkerLabel(gc, x, y, fill, label);
        if (!labeled && (isStartRole(label) || isEndRole(label))) {
            gc.setFill(Color.WHITE);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(isEndRole(label) ? "E" : "S", x, y + 3.5);
            gc.setTextAlign(TextAlignment.LEFT);
        }
    }

    /** Places START/END/A/B text where it stays inside the map and off other labels. */
    private boolean placeMarkerLabel(GraphicsContext gc, double x, double y, Color fill, String label) {
        double pillWidth = textWidth(label) + 14;
        double pillHeight = 16;
        double[][] spots = {
                {x - pillWidth / 2, y - PIN_OUTER - pillHeight - 4},
                {x - pillWidth / 2, y + PIN_OUTER + 4},
                {x + PIN_OUTER + 4, y - pillHeight / 2.0},
                {x - PIN_OUTER - pillWidth - 4, y - pillHeight / 2.0}
        };
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        for (double[] spot : spots) {
            if (spot[0] < 4 || spot[1] < 4 || spot[0] + pillWidth > width - 4 || spot[1] + pillHeight > height - 4) {
                continue;
            }
            if (overlapsLabel(spot[0], spot[1], pillWidth, pillHeight)) {
                continue;
            }
            gc.setFill(fill);
            gc.fillRoundRect(spot[0], spot[1], pillWidth, pillHeight, 8, 8);
            gc.setStroke(Color.WHITE);
            gc.setLineWidth(1);
            gc.strokeRoundRect(spot[0], spot[1], pillWidth, pillHeight, 8, 8);
            gc.setFill(LABEL_TEXT);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(label, spot[0] + pillWidth / 2, spot[1] + 12);
            gc.setTextAlign(TextAlignment.LEFT);
            labelBoxes.add(new double[]{spot[0], spot[1], pillWidth, pillHeight});
            return true;
        }
        return false;
    }

    private boolean overlapsLabel(double x, double y, double width, double height) {
        for (double[] box : labelBoxes) {
            boolean separated = x + width < box[0] || box[0] + box[2] < x || y + height < box[1] || box[1] + box[3] < y;
            if (!separated) {
                return true;
            }
        }
        return false;
    }

    private void drawSelected(GraphicsContext gc, MapRouteModel model, int index) {
        double x = viewport.x(model.longitude(index));
        double y = viewport.y(model.latitude(index));
        if (coversEndpoint(x, y)) {
            gc.setStroke(SELECTED_FILL);
            gc.setLineWidth(2.5);
            gc.strokeOval(x - 13, y - 13, 26, 26);
            return;
        }
        gc.setStroke(SELECTED_FILL);
        gc.setLineWidth(2);
        gc.strokeOval(x - 11, y - 11, 22, 22);
        gc.setFill(Color.WHITE);
        gc.fillOval(x - 7, y - 7, 14, 14);
        gc.setFill(SELECTED_FILL);
        gc.fillOval(x - 5, y - 5, 10, 10);
    }

    private boolean coversEndpoint(double x, double y) {
        for (PaintedMarker marker : paintedMarkers) {
            if (Math.hypot(marker.x() - x, marker.y() - y) <= PIN_OUTER) {
                return true;
            }
        }
        return false;
    }

    private void drawScaleBar(GraphicsContext gc, double width, double height) {
        double metersPerPixel = viewport.degreesPerPixel() * 111_320.0;
        double target = Math.min(150, width * 0.25);
        double rawMeters = metersPerPixel * target;
        double niceMeters = niceDistance(rawMeters);
        double barPixels = niceMeters / metersPerPixel;
        if (!(barPixels > 8) || !Double.isFinite(barPixels)) {
            return;
        }
        double x = 16;
        double y = height - 18;
        gc.setStroke(SCALE_TEXT);
        gc.setLineWidth(1.4);
        gc.strokeLine(x, y, x + barPixels, y);
        gc.strokeLine(x, y - 4, x, y + 4);
        gc.strokeLine(x + barPixels, y - 4, x + barPixels, y + 4);
        gc.setFill(SCALE_TEXT);
        gc.setFont(SCALE_FONT);
        gc.fillText(formatDistance(niceMeters), x, y - 7);
    }

    private static double niceDistance(double meters) {
        if (!(meters > 0)) {
            return 100;
        }
        double exponent = Math.floor(Math.log10(meters));
        double magnitude = Math.pow(10, exponent);
        double normalized = meters / magnitude;
        double factor = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
        return factor * magnitude;
    }

    private static String formatDistance(double meters) {
        return meters >= 1000
                ? String.format("%.0f km", meters / 1000)
                : String.format("%.0f m", meters);
    }

    private void onPressed(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        pressX = event.getX();
        pressY = event.getY();
        dragAnchorX = pressX;
        dragAnchorY = pressY;
        dragging = false;
    }

    private void onDragged(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY) {
            return;
        }
        double dx = event.getX() - dragAnchorX;
        double dy = event.getY() - dragAnchorY;
        if (!dragging && Math.hypot(event.getX() - pressX, event.getY() - pressY) <= CLICK_SLOP_PIXELS) {
            return;
        }
        dragging = true;
        hideTooltip();
        viewport.panByPixels(dx, dy);
        dragAnchorX = event.getX();
        dragAnchorY = event.getY();
        paintGate.requestCoalesced(this::redraw);
    }

    private void onReleased(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY || dragging) {
            dragging = false;
            return;
        }
        SegmentHit hit = pickHit(event.getX(), event.getY());
        if (hit == null) {
            return;
        }
        if (hit.segment != null) {
            selectedSegment = hit.segment;
            selectedIndex = hit.index;
            segmentPickedHandler.accept(hit.segment, hit.index);
        }
        pointPickedHandler.accept(hit.index);
    }

    private void onMoved(MouseEvent event) {
        long now = System.nanoTime();
        // Nearest-point resolution is cheap but not free; throttling keeps hover off the hot path.
        if (now - lastHoverNanos < HOVER_THROTTLE_NANOS) {
            return;
        }
        lastHoverNanos = now;
        PaintedMarker marker = markerAt(event.getX(), event.getY());
        if (marker != null) {
            showMarkerDetail(marker);
            return;
        }
        SegmentHit hit = pickHover(event.getX(), event.getY());
        hoverModel = hit == null ? null : modelOf(hit);
        hoverHandler.accept(hit == null ? null : hit.index);
        if (hit == null) {
            hideTooltip();
        }
    }

    private void onScroll(ScrollEvent event) {
        if (event.getDeltaY() == 0) {
            return;
        }
        viewport.zoomByLevelsAt(event.getDeltaY() > 0 ? 1 : -1, event.getX(), event.getY());
        paintGate.requestCoalesced(this::redraw);
    }

    /** @return original sample index nearest the pointer, or {@code -1} when the route is empty. */
    private int pick(double screenX, double screenY) {
        SegmentHit hit = pickHit(screenX, screenY);
        return hit == null ? -1 : hit.index;
    }

    private SegmentHit pickHit(double screenX, double screenY) {
        double latitude = viewport.latitudeAt(screenY);
        double longitude = viewport.longitudeAt(screenX);
        if (!overlaySegments.isEmpty()) {
            RouteSegment best = null;
            int bestIndex = -1;
            double bestDistance = Double.POSITIVE_INFINITY;
            for (RouteSegment segment : overlaySegments) {
                int index = segment.route().spatial().nearest(latitude, longitude);
                if (index < 0) {
                    continue;
                }
                MapRouteModel model = segment.route().model();
                double dlat = model.latitude(index) - latitude;
                double dlon = model.longitude(index) - longitude;
                double distance = dlat * dlat + dlon * dlon;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = segment;
                    bestIndex = index;
                }
            }
            return best == null ? null : new SegmentHit(best, bestIndex);
        }
        if (route == null || route.isEmpty()) {
            return null;
        }
        int index = route.spatial().nearest(latitude, longitude);
        return index < 0 ? null : new SegmentHit(null, index);
    }

    private record SegmentHit(RouteSegment segment, int index) {
    }

    private MapRouteModel modelOf(SegmentHit hit) {
        if (hit.segment != null) {
            return hit.segment.route().model();
        }
        return route == null ? null : route.model();
    }

    /** Nearest sample only when the pointer is actually on the route. */
    private SegmentHit pickHover(double screenX, double screenY) {
        SegmentHit hit = pickHit(screenX, screenY);
        if (hit == null) {
            return null;
        }
        MapRouteModel model = modelOf(hit);
        if (model == null || hit.index < 0 || hit.index >= model.size()) {
            return null;
        }
        double x = viewport.x(model.longitude(hit.index));
        double y = viewport.y(model.latitude(hit.index));
        if (Math.hypot(x - screenX, y - screenY) > HOVER_RADIUS_PX) {
            return null;
        }
        return hit;
    }

    /** Model used for the hover tooltip. Null when the pointer is not on a route. */
    public MapRouteModel hoverModel() {
        return hoverModel;
    }

    /** Positions the single shared tooltip; never allocates a tooltip per point. */
    public void showTooltip(String text, int index) {
        MapRouteModel model = hoverModel != null ? hoverModel : (route == null ? null : route.model());
        if (model == null || model.isEmpty() || index < 0 || index >= model.size() || text == null || text.isBlank()) {
            hideTooltip();
            return;
        }
        double x = viewport.x(model.longitude(index));
        double y = viewport.y(model.latitude(index));
        hoverInfo.setText(text);
        hoverInfo.setVisible(true);
        hoverInfo.toFront();
        hoverInfo.autosize();
        double[] placed = placeTooltip(x, y, hoverInfo.getWidth(), hoverInfo.getHeight(), getWidth(), getHeight());
        hoverInfo.relocate(placed[0], placed[1]);
    }

    static double[] placeTooltip(double anchorX, double anchorY, double tipWidth, double tipHeight,
                                  double canvasWidth, double canvasHeight) {
        double gap = 14;
        double[][] candidates = {
                {anchorX + 16, anchorY - tipHeight - gap},
                {anchorX - tipWidth - 16, anchorY - tipHeight - gap},
                {anchorX + 16, anchorY + gap},
                {anchorX - tipWidth - 16, anchorY + gap}
        };
        for (double[] candidate : candidates) {
            if (inside(candidate[0], candidate[1], tipWidth, tipHeight, canvasWidth, canvasHeight)) {
                return candidate;
            }
        }
        double maxX = Math.max(6, canvasWidth - tipWidth - 6);
        double maxY = Math.max(6, canvasHeight - tipHeight - 6);
        return new double[]{clamp(anchorX + 16, 6, maxX), clamp(anchorY - tipHeight - gap, 6, maxY)};
    }

    private static boolean inside(double x, double y, double width, double height, double canvasWidth, double canvasHeight) {
        return x >= 6 && y >= 6 && x + width <= canvasWidth - 6 && y + height <= canvasHeight - 6;
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(Math.max(value, min), max);
    }

    public void hideTooltip() {
        hoverInfo.setVisible(false);
    }

    public void paintForTest() {
        redraw();
    }

    public int paintCountForTest() {
        return paintGate.paints();
    }

    double[] canvasCssSizeForTest() {
        return new double[]{canvas.getWidth(), canvas.getHeight()};
    }

    public String canvasMetricsForTest() {
        CanvasRenderSupport.Size size = CanvasRenderSupport.fit(canvas, canvas.getWidth(), canvas.getHeight());
        return "css=" + Math.round(size.cssWidth()) + "x" + Math.round(size.cssHeight())
                + " backing=" + size.backingWidth() + "x" + size.backingHeight()
                + " maxEdge=" + size.maxBackingEdge()
                + " paintMs=" + lastRedrawMillis();
    }

    public List<EndpointMark> endpointMarksForTest() {
        return List.copyOf(endpointMarks);
    }

    public List<PaintedMarker> paintedMarkersForTest() {
        return paintedMarkers;
    }

    public void showMarkerAtForTest(double screenX, double screenY) {
        PaintedMarker marker = markerAt(screenX, screenY);
        if (marker == null) {
            hideTooltip();
            return;
        }
        showMarkerDetail(marker);
    }

    public void clickAtForTest(double x, double y) {
        MouseEvent pressed = new MouseEvent(
                canvas, canvas, MouseEvent.MOUSE_PRESSED,
                x, y, x, y, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null);
        MouseEvent released = new MouseEvent(
                canvas, canvas, MouseEvent.MOUSE_RELEASED,
                x, y, x, y, MouseButton.PRIMARY, 1,
                false, false, false, false, false, false, false, true, false, false, null);
        canvas.fireEvent(pressed);
        canvas.fireEvent(released);
    }

    public boolean hoverNearRouteForTest(double screenX, double screenY) {
        return pickHover(screenX, screenY) != null;
    }

    boolean tooltipVisibleForTest() {
        return hoverInfo.isVisible();
    }

    public Label tooltipForTest() {
        return hoverInfo;
    }

    public record EndpointMark(int segmentOrdinal, String measurementName, String role,
                               double latitude, double longitude, Long timestampNanos) {
        public EndpointMark(int segmentOrdinal, String measurementName, String role, double latitude, double longitude) {
            this(segmentOrdinal, measurementName, role, latitude, longitude, null);
        }
    }

    public record PaintedMarker(double x, double y, List<EndpointMark> endpoints) {
    }

    private static Button controlButton(String glyph, String tooltip, Runnable action) {
        Button button = new Button(glyph);
        button.getStyleClass().add("map-route-control-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setFocusTraversable(false);
        button.setOnAction(e -> action.run());
        return button;
    }

    /** One reused measuring node: marker pills must not allocate per frame. */
    private double textWidth(String text) {
        textMeasure.setText(text);
        return textMeasure.getLayoutBounds().getWidth();
    }
}
