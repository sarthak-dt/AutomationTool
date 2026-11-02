package com.stellar.platform.desktop.ui.graph.map;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Classpath map assets. Leaflet comes from the Leaflet webjar; PMTiles client is vendored under
 * {@code /map/vendor/pmtiles.js} (BSD-3-Clause) so Map Route never depends on a CDN at runtime.
 */
public final class MapWebAssets {

    private static final String LEAFLET_JS = "/META-INF/resources/webjars/leaflet/1.9.4/dist/leaflet.js";
    private static final String LEAFLET_CSS = "/META-INF/resources/webjars/leaflet/1.9.4/dist/leaflet.css";
    private static final String PMTILES_JS = "/map/vendor/pmtiles.js";

    private MapWebAssets() {
    }

    public static String leafletJs() {
        return read(LEAFLET_JS, "Packaged Leaflet 1.9.4 JS missing");
    }

    public static String leafletCss() {
        return read(LEAFLET_CSS, "Packaged Leaflet 1.9.4 CSS missing");
    }

    public static String pmtilesJs() {
        return read(PMTILES_JS, "Vendored pmtiles.js missing");
    }

    private static String read(String path, String missingMessage) {
        try (InputStream in = MapWebAssets.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(missingMessage + " (" + path + ")");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException(missingMessage, ex);
        }
    }
}
