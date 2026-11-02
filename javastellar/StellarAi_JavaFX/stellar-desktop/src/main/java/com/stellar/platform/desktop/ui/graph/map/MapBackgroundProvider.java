package com.stellar.platform.desktop.ui.graph.map;

import javafx.scene.canvas.GraphicsContext;

/**
 * Optional cartographic backdrop behind the route.
 *
 * <p>Deliberately separated from route analysis: point selection, A→B filtering, distance and
 * signal snapshots must all work with no backdrop at all, so the application stays usable offline
 * and never couples engineering results to tile availability.
 */
public interface MapBackgroundProvider {

    /** Paints the backdrop for the current viewport. Must not block the JavaFX thread. */
    void paint(GraphicsContext gc, MapViewport viewport);

    /** Human-readable name for the status line. */
    String displayName();

    /** Releases any cached imagery; called when the workspace is disposed. */
    default void dispose() {
    }
}
