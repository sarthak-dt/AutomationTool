package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineerEventDefinition;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.analysis.investigation.eval.EvaluationDocuments;
import com.stellar.platform.analysis.investigation.eval.EvaluationEngine;
import com.stellar.platform.analysis.investigation.eval.EvaluationOutcome;
import com.stellar.platform.analysis.investigation.eval.EvaluationResult;
import com.stellar.platform.analysis.investigation.eval.EvaluationRule;
import com.stellar.platform.analysis.investigation.eval.EvaluationSample;
import com.stellar.platform.analysis.investigation.eval.EvaluationSeries;
import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Evaluation against stored Tata Sierra samples.
 * Temperature bounds in this test are QA-only and are not customer criteria.
 */
class EngineeringReportV2M112EvaluationTest {

    private static final Path ROOT = Path.of("C:/Users/DT - User/Downloads/Mahima_Time_Issue");
    private static final Path MF4 = ROOT.resolve("tata_sierra_0002391_20260921_133054 (1).mf4");
    private static final Path DBC = ROOT.resolve("TEMP DBC 1 (1).dbc");
    private static final String CH01 = "CH01_FRONT_BUMPER_GRILL_AMBIENT";
    private static final String QA = "QA-only fixture. Not a customer-approved criterion.";

    @TempDir Path temp;

