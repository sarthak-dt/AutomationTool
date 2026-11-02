package com.stellar.platform.desktop.ui.canvas;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.stage.Screen;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared sizing and paint gate for every JavaFX {@link Canvas} in the desktop.
 *
 * <p>The backing store is a single render texture of {@code ceil(css × outputScale)} device
 * pixels. JavaFX 21.0.6 dereferences that texture with no null check, so a failed allocation
 * becomes {@code RTTexture.createGraphics} on the render thread. This type keeps every product
 * canvas inside the smaller of the pipeline's maximum texture edge and the current display's
 * device-pixel edge, and it refuses to acquire a {@code GraphicsContext} once a surface is
 * detached, hidden, or disposed.
 *
 * <p>Logical content (a long recording, a stacked waveform, a route) is not a canvas size.
 * Callers pass the viewport, and scroll a viewport-sized tile across taller content.
 */
public final class CanvasRenderSupport {

    private static final Logger LOG = Logger.getLogger(CanvasRenderSupport.class.getName());
    /** Used only when neither the pipeline nor a screen can answer. */
    private static final int FALLBACK_EDGE = 4096;
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private static volatile int cachedPipelineMax = -1;
    private static volatile String cachedPipelineName = "";

    private CanvasRenderSupport() {
    }

    /**
     * Largest device-pixel edge a canvas texture may have on this process.
     * The pipeline maximum and the display's device-pixel edge are both applied when known.
     */
    public static int maxBackingEdge() {
        int pipeline = pipelineMaxEdge();
        int screen = screenDeviceEdge();
        if (pipeline > 0 && screen > 0) {
            return Math.min(pipeline, screen);
        }
        if (pipeline > 0) {
            return pipeline;
        }
        if (screen > 0) {
            return screen;
        }
        return FALLBACK_EDGE;
    }

    public static String pipelineName() {
        pipelineMaxEdge();
        String name = cachedPipelineName;
        String order = System.getProperty("prism.order");
        if (order != null && !order.isBlank()) {
            return name.isBlank() ? order : name + "/" + order;
        }
        return name.isBlank() ? "default" : name;
    }

    /**
     * CSS size whose backing store stays inside {@code maxBackingEdge} at the given output scale.
     * Non-finite or sub-pixel sizes are not paintable.
     */
    public static Size fit(double cssWidth, double cssHeight, double scaleX, double scaleY, int maxBackingEdge) {
        double sx = positiveScale(scaleX);
        double sy = positiveScale(scaleY);
        int edge = maxBackingEdge > 0 ? maxBackingEdge : FALLBACK_EDGE;
        if (!Double.isFinite(cssWidth) || !Double.isFinite(cssHeight) || cssWidth < 1 || cssHeight < 1) {
            return new Size(0, 0, sx, sy, 0, 0, edge, false);
        }
        double maxCssW = Math.floor(edge / sx);
        double maxCssH = Math.floor(edge / sy);
        if (maxCssW < 1 || maxCssH < 1) {
            return new Size(0, 0, sx, sy, 0, 0, edge, false);
        }
        double width = Math.min(cssWidth, maxCssW);
        double height = Math.min(cssHeight, maxCssH);
        int backingW = backingPixels(width, sx);
        int backingH = backingPixels(height, sy);
        while (backingW > edge && width > 1) {
            width -= 1;
            backingW = backingPixels(width, sx);
        }
        while (backingH > edge && height > 1) {
            height -= 1;
            backingH = backingPixels(height, sy);
        }
        boolean paintable = backingW >= 1 && backingH >= 1 && backingW <= edge && backingH <= edge;
        if (!paintable) {
            return new Size(0, 0, sx, sy, 0, 0, edge, false);
        }
        return new Size(width, height, sx, sy, backingW, backingH, edge, true);
    }

    public static Size fit(Canvas canvas, double cssWidth, double cssHeight) {
        return fit(cssWidth, cssHeight, outputScaleX(canvas), outputScaleY(canvas), maxBackingEdge());
    }

