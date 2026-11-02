package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.desktop.ui.canvas.CanvasRenderSupport;
import com.stellar.platform.desktop.ui.canvas.CanvasRenderSupport.PaintGate;
import com.stellar.platform.desktop.ui.graph.GraphSeriesPalette;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.List;

/**
 * Compact multi-track sparkline strip for the A→B summary.
 *
 * <p>Draws the viewport series the waveform pipeline already produced for the selected interval, so
 * the map never issues a second query or keeps a second copy of signal data. Everything lands on
 * one canvas and the track count is capped, keeping the summary readable instead of turning into
 * dozens of charts.
 */
public final class MiniTrackCanvas extends Region {

    private static final int MAX_TRACKS = 4;
    private static final double TRACK_HEIGHT = 52;
    private static final double LABEL_WIDTH = 128;
    private static final double VALUE_WIDTH = 96;
    private static final Font LABEL_FONT = Font.font(com.stellar.platform.desktop.ui.theme.StellarTheme.FONT_UI_FAMILY, FontWeight.BOLD, 11);
    private static final Font SMALL_FONT = Font.font(com.stellar.platform.desktop.ui.theme.StellarTheme.FONT_UI_FAMILY, 10);
    private static final Color LABEL_COLOR = MapPalette.MINI_LABEL;
    private static final Color MUTED = MapPalette.MINI_MUTED;
    private static final Color BASELINE = MapPalette.MINI_BASELINE;

    private final Canvas canvas = new Canvas();
    private final PaintGate paintGate = new PaintGate(canvas);
    private List<GraphRenderSeries> series = List.of();
    private List<Integer> colorIndexes = List.of();

    public MiniTrackCanvas() {
        getStyleClass().add("map-route-mini-tracks");
        setMinHeight(0);
        getChildren().add(canvas);
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

    /** Drops queued paints. Does not upload a clear. */
    public void release() {
        paintGate.dispose();
        series = List.of();
    }

    public void setSeries(List<GraphRenderSeries> series) {
        setSeries(series, List.of());
    }

    public void setSeries(List<GraphRenderSeries> series, List<Integer> colorIndexes) {
        this.colorIndexes = colorIndexes == null ? List.of() : List.copyOf(colorIndexes);
        this.series = series == null ? List.of() : series.stream().limit(MAX_TRACKS).toList();
        double height = Math.max(TRACK_HEIGHT, this.series.size() * TRACK_HEIGHT);
        setPrefHeight(height);
        setMinHeight(0);
        setMaxHeight(height);
        requestRedraw();
    }

    public void clear() {
        setSeries(List.of());
    }

    public int trackCount() {
        return series.size();
    }

    public int colorIndexForTest(int index) {
        if (index >= 0 && index < colorIndexes.size() && colorIndexes.get(index) != null) {
            return colorIndexes.get(index);
        }
        return index;
    }

    public long sampleCountForTest(int index) {
        if (index < 0 || index >= series.size()) {
            return 0;
        }
        return series.get(index).sourceSampleCount();
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
        if (series.isEmpty()) {
            gc.setFill(MUTED);
            gc.setFont(SMALL_FONT);
            gc.fillText("Select signals to see their trend across the chosen range.", 4, 18);
            return;
        }
        double plotX = LABEL_WIDTH;
        double plotWidth = Math.max(40, w - LABEL_WIDTH - VALUE_WIDTH);
        for (int i = 0; i < series.size(); i++) {
            drawTrack(gc, series.get(i), i, plotX, plotWidth, w);
        }
    }

    private void drawTrack(GraphicsContext gc, GraphRenderSeries track, int index,
                           double plotX, double plotWidth, double width) {
        double top = index * TRACK_HEIGHT;
        double plotTop = top + 8;
        double plotHeight = TRACK_HEIGHT - 16;

        gc.setFill(LABEL_COLOR);
        gc.setFont(LABEL_FONT);
        gc.fillText(ellipsis(track.key().signalName(), 18), 4, top + 20);
        gc.setFill(MUTED);
        gc.setFont(SMALL_FONT);
        gc.fillText(track.unit() == null ? "" : track.unit(), 4, top + 34);

        gc.setStroke(BASELINE);
        gc.setLineWidth(1);
        gc.strokeLine(plotX, plotTop + plotHeight, plotX + plotWidth, plotTop + plotHeight);

        List<GraphBucket> buckets = track.buckets();
        if (buckets.isEmpty()) {
            gc.setFill(MUTED);
            gc.fillText("no samples in range", plotX + 4, plotTop + plotHeight / 2);
            return;
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (GraphBucket bucket : buckets) {
            if (bucket.minUsable()) {
                min = Math.min(min, bucket.minValue());
            }
            if (bucket.maxUsable()) {
                max = Math.max(max, bucket.maxValue());
            }
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            min = 0;
            max = 1;
        }
        double span = max - min;
        if (!(span > 0)) {
            span = 1;
            min -= 0.5;
        }

        int palette = index < colorIndexes.size() && colorIndexes.get(index) != null
                ? colorIndexes.get(index) : index;
        gc.setStroke(GraphSeriesPalette.at(palette));
        gc.setLineWidth(1.4);
        boolean penDown = false;
        for (int i = 0; i < buckets.size(); i++) {
            GraphBucket bucket = buckets.get(i);
            if (!bucket.lastUsable()) {
                if (penDown) {
                    gc.stroke();
                    penDown = false;
                }
                continue;
            }
            double x = plotX + (buckets.size() == 1 ? 0 : (double) i / (buckets.size() - 1) * plotWidth);
            double y = plotTop + plotHeight - (bucket.lastValue() - min) / span * plotHeight;
            if (!penDown) {
                gc.beginPath();
                gc.moveTo(x, y);
                penDown = true;
            } else {
                gc.lineTo(x, y);
            }
        }
        if (penDown) {
            gc.stroke();
        }

        gc.setFill(MUTED);
        gc.setFont(SMALL_FONT);
        gc.setTextAlign(TextAlignment.RIGHT);
        gc.fillText("Min " + compact(min), width - 6, top + 20);
        gc.fillText("Max " + compact(max) + " · " + track.sourceSampleCount() + " samples", width - 6, top + 34);
        gc.setTextAlign(TextAlignment.LEFT);
    }

    private static String ellipsis(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }

    private static String compact(double value) {
        double magnitude = Math.abs(value);
        if (magnitude >= 1000 || (magnitude > 0 && magnitude < 0.01)) {
            return String.format("%.3g", value);
        }
        return String.format("%.1f", value);
    }
}
