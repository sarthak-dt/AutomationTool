package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineeringReportSection;
import com.stellar.platform.analysis.investigation.EngineeringReportTemplate;
import com.stellar.platform.analysis.investigation.InvestigationStatus;
import com.stellar.platform.analysis.investigation.report.EngineeringHtmlReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringPdfReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModel;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModelBuilder;
import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.AggregatedDiagnostic;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Builds the Engineering Report V2 sample from the decoded Sierra thermocouple measurement.
 * The chart is {@link SignalReportRenderer} output. No event, cycle, or pass/fail result is added.
 */
class EngineeringReportV2SierraChartTest {

    private static final Path ROOT = Path.of("C:/Users/DT - User/Downloads/Mahima_Time_Issue");
    private static final Path MF4 = ROOT.resolve("tata_sierra_0002391_20260921_133054 (1).mf4");
    private static final Path DBC = ROOT.resolve("TEMP DBC 1 (1).dbc");
    private static final String CH01 = "CH01_FRONT_BUMPER_GRILL_AMBIENT";

    @Test
    @Timeout(180)
    void ch01WaveformReportUsesDecodedSamples() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC),
                "Tata Sierra MF4 and TEMP DBC are not on this machine");

        WorkspaceFile measurement = new WorkspaceFile(MF4, FileKind.MEASUREMENT_MF4, Files.size(MF4));
        WorkspaceFile definition = new WorkspaceFile(DBC, FileKind.DEFINITION_DBC, Files.size(DBC));
        LocalProcessingGateway.GatewayResult processed = LocalProcessingGateway.createDefault().process(
                measurement, definition, 512, new SignalPreviewBuffer(64), ProcessingProgressListener.NOOP, null);
        try {
            ProjectProcessingStatus status = processed.summary().completionStatus();
            assertTrue(status == ProjectProcessingStatus.COMPLETED
                            || status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS,
                    processed.summary().userMessage());
            SignalSeriesStore store = processed.catalogStore();
            SignalCatalogEntry ch01 = null;
            List<SignalCatalogEntry> entries = new ArrayList<>(store.snapshot().entries());
            entries.sort(Comparator.comparing(SignalCatalogEntry::signalName));
            for (SignalCatalogEntry entry : entries) {
                if (CH01.equals(entry.signalName())) {
                    ch01 = entry;
                }
            }
            assertTrue(ch01 != null, "decoded catalog has no " + CH01);
            List<SignalSamplePoint> samples = readAll(store, ch01);
            assertEquals(ch01.sampleCount(), samples.size());
            assertEquals(18_007, samples.size());
            assertTrue(ch01.unit() != null && ch01.unit().contains("C"), ch01.unit());

            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            int usable = 0;
            double[] time = new double[samples.size()];
            double[] values = new double[samples.size()];
            SignalValidity[] validity = new SignalValidity[samples.size()];
            for (int i = 0; i < samples.size(); i++) {
                SignalSamplePoint sample = samples.get(i);
                time[i] = sample.timestampNanos() / 1_000_000_000d;
                values[i] = sample.physicalValue();
                validity[i] = sample.validity();
                if (sample.usableMeasurement() && Double.isFinite(sample.physicalValue())) {
                    usable++;
                    min = Math.min(min, sample.physicalValue());
                    max = Math.max(max, sample.physicalValue());
                }
            }
            assertEquals(samples.size(), usable);
            assertTrue(max > min);
            assertEquals(ch01.minPhysical(), min, 1e-6);
            assertEquals(ch01.maxPhysical(), max, 1e-6);
            assertEquals(ch01.firstTimestampNanos(), samples.getFirst().timestampNanos());
            assertEquals(ch01.lastTimestampNanos(), samples.getLast().timestampNanos());

            String unit = ch01.unit();
            String fileName = MF4.getFileName().toString();
            String minText = decimal(min);
            String maxText = decimal(max);
            String range = seconds(samples.getFirst().timestampNanos())
                    + " s to " + seconds(samples.getLast().timestampNanos()) + " s";
            String span = seconds(samples.getLast().timestampNanos() - samples.getFirst().timestampNanos()) + " s";
            String caption = CH01 + " | " + samples.size() + " samples | minimum " + minText
                    + " " + unit + " | maximum " + maxText + " " + unit + " | " + fileName;

            SignalReportRenderer.Track track = new SignalReportRenderer.Track(
                    CH01, unit, time, values, Double.NaN, Double.NaN, validity);
            SignalReportRenderer.ChartLayout layout = SignalReportRenderer.layout(List.of(track));
            assertTrue(layout.tracks().getFirst().yMin() < min);
            assertTrue(layout.tracks().getFirst().yMax() > max);
            assertTrue(layout.tracks().getFirst().xMin() <= time[0]);
            assertTrue(layout.tracks().getFirst().xMax() >= time[time.length - 1]);
            List<SignalReportImage> images = SignalReportRenderer.render(List.of(track), null);
            assertEquals(1, images.size());
            assertFalse(images.getFirst().failed());
            assertTrue(images.getFirst().png() != null && images.getFirst().png().length > 1000);
            BufferedImage chart = ImageIO.read(new ByteArrayInputStream(images.getFirst().png()));
            assertEquals(SignalReportRenderer.WIDTH, chart.getWidth());
            assertTrue(chart.getHeight() > 120);
            assertTrue(distinctColors(chart) > 20, "waveform image has too few colors");

            Path sampleDir = sampleDir();
            Files.createDirectories(sampleDir);
            Path png = sampleDir.resolve("signals-waveform.png");
            Files.write(png, images.getFirst().png());

            List<EngineeringReportModel.SignalRow> rows = new ArrayList<>();
            for (SignalCatalogEntry entry : entries) {
                rows.add(new EngineeringReportModel.SignalRow(
                        entry.signalName(),
                        entry.unit(),
                        entry.sampleCount() + " samples",
                        entry.minPhysical() == null ? "" : decimal(entry.minPhysical()),
                        entry.maxPhysical() == null ? "" : decimal(entry.maxPhysical()),
                        "", "", ""));
            }
            List<String> warnings = new ArrayList<>();
            for (AggregatedDiagnostic diagnostic : processed.summary().diagnostics()) {
                warnings.add(diagnostic.layer() + " " + diagnostic.code() + ": "
                        + diagnostic.message() + " (" + diagnostic.count() + ")");
            }
            List<String> limitations = new ArrayList<>();
            limitations.add("The waveform uses the original CH01 samples. It is not resampled onto a fixed time base.");
            limitations.add("Duration is the span from the first CH01 sample to the last, on the measurement-relative clock.");
            if (processed.summary().mf4FileStatus() != null && !processed.summary().mf4FileStatus().isBlank()) {
                limitations.add("MF4 file status: " + processed.summary().mf4FileStatus());
            }
            if (processed.summary().recoveryUsed()) {
                limitations.add("Recovery was used while reading this MF4.");
            }

            String size = String.format(Locale.ROOT, "%.1f MB", Files.size(MF4) / (1024d * 1024d));
            EngineeringReportModel model = EngineeringReportModel.builder()
                    .template(EngineeringReportTemplate.ENGINEERING_V2)
                    .sections(List.of(EngineeringReportSection.values()))
                    .timestampBasis(EngineeringReportModelBuilder.TIMESTAMP_BASIS)
                    .header(new EngineeringReportModel.Header(
                            "CH01 thermocouple measurement", "", fileName, Instant.now(), "", ""))
                    .project(new EngineeringReportModel.ProjectSection(
                            fileName, 1, 1, 1, rows.size(), status.name(), warnings.size()))
                    .investigation(new EngineeringReportModel.InvestigationSection(
                            fileName, "", InvestigationStatus.DRAFT, range, span, 1))
                    .sources(List.of(new EngineeringReportModel.SourceRow(
                            fileName, "MF4", "", size, status.name())))
                    .definitions(List.of(new EngineeringReportModel.DefinitionRow(
                            DBC.getFileName().toString(), "DBC", status.name())))
                    .signals(rows)
                    .figures(List.of(new EngineeringReportModel.Figure(1, caption, png.getFileName().toString(), range)))
                    .warnings(warnings)
                    .limitations(limitations)
                    .build();

            String html = EngineeringHtmlReportRenderer.render(model);
            Path pdf = sampleDir.resolve("engineering-report-v2-sample.pdf");
            Path htmlFile = sampleDir.resolve("engineering-report-v2-sample.html");
            EngineeringPdfReportRenderer.write(model, pdf, sampleDir);
            Files.writeString(htmlFile, html);
            Files.deleteIfExists(sampleDir.resolve("graph.png"));

            assertTrue(html.contains(CH01));
            assertTrue(html.contains(fileName));
            assertTrue(html.contains(Integer.toString(samples.size())));
            assertTrue(html.contains(minText));
            assertTrue(html.contains(maxText));
            assertTrue(html.contains(range));
            assertTrue(html.contains(unit));
            assertTrue(html.contains("signals-waveform.png"));
            assertTrue(html.contains("DBC: " + DBC.getFileName()));
            assertTrue(html.contains("Recorded signals: " + rows.size()));
            assertFalse(html.contains("TEMPERATURE_CHANNEL"));
            assertFalse(html.contains("<script>"));
            assertFalse(html.contains("http://"));
            assertFalse(html.contains("https://"));
            assertFalse(html.contains("PASS"));
            assertFalse(html.contains("Executive Summary"));
            assertFalse(html.contains("Engineering KPIs"));
            assertFalse(html.contains("Signal Analysis"));
            assertTrue(html.contains("Graph Evidence"));
            assertTrue(html.contains("Event detection"));
            assertTrue(html.contains("Processing diagnostics"));
            assertTrue(html.contains("UNMATCHED_CAN_ID"));

            int embeddedWidth = 0;
            try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
                assertTrue(document.getNumberOfPages() >= 2);
                PDFTextStripper stripper = new PDFTextStripper();
                String pdfText = stripper.getText(document);
                assertTrue(compact(pdfText).contains(compact(fileName)));
                assertTrue(pdfText.contains(CH01));
                assertTrue(pdfText.contains(Integer.toString(samples.size())));
                assertTrue(pdfText.contains(minText));
                assertTrue(pdfText.contains(maxText));
                assertTrue(pdfText.contains(seconds(samples.getFirst().timestampNanos())));
                assertTrue(pdfText.contains(seconds(samples.getLast().timestampNanos())));
                assertTrue(pdfText.contains("Graph Evidence"));
                assertFalse(pdfText.contains("TEMPERATURE_CHANNEL"));
                assertFalse(pdfText.contains("PASS"));
                assertFalse(pdfText.contains("Executive Summary"));
                assertFalse(pdfText.contains("Signal Analysis"));
                assertTrue(pdfText.contains("Event detection"));
                assertTrue(pdfText.contains("Processing diagnostics"));
                assertTrue(pdfText.contains("UNMATCHED_CAN_ID"));
                assertTrue(pdfText.contains("Page 1 of"));
                for (int page = 1; page <= document.getNumberOfPages(); page++) {
                    PDFTextStripper one = new PDFTextStripper();
                    one.setStartPage(page);
                    one.setEndPage(page);
                    String text = one.getText(document);
                    assertTrue(text.contains("CH01") || text.contains("StellarAI") || text.contains(fileName),
                            "page " + page + " has no report text");
                    assertTrue(text.replace("Page " + page + " of " + document.getNumberOfPages(), "")
                            .trim().length() > 40, "page " + page + " is blank");
                }
                PDFTextStripper first = new PDFTextStripper();
                first.setStartPage(1);
                first.setEndPage(1);
                String firstText = first.getText(document);
                assertTrue(compact(firstText).contains(compact(fileName)));
                for (PDPage page : document.getPages()) {
                    if (page.getResources() == null) {
                        continue;
                    }
                    for (var name : page.getResources().getXObjectNames()) {
                        PDXObject object = page.getResources().getXObject(name);
                        if (object instanceof PDImageXObject image) {
                            embeddedWidth = Math.max(embeddedWidth, image.getWidth());
                        }
                    }
                }
                Path pages = sampleDir.resolve("engineering-report-v2-pages");
                if (Files.isDirectory(pages)) {
                    try (var listing = Files.list(pages)) {
                        for (Path old : listing.toList()) {
                            Files.deleteIfExists(old);
                        }
                    }
                }
                Files.createDirectories(pages);
                PDFRenderer renderer = new PDFRenderer(document);
                for (int i = 0; i < document.getNumberOfPages(); i++) {
                    BufferedImage rendered = renderer.renderImageWithDPI(i, 110);
                    ImageIO.write(rendered, "png", pages.resolve("page-" + (i + 1) + ".png").toFile());
                }
            }
            assertTrue(embeddedWidth >= 1000, "embedded chart width " + embeddedWidth);

            String facts = "file=" + fileName + "\ndbc=" + DBC.getFileName()
                    + "\nsignal=" + CH01 + "\nunit=" + unit
                    + "\nsamples=" + samples.size() + "\nminimum=" + minText
                    + "\nmaximum=" + maxText + "\nrange=" + range + "\nspan=" + span
                    + "\nstatus=" + status + "\nsignals=" + rows.size()
                    + "\nwarnings=" + warnings.size() + "\n";
            Files.writeString(sampleDir.resolve("engineering-report-v2-ch01-facts.txt"), facts);
            System.out.println(facts);
        } finally {
            if (processed.catalogStore() instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
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

    private static String seconds(long nanos) {
        return BigDecimal.valueOf(nanos, 9).stripTrailingZeros().toPlainString();
    }

    private static int distinctColors(BufferedImage image) {
        Set<Integer> colors = new HashSet<>();
        int stepX = Math.max(1, image.getWidth() / 80);
        int stepY = Math.max(1, image.getHeight() / 40);
        for (int y = 0; y < image.getHeight(); y += stepY) {
            for (int x = 0; x < image.getWidth(); x += stepX) {
                colors.add(image.getRGB(x, y));
            }
        }
        return colors.size();
    }

    private static Path sampleDir() {
        Path cwd = Path.of("").toAbsolutePath();
        Path repo = "stellar-desktop".equals(cwd.getFileName().toString()) ? cwd.getParent() : cwd;
        Path rootTarget = repo.resolve("target");
        if (Files.isDirectory(rootTarget) || Files.isDirectory(repo)) {
            return rootTarget;
        }
        return cwd.resolve("target");
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }
}
