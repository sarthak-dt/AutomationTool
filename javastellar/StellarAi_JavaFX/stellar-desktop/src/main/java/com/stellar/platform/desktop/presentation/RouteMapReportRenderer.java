package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.desktop.ui.graph.map.MapViewport;
import com.stellar.platform.desktop.ui.graph.map.MapViewportCalculator;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.processing.map.RouteSegment;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import com.stellar.platform.analysis.source.AnalysisCancelledException;
import com.stellar.platform.parser.spi.CancellationToken;

import java.util.logging.Logger;

/**
 * Draws a static route image from prepared GPS geometry.
 * No JavaFX stage, no WebView, no tile URL, and no API key is written into the image.
 */
public final class RouteMapReportRenderer {

    private static final Logger LOG = Logger.getLogger(RouteMapReportRenderer.class.getName());
    static final int WIDTH = 1600;
    static final int HEIGHT = 900;
    private static final Color SURFACE = new Color(0xF4, 0xF7, 0xFA);
    private static final Color GRID = new Color(0xE3, 0xEA, 0xF1);
    private static final Color GRID_MAJOR = new Color(0xD5, 0xDF, 0xE9);
    private static final Color CASING = Color.WHITE;
    private static final Color ROUTE = new Color(0x16, 0x77, 0xFF);
    private static final Color START = new Color(0x22, 0xA0, 0x6B);
    private static final Color END = new Color(0xE5, 0x48, 0x4D);

    private final ProjectRouteReportLoader loader = new ProjectRouteReportLoader();

    public RouteMapReportAsset prepare(DesktopAppService app) {
        return prepare(app, null);
    }

    public RouteMapReportAsset prepare(DesktopAppService app, CancellationToken token) {
        return prepare(app, null, false, token);
    }

    public RouteMapReportAsset prepare(
            DesktopAppService app,
            com.stellar.platform.analysis.investigation.EngineeringInvestigation investigation,
            boolean persistedOnly,
            CancellationToken token
    ) {
        try {
            if (token != null && token.isCancellationRequested()) {
                throw new AnalysisCancelledException();
            }
            return render(loader.load(app, investigation, persistedOnly, token), false, true);
        } catch (AnalysisCancelledException cancelled) {
            throw cancelled;
        } catch (RuntimeException ex) {
            LOG.warning("Report route was not prepared: "
                    + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            return null;
        }
    }

    public record MapMarker(String label, double latitude, double longitude) {
    }

    public RouteMapReportAsset render(RouteMapReportContext context) {
        return render(context, false, false);
    }

    public RouteMapReportAsset render(RouteMapReportContext context, List<MapMarker> markers) {
        RouteMapReportAsset asset = render(context, false, false);
        if (asset == null || !asset.hasImage() || markers == null || markers.isEmpty()) {
            return asset;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(asset.png()));
            if (image == null) {
                return asset;
            }
            Graphics2D g = image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                MapViewport viewport = fitted(context);
                g.setFont(new Font("Segoe UI", Font.BOLD, 22));
                for (MapMarker marker : markers) {
                    if (marker == null || !Double.isFinite(marker.latitude()) || !Double.isFinite(marker.longitude())) {
                        continue;
                    }
                    int x = (int) Math.round(viewport.x(marker.longitude()));
                    int y = (int) Math.round(viewport.y(marker.latitude()));
                    g.setColor(Color.WHITE);
                    g.fillOval(x - 12, y - 12, 24, 24);
                    g.setColor(new Color(0xC4, 0x5C, 0x26));
                    g.fillOval(x - 8, y - 8, 16, 16);
                    if (marker.label() != null && !marker.label().isBlank()) {
                        g.setColor(new Color(0x0E, 0x2A, 0x47));
                        g.drawString(marker.label(), x + 14, y - 8);
                    }
                }
            } finally {
                g.dispose();
            }
            return RouteMapReportAsset.image(png(image), context);
        } catch (RuntimeException | IOException ex) {
            LOG.warning("Event marker was not drawn: "
                    + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            return asset;
        }
    }

    RouteMapReportAsset render(RouteMapReportContext context, boolean forceFailure) {
        return render(context, forceFailure, false);
    }

