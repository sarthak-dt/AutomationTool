package com.stellar.platform.desktop.ui.graph.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for Leaflet HTML/JS lifecycle — no WebView, no pixel screenshots.
 */
class LeafletBasemapLifecycleTest {

    @Test
    void mapContainerUsesFullSizeLayoutWithoutFixedPixels() {
        String html = LeafletBasemapView.basemapHtml();
        assertTrue(html.contains("html,body,#map{"));
        assertTrue(html.contains("width:100%"));
        assertTrue(html.contains("height:100%"));
        assertTrue(html.contains("overflow:hidden"));
        assertFalse(html.contains("width:800px"), "must not pin Leaflet to a startup pixel width");
        assertFalse(html.contains("height:600px"));
    }

    @Test
    void leafletMapIsCreatedOnceAndTileLayerIsGuarded() {
        String html = LeafletBasemapView.basemapHtml();
        assertFalse(html.contains("if (map) return;"),
                "an existing JS map must replay leafletReady, not silently return");
        assertTrue(html.contains("notifyLeafletReadyIfUsable()"));
        assertTrue(html.contains("function notifyLeafletReadyIfUsable()"));
        assertTrue(html.contains("function mapIsUsable()"));
        assertTrue(html.contains("if (map) {"));
        assertTrue(html.contains("if (baseLayer && currentTileUrl === url) {"),
                "re-applying the same provider must not tear the layer down");
        assertFalse(html.contains("if (tileDone) notifyTiles('layerLoaded');"),
                "same-URL reapply must not reset Java via a second LiveConnect handshake");
        assertTrue(html.contains("window.stellarTileAudit = function()"));
        assertTrue(html.contains("window.stellarTileHealthSnapshot = function()"));
        assertTrue(html.contains("window.stellarMap = {"));
        assertTrue(html.contains("invalidateSize: function(w, h)"));
        assertTrue(html.contains("map.invalidateSize(true)"));
        assertEquals(1, count(html, "L.tileLayer("));
        assertEquals(1, count(html, "L.map("));
    }

    @Test
    void existingLeafletMapReplaysReadyInsteadOfRecreating() {
        String html = LeafletBasemapView.basemapHtml();
        int init = html.indexOf("window.stellarInitMap = function");
        int existing = html.indexOf("if (map) {", init);
        int created = html.indexOf("L.map(", init);
        int replay = html.indexOf("return notifyLeafletReadyIfUsable();", init);
        int defaultView = html.indexOf("map.setView([20,0], 2)", init);
        assertTrue(init > 0 && existing > init && existing < created,
                "retry must see the existing map before L.map");
        assertTrue(replay > existing && replay < created,
                "existing map must notify Java ready without creating another instance");
        assertTrue(defaultView > created,
                "the world default view belongs to first creation only");
        assertEquals(1, count(html, "mapInitCount++"),
                "map creation count must increment only when L.map runs");
    }

    @Test
    void readinessRequiresUsableContainerAndLeafletSize() {
        String html = LeafletBasemapView.basemapHtml();
        assertTrue(html.contains("function mapContainerUsable()"));
        assertTrue(html.contains("el.clientWidth >= MIN_MAP_PX && el.clientHeight >= MIN_MAP_PX"));
        assertTrue(html.contains("return !!map && mapContainerUsable() && mapDomSizeValid();"));
        assertTrue(html.contains("if (!mapIsUsable()) return false;"));
        int init = html.indexOf("window.stellarInitMap = function");
        int createGuard = html.indexOf(
                "if (!(w >= MIN_MAP_PX) || !(h >= MIN_MAP_PX) || !mapContainerUsable())", init);
        int created = html.indexOf("L.map(", init);
        assertTrue(createGuard > init && createGuard < created,
                "a zero-sized container must not create a Leaflet map");
    }

    @Test
    void viewIsAppliedAfterInvalidate() {
        String html = LeafletBasemapView.basemapHtml();
        int setView = html.indexOf("window.stellarSetView");
        int size = html.indexOf("map.invalidateSize(true);", setView);
        int applied = html.indexOf("map.setView(", setView);
        assertTrue(size > 0 && applied > size, "setView must follow invalidateSize");
    }

