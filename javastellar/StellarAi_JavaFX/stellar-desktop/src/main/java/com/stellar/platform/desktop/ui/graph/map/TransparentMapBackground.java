package com.stellar.platform.desktop.ui.graph.map;

import javafx.scene.canvas.GraphicsContext;

/** Clears the canvas so an underlying WebView basemap remains visible. */
public final class TransparentMapBackground implements MapBackgroundProvider {

    @Override
    public void paint(GraphicsContext gc, MapViewport viewport) {
        gc.clearRect(0, 0, viewport.width(), viewport.height());
    }

    @Override
    public String displayName() {
        return "Street map";
    }
}
