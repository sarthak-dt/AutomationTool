package com.stellar.platform.analysis.nft;

import com.stellar.platform.analysis.AutomotiveAnalysisFixtures;
import com.stellar.platform.analysis.diagnostics.DiagnosticRuleEngine;
import com.stellar.platform.analysis.diagnostics.DiagnosticRunRequest;
import com.stellar.platform.analysis.diagnostics.DiagnosticRunResult;
import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.InvestigationPackageExporter;
import com.stellar.platform.analysis.investigation.report.EngineeringHtmlReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModel;
import com.stellar.platform.analysis.model.AnalysisSeverity;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.TimeRangeSource;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.rules.CompareOp;
import com.stellar.platform.analysis.rules.DiagnosticRuleDefinition;
import com.stellar.platform.analysis.rules.DiagnosticRulePack;
import com.stellar.platform.analysis.rules.RuleCategory;
import com.stellar.platform.analysis.rules.RuleCondition;
import com.stellar.platform.analysis.rules.RuleEngineVersion;
import com.stellar.platform.analysis.rules.RuleInput;
import com.stellar.platform.analysis.rules.RuleOrigin;
import com.stellar.platform.analysis.rules.TemporalConstraint;
import com.stellar.platform.analysis.source.InMemoryAnalysisSampleSource;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("release-nft")
class ReleaseNftDiagnosticsReportTest {

    private final DiagnosticRuleEngine engine = new DiagnosticRuleEngine();

    @TempDir
    Path temp;

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void ruleScaleReportAndPackageStayMetadataBound() throws Exception {
        InMemoryAnalysisSampleSource src = new InMemoryAnalysisSampleSource();
        SignalCatalogKey unused = new SignalCatalogKey(
                "UnusedNoise", SignalSourceType.MF4_NATIVE, "MEAS", 0, 0, "synth.mf4", "");
        for (int i = 0; i < 8_000; i++) {
            src.add(com.stellar.platform.analysis.AutomotiveAnalysisFixtures.COOLANT, i * 1_000_000L, 90);
            src.add(unused, i * 1_000_000L, i);
        }
        int[] counts = {10, 50, 100, 500};
        for (int n : counts) {
            List<DiagnosticRuleDefinition> list = new ArrayList<>();
            Instant now = Instant.EPOCH;
            for (int i = 0; i < n; i++) {
                list.add(new DiagnosticRuleDefinition(
                        "r" + i, "1", "r" + i, "", RuleCategory.THERMAL, AnalysisSeverity.INFO, true,
                        List.of(RuleInput.required("COOLANT_TEMPERATURE", "°C", "CoolantTemperature")),
                        List.of(),
                        new RuleCondition.Comparison("COOLANT_TEMPERATURE", CompareOp.GTE, 200d, "°C",
                                TemporalConstraint.none()),
                        List.of(), List.of(), RuleOrigin.BUILT_IN, now, now));
            }
            long heap0 = heap();
            long t0 = System.nanoTime();
            DiagnosticRunResult result = engine.execute(
                    new DiagnosticRunRequest("p", "run-" + n,
                            new DiagnosticRulePack(1, "bench", "1", "b", "", RuleEngineVersion.CURRENT, list),
                            List.of(), TimeRangeSource.FULL, TimeWindow.unbounded(), Map.of(), 1L, "fp"),
                    src, catalog(), null);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            System.out.println("PHASE9C_DIAG rules=" + n + " durationMs=" + ms
                    + " executed=" + result.rulesExecuted() + " skipped=" + result.rulesSkipped()
                    + " findings=" + result.findings().size()
                    + " cacheHits=" + result.analysisCacheHits()
                    + " heapBefore=" + heap0 + " heapAfter=" + heap()
                    + " status=" + result.status());
            assertEquals(AnalysisStatus.COMPLETED, result.status());
            assertEquals(n, result.rulesExecuted());
        }

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
                .header(new EngineeringReportModel.Header("Large", "P", "I", Instant.now(), "1.0.0-rc1", ""))
                .signals(signals)
                .diagnostics(findings)
                .analysis(analysis)
                .figures(figures)
                .build();
        long t0 = System.nanoTime();
        long heap0 = heap();
        String html = EngineeringHtmlReportRenderer.render(model);
        long reportMs = (System.nanoTime() - t0) / 1_000_000L;
        Path htmlFile = temp.resolve("report.html");
        Files.writeString(htmlFile, html);
        System.out.println("PHASE9C_REPORT durationMs=" + reportMs + " bytes=" + html.length()
                + " heapBefore=" + heap0 + " heapAfter=" + heap());
        assertTrue(html.contains("S49"));
        assertFalse(html.contains("http://"));

        EngineeringInvestigation inv = EngineeringInvestigation.builder("p1")
                .investigationId("inv-large")
                .name("NFT Package")
                .build();
        List<Path> pngs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Path png = temp.resolve("g" + i + ".png");
            Files.write(png, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A});
            pngs.add(png);
        }
        Path zip = temp.resolve("pkg.zip");
        t0 = System.nanoTime();
        heap0 = heap();
        InvestigationPackageExporter.write(zip, inv, model, pngs, List.of(
                new InvestigationPackageExporter.NamedText("findings.csv", "id,text\n")), null);
        long pkgMs = (System.nanoTime() - t0) / 1_000_000L;
        System.out.println("PHASE9C_PACKAGE durationMs=" + pkgMs + " zipBytes=" + Files.size(zip)
                + " heapBefore=" + heap0 + " heapAfter=" + heap());
        try (ZipFile zf = new ZipFile(zip.toFile())) {
            assertTrue(zf.getEntry("report/report.html") != null);
            assertTrue(zf.getEntry("manifest.json") != null);
        }
    }

    private static List<SignalCatalogEntry> catalog() {
        return List.of(new SignalCatalogEntry(
                AutomotiveAnalysisFixtures.COOLANT, "°C", 20, 0L, 20_000_000_000L, 0d, 200d, "synth.mf4", "veh.dbc"));
    }

    private static long heap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
}