    RouteMapReportAsset render(RouteMapReportContext context, boolean forceFailure, boolean tiles) {
        if (context == null || !context.hasRoute()) {
            return null;
        }
        try {
            if (forceFailure) {
                throw new IllegalStateException("map render failed");
            }
            return RouteMapReportAsset.image(png(paint(models(context), tiles)), context);
        } catch (RuntimeException | IOException ex) {
            LOG.warning("Route map image was not created: "
                    + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
            return RouteMapReportAsset.unavailable(context);
        }
    }

    static BufferedImage paint(RouteMapReportContext context) {
        return paint(context, false);
    }

    static BufferedImage paint(RouteMapReportContext context, boolean tiles) {
        return paint(models(context), tiles);
    }

    private static BufferedImage paint(List<MapRouteModel> models, boolean tiles) {
        MapViewport viewport = new MapViewport();
        viewport.resize(WIDTH, HEIGHT);
        viewport.fitReport(models, 0);
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            boolean basemap = tiles && ReportMapTiles.paint(g, viewport, WIDTH, HEIGHT);
            if (!basemap) {
                paintGrid(g, viewport);
            }
            g.setStroke(new BasicStroke(12f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(CASING);
            for (MapRouteModel model : models) {
                g.draw(path(viewport, model));
            }
            g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(ROUTE);
            for (MapRouteModel model : models) {
                g.draw(path(viewport, model));
            }
            for (MapRouteModel model : models) {
                mark(g, viewport, model, MapViewportCalculator.firstIncluded(model), START);
                mark(g, viewport, model, MapViewportCalculator.lastIncluded(model), END);
            }
            if (basemap) {
                g.setFont(new Font("Segoe UI", Font.PLAIN, 13));
                g.setColor(new Color(0x0E, 0x2A, 0x47));
                g.drawString("\u00a9 OpenStreetMap", 16, HEIGHT - 18);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static List<MapRouteModel> models(RouteMapReportContext context) {
        List<MapRouteModel> models = new ArrayList<>();
        for (RouteSegment segment : context.route().gpsSegments()) {
            if (segment.hasRoute()) {
                models.add(segment.route().model());
            }
        }
        return models;
    }

    static MapViewport fitted(RouteMapReportContext context) {
        List<MapRouteModel> models = new ArrayList<>();
        for (RouteSegment segment : context.route().gpsSegments()) {
            if (segment.hasRoute()) {
                models.add(segment.route().model());
            }
        }
        MapViewport viewport = new MapViewport();
        viewport.resize(WIDTH, HEIGHT);
        viewport.fitReport(models, 0);
        return viewport;
    }

    private static void paintGrid(Graphics2D g, MapViewport viewport) {
        g.setColor(SURFACE);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        double step = niceStep(viewport.degreesPerPixel() * 90);
        double top = viewport.latitudeAt(0);
        double bottom = viewport.latitudeAt(HEIGHT);
        for (double lat = Math.ceil(bottom / step) * step; lat <= top; lat += step) {
            int y = (int) Math.floor(viewport.y(lat));
            g.setColor(major(lat, step) ? GRID_MAJOR : GRID);
            g.drawLine(0, y, WIDTH, y);
        }
        double left = viewport.longitudeAt(0);
        double right = viewport.longitudeAt(WIDTH);
        for (double lon = Math.ceil(left / step) * step; lon <= right; lon += step) {
            int x = (int) Math.floor(viewport.x(lon));
            g.setColor(major(lon, step) ? GRID_MAJOR : GRID);
            g.drawLine(x, 0, x, HEIGHT);
        }
    }

    /**
     * One path per measurement. A GPS gap lifts the pen. The next measurement starts a new path,
     * so files are never joined.
     */
    private static Path2D path(MapViewport viewport, MapRouteModel model) {
        Path2D path = new Path2D.Double();
        boolean open = false;
        double lastX = 0;
        double lastY = 0;
        for (int i = 0; i < model.size(); i++) {
            double x = viewport.x(model.longitude(i));
            double y = viewport.y(model.latitude(i));
            boolean broken = i > 0 && model.segmentBreakBefore(i);
            if (!open || broken) {
                path.moveTo(x, y);
                open = true;
            } else if (Math.hypot(x - lastX, y - lastY) < 1.2 && i < model.size() - 1) {
                continue;
            } else {
                path.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }
        return path;
    }

    private static void mark(Graphics2D g, MapViewport viewport, MapRouteModel model, int index, Color fill) {
        if (index < 0 || index >= model.size()) {
            return;
        }
        int x = (int) Math.round(viewport.x(model.longitude(index)));
        int y = (int) Math.round(viewport.y(model.latitude(index)));
        g.setColor(Color.WHITE);
        g.fillOval(x - 11, y - 11, 22, 22);
        g.setColor(fill);
        g.fillOval(x - 7, y - 7, 14, 14);
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("PNG writer is not available.");
        }
        return out.toByteArray();
    }

    private static boolean major(double value, double step) {
        return Math.abs(Math.IEEEremainder(value, step * 5)) < step * 0.1;
    }

    private static double niceStep(double raw) {
        if (!(raw > 0) || !Double.isFinite(raw)) {
            return 0.01;
        }
        double exponent = Math.floor(Math.log10(raw));
        double magnitude = Math.pow(10, exponent);
        double normalized = raw / magnitude;
        double factor = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
        return factor * magnitude;
    }

    static String relativeAsset() {
        return "assets/" + InvestigationStoragePaths.ROUTE_MAP_ASSET;
    }
}
