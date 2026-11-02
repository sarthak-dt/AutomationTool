package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportChartAxesTest {

    @Test
    void oneSignalHasTimeAxisUnitAndReportResolution() throws Exception {
        SignalReportRenderer.Track track = new SignalReportRenderer.Track(
                "EngineSpeed", "rpm",
                new double[]{0, 5, 10, 15, 20},
                new double[]{800, 1600, 2400, 1800, 1200});
        SignalReportRenderer.ChartLayout layout = SignalReportRenderer.layout(List.of(track));
        BufferedImage image = SignalReportRenderer.paint(List.of(track));
        assertTrue(image.getWidth() >= 1400);
        assertTrue(image.getHeight() >= 500);
        assertTrue(image.getHeight() <= 640);
        assertEquals(image.getWidth(), layout.width());
        assertEquals(image.getHeight(), layout.height());
        SignalReportRenderer.TrackFrame frame = layout.tracks().getFirst();
        assertEquals("rpm", frame.unit());
        assertTrue(frame.title().contains("EngineSpeed"));
        assertTrue(frame.title().contains("rpm"));
        assertTrue(frame.yTicks().length >= 4 && frame.yTicks().length <= 7);
        assertTrue(frame.xTicks().length >= 4 && frame.xTicks().length <= 10);
        assertTrue(labels(frame).stream().anyMatch(label -> "Time (s)".equals(label.text())));
        assertTrue(labels(frame).stream().anyMatch(label -> label.text().contains("800") || label.text().contains("2400")
                || frame.yMax() > 2000));
        assertNoClipping(layout);
        java.nio.file.Path preview = java.nio.file.Path.of("target", "report-preview");
        java.nio.file.Files.createDirectories(preview);
        ImageIO.write(image, "png", preview.resolve("signals-one.png").toFile());
        ImageIO.write(SignalReportRenderer.paint(List.of(
                new SignalReportRenderer.Track("EngineSpeed", "rpm", new double[]{800, 4000, 2200}),
                new SignalReportRenderer.Track("VehicleSpeed", "km/h", new double[]{0, 80, 40}),
                new SignalReportRenderer.Track("Coolant", "°C", new double[]{70, 90, 85}),
                new SignalReportRenderer.Track("Throttle", "%", new double[]{0, 40, 10})
        )), "png", preview.resolve("signals-four.png").toFile());
    }

    @Test
    void fourSignalsKeepIndependentUnits() {
        List<SignalReportRenderer.Track> tracks = List.of(
                new SignalReportRenderer.Track("EngineSpeed", "rpm", new double[]{800, 4000}),
                new SignalReportRenderer.Track("VehicleSpeed", "km/h", new double[]{0, 120}),
                new SignalReportRenderer.Track("Coolant", "°C", new double[]{70, 95}),
                new SignalReportRenderer.Track("Throttle", "%", new double[]{0, 100}));
        SignalReportRenderer.ChartLayout layout = SignalReportRenderer.layout(tracks);
        BufferedImage image = SignalReportRenderer.paint(tracks);
        assertEquals(4, layout.tracks().size());
        assertTrue(image.getHeight() >= 700);
        assertTrue(image.getHeight() <= 1100);
        assertEquals("rpm", layout.tracks().get(0).unit());
        assertEquals("km/h", layout.tracks().get(1).unit());
        assertTrue(layout.tracks().get(0).yMax() > layout.tracks().get(1).yMax());
        assertNoClipping(layout);
    }

    @Test
    void constantSignalKeepsAReadableScale() {
        SignalReportRenderer.ChartLayout layout = SignalReportRenderer.layout(List.of(
                new SignalReportRenderer.Track("ETC_Fan1_output", "", new double[]{0, 0, 0, 0})));
        SignalReportRenderer.TrackFrame frame = layout.tracks().getFirst();
        assertTrue(frame.yMax() > frame.yMin());
        assertTrue(frame.yTicks().length >= 2);
        assertTrue(frame.yMin() < 0 && frame.yMax() > 0);
        assertNoClipping(layout);
    }

    @Test
    void timeRangeUsesThePlottedSamples() {
        SignalReportRenderer.ChartLayout layout = SignalReportRenderer.layout(List.of(
                new SignalReportRenderer.Track("VehicleSpeed", "km/h",
                        new double[]{12.0, 12.5, 13.0},
                        new double[]{40, 55, 48})));
        SignalReportRenderer.TrackFrame frame = layout.tracks().getFirst();
        assertEquals(12.0, frame.xMin(), 0.001);
        assertEquals(13.0, frame.xMax(), 0.001);
        for (double tick : frame.xTicks()) {
            assertTrue(tick >= frame.xMin() - 0.001 && tick <= frame.xMax() + 0.001);
        }
        assertTrue(labels(frame).stream().anyMatch(label -> "Time (s)".equals(label.text())));
        assertNoClipping(layout);
    }

    @Test
    void investigationWindowKeepsTicksInside1706To1709() {
        double start = 1706.375;
        double end = 1709.225;
        SignalReportRenderer.ChartLayout layout = SignalReportRenderer.layout(List.of(
                new SignalReportRenderer.Track("ACM_Airmeter_flow_spd", "g/s",
                        new double[]{1706.40, 1707.20, 1708.10, 1709.10},
                        new double[]{265, 800, 1367, 500},
                        start, end)));
        SignalReportRenderer.TrackFrame frame = layout.tracks().getFirst();
        assertEquals(start, frame.xMin(), 0.001);
        assertEquals(end, frame.xMax(), 0.001);
        assertTrue(frame.xTicks().length >= 4 && frame.xTicks().length <= 10);
        for (double tick : frame.xTicks()) {
            assertTrue(tick >= start - 0.001 && tick <= end + 0.001, Double.toString(tick));
        }
        assertTrue(labels(frame).stream().anyMatch(label -> "Time (s)".equals(label.text())));
        assertTrue(labels(frame).stream().anyMatch(label -> label.text().startsWith("170")));
        assertTrue(labels(frame).stream()
                .filter(label -> !"Time (s)".equals(label.text()))
                .noneMatch(label -> label.text().contains("e") || label.text().contains("E")));
        assertEquals("g/s", frame.unit());
        assertTrue(frame.yTicks().length >= 2);
        assertNoClipping(layout);
        EngineeringInvestigation investigation = EngineeringInvestigation.builder("p1")
                .range(0L, 5_000_000_000_000L)
                .graphState("", "STACKED", 1_706_375_000_000L, 1_709_225_000_000L, null, null, "")
                .build();
        long[] window = SignalReportLoader.analysisWindow(investigation);
        assertEquals(1_706_375_000_000L, window[0]);
        assertEquals(1_709_225_000_000L, window[1]);
        SignalReportLoader.Series series = SignalReportLoader.series(List.of(
                new SignalSamplePoint(1_706_400_000_000L, 265, null, null),
                new SignalSamplePoint(1_709_100_000_000L, 1367, null, null)));
        assertEquals(1706.4, series.timeSeconds()[0], 0.001);
        assertEquals(1709.1, series.timeSeconds()[1], 0.001);
    }

    private static void assertNoClipping(SignalReportRenderer.ChartLayout layout) {
        SignalReportRenderer.TrackFrame last = layout.tracks().getLast();
        int bottom = layout.height() - (last.plotTop() + last.plotHeight());
        assertTrue(bottom >= 70 && bottom <= 120, "bottom=" + bottom);
        for (SignalReportRenderer.TrackFrame frame : layout.tracks()) {
            assertTrue(frame.plotLeft() >= 100, "left=" + frame.plotLeft());
            int right = layout.width() - (frame.plotLeft() + frame.plotWidth());
            assertTrue(right >= 30 && right <= 50, "right=" + right);
            assertTrue(frame.plotLeft() + frame.plotWidth() < layout.width());
            assertTrue(frame.plotTop() > 20);
            assertTrue(frame.plotTop() + frame.plotHeight() < layout.height());
            for (SignalReportRenderer.PlacedLabel label : frame.labels()) {
                assertTrue(label.x() >= 0, label.text());
                assertTrue(label.y() >= 0, label.text());
                assertTrue(label.x() + label.width() <= layout.width(), label.text());
                assertTrue(label.y() + label.height() <= layout.height(), label.text());
                boolean yAxis = label.x() + label.width() <= frame.plotLeft();
                boolean belowPlot = label.y() >= frame.plotTop() + frame.plotHeight();
                assertTrue(yAxis || belowPlot, label.text());
            }
        }
    }

    private static List<SignalReportRenderer.PlacedLabel> labels(SignalReportRenderer.TrackFrame frame) {
        return frame.labels();
    }
}
