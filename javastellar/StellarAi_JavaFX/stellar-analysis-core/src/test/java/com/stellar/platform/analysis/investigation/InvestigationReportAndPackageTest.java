package com.stellar.platform.analysis.investigation;

import com.stellar.platform.analysis.investigation.report.EngineeringHtmlReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModel;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModelBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("release-acceptance")
class InvestigationReportAndPackageTest {

    @TempDir
    Path temp;

    @Test
    void htmlEscapesUserTextAndOmitsOptionalSections() {
        EngineeringInvestigation inv = EngineeringInvestigation.builder("p1")
                .name("Brake <Response>")
                .description("Pedal & pressure")
                .conclusion("User wrote <script>alert(1)</script>")
                .reportConfig(new InvestigationReportConfig(
                        false, false, false, false, false, false, false, false, false, false))
                .observations(List.of(new EngineeringObservation(
                        "o1", "inv", null, null, "Note", "ampersand & quote \"", Instant.now(), Instant.now())))
                .build();
        EngineeringReportModel model = EngineeringReportModelBuilder.build(inv, new EngineeringReportModelBuilder.Context(
                "StellarAI 0.1.0", "Pat", null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of()));
        String html = EngineeringHtmlReportRenderer.render(model);
        assertTrue(html.contains("&lt;script&gt;"));
        assertFalse(html.contains("<script>alert"));
        assertTrue(html.contains("&amp;"));
        assertFalse(html.contains("cdn."));
        assertFalse(html.contains("googleapis"));
        assertFalse(html.contains("<script src"));
        assertTrue(html.contains("@media print"));
        assertFalse(html.contains("1. Project summary"));
        assertTrue(html.contains("Engineering conclusion"));
    }

    @Test
    void htmlIncludesTablesFiguresStaleAndReproducibility() {
        EngineeringInvestigation inv = EngineeringInvestigation.builder("p1")
                .name("Oscillation")
                .evidence(List.of(new EngineeringEvidenceItem(
                        "e1", "inv", EvidenceKind.SPECTRAL_RESULT, "fft-1", 0, true,
                        "Source B was reprocessed.", "fpB", 1L, "FFT", "dominant 12.4 Hz", Instant.now())))
                .build();
        EngineeringReportModel model = EngineeringReportModel.builder()
                .header(new EngineeringReportModel.Header("Oscillation", "Run", "Oscillation",
                        Instant.now(), "StellarAI 0.1.0", ""))
                .project(new EngineeringReportModel.ProjectSection("Run", 2, 1, 1, 40, "COMPLETED", 1))
                .investigation(new EngineeringReportModel.InvestigationSection(
                        "Oscillation", "", InvestigationStatus.DRAFT, "1.000 s → 2.000 s", "1.000 s", 2))
                .signals(List.of(new EngineeringReportModel.SignalRow(
                        "VehicleSpeed", "km/h", "run.mf4", "0", "83.4", "40.2", "45.1", "12.35")))
                .analysis(List.of(new EngineeringReportModel.ResultBlock(
                        "Statistics", "Window 1.000 s – 2.000 s", false, "",
                        "Analysis version 1 · alignment EXACT_TIMESTAMP")))
                .diagnostics(List.of(new EngineeringReportModel.ResultBlock(
                        "WARNING  brake.slow-response", "Observed 420 ms, limit 250 ms", false, "",
                        "Rule brake.slow-response version 1")))
                .frequency(List.of(new EngineeringReportModel.ResultBlock(
                        "FFT VehicleSpeed", "Peak 12.4 Hz", true, "Source B was reprocessed.",
                        "fs=100 Hz · window=HANN · FFT size=1024 · engine 1")))
                .figures(List.of(new EngineeringReportModel.Figure(
                        1, "Brake pressure response", "assets/graph-001.png", "1.000 s → 2.000 s")))
                .warnings(List.of("Irregular sampling on channel 2"))
                .appendix(List.of("Investigation id: inv"))
                .health(InvestigationHealth.PARTIALLY_STALE)
                .evidence(inv.evidence())
                .build();
        String html = EngineeringHtmlReportRenderer.render(model);
        assertTrue(html.contains("<table>"));
        assertTrue(html.contains("VehicleSpeed"));
        assertTrue(html.contains("assets/graph-001.png"));
        assertTrue(html.contains("Figure 1"));
        assertTrue(html.contains("STALE"));
        assertTrue(html.contains("Rule brake.slow-response version 1"));
        assertTrue(html.contains("FFT size=1024"));
        assertTrue(html.contains("Irregular sampling"));
        assertTrue(html.contains("PARTIALLY_STALE"));
        assertFalse(html.contains("http://"));
    }