    @Test
    void javaFxWebViewUses2dIntegerZoomWithoutCssTileScaling() {
        String html = LeafletBasemapView.basemapHtml();
        int init = html.indexOf("window.stellarInitMap = function");
        int any3d = html.indexOf("L.Browser.any3d = false", init);
        int created = html.indexOf("L.map(", init);
        String initBody = html.substring(init, html.indexOf("window.stellarResizeMap"));
        assertTrue(html.contains("var STELLAR_WEBVIEW_2D = true;"));
        assertTrue(any3d > init && any3d < created,
                "any3d must be disabled before L.map so tiles use left/top, not translate3d");
        assertTrue(initBody.contains("zoomSnap:1"), "production WebView must use integer Leaflet zoom");
        assertTrue(initBody.contains("zoomDelta:1"));
        assertTrue(initBody.contains("zoomAnimation:false"));
        assertTrue(initBody.contains("fadeAnimation:false"));
        assertTrue(html.contains("tileSize: 256"), "self-hosted and online raster must stay 256px XYZ");
        assertFalse(initBody.contains("zoomSnap:0"), "fractional zoomSnap reintroduces CSS scale holes");
        assertFalse(initBody.contains("scale("),
                "compatibility path must not apply a CSS scale transform to tiles");
        assertTrue(html.contains("window.stellarSetView = function(lat, lon, zoom)"));
        assertFalse(html.contains("window.stellarSetBounds"),
                "the bounds-fitting entry point must be gone, not merely unused");
        assertTrue(html.contains("pendingView = [lat, lon, Math.round(zoom)]"),
                "Leaflet must receive an integer zoom");
    }

    @Test
    void mapIsCreatedAgainstAnExplicitlySizedContainer() {
        String html = LeafletBasemapView.basemapHtml();
        assertTrue(html.contains("window.stellarInitMap = function(w, h)"),
                "Leaflet must be told the container size it is initializing against");
        int init = html.indexOf("window.stellarInitMap");
        int sized = html.indexOf("sizeMapElement(w, h);", init);
        int created = html.indexOf("L.map(", init);
        assertTrue(sized > 0 && created > sized, "the DOM element must be sized before L.map runs");
    }

    @Test
    void viewApplicationIsRefusedWhileTheDomHasNoUsableSize() {
        String html = LeafletBasemapView.basemapHtml();
        int guard = html.indexOf("if (!mapDomSizeValid()) return;");
        int applied = html.indexOf("map.setView(L.latLng(", guard);
        assertTrue(guard > 0 && applied > guard,
                "a zero-sized container must never reach the canvas view");
        assertTrue(html.contains("size.x >= MIN_MAP_PX && size.y >= MIN_MAP_PX"));
    }

    @Test
    void tileLifecycleIsReportedBackToJava() {
        String html = LeafletBasemapView.basemapHtml();
        assertTrue(html.contains("layer.on('loading'"));
        assertTrue(html.contains("layer.on('tileload'"));
        assertTrue(html.contains("layer.on('load'"));
        assertTrue(html.contains("layer.on('tileerror'"));
        assertTrue(html.contains("notifyTiles('firstTile')"));
        assertTrue(html.contains("notifyTiles('layerLoaded')"));
        assertTrue(html.contains("notifyTiles('tileError')"));
        assertTrue(html.contains("notifyTiles('tileStalled')"));
        assertTrue(html.contains("if (layer === baseLayer && !tileDone)"),
                "an incomplete viewport after the first tile must still stall");
        assertTrue(html.contains("window.stellarBridge.offlineFailed"));
    }

    @Test
    void tileReportingIsCoalescedRatherThanPerTile() {
        String html = LeafletBasemapView.basemapHtml();
        assertTrue(html.contains("if (layer !== baseLayer || tileFirst) return;"),
                "only the first tile may cross the bridge");
        assertTrue(html.contains("if (tileErrors === TILE_ERROR_LIMIT)"),
                "tile errors must be reported once at the threshold, not per tile");
    }

    @Test
    void tileEventsCarryTheProviderGenerationThatProducedThem() {
        String html = LeafletBasemapView.basemapHtml();
        assertTrue(html.contains("window.stellarBridge.tileEvent(kind, providerGen, tileErrors)"),
                "Java must be able to discard events from a superseded provider");
        assertTrue(html.contains("if (requestedGen !== providerGen) { return; }"),
                "a late PMTiles header must not install a layer for a superseded provider");
    }

    @Test
    void providerSwitchResetsTileEvidence() {
        String html = LeafletBasemapView.basemapHtml();
        int clear = html.indexOf("window.stellarClearBasemap = function");
        assertTrue(clear > 0);
        assertTrue(html.indexOf("resetTileState();", clear) > clear);
        assertTrue(html.contains("tileErrors = 0; tileLoading = false; tileFirst = false; tileDone = false;"));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return n;
            }
            n++;
            from = at + needle.length();
        }
    }
}
