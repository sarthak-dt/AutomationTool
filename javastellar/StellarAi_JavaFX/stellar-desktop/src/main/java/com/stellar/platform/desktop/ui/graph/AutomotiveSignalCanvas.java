package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.desktop.ui.canvas.CanvasRenderSupport;
import com.stellar.platform.desktop.ui.canvas.CanvasRenderSupport.PaintGate;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphLogicalTrack;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.GraphTrackArrangement;
import com.stellar.platform.processing.graph.GraphTrackSegment;
import com.stellar.platform.processing.graph.SharedTimelineHit;
import com.stellar.platform.processing.graph.timeline.UnknownOrderLayout;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Native, node-free automotive waveform surface. Rendering is bounded by viewport LOD. */
public final class AutomotiveSignalCanvas extends Region {
    private static final double LEFT = 82, RIGHT = 18, TOP = 28, BOTTOM = 30;
    /** Pointer distance, in CSS pixels, that still belongs to a waveform. */
    private static final double POINTER_TOLERANCE_PX = 8;
    private static final double CHOICE_CANCEL_PX = 12;
    private static final double TRACK_HEIGHT_COMFORTABLE = 168.0;
    private static final double TRACK_HEIGHT_AUTO = 132.0;
    private static final double TRACK_HEIGHT_COMPACT = 104.0;

    /**
     * Stacked tracks own four vertical bands: header, plot, summary, and — on the last track only —
     * the shared time axis. Overlay keeps {@link #BOTTOM} and is not laid out with these bands.
     */
    static final class StackedTrackChrome {
        static final double HEADER = TOP;
        static final double MIN_PLOT = 36;
        static final double SUMMARY_BAND = 18;
        static final double TIME_LABEL_BAND = 18;
        static final double TIME_CAPTION_BAND = 16;
        static final double FOOT_PAD = 8;
        static final double TEXT_ASCENT = 12;
        static final double TEXT_DESCENT = 4;
        static final double TIME_LABEL_WIDTH = 64;

        private StackedTrackChrome() {
        }

        static double footer(boolean last) {
            return SUMMARY_BAND + (last ? TIME_LABEL_BAND + TIME_CAPTION_BAND : 0) + FOOT_PAD;
        }

        static double summaryBaseline(double trackTop, double trackHeight, boolean last) {
            return trackTop + trackHeight - footer(last) + TEXT_ASCENT + 2;
        }

        static double timeLabelBaseline(double trackTop, double trackHeight) {
            return summaryBaseline(trackTop, trackHeight, true) + TIME_LABEL_BAND;
        }

        static double captionBaseline(double trackTop, double trackHeight) {
            return timeLabelBaseline(trackTop, trackHeight) + TIME_CAPTION_BAND;
        }
    }

