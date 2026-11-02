package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineerEventDefinition;
import com.stellar.platform.analysis.investigation.EngineerEventValidation;
import com.stellar.platform.analysis.investigation.EngineeringFormats;
import com.stellar.platform.analysis.investigation.EngineeringReportSection;
import com.stellar.platform.analysis.investigation.EngineeringReportTemplate;
import com.stellar.platform.analysis.investigation.InvestigationStatus;
import com.stellar.platform.analysis.investigation.report.EngineerEventReportRows;
import com.stellar.platform.analysis.investigation.report.EngineeringHtmlReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringPdfReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModel;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModelBuilder;
import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.project.FileKind;
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
import com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Engineer-defined events on the Tata Sierra thermocouple recording.
 * Samples and the saved bookmark are checked against the original timestamps.
 */
class EngineeringReportV2M11EventsTest {

    private static final Path ROOT = Path.of("C:/Users/DT - User/Downloads/Mahima_Time_Issue");
    private static final Path MF4 = ROOT.resolve("tata_sierra_0002391_20260921_133054 (1).mf4");
    private static final Path DBC = ROOT.resolve("TEMP DBC 1 (1).dbc");
    private static final String CH01 = "CH01_FRONT_BUMPER_GRILL_AMBIENT";

    @TempDir
    Path temp;

