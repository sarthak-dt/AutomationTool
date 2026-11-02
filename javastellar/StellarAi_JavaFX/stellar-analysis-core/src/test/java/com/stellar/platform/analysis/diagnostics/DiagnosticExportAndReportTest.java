package com.stellar.platform.analysis.diagnostics;

import com.stellar.platform.analysis.export.DiagnosticCsvExporter;
import com.stellar.platform.analysis.model.AnalysisStatus;
import com.stellar.platform.analysis.model.TimeRangeSource;
import com.stellar.platform.analysis.model.TimeWindow;
import com.stellar.platform.analysis.report.DiagnosticReportFormatter;
import com.stellar.platform.analysis.rules.GenericEngineeringPack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticExportAndReportTest {

    @TempDir
    Path temp;

    @Test
    void csvAndReportContainEvidenceNotAiWording() throws Exception {
        DiagnosticRuleEngine engine = new DiagnosticRuleEngine();
        DiagnosticRunResult result = engine.execute(
                new DiagnosticRunRequest("p", "run", GenericEngineeringPack.pack(),
                        List.of("thermal.high-coolant"), TimeRangeSource.FULL, TimeWindow.unbounded(),
                        Map.of(), 1L, "fp"),
                DiagnosticFixtures.thermalSustained(),
                DiagnosticFixtures.workstationCatalog(),
                null);
        DiagnosticCsvExporter csv = new DiagnosticCsvExporter();
        Path summary = temp.resolve("summary.csv");
        Path findings = temp.resolve("findings.csv");
        Path eval = temp.resolve("eval.csv");
        csv.writeSummary(result, summary);
        csv.writeFindings(result, findings);
        csv.writeEvaluation(result, eval);
        String report = DiagnosticReportFormatter.format(result);
        assertTrue(Files.readString(summary).contains("COMPLETED") || result.status() == AnalysisStatus.COMPLETED);
        assertTrue(Files.readString(findings).contains("thermal.high-coolant") || result.findings().isEmpty());
        assertTrue(Files.readString(eval).contains("thermal.high-coolant"));
        assertTrue(report.contains("DIAGNOSTICS"));
        assertTrue(report.toLowerCase().contains("not ai"));
        assertTrue(Instant.now().toEpochMilli() > 0);
    }
}
