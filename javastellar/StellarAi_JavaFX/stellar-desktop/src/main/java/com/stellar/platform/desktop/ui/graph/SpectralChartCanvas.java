package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.desktop.ui.canvas.CanvasRenderSupport;
import com.stellar.platform.desktop.ui.canvas.CanvasRenderSupport.PaintGate;
import com.stellar.platform.desktop.ui.theme.StellarTheme;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * Frequency-domain chart. X = Hz. Bounded rendering — never one JavaFX node per bin.
 */
public final class SpectralChartCanvas extends Canvas {

    public static final String EMPTY_HINT = "Run FFT or PSD to plot spectrum (Hz)";

    private static final int MAX_DRAW = 2048;

    private double[] frequency = new double[0];
    private double[] values = new double[0];
    private double[] comparison = new double[0];
    private List<Double> peakHz = List.of();
    private double highlightHz = Double.NaN;
    private String yLabel = "Magnitude";
    private DoubleConsumer hoverHandler = hz -> {};
    private final PaintGate paintGate = new PaintGate(this);
    private boolean adjustingSize;

    public SpectralChartCanvas() {
        super(640, 180);
        widthProperty().addListener((o, a, b) -> {
            if (!adjustingSize) {
                requestRedraw();
            }
        });
        heightProperty().addListener((o, a, b) -> {
            if (!adjustingSize) {
                requestRedraw();
            }
        });
        setOnMouseMoved(this::onMove);
        setOnMouseExited(e -> hoverHandler.accept(Double.NaN));
        sceneProperty().addListener((obs, was, now) -> {
            if (now != null) {
                requestRedraw();
            }
        });
    }

    public void release() {
        paintGate.dispose();
        setOnMouseMoved(null);
        setOnMouseExited(null);
    }

    public void setHoverHandler(DoubleConsumer handler) {
        hoverHandler = handler == null ? hz -> {} : handler;
    }

    public void setSeries(double[] frequencyHz, double[] values, String yLabel) {
        this.frequency = frequencyHz == null ? new double[0] : frequencyHz;
        this.values = values == null ? new double[0] : values;
        this.yLabel = yLabel == null ? "Magnitude" : yLabel;
        requestRedraw();
    }

    public void setComparison(double[] values) {
        this.comparison = values == null ? new double[0] : values;
        requestRedraw();
    }

    public void setPeaks(List<Double> peaks) {
        this.peakHz = peaks == null ? List.of() : List.copyOf(peaks);
        requestRedraw();
    }

    public void highlight(double hz) {
        this.highlightHz = hz;
        requestRedraw();
    }

    public String plottedHintForTest() {
        return frequency.length < 2 || values.length < 2 ? EMPTY_HINT : "";
    }

    public void moveHoverForTest(double x, double y) {
        onMove(new MouseEvent(MouseEvent.MOUSE_MOVED, x, y, x, y, javafx.scene.input.MouseButton.NONE, 1,
                false, false, false, false, false, false, false, true, false, false, null));
    }

    public String yAxisLabelForTest() {
        return yLabel;
    }

    public double valueAt(double hz) {
        if (frequency.length == 0) {
            return Double.NaN;
        }
        int best = 0;
        double d = Double.MAX_VALUE;
        for (int i = 0; i < frequency.length; i++) {
            double q = Math.abs(frequency[i] - hz);
            if (q < d) {
                d = q;
                best = i;
            }
        }
        return values.length > best ? values[best] : Double.NaN;
    }

    private void onMove(MouseEvent e) {
        if (frequency.length == 0) {
            return;
        }
        double left = 48;
        double right = getWidth() - 8;
        double x = e.getX();
        if (x < left || x > right) {
            hoverHandler.accept(Double.NaN);
            return;
        }
        double fMax = frequency[frequency.length - 1];
        if (fMax <= 0) {
            return;
        }
        double hz = ((x - left) / (right - left)) * fMax;
        highlight(hz);
        hoverHandler.accept(hz);
    }

