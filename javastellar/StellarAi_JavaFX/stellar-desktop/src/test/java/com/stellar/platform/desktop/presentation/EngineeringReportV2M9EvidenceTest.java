package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringFormats;
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
 * Engineer-selected CH01 evidence, one saved snapshot, one authored event, the GPS route,
 * and the KPI pack, rendered to the same HTML and PDF model.
 */
class EngineeringReportV2M9EvidenceTest {

    private static final Path ROOT = Path.of("C:/Users/DT - User/Downloads/Mahima_Time_Issue");
    private static final Path MF4 = ROOT.resolve("tata_sierra_0002391_20260921_133054 (1).mf4");
    private static final Path DBC = ROOT.resolve("TEMP DBC 1 (1).dbc");
    private static final String CH01 = "CH01_FRONT_BUMPER_GRILL_AMBIENT";
    private static final String EVENT = "Engineer temperature window";

    @Test
    @Timeout(180)
    void selectedEvidenceAndKpisMatchTheDecodedMeasurement() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC),
                "Tata Sierra MF4 and TEMP DBC are not on this machine");
        WorkspaceFile measurement = new WorkspaceFile(MF4, FileKind.MEASUREMENT_MF4, Files.size(MF4));
        WorkspaceFile definition = new WorkspaceFile(DBC, FileKind.DEFINITION_DBC, Files.size(DBC));
        LocalProcessingGateway.GatewayResult processed = LocalProcessingGateway.createDefault().process(
                measurement, definition, 512, new SignalPreviewBuffer(64), ProcessingProgressListener.NOOP, null);
        try {
            assertTrue(processed.summary().completionStatus() == ProjectProcessingStatus.COMPLETED
                    || processed.summary().completionStatus() == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS);
            SignalSeriesStore store = processed.catalogStore();
            SignalCatalogEntry ch01 = entry(store, CH01);
            SignalCatalogEntry latitude = entry(store, "LATITUDE");
            SignalCatalogEntry longitude = entry(store, "LONGITUDE");
            assertNotNull(ch01);
            assertNotNull(latitude);
            assertNotNull(longitude);
            List<SignalSamplePoint> samples = readAll(store, ch01);
            assertEquals(18_007, samples.size());

            int rise = -1;
            int settle = -1;
            for (int i = 0; i < samples.size(); i++) {
                if (samples.get(i).usableMeasurement() && samples.get(i).physicalValue() >= 40.0) {
                    if (rise < 0) {
                        rise = i;
                    }
                    settle = i;
                }
            }
            assertTrue(rise >= 0 && settle > rise);
            double windowMin = Double.POSITIVE_INFINITY;
            double windowMax = Double.NEGATIVE_INFINITY;
            for (int i = rise; i <= settle; i++) {
                windowMin = Math.min(windowMin, samples.get(i).physicalValue());
                windowMax = Math.max(windowMax, samples.get(i).physicalValue());
            }
            String start = EngineeringFormats.seconds(samples.get(rise).timestampNanos());
            String end = EngineeringFormats.seconds(samples.get(settle).timestampNanos());
            String description = "Engineer-authored window. Measured "
                    + decimal(windowMin) + " °C to " + decimal(windowMax)
                    + " °C. No automatic detector.";

            List<EngineeringKpiSeries.Sample> kpiSamples = new ArrayList<>();
            for (SignalSamplePoint sample : samples) {
                kpiSamples.add(new EngineeringKpiSeries.Sample(
                        sample.timestampNanos(), sample.physicalValue(), sample.usableMeasurement(), ""));
            }
            List<EngineeringKpiResult> kpis = EngineeringKpiCalculator.calculate(
                    EngineeringKpiConfig.parse("gap=1\nthermo=" + CH01 + "\ngnss=SPEED_OVER_GROUND"),
                    List.of(
                            EngineeringKpiSeries.present("thermo", CH01, ch01.unit(), MF4.getFileName().toString(), kpiSamples),
                            EngineeringKpiSeries.present("gnss", "SPEED_OVER_GROUND", "", MF4.getFileName().toString(),
                                    List.of(new EngineeringKpiSeries.Sample(0, 1, true, "")))));
            EngineeringKpiResult minimum = one(kpis, "thermal.tc.min");
            EngineeringKpiResult maximum = one(kpis, "thermal.tc.max");
            EngineeringKpiResult gnss = one(kpis, "vehicle.distance.gnss");
            assertEquals(ch01.minPhysical(), minimum.numericValue(), 1e-6);
            assertEquals(ch01.maxPhysical(), maximum.numericValue(), 1e-6);
            assertEquals(null, gnss.numericValue());
            assertTrue(gnss.notes().contains("not a supported speed unit"));
            assertTrue(kpis.stream().noneMatch(row -> row.id().startsWith("state.brake")));

            SignalReportRenderer.Track track = new SignalReportRenderer.Track(
                    CH01, ch01.unit(),
                    samples.stream().mapToDouble(sample -> sample.timestampNanos() / 1_000_000_000d).toArray(),
                    samples.stream().mapToDouble(SignalSamplePoint::physicalValue).toArray());
            List<SignalReportImage> images = SignalReportRenderer.render(List.of(track), null);
            assertFalse(images.getFirst().failed());
            BufferedImage chart = ImageIO.read(new ByteArrayInputStream(images.getFirst().png()));
            assertEquals(SignalReportRenderer.WIDTH, chart.getWidth());

            var validated = MapRouteBuilder.validateAndBuild(
                    readAll(store, latitude), readAll(store, longitude), List.of());
            assertFalse(validated.model().isEmpty(), validated.diagnostics().summary());
            ProjectRoute projectRoute = new ProjectRoute(List.of(new RouteSegment(
                    "measurement", MF4.getFileName().toString(), new MapRoute(validated.model()))), 1, 1);
            RouteMapReportContext context = new RouteMapReportContext(
                    false, MF4.getFileName().toString(), projectRoute, "");
            RouteMapReportAsset map = new RouteMapReportRenderer().render(context);
            assertNotNull(map);
            assertTrue(map.hasImage());
            assertTrue(map.png().length > 1000);

            Path sampleDir = sampleDir();
            Files.createDirectories(sampleDir);
            Files.write(sampleDir.resolve("signals-waveform.png"), images.getFirst().png());
            Files.write(sampleDir.resolve("route-map.png"), map.png());
            String range = EngineeringFormats.range(samples.getFirst().timestampNanos(), samples.getLast().timestampNanos());
            String caption = CH01 + " | " + samples.size() + " samples | minimum " + minimum.valueText()
                    + " " + ch01.unit() + " | maximum " + maximum.valueText() + " " + ch01.unit()
                    + " | " + MF4.getFileName();
            EngineeringReportModel model = EngineeringReportModel.builder()
                    .template(EngineeringReportTemplate.ENGINEERING_V2)
                    .sections(List.of(EngineeringReportSection.values()))
                    .timestampBasis(EngineeringReportModelBuilder.TIMESTAMP_BASIS)
                    .header(new EngineeringReportModel.Header(
                            "CH01 selected evidence", "", MF4.getFileName().toString(), Instant.now(), "", ""))
                    .investigation(new EngineeringReportModel.InvestigationSection(
                            MF4.getFileName().toString(), "", InvestigationStatus.DRAFT, range,
                            EngineeringFormats.duration(samples.getFirst().timestampNanos(), samples.getLast().timestampNanos()),
                            1))
                    .sources(List.of(new EngineeringReportModel.SourceRow(
                            MF4.getFileName().toString(), "MF4", "", "12.3 MB", "COMPLETED")))
                    .definitions(List.of(new EngineeringReportModel.DefinitionRow(
                            DBC.getFileName().toString(), "DBC", "COMPLETED")))
                    .signals(List.of(new EngineeringReportModel.SignalRow(
                            CH01, ch01.unit(),
                            MF4.getFileName() + " · " + ch01.messageName() + " · " + samples.size() + " samples",
                            minimum.valueText(), maximum.valueText(), "", "", "")))
                    .figures(List.of(new EngineeringReportModel.Figure(
                            1, caption, "signals-waveform.png", range)))
                    .routeMap(map.section("route-map.png"))
                    .events(List.of(new EngineeringReportModel.EventRow(
                            EVENT, "TIME_RANGE", start, end, MF4.getFileName().toString(), CH01,
                            description, "No graph snapshot is linked.",
                            "Not correlated with a route.",
                            "Engineer-authored. The original samples were not modified.")))
                    .kpis(kpis)
                    .build();

            String html = EngineeringHtmlReportRenderer.render(model);
            Path pdf = sampleDir.resolve("engineering-report-v2-m9-sample.pdf");
            Path htmlFile = sampleDir.resolve("engineering-report-v2-m9-sample.html");
            EngineeringPdfReportRenderer.write(model, pdf, sampleDir);
            Files.writeString(htmlFile, html);

            assertTrue(html.contains(CH01));
            assertTrue(html.contains(EVENT));
            assertTrue(html.contains(start));
            assertTrue(html.contains(minimum.valueText()));
            assertTrue(html.contains(maximum.valueText()));
            assertTrue(html.contains("signals-waveform.png"));
            assertTrue(html.contains("route-map.png"));
            assertTrue(html.contains("Engineering Events"));
            assertTrue(html.contains("Engineering KPIs"));
            assertTrue(html.contains("GPS Route Evidence"));
            assertTrue(html.contains("not a supported speed unit"));
            assertTrue(html.contains("Not correlated with a route."));
            assertFalse(html.contains("TEMPERATURE_CHANNEL"));
            assertFalse(html.contains("PASS"));
            assertFalse(html.contains("http://"));
            assertFalse(html.contains("https://"));
            assertFalse(html.contains("ALTITUDE"));

            int imagesEmbedded = 0;
            try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
                String pdfText = new PDFTextStripper().getText(document);
                String packed = pdfText.replaceAll("\\s+", "");
                assertTrue(packed.contains(CH01));
                assertTrue(packed.contains(EVENT.replaceAll("\\s+", "")));
                assertTrue(packed.contains(start.replaceAll("\\s+", "")), start);
                assertTrue(packed.contains(minimum.valueText()));
                assertTrue(packed.contains(maximum.valueText()));
                assertTrue(packed.contains("EngineeringEvents"));
                assertTrue(packed.contains("EngineeringKPIs"));
                assertTrue(packed.contains("GPSRouteEvidence"));
                String distance = map.section("route-map.png").distanceLabel().replaceAll("\\s+", "");
                assertTrue(packed.contains(distance), map.section("route-map.png").distanceLabel());
                assertTrue(html.contains(map.section("route-map.png").distanceLabel()));
                assertFalse(pdfText.contains("PASS"));
                assertFalse(pdfText.contains("TEMPERATURE_CHANNEL"));
                for (PDPage page : document.getPages()) {
                    if (page.getResources() == null) {
                        continue;
                    }
                    for (var name : page.getResources().getXObjectNames()) {
                        PDXObject object = page.getResources().getXObject(name);
                        if (object instanceof PDImageXObject image && image.getWidth() >= 1000) {
                            imagesEmbedded++;
                        }
                    }
                }
                Path pages = sampleDir.resolve("engineering-report-v2-m9-pages");
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
            assertTrue(imagesEmbedded >= 2, "embedded images " + imagesEmbedded);
        } finally {
            if (processed.catalogStore() instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private static String decimal(double value) {
        return new java.math.BigDecimal(Double.toString(value))
                .setScale(6, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
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