    /**
     * Moves and resizes {@code canvas} to a budget-safe CSS size. Returns whether the node
     * geometry changed. A non-paintable request clears the canvas to 0×0 so a later pulse cannot
     * upload the previous texture dimensions.
     */
    public static boolean place(Canvas canvas, double x, double y, double cssWidth, double cssHeight) {
        Size size = fit(canvas, cssWidth, cssHeight);
        double nextW = size.paintable() ? size.cssWidth() : 0;
        double nextH = size.paintable() ? size.cssHeight() : 0;
        boolean changed = false;
        if (Double.isFinite(x) && canvas.getLayoutX() != x) {
            canvas.setLayoutX(x);
            changed = true;
        }
        if (Double.isFinite(y) && canvas.getLayoutY() != y) {
            canvas.setLayoutY(y);
            changed = true;
        }
        if (canvas.getWidth() != nextW) {
            canvas.setWidth(nextW);
            changed = true;
        }
        if (canvas.getHeight() != nextH) {
            canvas.setHeight(nextH);
            changed = true;
        }
        if (size.paintable() && (cssWidth - size.cssWidth() > 0.5 || cssHeight - size.cssHeight() > 0.5)) {
            logClamp(ownerName(canvas), cssWidth, cssHeight, size);
        }
        return changed;
    }

    /** True when a {@code GraphicsContext} may be acquired without asking for an illegal texture. */
    public static boolean canPaint(Canvas canvas) {
        if (canvas == null || canvas.getScene() == null || !canvas.isVisible()) {
            return false;
        }
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        Size size = fit(canvas, width, height);
        return size.paintable()
                && size.backingWidth() <= size.maxBackingEdge()
                && size.backingHeight() <= size.maxBackingEdge()
                && Math.abs(size.cssWidth() - width) < 0.5
                && Math.abs(size.cssHeight() - height) < 0.5;
    }

    public static double outputScaleX(Canvas canvas) {
        return outputScale(canvas, true);
    }

    public static double outputScaleY(Canvas canvas) {
        return outputScale(canvas, false);
    }

    /**
     * Coalesces paints to one pulse and drops them after {@link #dispose()} or while suspended.
     * The paint callback must call {@link #begin()} before {@code getGraphicsContext2D}.
     */
    public static final class PaintGate {
        private final Canvas canvas;
        private boolean disposed;
        private boolean suspended;
        private boolean dirty = true;
        private boolean queued;
        private boolean burst;
        private Runnable latest;
        private int paints;

        public PaintGate(Canvas canvas) {
            this.canvas = canvas;
        }

        /**
         * Data changes paint on this pulse when the canvas can accept a texture.
         * Further requests in the same turn keep the latest callback and paint it once
         * on the next pulse, so a selection burst does not append a full command
         * buffer for every intermediate track list.
         */
        public void request(Runnable paint) {
            if (disposed || paint == null) {
                return;
            }
            dirty = true;
            latest = paint;
            if (Platform.isFxApplicationThread() && canPaint(canvas)) {
                queued = false;
                if (burst) {
                    return;
                }
                burst = true;
                paint.run();
                Platform.runLater(this::paintLatestAfterBurst);
                return;
            }
            if (queued) {
                return;
            }
            enqueue(paint);
        }

        /** Paints the callback retained by a same-pulse burst. Tests use this instead of waiting. */
        public void flushForTest() {
            paintLatestAfterBurst();
        }

        private void paintLatestAfterBurst() {
            burst = false;
            Runnable paint = latest;
            if (disposed || !dirty || paint == null || !canPaint(canvas)) {
                return;
            }
            paint.run();
        }

        /** Pointer and scroll bursts share one paint on the next pulse. */
        public void requestCoalesced(Runnable paint) {
            if (disposed || paint == null) {
                return;
            }
            dirty = true;
            if (queued) {
                return;
            }
            enqueue(paint);
        }

        private void enqueue(Runnable paint) {
            queued = true;
            Platform.runLater(() -> {
                queued = false;
                if (disposed || !dirty) {
                    return;
                }
                paint.run();
            });
        }

        /** @return whether the caller may acquire a graphics context on this pulse */
        public boolean begin() {
            if (disposed || suspended) {
                dirty = false;
                return false;
            }
            if (!canPaint(canvas)) {
                return false;
            }
            dirty = false;
            paints++;
            notePaint(canvas);
            return true;
        }

        public boolean isDirty() {
            return dirty;
        }

        public void markDirty() {
            if (!disposed) {
                dirty = true;
            }
        }

        public void dispose() {
            disposed = true;
            suspended = true;
            dirty = false;
            queued = false;
        }

        public void suspend() {
            suspended = true;
        }

        public void resume(Runnable paint) {
            if (disposed || !suspended) {
                return;
            }
            suspended = false;
            request(paint);
        }

        public boolean isDisposed() {
            return disposed;
        }

        public boolean isSuspended() {
            return suspended;
        }

        public int paints() {
            return paints;
        }
    }

    public record Size(
            double cssWidth,
            double cssHeight,
            double scaleX,
            double scaleY,
            int backingWidth,
            int backingHeight,
            int maxBackingEdge,
            boolean paintable
    ) {
    }

