package com.stellar.platform.desktop.ui;

import javafx.geometry.Rectangle2D;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopWindowLayoutTest {

    @Test
    void clamp_doesNotExceedVisualLimit() {
        assertEquals(1100, DesktopWindowLayout.clamp(1100, 1920));
        assertEquals(800, DesktopWindowLayout.clamp(1100, 800));
        assertEquals(1100, DesktopWindowLayout.clamp(1100, 0));
    }

    @Test
    void fit_keepsWindowInsideVisualBoundsIncludingTaskbarInset() {
        Rectangle2D visual = new Rectangle2D(0, 0, 1920, 1040);
        DesktopWindowLayout.FittedBounds fitted = DesktopWindowLayout.fit(-40, -30, 2000, 1400, visual);
        assertEquals(0, fitted.x());
        assertEquals(0, fitted.y());
        assertEquals(1920, fitted.width());
        assertEquals(1040, fitted.height());
    }

    @Test
    void fit_doesNotExpandSmallWindowToFillScreen() {
        Rectangle2D visual = new Rectangle2D(0, 0, 1920, 1040);
        DesktopWindowLayout.FittedBounds fitted = DesktopWindowLayout.fit(100, 80, 1280, 800, visual);
        assertEquals(100, fitted.x());
        assertEquals(80, fitted.y());
        assertEquals(1280, fitted.width());
        assertEquals(800, fitted.height());
    }

    @Test
    void fit_respectsNonOriginVisualBounds() {
        Rectangle2D visual = new Rectangle2D(100, 50, 1600, 900);
        DesktopWindowLayout.FittedBounds fitted = DesktopWindowLayout.fit(0, 0, 3000, 3000, visual);
        assertEquals(100, fitted.x());
        assertEquals(50, fitted.y());
        assertEquals(1600, fitted.width());
        assertEquals(900, fitted.height());
    }

    @Test
    void stretchToHost_allowsPageToFillAndShrink() {
        VBox page = new VBox();
        DesktopWindowLayout.stretchToHost(page);
        assertEquals(0, page.getMinWidth());
        assertEquals(0, page.getMinHeight());
        assertTrue(page.getMaxWidth() >= Double.MAX_VALUE / 2);
        assertTrue(page.getMaxHeight() >= Double.MAX_VALUE / 2);
    }
}