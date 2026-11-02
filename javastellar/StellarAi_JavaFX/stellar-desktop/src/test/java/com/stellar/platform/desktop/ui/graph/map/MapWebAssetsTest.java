package com.stellar.platform.desktop.ui.graph.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapWebAssetsTest {

    @Test
    void leafletAndPmtilesArePackagedLocally() {
        String css = MapWebAssets.leafletCss();
        String js = MapWebAssets.leafletJs();
        String pmtiles = MapWebAssets.pmtilesJs();
        assertTrue(css.contains(".leaflet"), "Leaflet CSS must be embedded");
        assertTrue(js.contains("L.Map") || js.contains("function"), "Leaflet JS must be embedded");
        assertTrue(pmtiles.contains("PMTiles") || pmtiles.contains("leafletRasterLayer"));
        assertFalse(js.contains("unpkg.com"));
        assertFalse(pmtiles.contains("unpkg.com"));
    }
}
