package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.ui.graph.map.MapScale;
import com.stellar.platform.desktop.ui.graph.map.MapViewport;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Logger;

/**
 * Paints OpenStreetMap raster tiles into a report viewport.
 * Every required tile is fetched before any pixel is drawn. A missing tile
 * leaves the caller to paint the route-only fallback. There is no sleep.
 */
final class ReportMapTiles {

    private static final Logger LOG = Logger.getLogger(ReportMapTiles.class.getName());
    private static final int MAX_TILES = 36;
    private static final int MAX_ZOOM = 16;

    @FunctionalInterface
    interface TileFetcher {
        BufferedImage fetch(int zoom, int x, int y) throws IOException;
    }

    static TileFetcher fetcher = ReportMapTiles::download;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ReportMapTiles() {
    }

    static boolean paint(Graphics2D g, MapViewport viewport, int width, int height) {
        TileFetcher source = fetcher == null ? ReportMapTiles::download : fetcher;
        try {
            int zoom = chooseZoom(viewport);
            double north = viewport.latitudeAt(0);
            double south = viewport.latitudeAt(height);
            double west = viewport.longitudeAt(0);
            double east = viewport.longitudeAt(width);
            int n = 1 << zoom;
            int x0 = clamp(tileX(west, zoom), 0, n - 1);
            int x1 = clamp(tileX(east, zoom), 0, n - 1);
            int y0 = clamp(tileY(north, zoom), 0, n - 1);
            int y1 = clamp(tileY(south, zoom), 0, n - 1);
            if (x1 < x0 || y1 < y0) {
                return false;
            }
            int count = (x1 - x0 + 1) * (y1 - y0 + 1);
            while (count > MAX_TILES && zoom > 1) {
                zoom--;
                n = 1 << zoom;
                x0 = clamp(tileX(west, zoom), 0, n - 1);
                x1 = clamp(tileX(east, zoom), 0, n - 1);
                y0 = clamp(tileY(north, zoom), 0, n - 1);
                y1 = clamp(tileY(south, zoom), 0, n - 1);
                count = (x1 - x0 + 1) * (y1 - y0 + 1);
            }
            if (count > MAX_TILES) {
                return false;
            }
            List<Tile> tiles = fetchAll(source, zoom, x0, x1, y0, y1);
            if (tiles == null) {
                return false;
            }
            Object previous = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (Tile tile : tiles) {
                double lon0 = tileLon(tile.x, zoom);
                double lon1 = tileLon(tile.x + 1, zoom);
                double latN = tileLat(tile.y, zoom);
                double latS = tileLat(tile.y + 1, zoom);
                int dx = (int) Math.floor(viewport.x(lon0));
                int dy = (int) Math.floor(viewport.y(latN));
                int dw = Math.max(1, (int) Math.ceil(viewport.x(lon1)) - dx);
                int dh = Math.max(1, (int) Math.ceil(viewport.y(latS)) - dy);
                g.drawImage(tile.image, dx, dy, dw, dh, null);
            }
            if (previous == null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            } else {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, previous);
            }
            return true;
        } catch (RuntimeException ex) {
            LOG.fine(() -> "Report basemap was not painted: " + ex.getMessage());
            return false;
        }
    }

    private static List<Tile> fetchAll(TileFetcher source, int zoom, int x0, int x1, int y0, int y1) {
        record Request(int x, int y) {
        }
        List<Request> requests = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                requests.add(new Request(x, y));
            }
        }
        List<Tile> tiles = new ArrayList<>(requests.size());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Tile>> futures = new ArrayList<>();
            for (Request request : requests) {
                futures.add(pool.submit(() -> {
                    BufferedImage image = source.fetch(zoom, request.x, request.y);
                    if (image == null) {
                        throw new IOException("missing tile");
                    }
                    return new Tile(request.x, request.y, image);
                }));
            }
            for (Future<Tile> future : futures) {
                tiles.add(future.get());
            }
        } catch (Exception ex) {
            LOG.info("Report basemap tiles were not ready; using the route-only map.");
            return null;
        }
        return tiles;
    }

    private static int chooseZoom(MapViewport viewport) {
        double desired = MapScale.zoomFor(viewport.pixelsPerDegreeLongitude());
        int zoom = (int) Math.ceil(desired - 1e-6);
        if (zoom < 2) {
            return 2;
        }
        return Math.min(MAX_ZOOM, zoom);
    }

    private static BufferedImage download(int zoom, int x, int y) throws IOException {
        String url = "https://tile.openstreetmap.org/" + zoom + "/" + x + "/" + y + ".png";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", "StellarAI-EngineeringReport/" + DesktopVersion.displayVersion())
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body() == null || response.body().length == 0) {
                return null;
            }
            return ImageIO.read(new ByteArrayInputStream(response.body()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("tile download interrupted", ex);
        }
    }

    private static int tileX(double longitude, int zoom) {
        double n = Math.pow(2, zoom);
        return (int) Math.floor((longitude + 180.0) / 360.0 * n);
    }

    private static int tileY(double latitude, int zoom) {
        double clamped = Math.max(-85.05112878, Math.min(85.05112878, latitude));
        double rad = Math.toRadians(clamped);
        double merc = Math.log(Math.tan(rad) + 1.0 / Math.cos(rad));
        double n = Math.pow(2, zoom);
        return (int) Math.floor((1.0 - merc / Math.PI) / 2.0 * n);
    }

    private static double tileLon(int x, int zoom) {
        return x / Math.pow(2, zoom) * 360.0 - 180.0;
    }

    private static double tileLat(int y, int zoom) {
        double merc = Math.PI * (1 - 2.0 * y / Math.pow(2, zoom));
        return Math.toDegrees(Math.atan(Math.sinh(merc)));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record Tile(int x, int y, BufferedImage image) {
    }
}