    @Test
    void packageExcludesRawFilesAndPrivatePaths() throws Exception {
        EngineeringInvestigation inv = EngineeringInvestigation.builder("p1")
                .investigationId("inv-1")
                .name("Brake Response Investigation")
                .build();
        EngineeringReportModel model = EngineeringReportModelBuilder.build(inv, emptyContext());
        Path png = temp.resolve("graph.png");
        Files.write(png, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});
        Path zip = temp.resolve("Brake_Response_Investigation.zip");
        InvestigationPackageExporter.write(zip, inv, model, List.of(png), List.of(
                new InvestigationPackageExporter.NamedText("findings.csv", InvestigationCsvExporter.findings(inv))),
                null);
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            assertTrue(zf.getEntry("manifest.json") != null);
            assertTrue(zf.getEntry("report/report.html") != null);
            assertTrue(zf.getEntry("evidence/graph-001.png") != null);
            assertTrue(zf.getEntry("exports/findings.csv") != null);
            assertTrue(zf.getEntry("metadata/investigation.json") != null);
            var names = zf.stream().map(e -> e.getName().toLowerCase()).toList();
            assertFalse(names.stream().anyMatch(n -> n.endsWith(".mf4")));
            assertFalse(names.stream().anyMatch(n -> n.endsWith(".dbc")));
            assertFalse(names.stream().anyMatch(n -> n.contains("stellar.db")));
            String manifest = new String(zf.getInputStream(zf.getEntry("manifest.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(manifest.contains("\"containsRawMf4\":false"));
            assertFalse(manifest.contains(System.getProperty("user.home")));
        }
    }

    @Test
    void formatsNumbersWithoutExcessPrecision() {
        assertEquals("12.35", EngineeringFormats.number(12.3489384765625d));
        assertEquals("83.40 km/h", EngineeringFormats.withUnit(83.4d, "km/h"));
        assertEquals("12.40 Hz", EngineeringFormats.withUnit(12.4d, "Hz"));
    }

    @Test
    void sanitizesPrivatePaths() {
        String home = System.getProperty("user.home");
        String sanitized = PathSanitizer.sanitize(home + "\\data\\run.mf4");
        assertFalse(sanitized.contains(home));
        assertEquals("run.mf4", PathSanitizer.displayName("C:\\Users\\ADMIN\\data\\run.mf4"));
    }

    @Test
    void selectiveStalenessMarksOnlyDependentEvidence() {
        EngineeringEvidenceItem a = new EngineeringEvidenceItem(
                "ea", "inv", EvidenceKind.ANALYSIS_RESULT, "anA", 0, false, "", "fpA", 1L,
                "A", "stats", Instant.now());
        EngineeringEvidenceItem b = new EngineeringEvidenceItem(
                "eb", "inv", EvidenceKind.DIAGNOSTIC_FINDING, "fB", 1, false, "", "fpB", 1L,
                "B", "finding", Instant.now());
        List<EngineeringEvidenceItem> refreshed = InvestigationStaleness.refreshAll(
                List.of(a, b), Set.of("fpA", "fpB2"));
        assertFalse(refreshed.get(0).stale());
        assertTrue(refreshed.get(1).stale());
        assertEquals(InvestigationHealth.PARTIALLY_STALE, InvestigationValidity.health(refreshed));
        assertFalse(InvestigationValidity.explanations(refreshed).isEmpty());
    }

    @Test
    void evidenceOrderIsPreserved() {
        List<EngineeringEvidenceItem> items = new ArrayList<>();
        items.add(item("e1", 2, "later"));
        items.add(item("e2", 0, "first"));
        items.add(item("e3", 1, "second"));
        items.sort(java.util.Comparator.comparingInt(EngineeringEvidenceItem::sortOrder));
        EngineeringInvestigation inv = EngineeringInvestigation.builder("p")
                .evidence(items)
                .build();
        assertEquals(List.of("first", "second", "later"),
                inv.evidence().stream().map(EngineeringEvidenceItem::caption).toList());
    }

    @Test
    void largeReportModelRendersWithoutExternalAssets() {
        List<EngineeringReportModel.SignalRow> signals = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            signals.add(new EngineeringReportModel.SignalRow("S" + i, "V", "src", "0", "1", "0.5", "0.6", "1"));
        }
        List<EngineeringReportModel.ResultBlock> findings = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            findings.add(new EngineeringReportModel.ResultBlock("F" + i, "body " + i, false, "", "rule v1"));
        }
        List<EngineeringReportModel.ResultBlock> analysis = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            analysis.add(new EngineeringReportModel.ResultBlock("A" + i, "stats", false, "", "analysis v1"));
        }
        List<EngineeringReportModel.Figure> figures = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            figures.add(new EngineeringReportModel.Figure(i, "Fig " + i, "assets/graph-" + i + ".png", "0-1 s"));
        }
        EngineeringReportModel model = EngineeringReportModel.builder()
                .header(new EngineeringReportModel.Header("Large", "P", "I", Instant.now(), "1", ""))
                .signals(signals)
                .diagnostics(findings)
                .analysis(analysis)
                .figures(figures)
                .build();
        String html = EngineeringHtmlReportRenderer.render(model);
        assertTrue(html.contains("S49"));
        assertTrue(html.contains("F99"));
        assertTrue(html.contains("assets/graph-20.png"));
        assertTrue(html.length() > 10_000);
    }

    private static EngineeringEvidenceItem item(String id, int order, String caption) {
        return new EngineeringEvidenceItem(id, "inv", EvidenceKind.SIGNAL, id, order, false, "",
                "", 0L, caption, "", Instant.now());
    }

    private static EngineeringReportModelBuilder.Context emptyContext() {
        return new EngineeringReportModelBuilder.Context(
                "StellarAI 0.1.0", "", null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of());
    }
}
