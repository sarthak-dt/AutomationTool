package com.stellar.platform.analysis.investigation.report;

import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EngineeringObservation;
import com.stellar.platform.analysis.investigation.ReportAvailability;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineeringHtmlReportPhase1Test {

    @TempDir
    Path temp;

    @Test
    void filenameStripsWindowsReservedCharactersAndKeepsStamp() {
        Instant when = Instant.parse("2026-10-04T18:30:00Z");
        String zoneStamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm").withZone(ZoneId.systemDefault()).format(when);
        String name = ReportFileNames.suggest("QA <E2E> & \"1515\"", "GPS: Investigation?", when);
        assertTrue(name.endsWith("_" + zoneStamp + "_Report.html"));
        assertFalse(name.contains("<"));
        assertFalse(name.contains(">"));
        assertFalse(name.contains(":"));
        assertFalse(name.contains("\""));
        assertFalse(name.contains("?"));
        assertTrue(name.contains("QA_E2E"));
        assertEquals("Report", ReportFileNames.sanitize("   "));
        assertEquals("Report", ReportFileNames.sanitize("<<<>>>"));
        String longName = ReportFileNames.sanitize("N".repeat(200));
        assertTrue(longName.length() <= 80);
    }

    @Test
    void htmlEscapesQuotesApostrophesAndEmbedsGraphWithoutHttp() throws Exception {
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        String uri = GraphImageEmbed.pngDataUri(png);
        EngineeringInvestigation inv = EngineeringInvestigation.builder("project-a")
                .name("Brake <Response> \"A\"")
                .description("Pedal & pressure 'note'")
                .conclusion("")
                .build();
        EngineeringReportModel model = EngineeringReportModelBuilder.build(inv, new EngineeringReportModelBuilder.Context(
                "StellarAI 1.0.1",
                "",
                new EngineeringReportModel.ProjectSection(
                        "QA_E2E_20261004_1515", 1, 1, 1, 42, "Ready with warnings", 2),
                List.of(new EngineeringReportModel.SourceRow(
                        "C:\\Users\\ADMIN\\data\\OBD2_Data_Lavasa_0002260_20240305_142053.mf4",
                        "MF4", "2\t1200", "1.2 MB", "Processed with warnings")),
                List.of(new EngineeringReportModel.SignalRow(
                        "LATITUDE <raw>", "deg", "OBD2_Data_Lavasa_0002260_20240305_142053.mf4",
                        "0", "1", "—", "—", "—")),
                List.of(), List.of(), List.of(),
                List.of(new EngineeringReportModel.Figure(1, "GPS", uri, "LATITUDE")),
                List.of("Channel 2 completed with warnings")))
                .withProjectFacts(
                        List.of(new EngineeringReportModel.DefinitionRow("vehicle.dbc", "DBC", "Selected")),
                        new EngineeringReportModel.ProcessingDetail(
                                "Completed with warnings", 1, 0, 2, "2026-10-04 18:30:00", 1200),
                        List.of("Processing completed with warnings."));
        String html = EngineeringHtmlReportRenderer.render(model);
        Path sample = temp.resolve("sample.html");
        Files.writeString(sample, html, StandardCharsets.UTF_8);
        Path published = Path.of("target", "engineering-analysis-report-sample.html");
        Files.createDirectories(published.getParent());
        Files.writeString(published, html, StandardCharsets.UTF_8);

        assertTrue(html.contains("Application version"));
        assertTrue(html.contains("StellarAI 1.0.1"));
        assertFalse(html.contains("StellarAI 1.0.0"));
        assertTrue(html.contains("Engineering Analysis Report"));
        assertTrue(html.contains("Completed with warnings"));
        assertTrue(html.contains("Catalog entries"));
        assertTrue(html.contains("OBD2_Data_Lavasa_0002260_20240305_142053.mf4"));
        assertFalse(html.contains("C:\\Users\\ADMIN"));
        assertFalse(html.contains("Source A"));
        assertTrue(html.contains("vehicle.dbc"));
        assertTrue(html.contains("LATITUDE &lt;raw&gt;"));
        assertTrue(html.contains("Pedal &amp; pressure &#39;note&#39;"));
        assertTrue(html.contains("Brake &lt;Response&gt; &quot;A&quot;"));
        assertTrue(html.contains("No conclusion recorded."));
        assertTrue(html.contains("data:image/png;base64,"));
        assertFalse(html.contains("http://"));
        assertFalse(html.contains("https://"));
        assertFalse(html.contains("<script"));
        assertTrue(html.contains("@media print"));
        assertTrue(html.contains("No findings recorded.") == false);
        assertTrue(Files.size(sample) > 0);
    }

    @Test
    void emptyInvestigationCannotGenerateAndProjectsStayIsolated() {
        assertFalse(ReportAvailability.canGenerate(null));
        assertFalse(ReportAvailability.canGenerate(EngineeringInvestigation.builder(" ").name("x").build()));
        EngineeringInvestigation a = EngineeringInvestigation.builder("p-a").name("Alpha").build();
        EngineeringInvestigation b = EngineeringInvestigation.builder("p-b").name("Beta").build();
        assertTrue(ReportAvailability.canGenerate(a));
        String htmlA = EngineeringHtmlReportRenderer.render(EngineeringReportModelBuilder.build(
                a, context("Project Alpha", "alpha.mf4")));
        String htmlB = EngineeringHtmlReportRenderer.render(EngineeringReportModelBuilder.build(
                b, context("Project Beta", "beta.mf4")));
        assertTrue(htmlA.contains("Project Alpha"));
        assertTrue(htmlA.contains("alpha.mf4"));
        assertFalse(htmlA.contains("Project Beta"));
        assertFalse(htmlA.contains("beta.mf4"));
        assertTrue(htmlB.contains("beta.mf4"));
        assertFalse(htmlB.contains("alpha.mf4"));
    }

    @Test
    void observationsStayUserTextAndAreNotInvented() {
        EngineeringInvestigation inv = EngineeringInvestigation.builder("p")
                .name("Notes")
                .observations(List.of(new EngineeringObservation(
                        "o", "inv", null, null, "Finding", "Left turn & <speed>", Instant.parse("2026-10-04T00:00:00Z"),
                        Instant.parse("2026-10-04T00:00:00Z"))))
                .conclusion("Hold speed")
                .build();
        String html = EngineeringHtmlReportRenderer.render(EngineeringReportModelBuilder.build(
                inv, new EngineeringReportModelBuilder.Context(
                        "StellarAI 1.0.1", "", null, List.of(), List.of(),
                        List.of(), List.of(), List.of(), List.of(), List.of())));
        assertTrue(html.contains("Left turn &amp; &lt;speed&gt;"));
        assertTrue(html.contains("Hold speed"));
        assertFalse(html.contains("automatic"));
        assertTrue(html.contains("Not an automated diagnosis"));
    }

    @Test
    void categoricalLabelIsShownAndNumericRangeIsNotInvented() {
        EngineeringInvestigation inv = EngineeringInvestigation.builder("xenon").name("Fan").build();
        String html = EngineeringHtmlReportRenderer.render(EngineeringReportModelBuilder.build(
                inv, new EngineeringReportModelBuilder.Context(
                        "StellarAI 1.0.1", "", null, List.of(),
                        List.of(
                                new EngineeringReportModel.SignalRow(
                                        "ETC_Fan2_output", "", "Xenon.dat", "n/a", "n/a", "—", "—", "—", "FALSE"),
                                new EngineeringReportModel.SignalRow(
                                        "IN_Vehicle_speed", "km/h", "Xenon.dat", "23.09", "85.02", "—", "—", "—"),
                                new EngineeringReportModel.SignalRow(
                                        "Missing", "", "", "—", "—", "—", "—", "—")),
                        List.of(), List.of(), List.of(), List.of(), List.of())));
        assertTrue(html.contains("<th>Label</th>"));
        assertTrue(html.contains(">FALSE<"));
        assertTrue(html.contains(">n/a<"));
        assertTrue(html.contains(">23.09<"));
        assertFalse(html.contains(">0.0000<"));
        assertFalse(html.contains(">NaN<"));
    }

    private static EngineeringReportModelBuilder.Context context(String project, String file) {
        return new EngineeringReportModelBuilder.Context(
                "StellarAI 1.0.1",
                "",
                new EngineeringReportModel.ProjectSection(project, 1, 1, 0, 1, "Completed", 0),
                List.of(new EngineeringReportModel.SourceRow(file, "MF4", "1\t1", "1 KB", "Processed")),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
