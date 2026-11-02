package com.stellar.platform.desktop.ui.graph.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapViewportRevealTest {

    @Test
    void revealPansOnlyWhenPointIsOutsideViewport() {
        MapViewport viewport = new MapViewport();
        viewport.resize(400, 300);
        viewport.fit(null, 20);
        boolean insideMove = viewport.reveal(viewport.centerLatitude(), viewport.centerLongitude(), 40);
        assertFalse(insideMove);
        boolean outside = viewport.reveal(viewport.centerLatitude() + 5, viewport.centerLongitude() + 5, 40);
        assertTrue(outside);
    }
}