    private void requestRedraw() {
        paintGate.request(this::redraw);
    }

    private void redraw() {
        CanvasRenderSupport.Size fitted = CanvasRenderSupport.fit(this, getWidth(), getHeight());
        if (fitted.paintable()
                && (Math.abs(getWidth() - fitted.cssWidth()) > 0.5 || Math.abs(getHeight() - fitted.cssHeight()) > 0.5)) {
            adjustingSize = true;
            if (!widthProperty().isBound() && Math.abs(getWidth() - fitted.cssWidth()) > 0.5) {
                setWidth(fitted.cssWidth());
            }
            if (!heightProperty().isBound() && Math.abs(getHeight() - fitted.cssHeight()) > 0.5) {
                setHeight(fitted.cssHeight());
            }
            adjustingSize = false;
        }
        if (!paintGate.begin()) {
            return;
        }
        GraphicsContext g = getGraphicsContext2D();
        double w = getWidth();
        double h = getHeight();
        g.setFill(Color.web("#f7f7f4"));
        g.fillRect(0, 0, w, h);
        g.setStroke(Color.web("#d0d0c8"));
        g.strokeRect(0.5, 0.5, w - 1, h - 1);
        if (frequency.length < 2 || values.length < 2) {
            g.setFill(Color.web("#666"));
            g.setFont(Font.font(StellarTheme.FONT_UI_FAMILY, 11));
            g.fillText(EMPTY_HINT, 12, h / 2);
            return;
        }
        double left = 48;
        double right = w - 8;
        double top = 12;
        double bottom = h - 22;
        double fMax = frequency[frequency.length - 1];
        double vMax = 1e-12;
        for (double v : values) {
            if (Double.isFinite(v)) {
                vMax = Math.max(vMax, v);
            }
        }
        for (double v : comparison) {
            if (Double.isFinite(v)) {
                vMax = Math.max(vMax, v);
            }
        }
        g.setStroke(Color.web("#bbb"));
        g.strokeLine(left, top, left, bottom);
        g.strokeLine(left, bottom, right, bottom);
        g.setFill(Color.web("#444"));
        g.setFont(Font.font(StellarTheme.FONT_UI_FAMILY, 10));
        g.fillText("Hz", right - 18, h - 6);
        g.fillText(yLabel, 4, 12);
        g.fillText(String.format(Locale.ROOT, "%.3g", fMax), right - 40, h - 6);
        g.fillText("0", left, h - 6);
        drawPolyline(g, frequency, values, left, right, top, bottom, fMax, vMax, Color.web("#1d4f7a"));
        if (comparison.length == frequency.length) {
            drawPolyline(g, frequency, comparison, left, right, top, bottom, fMax, vMax, Color.web("#b85c38"));
        }
        g.setStroke(Color.web("#c45c26"));
        for (Double pk : peakHz) {
            if (pk == null || !Double.isFinite(pk) || fMax <= 0) {
                continue;
            }
            double x = left + (pk / fMax) * (right - left);
            g.strokeLine(x, top, x, bottom);
        }
        if (Double.isFinite(highlightHz) && fMax > 0) {
            double x = left + (highlightHz / fMax) * (right - left);
            g.setStroke(Color.web("#222"));
            g.strokeLine(x, top, x, bottom);
        }
    }

    private static void drawPolyline(
            GraphicsContext g,
            double[] f,
            double[] v,
            double left,
            double right,
            double top,
            double bottom,
            double fMax,
            double vMax,
            Color color
    ) {
        int n = Math.min(f.length, v.length);
        int step = Math.max(1, n / MAX_DRAW);
        g.setStroke(color);
        g.setLineWidth(1.1);
        g.beginPath();
        boolean started = false;
        for (int i = 0; i < n; i += step) {
            double x = left + (f[i] / fMax) * (right - left);
            double y = bottom - (v[i] / vMax) * (bottom - top);
            if (!started) {
                g.moveTo(x, y);
                started = true;
            } else {
                g.lineTo(x, y);
            }
        }
        g.stroke();
    }
}
