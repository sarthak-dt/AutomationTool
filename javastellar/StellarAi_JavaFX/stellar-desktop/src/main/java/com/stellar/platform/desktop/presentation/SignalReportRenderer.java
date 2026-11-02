package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.report.SignalAnalysisSection;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.parser.spi.CancellationToken;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws stacked signal tracks for a report. Each track has its own Y scale.
 * The image is rendered at report resolution; it is not a screenshot of the application window.
 */
public final class SignalReportRenderer {

    public static final int TRACKS_PER_IMAGE = 6;
    public static final int WIDTH = 1400;
    public static final String TIME_AXIS = "Time (s)";
    /** Right plot padding. Left margin grows from {@link #LEFT_MIN} so tick text is not clipped. */
    private static final int RIGHT = 40;
    private static final int LEFT_MIN = 112;
    private static final int TITLE = 36;
    private static final int AXIS_BAND = 80;
    private static final int OUTER = 16;
    private static final Color INK = new Color(0x0E, 0x2A, 0x47);
    private static final Color AXIS = new Color(0x3D, 0x51, 0x66);
    private static final Color GRID = new Color(0xE3, 0xEA, 0xF1);
    private static final Color[] SERIES = {
            new Color(0x16, 0x77, 0xFF),
            new Color(0x1B, 0x7F, 0x4E),
            new Color(0xC4, 0x5C, 0x26),
            new Color(0x6B, 0x4E, 0xC2),
            new Color(0x0E, 0x6E, 0x8C),
            new Color(0xA1, 0x26, 0x22)
    };

    private SignalReportRenderer() {
    }

    /**
     * A presentation mark on the shared time axis. A window uses both times.
     * A point uses only {@code startSeconds}. This does not change sample values.
     */
    public record EventSpan(String label, double startSeconds, double endSeconds) {
        public boolean window() {
            return Double.isFinite(startSeconds) && Double.isFinite(endSeconds) && endSeconds >= startSeconds;
        }

        public boolean point() {
            return Double.isFinite(startSeconds) && !window();
        }
    }

    public record Track(
            String name,
            String unit,
            double[] timeSeconds,
            double[] values,
            double domainStart,
            double domainEnd,
            SignalValidity[] validity
    ) {
        public Track(String name, String unit, double[] timeSeconds, double[] values,
                     double domainStart, double domainEnd) {
            this(name, unit, timeSeconds, values, domainStart, domainEnd, null);
        }

        public Track(String name, String unit, double[] timeSeconds, double[] values) {
            this(name, unit, timeSeconds, values, Double.NaN, Double.NaN);
        }

        public Track(String name, String unit, double[] values) {
            this(name, unit, null, values);
        }
    }

    public record PlacedLabel(String text, int x, int y, int width, int height) {
    }

    public record TrackFrame(
            String title,
            String unit,
            int plotLeft,
            int plotTop,
            int plotWidth,
            int plotHeight,
            double yMin,
            double yMax,
            double[] yTicks,
            double xMin,
            double xMax,
            double[] xTicks,
            List<PlacedLabel> labels
    ) {
    }

    public record ChartLayout(int width, int height, List<TrackFrame> tracks, List<PlacedLabel> labels) {
    }

    public static List<SignalReportImage> render(List<Track> tracks, CancellationToken token) {
        return render(tracks, List.of(), token);
    }

    /**
     * Same stacked waveform as {@link #render(List, CancellationToken)}.
     * Included graph events are drawn on that time axis. Sample arrays are not changed.
     */
    public static List<SignalReportImage> render(
            List<Track> tracks, List<EventSpan> events, CancellationToken token
    ) {
        if (tracks == null || tracks.isEmpty()) {
            return List.of();
        }
        List<SignalReportImage> images = new ArrayList<>();
        int page = 1;
        int pageCount = (tracks.size() + TRACKS_PER_IMAGE - 1) / TRACKS_PER_IMAGE;
        for (int from = 0; from < tracks.size(); from += TRACKS_PER_IMAGE) {
            if (token != null && token.isCancellationRequested()) {
                throw new com.stellar.platform.analysis.source.AnalysisCancelledException();
            }
            int to = Math.min(tracks.size(), from + TRACKS_PER_IMAGE);
            List<Track> slice = tracks.subList(from, to);
            String file = pageCount == 1 ? "signals-waveform.png"
                    : String.format(Locale.ROOT, "signals-waveform-%02d.png", page);
            try {
                images.add(new SignalReportImage(file, png(paint(slice, events)), caption(slice), false));
            } catch (RuntimeException | IOException ex) {
                images.add(new SignalReportImage(file, null, caption(slice), true));
            }
            page++;
        }
        return List.copyOf(images);
    }