    @Test
    @Timeout(180)
    void sierraSamplesReproduceTheRuleVersionThatWasStored() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC));
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        WorkspaceFile measurement = new WorkspaceFile(MF4, FileKind.MEASUREMENT_MF4, Files.size(MF4));
        WorkspaceFile definition = new WorkspaceFile(DBC, FileKind.DEFINITION_DBC, Files.size(DBC));
        LocalProcessingGateway.GatewayResult processed = LocalProcessingGateway.createDefault().process(
                measurement, definition, 512, new SignalPreviewBuffer(64), ProcessingProgressListener.NOOP, null);
        DesktopAppService service = null;
        try {
            SignalSeriesStore store = processed.catalogStore();
            SignalCatalogEntry ch01 = null;
            for (SignalCatalogEntry candidate : store.snapshot().entries()) {
                if (CH01.equals(candidate.signalName())) {
                    ch01 = candidate;
                }
            }
            assertNotNull(ch01);
            assertNotNull(ch01.unit());
            assertFalse(ch01.unit().isBlank());
            List<SignalSamplePoint> primary = readAll(store, ch01);
            double kept = primary.get(primary.size() / 2).physicalValue();
            int rise = -1;
            int settle = -1;
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
            long start = primary.get(rise).timestampNanos();
            long end = primary.get(settle).timestampNanos();
            SignalSamplePoint point = primary.get(Math.min(100, primary.size() - 1));
            List<EvaluationSample> samples = new ArrayList<>();
            for (SignalSamplePoint sample : primary) {
                samples.add(new EvaluationSample(sample.timestampNanos(), sample.physicalValue(),
                        sample.usableMeasurement(), ""));
            }
            service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
            service.newProject("M112");
            service.addMeasurementPaths(List.of(MF4), DesktopAppService.PlatformUi.NOOP);
            String sourceId = "";
            for (var file : service.workspace().measurementFiles()) {
                if (file.fileName().contains("tata_sierra")) {
                    sourceId = file.id();
                }
            }
            assertFalse(sourceId.isBlank());
            EvaluationSeries series = new EvaluationSeries(CH01, ch01.unit(), sourceId, samples);
            var created = service.createInvestigation("Sierra evaluation", "QA evaluation", "FULL");
            service.saveInvestigation(created.toBuilder().reportConfig(InvestigationReportConfig.engineeringV2()).build());
            String windowId = service.saveEngineerEvent(new EngineerEventDefinition(
                    "", "Thermal window", "Thermal observation", sourceId, MF4.getFileName().toString(),
                    start, end, List.of(CH01), "Engineer-selected window.", "", "", List.of(), false, "Confirmed", "", "", ""));
            String pointId = service.saveEngineerEvent(new EngineerEventDefinition(
                    "", "Sensor check", "Sensor anomaly", sourceId, MF4.getFileName().toString(),
                    point.timestampNanos(), null, List.of(CH01), "Point event.", "", "", List.of(), false, "Draft", "", "", ""));
            String observation = service.findEngineeringBookmark(windowId).orElseThrow().observation();
            service.includeEngineerEvent(windowId);
            service.includeEngineerEvent(pointId);
            EngineerEventDefinition window = EngineerEventDocuments.decode(service.findEngineeringBookmark(windowId).orElseThrow());
            EngineerEventDefinition pointEvent = EngineerEventDocuments.decode(service.findEngineeringBookmark(pointId).orElseThrow());
            EvaluationRule meetsRule = service.saveEvaluationRule(qaRule(
                    "", EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE, null, 0.0d, 80.0d, "",
                    ch01.unit(), 2_000_000_000L, 0.5d, sourceId));
            EvaluationResult meets = service.evaluateAndStore(window, meetsRule, List.of(series), true, true);
            assertEquals(EvaluationOutcome.MEETS_CRITERION, meets.outcome());
            EvaluationRule failsRule = service.saveEvaluationRule(qaRule(
                    "", EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE, null, 40.0d, 80.0d, "",
                    ch01.unit(), 2_000_000_000L, 0.5d, sourceId));
            EvaluationResult fails = service.evaluateAndStore(window, failsRule, List.of(series), true, true);
            assertEquals(EvaluationOutcome.DOES_NOT_MEET_CRITERION, fails.outcome());
            EvaluationRule gapRule = service.saveEvaluationRule(qaRule(
                    "", EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE, null, 0.0d, 80.0d, "",
                    ch01.unit(), 1L, 0.5d, sourceId));
            EvaluationResult gap = service.evaluateAndStore(window, gapRule, List.of(series), true, true);
            assertEquals(EvaluationEngine.UNACCEPTABLE_TIMESTAMP_GAP, gap.reason());
            EvaluationRule unitRule = service.saveEvaluationRule(qaRule(
                    "", EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE, null, 0.0d, 80.0d, "",
                    "rpm", 2_000_000_000L, 0.5d, sourceId));
            EvaluationResult unit = service.evaluateAndStore(window, unitRule, List.of(series), true, true);
            assertEquals(EvaluationEngine.UNIT_INCOMPATIBLE, unit.reason());
            EvaluationRule pointRule = service.saveEvaluationRule(qaRule(
                    "", EvaluationRule.ComparisonOperator.POINT_SAMPLE, point.physicalValue(), null, null, "",
                    ch01.unit(), null, null, sourceId));
            EvaluationResult pointResult = service.evaluateAndStore(pointEvent, pointRule, List.of(series), true, true);
            assertEquals(EvaluationOutcome.MEETS_CRITERION, pointResult.outcome());
            EvaluationRule unused = service.saveEvaluationRule(qaRule(
                    "", EvaluationRule.ComparisonOperator.MAXIMUM_WITHIN_RANGE, null, 0.0d, 80.0d, "",
                    ch01.unit(), 2_000_000_000L, 0.5d, sourceId));
            EvaluationResult notRun = service.storeNotEvaluated(window, unused, true);
            assertEquals(EvaluationOutcome.NOT_EVALUATED, notRun.outcome());
            EvaluationRule revised = service.saveEvaluationRule(qaRule(
                    meetsRule.ruleId(), EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE, null, 40.0d, 80.0d, "",
                    ch01.unit(), 2_000_000_000L, 0.5d, sourceId));
            assertEquals(2, revised.version());
            EvaluationResult revisedResult = service.evaluateAndStore(window, revised, List.of(series), true, false);
            assertEquals(2, revisedResult.ruleVersion());
            assertEquals(EvaluationOutcome.DOES_NOT_MEET_CRITERION, revisedResult.outcome());
            EvaluationResult frozen = EvaluationDocuments.decodeResult(
                    service.metadataStore().listEvaluationResults(service.currentProjectId()).stream()
                            .filter(row -> row.resultId().equals(meets.resultId())).findFirst().orElseThrow().document());
            assertEquals(1, frozen.ruleVersion());
            assertEquals(EvaluationOutcome.MEETS_CRITERION, frozen.outcome());
            assertEquals(meets.expectedText(), frozen.expectedText());
            assertEquals(observation, service.findEngineeringBookmark(windowId).orElseThrow().observation());
            assertEquals(kept, primary.get(primary.size() / 2).physicalValue(), 0.0d);

            Path html = service.generateInvestigationReport(
                    service.engineeringInvestigations().find(service.metadataStore(), created.investigationId()).orElseThrow(),
                    "Ada", temp.resolve("m112.html"));
            Path pdf = service.generateInvestigationPdfReport(
                    service.engineeringInvestigations().find(service.metadataStore(), created.investigationId()).orElseThrow(),
                    "Ada", temp.resolve("m112.pdf"));
            String text = Files.readString(html);
            assertTrue(text.contains("href=\"#sec-evaluation\""));
            assertTrue(text.contains("Expected vs Actual"));
            assertTrue(text.contains("Meets criterion"));
            assertTrue(text.contains("Does not meet criterion"));
            assertTrue(text.contains("Indeterminate"));
            assertTrue(text.contains("Not evaluated"));
            assertTrue(text.contains(EvaluationEngine.UNACCEPTABLE_TIMESTAMP_GAP));
            assertTrue(text.contains(EvaluationEngine.UNIT_INCOMPATIBLE));
            assertTrue(text.contains(QA));
            assertTrue(text.contains("not a customer-approved compliance result"));
            assertTrue(text.contains("not a vehicle pass or fail"));
            assertFalse(text.contains("PASS"));
            try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
                String packed = new PDFTextStripper().getText(document).replaceAll("\\s+", "");
                assertTrue(packed.contains("Meetscriterion"));
                assertTrue(packed.contains("Doesnotmeetcriterion"));
                assertTrue(packed.contains("Indeterminate"));
                assertTrue(packed.contains("Notevaluated"));
                assertTrue(packed.contains("QA-onlyfixture"));
            }
            Path repo = Path.of("").toAbsolutePath();
            Path out = ("stellar-desktop".equals(repo.getFileName().toString()) ? repo.getParent() : repo).resolve("target");
            Files.createDirectories(out);
            Files.copy(html, out.resolve("engineering-report-v2-m112-sample.html"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(pdf, out.resolve("engineering-report-v2-m112-sample.pdf"), StandardCopyOption.REPLACE_EXISTING);
            assertEquals(kept, primary.get(primary.size() / 2).physicalValue(), 0.0d);
        } finally {
            if (service != null) {
                service.shutdown();
            }
            if (processed.catalogStore() instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }
    }

    private static EvaluationRule qaRule(
            String id, EvaluationRule.ComparisonOperator operator, Double value, Double low, Double high, String state,
            String unit, Long gap, Double coverage, String sourceId
    ) {
        return new EvaluationRule(id, 0, "QA thermal check", "qa-1", "Thermal observation", List.of(CH01), sourceId,
                operator, value, low, high, state, unit, EvaluationRule.BoundaryPolicy.INCLUSIVE,
                EvaluationRule.BoundaryPolicy.INCLUSIVE, null, gap, coverage, EvaluationRule.RuleApproval.DRAFT,
                "", QA, "", "");
    }

    private static List<SignalSamplePoint> readAll(SignalSeriesStore store, SignalCatalogEntry entry) {
        int retained = store.retainedSampleCount(entry.key());
        List<SignalSamplePoint> all = new ArrayList<>();
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
}