    @Test
    @Timeout(180)
    void engineerEventsKeepOriginalTimestampsAndReportTheGnssSample() throws Exception {
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
            double kept = primary.get(primary.size() / 2).physicalValue();
            int rise = -1;
            int settle = -1;
            double windowMin = Double.POSITIVE_INFINITY;
            double windowMax = Double.NEGATIVE_INFINITY;
            int below = 0;
            int usable = 0;
            for (int i = 0; i < primary.size(); i++) {
                SignalSamplePoint sample = primary.get(i);
                if (!sample.usableMeasurement() || sample.physicalValue() < 40.0d) {
                    continue;
                }
                if (rise < 0) {
                    rise = i;
                }
                settle = i;
            }
            assertTrue(rise > 0 && settle > rise);
            long startNanos = primary.get(rise).timestampNanos();
            long endNanos = primary.get(settle).timestampNanos();
            for (int i = rise; i <= settle; i++) {
                SignalSamplePoint sample = primary.get(i);
                if (!sample.usableMeasurement()) {
                    continue;
                }
                usable++;
                windowMin = Math.min(windowMin, sample.physicalValue());
                windowMax = Math.max(windowMax, sample.physicalValue());
                if (sample.physicalValue() < 40.0d) {
                    below++;
                }
            }
            assertTrue(usable > 0);
            var route = MapRouteBuilder.validateAndBuild(
                    readAll(store, latitude), readAll(store, longitude), List.of());
            assertFalse(route.model().isEmpty(), route.diagnostics().summary());
            GnssEventCorrelation.Result located = GnssEventCorrelation.locate(
                    route.model(), startNanos, endNanos, "Thermal window");
            assertTrue(located.located(), located.statement());
            int index = route.model().indexAtOrAfter(startNanos);
            assertEquals(route.model().latitude(index), located.latitude(), 0.0);
            assertEquals(route.model().longitude(index), located.longitude(), 0.0);
            assertEquals(route.model().timestampNanos(index), located.sampleTimestampNanos());
            long gapStart = -1L;
            long gapEnd = -1L;
            for (int i = 0; i + 1 < route.model().size(); i++) {
                long left = route.model().timestampNanos(i);
                long right = route.model().timestampNanos(i + 1);
                if (right - left > 2L) {
                    gapStart = left + 1L;
                    gapEnd = right - 1L;
                    break;
                }
            }
            assertTrue(gapStart > 0L && gapEnd >= gapStart);
            GnssEventCorrelation.Result missing = GnssEventCorrelation.locate(
                    route.model(), gapStart, gapEnd, "GNSS gap");
            assertFalse(missing.located(), missing.statement());

            String ch01Signal = CH01 + " (°C)";
            String ch02Signal = second.signalName() + " (°C)";
            String measured = "Measured CH01 minimum " + decimal(windowMin) + " °C and maximum "
                    + decimal(windowMax) + " °C. " + below + " usable samples in the window are below 40 °C.";
            EngineerEventDefinition thermal = new EngineerEventDefinition(
                    "thermal", "Thermal window", "Thermal observation", measurement.id(), MF4.getFileName().toString(),
                    startNanos, endNanos, List.of(ch01Signal, ch02Signal),
                    "Engineer-selected window on the measurement clock.",
                    "Record the window. Do not treat it as a continuous threshold result.",
                    measured, List.of("signals-comparison.png"), true, "Confirmed",
                    located.statement() + " Latitude " + decimal(located.latitude())
                            + ", longitude " + decimal(located.longitude())
                            + ". GNSS timestamp " + EngineeringFormats.seconds(located.sampleTimestampNanos())
                            + ". Source " + MF4.getFileName() + ". Event "
                            + EngineeringFormats.interval(startNanos, endNanos) + ".",
                    "", "signals-comparison.png");
            assertTrue(EngineerEventValidation.blocking(
                    thermal, primary.getFirst().timestampNanos(), primary.getLast().timestampNanos(),
                    Set.of(CH01, second.signalName())).isEmpty());
            SignalSamplePoint pointSample = primary.get(Math.min(100, primary.size() - 1));
            EngineerEventDefinition point = new EngineerEventDefinition(
                    "point", "Sensor check", "Sensor anomaly", measurement.id(), MF4.getFileName().toString(),
                    pointSample.timestampNanos(), null, List.of(ch01Signal),
                    "Point event at one original sample.", "",
                    "CH01 at this sample is " + decimal(pointSample.physicalValue()) + " °C.",
                    List.of(), false, "Draft",
                    "GPS evidence was not attached. No coordinate was invented.",
                    "GPS evidence was not attached. No coordinate was invented.", "");
            EngineerEventDefinition gap = new EngineerEventDefinition(
                    "gap", "GNSS gap", "Custom event", measurement.id(), MF4.getFileName().toString(),
                    gapStart, gapEnd, List.of(ch01Signal),
                    "Short window between two GNSS samples.", "",
                    "No thermocouple value was interpolated into this gap.",
                    List.of(), true, "Draft", missing.statement(),
                    "No GNSS sample on this recording matches the event. No coordinate was invented.", "");

            BufferedImage chart = SignalReportRenderer.paintAligned(List.of(
                    track(CH01, ch01.unit(), primary),
                    track(second.signalName(), second.unit(), other)), List.of(
                    new SignalReportRenderer.EventSpan("Thermal window", startNanos / 1_000_000_000d, endNanos / 1_000_000_000d),
                    new SignalReportRenderer.EventSpan("Sensor check", pointSample.timestampNanos() / 1_000_000_000d, Double.NaN)));
            assertEquals(kept, primary.get(primary.size() / 2).physicalValue(), 0.0);
            Path sampleDir = Path.of("").toAbsolutePath();
            Path repo = "stellar-desktop".equals(sampleDir.getFileName().toString()) ? sampleDir.getParent() : sampleDir;
            Path out = repo.resolve("target");
            Files.createDirectories(out);
            Files.write(out.resolve("signals-comparison.png"), toPng(chart));
            ProjectRoute projectRoute = new ProjectRoute(List.of(new RouteSegment(
                    "measurement", MF4.getFileName().toString(), new MapRoute(route.model()))), 1, 1);
            var map = new RouteMapReportRenderer().render(new RouteMapReportContext(
                    false, MF4.getFileName().toString(), projectRoute, ""), List.of(
                    new RouteMapReportRenderer.MapMarker("First GNSS sample", located.latitude(), located.longitude())));
            Files.write(out.resolve("route-map-m11.png"), map.png());

            Path db = temp.resolve("m11.db");
            try (SqliteProjectMetadataStore metadata = new SqliteProjectMetadataStore(db)) {
                metadata.upsertProject(new ProjectMetadata(
                        "proj", "M11", Instant.now(), Instant.now(), "GRAPHS", null));
                metadata.upsertBookmark(new EngineeringBookmarkMetadata(
                        thermal.id(), "proj", thermal.sourceId(), "", ch01Signal, ch01Signal, thermal.startNanos(),
                        thermal.category(), EngineerEventDocuments.observation(thermal), Instant.now(), Instant.now()));
            }
            try (SqliteProjectMetadataStore metadata = new SqliteProjectMetadataStore(db)) {
                EngineerEventDefinition reopened = EngineerEventDocuments.decode(metadata.listBookmarks("proj").getFirst());
                assertEquals(startNanos, reopened.startNanos());
                assertEquals(endNanos, reopened.endNanos());
                assertEquals(List.of(ch01Signal, ch02Signal), reopened.signals());
                assertEquals(kept, primary.get(primary.size() / 2).physicalValue(), 0.0);
            }

            EngineeringReportModel model = EngineeringReportModel.builder()
                    .template(EngineeringReportTemplate.ENGINEERING_V2)
                    .sections(List.of(EngineeringReportSection.values()))
                    .timestampBasis(EngineeringReportModelBuilder.TIMESTAMP_BASIS)
                    .header(new EngineeringReportModel.Header(
                            "Engineer events", "", MF4.getFileName().toString(), Instant.now(), "", ""))
                    .investigation(new EngineeringReportModel.InvestigationSection(
                            MF4.getFileName().toString(), "", InvestigationStatus.DRAFT,
                            EngineeringFormats.range(primary.getFirst().timestampNanos(), primary.getLast().timestampNanos()),
                            EngineeringFormats.duration(primary.getFirst().timestampNanos(), primary.getLast().timestampNanos()),
                            1))
                    .sources(List.of(new EngineeringReportModel.SourceRow(
                            MF4.getFileName().toString(), "MF4", "", "12.3 MB", "COMPLETED")))
                    .signals(List.of(
                            new EngineeringReportModel.SignalRow(CH01, ch01.unit(),
                                    MF4.getFileName() + " · " + primary.size() + " samples",
                                    decimal(ch01.minPhysical()), decimal(ch01.maxPhysical()), "", "", ""),
                            new EngineeringReportModel.SignalRow(second.signalName(), second.unit(),
                                    MF4.getFileName() + " · " + other.size() + " samples",
                                    decimal(second.minPhysical()), decimal(second.maxPhysical()), "", "", "")))
                    .figures(List.of(new EngineeringReportModel.Figure(
                            1, "CH01 and " + second.signalName() + " with the engineer window "
                            + EngineeringFormats.interval(startNanos, endNanos),
                            "signals-comparison.png",
                            EngineeringFormats.range(primary.getFirst().timestampNanos(), primary.getLast().timestampNanos()))))
                    .routeMap(map.section("route-map-m11.png"))
                    .events(List.of(
                            EngineerEventReportRows.row(thermal),
                            EngineerEventReportRows.row(point),
                            EngineerEventReportRows.row(gap)))
                    .build();
            String html = EngineeringHtmlReportRenderer.render(model);
            Path htmlFile = out.resolve("engineering-report-v2-m11-sample.html");
            Path pdf = out.resolve("engineering-report-v2-m11-sample.pdf");
            Files.writeString(htmlFile, html);
            EngineeringPdfReportRenderer.write(model, pdf, out);
            assertTrue(html.contains("Thermal window"));
            assertTrue(html.contains("Sensor check"));
            assertTrue(html.contains("GNSS gap"));
            assertTrue(html.contains(EngineeringFormats.interval(startNanos, endNanos)));
            assertTrue(html.contains(decimal(located.latitude())));
            assertTrue(html.contains(decimal(located.longitude())));
            assertTrue(html.contains(EngineeringFormats.seconds(located.sampleTimestampNanos())));
            assertTrue(html.contains(measured));
            assertTrue(html.contains("No coordinate was invented"));
            assertTrue(html.contains(missing.statement()));
            assertTrue(html.contains(decimal(ch01.minPhysical())));
            assertTrue(html.contains(decimal(ch01.maxPhysical())));
            assertTrue(html.contains(decimal(second.minPhysical())));
            assertTrue(html.contains(decimal(second.maxPhysical())));
            assertFalse(html.contains(" s s"));
            assertFalse(html.contains("at or above 40 °C from"));
            assertFalse(html.contains("PASS"));
            assertFalse(html.contains("http://"));
            try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
                String packed = new PDFTextStripper().getText(document).replaceAll("\\s+", "");
                assertTrue(packed.contains("Thermalwindow"));
                assertTrue(packed.contains("Sensorcheck"));
                assertTrue(packed.contains("GNSSgap"));
                assertTrue(packed.contains(EngineeringFormats.interval(startNanos, endNanos).replace(" ", "")));
                assertTrue(packed.contains(decimal(located.latitude())));
                assertTrue(packed.contains(decimal(located.longitude())));
                assertFalse(packed.contains(EngineeringFormats.seconds(startNanos).replace(" ", "") + "s"));
                assertFalse(packed.contains("PASS"));
                Path pages = out.resolve("engineering-report-v2-m11-pages");
                Files.createDirectories(pages);
                try (var listing = Files.list(pages)) {
                    for (Path old : listing.toList()) {
                        Files.deleteIfExists(old);
                    }
                }
                PDFRenderer renderer = new PDFRenderer(document);
                for (int i = 0; i < document.getNumberOfPages(); i++) {
                    ImageIO.write(renderer.renderImageWithDPI(i, 90), "png",
                            pages.resolve("page-" + (i + 1) + ".png").toFile());
                }
                assertTrue(document.getNumberOfPages() >= 2);
            }
            assertEquals(kept, primary.get(primary.size() / 2).physicalValue(), 0.0);
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

    private static SignalCatalogEntry entry(SignalSeriesStore store, String name) {
        for (SignalCatalogEntry candidate : store.snapshot().entries()) {
            if (name.equals(candidate.signalName())) {
                return candidate;
            }
        }
        return null;
    }

    private static SignalCatalogEntry secondThermocouple(SignalSeriesStore store, SignalCatalogEntry ch01) {
        SignalCatalogEntry found = null;
        for (SignalCatalogEntry candidate : store.snapshot().entries()) {
            if (candidate == ch01 || CH01.equals(candidate.signalName())) {
                continue;
            }
            if (candidate.unit() == null || !candidate.unit().contains("C") || candidate.sampleCount() < 1000) {
                continue;
            }
            if (candidate.minPhysical() == null || candidate.maxPhysical() == null
                    || candidate.minPhysical().equals(candidate.maxPhysical())) {
                continue;
            }
            if (found == null || candidate.signalName().compareTo(found.signalName()) < 0) {
                found = candidate;
            }
        }
        return found;
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

    private static String decimal(double value) {
        return new BigDecimal(Double.toString(value)).setScale(6, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static byte[] toPng(BufferedImage image) throws Exception {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        return buffer.toByteArray();
    }
}