    public static List<SignalAnalysisSection> sections(List<SignalReportImage> images) {
        if (images == null || images.isEmpty()) {
            return List.of();
        }
        List<SignalAnalysisSection> out = new ArrayList<>();
        for (SignalReportImage image : images) {
            if (image.hasImage()) {
                out.add(new SignalAnalysisSection(image.title(), "assets/" + image.fileName(), ""));
            } else {
                out.add(new SignalAnalysisSection(image.title(), "", SignalAnalysisSection.UNAVAILABLE));
            }
        }
        return List.copyOf(out);
    }

    public static ChartLayout layout(List<Track> tracks) {
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = probe.createGraphics();
        try {
            g.setFont(tickFont());
            return layout(g, tracks);
        } finally {
            g.dispose();
        }
    }

    static BufferedImage paint(List<Track> tracks) {
        return paint(tracks, List.of());
    }

    static BufferedImage paint(List<Track> tracks, List<EventSpan> events) {
        ChartLayout chart = layout(tracks);
        BufferedImage image = new BufferedImage(chart.width(), chart.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(new Color(0xF7, 0xF9, 0xFC));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setFont(tickFont());
            FontMetrics ticks = g.getFontMetrics();
            for (int i = 0; i < chart.tracks().size(); i++) {
                TrackFrame frame = chart.tracks().get(i);
                g.setFont(titleFont());
                g.setColor(INK);
                g.drawString(fit(g.getFontMetrics(), frame.title(), frame.plotWidth()),
                        frame.plotLeft(), frame.plotTop() - 12);
                g.setFont(tickFont());
                g.setColor(Color.WHITE);
                g.fillRect(frame.plotLeft(), frame.plotTop(), frame.plotWidth(), frame.plotHeight());
                g.setColor(GRID);
                for (double tick : frame.yTicks()) {
                    int y = yOf(frame, tick);
                    g.drawLine(frame.plotLeft(), y, frame.plotLeft() + frame.plotWidth(), y);
                }
                for (double tick : frame.xTicks()) {
                    int x = xOf(frame, tick);
                    g.drawLine(x, frame.plotTop(), x, frame.plotTop() + frame.plotHeight());
                }
                g.setColor(AXIS);
                g.setStroke(new BasicStroke(1.8f));
                g.drawLine(frame.plotLeft(), frame.plotTop(), frame.plotLeft(),
                        frame.plotTop() + frame.plotHeight());
                g.drawLine(frame.plotLeft(), frame.plotTop() + frame.plotHeight(),
                        frame.plotLeft() + frame.plotWidth(), frame.plotTop() + frame.plotHeight());
                drawSeries(g, tracks.get(i), frame, SERIES[i % SERIES.length]);
                drawEventSpans(g, frame, events);
                g.setColor(AXIS);
                g.setFont(tickFont());
                for (PlacedLabel label : frame.labels()) {
                    g.drawString(label.text(), label.x(), label.y() + ticks.getAscent());
                }
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static ChartLayout layout(Graphics2D g, List<Track> tracks) {
        g.setFont(tickFont());
        FontMetrics metrics = g.getFontMetrics();
        int left = LEFT_MIN;
        List<double[]> domains = new ArrayList<>();
        for (Track track : tracks) {
            double[] domain = yDomain(track.values(), track.validity());
            domains.add(domain);
            double[] yTicks = ticks(domain[0], domain[1], 5);
            double step = tickStep(yTicks, domain[1] - domain[0]);
            for (double tick : yTicks) {
                left = Math.max(left, metrics.stringWidth(formatTick(tick, step)) + 28);
            }
        }
        int plotWidth = WIDTH - left - RIGHT;
        int plotHeight = plotHeight(tracks.size());
        List<TrackFrame> frames = new ArrayList<>();
        List<PlacedLabel> all = new ArrayList<>();
        int top = OUTER;
        for (int i = 0; i < tracks.size(); i++) {
            Track track = tracks.get(i);
            double[] domain = domains.get(i);
            double[] yTicks = ticks(domain[0], domain[1], 5);
            double[] time = timeAxis(track);
            double[] xTicks = ticks(time[0], time[1], 7);
            int plotTop = top + TITLE;
            List<PlacedLabel> labels = new ArrayList<>();
            double yStep = tickStep(yTicks, domain[1] - domain[0]);
            for (double tick : yTicks) {
                String text = formatTick(tick, yStep);
                int width = metrics.stringWidth(text);
                int y = yOf(plotTop, plotHeight, domain[0], domain[1], tick) - metrics.getAscent() / 2;
                labels.add(new PlacedLabel(text, left - 12 - width, y, width, metrics.getHeight()));
            }
            int axisY = plotTop + plotHeight;
            double xStep = tickStep(xTicks, time[1] - time[0]);
            for (double tick : xTicks) {
                String text = formatTick(tick, xStep);
                int width = metrics.stringWidth(text);
                int x = xOf(left, plotWidth, time[0], time[1], tick) - width / 2;
                x = Math.max(4, Math.min(WIDTH - width - 4, x));
                labels.add(new PlacedLabel(text, x, axisY + 6, width, metrics.getHeight()));
            }
            String timeTitle = TIME_AXIS;
            int titleWidth = metrics.stringWidth(timeTitle);
            int titleX = left + (plotWidth - titleWidth) / 2;
            labels.add(new PlacedLabel(timeTitle, titleX, axisY + 6 + metrics.getHeight(),
                    titleWidth, metrics.getHeight()));
            TrackFrame frame = new TrackFrame(
                    heading(track), blank(track.unit()), left, plotTop, plotWidth, plotHeight,
                    domain[0], domain[1], yTicks, time[0], time[1], xTicks, List.copyOf(labels));
            frames.add(frame);
            all.addAll(labels);
            top = axisY + AXIS_BAND;
        }
        return new ChartLayout(WIDTH, top + OUTER, List.copyOf(frames), List.copyOf(all));
    }

    private static void drawSeries(Graphics2D g, Track track, TrackFrame frame, Color color) {
        double[] values = track.values();
        if (values == null || values.length == 0) {
            return;
        }
        double[] time = times(track);
        g.setColor(color);
        g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int prevX = 0;
        int prevY = 0;
        boolean penDown = false;
        boolean connected = false;
        for (int i = 0; i < values.length; i++) {
            if (!usablePoint(values[i], validityAt(track.validity(), i))) {
                if (penDown && !connected) {
                    g.fillOval(prevX - 3, prevY - 3, 6, 6);
                }
                penDown = false;
                connected = false;
                continue;
            }
            int x = xOf(frame, time[i]);
            int y = yOf(frame, values[i]);
            if (!penDown) {
                prevX = x;
                prevY = y;
                penDown = true;
                connected = false;
                continue;
            }
            g.drawLine(prevX, prevY, x, y);
            prevX = x;
            prevY = y;
            connected = true;
        }
        if (penDown && !connected) {
            g.fillOval(prevX - 3, prevY - 3, 6, 6);
        }
    }

    static int strokeSegmentCount(double[] values, SignalValidity[] validity) {
        int segments = 0;
        boolean open = false;
        if (values == null) {
            return 0;
        }
        for (int i = 0; i < values.length; i++) {
            if (!usablePoint(values[i], validityAt(validity, i))) {
                open = false;
                continue;
            }
            if (!open) {
                segments++;
                open = true;
            }
        }
        return segments;
    }

    private static SignalValidity validityAt(SignalValidity[] validity, int index) {
        if (validity == null || index < 0 || index >= validity.length || validity[index] == null) {
            return null;
        }
        return validity[index];
    }

    private static boolean usablePoint(double value, SignalValidity validity) {
        if (validity == null) {
            return Double.isFinite(value);
        }
        return validity.usableWith(value);
    }

    static double[] yDomain(double[] values) {
        return yDomain(values, null);
    }

    static double[] yDomain(double[] values, SignalValidity[] validity) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        if (values != null) {
            for (int i = 0; i < values.length; i++) {
                double value = values[i];
                if (usablePoint(value, validityAt(validity, i))) {
                    min = Math.min(min, value);
                    max = Math.max(max, value);
                }
            }
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            return new double[]{-1, 1};
        }
        if (max == min) {
            double pad = min == 0 ? 1.0 : Math.max(Math.abs(min) * 0.1, 1.0);
            return new double[]{min - pad, max + pad};
        }
        double pad = (max - min) * 0.08;
        return new double[]{min - pad, max + pad};
    }

    static double[] ticks(double min, double max, int desired) {
        if (!(max > min)) {
            return new double[]{min};
        }
        double step = niceStep((max - min) / Math.max(1, desired));
        double start = Math.ceil((min - step * 1e-9) / step) * step;
        List<Double> out = new ArrayList<>();
        for (double value = start; value <= max + step * 1e-6 && out.size() < 10; value += step) {
            if (value >= min - step * 1e-6) {
                out.add(Math.abs(value) < step * 1e-6 ? 0.0 : value);
            }
        }
        if (out.size() < 2) {
            return new double[]{min, max};
        }
        double[] ticks = new double[out.size()];
        for (int i = 0; i < out.size(); i++) {
            ticks[i] = out.get(i);
        }
        return ticks;
    }

    static String formatTick(double value, double step) {
        double abs = Math.abs(value);
        double safeStep = step > 0 && Double.isFinite(step) ? step : 1d;
        if (abs != 0 && (abs >= 1_000_000d || (abs < 0.001d && safeStep < 0.001d))) {
            return String.format(Locale.ROOT, "%.3g", value);
        }
        int decimals = 0;
        if (safeStep < 1d) {
            decimals = (int) Math.min(3, Math.ceil(-Math.log10(safeStep) - 1e-9));
        }
        if (decimals == 0) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private static int plotHeight(int tracks) {
        if (tracks <= 1) {
            return 452;
        }
        if (tracks == 2) {
            return 246;
        }
        if (tracks <= 4) {
            return 132;
        }
        return 108;
    }

    private static double tickStep(double[] ticks, double span) {
        if (ticks != null && ticks.length >= 2) {
            return Math.abs(ticks[1] - ticks[0]);
        }
        return Math.max(span, 1e-9);
    }

    private static double[] timeAxis(Track track) {
        if (Double.isFinite(track.domainStart()) && Double.isFinite(track.domainEnd())
                && track.domainEnd() > track.domainStart()) {
            return new double[]{track.domainStart(), track.domainEnd()};
        }
        double[] time = times(track);
        double min = time[0];
        double max = time[time.length - 1];
        for (double value : time) {
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        if (!(max > min)) {
            return new double[]{min, min + 1.0};
        }
        return new double[]{min, max};
    }

    private static double[] times(Track track) {
        double[] values = track.values() == null ? new double[0] : track.values();
        double[] given = track.timeSeconds();
        if (given != null && given.length == values.length && values.length > 0) {
            return given;
        }
        double[] time = new double[Math.max(1, values.length)];
        for (int i = 0; i < time.length; i++) {
            time[i] = i;
        }
        return time;
    }

    private static int yOf(TrackFrame frame, double value) {
        return yOf(frame.plotTop(), frame.plotHeight(), frame.yMin(), frame.yMax(), value);
    }

    private static int yOf(int plotTop, int plotHeight, double min, double max, double value) {
        double span = Math.max(1e-12, max - min);
        double fraction = (value - min) / span;
        fraction = Math.max(0, Math.min(1, fraction));
        return plotTop + plotHeight - (int) Math.round(fraction * (plotHeight - 1));
    }

    private static int xOf(TrackFrame frame, double time) {
        return xOf(frame.plotLeft(), frame.plotWidth(), frame.xMin(), frame.xMax(), time);
    }

    private static int xOf(int plotLeft, int plotWidth, double min, double max, double time) {
        double span = Math.max(1e-12, max - min);
        double fraction = (time - min) / span;
        fraction = Math.max(0, Math.min(1, fraction));
        return plotLeft + (int) Math.round(fraction * (plotWidth - 1));
    }

    private static String heading(Track track) {
        String name = track.name() == null || track.name().isBlank() ? "Signal" : track.name();
        if (track.unit() == null || track.unit().isBlank()) {
            return name;
        }
        return name + " [" + track.unit() + "]";
    }

    private static String caption(List<Track> tracks) {
        StringBuilder names = new StringBuilder();
        for (Track track : tracks) {
            if (!names.isEmpty()) {
                names.append(", ");
            }
            names.append(track.name() == null || track.name().isBlank() ? "Signal" : track.name());
        }
        String text = names.toString();
        if (text.length() > 120) {
            text = text.substring(0, 117) + "...";
        }
        return "Signal Analysis — " + text;
    }

    private static String blank(String unit) {
        return unit == null ? "" : unit;
    }

    private static Font titleFont() {
        return new Font("Segoe UI", Font.BOLD, 18);
    }

    private static Font tickFont() {
        return new Font("Segoe UI", Font.PLAIN, 15);
    }

    private static double niceStep(double raw) {
        if (!(raw > 0) || !Double.isFinite(raw)) {
            return 1;
        }
        double exponent = Math.floor(Math.log10(raw));
        double magnitude = Math.pow(10, exponent);
        double normalized = raw / magnitude;
        double factor = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
        return factor * magnitude;
    }

    /**
     * Stacks selected signals on one time axis. Signals that share a unit share one panel
     * and a legend. A different unit, or a blank unit, stays on its own panel.
     * Event marks are drawn only when they intersect that panel's time axis.
     */
    public static ChartLayout alignedLayout(List<Track> tracks) {
        return layoutAligned(tracks == null ? List.of() : tracks);
    }

    public static List<SignalReportImage> renderAligned(
            List<Track> tracks, List<EventSpan> events, CancellationToken token
    ) {
        if (tracks == null || tracks.isEmpty()) {
            return List.of();
        }
        if (token != null && token.isCancellationRequested()) {
            throw new com.stellar.platform.analysis.source.AnalysisCancelledException();
        }
        try {
            BufferedImage image = paintAligned(tracks, events);
            return List.of(new SignalReportImage("signals-comparison.png", png(image), caption(tracks), false));
        } catch (RuntimeException | IOException ex) {
            return List.of(new SignalReportImage("signals-comparison.png", null, caption(tracks), true));
        }
    }

    public static BufferedImage paintAligned(List<Track> tracks, List<EventSpan> events) {
        ChartLayout chart = layoutAligned(tracks);
        List<List<Track>> groups = groupByUnit(tracks);
        BufferedImage image = new BufferedImage(chart.width(), chart.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(0xF7, 0xF9, 0xFC));
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
            g.setFont(tickFont());
            FontMetrics ticks = g.getFontMetrics();
            for (int i = 0; i < chart.tracks().size(); i++) {
                TrackFrame frame = chart.tracks().get(i);
                List<Track> group = groups.get(i);
                g.setFont(titleFont());
                g.setColor(INK);
                int legendRows = group.size() > 1 ? group.size() : 0;
                g.drawString(fit(g.getFontMetrics(), frame.title(), frame.plotWidth()),
                        frame.plotLeft(), frame.plotTop() - 12 - legendRows * 18);
                if (legendRows > 0) {
                    int legendY = frame.plotTop() - 8 - (legendRows - 1) * 18;
                    for (int series = 0; series < group.size(); series++) {
                        g.setColor(SERIES[series % SERIES.length]);
                        g.fillRect(frame.plotLeft(), legendY - 10, 18, 4);
                        g.setColor(INK);
                        g.setFont(tickFont());
                        String name = group.get(series).name() == null ? "" : group.get(series).name();
                        String unit = group.get(series).unit() == null || group.get(series).unit().isBlank()
                                ? "" : " [" + group.get(series).unit() + "]";
                        g.drawString(fit(g.getFontMetrics(), name + unit, frame.plotWidth() - 28),
                                frame.plotLeft() + 24, legendY);
                        legendY += 18;
                    }
                }
                g.setFont(tickFont());
                g.setColor(Color.WHITE);
                g.fillRect(frame.plotLeft(), frame.plotTop(), frame.plotWidth(), frame.plotHeight());
                g.setColor(GRID);
                for (double tick : frame.yTicks()) {
                    int y = yOf(frame, tick);
                    g.drawLine(frame.plotLeft(), y, frame.plotLeft() + frame.plotWidth(), y);
                }
                for (double tick : frame.xTicks()) {
                    int x = xOf(frame, tick);
                    g.drawLine(x, frame.plotTop(), x, frame.plotTop() + frame.plotHeight());
                }
                drawEventSpans(g, frame, events);
                for (int series = 0; series < group.size(); series++) {
                    drawSeries(g, group.get(series), frame, SERIES[series % SERIES.length]);
                }
                g.setColor(AXIS);
                g.setStroke(new BasicStroke(1.8f));
                g.drawLine(frame.plotLeft(), frame.plotTop(), frame.plotLeft(),
                        frame.plotTop() + frame.plotHeight());
                g.drawLine(frame.plotLeft(), frame.plotTop() + frame.plotHeight(),
                        frame.plotLeft() + frame.plotWidth(), frame.plotTop() + frame.plotHeight());
                g.setFont(tickFont());
                for (PlacedLabel label : frame.labels()) {
                    g.setColor(AXIS);
                    g.drawString(label.text(), label.x(), label.y() + ticks.getAscent());
                }
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private static ChartLayout layoutAligned(List<Track> tracks) {
        List<List<Track>> groups = groupByUnit(tracks);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = probe.createGraphics();
        try {
            g.setFont(tickFont());
            FontMetrics metrics = g.getFontMetrics();
            int left = LEFT_MIN;
            for (List<Track> group : groups) {
                double[] domain = combinedDomain(group);
                double[] yTicks = ticks(domain[0], domain[1], 4);
                double step = tickStep(yTicks, domain[1] - domain[0]);
                for (double tick : yTicks) {
                    left = Math.max(left, metrics.stringWidth(formatTick(tick, step)) + 28);
                }
            }
            int plotWidth = WIDTH - left - RIGHT;
            int height = plotHeight(Math.max(1, groups.size()));
            List<TrackFrame> frames = new ArrayList<>();
            List<PlacedLabel> labels = new ArrayList<>();
            int top = OUTER;
            for (List<Track> group : groups) {
                int legendRows = group.size() > 1 ? group.size() : 0;
                top += TITLE + legendRows * 18;
                double[] y = combinedDomain(group);
                double[] x = combinedTime(group);
                double[] yTicks = ticks(y[0], y[1], 4);
                double[] xTicks = ticks(x[0], x[1], 5);
                List<PlacedLabel> frameLabels = new ArrayList<>();
                for (double tick : yTicks) {
                    String text = formatTick(tick, tickStep(yTicks, y[1] - y[0]));
                    int yPix = yOf(top, height, y[0], y[1], tick);
                    int width = metrics.stringWidth(text);
                    frameLabels.add(new PlacedLabel(text, Math.max(4, left - width - 8),
                            yPix - metrics.getAscent() / 2, width, metrics.getHeight()));
                }
                for (double tick : xTicks) {
                    String text = formatTick(tick, tickStep(xTicks, x[1] - x[0]));
                    int width = metrics.stringWidth(text);
                    int xPix = xOf(left, plotWidth, x[0], x[1], tick) - width / 2;
                    xPix = Math.max(4, Math.min(WIDTH - width - 4, xPix));
                    frameLabels.add(new PlacedLabel(text, xPix,
                            top + height + 6, width, metrics.getHeight()));
                }
                String axis = TIME_AXIS;
                frameLabels.add(new PlacedLabel(axis, left + plotWidth / 2 - metrics.stringWidth(axis) / 2,
                        top + height + 28, metrics.stringWidth(axis), metrics.getHeight()));
                String title = group.size() == 1 ? heading(group.getFirst()) : group.getFirst().unit();
                frames.add(new TrackFrame(title, group.getFirst().unit(), left, top, plotWidth, height,
                        y[0], y[1], yTicks, x[0], x[1], xTicks, frameLabels));
                labels.addAll(frameLabels);
                top += height + AXIS_BAND;
            }
            return new ChartLayout(WIDTH, Math.max(top + OUTER, 80), frames, labels);
        } finally {
            g.dispose();
        }
    }

    private static List<List<Track>> groupByUnit(List<Track> tracks) {
        List<List<Track>> groups = new ArrayList<>();
        if (tracks == null) {
            return groups;
        }
        for (Track track : tracks) {
            if (track == null) {
                continue;
            }
            if (!groups.isEmpty() && sameUnit(groups.getLast().getFirst(), track)) {
                groups.getLast().add(track);
            } else {
                List<Track> group = new ArrayList<>();
                group.add(track);
                groups.add(group);
            }
        }
        return groups;
    }

    private static boolean sameUnit(Track left, Track right) {
        String a = left.unit() == null ? "" : left.unit().trim();
        String b = right.unit() == null ? "" : right.unit().trim();
        return !a.isEmpty() && a.equals(b);
    }

    private static double[] combinedDomain(List<Track> group) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Track track : group) {
            double[] values = track.values();
            if (values == null) {
                continue;
            }
            for (int i = 0; i < values.length; i++) {
                if (!usablePoint(values[i], validityAt(track.validity(), i))) {
                    continue;
                }
                min = Math.min(min, values[i]);
                max = Math.max(max, values[i]);
            }
        }
        if (!Double.isFinite(min) || !Double.isFinite(max)) {
            return new double[]{-1, 1};
        }
        return yDomain(new double[]{min, max});
    }

    private static double[] combinedTime(List<Track> group) {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Track track : group) {
            double[] axis = timeAxis(track);
            min = Math.min(min, axis[0]);
            max = Math.max(max, axis[1]);
        }
        if (!Double.isFinite(min) || !Double.isFinite(max) || !(max > min)) {
            return new double[]{0, 1};
        }
        return new double[]{min, max};
    }

    private static void drawEventSpans(Graphics2D g, TrackFrame frame, List<EventSpan> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        int drawn = 0;
        for (int i = 0; i < events.size(); i++) {
            EventSpan event = events.get(i);
            if (event == null) {
                continue;
            }
            Color ink = EVENT_INK[drawn % EVENT_INK.length];
            if (event.window()) {
                if (event.endSeconds() < frame.xMin() || event.startSeconds() > frame.xMax()) {
                    continue;
                }
                int x0 = xOf(frame, Math.max(event.startSeconds(), frame.xMin()));
                int x1 = xOf(frame, Math.min(event.endSeconds(), frame.xMax()));
                if (x1 < x0) {
                    int swap = x0;
                    x0 = x1;
                    x1 = swap;
                }
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.28f));
                g.setColor(ink);
                g.fillRect(x0, frame.plotTop(), Math.max(2, x1 - x0), frame.plotHeight());
                g.setComposite(AlphaComposite.SrcOver);
                drawEventLabel(g, frame, event.label(), x0, drawn);
                drawn++;
            } else if (event.point() && event.startSeconds() >= frame.xMin()
                    && event.startSeconds() <= frame.xMax()) {
                int x = xOf(frame, event.startSeconds());
                g.setColor(ink);
                g.fillRect(x - 1, frame.plotTop(), 3, frame.plotHeight());
                drawEventLabel(g, frame, event.label(), x + 4, drawn);
                drawn++;
            }
        }
    }

    private static void drawEventLabel(Graphics2D g, TrackFrame frame, String label, int x, int index) {
        if (label == null || label.isBlank()) {
            return;
        }
        if (index > 2) {
            return;
        }
        g.setFont(tickFont());
        FontMetrics metrics = g.getFontMetrics();
        g.setComposite(AlphaComposite.SrcOver);
        String text = fit(metrics, label, Math.max(40, frame.plotWidth() - 12));
        int y = frame.plotTop() + 14 + index * 16;
        int textX = Math.max(frame.plotLeft() + 4,
                Math.min(x + 4, frame.plotLeft() + frame.plotWidth() - metrics.stringWidth(text) - 4));
        g.setColor(new Color(255, 255, 255, 220));
        g.fillRect(textX - 2, y - metrics.getAscent(), metrics.stringWidth(text) + 4, metrics.getHeight());
        g.setColor(INK);
        g.drawString(text, textX, y);
    }

    private static String fit(FontMetrics metrics, String text, int width) {
        if (text == null || text.isBlank() || width < 12) {
            return text == null ? "" : text;
        }
        if (metrics.stringWidth(text) <= width) {
            return text;
        }
        String trimmed = text;
        while (trimmed.length() > 1 && metrics.stringWidth(trimmed + "...") > width) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed + "...";
    }

    private static final Color[] EVENT_INK = {
            new Color(0xE6, 0xA8, 0x17),
            new Color(0x6B, 0x4E, 0xC2),
            new Color(0xA1, 0x26, 0x22)
    };

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("PNG writer is not available.");
        }
        return out.toByteArray();
    }
}
