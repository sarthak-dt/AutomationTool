package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringFormats;
import com.stellar.platform.analysis.investigation.EngineeringObservation;
import com.stellar.platform.analysis.investigation.EngineeringReportSection;
import com.stellar.platform.analysis.investigation.EngineeringReportTemplate;
import com.stellar.platform.analysis.investigation.InvestigationStatus;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiCalculator;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiConfig;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiResult;
import com.stellar.platform.analysis.investigation.kpi.EngineeringKpiSeries;
import com.stellar.platform.analysis.investigation.report.EngineeringHtmlReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringPdfReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModel;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModelBuilder;
import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.map.MapRoute;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.ProjectRoute;
import com.stellar.platform.processing.map.RouteSegment;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * CH01 and a second thermocouple, an engineer event drawn on that shared clock,
 * and a GNSS marker only when a route sample falls inside the event.
 */
class EngineeringReportV2M10CorrelationTest {

    private static final Path ROOT = Path.of("C:/Users/DT - User/Downloads/Mahima_Time_Issue");
    private static final Path MF4 = ROOT.resolve("tata_sierra_0002391_20260921_133054 (1).mf4");
    private static final Path DBC = ROOT.resolve("TEMP DBC 1 (1).dbc");
    private static final String CH01 = "CH01_FRONT_BUMPER_GRILL_AMBIENT";
    private static final String EVENT = "Engineer temperature window";

    @Test
    void sameUnitSharesAPanelAndAPointStaysOffAnUnrelatedAxis() {
        SignalReportRenderer.Track warm = new SignalReportRenderer.Track(
                "A", "°C", new double[] {0, 1}, new double[] {1, 2});
        SignalReportRenderer.Track hotter = new SignalReportRenderer.Track(
                "B", "°C", new double[] {0, 1}, new double[] {3, 4});
        SignalReportRenderer.Track speed = new SignalReportRenderer.Track(
                "S", "rpm", new double[] {0, 1}, new double[] {100, 200});
        SignalReportRenderer.Track blank = new SignalReportRenderer.Track(
                "G", "", new double[] {0, 1}, new double[] {1, 2});
        SignalReportRenderer.Track blankToo = new SignalReportRenderer.Track(
                "H", "", new double[] {0, 1}, new double[] {3, 4});
        assertEquals(1, SignalReportRenderer.alignedLayout(List.of(warm, hotter)).tracks().size());
        assertEquals(2, SignalReportRenderer.alignedLayout(List.of(warm, speed)).tracks().size());
        assertEquals(2, SignalReportRenderer.alignedLayout(List.of(blank, blankToo)).tracks().size());
        BufferedImage image = SignalReportRenderer.paintAligned(List.of(warm), List.of(
                new SignalReportRenderer.EventSpan("Outside", 10, 12),
                new SignalReportRenderer.EventSpan("Point", 0.5, Double.NaN)));
        var frame = SignalReportRenderer.alignedLayout(List.of(warm)).tracks().getFirst();
        int outside = xOf(frame, 10);
        int point = xOf(frame, 0.5);
        assertFalse(shaded(image, Math.min(outside, image.getWidth() - 1), frame.plotTop() + 40));
        assertTrue(shaded(image, point, frame.plotTop() + 36));
    }