    /** Geometry of one stacked track, including the glyph boxes of its footer text. */
    public record StackedTrackFrame(
            double top,
            double height,
            double plotTop,
            double plotBottom,
            double summaryBaseline,
            double summaryTop,
            double summaryBottom,
            double timeLabelBaseline,
            double timeLabelTop,
            double timeLabelBottom,
            double captionBaseline,
            double captionBottom,
            boolean last
    ) {
        static StackedTrackFrame compute(double trackTop, double trackHeight, boolean last) {
            double plotTop = trackTop + StackedTrackChrome.HEADER;
            double plotBottom = trackTop + trackHeight - StackedTrackChrome.footer(last);
            double summaryBaseline = StackedTrackChrome.summaryBaseline(trackTop, trackHeight, last);
            double timeBaseline = last ? StackedTrackChrome.timeLabelBaseline(trackTop, trackHeight) : 0;
            double captionBaseline = last ? StackedTrackChrome.captionBaseline(trackTop, trackHeight) : 0;
            return new StackedTrackFrame(
                    trackTop,
                    trackHeight,
                    plotTop,
                    plotBottom,
                    summaryBaseline,
                    summaryBaseline - StackedTrackChrome.TEXT_ASCENT,
                    summaryBaseline + StackedTrackChrome.TEXT_DESCENT,
                    timeBaseline,
                    last ? timeBaseline - StackedTrackChrome.TEXT_ASCENT : 0,
                    last ? timeBaseline + StackedTrackChrome.TEXT_DESCENT : 0,
                    captionBaseline,
                    last ? captionBaseline + StackedTrackChrome.TEXT_DESCENT : 0,
                    last);
        }

        public double bottom() {
            return top + height;
        }

        public boolean summaryIntersectsTimeAxis() {
            return last && summaryBottom > timeLabelTop + 0.01;
        }

        public boolean captionInsideTrack() {
            return !last || captionBottom <= bottom() + 0.01;
        }

        public boolean summaryInsideTrack() {
            return summaryBottom <= bottom() + 0.01 && summaryTop >= plotBottom - 0.01;
        }
    }
    public enum LayoutMode { STACKED, OVERLAY_MULTI_AXIS, OVERLAY_NORMALIZED }
    public enum TrackDensity { AUTO, COMPACT, COMFORTABLE }
    private final Canvas canvas = new Canvas();
    private final PaintGate paintGate = new PaintGate(canvas);
    private long lastRedrawNanos;
    /** 0 data, 1 pointer, 2 scene, 3 layout move, 4 layout dirty. */
    private int pendingPaintSource;
    private int paintsFromPointer;
    private int paintsFromLayoutMove;
    private int paintsFromLayoutDirty;
    private int paintsFromData;
    private int paintsFromScene;
    /** Greater than zero while {@link #presentTogether} is applying a finished frame. */
    private int presentationDepth;
    private int pointerEvents;
    /** Scroll-viewport height. Zero until the host reports one; the tile then matches the region. */
    private double visibleWindowHeight;
    private double visibleWindowOffset;
    /** Logical Y of the tile origin, so stacked tracks keep their geometry while the texture stays viewport-sized. */
    private double paintOriginY;
    private List<GraphRenderSeries> series = List.of();
    private List<GraphLogicalTrack> tracks = List.of();
    private Long cursorA, cursorB;
    private long viewportStart, viewportEnd;
    private double dragStartX;
    private long dragStartViewportStart, dragStartViewportEnd;
    private boolean dragging;
    private boolean didPan;
    /** Press landed on a painted plus. The release opens the note and does not select a sample. */
    private boolean markerPress;
    /** Orange selected-sample line. It is not Cursor A. */
    private Long selectionNanos;
    private Consumer<AnalysisOverlay> overlayClickHandler = overlay -> {};
    private BiConsumer<Double, Long> zoomAtHandler = (factor, at) -> {};
    private BiConsumer<Long, Long> panHandler = (a,b) -> {};
    private BiConsumer<String, Long> cursorHandler = (a,b) -> {};
    private BiConsumer<String, SharedTimelineHit.Hit> measurementCursorHandler = (which, hit) -> {};
    private Consumer<OverlapPick> overlapChoiceHandler = pick -> {};
    private Runnable overlapDismissHandler = () -> {};
    private Consumer<Long> sharedGapHandler = absolute -> {};
    private boolean choiceArmed;
    private double choiceAnchorX = Double.NaN;
    private double choiceAnchorY = Double.NaN;
    /** Absolute timeline position of a cursor. Null keeps the stored-time viewport axis. */
    private Long cursorADisplay;
    private Long cursorBDisplay;
    private Consumer<SignalCatalogKey> legendToggleHandler = key -> {};
    private Map<SignalCatalogKey, String> traceLabels = Map.of();
    private Map<SignalCatalogKey, Integer> colorIndexes = Map.of();
    private final ObjectProperty<Long> hoverTimestamp = new SimpleObjectProperty<>();
    private final ObjectProperty<HoverPoint> hoverPoint = new SimpleObjectProperty<>();
    /** Pointer position in canvas coordinates. Multi-file tracks resolve the segment from this, not from the shared time axis. */
    private double pointerX = Double.NaN;
    private double pointerY = Double.NaN;
    private double viewportFloorHeight = 360.0;
    private LayoutMode layoutMode = LayoutMode.STACKED;
    /** When true, scroll-wheel zoom is ignored (layout transitions must not mutate viewport). */
    private boolean suppressZoomFromLayout;
    /** True when the plot contains more than one measurement and each file keeps its own clock. */
    private boolean measurementRelativeTime;
    /** File navigator for non-comparable clocks. Count 0 hides it. Index selects the focused file. */
    private int navigatorCount;
    private int navigatorIndex;
    private boolean unknownOrderOverview;
    private Consumer<Integer> fileNavigatorHandler = index -> {};
    private static final double NAVIGATOR_HEIGHT = 18;
    /** Normalized overlay ticks are percents of each signal's own range, not engineering units. */
    private boolean percentTicks;
    private TrackDensity trackDensity = TrackDensity.AUTO;
    private boolean showGrid = true, showLegend = true, showCrosshair = true, smoothLines = false;
    /** Full state names drawn on the last paint. Truncation is only the gutter glyph. */
    private final java.util.ArrayList<String> paintedStateLabels = new java.util.ArrayList<>();
    private Color plotBackground = Color.WHITE;
    private String emptyHint = "No tracks to display.";
    private List<AnalysisOverlay> analysisOverlays = List.of();
    private boolean analysisOverlaysVisible = true;
    private final GraphPointEventMarkers pointMarkers = new GraphPointEventMarkers(new GraphPointEventMarkers.Host() {
        @Override public void add(javafx.scene.Node node) {
            javafx.scene.layout.Pane layer = node.getStyleClass().contains("graph-event-note") ? noteLayer() : null;
            if (layer != null) {
                layer.getChildren().add(node);
                node.toFront();
                return;
            }
            getChildren().add(node);
            node.toFront();
        }
        @Override public void remove(javafx.scene.Node node) {
            if (node.getParent() instanceof javafx.scene.layout.Pane pane) {
                pane.getChildren().remove(node);
            }
        }
        @Override public boolean contains(javafx.scene.Node node) { return node.getParent() != null; }
        @Override public double width() { return getWidth(); }
        @Override public double height() { return getHeight(); }
        @Override public void aboveWaveform() { releaseCoveringCache(); }
        @Override public javafx.geometry.Point2D notePoint(double x, double y) {
            javafx.scene.layout.Pane layer = noteLayer();
            if (layer == null) {
                return new javafx.geometry.Point2D(x, y);
            }
            javafx.geometry.Point2D scene = localToScene(x, y);
            if (scene == null) {
                return new javafx.geometry.Point2D(x, y);
            }
            javafx.geometry.Point2D local = layer.sceneToLocal(scene);
            return local == null ? new javafx.geometry.Point2D(x, y) : local;
        }
        @Override public double noteWidth() {
            javafx.scene.layout.Pane layer = noteLayer();
            return layer == null || layer.getWidth() < 1 ? getWidth() : layer.getWidth();
        }
        @Override public double noteHeight() {
            javafx.scene.layout.Pane layer = noteLayer();
            return layer == null || layer.getHeight() < 1 ? getHeight() : layer.getHeight();
        }
    });
    private final javafx.event.EventHandler<javafx.scene.input.KeyEvent> noteEscape = event -> {
        if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE && pointMarkers.collapseFromEscape()) {
            event.consume();
        }
    };

    /**
     * A guide overlay keeps the existing time band and mark.
     * A point marker is only the plus glyph at the plotted sample.
     */
    public record AnalysisOverlay(
            long startNanos,
            Long endNanos,
            String label,
            boolean selected,
            boolean pointMarker,
            String markerId,
            String signalName,
            String note,
            String valueText,
            String timeText,
            Double sampleValue
    ) {
        public AnalysisOverlay(long startNanos, Long endNanos, String label, boolean selected) {
            this(startNanos, endNanos, label, selected, false, "", "", "", "", "", null);
        }

        public AnalysisOverlay(long startNanos, Long endNanos, String label) {
            this(startNanos, endNanos, label, false);
        }

        public AnalysisOverlay {
            label = label == null ? "" : label;
            markerId = markerId == null ? "" : markerId;
            signalName = signalName == null ? "" : signalName;
            note = note == null ? "" : note;
            valueText = valueText == null ? "" : valueText;
            timeText = timeText == null ? "" : timeText;
        }

        public static AnalysisOverlay point(
                long timestampNanos,
                String markerId,
                String title,
                String signalName,
                String note,
                String valueText,
                String timeText,
                Double sampleValue,
                boolean selected
        ) {
            return new AnalysisOverlay(
                    timestampNanos, timestampNanos, title, selected, true,
                    markerId, signalName, note, valueText, timeText, sampleValue);
        }
    }

    /** One real plotted sample. The timestamp and value come from a bucket corner, not a blend. */
    public record PlottedSample(
            SignalCatalogKey key,
            String signalName,
            String unit,
            long timestampNanos,
            double value,
            String valueText
    ) {
    }
    private final java.util.Set<Integer> hiddenSeries = new java.util.HashSet<>();
    private final java.util.Set<SignalCatalogKey> hiddenKeys = new java.util.HashSet<>();
    /** Selected tracks that are not in the current query window. They keep a slot and do not draw stale samples. */
    private final java.util.Set<SignalCatalogKey> unloadedKeys = new java.util.LinkedHashSet<>();
    private final java.util.List<LegendHit> legendHits = new java.util.ArrayList<>();
    private int seriesAssignmentCount;
    private static final Color HIDDEN_LEGEND_GRAY = GraphChrome.HIDDEN_SERIES;

    public AutomotiveSignalCanvas() {
        getChildren().add(canvas);
        canvas.setViewOrder(1);
        canvas.setMouseTransparent(false);
        setMinHeight(260);
        setFocusTraversable(true);
        // Resize paints from layoutChildren once the tile geometry changes. A second
        // listener here painted the previous texture and then the laid-out one.
        sceneProperty().addListener((obs, was, now) -> {
            if (was != null) {
                was.removeEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, noteEscape);
            }
            if (now != null) {
                now.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, noteEscape);
                pendingPaintSource = 2;
                paintGate.request(this::redraw);
            }
        });
        setOnScroll(e -> {
            acceptWheel(e.getDeltaY(), e.getX());
            if (e.getDeltaY() != 0) {
                e.consume();
            }
        });
        setOnMouseMoved(e -> {
            pointerEvents++;
            updateHover(e.getX(), e.getY());
            if (choiceArmed && Math.hypot(e.getX() - choiceAnchorX, e.getY() - choiceAnchorY) > CHOICE_CANCEL_PX) {
                disarmChoice();
            }
            requestPointerRedraw();
        });
        setOnMouseExited(e -> { pointerX = Double.NaN; pointerY = Double.NaN; hoverTimestamp.set(null); hoverPoint.set(null); requestPointerRedraw(); });
        setOnMouseClicked(e -> {
            if (!ownsPointer(e)) {
                return;
            }
            if (selectFileNavigator(e.getX(), e.getY())) {
                e.consume();
                return;
            }
            if(e.getButton()==MouseButton.PRIMARY && showLegend){ for(LegendHit hit:List.copyOf(legendHits)) if(hit.contains(e.getX(),e.getY())){ SignalCatalogKey key=hit.key()!=null?hit.key():hit.seriesIndex()>=0&&hit.seriesIndex()<series.size()?series.get(hit.seriesIndex()).key():null; if(key!=null) legendToggleHandler.accept(key); e.consume(); return; } }
        });
        setOnMousePressed(e -> {
            if (!ownsPointer(e)) {
                return;
            }
            if (inFileNavigator(e.getY())) {
                e.consume();
                return;
            }
            requestFocus();
            didPan = false;
            markerPress = false;
            if (e.getButton()==MouseButton.PRIMARY && markerUnder(e)) {
                markerPress = !isPointMarkerTarget(e.getTarget());
                e.consume();
                return;
            }
            // Shift+click pins Cursor A. Ctrl+click pins Cursor B. A plain click only selects a sample.
            if (e.getButton()==MouseButton.PRIMARY && e.isShiftDown()) { placePointerCursor("A", e.getX(), e.getY()); e.consume(); return; }
            if (e.getButton()==MouseButton.PRIMARY && e.isControlDown()) { placePointerCursor("B", e.getX(), e.getY()); e.consume(); return; }
            if (e.getButton()==MouseButton.PRIMARY) { dragging=true; dragStartX=e.getX(); dragStartViewportStart=viewportStart; dragStartViewportEnd=viewportEnd; }
        });
        setOnMouseDragged(e -> {
            if (!ownsPointer(e) || !dragging) return;
            long span=dragStartViewportEnd-dragStartViewportStart; if(span<=0)return;
            if (Math.abs(e.getX()-dragStartX) > 4) didPan = true;
            double plotW=Math.max(1.0,plotRight(getWidth())-plotLeft(getWidth())); long shift=(long)(-(e.getX()-dragStartX)/plotW*span); panHandler.accept(dragStartViewportStart+shift,dragStartViewportEnd+shift);
        });
        setOnMouseReleased(e -> {
            if (!ownsPointer(e)) {
                dragging=false;
                didPan=false;
                markerPress=false;
                return;
            }
            if (isPointMarkerTarget(e.getTarget())) {
                e.consume();
                markerPress = false;
                dragging = false;
                didPan = false;
                return;
            }
            if (markerPress || (dragging && !didPan && pointMarkers.hits(e.getX(), e.getY()))) {
                if (!isPointMarkerTarget(e.getTarget())) {
                    pointMarkers.toggleAt(e.getX(), e.getY());
                }
                e.consume();
                markerPress = false;
                dragging = false;
                didPan = false;
                return;
            }
            if (dragging && !didPan && e.getButton()==MouseButton.PRIMARY && !e.isShiftDown() && !e.isControlDown()) {
                AnalysisOverlay overlay = overlayAt(e.getX());
                if (overlay != null) {
                    overlayClickHandler.accept(overlay);
                } else {
                    placePointerCursor("S", e.getX(), e.getY());
                }
            }
            dragging=false;
            didPan=false;
        });
    }
    public void setInteractionHandlers(Consumer<Double> zoom, BiConsumer<Long,Long> pan, BiConsumer<String,Long> cursor) {
        setInteractionHandlers(zoom == null ? null : (factor, at) -> zoom.accept(factor), pan, cursor);
    }

    /**
     * Wheel zoom changes the time window around the cursor. Layout settling swallows the gesture
     * so a mode switch cannot collapse the window onto one timestamp.
     */
    public void acceptWheel(double deltaY, double x) {
        if (deltaY == 0 || suppressZoomFromLayout) {
            return;
        }
        zoomAtHandler.accept(deltaY > 0 ? 0.72 : 1.38, timestampAt(x));
    }

    public void setMeasurementRelativeTime(boolean value) {
        if (measurementRelativeTime == value) {
            return;
        }
        measurementRelativeTime = value;
        requestRedraw();
    }

    public boolean measurementRelativeTimeForTest() {
        return measurementRelativeTime;
    }

    /**
     * Compact file navigator. {@code count <= 1} hides it. The waveform itself stays one track.
     */
    /** Unknown-order slots use layout positions. Those positions are not timestamps. */
    public void setUnknownOrderOverview(boolean enabled) {
        if (unknownOrderOverview == enabled) {
            return;
        }
        unknownOrderOverview = enabled;
        requestRedraw();
    }

    public boolean unknownOrderOverviewForTest() {
        return unknownOrderOverview;
    }

    public String overviewCaptionForTest() {
        return unknownOrderOverview
                ? "UNKNOWN ORDER · slot position is not elapsed time"
                : "";
    }

    /** Half-open slot owner for an x on the plot, or -1 when the pointer is outside every slot. */
    public int layoutOwnerForTest(int trackIndex, double x) {
        if (trackIndex < 0 || trackIndex >= tracks.size()) {
            return -1;
        }
        return layoutOwner(tracks.get(trackIndex), x, plotWidth());
    }

    public void setFileNavigator(int index, int count) {
        int nextCount = Math.max(0, count);
        int nextIndex = nextCount == 0 ? 0 : Math.max(0, Math.min(index, nextCount - 1));
        if (nextCount == navigatorCount && nextIndex == navigatorIndex) {
            return;
        }
        navigatorCount = nextCount;
        navigatorIndex = nextIndex;
        requestRedraw();
    }

    public void setFileNavigatorHandler(Consumer<Integer> handler) {
        fileNavigatorHandler = handler == null ? index -> {} : handler;
    }

    public int fileNavigatorCountForTest() {
        return navigatorCount;
    }

    public int fileNavigatorIndexForTest() {
        return navigatorIndex;
    }

    public String timeAxisCaptionForTest() {
        return timeAxisCaption();
    }

    public void setInteractionHandlers(BiConsumer<Double, Long> zoomAt, BiConsumer<Long,Long> pan, BiConsumer<String,Long> cursor) {
        zoomAtHandler = zoomAt == null ? (factor, at) -> {} : zoomAt;
        panHandler = pan == null ? (a, b) -> {} : pan;
        cursorHandler = cursor == null ? (a, b) -> {} : cursor;
    }

    public void setMeasurementCursorHandler(BiConsumer<String, SharedTimelineHit.Hit> handler) {
        measurementCursorHandler = handler == null ? (which, hit) -> {} : handler;
    }

    public void setOverlapChoiceHandler(Consumer<OverlapPick> handler) {
        overlapChoiceHandler = handler == null ? pick -> {} : handler;
    }

    public void setOverlapDismissHandler(Runnable handler) {
        overlapDismissHandler = handler == null ? () -> {} : handler;
    }

    public void setSharedGapHandler(Consumer<Long> handler) {
        sharedGapHandler = handler == null ? absolute -> {} : handler;
    }

    public void setLegendToggleHandler(Consumer<SignalCatalogKey> handler) {
        legendToggleHandler = handler == null ? key -> {} : handler;
    }

    public void setOverlayClickHandler(Consumer<AnalysisOverlay> handler) {
        overlayClickHandler = handler == null ? overlay -> {} : handler;
    }

    public void setTracePresentation(Map<SignalCatalogKey, String> labels, Map<SignalCatalogKey, Integer> colors) {
        Map<SignalCatalogKey, String> nextLabels = labels == null ? Map.of() : Map.copyOf(labels);
        Map<SignalCatalogKey, Integer> nextColors = colors == null ? Map.of() : Map.copyOf(colors);
        if (nextLabels.equals(traceLabels) && nextColors.equals(colorIndexes)) {
            return;
        }
        traceLabels = nextLabels;
        colorIndexes = nextColors;
        requestRedraw();
    }
    /**
     * Shared analysis X viewport owned by the Graph session/controller.
     * Renderers consume this; series payloads must not become the source of truth.
     */
    public void setSharedViewport(long start, long end) {
        if (!prepareSharedViewport(start, end)) {
            return;
        }
        requestRedraw();
    }

    /**
     * Records the viewport without painting. The query still has the previous tracks, and
     * painting those stored timestamps into the new window blocks the JavaFX thread.
     *
     * @return false when the range is unusable
     */
    public boolean prepareSharedViewport(long start, long end) {
        if (end < start) {
            return false;
        }
        boolean changed = start != viewportStart || end != viewportEnd;
        viewportStart = start;
        viewportEnd = end;
        if (changed) {
            disarmChoice();
        }
        return true;
    }

    public void setSeries(List<GraphRenderSeries> s) {
        seriesAssignmentCount++;
        series=s==null?List.of():List.copyOf(s);
        java.util.ArrayList<GraphLogicalTrack> built=new java.util.ArrayList<>(series.size());
        for(GraphRenderSeries item:series) built.add(GraphLogicalTrack.single(item));
        tracks=List.copyOf(built);
        syncHiddenSeriesIndices();
        // Do not copy series[0] viewport into the shared X range — that allowed a
        // signal-specific / degenerate stamp to overwrite the session viewport on redraw.
        applyStackedHeightBounds();
        requestParentLayout(); requestRedraw();
    }

    /**
     * One track per logical signal. Segments keep their measurement identity inside that track.
     */
    public void setLogicalTracks(List<GraphLogicalTrack> incoming) {
        seriesAssignmentCount++;
        tracks = incoming == null ? List.of() : List.copyOf(incoming);
        java.util.ArrayList<GraphRenderSeries> flat = new java.util.ArrayList<>();
        for (GraphLogicalTrack track : tracks) {
            for (GraphTrackSegment segment : track.segments()) {
                if (segment != null && segment.series() != null) {
                    flat.add(segment.series());
                }
            }
        }
        series = List.copyOf(flat);
        syncHiddenSeriesIndices();
        applyStackedHeightBounds();
        requestParentLayout();
        requestRedraw();
        if (Double.isFinite(pointerX) && Double.isFinite(pointerY)) {
            updateHover(pointerX, pointerY);
        }
    }

    /** Tracks outside the resident query window. An empty bucket list then means "not loaded", not "no samples". */
    public void setUnloadedKeys(java.util.Collection<SignalCatalogKey> keys) {
        java.util.LinkedHashSet<SignalCatalogKey> next = new java.util.LinkedHashSet<>();
        if (keys != null) {
            for (SignalCatalogKey key : keys) {
                if (key != null) {
                    next.add(key);
                }
            }
        }
        if (next.equals(unloadedKeys)) {
            return;
        }
        unloadedKeys.clear();
        unloadedKeys.addAll(next);
        requestRedraw();
    }

    /**
     * Session visibility (SELECTED but not VISIBLE). Legend stays clickable in muted gray;
     * assigned colours are retained and restored when shown again.
     */
    public void setHiddenKeys(java.util.Collection<SignalCatalogKey> keys) {
        java.util.LinkedHashSet<SignalCatalogKey> next = new java.util.LinkedHashSet<>();
        if (keys != null) {
            for (SignalCatalogKey key : keys) {
                if (key != null) {
                    next.add(key);
                }
            }
        }
        if (next.equals(hiddenKeys)) {
            return;
        }
        hiddenKeys.clear();
        hiddenKeys.addAll(next);
        syncHiddenSeriesIndices();
        applyStackedHeightBounds();
        requestParentLayout();
        requestRedraw();
    }

    private void syncHiddenSeriesIndices() {
        hiddenSeries.clear();
        for (int i = 0; i < series.size(); i++) {
            if (seriesHidden(series.get(i).key())) {
                hiddenSeries.add(i);
            }
        }
    }

    private boolean seriesHidden(SignalCatalogKey key) {
        if (key != null && hiddenKeys.contains(key)) {
            return true;
        }
        for (GraphLogicalTrack track : tracks) {
            if (!hiddenKeys.contains(track.logicalKey())) {
                continue;
            }
            for (GraphTrackSegment segment : track.segments()) {
                if (key != null && key.equals(segment.series().key())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean trackHidden(int trackIndex) {
        if (trackIndex < 0 || trackIndex >= tracks.size()) {
            return true;
        }
        GraphLogicalTrack track = tracks.get(trackIndex);
        if (hiddenKeys.contains(track.logicalKey())) {
            return true;
        }
        if (track.segments().isEmpty()) {
            return false;
        }
        for (GraphTrackSegment segment : track.segments()) {
            if (!hiddenKeys.contains(segment.series().key())) {
                return false;
            }
        }
        return true;
    }

    private int visibleSeriesCount() {
        int n = 0;
        for (int i = 0; i < tracks.size(); i++) {
            if (!trackHidden(i)) {
                n++;
            }
        }
        return n;
    }

    private int trackIndexForSeries(int seriesIndex) {
        int cursor = 0;
        for (int t = 0; t < tracks.size(); t++) {
            int count = Math.max(1, tracks.get(t).segments().size());
            if (seriesIndex < cursor + count) {
                return t;
            }
            cursor += count;
        }
        return Math.max(0, tracks.size() - 1);
    }

    /**
     * Graph Clear / Dispose: drop all series model references and ephemeral hit-test state.
     * Does not delete disk-backed lossless artifacts.
     */
    public void clearGraphData() {
        series = List.of();
        tracks = List.of();
        hiddenSeries.clear();
        hiddenKeys.clear();
        unloadedKeys.clear();
        legendHits.clear();
        cursorA = null;
        cursorB = null;
        hoverTimestamp.set(null);
        hoverPoint.set(null);
        viewportStart = 0;
        viewportEnd = 0;
        dragging = false;
        requestParentLayout();
        requestRedraw();
    }

    /** Distinguishes "no membership" from "selected but data unavailable" on the plot surface. */
    public void setEmptyHint(String hint) {
        emptyHint = hint == null || hint.isBlank() ? "No tracks to display." : hint;
        if (series.isEmpty()) {
            requestRedraw();
        }
    }

    /** Test/NFT helper: number of series currently retained by the canvas model. */
    public int seriesCountForTest() {
        return series.size();
    }

    /** Visible stacked slots. One logical signal is one slot, however many files it contains. */
    public int logicalTrackCountForTest() {
        return visibleSeriesCount();
    }

    public int segmentCountForTest(int trackIndex) {
        if (trackIndex < 0 || trackIndex >= tracks.size()) {
            return 0;
        }
        return tracks.get(trackIndex).segments().size();
    }

    public Color logicalTrackColorForTest(int trackIndex) {
        if (trackIndex < 0 || trackIndex >= tracks.size()) {
            return GraphSeriesPalette.at(0);
        }
        return colorOf(seriesIndexOfTrack(trackIndex));
    }
    @Override protected double computePrefHeight(double width) {
        if (layoutMode != LayoutMode.STACKED) return viewportFloorHeight;
        return Math.max(viewportFloorHeight, stackedContentHeight(Math.max(1, visibleSeriesCount())));
    }

    private double stackedContentHeight(int tracks) {
        double body = plotBodyForDensity(tracks);
        double height = 0;
        for (int i = 0; i < tracks; i++) {
            height += StackedTrackChrome.HEADER + body + StackedTrackChrome.footer(i == tracks - 1);
        }
        return height;
    }

    /** Nominal stacked-track pitch used to choose the query window before the canvas has been measured. */
    public double nominalTrackStride() {
        double body = plotBodyForDensity(8);
        return StackedTrackChrome.HEADER + body + StackedTrackChrome.footer(false);
    }

    /** Plot-body height taken from the historical track size, with header and the old axis margin removed. */
    private double plotBodyForDensity(int tracks) {
        double legacy = switch (trackDensity) {
            case COMPACT -> TRACK_HEIGHT_COMPACT;
            case COMFORTABLE -> TRACK_HEIGHT_COMFORTABLE;
            case AUTO -> tracks <= 3 ? TRACK_HEIGHT_COMFORTABLE : TRACK_HEIGHT_AUTO;
        };
        return Math.max(StackedTrackChrome.MIN_PLOT, legacy - TOP - BOTTOM);
    }

    /**
     * Splits the canvas so every track keeps its footer. Extra height is shared by the plot bodies.
     * A canvas shorter than the reserved chrome does not borrow that space from the last time axis.
     */
    private double[] stackedTrackHeights(double canvasHeight, int visible) {
        double[] heights = new double[visible];
        double chrome = 0;
        for (int i = 0; i < visible; i++) {
            chrome += StackedTrackChrome.HEADER + StackedTrackChrome.footer(i == visible - 1);
        }
        double plotBudget = canvasHeight - chrome;
        if (plotBudget < 0) {
            for (int i = 0; i < visible; i++) {
                heights[i] = StackedTrackChrome.HEADER + StackedTrackChrome.footer(i == visible - 1);
            }
            return heights;
        }
        double plot = plotBudget / visible;
        for (int i = 0; i < visible; i++) {
            heights[i] = StackedTrackChrome.HEADER + plot + StackedTrackChrome.footer(i == visible - 1);
        }
        double sum = 0;
        for (double height : heights) {
            sum += height;
        }
        heights[visible - 1] += canvasHeight - sum;
        return heights;
    }

    public List<StackedTrackFrame> stackedFramesForTest() {
        if (layoutMode != LayoutMode.STACKED) {
            return List.of();
        }
        int visible = visibleSeriesCount();
        if (visible <= 0) {
            return List.of();
        }
        double[] heights = stackedTrackHeights(Math.max(1, getHeight()), visible);
        List<StackedTrackFrame> frames = new ArrayList<>(visible);
        double y = 0;
        for (int i = 0; i < visible; i++) {
            frames.add(StackedTrackFrame.compute(y, heights[i], i == visible - 1));
            y += heights[i];
        }
        return List.copyOf(frames);
    }
    public void setLayoutMode(LayoutMode mode) {
        layoutMode = mode == null ? LayoutMode.STACKED : mode;
        if (!sharedLegendActive()) {
            legendHits.removeIf(LegendHit::shared);
        }
        disarmChoice();
        // Suppress wheel-zoom while preferred height / ScrollPane bounds settle after a mode change.
        suppressZoomFromLayout = true;
        applyStackedHeightBounds();
        requestParentLayout();
        requestRedraw();
        javafx.application.Platform.runLater(() -> javafx.application.Platform.runLater(() -> suppressZoomFromLayout = false));
    }
    public LayoutMode getLayoutMode(){ return layoutMode; }
    public void setTrackDensity(TrackDensity density) { trackDensity=density==null?TrackDensity.AUTO:density; applyStackedHeightBounds(); requestParentLayout(); requestRedraw(); }
    public TrackDensity getTrackDensity(){ return trackDensity; }
    public void setShowGrid(boolean value){ showGrid=value; requestRedraw(); }
    public void setShowLegend(boolean value){ showLegend=value; requestRedraw(); }
    public void setShowCrosshair(boolean value){ showCrosshair=value; requestRedraw(); }
    public void setSmoothLines(boolean value){ smoothLines=value; requestRedraw(); }
    public void setPlotBackground(String css){ try{ plotBackground=Color.web(css); }catch(RuntimeException ignored){ plotBackground=Color.WHITE; } requestRedraw(); }
    public void setViewportFloorHeight(double height) {
        double next = Math.max(320.0, height);
        if (Math.abs(next - viewportFloorHeight) < 1.0) {
            applyStackedHeightBounds();
            return;
        }
        viewportFloorHeight = next;
        applyStackedHeightBounds();
    }

    /**
     * The scroll pane must not clamp a stacked canvas to the viewport. The minimum height is the
     * viewport for a short stack, and the full header/plot/footer stack when that is taller.
     */
    private void applyStackedHeightBounds() {
        double required = layoutMode == LayoutMode.STACKED
                ? Math.max(viewportFloorHeight, stackedContentHeight(Math.max(1, visibleSeriesCount())))
                : viewportFloorHeight;
        if (Math.abs(getMinHeight() - required) < 1.0 && Math.abs(getPrefHeight() - required) < 1.0) {
            return;
        }
        setMinHeight(required);
        setPrefHeight(required);
        requestParentLayout();
    }
    public void setCursors(Long a, Long b) {
        if (java.util.Objects.equals(cursorA, a) && java.util.Objects.equals(cursorB, b)) {
            return;
        }
        cursorA = a;
        cursorB = b;
        requestRedraw();
    }

    /** Selected sample while adding a point event. Null hides the line. It does not move Cursor A or B. */
    public void setSelectionLine(Long nanos) {
        if (java.util.Objects.equals(selectionNanos, nanos)) {
            return;
        }
        selectionNanos = nanos;
        requestRedraw();
    }

    /** Absolute positions for a shared UTC axis. Null values draw {@link #setCursors} on the stored viewport. */
    public void setSharedCursorDisplays(Long absoluteA, Long absoluteB) {
        if (java.util.Objects.equals(cursorADisplay, absoluteA) && java.util.Objects.equals(cursorBDisplay, absoluteB)) {
            return;
        }
        cursorADisplay = absoluteA;
        cursorBDisplay = absoluteB;
        requestRedraw();
    }
    public void setAnalysisOverlays(List<AnalysisOverlay> overlays) {
        List<AnalysisOverlay> next = overlays == null ? List.of() : List.copyOf(overlays);
        if (next.equals(analysisOverlays)) {
            return;
        }
        analysisOverlays = next;
        requestRedraw();
    }

    public List<AnalysisOverlay> analysisOverlaysForTest() {
        return List.copyOf(analysisOverlays);
    }
    public void setAnalysisOverlaysVisible(boolean visible) {
        if (analysisOverlaysVisible == visible) {
            return;
        }
        analysisOverlaysVisible = visible;
        requestRedraw();
    }
    private void drawAnalysisOverlays(GraphicsContext g, double w, double h) {
        int slots = Math.max(1, (int) Math.floor(Math.max(16, h - 14) / 16));
        int visible = 0;
        for (AnalysisOverlay overlay : analysisOverlays) {
            if (overlay.pointMarker()) {
                continue;
            }
            boolean window = overlay.endNanos() != null && !overlay.endNanos().equals(overlay.startNanos());
            if (window) {
                double x0 = xFor(overlay.startNanos(), w);
                double x1 = xFor(overlay.endNanos(), w);
                g.setFill(overlay.selected()
                        ? Color.rgb(14, 116, 144, 0.34)
                        : GraphChrome.ANALYSIS_BAND);
                g.fillRect(Math.min(x0, x1), 0, Math.max(2, Math.abs(x1 - x0)), h);
            }
            long ts = overlay.startNanos();
            if (ts < viewportStart || ts > viewportEnd) {
                continue;
            }
            drawEventMark(g, ts, overlay.label() == null ? "E" : overlay.label(),
                    overlay.selected() ? Color.web("#0B4F63") : GraphChrome.ANALYSIS_EVENT,
                    overlay.selected() ? 2.4 : 1.2, 14 + (visible % slots) * 16, w, h);
            visible++;
        }
    }
    public ObjectProperty<Long> hoverTimestampProperty(){return hoverTimestamp;}
    public ObjectProperty<HoverPoint> hoverPointProperty(){return hoverPoint;}
    /**
     * One physical waveform under the pointer. Several choices can share a pixel.
     * The logical signal, its colour, and its track stay singular.
     */
    public record MeasurementChoice(
            SharedTimelineHit.Hit hit,
            double physicalValue,
            String unit,
            double distance,
            String segmentLabel
    ) {
        public MeasurementChoice {
            unit = unit == null ? "" : unit;
            segmentLabel = segmentLabel == null ? "" : segmentLabel;
        }
    }

    public record OverlapPick(String which, double screenX, double screenY, List<MeasurementChoice> choices) {
        public OverlapPick {
            choices = choices == null ? List.of() : List.copyOf(choices);
        }
    }

    public record HoverPoint(
            long timestampNanos,
            String signalName,
            String unit,
            double value,
            String validityNote,
            String categoricalLabel,
            String measurement,
            long pointerTimestampNanos,
            long bucketStartNanos,
            long bucketEndNanos,
            long bucketSampleCount,
            String valueRole,
            String validityName
    ) {
        public HoverPoint(
                long timestampNanos, String signalName, String unit, double value,
                String validityNote, String categoricalLabel, String measurement
        ) {
            this(timestampNanos, signalName, unit, value, validityNote, categoricalLabel, measurement,
                    timestampNanos, timestampNanos, timestampNanos, 1L, "exact", "");
        }

        public HoverPoint(long timestampNanos, String signalName, String unit, double value, String validityNote) {
            this(timestampNanos, signalName, unit, value, validityNote, "", "");
        }

        public HoverPoint(long timestampNanos, String signalName, String unit, double value) {
            this(timestampNanos, signalName, unit, value, "");
        }

        public HoverPoint {
            validityNote = validityNote == null ? "" : validityNote;
            categoricalLabel = categoricalLabel == null ? "" : categoricalLabel;
            measurement = measurement == null ? "" : measurement;
            valueRole = valueRole == null || valueRole.isBlank() ? "exact" : valueRole;
            validityName = validityName == null ? "" : validityName;
            if (bucketSampleCount < 1) {
                bucketSampleCount = 1;
            }
            if (bucketEndNanos < bucketStartNanos) {
                bucketEndNanos = bucketStartNanos;
            }
        }
    }

    private void updateHover(double x, double y){
        pointerX = x;
        pointerY = y;
        if(series.isEmpty()){hoverTimestamp.set(null);hoverPoint.set(null);return;}
        int visible=visibleSeriesCount();
        if(visible==0){hoverPoint.set(null);return;}
        if(layoutMode!=LayoutMode.STACKED){
            GraphLogicalTrack layoutTrack=firstLayoutTrack();
            if(layoutTrack!=null){
                double width=plotWidth();
                int segment=layoutOwner(layoutTrack, x, width);
                if(segment<0){hoverPoint.set(null);return;}
                long stored=timestampInSegment(layoutTrack, segment, x, width);
                int index=nearestLayoutSeries(segment, stored, y);
                hoverTimestamp.set(stored);
                publishHover(index, stored, stored);
                return;
            }
            long ts=timestampAt(x);
            hoverTimestamp.set(ts);
            int index=nearestOverlaySeries(ts,y);
            publishHover(index, ts, storedLookup(index, ts));
            return;
        }
        int trackIndex=trackAtY(y);
        if(trackIndex<0 || trackIndex>=tracks.size()){hoverPoint.set(null);return;}
        GraphLogicalTrack track=tracks.get(trackIndex);
        if(track.segments().size()<=1 && !usesLayout(track)){
            long ts=timestampAt(x);
            hoverTimestamp.set(ts);
            publishHover(seriesIndexOfTrack(trackIndex), ts, ts);
            return;
        }
        double width=plotWidth();
        int segment=owningSegment(track, x, y, width);
        if(segment<0){
            if(track.sharedAbsoluteTimeline()){
                GraphTrackArrangement.Pane axis=sharedAxis(width);
                if(axis!=null) hoverTimestamp.set(absoluteOnAxis(axis, x));
            }
            hoverPoint.set(null);
            return;
        }
        long stored=timestampInSegment(track, segment, x, width);
        long display=displayOf(track, segment, stored);
        hoverTimestamp.set(display);
        publishHover(seriesIndexOfSegment(trackIndex, segment), display, stored);
    }

    /**
     * {@code pointerNanos} is the display-axis position. {@code lookupNanos} selects a stored
     * bucket corner. The value keeps that corner's timestamp, never the pointer time.
     */
    private void publishHover(int index, long pointerNanos, long lookupNanos){
        if(index<0||index>=series.size()){hoverPoint.set(null);return;}
        GraphRenderSeries s=series.get(index); GraphBucket nearest=null; long best=Long.MAX_VALUE;
        for(GraphBucket b:s.buckets()){
            long[] times={b.firstTimestampNanos(),b.minTimestampNanos(),b.maxTimestampNanos(),b.lastTimestampNanos()};
            for(long t:times){long d=Math.abs(t-lookupNanos);if(d<best){best=d;nearest=b;}}
        }
        if(nearest==null){hoverPoint.set(null);return;}
        long[] times={nearest.firstTimestampNanos(),nearest.minTimestampNanos(),nearest.maxTimestampNanos(),nearest.lastTimestampNanos()};
        double[] values={nearest.firstValue(),nearest.minValue(),nearest.maxValue(),nearest.lastValue()};
        SignalValidity[] states={nearest.firstValidity(),nearest.minValidity(),nearest.maxValidity(),nearest.lastValidity()};
        int bi=0;best=Long.MAX_VALUE;for(int i=0;i<times.length;i++){long d=Math.abs(times[i]-lookupNanos);if(d<best){best=d;bi=i;}}
        String[] labels={nearest.firstLabel(),nearest.minLabel(),nearest.maxLabel(),nearest.lastLabel()};
        String categorical=labels[bi];
        boolean exact=nearest.sampleCount()==1 && nearest.firstTimestampNanos()==nearest.lastTimestampNanos();
        String role=exact?"exact":bi==1?"minimum":bi==2?"maximum":"nearest";
        String validity=states[bi]==null?"":states[bi].name();
        String note=categorical!=null&&!categorical.isBlank()?"":states[bi]!=null && states[bi].usableWith(values[bi])?"":validity;
        String measurement=s.key()==null?"":s.key().sourceFile();
        hoverPoint.set(new HoverPoint(
                times[bi], labelOf(s), s.unit(), values[bi], note,
                categorical==null?"":categorical, measurement==null?"":measurement,
                pointerNanos, nearest.firstTimestampNanos(), nearest.lastTimestampNanos(),
                nearest.sampleCount(), role, validity));
    }

    private int trackAtY(double y){
        int visible=visibleSeriesCount();
        if(visible<=0) return -1;
        double[] heights=stackedTrackHeights(Math.max(1, getHeight()), visible);
        double cursor=0;
        int slot=0;
        for(int i=0;i<tracks.size();i++){
            if(trackHidden(i)) continue;
            double height=heights[Math.min(slot, heights.length-1)];
            if(y>=cursor && y<cursor+height) return i;
            cursor+=height;
            slot++;
        }
        return slot>0 && y>=cursor ? visibleTrackIndex(visible-1) : -1;
    }

    private int visibleTrackIndex(int slot){
        int seen=0;
        for(int i=0;i<tracks.size();i++){
            if(trackHidden(i)) continue;
            if(seen==slot) return i;
            seen++;
        }
        return -1;
    }

    private int seriesIndexOfSegment(int trackIndex, int segmentIndex){
        return seriesIndexOfTrack(trackIndex)+Math.max(0, segmentIndex);
    }

    private int visibleSeriesAtSlot(int slot){
        int seen=0;
        for(int i=0;i<series.size();i++){
            if(hiddenSeries.contains(i))continue;
            if(seen==slot)return i;
            seen++;
        }
        return -1;
    }

    private int paletteIndex(int seriesIndex) {
        if (seriesIndex < 0 || seriesIndex >= series.size()) {
            return Math.max(0, seriesIndex);
        }
        int trackIndex = trackIndexForSeries(seriesIndex);
        GraphLogicalTrack track = trackIndex < tracks.size() ? tracks.get(trackIndex) : null;
        Integer assigned = track == null ? null : colorIndexes.get(track.logicalKey());
        if (assigned == null) {
            assigned = colorIndexes.get(series.get(seriesIndex).key());
        }
        return assigned == null ? Math.max(0, trackIndex) : assigned;
    }

    private Color colorOf(int seriesIndex) {
        return GraphSeriesPalette.at(paletteIndex(seriesIndex));
    }

    private void applySeriesDashes(GraphicsContext g, int seriesIndex) {
        double[] dashes = GraphSeriesPalette.dashesAt(paletteIndex(seriesIndex));
        if (dashes == null || dashes.length == 0) {
            g.setLineDashes(null);
        } else {
            g.setLineDashes(dashes);
        }
    }

    private String labelOf(GraphRenderSeries s) {
        if (s == null) {
            return "";
        }
        String label = traceLabels.get(s.key());
        return label == null || label.isBlank() ? s.key().signalName() : label;
    }

    /**
     * The region stays as tall as the stacked tracks so the scroll pane can move through them.
     * The canvas texture is only the visible window, capped to the display and pipeline budget.
     */
    @Override
    protected void layoutChildren() {
        double logicalW = getWidth();
        double logicalH = getHeight();
        double tileH = visibleWindowHeight > 1 ? Math.min(logicalH, visibleWindowHeight) : logicalH;
        double tileY = 0;
        if (Double.isFinite(logicalH) && Double.isFinite(tileH) && logicalH > tileH + 0.5) {
            double offset = Double.isFinite(visibleWindowOffset) ? visibleWindowOffset : 0;
            tileY = Math.max(0, Math.min(offset, logicalH - tileH));
        }
        boolean moved = CanvasRenderSupport.place(canvas, 0, tileY, logicalW, tileH);
        if (presentationDepth > 0) {
            if (moved || paintGate.isDirty()) {
                paintGate.markDirty();
            }
            return;
        }
        if (moved || paintGate.isDirty()) {
            pendingPaintSource = moved ? 3 : 4;
            redraw();
        }
    }

    /**
     * Applies several presentation updates, then paints the finished waveform once.
     * Intermediate setter calls would otherwise upload a full software-renderer frame each.
     */
    public void presentTogether(Runnable updates) {
        if (updates == null || paintGate.isDisposed()) {
            return;
        }
        presentationDepth++;
        try {
            updates.run();
        } finally {
            presentationDepth--;
            if (presentationDepth == 0 && paintGate.isDirty()) {
                pendingPaintSource = 0;
                paintGate.request(this::redraw);
            }
        }
    }

    /**
     * Viewport slice reported by the waveform scroll pane. Does not change the time window or Y axes.
     */
    public void setVisibleWindow(double height, double offset) {
        if (paintGate.isDisposed()) {
            return;
        }
        double nextH = Double.isFinite(height) && height > 1 ? height : 0;
        double nextO = Double.isFinite(offset) ? Math.max(0, offset) : 0;
        if (Math.abs(nextH - visibleWindowHeight) < 0.5 && Math.abs(nextO - visibleWindowOffset) < 0.5) {
            return;
        }
        visibleWindowHeight = nextH;
        visibleWindowOffset = nextO;
        requestLayout();
    }

    /** Drops queued paints on application shutdown. Does not upload a clear. */
    public void release() {
        paintGate.dispose();
        series = List.of();
        tracks = List.of();
        hiddenSeries.clear();
        hiddenKeys.clear();
        unloadedKeys.clear();
        legendHits.clear();
        cursorA = null;
        cursorB = null;
        hoverTimestamp.set(null);
        hoverPoint.set(null);
    }

    private void requestRedraw() {
        if (presentationDepth > 0) {
            paintGate.markDirty();
            return;
        }
        pendingPaintSource = 0;
        paintGate.request(this::redraw);
    }

    private void requestPointerRedraw() {
        if (presentationDepth > 0) {
            paintGate.markDirty();
            return;
        }
        pendingPaintSource = 1;
        paintGate.requestCoalesced(this::redraw);
    }

    private static final int PAINT_LOG = 24;
    private final int[] paintLogSource = new int[PAINT_LOG];
    private final long[] paintLogNanos = new long[PAINT_LOG];
    private int paintLogCount;

    private void recordPaintReason() {
        int source = switch (pendingPaintSource) {
            case 1 -> {
                paintsFromPointer++;
                yield 1;
            }
            case 2 -> {
                paintsFromScene++;
                yield 2;
            }
            case 3 -> {
                paintsFromLayoutMove++;
                yield 3;
            }
            case 4 -> {
                paintsFromLayoutDirty++;
                yield 4;
            }
            default -> {
                paintsFromData++;
                yield 0;
            }
        };
        int slot = paintLogCount % PAINT_LOG;
        paintLogSource[slot] = source;
        paintLogNanos[slot] = System.nanoTime();
        paintLogCount++;
    }

    public String paintDiagnosticsForTest() {
        StringBuilder recent = new StringBuilder();
        int available = Math.min(PAINT_LOG, paintLogCount);
        long previous = 0;
        for (int i = paintLogCount - available; i < paintLogCount; i++) {
            int slot = Math.floorMod(i, PAINT_LOG);
            if (recent.length() > 0) {
                recent.append(',');
            }
            long gapMs = previous == 0 ? 0 : (paintLogNanos[slot] - previous) / 1_000_000L;
            recent.append(paintLogSource[slot]).append('+').append(gapMs).append("ms");
            previous = paintLogNanos[slot];
        }
        return "pointer=" + paintsFromPointer
                + " layoutMoved=" + paintsFromLayoutMove
                + " layoutDirty=" + paintsFromLayoutDirty
                + " data=" + paintsFromData
                + " scene=" + paintsFromScene
                + " pointerEvents=" + pointerEvents
                + " css=" + Math.round(canvas.getWidth()) + "x" + Math.round(canvas.getHeight())
                + " y=" + Math.round(canvas.getLayoutY())
                + " recent=" + recent;
    }

    public void flushDeferredPaintForTest() {
        paintGate.flushForTest();
    }

    public int paintCountForTest() {
        return paintGate.paints();
    }

    public double[] innerCanvasSizeForTest() {
        return new double[]{canvas.getWidth(), canvas.getHeight(), canvas.getLayoutY()};
    }

    /** Plot rectangle inside the canvas: x, y, width, height, then canvas css width and height. */
    public double[] plotBoundsForTest() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        double left = plotLeft(width);
        double right = plotRight(width);
        double top = TOP;
        double bottom = Math.max(top, height - BOTTOM);
        return new double[]{left, top, Math.max(0, right - left), Math.max(0, bottom - top), width, height};
    }

    public String canvasMetricsForTest() {
        CanvasRenderSupport.Size size = CanvasRenderSupport.fit(canvas, canvas.getWidth(), canvas.getHeight());
        return "css=" + Math.round(size.cssWidth()) + "x" + Math.round(size.cssHeight())
                + " backing=" + size.backingWidth() + "x" + size.backingHeight()
                + " maxEdge=" + size.maxBackingEdge()
                + " paintMs=" + lastRedrawMillis();
    }

    /**
     * Waveform pointer handling is owned by this canvas only. Explorer / scene clicks
     * must not select time, pan, or set cursors.
     */
    private boolean ownsPointer(javafx.scene.input.MouseEvent event) {
        if (event == null) {
            return false;
        }
        javafx.scene.Node picked = event.getPickResult() == null ? null : event.getPickResult().getIntersectedNode();
        Object target = event.getTarget();
        return isSelfOrChild(picked) || isSelfOrChild(target instanceof javafx.scene.Node n ? n : null);
    }

    private boolean isSelfOrChild(javafx.scene.Node node) {
        javafx.scene.Node current = node;
        while (current != null) {
            if (current == this || current == canvas) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    public SharedTimelineHit.Hit hitForTest(double x, double y) {
        return hitAt(x, y);
    }

    public void movePointerForTest(double x, double y) {
        updateHover(x, y);
    }

    public List<MeasurementChoice> choicesForTest(double x, double y) {
        return choicesAt(x, y);
    }

    public int childCountForTest() {
        return getChildren().size();
    }

    private void disarmChoice() {
        if (!choiceArmed) {
            return;
        }
        choiceArmed = false;
        overlapDismissHandler.run();
    }

    private List<MeasurementChoice> choicesAt(double x, double y) {
        if (tracks.isEmpty() || !Double.isFinite(x) || !Double.isFinite(y)) {
            return List.of();
        }
        double width = plotWidth();
        ArrayList<MeasurementChoice> choices = new ArrayList<>();
        if (layoutMode != LayoutMode.STACKED) {
            for (GraphLogicalTrack track : tracks) {
                choices.addAll(choicesForTrack(track, x, y, width, true));
            }
        } else {
            int trackIndex = trackAtY(y);
            if (trackIndex >= 0 && trackIndex < tracks.size()) {
                choices.addAll(choicesForTrack(tracks.get(trackIndex), x, y, width, false));
            }
        }
        choices.sort(Comparator
                .comparingDouble(MeasurementChoice::distance)
                .thenComparing((MeasurementChoice choice) -> -choice.hit().segmentIndex()));
        return List.copyOf(choices);
    }

    private List<MeasurementChoice> choicesForTrack(
            GraphLogicalTrack track, double x, double y, double width, boolean overlayPixels) {
        if (track == null || !track.sharedAbsoluteTimeline() || track.segments().size() <= 1) {
            return List.of();
        }
        GraphTrackArrangement.Pane axis = sharedAxis(width);
        if (axis == null || x < axis.x0() || x > axis.x1()) {
            return List.of();
        }
        long absolute = absoluteOnAxis(axis, x);
        int count = track.segments().size();
        ArrayList<SharedTimelineHit.Segment> segments = new ArrayList<>(count);
        double[] compare = new double[count];
        double[] physical = new double[count];
        double[] pixels = overlayPixels ? overlaySamplePixels(track, x, width) : null;
        for (int i = 0; i < count; i++) {
            GraphTrackSegment segment = track.segments().get(i);
            long start = segmentStart(segment);
            long end = segmentEnd(segment, start);
            String id = segment.series().key() == null ? "" : segment.series().key().sourceFile();
            segments.add(new SharedTimelineHit.Segment(
                    id, track.logicalKey().signalName(), start, end, segment.absoluteOriginNanos()));
            long stored = storedAt(segment, absolute);
            physical[i] = valueNear(segment, stored);
            compare[i] = overlayPixels ? pixels[i] : physical[i];
        }
        Double pointer = overlayPixels ? Double.valueOf(y) : pointerValue(track, y);
        if (pointer == null || !Double.isFinite(pointer)) {
            return List.of();
        }
        double tolerance = overlayPixels ? POINTER_TOLERANCE_PX : physicalTolerance(track);
        List<SharedTimelineHit.Candidate> matches = SharedTimelineHit.withinTolerance(
                absolute, segments, pointer, compare, tolerance);
        ArrayList<MeasurementChoice> choices = new ArrayList<>();
        for (SharedTimelineHit.Candidate match : matches) {
            GraphTrackSegment segment = track.segments().get(match.segmentIndex());
            long start = segmentStart(segment);
            choices.add(new MeasurementChoice(
                    new SharedTimelineHit.Hit(
                            match.measurementId(),
                            match.signalName(),
                            match.segmentIndex(),
                            match.storedTimestampNanos(),
                            match.absoluteTimestampNanos(),
                            start,
                            segmentEnd(segment, start)),
                    physical[match.segmentIndex()],
                    segment.series().unit(),
                    match.distance(),
                    segment.measurementLabel()));
        }
        return choices;
    }

    private double physicalTolerance(GraphLogicalTrack track) {
        int visible = visibleSeriesCount();
        if (visible <= 0) {
            return 0;
        }
        double[] heights = stackedTrackHeights(Math.max(1, getHeight()), visible);
        double cursor = 0;
        int slot = 0;
        for (int i = 0; i < tracks.size(); i++) {
            if (trackHidden(i)) {
                continue;
            }
            double height = heights[Math.min(slot, heights.length - 1)];
            if (tracks.get(i) == track) {
                StackedTrackFrame frame = StackedTrackFrame.compute(cursor, height, slot == visible - 1);
                double plot = Math.max(1e-9, frame.plotBottom() - frame.plotTop());
                ArrayList<GraphBucket> buckets = new ArrayList<>();
                for (GraphTrackSegment segment : track.segments()) {
                    buckets.addAll(segment.series().buckets());
                }
                GraphAxisScale.Domain domain = GraphAxisScale.fromBuckets(buckets);
                if (!domain.hasUsable()) {
                    return 0;
                }
                return Math.abs(domain.axisMax() - domain.axisMin()) / plot * POINTER_TOLERANCE_PX;
            }
            cursor += height;
            slot++;
        }
        return 0;
    }

    private static long storedAt(GraphTrackSegment segment, long absolute) {
        if (segment.absoluteOriginNanos() == null) {
            return absolute;
        }
        try {
            return Math.subtractExact(absolute, segment.absoluteOriginNanos());
        } catch (ArithmeticException overflow) {
            return absolute;
        }
    }

    private void placePointerCursor(String which, double x, double y) {
        disarmChoice();
        List<MeasurementChoice> choices = choicesAt(x, y);
        if (choices.size() > 1) {
            choiceArmed = true;
            choiceAnchorX = x;
            choiceAnchorY = y;
            javafx.geometry.Point2D screen = localToScreen(x, y);
            double screenX = screen == null ? x : screen.getX();
            double screenY = screen == null ? y : screen.getY();
            overlapChoiceHandler.accept(new OverlapPick(which, screenX, screenY, choices));
            return;
        }
        if (choices.size() == 1) {
            measurementCursorHandler.accept(which, choices.getFirst().hit());
            return;
        }
        SharedTimelineHit.Hit owned = hitAt(x, y);
        if (owned != null && layoutOwned(owned)) {
            measurementCursorHandler.accept(which, owned);
            return;
        }
        Long gap = gapAbsolute(x);
        if (gap != null) {
            if ("S".equals(which)) {
                cursorHandler.accept("S", gap);
            } else {
                sharedGapHandler.accept(gap);
            }
            return;
        }
        cursorHandler.accept(which, timestampAt(x));
    }

    /** Absolute time when a shared axis is showing and no recording covers that instant. */
    private Long gapAbsolute(double x) {
        if (!hasSharedTimeline()) {
            return null;
        }
        double width = plotWidth();
        GraphTrackArrangement.Pane axis = sharedAxis(width);
        if (axis == null || x < axis.x0() || x > axis.x1()) {
            return null;
        }
        long absolute = absoluteOnAxis(axis, x);
        for (GraphLogicalTrack track : tracks) {
            if (!track.sharedAbsoluteTimeline() || track.segments().size() <= 1) {
                continue;
            }
            if (sharedHit(track, x, Double.NaN, width, null, null).isPresent()) {
                return null;
            }
        }
        return absolute;
    }

    private boolean hasSharedTimeline() {
        for (GraphLogicalTrack track : tracks) {
            if (track.sharedAbsoluteTimeline() && track.segments().size() > 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Measurement under the pointer. A single-file or file-focus track returns null so the
     * stored viewport cursor stays in place. A shared-axis gap also returns null.
     */
    private SharedTimelineHit.Hit hitAt(double x, double y) {
        if (tracks.isEmpty()) {
            return null;
        }
        double width = plotWidth();
        if (layoutMode != LayoutMode.STACKED) {
            SharedTimelineHit.Hit layout = overlayLayoutHit(x, y, width);
            if (layout != null) {
                return layout;
            }
            return overlayHit(x, y, width);
        }
        int trackIndex = trackAtY(y);
        if (trackIndex < 0 || trackIndex >= tracks.size()) {
            return null;
        }
        GraphLogicalTrack track = tracks.get(trackIndex);
        if (track.segments().size() <= 1) {
            return null;
        }
        int segment = owningSegment(track, x, y, width);
        if (segment < 0 || segment >= track.segments().size()) {
            return null;
        }
        if (track.sharedAbsoluteTimeline()) {
            return sharedHit(track, x, y, width, null, null).orElse(null);
        }
        GraphTrackSegment chosen = track.segments().get(segment);
        String id = chosen.series().key() == null ? "" : chosen.series().key().sourceFile();
        if (id == null || id.isBlank()) {
            return null;
        }
        long stored = timestampInSegment(track, segment, x, width);
        long start = segmentStart(chosen);
        long end = segmentEnd(chosen, start);
        return new SharedTimelineHit.Hit(
                id, track.logicalKey().signalName(), segment, stored, null, start, end);
    }

    private int owningSegment(GraphLogicalTrack track, double x, double y, double width) {
        if (track == null || track.segments().size() <= 1) {
            return track == null || track.segments().isEmpty() ? -1 : 0;
        }
        if (track.sharedAbsoluteTimeline()) {
            return sharedHit(track, x, y, width, null, null).map(SharedTimelineHit.Hit::segmentIndex).orElse(-1);
        }
        if (usesLayout(track)) {
            return layoutOwner(track, x, width);
        }
        return GraphTrackArrangement.paneContaining(panesFor(track, width), x);
    }

    private boolean layoutOwned(SharedTimelineHit.Hit hit) {
        if (hit == null || hit.segmentIndex() < 0) {
            return false;
        }
        for (GraphLogicalTrack track : tracks) {
            if (hit.segmentIndex() < track.segments().size()
                    && track.segments().get(hit.segmentIndex()).layoutSlot()
                    && track.logicalKey().signalName().equals(hit.signalName())) {
                return true;
            }
        }
        return false;
    }

    private boolean usesLayout(GraphLogicalTrack track) {
        if (track == null) {
            return false;
        }
        for (GraphTrackSegment segment : track.segments()) {
            if (segment.layoutSlot()) {
                return true;
            }
        }
        return false;
    }

    /** Slot {@code i} owns {@code [layoutStart, layoutEnd)}. The last slot also owns its end. */
    private int layoutOwner(GraphLogicalTrack track, double x, double width) {
        List<GraphTrackArrangement.Pane> panes = panesFor(track, width);
        int last = -1;
        for (int i = 0; i < panes.size(); i++) {
            GraphTrackArrangement.Pane pane = panes.get(i);
            if (pane == null || pane.x1() <= pane.x0()) {
                continue;
            }
            last = i;
            boolean end = i == panes.size() - 1;
            if (x >= pane.x0() && (end ? x <= pane.x1() : x < pane.x1())) {
                return i;
            }
        }
        return last >= 0 && x == panes.get(last).x1() ? last : -1;
    }

    private SharedTimelineHit.Hit overlayHit(double x, double y, double width) {
        SharedTimelineHit.Hit best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (GraphLogicalTrack track : tracks) {
            if (!track.sharedAbsoluteTimeline() || track.segments().size() <= 1) {
                continue;
            }
            double[] pixels = overlaySamplePixels(track, x, width);
            java.util.Optional<SharedTimelineHit.Hit> hit = sharedHit(track, x, y, width, y, pixels);
            if (hit.isEmpty()) {
                continue;
            }
            int index = hit.get().segmentIndex();
            double distance = index >= 0 && index < pixels.length && Double.isFinite(pixels[index])
                    ? Math.abs(pixels[index] - y) : Double.POSITIVE_INFINITY;
            if (best == null || distance < bestDistance || (distance == bestDistance && index > best.segmentIndex())) {
                best = hit.get();
                bestDistance = distance;
            }
        }
        return best;
    }

    private double[] overlaySamplePixels(GraphLogicalTrack track, double x, double width) {
        double[] pixels = new double[track.segments().size()];
        java.util.Arrays.fill(pixels, Double.NaN);
        GraphTrackArrangement.Pane axis = sharedAxis(width);
        if (axis == null) {
            return pixels;
        }
        long absolute = absoluteOnAxis(axis, x);
        double top = layoutMode == LayoutMode.OVERLAY_MULTI_AXIS ? 68 : TOP + 8;
        double bottom = Math.max(top + 1, getHeight() - BOTTOM);
        for (int i = 0; i < track.segments().size(); i++) {
            GraphTrackSegment segment = track.segments().get(i);
            if (segment.absoluteOriginNanos() == null) {
                continue;
            }
            long stored;
            try {
                stored = Math.subtractExact(absolute, segment.absoluteOriginNanos());
            } catch (ArithmeticException overflow) {
                continue;
            }
            double value = valueNear(segment, stored);
            if (!Double.isFinite(value)) {
                continue;
            }
            double min = usableMin(segment.series().buckets());
            double max = usableMax(segment.series().buckets(), min);
            double span = Math.max(1e-12, max - min);
            pixels[i] = bottom - ((value - min) / span) * (bottom - top);
        }
        return pixels;
    }

    private java.util.Optional<SharedTimelineHit.Hit> sharedHit(
            GraphLogicalTrack track, double x, double y, double width, Double pointer, double[] valuesOverride) {
        GraphTrackArrangement.Pane axis = sharedAxis(width);
        if (axis == null) {
            return java.util.Optional.empty();
        }
        long absolute = absoluteOnAxis(axis, x);
        java.util.ArrayList<SharedTimelineHit.Segment> segments = new java.util.ArrayList<>();
        double[] values = new double[track.segments().size()];
        for (int i = 0; i < track.segments().size(); i++) {
            GraphTrackSegment segment = track.segments().get(i);
            long start = segmentStart(segment);
            long end = segmentEnd(segment, start);
            String id = segment.series().key() == null ? "" : segment.series().key().sourceFile();
            segments.add(new SharedTimelineHit.Segment(
                    id, track.logicalKey().signalName(), start, end, segment.absoluteOriginNanos()));
            long stored = 0L;
            try {
                stored = segment.absoluteOriginNanos() == null ? absolute : Math.subtractExact(absolute, segment.absoluteOriginNanos());
            } catch (ArithmeticException overflow) {
                stored = absolute;
            }
            values[i] = valuesOverride == null ? valueNear(segment, stored) : valuesOverride[Math.min(i, valuesOverride.length - 1)];
        }
        Double chosenPointer = pointer != null && Double.isFinite(pointer) ? pointer : pointerValue(track, y);
        return SharedTimelineHit.select(absolute, segments, chosenPointer, valuesOverride == null ? values : valuesOverride);
    }

    private Double pointerValue(GraphLogicalTrack track, double y) {
        int visible = visibleSeriesCount();
        if (visible <= 0 || !Double.isFinite(y)) {
            return null;
        }
        double[] heights = stackedTrackHeights(Math.max(1, getHeight()), visible);
        double cursor = 0;
        int slot = 0;
        for (int i = 0; i < tracks.size(); i++) {
            if (trackHidden(i)) {
                continue;
            }
            double height = heights[Math.min(slot, heights.length - 1)];
            if (tracks.get(i) == track) {
                StackedTrackFrame frame = StackedTrackFrame.compute(cursor, height, slot == visible - 1);
                if (y < frame.plotTop() || y > frame.plotBottom()) {
                    return null;
                }
                java.util.ArrayList<GraphBucket> buckets = new java.util.ArrayList<>();
                for (GraphTrackSegment segment : track.segments()) {
                    buckets.addAll(segment.series().buckets());
                }
                GraphAxisScale.Domain domain = GraphAxisScale.fromBuckets(buckets);
                if (!domain.hasUsable()) {
                    return null;
                }
                double span = Math.max(1e-9, frame.plotBottom() - frame.plotTop());
                double fraction = (frame.plotBottom() - y) / span;
                return domain.axisMin() + fraction * (domain.axisMax() - domain.axisMin());
            }
            cursor += height;
            slot++;
        }
        return null;
    }

    private static double valueNear(GraphTrackSegment segment, long stored) {
        GraphBucket nearest = null;
        long best = Long.MAX_VALUE;
        for (GraphBucket bucket : segment.series().buckets()) {
            if (!bucket.lastUsable()) {
                continue;
            }
            long distance = Math.abs(bucket.lastTimestampNanos() - stored);
            if (distance < best) {
                best = distance;
                nearest = bucket;
            }
        }
        return nearest == null ? Double.NaN : nearest.lastValue();
    }

    private static long segmentStart(GraphTrackSegment segment) {
        long start = Long.MAX_VALUE;
        for (GraphBucket bucket : segment.series().buckets()) {
            start = Math.min(start, bucket.firstTimestampNanos());
        }
        return start == Long.MAX_VALUE ? 0L : start;
    }

    private static long segmentEnd(GraphTrackSegment segment, long start) {
        long end = Long.MIN_VALUE;
        for (GraphBucket bucket : segment.series().buckets()) {
            end = Math.max(end, bucket.lastTimestampNanos());
        }
        return end < start ? start : end;
    }

    private GraphTrackArrangement.Pane sharedAxis(double width) {
        for (GraphLogicalTrack track : tracks) {
            if (track.sharedAbsoluteTimeline() && track.segments().size() > 1) {
                List<GraphTrackArrangement.Pane> panes = panesFor(track, width);
                if (panes.isEmpty()) {
                    return null;
                }
                GraphTrackArrangement.Pane axis = panes.getFirst();
                if (layoutMode == LayoutMode.STACKED) {
                    return axis;
                }
                return new GraphTrackArrangement.Pane(
                        axis.index(), plotLeft(width), plotRight(width), axis.axisStart(), axis.axisEnd(), axis.originNanos());
            }
        }
        return null;
    }

    private static long absoluteOnAxis(GraphTrackArrangement.Pane axis, double x) {
        double span = Math.max(1e-6, axis.x1() - axis.x0());
        double fraction = Math.max(0, Math.min(1, (x - axis.x0()) / span));
        return SharedTimelineHit.absoluteAt(axis.axisStart(), axis.axisEnd(), fraction);
    }

    private static double xOnAxis(GraphTrackArrangement.Pane axis, long absolute) {
        long span = axis.axisEnd() - axis.axisStart();
        double width = Math.max(1e-6, axis.x1() - axis.x0());
        if (span <= 0) {
            return axis.x0();
        }
        double fraction = (absolute - axis.axisStart()) / (double) span;
        return axis.x0() + Math.max(0, Math.min(1, fraction)) * width;
    }

    private double plotWidth() {
        double width = canvas.getWidth() > 1 ? canvas.getWidth() : getWidth();
        return Math.max(1, width);
    }

    public long timestampAtForTest(double x) {
        return timestampAt(x);
    }

    public void clickPrimaryAtXForTest(double x) {
        // Source and target keep x local to this canvas. A scene-only event is
        // rebased by the canvas position and would select the wrong timestamp.
        javafx.scene.input.MouseEvent press = new javafx.scene.input.MouseEvent(
                this, this, javafx.scene.input.MouseEvent.MOUSE_PRESSED,
                x, 40, x, 40,
                MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, true, false, false, null);
        javafx.scene.input.MouseEvent release = new javafx.scene.input.MouseEvent(
                this, this, javafx.scene.input.MouseEvent.MOUSE_RELEASED,
                x, 40, x, 40,
                MouseButton.PRIMARY, 1,
                false, false, false, false, false, false, false, true, false, false, null);
        fireEvent(press);
        fireEvent(release);
    }

    /**
     * Primary click on an in-chart legend entry, through the same handler as a user click.
     * Overlay uses the shared legend above the plot. Stacked uses the track header.
     *
     * @return false when that signal has no legend hit in the current layout
     */
    public boolean clickLegendEntryForTest(SignalCatalogKey key) {
        if (key == null) {
            return false;
        }
        for (LegendHit hit : List.copyOf(legendHits)) {
            if (hit.key() == null || !key.equals(hit.key())) {
                continue;
            }
            double x = hit.x() + hit.w() / 2.0;
            double y = hit.y() + hit.h() / 2.0;
            javafx.scene.input.MouseEvent clicked = new javafx.scene.input.MouseEvent(
                    this, this, javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                    x, y, x, y,
                    MouseButton.PRIMARY, 1,
                    false, false, false, false, false, false, false, true, false, false, null);
            fireEvent(clicked);
            return true;
        }
        return false;
    }

    private long timestampAt(double x){
        if(viewportEnd<=viewportStart)return viewportStart;
        double width = canvas.getWidth() > 1 ? canvas.getWidth() : getWidth();
        double left=plotLeft(width), right=plotRight(width);
        double plotW=Math.max(1,right-left);
        double f=Math.max(0,Math.min(1,(x-left)/plotW));
        return viewportStart+(long)((viewportEnd-viewportStart)*f);
    }
    private double plotLeft(double w){
        if(layoutMode!=LayoutMode.OVERLAY_MULTI_AXIS)return LEFT;
        int axes=axisGroups().size(), leftAxes=(axes+1)/2; return 24+leftAxes*58.0;
    }
    private double plotRight(double w){
        if(layoutMode!=LayoutMode.OVERLAY_MULTI_AXIS)return w-RIGHT;
        int axes=axisGroups().size(), leftAxes=(axes+1)/2, rightAxes=axes-leftAxes; return w-24-rightAxes*58.0;
    }
    private double xFor(long ts,double w){double left=plotLeft(w),right=plotRight(w),plotW=Math.max(1,right-left);return viewportEnd<=viewportStart?left:left+((ts-viewportStart)/(double)(viewportEnd-viewportStart))*plotW;}

    /**
     * Nearest real bucket corner on the named signal. An empty name searches every plotted signal.
     * The result is never a time or value interpolated between samples.
     */
    public PlottedSample nearestPlottedSample(long timestampNanos, String signalName) {
        PlottedSample best = null;
        long bestDelta = Long.MAX_VALUE;
        String wanted = signalName == null ? "" : signalName;
        for (GraphRenderSeries seriesItem : series) {
            if (seriesItem == null || seriesItem.key() == null) {
                continue;
            }
            if (!wanted.isBlank() && !sameSignal(wanted, seriesItem)) {
                continue;
            }
            for (GraphBucket bucket : seriesItem.buckets()) {
                best = closer(best, bestDelta, seriesItem, bucket.firstTimestampNanos(), bucket.firstValue(),
                        bucket.firstValidity(), bucket.firstLabel(), timestampNanos);
                bestDelta = best == null ? Long.MAX_VALUE : Math.abs(best.timestampNanos() - timestampNanos);
                best = closer(best, bestDelta, seriesItem, bucket.minTimestampNanos(), bucket.minValue(),
                        bucket.minValidity(), bucket.minLabel(), timestampNanos);
                bestDelta = best == null ? Long.MAX_VALUE : Math.abs(best.timestampNanos() - timestampNanos);
                best = closer(best, bestDelta, seriesItem, bucket.maxTimestampNanos(), bucket.maxValue(),
                        bucket.maxValidity(), bucket.maxLabel(), timestampNanos);
                bestDelta = best == null ? Long.MAX_VALUE : Math.abs(best.timestampNanos() - timestampNanos);
                best = closer(best, bestDelta, seriesItem, bucket.lastTimestampNanos(), bucket.lastValue(),
                        bucket.lastValidity(), bucket.lastLabel(), timestampNanos);
                bestDelta = best == null ? Long.MAX_VALUE : Math.abs(best.timestampNanos() - timestampNanos);
            }
        }
        return best;
    }

    private static PlottedSample closer(
            PlottedSample best,
            long bestDelta,
            GraphRenderSeries seriesItem,
            long timestampNanos,
            double value,
            SignalValidity validity,
            String label,
            long target
    ) {
        boolean numeric = validity != null && validity.usableWith(value);
        boolean named = label != null && !label.isBlank();
        if (!numeric && !named) {
            return best;
        }
        long delta = Math.abs(timestampNanos - target);
        if (best != null && delta >= bestDelta) {
            return best;
        }
        String unit = seriesItem.unit() == null ? "" : seriesItem.unit();
        String number = numeric ? formatSample(value) : "";
        String text = number + (unit.isBlank() || number.isBlank() ? "" : " " + unit);
        if (named) {
            text = number.isBlank() ? label.trim() : label.trim() + " (" + number + ")";
        }
        double stored = numeric ? value : Double.NaN;
        return new PlottedSample(seriesItem.key(), seriesItem.key().signalName(), unit, timestampNanos, stored, text);
    }

    private static String formatSample(double value) {
        if (!Double.isFinite(value)) {
            return "non-finite";
        }
        if (Math.abs(value - Math.rint(value)) < 1e-9 && Math.abs(value) < 1e12) {
            return String.format(java.util.Locale.ROOT, "%.0f", value);
        }
        return String.format(java.util.Locale.ROOT, "%.6g", value);
    }

    public boolean hasPlottedSeries() {
        for (GraphRenderSeries item : series) {
            if (item != null && item.buckets() != null && !item.buckets().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Value-table text on the nearest plotted corner, or blank. */
    public String stateLabelAt(String signalName, long timestampNanos) {
        String label = "";
        long bestDelta = Long.MAX_VALUE;
        String wanted = signalName == null ? "" : signalName;
        for (GraphRenderSeries item : series) {
            if (item == null || item.buckets() == null || (!wanted.isBlank() && !sameSignal(wanted, item))) {
                continue;
            }
            for (GraphBucket bucket : item.buckets()) {
                long[] times = {
                        bucket.firstTimestampNanos(), bucket.minTimestampNanos(),
                        bucket.maxTimestampNanos(), bucket.lastTimestampNanos()
                };
                String[] labels = {bucket.firstLabel(), bucket.minLabel(), bucket.maxLabel(), bucket.lastLabel()};
                for (int i = 0; i < times.length; i++) {
                    if (labels[i] == null || labels[i].isBlank()) {
                        continue;
                    }
                    long delta = Math.abs(times[i] - timestampNanos);
                    if (delta < bestDelta) {
                        bestDelta = delta;
                        label = labels[i].trim();
                    }
                }
            }
        }
        return label;
    }

    /** Waveform position of a point event. Null when the signal is not on the current plot. */
    public javafx.geometry.Point2D pointMarkerLocation(String signalName, long timestampNanos, Double sampleValue) {
        if (timestampNanos < viewportStart || timestampNanos > viewportEnd) {
            return null;
        }
        double width = Math.max(1, getWidth());
        double height = Math.max(1, getHeight());
        Double y = yForSignalValue(signalName, sampleValue == null ? Double.NaN : sampleValue, width, height);
        if (y == null) {
            return null;
        }
        return new javafx.geometry.Point2D(xFor(timestampNanos, width), y);
    }

    public java.util.List<javafx.scene.Node> pointMarkerNodesForTest() {
        return pointMarkers.nodesForTest();
    }

    private java.util.List<GraphPointEventMarkers.Marker> pendingPointMarkers = java.util.List.of();
    private boolean pointMarkerApplyPosted;

    private void syncPointMarkers(double width, double height) {
        if (!analysisOverlaysVisible) {
            pendingPointMarkers = java.util.List.of();
        } else {
            java.util.ArrayList<GraphPointEventMarkers.Marker> markers = new java.util.ArrayList<>();
            for (AnalysisOverlay overlay : analysisOverlays) {
                if (!overlay.pointMarker()) {
                    continue;
                }
                javafx.geometry.Point2D at = plottedAnchor(overlay, width, height);
                if (at == null) {
                    continue;
                }
                String id = overlay.markerId().isBlank()
                        ? overlay.signalName() + "@" + overlay.startNanos()
                        : overlay.markerId();
                markers.add(new GraphPointEventMarkers.Marker(
                        id, at.getX(), at.getY(),
                        overlay.label(), overlay.timeText(), overlay.valueText(), overlay.note()));
            }
            pendingPointMarkers = markers;
        }
        if (!pointMarkerApplyPosted) {
            pointMarkerApplyPosted = true;
            javafx.application.Platform.runLater(() -> {
                pointMarkerApplyPosted = false;
                pointMarkers.sync(pendingPointMarkers);
            });
        }
    }

    public void flushPointMarkersForTest() {
        pointMarkerApplyPosted = false;
        pointMarkers.sync(pendingPointMarkers);
    }

    private javafx.geometry.Point2D plottedAnchor(AnalysisOverlay overlay, double width, double height) {
        if (overlay == null || overlay.startNanos() < viewportStart || overlay.startNanos() > viewportEnd) {
            return null;
        }
        Double y = yForPoint(overlay, width, height);
        if (y == null || !Double.isFinite(y) || y < -24 || y > height + 24) {
            return null;
        }
        double x = xFor(overlay.startNanos(), width);
        if (!Double.isFinite(x) || x < -24 || x > width + 24) {
            return null;
        }
        return new javafx.geometry.Point2D(x, y);
    }

    /**
     * The plus is painted into the waveform tile, above the cursor stroke.
     * It is only the glyph. It does not add a guide line.
     */
    private void drawPointEventGlyphs(GraphicsContext g, double width, double height) {
        if (!analysisOverlaysVisible || analysisOverlays.isEmpty()) {
            return;
        }
        double tileTop = paintOriginY;
        double tileBottom = paintOriginY + Math.max(1, canvas.getHeight());
        for (AnalysisOverlay overlay : analysisOverlays) {
            if (!overlay.pointMarker()) {
                continue;
            }
            javafx.geometry.Point2D at = plottedAnchor(overlay, width, height);
            if (at == null || at.getY() < tileTop - 12 || at.getY() > tileBottom + 12) {
                continue;
            }
            double x = at.getX();
            double y = at.getY();
            g.setFill(GraphChrome.ANALYSIS_EVENT);
            g.fillOval(x - 8, y - 8, 16, 16);
            g.setStroke(Color.WHITE);
            g.setLineWidth(2.2);
            g.strokeLine(x - 4.5, y, x + 4.5, y);
            g.strokeLine(x, y - 4.5, x, y + 4.5);
        }
    }

    private Double yForPoint(AnalysisOverlay overlay, double width, double height) {
        Double value = overlay.sampleValue();
        if (value == null || !Double.isFinite(value)) {
            PlottedSample sample = nearestPlottedSample(overlay.startNanos(), overlay.signalName());
            if (sample != null && sample.timestampNanos() == overlay.startNanos() && Double.isFinite(sample.value())) {
                value = sample.value();
            } else {
                value = levelValue(overlay.signalName(), stateLabelAt(overlay.signalName(), overlay.startNanos()));
            }
        }
        if (value == null || !Double.isFinite(value)) {
            return null;
        }
        return yForSignalValue(overlay.signalName(), value, width, height);
    }

    private Double levelValue(String signalName, String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        int trackIndex = trackIndexFor(signalName);
        if (trackIndex < 0) {
            return null;
        }
        java.util.ArrayList<GraphBucket> buckets = new java.util.ArrayList<>();
        for (GraphTrackSegment segment : tracks.get(trackIndex).segments()) {
            buckets.addAll(segment.series().buckets());
        }
        for (EnumeratedTrace.Level level : EnumeratedTrace.levels(buckets)) {
            if (label.equals(level.label())) {
                return level.value();
            }
        }
        return null;
    }

    private int trackIndexFor(String signalName) {
        if (signalName == null || signalName.isBlank()) {
            return -1;
        }
        for (int i = 0; i < tracks.size(); i++) {
            if (trackHidden(i)) {
                continue;
            }
            GraphLogicalTrack track = tracks.get(i);
            if (track.logicalKey() != null && signalName.equals(track.logicalKey().signalName())) {
                return i;
            }
            for (GraphTrackSegment segment : track.segments()) {
                if (segment.series() != null && sameSignal(signalName, segment.series())) {
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean sameSignal(String wanted, GraphRenderSeries seriesItem) {
        if (seriesItem == null || seriesItem.key() == null || wanted == null || wanted.isBlank()) {
            return false;
        }
        if (wanted.equals(seriesItem.key().signalName()) || wanted.equals(labelOf(seriesItem))) {
            return true;
        }
        return wanted.startsWith(seriesItem.key().signalName() + " \u00b7 ");
    }

    private javafx.scene.layout.Pane noteLayer() {
        boolean passedScroll = false;
        javafx.scene.Node node = getParent();
        while (node != null) {
            if (node instanceof javafx.scene.control.ScrollPane) {
                passedScroll = true;
            } else if (passedScroll && node instanceof javafx.scene.layout.StackPane
                    && !(node instanceof javafx.scene.control.TabPane)) {
                return (javafx.scene.layout.Pane) node;
            }
            node = node.getParent();
        }
        return null;
    }

    private void releaseCoveringCache() {
        javafx.scene.Node node = this;
        while (node != null) {
            if (node.isCache()) {
                node.setCache(false);
            }
            node = node.getParent();
        }
    }

    private boolean markerUnder(javafx.scene.input.MouseEvent event) {
        if (isPointMarkerTarget(event.getTarget())) {
            return true;
        }
        if (pointMarkers.hits(event.getX(), event.getY())) {
            return true;
        }
        for (javafx.scene.Node node : getChildren()) {
            if (node.getStyleClass().contains("graph-event-marker") && node.getBoundsInParent().contains(event.getX(), event.getY())) {
                return true;
            }
        }
        return false;
    }

    private boolean isPointMarkerTarget(Object target) {
        javafx.scene.Node node = target instanceof javafx.scene.Node child ? child : null;
        while (node != null) {
            if (node.getStyleClass().contains("graph-event-marker") || node.getStyleClass().contains("graph-event-note")) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }

    private Double yForSignalValue(String signalName, double value, double width, double height) {
        if (signalName == null || signalName.isBlank() || !Double.isFinite(value)) {
            return null;
        }
        int trackIndex = trackIndexFor(signalName);
        if (trackIndex < 0) {
            return null;
        }
        GraphLogicalTrack track = tracks.get(trackIndex);
        java.util.ArrayList<GraphBucket> buckets = new java.util.ArrayList<>();
        for (GraphTrackSegment segment : track.segments()) {
            buckets.addAll(segment.series().buckets());
        }
        if (layoutMode == LayoutMode.STACKED) {
            int visible = Math.max(1, visibleSeriesCount());
            double[] heights = stackedTrackHeights(height, visible);
            int slot = 0;
            double top = 0;
            int visibleIndex = 0;
            for (int i = 0; i < tracks.size(); i++) {
                if (trackHidden(i)) {
                    continue;
                }
                double trackHeight = heights[Math.min(slot, heights.length - 1)];
                if (i == trackIndex) {
                    StackedTrackFrame frame = StackedTrackFrame.compute(top, trackHeight, visibleIndex == visible - 1);
                    GraphAxisScale.Domain domain = GraphAxisScale.fromBuckets(buckets);
                    if (!domain.hasUsable()) {
                        return null;
                    }
                    return valueY(value, domain.axisMin(), domain.axisMax(), frame.plotTop(), frame.plotBottom());
                }
                top += trackHeight;
                slot++;
                visibleIndex++;
            }
            return null;
        }
        double min;
        double max;
        double plotTop;
        double plotBottom;
        if (layoutMode == LayoutMode.OVERLAY_NORMALIZED) {
            min = usableMin(buckets);
            max = usableMax(buckets, min);
            plotTop = TOP + 8;
            plotBottom = height - BOTTOM;
        } else {
            AxisGroup axis = axisForTrack(track, axisGroups());
            if (axis == null) {
                return null;
            }
            min = axis.min();
            max = axis.max();
            plotTop = 68;
            plotBottom = height - BOTTOM - 12;
        }
        return valueY(value, min, max, plotTop, plotBottom);
    }

    private static double valueY(double value, double min, double max, double top, double bottom) {
        double span = max - min;
        double ratio = Math.abs(span) < 1e-12 ? 0.5 : (value - min) / span;
        ratio = Math.max(0, Math.min(1, ratio));
        return bottom - ratio * (bottom - top);
    }

    private AnalysisOverlay overlayAt(double x) {
        if (!analysisOverlaysVisible || analysisOverlays.isEmpty()) {
            return null;
        }
        double width = getWidth();
        AnalysisOverlay best = null;
        double bestDelta = 10;
        for (AnalysisOverlay overlay : analysisOverlays) {
            if (overlay.pointMarker()) {
                continue;
            }
            double delta = Math.abs(xFor(overlay.startNanos(), width) - x);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = overlay;
            }
        }
        return best;
    }

    private void redraw(){
        if (!paintGate.begin()) {
            return;
        }
        recordPaintReason();
        double w = canvas.getWidth();
        double logicalH = getHeight();
        double h = Double.isFinite(logicalH) && logicalH > 1 ? logicalH : canvas.getHeight();
        paintOriginY = canvas.getLayoutY();
        legendHits.clear();
        paintedStateLabels.clear();
        long paintStarted = System.nanoTime();
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.save();
        try {
            // A path clip of the whole canvas does not change pixels. On the software
            // pipeline every later draw is blended through a full-size texture
            // (NGCanvas.blendAthruBintoC → PiscesRenderer.drawImage). Plot clips below
            // stay where a series must not cover the axis.
            g.translate(0, -paintOriginY);
            g.clearRect(0, paintOriginY, w, canvas.getHeight());
            g.setFill(plotBackground);
            g.fillRect(0, paintOriginY, w, canvas.getHeight());
            paintSeries(g, w, h);
            drawFileNavigator(g, w);
        } finally {
            g.restore();
            lastRedrawNanos = System.nanoTime() - paintStarted;
            syncPointMarkers(w, h);
        }
    }

    /** Duration of the most recent synchronous canvas paint, including layout of the current tracks. */
    public long lastRedrawMillis() {
        return lastRedrawNanos / 1_000_000L;
    }

    /** Value-table names painted on the Y axis of the last redraw. */
    public List<String> paintedStateLabelsForTest() {
        return List.copyOf(paintedStateLabels);
    }

    private void paintSeries(GraphicsContext g, double w, double h) {
        if(series.isEmpty()){
            g.setFill(GraphChrome.AXIS_LABEL);
            String hint=emptyHint==null||emptyHint.isBlank()
                    ?"No tracks to display."
                    :emptyHint;
            g.fillText(hint,16,paintOriginY + 28);
            return;
        }
        if(visibleSeriesCount()==0){
            g.setFill(GraphChrome.AXIS_LABEL);
            g.fillText(emptyHint==null||emptyHint.isBlank()
                    ?"All selected signals are hidden. Show a signal to plot."
                    :emptyHint,16,paintOriginY + 28);
            if(sharedLegendActive()) drawInteractiveLegend(g,LEFT,paintOriginY + 48,w-RIGHT,22);
            return;
        }
        if(layoutMode==LayoutMode.OVERLAY_NORMALIZED) drawOverlay(g,w,h);
        else if(layoutMode==LayoutMode.OVERLAY_MULTI_AXIS) drawMultiAxisOverlay(g,w,h);
        else {
            int visible=visibleSeriesCount();
            double[] trackHeights=stackedTrackHeights(h, Math.max(1, visible));
            int slot=0;
            double y=0;
            for(int i=0;i<tracks.size();i++){
                if(trackHidden(i))continue;
                double trackH=trackHeights[Math.min(slot, trackHeights.length-1)];
                double tileBottom = paintOriginY + canvas.getHeight();
                if (y + trackH >= paintOriginY && y <= tileBottom) {
                    if (unloadedKeys.contains(tracks.get(i).logicalKey())) {
                        drawUnloadedTrack(g, tracks.get(i), i, y, w, trackH);
                    } else {
                        drawLogicalTrack(g,tracks.get(i),i,y,w,trackH,slot==visible-1);
                    }
                }
                y+=trackH;
                slot++;
            }
        }
        drawNavigator(g,w,h);
        if (analysisOverlaysVisible) {
            drawAnalysisOverlays(g, w, h);
        }
        drawCursor(g,cursorA,cursorADisplay,"A",GraphChrome.CURSOR_A,w,h);drawCursor(g,cursorB,cursorBDisplay,"B",GraphChrome.CURSOR_B,w,h);
        if (selectionNanos != null && !selectionNanos.equals(cursorA) && !selectionNanos.equals(cursorB)) {
            drawCursor(g, selectionNanos, null, "", GraphChrome.CURSOR_A, w, h);
        }
        drawPointEventGlyphs(g, w, h);
        Long hover=hoverTimestamp.get(); if(showCrosshair && hover!=null){
            double x=multiFilePointer() ? pointerX : xFor(hover,w);
            g.setStroke(GraphChrome.CROSSHAIR);g.setLineDashes(3,3);g.strokeLine(x,0,x,h);g.setLineDashes(null);drawHoverTooltip(g,hover,x,w,h);
        }
    }


    private void drawNavigator(GraphicsContext g,double w,double h){
        if(layoutMode==LayoutMode.STACKED || series.isEmpty() || h<250)return;
        double left=plotLeft(w), right=plotRight(w), navH=30, bottom=h-5, top=bottom-navH;
        if(right-left<180)return;
        g.setFill(GraphChrome.NAVIGATOR_FILL); g.fillRoundRect(left,top,right-left,navH,7,7);
        g.setStroke(GraphChrome.NAVIGATOR_STROKE); g.strokeRoundRect(left,top,right-left,navH,7,7);
        GraphRenderSeries s=series.get(0); if(s.buckets().isEmpty())return;
        double min=usableMin(s.buckets());
        double max=usableMax(s.buckets(), min);
        double den=Math.max(1e-12,max-min); g.setStroke(GraphChrome.NAVIGATOR_TRACE); g.setLineWidth(.8); P prev=null;
        for(GraphBucket b:s.buckets()){
            if(!b.lastUsable()){ prev=null; continue; }
            long shown=b.lastTimestampNanos();
            GraphTrackSegment owned=segmentOf(s);
            if(owned!=null && owned.absoluteOriginNanos()!=null){
                shown=GraphTrackArrangement.addSaturating(owned.absoluteOriginNanos(), shown);
            }
            double f=viewportEnd<=viewportStart?0:(shown-viewportStart)/(double)(viewportEnd-viewportStart);
            double x=left+Math.max(0,Math.min(1,f))*(right-left); double y=bottom-4-((b.lastValue()-min)/den)*(navH-8); P q=new P(b.lastTimestampNanos(),x,y);
            if(prev!=null)g.strokeLine(prev.x,prev.y,q.x,q.y); prev=q;
        }
        g.setStroke(GraphChrome.NAVIGATOR_WINDOW);g.setLineWidth(1.2);g.strokeRoundRect(left+2,top+2,right-left-4,navH-4,5,5);
    }

    private void drawHoverTooltip(GraphicsContext g,long ts,double x,double w,double h){
        int visibleTracks=visibleSeriesCount();
        if(visibleTracks==0)return;
        int count=Math.min(visibleTracks,8); double boxW=420, rowH=19, boxH=34+count*rowH;
        double bx=x+14; if(bx+boxW>w-8)bx=x-boxW-14; bx=Math.max(8,bx);
        double viewTop=paintOriginY, viewBottom=paintOriginY+canvas.getHeight();
        double by=viewTop+44; if(by+boxH>viewBottom-8)by=Math.max(viewTop+8,viewBottom-boxH-8);
        g.setFill(GraphChrome.TOOLTIP_SURFACE);g.fillRoundRect(bx,by,boxW,boxH,9,9);g.setStroke(GraphChrome.TOOLTIP_BORDER);g.setLineWidth(1);g.strokeRoundRect(bx,by,boxW,boxH,9,9);
        g.setFill(GraphChrome.TOOLTIP_TITLE);g.fillText("Time: "+String.format("%.6f s",ts/1e9),bx+12,by+20);
        int row=0;
        for(int trackIndex=0;trackIndex<tracks.size() && row<count;trackIndex++){
            if(trackHidden(trackIndex)) continue;
            GraphLogicalTrack track=tracks.get(trackIndex);
            GraphRenderSeries s;
            long sampleTs=ts;
            if(track.segments().size()>1 && !Double.isNaN(pointerX)){
                List<MeasurementChoice> choices = choicesAt(pointerX, pointerY).stream()
                        .filter(choice -> track.logicalKey().signalName().equals(choice.hit().signalName()))
                        .toList();
                if (choices.size() > 1) {
                    Color c = colorOf(seriesIndexOfTrack(trackIndex));
                    int shown = Math.min(choices.size(), 6);
                    for (int i = 0; i < shown && row < count; i++) {
                        MeasurementChoice choice = choices.get(i);
                        double yy = by + 40 + row * rowH;
                        g.setFill(c);
                        g.fillOval(bx + 12, yy - 8, 8, 8);
                        g.setFill(GraphChrome.TOOLTIP_VALUE);
                        String name = shortName(choice.segmentLabel().isBlank()
                                ? choice.hit().measurementId() : choice.segmentLabel(), 36);
                        g.fillText(name, bx + 27, yy);
                        String val = Double.isFinite(choice.physicalValue())
                                ? String.format("%.3f %s", choice.physicalValue(), choice.unit()).trim()
                                : "—";
                        g.setTextAlign(javafx.scene.text.TextAlignment.RIGHT);
                        g.fillText(val, bx + boxW - 12, yy);
                        g.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
                        row++;
                    }
                    continue;
                }
                int segment=owningSegment(track, pointerX, pointerY, w);
                if(segment<0){
                    if(track.sharedAbsoluteTimeline()){
                        g.setFill(GraphChrome.TOOLTIP_VALUE);
                        g.fillText(com.stellar.platform.processing.time.MeasurementCursor.NO_MEASUREMENT_AT_TIME, bx+12, by+40);
                    }
                    continue;
                }
                s=track.segments().get(segment).series();
                sampleTs=timestampInSegment(track, segment, pointerX, w);
                String file=track.segments().get(segment).measurementLabel();
                GraphBucket q=nearestBucket(s.buckets(), sampleTs);
                if(q==null) continue;
                double yy=by+40+row*rowH;Color c=colorOf(seriesIndexOfTrack(trackIndex));g.setFill(c);g.fillOval(bx+12,yy-8,8,8);g.setFill(GraphChrome.TOOLTIP_VALUE);
                String name=shortName(labelOf(s), 28);
                if(file!=null && !file.isBlank()) name=name+" · "+shortName(file, 24);
                g.fillText(name,bx+27,yy);String val=formatHoverValue(q,s.unit());
                g.setTextAlign(javafx.scene.text.TextAlignment.RIGHT);g.fillText(val,bx+boxW-12,yy);g.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
                row++;
                continue;
            }else if(!track.segments().isEmpty()){
                s=track.segments().getFirst().series();
            }else{
                continue;
            }
            GraphBucket q=nearestBucket(s.buckets(), sampleTs);
            if(q==null) continue;
            double yy=by+40+row*rowH;Color c=colorOf(seriesIndexOfTrack(trackIndex));g.setFill(c);g.fillOval(bx+12,yy-8,8,8);g.setFill(GraphChrome.TOOLTIP_VALUE);
            g.fillText(shortName(labelOf(s),48),bx+27,yy);String val=formatHoverValue(q,s.unit());
            g.setTextAlign(javafx.scene.text.TextAlignment.RIGHT);g.fillText(val,bx+boxW-12,yy);g.setTextAlign(javafx.scene.text.TextAlignment.LEFT);
            row++;
        }
    }
    private void drawCursor(GraphicsContext g,Long ts,String label,Color c,double w,double h){
        drawCursor(g, ts, null, label, c, w, h);
    }

    private void drawCursor(GraphicsContext g, Long ts, Long display, String label, Color c, double w, double h) {
        GraphTrackArrangement.Pane axis = display == null ? null : sharedAxis(w);
        double x;
        if (unknownOrderOverview && display != null) {
            if (display < viewportStart || display > viewportEnd) {
                return;
            }
            x = xFor(display, w);
        } else if (axis != null) {
            if (display < axis.axisStart() || display > axis.axisEnd()) {
                return;
            }
            x = xOnAxis(axis, display);
        } else {
            if (ts == null || ts < viewportStart || ts > viewportEnd) {
                return;
            }
            x = xFor(ts, w);
        }
        g.setStroke(c);
        g.setLineWidth(1.2);
        g.strokeLine(x, 0, x, h);
        g.setFill(c);
        g.fillText(label, Math.min(w - 14, x + 4), 14);
    }

    private void drawEventMark(
            GraphicsContext g, long ts, String label, Color color, double width, double labelY, double w, double h
    ) {
        if (ts < viewportStart || ts > viewportEnd) {
            return;
        }
        double x = xFor(ts, w);
        g.setStroke(color);
        g.setLineWidth(width);
        g.strokeLine(x, 0, x, h);
        g.setFill(color);
        g.fillText(label == null ? "" : label, Math.min(w - 14, x + 4), labelY);
    }

    private void drawUnloadedTrack(GraphicsContext g, GraphLogicalTrack track, int trackIndex, double y, double w, double h) {
        g.setStroke(GraphChrome.GRID);
        g.strokeLine(0, y + h - 1, w, y + h - 1);
        Integer assigned = colorIndexes.get(track.logicalKey());
        int palette = assigned == null ? trackIndex : assigned;
        Color seriesColor = GraphSeriesPalette.at(palette);
        g.setStroke(seriesColor);
        g.setLineWidth(3.0);
        double[] dashes = GraphSeriesPalette.dashesAt(palette);
        if (dashes == null || dashes.length == 0) {
            g.setLineDashes(null);
        } else {
            g.setLineDashes(dashes);
        }
        g.strokeLine(10, y + 7, 10, y + 18);
        g.setLineDashes(null);
        g.setFill(GraphChrome.TITLE);
        String title = track.logicalKey().signalName() + " · Loading…";
        g.fillText(title, 17, y + 16);
    }

    private void drawLogicalTrack(GraphicsContext g, GraphLogicalTrack track, int trackIndex, double y, double w, double h, boolean last) {
        if (track.segments().size() <= 1 && !usesLayout(track)) {
            GraphRenderSeries only = track.segments().isEmpty() ? null : track.segments().getFirst().series();
            if (only == null) {
                return;
            }
            int seriesIndex = Math.max(0, series.indexOf(only));
            drawTrack(g, only, seriesIndex, y, w, h, last, track.logicalKey());
            return;
        }
        StackedTrackFrame frame = StackedTrackFrame.compute(y, h, last);
        double top = frame.plotTop();
        double bottom = frame.plotBottom();
        g.setStroke(GraphChrome.GRID);
        g.strokeLine(0, y + h - 1, w, y + h - 1);
        int seriesIndex = seriesIndexOfTrack(trackIndex);
        Color seriesColor = colorOf(seriesIndex);
        String title = track.logicalKey().signalName()
                + (track.unit().isBlank() ? "" : " [" + track.unit() + "]")
                + " · " + track.segments().size() + " files";
        g.setStroke(seriesColor);
        g.setLineWidth(3.0);
        applySeriesDashes(g, seriesIndex);
        g.strokeLine(10, y + 7, 10, y + 18);
        g.setLineDashes(null);
        g.setFill(GraphChrome.TITLE);
        g.fillText(title, 17, y + 16);
        if (pointerInTrack(y, h)) {
            List<MeasurementChoice> choices = choicesAt(pointerX, pointerY).stream()
                    .filter(choice -> track.logicalKey().signalName().equals(choice.hit().signalName()))
                    .toList();
            if (choices.size() > 1) {
                g.fillText(choices.size() + " measurements", Math.max(160, w - RIGHT - 300), y + 16);
            } else if (choices.size() == 1 && !choices.getFirst().segmentLabel().isBlank()) {
                g.fillText(shortName(choices.getFirst().segmentLabel(), 42), Math.max(160, w - RIGHT - 300), y + 16);
            } else {
                int hovered = owningSegment(track, pointerX, pointerY, w);
                if (hovered >= 0 && hovered < track.segments().size()) {
                    String file = track.segments().get(hovered).measurementLabel();
                    if (file != null && !file.isBlank()) {
                        g.fillText(shortName(file, 42), Math.max(160, w - RIGHT - 300), y + 16);
                    }
                }
            }
        }
        double titleWidth = Math.min(w - 40, 24 + title.length() * 7.0);
        legendHits.add(new LegendHit(seriesIndexOfTrack(trackIndex), track.logicalKey(), 10, y + 2, titleWidth, 18, false));
        java.util.ArrayList<GraphBucket> domainBuckets = new java.util.ArrayList<>();
        for (GraphTrackSegment segment : track.segments()) {
            domainBuckets.addAll(segment.series().buckets());
        }
        GraphAxisScale.Domain domain = GraphAxisScale.fromBuckets(domainBuckets);
        if (!domain.hasUsable()) {
            g.setFill(GraphChrome.AXIS_LABEL);
            g.fillText("No usable samples in viewport", LEFT, top + 18);
            return;
        }
        int ticks = axisTickCount(bottom - top, domain.binary(), domain.constant());
        percentTicks = false;
        boolean state = EnumeratedTrace.isState(domainBuckets);
        drawGridAndAxes(g, w, top, bottom, domain.axisMin(), domain.axisMax(), false, ticks, domain.binary(),
                domain.constant() ? domain.dataMin() : null, frame.timeLabelBaseline(), frame.captionBaseline(),
                state ? EnumeratedTrace.levels(domainBuckets) : List.of());
        List<GraphTrackArrangement.Pane> panes = panesFor(track, w);
        GraphTrackArrangement.Mode mode = GraphTrackArrangement.mode(track.sharedAbsoluteTimeline(), track.segments().size());
        for (int i = 0; i < track.segments().size() && i < panes.size(); i++) {
            GraphTrackSegment segment = track.segments().get(i);
            GraphTrackArrangement.Pane pane = panes.get(i);
            if (missesPlot(pane.x0(), pane.x1(), w)) {
                continue;
            }
            if (usesLayout(track) && pane.x0() >= LEFT) {
                g.setStroke(GraphChrome.GRID);
                g.setLineWidth(1.0);
                g.strokeLine(pane.x0(), top, pane.x0(), bottom);
            }
            if (pane.x1() - pane.x0() > 36) {
                g.setFill(GraphChrome.AXIS_LABEL);
                g.fillText(shortName(segment.measurementLabel().isBlank()
                        ? segment.series().key().signalName() : segment.measurementLabel(), 18), pane.x0() + 2, top + 12);
            }
            if (segment.series().buckets().isEmpty()) {
                if (pane.x1() - pane.x0() > 48) {
                    g.setFill(GraphChrome.AXIS_LABEL);
                    g.fillText("No samples", pane.x0() + 2, top + 28);
                }
                continue;
            }
            List<P> pts = pointsInPane(segment.series().buckets(), domain.axisMin(), domain.axisMax(),
                    pane, top, bottom);
            g.setStroke(seriesColor);
            g.setLineWidth(1.25);
            applySeriesDashes(g, seriesIndex);
            boolean segmentState = EnumeratedTrace.isState(segment.series().buckets());
            long segmentGap = segmentState ? Math.max(1L, (viewportEnd - viewportStart) / 12) : Long.MAX_VALUE;
            drawSeries(g, pts, segmentGap, domain.binary() || segmentState);
            if (mode == GraphTrackArrangement.Mode.INDEPENDENT_RELATIVE && pane.x1() - pane.x0() > 48) {
                g.setFill(GraphChrome.AXIS_LABEL);
                g.fillText(formatTime(pane.axisStart()), pane.x0(), frame.timeLabelBaseline());
            }
        }
        if (mode == GraphTrackArrangement.Mode.SHARED_ABSOLUTE && last) {
            g.setFill(GraphChrome.AXIS_LABEL);
            long span = Math.max(1L, panes.getFirst().axisEnd() - panes.getFirst().axisStart());
            for (int i = 0; i <= 4; i++) {
                double f = i / 4.0;
                double x = panes.getFirst().x0() + f * (panes.getFirst().x1() - panes.getFirst().x0());
                g.fillText(formatTime(span == 0 ? 0 : (long) (span * f)), x, frame.timeLabelBaseline());
            }
        }
        g.setFill(GraphChrome.AXIS_LABEL);
        String caption = unknownOrderOverview
                ? "UNKNOWN ORDER · slot position is not elapsed time"
                : mode == GraphTrackArrangement.Mode.SHARED_ABSOLUTE
                ? "Aligned recording clocks (s)"
                : "Each file keeps its own clock";
        g.fillText(caption, LEFT, frame.captionBaseline());
    }

    private boolean pointerInTrack(double y, double height) {
        return !Double.isNaN(pointerY) && pointerY >= y && pointerY < y + height;
    }

    private boolean multiFilePointer() {
        if (Double.isNaN(pointerY) || layoutMode != LayoutMode.STACKED) {
            return false;
        }
        int trackIndex = trackAtY(pointerY);
        return trackIndex >= 0 && trackIndex < tracks.size() && tracks.get(trackIndex).segments().size() > 1;
    }

    private List<GraphTrackArrangement.Pane> panesFor(GraphLogicalTrack track, double width) {
        if (usesLayout(track)) {
            return layoutPanes(track, width);
        }
        java.util.ArrayList<GraphTrackArrangement.Input> inputs = new java.util.ArrayList<>();
        for (GraphTrackSegment segment : track.segments()) {
            long first = Long.MAX_VALUE;
            long end = Long.MIN_VALUE;
            for (GraphBucket bucket : segment.series().buckets()) {
                first = Math.min(first, bucket.firstTimestampNanos());
                end = Math.max(end, bucket.lastTimestampNanos());
            }
            Long origin = segment.absoluteOriginNanos();
            if (first > end) {
                first = viewportStart;
                end = Math.max(viewportStart, viewportEnd);
                if (track.sharedAbsoluteTimeline()) {
                    origin = 0L;
                }
            }
            inputs.add(new GraphTrackArrangement.Input(first, end, origin));
        }
        GraphTrackArrangement.Mode mode = GraphTrackArrangement.mode(
                track.sharedAbsoluteTimeline(), track.segments().size());
        return GraphTrackArrangement.panes(mode, inputs, LEFT, Math.max(LEFT + 1, width - RIGHT));
    }

    /**
     * Maps each unknown-order slot onto the current layout viewport. Stored timestamps stay on
     * the pane axis. Adjacent slots share an edge; ownership of that edge is half-open.
     */
    private List<GraphTrackArrangement.Pane> layoutPanes(GraphLogicalTrack track, double width) {
        double left = plotLeft(width);
        double right = Math.max(left + 1, plotRight(width));
        return layoutPanes(track, left, right);
    }

    private List<GraphTrackArrangement.Pane> layoutPanes(GraphLogicalTrack track, double left, double right) {
        long viewStart = viewportStart;
        long viewEnd = viewportEnd > viewportStart ? viewportEnd : viewportStart + 1;
        double span = Math.max(1d, (double) (viewEnd - viewStart));
        java.util.ArrayList<GraphTrackArrangement.Pane> panes = new java.util.ArrayList<>();
        for (int i = 0; i < track.segments().size(); i++) {
            GraphTrackSegment segment = track.segments().get(i);
            if (!segment.layoutSlot()) {
                panes.add(new GraphTrackArrangement.Pane(i, left, left, viewStart, viewEnd, 0L));
                continue;
            }
            double x0 = left + (segment.layoutStartNanos() - viewStart) / span * (right - left);
            double x1 = left + (segment.layoutEndNanos() - viewStart) / span * (right - left);
            long storedStart = segment.series().viewportStartTimestampNanos();
            long storedEnd = segment.series().viewportEndTimestampNanos();
            if (storedEnd < storedStart) {
                storedEnd = storedStart;
            }
            panes.add(new GraphTrackArrangement.Pane(i, x0, x1, storedStart, storedEnd, 0L));
        }
        return List.copyOf(panes);
    }

    private int segmentAt(GraphLogicalTrack track, double x, double width) {
        List<GraphTrackArrangement.Pane> panes = panesFor(track, width);
        if (panes.isEmpty()) {
            return -1;
        }
        int nearest = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < panes.size(); i++) {
            GraphTrackArrangement.Pane pane = panes.get(i);
            if (x >= pane.x0() && x <= pane.x1()) {
                return i;
            }
            double mid = (pane.x0() + pane.x1()) / 2.0;
            double distance = Math.abs(x - mid);
            if (distance < best) {
                best = distance;
                nearest = i;
            }
        }
        return nearest;
    }

    private long timestampInSegment(GraphLogicalTrack track, int segment, double x, double width) {
        List<GraphTrackArrangement.Pane> panes = panesFor(track, width);
        if (segment < 0 || segment >= panes.size()) {
            return timestampAt(x);
        }
        GraphTrackArrangement.Pane pane = panes.get(segment);
        double span = Math.max(1e-6, pane.x1() - pane.x0());
        double fraction = Math.max(0, Math.min(1, (x - pane.x0()) / span));
        long axisSpan = pane.axisEnd() - pane.axisStart();
        long display = pane.axisStart() + (long) (axisSpan * fraction);
        try {
            return Math.subtractExact(display, pane.originNanos());
        } catch (ArithmeticException overflow) {
            return display;
        }
    }

    private int seriesIndexOfTrack(int trackIndex) {
        int cursor = 0;
        for (int t = 0; t < trackIndex && t < tracks.size(); t++) {
            cursor += Math.max(1, tracks.get(t).segments().size());
        }
        return cursor;
    }

    private List<P> pointsInPane(List<GraphBucket> buckets, double min, double max, GraphTrackArrangement.Pane pane, double top, double bottom) {
        List<P> pts = new ArrayList<>(buckets.size() * 4);
        long axisSpan = pane.axisEnd() - pane.axisStart();
        double width = Math.max(1e-6, pane.x1() - pane.x0());
        for (GraphBucket bucket : buckets) {
            addInPane(pts, bucket.firstTimestampNanos(), bucket.firstValue(), bucket.firstValidity(), min, max, pane, axisSpan, width, top, bottom);
            addInPane(pts, bucket.minTimestampNanos(), bucket.minValue(), bucket.minValidity(), min, max, pane, axisSpan, width, top, bottom);
            addInPane(pts, bucket.maxTimestampNanos(), bucket.maxValue(), bucket.maxValidity(), min, max, pane, axisSpan, width, top, bottom);
            addInPane(pts, bucket.lastTimestampNanos(), bucket.lastValue(), bucket.lastValidity(), min, max, pane, axisSpan, width, top, bottom);
        }
        pts.sort(Comparator.comparingLong(P::t));
        return pts;
    }

    private void addInPane(List<P> pts, long ts, double v, SignalValidity validity, double min, double max,
                           GraphTrackArrangement.Pane pane, long axisSpan, double width, double top, double bottom) {
        if (validity == null || !validity.usableWith(v)) {
            pts.add(new P(ts, Double.NaN, Double.NaN, true));
            return;
        }
        long display = GraphTrackArrangement.addSaturating(pane.originNanos(), ts);
        double f = axisSpan <= 0 ? 0 : (display - pane.axisStart()) / (double) axisSpan;
        f = Math.max(0, Math.min(1, f));
        double x = pane.x0() + f * width;
        double den = max - min;
        double ratio = Math.abs(den) < 1e-12 ? 0.5 : (v - min) / den;
        pts.add(new P(ts, x, bottom - ratio * (bottom - top)));
    }

    private void drawTrack(GraphicsContext g,GraphRenderSeries s,int seriesIndex,double y,double w,double h,boolean last, SignalCatalogKey legendKey){
        List<GraphBucket>b=s.buckets();
        StackedTrackFrame frame=StackedTrackFrame.compute(y,h,last);
        double top=frame.plotTop(),bottom=frame.plotBottom();
        g.setStroke(GraphChrome.GRID);g.strokeLine(0,y+h-1,w,y+h-1);
        Color seriesColor = colorOf(seriesIndex);
        String title=labelOf(s)+(s.unit().isBlank()?"":" ["+s.unit()+"]");
        g.setStroke(seriesColor); g.setLineWidth(3.0); applySeriesDashes(g, seriesIndex); g.strokeLine(10,y+7,10,y+18); g.setLineDashes(null);
        g.setFill(GraphChrome.TITLE);g.fillText(title,17,y+16);
        // Track header/name is a visibility toggle hit target (same semantics as Overlay legend).
        double titleWidth=Math.min(w-40, 24+title.length()*7.0);
        legendHits.add(new LegendHit(seriesIndex, legendKey, 10, y+2, titleWidth, 18, false));
        if(b.isEmpty()){g.setFill(GraphChrome.AXIS_LABEL);g.fillText(navigatorCount>1?"Not in this file":"No samples in viewport",LEFT,top+18);return;}
        GraphAxisScale.Domain domain=GraphAxisScale.fromBuckets(b);
        if(!domain.hasUsable()){
            g.setFill(GraphChrome.AXIS_LABEL);
            g.fillText("No usable samples in viewport",LEFT,top+18);
            return;
        }
        double minV=domain.dataMin(), maxV=domain.dataMax();
        boolean constant=domain.constant();
        boolean binary=domain.binary();
        boolean enumerated=EnumeratedTrace.isState(b);
        int ticks=axisTickCount(bottom-top,binary,constant);
        percentTicks=false;
        drawGridAndAxes(g,w,top,bottom,domain.axisMin(),domain.axisMax(),last,ticks,binary,constant?minV:null,
                frame.timeLabelBaseline(),frame.captionBaseline(),
                enumerated ? EnumeratedTrace.levels(b) : List.of());
        List<P>pts=points(b,domain.axisMin(),domain.axisMax(),w,top,bottom);
        long gapThreshold=Math.max(1,(viewportEnd-viewportStart)/12);
        // Keep the same stable series colour in stacked and overlay layouts so the
        // selector, waveform and cursor context always describe one visual identity.
        g.setStroke(seriesColor);g.setLineWidth(1.25);applySeriesDashes(g,seriesIndex);
        drawSeries(g,pts,gapThreshold,binary || enumerated);
        g.setFill(GraphChrome.AXIS_LABEL);
        String state=constant?" · constant":"";
        g.fillText("Min "+formatAxis(minV)+"  ·  Max "+formatAxis(maxV)+"  ·  "+String.format("%,d",s.sourceSampleCount())+" samples"+state,LEFT,frame.summaryBaseline());
    }
    private void drawMultiAxisOverlay(GraphicsContext g,double w,double h){
        // Signals with the same engineering unit share one axis/range. This keeps a 5-8 signal
        // diagnostic overlay readable instead of painting one competing axis per signal.
        List<AxisGroup> axes=axisGroups(); int leftAxes=(axes.size()+1)/2; double lane=58.0;
        double plotLeft=plotLeft(w), plotRight=plotRight(w);
        if(plotRight-plotLeft<300){ plotLeft=Math.min(LEFT,w*.22); plotRight=Math.max(w-RIGHT,w*.78); }
        double top=68,bottom=h-BOTTOM-12;
        g.setFill(GraphChrome.AXIS_LABEL);
        g.fillText(overlayValueCaption(), plotLeft, 16);
        if(axes.isEmpty()){
            g.fillText("No usable samples in viewport", plotLeft, top+18);
            return;
        }
        if(showGrid){
            g.setLineWidth(0.65); g.setStroke(GraphChrome.GRID_SOFT);
            for(int i=0;i<=5;i++){double y=bottom-(bottom-top)*i/5.0;g.strokeLine(plotLeft,y,plotRight,y);}
            for(int i=0;i<=8;i++){double x=plotLeft+(plotRight-plotLeft)*i/8.0;g.strokeLine(x,top,x,bottom);}
        }
        g.save();
        g.beginPath();
        g.rect(plotLeft, top, Math.max(1, plotRight-plotLeft), Math.max(1, bottom-top));
        g.clip();
        for(int ti=0;ti<tracks.size();ti++){
            if(trackHidden(ti) || unloadedKeys.contains(tracks.get(ti).logicalKey())) continue;
            GraphLogicalTrack track=tracks.get(ti);
            AxisGroup axis=axisForTrack(track, axes);
            if(axis==null) continue;
            int colorIndex=seriesIndexOfTrack(ti);
            g.setStroke(colorOf(colorIndex));
            g.setFill(colorOf(colorIndex));
            g.setLineWidth(1.55);
            applySeriesDashes(g, colorIndex);
            strokeOverlayTrack(g, track, axis.min(), axis.max(), w, top, bottom, plotLeft, plotRight,
                    trackState(track));
        }
        g.restore();
        for(int ai=0;ai<axes.size();ai++){
            AxisGroup a=axes.get(ai); boolean left=ai<leftAxes; int laneIndex=left?ai:ai-leftAxes;
            double x=left?plotLeft-(leftAxes-laneIndex-1)*lane:plotRight+laneIndex*lane;
            Color c=colorOf(a.firstSeriesIndex()); g.setStroke(c);g.setFill(c);g.setLineWidth(1.15);g.strokeLine(x,top,x,bottom);
            g.setTextAlign(left?javafx.scene.text.TextAlignment.RIGHT:javafx.scene.text.TextAlignment.LEFT);
            g.fillText(a.label(),left?x-7:x+7,top-18);
            List<EnumeratedTrace.Level> stateLevels = purelyEnumerated(a)
                    ? EnumeratedTrace.fit(levelsForAxis(a), bottom - top)
                    : List.of();
            if (!stateLevels.isEmpty()) {
                double span = a.max() - a.min();
                for (EnumeratedTrace.Level level : stateLevels) {
                    double ratio = Math.abs(span) < 1e-12 ? 0.5 : (level.value() - a.min()) / span;
                    double yy = bottom - ratio * (bottom - top);
                    g.fillText(EnumeratedTrace.gutter(level.label()), left ? x - 6 : x + 6, yy + 4);
                    g.strokeLine(left ? x - 4 : x, yy, left ? x : x + 4, yy);
                    paintedStateLabels.add(level.label());
                }
            } else {
                for (int tick = 0; tick <= 5; tick++) {
                    double f = tick / 5.0;
                    double yy = bottom - f * (bottom - top);
                    double v = a.min() + f * (a.max() - a.min());
                    g.fillText(formatAxis(v), left ? x - 6 : x + 6, yy + 4);
                    g.strokeLine(left ? x - 4 : x, yy, left ? x : x + 4, yy);
                }
            }
        }
        g.setTextAlign(javafx.scene.text.TextAlignment.LEFT);g.setFill(GraphChrome.AXIS_SECONDARY);
        boolean slotAxis=unknownOrderOverview && viewportEnd-viewportStart>UnknownOrderLayout.EQUAL_SLOT_SPAN;
        for(int i=0;i<=6;i++){
            double f=i/6.0,x=plotLeft+(plotRight-plotLeft)*f;
            long ts=viewportStart+(long)((viewportEnd-viewportStart)*f);
            String label=slotAxis
                    ? "Slot "+(ts/UnknownOrderLayout.EQUAL_SLOT_SPAN+1)
                    : formatTime(unknownOrderOverview ? storedAtLayout(ts) : ts);
            double tx=Math.max(plotLeft,Math.min(plotRight-48,x-22));
            g.fillText(label,tx,bottom+20);
        }
        g.setFill(GraphChrome.AXIS_LABEL);
        g.fillText(timeAxisCaption(), plotLeft, bottom+36);
        if(sharedLegendActive())drawInteractiveLegend(g,plotLeft,26,plotRight,24);
    }
    private List<AxisGroup> axisGroups(){
        java.util.LinkedHashMap<String,AxisAccumulator> map=new java.util.LinkedHashMap<>();
        for(int i=0;i<series.size();i++){
            if(hiddenSeries.contains(i))continue;
            GraphRenderSeries sr=series.get(i); final int seriesIndex=i; String unit=sr.unit()==null||sr.unit().isBlank()?"signal:"+sr.key().signalName():sr.unit().trim();
            AxisAccumulator a=map.computeIfAbsent(unit,k->new AxisAccumulator(unit,seriesIndex,sr.unit()==null||sr.unit().isBlank()?shortName(sr.key().signalName(),10):sr.unit()));
            for(GraphBucket b:sr.buckets()){if(b.minUsable())a.accept(b.minValue());if(b.maxUsable())a.accept(b.maxValue());}
        }
        java.util.ArrayList<AxisGroup> out=new java.util.ArrayList<>();
        for(AxisAccumulator a:map.values()){
            GraphAxisScale.Domain domain=GraphAxisScale.fromRange(a.min, a.max);
            if(!domain.hasUsable()) continue;
            out.add(new AxisGroup(a.key, a.label, domain.axisMin(), domain.axisMax(), a.first));
        }
        return out;
    }
    private static AxisGroup axisFor(GraphRenderSeries sr,List<AxisGroup> axes){String unit=unitKey(sr);for(AxisGroup a:axes)if(a.key().equals(unit))return a;return axes.get(0);}
    private static String unitKey(GraphRenderSeries sr){
        return sr.unit()==null||sr.unit().isBlank()?"signal:"+sr.key().signalName():sr.unit().trim();
    }
    private boolean trackState(GraphLogicalTrack track){
        for(GraphTrackSegment segment:track.segments()){
            if(EnumeratedTrace.isState(segment.series().buckets())) return true;
        }
        return false;
    }
    private boolean purelyEnumerated(AxisGroup group){
        boolean any=false;
        for(int i=0;i<series.size();i++){
            if(hiddenSeries.contains(i)) continue;
            GraphRenderSeries sr=series.get(i);
            if(!group.key().equals(unitKey(sr))) continue;
            if(!EnumeratedTrace.isState(sr.buckets())) return false;
            any=true;
        }
        return any;
    }
    private List<EnumeratedTrace.Level> levelsForAxis(AxisGroup group){
        java.util.ArrayList<GraphBucket> buckets=new java.util.ArrayList<>();
        for(int i=0;i<series.size();i++){
            if(hiddenSeries.contains(i)) continue;
            GraphRenderSeries sr=series.get(i);
            if(group.key().equals(unitKey(sr))) buckets.addAll(sr.buckets());
        }
        return EnumeratedTrace.levels(buckets);
    }
    private static final class AxisAccumulator{final String key;final int first;final String label;double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;AxisAccumulator(String key,int first,String label){this.key=key;this.first=first;this.label=label;}void accept(double v){if(Double.isFinite(v)){min=Math.min(min,v);max=Math.max(max,v);}}}
    private record AxisGroup(String key,String label,double min,double max,int firstSeriesIndex){}

    private List<P> pointsInPlot(List<GraphBucket>b,double min,double max,double plotLeft,double plotRight,double top,double bottom){
        List<P>pts=new ArrayList<>(b.size()*4);
        for(GraphBucket q:b){
            addInPlot(pts,q.firstTimestampNanos(),q.firstValue(),q.firstValidity(),min,max,plotLeft,plotRight,top,bottom);
            addInPlot(pts,q.minTimestampNanos(),q.minValue(),q.minValidity(),min,max,plotLeft,plotRight,top,bottom);
            addInPlot(pts,q.maxTimestampNanos(),q.maxValue(),q.maxValidity(),min,max,plotLeft,plotRight,top,bottom);
            addInPlot(pts,q.lastTimestampNanos(),q.lastValue(),q.lastValidity(),min,max,plotLeft,plotRight,top,bottom);
        }
        pts.sort(Comparator.comparingLong(P::t)); return pts;
    }
    private void addInPlot(List<P>pts,long ts,double v,SignalValidity validity,double min,double max,double plotLeft,double plotRight,double top,double bottom){
        if(validity==null||!validity.usableWith(v)){pts.add(new P(ts,Double.NaN,Double.NaN,true));return;}
        double f=viewportEnd<=viewportStart?0:(ts-viewportStart)/(double)(viewportEnd-viewportStart);
        double x=plotLeft+Math.max(0,Math.min(1,f))*(plotRight-plotLeft); double den=max-min; double ratio=Math.abs(den)<1e-12?.5:(v-min)/den;
        pts.add(new P(ts,x,bottom-ratio*(bottom-top)));
    }

    private void drawOverlay(GraphicsContext g,double w,double h){
        double top=TOP+8,bottom=h-BOTTOM;
        percentTicks=true;
        drawGridAndAxes(g,w,top,bottom,0,100,true,5,false,null,bottom+17,bottom+32,List.of());
        percentTicks=false;
        g.setFill(GraphChrome.AXIS_LABEL);g.fillText("Normalized overlay — axis is % of each signal's own range, not an engineering unit. Tooltips show physical values.",LEFT,18);
        if(sharedLegendActive())drawInteractiveLegend(g,LEFT,34,w-RIGHT,20);
        double left=plotLeft(w), right=plotRight(w);
        g.save();
        g.beginPath();
        g.rect(left, top, Math.max(1, right-left), Math.max(1, bottom-top));
        g.clip();
        for(int ti=0;ti<tracks.size();ti++){
            if(trackHidden(ti) || unloadedKeys.contains(tracks.get(ti).logicalKey())) continue;
            GraphLogicalTrack track=tracks.get(ti);
            java.util.ArrayList<GraphBucket> buckets=new java.util.ArrayList<>();
            for(GraphTrackSegment segment:track.segments()) buckets.addAll(segment.series().buckets());
            if(buckets.isEmpty()) continue;
            double min=usableMin(buckets), max=usableMax(buckets, min);
            int colorIndex=seriesIndexOfTrack(ti);
            g.setStroke(colorOf(colorIndex));
            g.setFill(colorOf(colorIndex));
            applySeriesDashes(g, colorIndex);
            strokeOverlayTrack(g, track, min, max, w, top, bottom, left, right, trackState(track));
        }
        g.restore();
    }

    /**
     * Overlay shares the plot. Unknown-order X is the slot pane, the same mapping Stacked uses.
     * Stored timestamps stay inside that pane and are not joined across a recording boundary.
     */
    private void strokeOverlayTrack(GraphicsContext g, GraphLogicalTrack track, double min, double max,
                                    double width, double top, double bottom, double plotLeft, double plotRight,
                                    boolean binary) {
        if (usesLayout(track)) {
            List<GraphTrackArrangement.Pane> panes = layoutPanes(track, plotLeft, plotRight);
            for (int i = 0; i < track.segments().size() && i < panes.size(); i++) {
                GraphTrackSegment segment = track.segments().get(i);
                GraphTrackArrangement.Pane pane = panes.get(i);
                if (segment.series().buckets().isEmpty() || missesPlot(pane.x0(), pane.x1(), Math.max(width, getWidth()))) {
                    continue;
                }
                List<P> pts = pointsInPane(segment.series().buckets(), min, max, pane, top, bottom);
                applySeriesDashes(g, seriesIndexOfTrack(indexOf(track)));
                drawSeries(g, pts, Long.MAX_VALUE, binary);
            }
            return;
        }
        long gap = Math.max(1L, (viewportEnd - viewportStart) / 12);
        for (GraphTrackSegment segment : track.segments()) {
            if (segment.series().buckets().isEmpty()) {
                continue;
            }
            List<P> pts = plotPoints(segment.series(), min, max, width, top, bottom, plotLeft, plotRight);
            drawSeries(g, pts, gap, binary);
        }
    }

    private int indexOf(GraphLogicalTrack track) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i) == track) {
                return i;
            }
        }
        return 0;
    }

    private GraphLogicalTrack firstLayoutTrack() {
        for (GraphLogicalTrack track : tracks) {
            if (usesLayout(track)) {
                return track;
            }
        }
        return null;
    }

    private SharedTimelineHit.Hit overlayLayoutHit(double x, double y, double width) {
        GraphLogicalTrack layoutTrack = firstLayoutTrack();
        if (layoutTrack == null) {
            return null;
        }
        int segment = layoutOwner(layoutTrack, x, width);
        if (segment < 0) {
            return null;
        }
        long stored = timestampInSegment(layoutTrack, segment, x, width);
        int seriesIndex = nearestLayoutSeries(segment, stored, y);
        int trackIndex = seriesIndex < 0 ? 0 : trackIndexForSeries(seriesIndex);
        if (trackIndex < 0 || trackIndex >= tracks.size()) {
            return null;
        }
        GraphLogicalTrack track = tracks.get(trackIndex);
        if (segment >= track.segments().size()) {
            return null;
        }
        GraphTrackSegment chosen = track.segments().get(segment);
        String id = chosen.series().key() == null ? "" : chosen.series().key().sourceFile();
        if (id == null || id.isBlank()) {
            return null;
        }
        long start = segmentStart(chosen);
        return new SharedTimelineHit.Hit(
                id, track.logicalKey().signalName(), segment, stored, null, start, segmentEnd(chosen, start));
    }

    private int nearestLayoutSeries(int segment, long stored, double y) {
        double width = Math.max(1, getWidth());
        double top = layoutMode == LayoutMode.OVERLAY_MULTI_AXIS ? 68 : TOP + 8;
        double bottom = Math.max(top + 1, getHeight() - BOTTOM - 12);
        List<AxisGroup> axes = layoutMode == LayoutMode.OVERLAY_MULTI_AXIS ? axisGroups() : List.of();
        int best = -1;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (int ti = 0; ti < tracks.size(); ti++) {
            GraphLogicalTrack track = tracks.get(ti);
            if (trackHidden(ti) || segment < 0 || segment >= track.segments().size()) {
                continue;
            }
            GraphTrackSegment chosen = track.segments().get(segment);
            double value = valueNear(chosen, stored);
            if (!Double.isFinite(value)) {
                continue;
            }
            double min;
            double max;
            if (!axes.isEmpty()) {
                AxisGroup axis = axisForTrack(track, axes);
                if (axis == null) {
                    continue;
                }
                min = axis.min();
                max = axis.max();
            } else {
                java.util.ArrayList<GraphBucket> buckets = new java.util.ArrayList<>();
                for (GraphTrackSegment part : track.segments()) {
                    buckets.addAll(part.series().buckets());
                }
                min = usableMin(buckets);
                max = usableMax(buckets, min);
            }
            double den = Math.max(1e-12, max - min);
            double py = bottom - ((value - min) / den) * (bottom - top);
            double distance = Math.abs(py - y);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = seriesIndexOfSegment(ti, segment);
            }
        }
        return best;
    }

    private static AxisGroup axisForTrack(GraphLogicalTrack track, List<AxisGroup> axes) {
        String unit = track.unit() == null || track.unit().isBlank()
                ? "signal:" + track.logicalKey().signalName()
                : track.unit().trim();
        for (AxisGroup axis : axes) {
            if (axis.key().equals(unit)) {
                return axis;
            }
        }
        return axes.isEmpty() ? null : axes.get(0);
    }

    public List<Double> overlayLayoutXsForTest(int trackIndex) {
        if (trackIndex < 0 || trackIndex >= tracks.size() || !usesLayout(tracks.get(trackIndex))) {
            return List.of();
        }
        GraphLogicalTrack track = tracks.get(trackIndex);
        double width = Math.max(1, getWidth());
        List<GraphTrackArrangement.Pane> panes = layoutPanes(track, width);
        java.util.ArrayList<Double> xs = new java.util.ArrayList<>();
        for (int i = 0; i < track.segments().size() && i < panes.size(); i++) {
            GraphTrackSegment segment = track.segments().get(i);
            if (segment.series().buckets().isEmpty()) {
                xs.add(Double.NaN);
                continue;
            }
            List<P> pts = pointsInPane(segment.series().buckets(), 0, 1, panes.get(i), 0, 1);
            double x = Double.NaN;
            for (P point : pts) {
                if (!point.breakBefore() && Double.isFinite(point.x())) {
                    x = point.x();
                    break;
                }
            }
            xs.add(x);
        }
        return List.copyOf(xs);
    }
    /** The segment does not cross the plot, so stroking it cannot change the visible canvas. */
    private static boolean missesPlot(double x0, double x1, double width) {
        return Math.max(x0, x1) < -1d || Math.min(x0, x1) > width + 1d;
    }

    private void drawSeries(GraphicsContext g,List<P> pts,long gap,boolean binary){
        // One path per series. A separate strokeLine per sample makes the software
        // rasterizer replay tens of thousands of native strokes on the next pulse.
        List<P> drawn=envelopeColumns(pts, gap);
        double width=canvas.getWidth();
        g.beginPath();
        boolean pen=false;
        P prev=null;
        for(P q:drawn){
            if(q.breakBefore()){pen=false;prev=null;continue;}
            if(prev!=null&&q.t-prev.t<=gap){
                if(missesPlot(prev.x, q.x, width)){pen=false;prev=q;continue;}
                if(!pen){g.moveTo(prev.x, prev.y);pen=true;}
                if(binary){g.lineTo(q.x, prev.y);g.lineTo(q.x, q.y);}
                else if(smoothLines){double mx=(prev.x+q.x)/2.0;g.quadraticCurveTo(mx, prev.y, q.x, q.y);}
                else g.lineTo(q.x, q.y);
            }else if(prev!=null){
                pen=false;
            }
            prev=q;
        }
        g.stroke();
        g.setLineDashes(null);
    }

    /**
     * Keeps the vertical envelope of samples that land in the same device pixel.
     * Breaks and gaps stay breaks so ineligible samples are not bridged.
     */
    private static List<P> envelopeColumns(List<P> pts, long gap){
        if(pts.size()<3) return pts;
        java.util.ArrayList<P> out=new java.util.ArrayList<>(Math.min(pts.size(), 4096));
        P prev=null;
        P colMin=null;
        P colMax=null;
        double colX=Double.NaN;
        for(P q:pts){
            boolean split=q.breakBefore() || (prev!=null && q.t-prev.t>gap)
                    || !Double.isFinite(q.x()) || !Double.isFinite(colX) || Math.abs(q.x()-colX)>=1.0;
            if(split){
                appendColumn(out, colMin, colMax);
                colMin=null;
                colMax=null;
                colX=Double.NaN;
            }
            if(q.breakBefore() || !Double.isFinite(q.x()) || !Double.isFinite(q.y())){
                out.add(q);
                prev=q.breakBefore()?null:q;
                continue;
            }
            if(colMin==null){
                colMin=q;
                colMax=q;
                colX=q.x();
            }else{
                if(q.y()<colMin.y()) colMin=q;
                if(q.y()>colMax.y()) colMax=q;
            }
            prev=q;
        }
        appendColumn(out, colMin, colMax);
        return out;
    }

    private static void appendColumn(List<P> out, P min, P max){
        if(min==null) return;
        if(max==null || max==min || Math.abs(min.y()-max.y())<0.5){
            out.add(min);
            return;
        }
        if(min.t()<=max.t()){out.add(min);out.add(max);}
        else{out.add(max);out.add(min);}
    }

    private void drawInteractiveLegend(GraphicsContext g,double startX,double startY,double maxX,int maxChars){
        double x=startX,y=startY;
        for(int i=0;i<tracks.size();i++){
            GraphLogicalTrack track=tracks.get(i);
            String label=shortName(track.logicalKey().signalName(),maxChars);
            double width=Math.min(215,34+label.length()*6.5);
            if(x+width>maxX&&x>startX){x=startX;y+=17;}
            boolean hidden=trackHidden(i);
            int seriesIndex=seriesIndexOfTrack(i);
            Color c=hidden?HIDDEN_LEGEND_GRAY:colorOf(seriesIndex);
            g.setFill(c);g.fillOval(x,y-8,8,8);g.fillText(label,x+12,y);
            legendHits.add(new LegendHit(seriesIndex, track.logicalKey(), x, y-14, width, 18, true)); x+=width;
        }
    }

    /** Shared top legend is an Overlay aid. Stacked tracks already name themselves. */
    private boolean sharedLegendActive() {
        return showLegend && layoutMode != LayoutMode.STACKED;
    }

    public boolean sharedLegendVisibleForTest() {
        return sharedLegendActive() && !series.isEmpty();
    }

    /** Stacked does not reserve a legend row, so the waveform uses that space. */
    public boolean sharedLegendManagedForTest() {
        return sharedLegendVisibleForTest();
    }

    public int sharedLegendHitCountForTest() {
        int count = 0;
        for (LegendHit hit : legendHits) {
            if (hit.shared()) {
                count++;
            }
        }
        return count;
    }

    public int trackHeaderHitCountForTest() {
        int count = 0;
        for (LegendHit hit : legendHits) {
            if (!hit.shared()) {
                count++;
            }
        }
        return count;
    }

    public int seriesAssignmentCountForTest() {
        return seriesAssignmentCount;
    }

    public Color seriesColorForTest(int seriesIndex) {
        return colorOf(seriesIndex);
    }

    public String seriesLabelForTest(int seriesIndex) {
        return seriesIndex < 0 || seriesIndex >= series.size() ? "" : labelOf(series.get(seriesIndex));
    }

    public double firstTrackHeaderYForTest() {
        double y = Double.MAX_VALUE;
        for (LegendHit hit : legendHits) {
            if (!hit.shared()) {
                y = Math.min(y, hit.y());
            }
        }
        return y;
    }

    private record LegendHit(int seriesIndex, SignalCatalogKey key, double x, double y, double w, double h, boolean shared){boolean contains(double px,double py){return px>=x&&px<=x+w&&py>=y&&py<=y+h;}}

    private int nearestOverlaySeries(long ts,double y){
        if(series.size()<=1)return 0;
        boolean physical=layoutMode==LayoutMode.OVERLAY_MULTI_AXIS;
        List<AxisGroup> axes=physical?axisGroups():List.of();
        double top=physical?68:TOP+8,bottom=physical?getHeight()-BOTTOM-12:getHeight()-BOTTOM;int bestI=0;double best=Double.MAX_VALUE;
        for(int i=0;i<series.size();i++){
            if(hiddenSeries.contains(i))continue;
            GraphRenderSeries s=series.get(i);
            GraphBucket q=nearestBucket(s.buckets(),storedLookup(i, ts));
            if(q==null||!q.lastUsable())continue;
            double min,max;
            if(physical && !axes.isEmpty()){
                AxisGroup axis=axisFor(s,axes);
                min=axis.min(); max=axis.max();
            }else{
                min=usableMin(s.buckets()); max=usableMax(s.buckets(),min);
            }
            double py=bottom-((q.lastValue()-min)/Math.max(1e-12,max-min))*(bottom-top);
            double d=Math.abs(py-y);
            if(d<best){best=d;bestI=i;}
        }
        return bestI;
    }
    private static GraphBucket nearestBucket(List<GraphBucket>b,long ts){GraphBucket n=null;long d=Long.MAX_VALUE;for(GraphBucket q:b){long x=Math.abs(q.lastTimestampNanos()-ts);if(x<d){d=x;n=q;}}return n;}
    private List<P> points(List<GraphBucket>b,double min,double max,double w,double top,double bottom){List<P>pts=new ArrayList<>(b.size()*4);for(GraphBucket q:b){add(pts,q.firstTimestampNanos(),q.firstValue(),q.firstValidity(),min,max,w,top,bottom);add(pts,q.minTimestampNanos(),q.minValue(),q.minValidity(),min,max,w,top,bottom);add(pts,q.maxTimestampNanos(),q.maxValue(),q.maxValidity(),min,max,w,top,bottom);add(pts,q.lastTimestampNanos(),q.lastValue(),q.lastValidity(),min,max,w,top,bottom);}pts.sort(Comparator.comparingLong(P::t));return pts;}

    /** Overlay samples on a shared UTC axis use the recording origin. Other plots keep the viewport axis. */
    private List<P> plotPoints(GraphRenderSeries series, double min, double max, double width, double top, double bottom, double left, double right) {
        GraphTrackSegment segment = segmentOf(series);
        if (segment == null || segment.absoluteOriginNanos() == null || !hasSharedTimeline()) {
            return points(series.buckets(), min, max, width, top, bottom);
        }
        GraphTrackArrangement.Pane axis = sharedAxis(width);
        if (axis == null) {
            return points(series.buckets(), min, max, width, top, bottom);
        }
        GraphTrackArrangement.Pane placed = new GraphTrackArrangement.Pane(
                0, left, right, axis.axisStart(), axis.axisEnd(), segment.absoluteOriginNanos());
        return pointsInPane(series.buckets(), min, max, placed, top, bottom);
    }

    private GraphTrackSegment segmentOf(GraphRenderSeries series) {
        if (series == null) {
            return null;
        }
        for (GraphLogicalTrack track : tracks) {
            for (GraphTrackSegment segment : track.segments()) {
                if (segment.series() == series && track.sharedAbsoluteTimeline()) {
                    return segment;
                }
            }
        }
        return null;
    }

    private long storedLookup(int seriesIndex, long displayNanos) {
        if (seriesIndex < 0 || seriesIndex >= series.size()) {
            return displayNanos;
        }
        GraphTrackSegment segment = segmentOf(series.get(seriesIndex));
        if (segment == null || segment.absoluteOriginNanos() == null) {
            return displayNanos;
        }
        try {
            return Math.subtractExact(displayNanos, segment.absoluteOriginNanos());
        } catch (ArithmeticException overflow) {
            return displayNanos;
        }
    }

    private static long displayOf(GraphLogicalTrack track, int segment, long storedNanos) {
        if (track == null || !track.sharedAbsoluteTimeline() || segment < 0 || segment >= track.segments().size()) {
            return storedNanos;
        }
        Long origin = track.segments().get(segment).absoluteOriginNanos();
        if (origin == null) {
            return storedNanos;
        }
        return GraphTrackArrangement.addSaturating(origin, storedNanos);
    }
    private static int axisTickCount(double h,boolean binary,boolean constant){if(binary)return 1;if(constant)return 0;if(h<115)return 2;if(h<150)return 3;return 4;}
    private void drawGridAndAxes(GraphicsContext g,double w,double top,double bottom,double min,double max,boolean last,int ticks,boolean binary,Double constant,double timeBaseline,double captionBaseline,List<EnumeratedTrace.Level> states){
        g.setLineWidth(0.6);
        List<EnumeratedTrace.Level> shown = percentTicks || states == null
                ? List.of()
                : EnumeratedTrace.fit(states, bottom - top);
        if (!shown.isEmpty()) {
            double span = max - min;
            for (EnumeratedTrace.Level level : shown) {
                double ratio = Math.abs(span) < 1e-12 ? 0.5 : (level.value() - min) / span;
                double y = bottom - ratio * (bottom - top);
                if (y < top - 4 || y > bottom + 4) {
                    continue;
                }
                if (showGrid) {
                    g.setStroke(GraphChrome.GRID);
                    g.strokeLine(LEFT, y, w - RIGHT, y);
                }
                g.setFill(GraphChrome.AXIS_LABEL);
                g.fillText(EnumeratedTrace.gutter(level.label()), 6, y + 4);
                paintedStateLabels.add(level.label());
            }
        } else if(constant!=null){double y=(top+bottom)/2;if(showGrid){g.setStroke(GraphChrome.GRID);g.strokeLine(LEFT,y,w-RIGHT,y);}g.setFill(GraphChrome.AXIS_LABEL);g.fillText(formatAxis(constant),6,y+4);}
        else for(int i=0;i<=ticks;i++){double f=ticks==0?0.5:i/(double)ticks,y=bottom-f*(bottom-top),v=min+f*(max-min);if(showGrid){g.setStroke(GraphChrome.GRID);g.strokeLine(LEFT,y,w-RIGHT,y);}g.setFill(GraphChrome.AXIS_LABEL);g.fillText(binary?String.format("%.0f",v):percentTicks?GraphAxisScale.formatNormalizedPercent(v):formatAxis(v),6,y+4);}
        if(last){
            double labelReserve=StackedTrackChrome.TIME_LABEL_WIDTH;
            boolean slotAxis=unknownOrderOverview && viewportEnd-viewportStart>UnknownOrderLayout.EQUAL_SLOT_SPAN;
            for(int i=0;i<=5;i++){
                double f=i/5.0,x=LEFT+f*Math.max(1,w-LEFT-RIGHT);
                long ts=viewportStart+(long)((viewportEnd-viewportStart)*f);
                g.setStroke(GraphChrome.GRID_TIME);g.strokeLine(x,top,x,bottom);
                g.setFill(GraphChrome.AXIS_LABEL);
                double labelX=Math.max(LEFT, Math.min(w-RIGHT-labelReserve, x-20));
                String tick=slotAxis ? "Slot "+(ts/UnknownOrderLayout.EQUAL_SLOT_SPAN+1)
                        : formatTime(unknownOrderOverview ? storedAtLayout(ts) : ts);
                g.fillText(tick,labelX,timeBaseline);
            }
            g.setFill(GraphChrome.AXIS_LABEL);
            g.fillText(timeAxisCaption(), LEFT, captionBaseline);
        }
    }
    private String overlayValueCaption() {
        if (navigatorCount > 1) {
            return "Physical values · time within this file";
        }
        return measurementRelativeTime
                ? "Physical values · each file keeps its own clock"
                : "Physical values";
    }

    private String timeAxisCaption(){
        if(unknownOrderOverview){
            if(viewportEnd-viewportStart>UnknownOrderLayout.EQUAL_SLOT_SPAN){
                return "Slot position is not elapsed time";
            }
            return "Stored time in this recording (s)";
        }
        if (navigatorCount > 1) {
            return "Time within this file (s)";
        }
        return measurementRelativeTime ? "Time within each measurement (s)" : "Time (s)";
    }

    /** Layout position to the owning recording's stored clock. Layout time itself is not returned. */
    private long storedAtLayout(long layoutNanos){
        for(GraphLogicalTrack track:tracks){
            for(GraphTrackSegment segment:track.segments()){
                if(!segment.layoutSlot() || layoutNanos<segment.layoutStartNanos()
                        || layoutNanos>=segment.layoutEndNanos()) continue;
                long layoutSpan=segment.layoutEndNanos()-segment.layoutStartNanos();
                long storedStart=segment.series().viewportStartTimestampNanos();
                long storedSpan=segment.series().viewportEndTimestampNanos()-storedStart;
                if(layoutSpan<=0 || storedSpan<=0) return storedStart;
                double fraction=(layoutNanos-segment.layoutStartNanos())/(double)layoutSpan;
                return storedStart+(long)(storedSpan*fraction);
            }
        }
        return layoutNanos;
    }

    private boolean inFileNavigator(double y) {
        if (navigatorCount <= 1) {
            return false;
        }
        double top = canvas.getLayoutY() + Math.max(0, canvas.getHeight() - NAVIGATOR_HEIGHT);
        return y >= top && y <= top + NAVIGATOR_HEIGHT;
    }

    private boolean selectFileNavigator(double x, double y) {
        if (!inFileNavigator(y)) {
            return false;
        }
        double left = LEFT;
        double right = Math.max(left + 1, getWidth() - RIGHT);
        int index = (int) ((x - left) / (right - left) * navigatorCount);
        index = Math.max(0, Math.min(navigatorCount - 1, index));
        fileNavigatorHandler.accept(index);
        return true;
    }

    private void drawFileNavigator(GraphicsContext g, double w) {
        if (navigatorCount <= 1) {
            return;
        }
        double top = paintOriginY + Math.max(0, canvas.getHeight() - NAVIGATOR_HEIGHT);
        double left = LEFT;
        double right = Math.max(left + 1, w - RIGHT);
        double cell = (right - left) / navigatorCount;
        g.setFill(GraphChrome.NAVIGATOR_FILL);
        g.fillRect(left, top, right - left, NAVIGATOR_HEIGHT - 2);
        for (int i = 0; i < navigatorCount; i++) {
            double x = left + i * cell;
            if (i == navigatorIndex) {
                g.setFill(colorOf(0));
                g.fillRect(x, top, Math.max(1, cell - 1), NAVIGATOR_HEIGHT - 2);
            } else {
                g.setStroke(GraphChrome.NAVIGATOR_STROKE);
                g.setLineWidth(0.6);
                g.strokeRect(x, top, Math.max(1, cell - 1), NAVIGATOR_HEIGHT - 2);
            }
        }
    }
    private static String shortName(String s,int n){return s.length()<=n?s:s.substring(0,Math.max(1,n-1))+"…";}
    private static String formatAxis(double v){return GraphAxisScale.formatTick(v);}
    private static String formatTime(long nanos){
        long ms=Math.round(nanos/1_000_000.0), sec=ms/1000;
        if(sec>=3600)return String.format("%02d:%02d:%02d",sec/3600,(sec/60)%60,sec%60);
        if(sec>=60)return String.format("%02d:%02d",sec/60,sec%60);
        return String.format("%.2fs",nanos/1e9);
    }
    private void add(List<P> pts,long ts,double v,SignalValidity validity,double min,double max,double w,double top,double bottom){if(validity==null||!validity.usableWith(v)){pts.add(new P(ts,Double.NaN,Double.NaN,true));return;}pts.add(p(ts,v,min,max,w,top,bottom));}
    private static double usableMin(List<GraphBucket> buckets){double min=Double.POSITIVE_INFINITY;for(GraphBucket b:buckets){if(b.minUsable())min=Math.min(min,b.minValue());}return Double.isFinite(min)?min:0;}
    private static double usableMax(List<GraphBucket> buckets,double min){double max=Double.NEGATIVE_INFINITY;for(GraphBucket b:buckets){if(b.maxUsable())max=Math.max(max,b.maxValue());}return Double.isFinite(max)?max:min+1;}
    private static String formatHoverValue(GraphBucket q,String unit){return com.stellar.platform.desktop.presentation.SampleDisplay.hover(q.lastValue(),q.lastLabel(),unit,q.lastValidity());}
    private P p(long ts,double v,double minV,double maxV,double w,double top,double bottom){double x=xFor(ts,w);double den=maxV-minV;double ratio=Math.abs(den)<1e-12?0.5:(v-minV)/den;double yy=bottom-ratio*Math.max(1,bottom-top);return new P(ts,x,yy,false);} private record P(long t,double x,double y,boolean breakBefore){private P(long t,double x,double y){this(t,x,y,false);}}
}
