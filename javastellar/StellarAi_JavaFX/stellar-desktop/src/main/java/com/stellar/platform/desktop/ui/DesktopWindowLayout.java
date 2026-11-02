package com.stellar.platform.desktop.ui;

import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

/**
 * Window chrome and host-stretch helpers for the desktop shell.
 * Uses {@link Screen#getVisualBounds()} (taskbar-aware) rather than full screen bounds.
 */
public final class DesktopWindowLayout {

    /** Usability floor, not a monitor-resolution assumption. */
    public static final double MIN_WIDTH = 1100;
    /** Usability floor, not a monitor-resolution assumption. */
    public static final double MIN_HEIGHT = 700;
    public static final double DEFAULT_WIDTH = 1280;
    public static final double DEFAULT_HEIGHT = 800;

    private DesktopWindowLayout() {
    }

    /**
     * Decorated, resizable workspace: native Windows min/max/close, never sized to
     * the full screen rectangle. Maximized state is delegated to the OS.
     */
    public static void configurePrimaryStage(Stage stage, javafx.scene.Scene scene) {
        Rectangle2D visual = Screen.getPrimary().getVisualBounds();
        stage.initStyle(StageStyle.DECORATED);
        stage.setResizable(true);
        stage.setFullScreen(false);
        stage.setMinWidth(clamp(MIN_WIDTH, visual.getWidth()));
        stage.setMinHeight(clamp(MIN_HEIGHT, visual.getHeight()));
        stage.setScene(scene);

        double initialWidth = clamp(DEFAULT_WIDTH, visual.getWidth());
        double initialHeight = clamp(DEFAULT_HEIGHT, visual.getHeight());
        FittedBounds initial = fit(
                visual.getMinX() + (visual.getWidth() - initialWidth) / 2.0,
                visual.getMinY() + (visual.getHeight() - initialHeight) / 2.0,
                initialWidth,
                initialHeight,
                visual
        );
        stage.setWidth(initial.width());
        stage.setHeight(initial.height());
        stage.setX(initial.x());
        stage.setY(initial.y());
        stage.show();
        // setWidth in the same pulse as setMaximized leaves the frame at 1280x800
        // while the flag says maximized, so the work area is never actually used.
        javafx.application.Platform.runLater(() -> stage.setMaximized(true));
    }

    /** Page roots in a StackPane must allow grow/shrink or they overflow at pref size. */
    public static void stretchToHost(Node node) {
        if (node instanceof Region region) {
            region.setMinSize(0, 0);
            region.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        }
    }

    public static double clamp(double desired, double visualLimit) {
        if (visualLimit <= 0) {
            return desired;
        }
        return Math.min(desired, visualLimit);
    }

    /**
     * Keep a window rectangle inside usable desktop space (visual bounds).
     * Does not expand to fill the screen — only shrinks/shifts if needed.
     */
    public static FittedBounds fit(double x, double y, double width, double height, Rectangle2D visual) {
        double w = clamp(width, visual.getWidth());
        double h = clamp(height, visual.getHeight());
        double maxX = visual.getMaxX() - w;
        double maxY = visual.getMaxY() - h;
        double nx = x;
        double ny = y;
        if (nx < visual.getMinX()) {
            nx = visual.getMinX();
        } else if (nx > maxX) {
            nx = maxX;
        }
        if (ny < visual.getMinY()) {
            ny = visual.getMinY();
        } else if (ny > maxY) {
            ny = maxY;
        }
        return new FittedBounds(nx, ny, w, h);
    }

    public record FittedBounds(double x, double y, double width, double height) {
    }
}