    @Test
    @Timeout(180)
    void eventWindowMatchesTheSourceClockAndTheGnssSample() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC),
                "Tata Sierra MF4 and TEMP DBC are not on this machine");
        WorkspaceFile measurement = new WorkspaceFile(MF4, FileKind.MEASUREMENT_MF4, Files.size(MF4));
        WorkspaceFile definition = new WorkspaceFile(DBC, FileKind.DEFINITION_DBC, Files.size(DBC));
        LocalProcessingGateway.GatewayResult processed = LocalProcessingGateway.createDefault().process(
                measurement, definition, 512, new SignalPreviewBuffer(64), ProcessingProgressListener.NOOP, null);
        try {
            SignalSeriesStore store = processed.catalogStore();
            SignalCatalogEntry ch01 = entry(store, CH01);
            SignalCatalogEntry second = secondThermocouple(store, ch01);
            SignalCatalogEntry latitude = entry(store, "LATITUDE");
            SignalCatalogEntry longitude = entry(store, "LONGITUDE");
            assertNotNull(ch01);
            assertNotNull(second);
            assertNotNull(latitude);
            assertNotNull(longitude);
            List<SignalSamplePoint> primary = readAll(store, ch01);
            List<SignalSamplePoint> other = readAll(store, second);
            double[] primaryValues = primary.stream().mapToDouble(SignalSamplePoint::physicalValue).toArray();
            double kept = primaryValues[primaryValues.length / 2];
            int rise = -1;
            int settle = -1;
            for (int i = 0; i < primary.size(); i++) {
                if (primary.get(i).usableMeasurement() && primary.get(i).physicalValue() >= 40.0) {
                    if (rise < 0) {
                        rise = i;
                    }
                    settle = i;
                }
            }
            assertTrue(rise > 0 && settle > rise);
            long startNanos = primary.get(rise).timestampNanos();
            long endNanos = primary.get(settle).timestampNanos();
            double startSeconds = startNanos / 1_000_000_000d;
            double endSeconds = endNanos / 1_000_000_000d;
            assertEquals(kept, primaryValues[primaryValues.length / 2], 0.0);

            List<EngineeringKpiSeries.Sample> firstKpi = kpiSamples(primary);
            List<EngineeringKpiSeries.Sample> secondKpi = kpiSamples(other);
            List<EngineeringKpiResult> kpis = EngineeringKpiCalculator.calculate(
                    EngineeringKpiConfig.parse("gap=1\nthermo=" + CH01 + "," + second.signalName()
                            + "\ngnss=SPEED_OVER_GROUND"),
                    List.of(
                            EngineeringKpiSeries.present("thermo", CH01, ch01.unit(), MF4.getFileName().toString(), firstKpi),
                            EngineeringKpiSeries.present("thermo", second.signalName(), second.unit(),
                                    MF4.getFileName().toString(), secondKpi),
                            EngineeringKpiSeries.present("gnss", "SPEED_OVER_GROUND", "", MF4.getFileName().toString(),
                                    List.of(new EngineeringKpiSeries.Sample(0, 1, true, "")))));
            EngineeringKpiResult minimum = kpis.stream()
                    .filter(row -> row.id().equals("thermal.tc.min") && CH01.equals(row.sourceSignal()))
                    .findFirst().orElseThrow();
            assertEquals(ch01.minPhysical(), minimum.numericValue(), 1e-6);
            assertTrue(kpis.stream().anyMatch(row -> row.sourceSignal().equals(CH01) && row.id().equals("thermal.tc.max")));
            assertTrue(kpis.stream().anyMatch(row -> row.sourceSignal().equals(second.signalName())
                    && row.id().equals("thermal.tc.max")));

            SignalReportRenderer.Track left = track(CH01, ch01.unit(), primary);
            SignalReportRenderer.Track right = track(second.signalName(), second.unit(), other);
            List<SignalReportRenderer.EventSpan> marks = List.of(
                    new SignalReportRenderer.EventSpan(EVENT, startSeconds, endSeconds));
            BufferedImage chart = SignalReportRenderer.paintAligned(List.of(left, right), marks);
            assertEquals(kept, primaryValues[primaryValues.length / 2], 0.0);
            var frame = SignalReportRenderer.alignedLayout(List.of(left, right)).tracks().getFirst();
            assertEquals(1, SignalReportRenderer.alignedLayout(List.of(left, right)).tracks().size());
            int inside = xOf(frame, (startSeconds + endSeconds) / 2.0);
            int before = xOf(frame, Math.max(frame.xMin(), startSeconds - 30));
            assertTrue(shadedNear(chart, inside, frame.plotTop() + 8), "event shade missing");
            assertFalse(shaded(chart, before, frame.plotTop() + 8));
            byte[] chartPng = toPng(chart);
            assertEquals(SignalReportRenderer.WIDTH, chart.getWidth());

            var validated = MapRouteBuilder.validateAndBuild(
                    readAll(store, latitude), readAll(store, longitude), List.of());
            assertFalse(validated.model().isEmpty(), validated.diagnostics().summary());
            GnssEventCorrelation.Result located = GnssEventCorrelation.locate(
                    validated.model(), startNanos, endNanos, EVENT);
            assertTrue(located.located(), located.statement());
            int index = validated.model().indexAtOrAfter(startNanos);
            assertEquals(validated.model().latitude(index), located.latitude(), 0.0);
            assertEquals(validated.model().longitude(index), located.longitude(), 0.0);
            assertEquals(validated.model().timestampNanos(index), located.sampleTimestampNanos());
            assertTrue(located.sampleTimestampNanos() >= startNanos);
            assertTrue(located.sampleTimestampNanos() <= endNanos);
            assertTrue(located.statement().contains("First GNSS sample"));
            assertTrue(located.statement().contains("not the whole event"));
            ProjectRoute projectRoute = new ProjectRoute(List.of(new RouteSegment(
                    "measurement", MF4.getFileName().toString(), new MapRoute(validated.model()))), 1, 1);
            RouteMapReportContext context = new RouteMapReportContext(
                    false, MF4.getFileName().toString(), projectRoute, "");
            RouteMapReportAsset map = new RouteMapReportRenderer().render(context, List.of(
                    new RouteMapReportRenderer.MapMarker(
                            "First GNSS sample", located.latitude(), located.longitude())));
            assertTrue(map.hasImage());

            Path sampleDir = sampleDir();
            Files.createDirectories(sampleDir);
            Files.write(sampleDir.resolve("signals-comparison.png"), chartPng);
            Files.write(sampleDir.resolve("route-map-m10.png"), map.png());
            copyBefore(sampleDir);
            String range = EngineeringFormats.range(primary.getFirst().timestampNanos(), primary.getLast().timestampNanos());
            String window = EngineeringFormats.interval(startNanos, endNanos);
            int usableInWindow = 0;
            int unusableInWindow = 0;
            int belowForty = 0;
            double windowMin = Double.POSITIVE_INFINITY;
            double windowMax = Double.NEGATIVE_INFINITY;
            for (int i = rise; i <= settle; i++) {
                SignalSamplePoint sample = primary.get(i);
                if (!sample.usableMeasurement()) {
                    unusableInWindow++;
                    continue;
                }
                usableInWindow++;
                double value = sample.physicalValue();
                windowMin = Math.min(windowMin, value);
                windowMax = Math.max(windowMax, value);
                if (value < 40.0d) {
                    belowForty++;
                }
            }
            assertTrue(usableInWindow > 0);
            String measured = "Measured CH01 minimum " + decimal(windowMin) + " °C and maximum "
                    + decimal(windowMax) + " °C inside the engineer-selected window " + window + ".";
            String compliance = belowForty == 0 && unusableInWindow == 0
                    ? "Verified time above 40 °C: every CH01 sample in that window is at or above 40 °C."
                    : "Not a verified time-above-threshold result: " + belowForty
                            + " usable CH01 samples are below 40 °C and " + unusableInWindow
                            + " samples in the window are not usable.";
            String note = "Engineer-selected window " + window + ". " + measured + " " + compliance
                    + " Engineer-authored interpretation. No automatic detector.";
            String caption = CH01 + " and " + second.signalName()
                    + " on one temperature axis. Event window " + window
                    + " is drawn over the original samples. Samples were not modified. | " + MF4.getFileName();
            EngineeringReportModel model = EngineeringReportModel.builder()
                    .template(EngineeringReportTemplate.ENGINEERING_V2)
                    .sections(List.of(EngineeringReportSection.values()))
                    .timestampBasis(EngineeringReportModelBuilder.TIMESTAMP_BASIS)
                    .header(new EngineeringReportModel.Header(
                            "Thermocouple and route evidence", "", MF4.getFileName().toString(), Instant.now(), "", ""))
                    .investigation(new EngineeringReportModel.InvestigationSection(
                            MF4.getFileName().toString(), "", InvestigationStatus.DRAFT, range,
                            EngineeringFormats.duration(primary.getFirst().timestampNanos(), primary.getLast().timestampNanos()),
                            1))
                    .sources(List.of(new EngineeringReportModel.SourceRow(
                            MF4.getFileName().toString(), "MF4", "", "12.3 MB", "COMPLETED")))
                    .definitions(List.of(new EngineeringReportModel.DefinitionRow(
                            DBC.getFileName().toString(), "DBC", "COMPLETED")))
                    .signals(List.of(
                            signalRow(ch01, primary.size()),
                            signalRow(second, other.size())))
                    .figures(List.of(new EngineeringReportModel.Figure(1, caption, "signals-comparison.png", range)))
                    .routeMap(map.section("route-map-m10.png"))
                    .events(List.of(new EngineeringReportModel.EventRow(
                            EVENT, "TIME_RANGE",
                            EngineeringFormats.seconds(startNanos), EngineeringFormats.seconds(endNanos),
                            MF4.getFileName().toString(), CH01 + ", " + second.signalName(),
                            "Engineer-selected window on the measurement clock. No automatic detector.",
                            "signals-comparison.png",
                            located.statement()
                                    + " Latitude " + decimal(located.latitude())
                                    + ", longitude " + decimal(located.longitude())
                                    + ". GNSS timestamp " + EngineeringFormats.seconds(located.sampleTimestampNanos())
                                    + ". Source " + MF4.getFileName()
                                    + ". Event " + window + ".",
                            "Engineer-authored. Original samples were not modified.")))
                    .observations(List.of(new EngineeringObservation(
                            "note", "inv", startNanos, endNanos, "Temperature window",
                            note, Instant.now(), Instant.now())))
                    .limitations(List.of(
                            "Vehicle speed, engine speed, accelerator, brake and stability status are not mapped from this DBC.",
                            "The route marker is the first GNSS sample inside the engineer-selected window. It is not the event location."))
                    .kpis(kpis)
                    .build();
            String html = EngineeringHtmlReportRenderer.render(model);
            Path pdf = sampleDir.resolve("engineering-report-v2-m10-sample.pdf");
            Path htmlFile = sampleDir.resolve("engineering-report-v2-m10-sample.html");
            EngineeringPdfReportRenderer.write(model, pdf, sampleDir);
            Files.writeString(htmlFile, html);
            assertTrue(html.contains(CH01));
            assertTrue(html.contains(second.signalName()));
            assertTrue(html.contains(EVENT));
            assertTrue(html.contains(decimal(located.latitude())));
            assertTrue(html.contains(decimal(located.longitude())));
            assertTrue(html.contains("Latitude " + decimal(located.latitude())));
            assertTrue(html.contains("longitude " + decimal(located.longitude())));
            assertTrue(html.contains(window));
            assertTrue(html.contains(measured));
            assertTrue(html.contains(compliance));
            assertTrue(html.contains("First GNSS sample inside the engineer-selected window"));
            assertTrue(html.contains("not the whole event"));
            assertTrue(html.contains(EngineeringFormats.seconds(located.sampleTimestampNanos())));
            assertTrue(html.contains(decimal(ch01.minPhysical())));
            assertTrue(html.contains(decimal(ch01.maxPhysical())));
            assertTrue(html.contains(decimal(second.minPhysical())));
            assertTrue(html.contains(decimal(second.maxPhysical())));
            assertTrue(html.contains("Temperature"));
            assertTrue(html.contains("not a supported speed unit"));
            assertTrue(html.contains("not interpolated"));
            assertFalse(html.contains(" s s"));
            assertFalse(html.contains("at or above 40 °C from"));
            assertFalse(html.contains("PASS"));
            assertFalse(html.contains("http://"));
            String packed;
            int images = 0;
            try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
                packed = new PDFTextStripper().getText(document).replaceAll("\\s+", "");
                assertTrue(packed.contains(CH01));
                assertTrue(packed.contains(second.signalName()));
                assertTrue(packed.contains(EVENT.replace(" ", "")));
                assertTrue(packed.contains(decimal(located.latitude())));
                assertTrue(packed.contains(decimal(located.longitude())));
                assertTrue(packed.contains(window.replace(" ", "")));
                assertTrue(packed.contains(compliance.replaceAll("\\s+", "")));
                assertTrue(packed.contains("FirstGNSSsample"));
                assertTrue(packed.contains("notthewholeevent"));
                assertTrue(packed.contains(EngineeringFormats.seconds(located.sampleTimestampNanos()).replace(" ", "")));
                assertFalse(packed.contains(EngineeringFormats.seconds(startNanos).replace(" ", "") + "s"));
                assertTrue(packed.contains("Temperature"));
                assertFalse(packed.contains("PASS"));
                for (PDPage page : document.getPages()) {
                    if (page.getResources() == null) {
                        continue;
                    }
                    for (var name : page.getResources().getXObjectNames()) {
                        PDXObject object = page.getResources().getXObject(name);
                        if (object instanceof PDImageXObject image && image.getWidth() >= 1000) {
                            images++;
                        }
                    }
                }
                Path pages = sampleDir.resolve("engineering-report-v2-m10-pages");
                Files.createDirectories(pages);
                try (var listing = Files.list(pages)) {
                    for (Path old : listing.toList()) {
                        Files.deleteIfExists(old);
                    }
                }
                PDFRenderer renderer = new PDFRenderer(document);
                for (int i = 0; i < document.getNumberOfPages(); i++) {
                    ImageIO.write(renderer.renderImageWithDPI(i, 100), "png",
                            pages.resolve("page-" + (i + 1) + ".png").toFile());
                }
            }
            assertTrue(images >= 2);
            assertTrue(packed.contains("notasupportedspeedunit") || html.contains("not a supported speed unit"));
        } finally {
            if (processed.catalogStore() instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private static SignalReportRenderer.Track track(String name, String unit, List<SignalSamplePoint> samples) {
        return new SignalReportRenderer.Track(name, unit,
                samples.stream().mapToDouble(sample -> sample.timestampNanos() / 1_000_000_000d).toArray(),
                samples.stream().mapToDouble(SignalSamplePoint::physicalValue).toArray());
    }

    private static EngineeringReportModel.SignalRow signalRow(SignalCatalogEntry entry, int samples) {
        return new EngineeringReportModel.SignalRow(
                entry.signalName(), entry.unit(),
                MF4.getFileName() + " · " + entry.messageName() + " · " + samples + " samples",
                decimal(entry.minPhysical()), decimal(entry.maxPhysical()), "", "", "");
    }

    private static List<EngineeringKpiSeries.Sample> kpiSamples(List<SignalSamplePoint> samples) {
        List<EngineeringKpiSeries.Sample> rows = new ArrayList<>();
        for (SignalSamplePoint sample : samples) {
            rows.add(new EngineeringKpiSeries.Sample(
                    sample.timestampNanos(), sample.physicalValue(), sample.usableMeasurement(), ""));
        }
        return rows;
    }

    private static SignalCatalogEntry secondThermocouple(SignalSeriesStore store, SignalCatalogEntry ch01) {
        SignalCatalogEntry found = null;
        for (SignalCatalogEntry entry : store.snapshot().entries()) {
            if (entry == ch01 || CH01.equals(entry.signalName())) {
                continue;
            }
            if (entry.unit() == null || !entry.unit().contains("C") || entry.sampleCount() < 1000) {
                continue;
            }
            if (entry.minPhysical() == null || entry.maxPhysical() == null
                    || entry.minPhysical().equals(entry.maxPhysical())) {
                continue;
            }
            if (found == null || entry.signalName().compareTo(found.signalName()) < 0) {
                found = entry;
            }
        }
        return found;
    }

    private static boolean shadedNear(BufferedImage image, int x, int y) {
        for (int dx = -12; dx <= 12; dx++) {
            if (shaded(image, x + dx, y)) {
                return true;
            }
        }
        return false;
    }

    private static boolean shaded(BufferedImage image, int x, int y) {
        if (x < 0 || y < 0 || x >= image.getWidth() || y >= image.getHeight()) {
            return false;
        }
        int rgb = image.getRGB(x, y);
        int red = (rgb >> 16) & 0xFF;
        int blue = rgb & 0xFF;
        return red > 220 && blue < 210 && red > blue + 20;
    }

    private static int xOf(SignalReportRenderer.TrackFrame frame, double time) {
        double span = Math.max(1e-12, frame.xMax() - frame.xMin());
        double fraction = Math.max(0, Math.min(1, (time - frame.xMin()) / span));
        return frame.plotLeft() + (int) Math.round(fraction * (frame.plotWidth() - 1));
    }

    private static byte[] toPng(BufferedImage image) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static void copyBefore(Path sampleDir) throws Exception {
        Path previous = sampleDir.resolve("engineering-report-v2-m9-pages");
        Path before = sampleDir.resolve("engineering-report-v2-m10-before");
        if (!Files.isDirectory(previous)) {
            return;
        }
        Files.createDirectories(before);
        try (var listing = Files.list(previous)) {
            for (Path page : listing.toList()) {
                Files.copy(page, before.resolve(page.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String decimal(double value) {
        return new BigDecimal(Double.toString(value)).setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static EngineeringKpiResult one(List<EngineeringKpiResult> rows, String id) {
        return rows.stream().filter(row -> row.id().equals(id)).findFirst().orElseThrow();
    }

    private static SignalCatalogEntry entry(SignalSeriesStore store, String name) {
        for (SignalCatalogEntry entry : store.snapshot().entries()) {
            if (name.equals(entry.signalName())) {
                return entry;
            }
        }
        return null;
    }

    private static List<SignalSamplePoint> readAll(SignalSeriesStore store, SignalCatalogEntry entry) {
        int retained = store.retainedSampleCount(entry.key());
        List<SignalSamplePoint> all = new ArrayList<>(Math.max(16, retained));
        int offset = 0;
        while (offset < retained) {
            List<SignalSamplePoint> page = store.querySamples(entry.key(), offset, 5_000);
            if (page.isEmpty()) {
                break;
            }
            all.addAll(page);
            offset += page.size();
        }
        return all;
    }

    private static Path sampleDir() {
        Path cwd = Path.of("").toAbsolutePath();
        Path repo = "stellar-desktop".equals(cwd.getFileName().toString()) ? cwd.getParent() : cwd;
        return repo.resolve("target");
    }
}
