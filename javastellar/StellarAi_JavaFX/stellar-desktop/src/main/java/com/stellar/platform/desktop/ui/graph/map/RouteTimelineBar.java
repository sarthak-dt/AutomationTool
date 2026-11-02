package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.desktop.ui.canvas.CanvasRenderSupport;
import com.stellar.platform.desktop.ui.canvas.CanvasRenderSupport.PaintGate;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.function.LongConsumer;

/**
 * Lightweight route timeline drawn on a single canvas.
 *
 * <p>Intentionally not a second waveform renderer: it paints a time axis, the selected instant or
 * the A/B band, and nothing else. Dragging a handle reports a timestamp; the map and every linked
 * analysis view react through the shared time selection.
 */
public final class RouteTimelineBar extends Region {

    private static final double AXIS_TOP = 34;
    private static final double TRACK_HEIGHT = 6;
    private static final double HANDLE_GRAB_PIXELS = 10;
    private static final double TARGET_TICK_SPACING = 110;

    private static final Color TRACK = MapPalette.TRACK;
    private static final Color BAND = MapPalette.ROUTE_ACTIVE;
    private static final Color HANDLE = MapPalette.ROUTE_ACTIVE;
    private static final Color POINT = MapPalette.SELECTED_FILL;
    private static final Color TICK_TEXT = MapPalette.TICK_TEXT;
    private static final Color TICK_LINE = MapPalette.TICK_LINE;
    private static final Font TICK_FONT = Font.font(com.stellar.platform.desktop.ui.theme.StellarTheme.FONT_UI_FAMILY, 10);
    private static final Font PILL_FONT = Font.font(com.stellar.platform.desktop.ui.theme.StellarTheme.FONT_UI_FAMILY, FontWeight.BOLD, 10);

    private final Canvas canvas = new Canvas();
    private final PaintGate paintGate = new PaintGate(canvas);

    private long rangeStartNanos;
    private long rangeEndNanos;
    private MapSelectionMode mode = MapSelectionMode.ALL_ROUTE;
    private Long pointNanos;
    private Long aNanos;
    private Long bNanos;

    private LongConsumer pointDraggedHandler = t -> { };
    private RangeHandler rangeDraggedHandler = (a, b) -> { };

    private Handle activeHandle = Handle.NONE;

    /** Which timeline handle a drag is currently moving. */
    private enum Handle { NONE, POINT, A, B }

    /** Reports a dragged A→B interval. */
    @FunctionalInterface
    public interface RangeHandler {
        void accept(long startNanos, long endNanos);
    }

    public RouteTimelineBar() {
        getStyleClass().add("map-route-timeline");
        setMinHeight(64);
        setPrefHeight(64);
        setMaxHeight(64);
        getChildren().add(canvas);

        canvas.setOnMousePressed(this::onPressed);
        canvas.setOnMouseDragged(this::onDragged);
        canvas.setOnMouseReleased(e -> activeHandle = Handle.NONE);
        sceneProperty().addListener((obs, was, now) -> {
            if (now != null) {
                requestRedraw();
            }
        });
    }

    public void suspendPainting() {
        paintGate.suspend();
    }

    public void resumePainting() {
        paintGate.resume(this::redraw);
    }

    public void setPointDraggedHandler(LongConsumer handler) {
        this.pointDraggedHandler = handler == null ? t -> { } : handler;
    }

    public void setRangeDraggedHandler(RangeHandler handler) {
        this.rangeDraggedHandler = handler == null ? (a, b) -> { } : handler;
    }

    public void setRange(long startNanos, long endNanos) {
        this.rangeStartNanos = startNanos;
        this.rangeEndNanos = Math.max(startNanos + 1, endNanos);
        requestRedraw();
    }

    public void setMode(MapSelectionMode mode) {
        this.mode = mode == null ? MapSelectionMode.ALL_ROUTE : mode;
        requestRedraw();
    }

    public void setPoint(Long timestampNanos) {
        this.pointNanos = timestampNanos;
        requestRedraw();
    }

    public void setRangeSelection(Long startNanos, Long endNanos) {
        this.aNanos = startNanos;
        this.bNanos = endNanos;
        requestRedraw();
    }

    public void clearSelection() {
        pointNanos = null;
        aNanos = null;
        bNanos = null;
        requestRedraw();
    }

    public void dispose() {
        paintGate.dispose();
        canvas.setOnMousePressed(null);
        canvas.setOnMouseDragged(null);
        canvas.setOnMouseReleased(null);
        pointDraggedHandler = t -> { };
        rangeDraggedHandler = (a, b) -> { };
    }

    @Override
    protected void layoutChildren() {
        Insets insets = getInsets();
        double w = finiteLayout(getWidth() - insets.getLeft() - insets.getRight());
        double h = finiteLayout(getHeight() - insets.getTop() - insets.getBottom());
        boolean geometryChanged = CanvasRenderSupport.place(canvas, insets.getLeft(), insets.getTop(), w, h);
        if (geometryChanged || paintGate.isDirty()) {
            redraw();
        }
    }

    private static double finiteLayout(double value) {
        if (!Double.isFinite(value)) {
            return 0;
        }
        return Math.max(1, value);
    }

    private void requestRedraw() {
        paintGate.request(this::redraw);
    }

    private void redraw() {
        if (!paintGate.begin()) {
            return;
        }
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);

        drawTicks(gc, w, h);

