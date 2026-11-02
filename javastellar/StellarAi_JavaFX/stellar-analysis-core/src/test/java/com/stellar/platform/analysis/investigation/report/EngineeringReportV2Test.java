package com.stellar.platform.analysis.investigation.report;

import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EngineeringObservation;
import com.stellar.platform.analysis.investigation.EngineeringReportSection;
import com.stellar.platform.analysis.investigation.EngineeringReportTemplate;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.analysis.investigation.InvestigationStatus;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineeringReportV2Test {

    private static final String SIGNAL = "CH01_FRONT_BUMPER_GRILL_AMBIENT";
    private static final String SOURCE = "tata_sierra_0002391_20260921_133054 (1).mf4";
    private static final String MEASURED = "27.4";

    @TempDir
    Path temp;

    @Test
    void oldReportConfigStaysClassicAndV2RoundTrips() {
        InvestigationReportConfig ten = InvestigationReportConfig.decode("1111111110");
        InvestigationReportConfig eleven = InvestigationReportConfig.decode("11111111101");
        assertEquals(EngineeringReportTemplate.CLASSIC, ten.template());
        assertEquals(EngineeringReportTemplate.CLASSIC, eleven.template());
        assertFalse(ten.shows(EngineeringReportSection.COVER));

        int hiddenGps = EngineeringReportSection.allMask()
                & ~(1 << EngineeringReportSection.GPS_ROUTE.ordinal());
        InvestigationReportConfig v2 = InvestigationReportConfig.engineeringV2()
                .withTemplate(EngineeringReportTemplate.ENGINEERING_V2, hiddenGps);
        InvestigationReportConfig decoded = InvestigationReportConfig.decode(v2.encode());
        assertEquals(EngineeringReportTemplate.ENGINEERING_V2, decoded.template());
        assertTrue(decoded.shows(EngineeringReportSection.GRAPH_EVIDENCE));
        assertFalse(decoded.shows(EngineeringReportSection.GPS_ROUTE));
        assertEquals(v2.encode(), decoded.encode());
    }

    @Test
    void builderKeepsClassicLayoutAndSelectsV2Sections() {
        var context = new EngineeringReportModelBuilder.Context(
                "1.0", "Ada", null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        EngineeringReportModel classic = EngineeringReportModelBuilder.build(
                EngineeringInvestigation.builder("project").name("Existing").build(), context);
        assertEquals(EngineeringReportTemplate.CLASSIC, classic.template());
        assertTrue(classic.sections().isEmpty());

        int hiddenGps = EngineeringReportSection.allMask()
                & ~(1 << EngineeringReportSection.GPS_ROUTE.ordinal());
        EngineeringInvestigation investigation = EngineeringInvestigation.builder("project")
                .name("Sierra")
                .description("Engineer summary")
                .reportConfig(InvestigationReportConfig.engineeringV2()
                        .withTemplate(EngineeringReportTemplate.ENGINEERING_V2, hiddenGps))
                .build();
        EngineeringReportModel v2 = EngineeringReportModelBuilder.build(investigation, context);
        assertEquals(EngineeringReportTemplate.ENGINEERING_V2, v2.template());
        assertTrue(v2.shows(EngineeringReportSection.COVER));
        assertFalse(v2.shows(EngineeringReportSection.GPS_ROUTE));
        assertTrue(v2.timestampBasis().contains("measurement-relative"));
        assertTrue(v2.analysis().isEmpty());
        assertTrue(v2.diagnostics().isEmpty());
    }

    @Test
    void htmlAndPdfShareFactsHonourSectionsAndStayOffline() throws Exception {
        Path graph = writePng(temp.resolve("graph.png"), 400, 2000);
        Path route = writePng(temp.resolve("route.png"), 120, 60);
        int hiddenGps = EngineeringReportSection.allMask()
                & ~(1 << EngineeringReportSection.GPS_ROUTE.ordinal());
        List<EngineeringReportSection> sections = new ArrayList<>();
        for (EngineeringReportSection section : EngineeringReportSection.values()) {
            if ((hiddenGps & (1 << section.ordinal())) != 0) {
                sections.add(section);
            }
        }
        List<EngineeringReportModel.SignalRow> signals = new ArrayList<>();
        signals.add(new EngineeringReportModel.SignalRow(
                SIGNAL, "°C", SOURCE, MEASURED, "31.0", "", "", ""));
        for (int i = 0; i < 48; i++) {
            signals.add(new EngineeringReportModel.SignalRow(
                    "Appendix inventory signal " + i + " catalog-name-padding-for-pagination",
                    "text", SOURCE, "0", "1", "", "", ""));
        }
        EngineeringReportModel model = EngineeringReportModel.builder()
                .template(EngineeringReportTemplate.ENGINEERING_V2)
                .sections(sections)
                .timestampBasis(EngineeringReportModelBuilder.TIMESTAMP_BASIS)
                .header(new EngineeringReportModel.Header(
                        "Sierra café report", "Tata Sierra", "Sierra",
                        Instant.parse("2026-09-21T13:30:54Z"), "2.0", "Ada"))
                .project(new EngineeringReportModel.ProjectSection(
                        "Tata Sierra", 1, 1, 1, 2, "COMPLETED", 0))
                .investigation(new EngineeringReportModel.InvestigationSection(
                        "Sierra", "<script>alert(1)</script>", InvestigationStatus.DRAFT,
                        "0.085 s – 1800.000 s", "1799.915 s", 1))
                .sources(List.of(new EngineeringReportModel.SourceRow(
                        SOURCE, "MF4", "", "12 MB", "COMPLETED")))
                .signals(signals)
                .definitions(List.of(new EngineeringReportModel.DefinitionRow(
                        "TEMP DBC 1 (1).dbc", "DBC", "Matched")))
                .figures(List.of(new EngineeringReportModel.Figure(
                        1, SIGNAL + "  " + MEASURED + " °C", "graph.png", "0.085 s – 1800.000 s")))
                .signalAnalysis(List.of(new SignalAnalysisSection(SIGNAL, "graph.png", "")))
                .routeMap(new EngineeringReportModel.RouteMapSection(
                        "Verified GPS route", "route.png", "", "start", "end", "1 s", "1 km", "2"))
                .observations(List.of(new EngineeringObservation(
                        "obs", "inv", null, null, "Grill note", "café observation", Instant.now(), Instant.now())))
                .conclusion("")
                .limitations(List.of("Original samples are retained."))
                .build();

        String html = EngineeringHtmlReportRenderer.render(model);
        Path pdf = temp.resolve("engineering-report-v2.pdf");
        EngineeringPdfReportRenderer.write(model, pdf, temp);
        String pdfText;
        String pageOne;
        String pageTwo;
        int pages;
        boolean embedded = false;
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            pages = document.getNumberOfPages();
            pdfText = new PDFTextStripper().getText(document);
            PDFTextStripper one = new PDFTextStripper();
            one.setStartPage(1);
            one.setEndPage(1);
            pageOne = one.getText(document);
            PDFTextStripper two = new PDFTextStripper();
            two.setStartPage(2);
            two.setEndPage(Math.min(2, pages));
            pageTwo = two.getText(document);
            for (PDPage page : document.getPages()) {
                if (page.getResources() == null) {
                    continue;
                }
                for (var name : page.getResources().getXObjectNames()) {
                    PDXObject object = page.getResources().getXObject(name);
                    if (object instanceof PDImageXObject) {
                        embedded = true;
                    }
                }
            }
        }

        assertTrue(html.contains(SIGNAL));
        assertTrue(compact(pdfText).contains(SIGNAL));
        assertTrue(html.contains(SOURCE));
        assertTrue(compact(pdfText).contains(compact(SOURCE)));
        assertTrue(html.contains(MEASURED));
        assertTrue(pdfText.contains(MEASURED));
        assertTrue(html.contains("°C") || html.contains("&#176;C") || html.contains("&deg;C"));
        assertTrue(pdfText.contains("°C") || pdfText.contains("C"));
        assertTrue(html.contains("caf") );
        assertTrue(pdfText.contains("caf"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("http://"));
        assertFalse(html.contains("https://"));
        assertTrue(html.contains("Contents"));
        assertTrue(html.contains("Recorded signals: 49"));
        assertTrue(pdfText.contains("Recorded signals: 49"));
        assertTrue(html.contains("DBC: TEMP DBC 1 (1).dbc"));
        assertTrue(pdfText.contains("DBC: TEMP DBC 1 (1).dbc"));
        assertTrue(html.contains("Processing: COMPLETED"));
        assertTrue(pdfText.contains("Processing: COMPLETED"));
        assertFalse(html.contains("Signal Analysis"));
        assertFalse(pdfText.contains("Signal Analysis"));
        assertTrue(html.contains("Graph Evidence"));
        assertTrue(pdfText.contains("Graph Evidence"));
        assertFalse(html.contains("Engineering KPIs"));
        assertFalse(pdfText.contains("Engineering KPIs"));
        assertFalse(html.contains("No engineering conclusion has been entered."));
        assertFalse(pdfText.contains("No engineering conclusion has been entered."));
        assertFalse(html.contains("TEMPERATURE_CHANNEL"));
        assertFalse(pdfText.contains("TEMPERATURE_CHANNEL"));
        assertTrue(compact(pageOne).contains(compact(SOURCE)));
        assertFalse(pageOne.contains("Graph Evidence"));
        assertTrue(pageTwo.contains("Graph Evidence"));
        assertTrue(pageTwo.contains(SIGNAL));
        assertFalse(html.contains("GPS Route Evidence"));
        assertFalse(pdfText.contains("GPS Route Evidence"));
        assertFalse(html.contains("Verified GPS route"));
        assertFalse(pdfText.contains("Verified GPS route"));
        assertTrue(html.contains("graph.png"));
        assertTrue(html.contains("0.085 s"));
        assertTrue(pdfText.contains("0.085 s"));
        assertTrue(html.contains("measurement-relative"));
        assertTrue(pdfText.contains("measurement-relative"));
        assertTrue(html.contains("Event detection"));
        assertTrue(pdfText.contains("Event detection"));
        assertFalse(html.contains("PASS"));
        assertFalse(pdfText.contains("PASS"));
        assertTrue(pdfText.contains("Page 1 of"));
        assertTrue(pages >= 2);
        assertTrue(embedded);
        assertTrue(count(pdfText, "Unit") >= 2);
        assertTrue(graph.toFile().isFile());
    }

    @Test
    void shortCoverFlowsIntoTheOverviewAndBlankSectionsStayOut() throws Exception {
        EngineeringReportModel model = EngineeringReportModel.builder()
                .template(EngineeringReportTemplate.ENGINEERING_V2)
                .sections(List.of(EngineeringReportSection.values()))
                .timestampBasis(EngineeringReportModelBuilder.TIMESTAMP_BASIS)
                .header(new EngineeringReportModel.Header(
                        "Short report", "", "Short", Instant.parse("2026-09-21T13:30:54Z"), "", ""))
                .sources(List.of(new EngineeringReportModel.SourceRow(
                        SOURCE, "MF4", "", "1 MB", "COMPLETED")))
                .build();
        String html = EngineeringHtmlReportRenderer.render(model);
        Path pdf = temp.resolve("short.pdf");
        EngineeringPdfReportRenderer.write(model, pdf, temp);
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            assertEquals(1, document.getNumberOfPages());
            String text = new PDFTextStripper().getText(document);
            assertTrue(compact(text).contains(compact(SOURCE)));
            assertTrue(text.contains("Short report"));
            assertFalse(text.contains("Executive Summary"));
            assertFalse(text.contains("Engineering KPIs"));
            assertFalse(text.contains("Signal / DBC Appendix"));
        }
        assertFalse(html.contains("Executive Summary"));
        assertFalse(html.contains("Engineering KPIs"));
        assertTrue(html.contains(SOURCE));
    }

    @Test
    void distinctWaveformsBothRemainAndABlankRouteIsOmitted() throws Exception {
        writePng(temp.resolve("analysis.png"), 80, 40);
        writePng(temp.resolve("graph.png"), 80, 40);
        EngineeringReportModel model = EngineeringReportModel.builder()
                .template(EngineeringReportTemplate.ENGINEERING_V2)
                .sections(List.of(EngineeringReportSection.values()))
                .timestampBasis(EngineeringReportModelBuilder.TIMESTAMP_BASIS)
                .header(new EngineeringReportModel.Header(
                        "Two figures", "Project", "Two", Instant.parse("2026-09-21T13:30:54Z"), "", "Ada"))
                .signalAnalysis(List.of(new SignalAnalysisSection("Unique analysis", "analysis.png", "")))
                .figures(List.of(new EngineeringReportModel.Figure(1, "Unique graph", "graph.png", "1 s to 2 s")))
                .routeMap(new EngineeringReportModel.RouteMapSection(
                        "Missing route", "", "", "", "", "", "", ""))
                .build();
        String html = EngineeringHtmlReportRenderer.render(model);
        assertTrue(html.contains("Signal Analysis"));
        assertTrue(html.contains("Unique analysis"));
        assertTrue(html.contains("Graph Evidence"));
        assertTrue(html.contains("Unique graph"));
        assertTrue(html.contains("analysis.png"));
        assertTrue(html.contains("graph.png"));
        assertTrue(html.contains("GPS Route Evidence"));
        assertTrue(html.contains("No GPS route is attached"));
        assertFalse(html.contains("Missing route"));
    }

    private static Path writePng(Path file, int width, int height) throws Exception {
        BufferedImage picture = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = picture.createGraphics();
        graphics.setColor(Color.DARK_GRAY);
        graphics.fillRect(0, 0, 120, 60);
        graphics.dispose();
        ImageIO.write(picture, "png", file.toFile());
        return file;
    }

    private static String compact(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private static int count(String text, String token) {
        int n = 0;
        int from = 0;
        while (from >= 0) {
            from = text.indexOf(token, from);
            if (from < 0) {
                break;
            }
            n++;
            from += token.length();
        }
        return n;
    }
}