    private static void notePaint(Canvas canvas) {
        Size size = fit(canvas, canvas.getWidth(), canvas.getHeight());
        if (!size.paintable()) {
            return;
        }
        String key = ownerName(canvas) + ":" + (size.backingWidth() / 64) + "x" + (size.backingHeight() / 64);
        if (LOGGED.size() > 200 || !LOGGED.add(key)) {
            return;
        }
        LOG.info(() -> "Canvas " + ownerName(canvas)
                + " pipeline=" + pipelineName()
                + " scale=" + trim(size.scaleX()) + "x" + trim(size.scaleY())
                + " css=" + Math.round(size.cssWidth()) + "x" + Math.round(size.cssHeight())
                + " backing=" + size.backingWidth() + "x" + size.backingHeight()
                + " maxEdge=" + size.maxBackingEdge());
    }

    private static void logClamp(String owner, double requestedW, double requestedH, Size size) {
        String key = "clamp:" + owner + ":" + size.backingWidth() + "x" + size.backingHeight();
        if (!LOGGED.add(key)) {
            return;
        }
        LOG.log(Level.WARNING, () -> "Canvas " + owner + " clamped "
                + Math.round(requestedW) + "x" + Math.round(requestedH)
                + " css to " + Math.round(size.cssWidth()) + "x" + Math.round(size.cssHeight())
                + " scale=" + trim(size.scaleX()) + "x" + trim(size.scaleY())
                + " backing=" + size.backingWidth() + "x" + size.backingHeight()
                + " maxEdge=" + size.maxBackingEdge()
                + " pipeline=" + pipelineName());
    }

    private static String ownerName(Canvas canvas) {
        if (canvas == null) {
            return "Canvas";
        }
        if (canvas.getParent() != null) {
            return canvas.getParent().getClass().getSimpleName();
        }
        return canvas.getClass().getSimpleName();
    }

    private static String trim(double scale) {
        return String.format(java.util.Locale.ROOT, "%.2f", scale);
    }

    private static int backingPixels(double css, double scale) {
        double pixels = css * scale;
        if (!Double.isFinite(pixels) || pixels <= 0) {
            return 0;
        }
        return (int) Math.ceil(pixels);
    }

    private static double outputScale(Canvas canvas, boolean horizontal) {
        try {
            if (canvas != null && canvas.getScene() != null && canvas.getScene().getWindow() != null) {
                var window = canvas.getScene().getWindow();
                return positiveScale(horizontal ? window.getOutputScaleX() : window.getOutputScaleY());
            }
            Screen screen = Screen.getPrimary();
            if (screen != null) {
                return positiveScale(horizontal ? screen.getOutputScaleX() : screen.getOutputScaleY());
            }
        } catch (RuntimeException ignored) {
            return 1;
        }
        return 1;
    }

    private static double positiveScale(double scale) {
        return Double.isFinite(scale) && scale > 0 ? scale : 1;
    }

    private static int screenDeviceEdge() {
        double max = 0;
        try {
            for (Screen screen : Screen.getScreens()) {
                if (screen == null || screen.getBounds() == null) {
                    continue;
                }
                max = Math.max(max, screen.getBounds().getWidth() * positiveScale(screen.getOutputScaleX()));
                max = Math.max(max, screen.getBounds().getHeight() * positiveScale(screen.getOutputScaleY()));
            }
        } catch (RuntimeException ignored) {
            return 0;
        }
        if (!(max >= 64) || max > Integer.MAX_VALUE) {
            return 0;
        }
        return (int) Math.floor(max);
    }

    /** 0 when this pipeline does not publish a device maximum (the software pipeline). */
    private static int pipelineMaxEdge() {
        int cached = cachedPipelineMax;
        if (cached >= 0) {
            return cached;
        }
        int value = 0;
        String name = "unknown";
        try {
            Class<?> type = Class.forName("com.sun.prism.GraphicsPipeline");
            Object pipeline = type.getMethod("getPipeline").invoke(null);
            if (pipeline != null) {
                name = pipeline.getClass().getSimpleName();
                try {
                    Object reported = pipeline.getClass().getMethod("getMaxTextureSize").invoke(pipeline);
                    if (reported instanceof Integer edge && edge > 0) {
                        value = edge;
                    }
                } catch (ReflectiveOperationException ignored) {
                    value = 0;
                }
            }
        } catch (Throwable ex) {
            name = "encapsulated";
            value = 0;
            if (LOGGED.add("pipeline-probe")) {
                LOG.info("Canvas pipeline type is not readable ("
                        + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                        + "). Texture edge uses the display device pixels.");
            }
        }
        cachedPipelineName = name;
        cachedPipelineMax = value;
        return value;
    }
}
