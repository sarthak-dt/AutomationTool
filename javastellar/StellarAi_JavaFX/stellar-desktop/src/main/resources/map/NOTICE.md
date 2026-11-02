# Local map assets (not loaded from CDN at runtime)

## Leaflet 1.9.4
- Source: Maven WebJar `org.webjars.npm:leaflet:1.9.4`
- License: BSD-2-Clause
- Purpose: Embedded Map Route street map engine (JavaFX WebView)

## pmtiles.js
- Path: `map/vendor/pmtiles.js`
- Upstream: https://github.com/protomaps/PMTiles (npm package `pmtiles`)
- License: BSD-3-Clause
- Purpose: Offline raster PMTiles client for Leaflet (`leafletRasterLayer`)

## Online tile providers
- OpenStreetMap Carto raster is retained for diagnostics only. It is not English-compliant.
- MapTiler English raster (pilot): attribution © MapTiler © OpenStreetMap contributors. API keys are external; never bundled.
- Self-hosted OpenMapTiles raster: attribution © OpenMapTiles © OpenStreetMap contributors (plus style license as configured).

Vector PMTiles (MVT) are not rendered in this pass — JavaFX WebView stability prefers raster PMTiles or online raster tiles.