        double trackY = AXIS_TOP;
        gc.setFill(TRACK);
        gc.fillRoundRect(0, trackY, w, TRACK_HEIGHT, 3, 3);

        if (mode == MapSelectionMode.A_B_RANGE && aNanos != null && bNanos != null) {
            double xa = x(Math.min(aNanos, bNanos), w);
            double xb = x(Math.max(aNanos, bNanos), w);
            gc.setFill(BAND);
            gc.fillRoundRect(xa, trackY, Math.max(2, xb - xa), TRACK_HEIGHT, 3, 3);
            drawHandle(gc, xa, trackY, "A", HANDLE, Math.min(aNanos, bNanos));
            drawHandle(gc, xb, trackY, "B", HANDLE, Math.max(aNanos, bNanos));
        } else if (mode == MapSelectionMode.SINGLE_POINT && pointNanos != null) {
            double xp = x(pointNanos, w);
            drawHandle(gc, xp, trackY, null, POINT, pointNanos);
        }
    }

    private void drawTicks(GraphicsContext gc, double width, double height) {
        long span = Math.max(1, rangeEndNanos - rangeStartNanos);
        int desired = Math.max(2, (int) (width / TARGET_TICK_SPACING));
        long step = niceStepNanos(span / desired);
        gc.setFont(TICK_FONT);
        gc.setTextAlign(TextAlignment.CENTER);
        long first = (rangeStartNanos / step) * step;
        for (long t = first; t <= rangeEndNanos; t += step) {
            if (t < rangeStartNanos) {
                continue;
            }
            double x = x(t, width);
            gc.setStroke(TICK_LINE);
            gc.setLineWidth(1);
            gc.strokeLine(x, AXIS_TOP + TRACK_HEIGHT + 4, x, height - 2);
            gc.setFill(TICK_TEXT);
            gc.fillText(MapTimeFormat.clock(t), x, height - 5);
        }
        gc.setTextAlign(TextAlignment.LEFT);
    }

    private void drawHandle(GraphicsContext gc, double x, double trackY, String label, Color color, long timestampNanos) {
        gc.setFill(color);
        gc.fillRoundRect(x - 3, trackY - 5, 6, TRACK_HEIGHT + 10, 3, 3);

        String text = MapTimeFormat.withMillis(timestampNanos);
        gc.setFont(PILL_FONT);
        double pillWidth = text.length() * 5.6 + 12;
        double pillX = Math.max(0, Math.min(canvas.getWidth() - pillWidth, x - pillWidth / 2));
        gc.setFill(color);
        gc.fillRoundRect(pillX, 2, pillWidth, 17, 8, 8);
        gc.setFill(Color.WHITE);
        gc.setTextAlign(TextAlignment.CENTER);
        gc.fillText(text, pillX + pillWidth / 2, 14.5);
        if (label != null) {
            gc.setFill(color);
            gc.fillText(label, x, trackY + TRACK_HEIGHT + 16);
        }
        gc.setTextAlign(TextAlignment.LEFT);
    }

    private void onPressed(MouseEvent event) {
        double w = canvas.getWidth();
        activeHandle = Handle.NONE;
        if (mode == MapSelectionMode.A_B_RANGE && aNanos != null && bNanos != null) {
            double distanceToA = Math.abs(event.getX() - x(aNanos, w));
            double distanceToB = Math.abs(event.getX() - x(bNanos, w));
            if (Math.min(distanceToA, distanceToB) <= HANDLE_GRAB_PIXELS) {
                activeHandle = distanceToA <= distanceToB ? Handle.A : Handle.B;
            }
        } else if (mode == MapSelectionMode.SINGLE_POINT) {
            activeHandle = Handle.POINT;
            pointDraggedHandler.accept(timeAt(event.getX(), w));
        }
    }

    private void onDragged(MouseEvent event) {
        double w = canvas.getWidth();
        long t = timeAt(event.getX(), w);
        switch (activeHandle) {
            case POINT -> pointDraggedHandler.accept(t);
            case A -> {
                if (bNanos != null) {
                    rangeDraggedHandler.accept(t, bNanos);
                }
            }
            case B -> {
                if (aNanos != null) {
                    rangeDraggedHandler.accept(aNanos, t);
                }
            }
            case NONE -> {
            }
        }
    }

    private double x(long timestampNanos, double width) {
        long span = Math.max(1, rangeEndNanos - rangeStartNanos);
        double ratio = (double) (timestampNanos - rangeStartNanos) / span;
        return Math.max(0, Math.min(width, ratio * width));
    }

    private long timeAt(double x, double width) {
        long span = Math.max(1, rangeEndNanos - rangeStartNanos);
        double ratio = Math.max(0, Math.min(1, x / Math.max(1, width)));
        return rangeStartNanos + (long) (ratio * span);
    }

    /** Snaps ticks to 1/2/5/10/15/30 second-and-minute steps so labels stay readable. */
    private static long niceStepNanos(long raw) {
        long[] steps = {
                1_000_000L, 10_000_000L, 100_000_000L, 500_000_000L,
                1_000_000_000L, 2_000_000_000L, 5_000_000_000L, 10_000_000_000L, 15_000_000_000L,
                30_000_000_000L, 60_000_000_000L, 120_000_000_000L, 300_000_000_000L,
                600_000_000_000L, 900_000_000_000L, 1_800_000_000_000L, 3_600_000_000_000L};
        for (long step : steps) {
            if (step >= raw) {
                return step;
            }
        }
        return Math.max(1, raw);
    }
}
