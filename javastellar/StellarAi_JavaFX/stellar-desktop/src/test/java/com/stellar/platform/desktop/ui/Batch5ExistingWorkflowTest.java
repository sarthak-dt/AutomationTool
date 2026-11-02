package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.AnalysisEngine;
import com.stellar.platform.analysis.investigation.EngineeringEvidenceItem;
import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.report.EngineeringHtmlReportRenderer;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModel;
import com.stellar.platform.analysis.investigation.report.EngineeringReportModelBuilder;
import com.stellar.platform.analysis.investigation.report.ReportFileNames;
import com.stellar.platform.analysis.model.AnalysisParameters;
import com.stellar.platform.analysis.model.AnalysisRequest;
import com.stellar.platform.analysis.model.AnalysisResult;
import com.stellar.platform.analysis.model.AnalysisType;
import com.stellar.platform.analysis.model.ThresholdOperator;
import com.stellar.platform.analysis.source.InMemoryAnalysisSampleSource;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationCodec;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.FileValidationStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas;
import com.stellar.platform.desktop.ui.graph.GraphSeriesPalette;
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 5 checks against functionality that already exists.
 * Disposable homes only. Customer projects and original measurement files are not opened.
 */
class Batch5ExistingWorkflowTest {

    private static final long SECOND = 1_000_000_000L;
    private static final List<String> NOTES = new ArrayList<>();

    private String previousHome;
    private String previousIsolate;
    private String previousMetadata;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void restore() {
        AnalysisStorageRoots.clear();
        restore("stellarai.home", previousHome);
        restore("stellarai.test.isolate", previousIsolate);
        restore("stellarai.metadata.db", previousMetadata);
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        NOTES.add("TC_VIZ_002 NOT RUN category D: scatter chart is not an existing Graph control.");
        NOTES.add("TC_VIZ_003 NOT RUN category D: histogram chart is not an existing Graph control.");
        NOTES.add("TC_VIZ_004 NOT RUN category D: bar chart is not an existing Graph control.");
        NOTES.add("TC_VIZ_005 NOT RUN category D: heat map is not an existing Graph control.");
        NOTES.add("TC_VIZ_006 NOT RUN category D: correlation chart is not an existing Graph control.");
        NOTES.add("TC_VIZ_007 NOT RUN category D: timeline chart type is not a separate control. The waveform is the existing time plot.");
        NOTES.add("TC_VIZ_011 NOT RUN: the Analytics FFT pane was not opened in this batch.");
        NOTES.add("TC_VIZ_012 NOT RUN: the Analytics FFT pane was not opened in this batch.");
        NOTES.add("TC_VIZ_013 NOT RUN: the Analytics FFT pane was not opened in this batch.");
        NOTES.add("TC_VIZ_014 NOT RUN: the Analytics FFT pane was not opened in this batch.");
        NOTES.add("TC_VIZ_015 NOT RUN category D: distribution chart is not an existing control.");
        NOTES.add("TC_VIZ_016 NOT RUN category D: distribution chart is not an existing control.");
        NOTES.add("TC_VIZ_017 NOT RUN category D: distribution chart is not an existing control.");
        NOTES.add("TC_VIZ_018 NOT RUN: 100-signal plotting was not driven.");
        NOTES.add("TC_VIZ_019 NOT RUN category D: histogram is not an existing control.");
        NOTES.add("TC_VIZ_020 NOT RUN category D: histogram is not an existing control.");
        NOTES.add("TC_VIZ_021 NOT RUN category D: histogram is not an existing control.");
        NOTES.add("TC_VIZ_022 NOT RUN category D: histogram is not an existing control.");
        NOTES.add("TC_VIZ_023 NOT RUN category D: histogram is not an existing control.");
        NOTES.add("TC_VIZ_024 NOT RUN category D: histogram is not an existing control.");
        NOTES.add("TC_VIZ_025 NOT RUN category D: histogram is not an existing control.");
        NOTES.add("TC_VIZ_026 NOT RUN category D: histogram is not an existing control.");
        NOTES.add("TC_VIZ_027 NOT RUN category D: histogram is not an existing control.");
        NOTES.add("TC_VIZ_028 NOT RUN category D: pie chart is not an existing control.");
        NOTES.add("TC_VIZ_029 NOT RUN category D: bubble chart is not an existing control.");
        NOTES.add("TC_VIZ_030 NOT RUN category D: bubble chart is not an existing control.");
        NOTES.add("TC_VIZ_031 NOT RUN category D: heatmap is not an existing control.");
        NOTES.add("TC_VIZ_032 NOT RUN category D: heatmap is not an existing control.");
        NOTES.add("TC_VIZ_033 NOT RUN category D: heatmap is not an existing control.");
        NOTES.add("TC_VIZ_035 NOT RUN: adding or removing a source on a live chart was not driven.");
        NOTES.add("TC_EVT_005 NOT RUN category D: a separate fault-condition detector is not implemented.");
        NOTES.add("TC_EVT_007 NOT RUN: the event list was not opened in the desktop window.");
        NOTES.add("TC_EVT_008 NOT RUN category C: there is no threshold entry form that rejects a typed invalid value.");
        NOTES.add("TC_EVT_009 NOT RUN category D: anomaly detection is not implemented.");
        NOTES.add("TC_EVT_010 NOT RUN category D: anomaly detection is not implemented.");
        NOTES.add("TC_EVT_011 NOT RUN category D: anomaly detection is not implemented.");
        NOTES.add("TC_EVT_012 NOT RUN category D: anomaly detection is not implemented.");
        NOTES.add("TC_EVT_013 NOT RUN category D: anomaly detection is not implemented.");
        NOTES.add("TC_EVT_014 NOT RUN category C: a named engineering-event editor was not found.");
        NOTES.add("TC_EVT_015 NOT RUN category C: a named engineering-event editor was not found.");
        NOTES.add("TC_EVT_016 NOT RUN: event detection was not shown in the desktop window.");
        NOTES.add("TC_EVT_017 NOT RUN category C: a named event-definition editor was not found.");
        NOTES.add("TC_EVT_018 NOT RUN category D: threshold file import is not implemented.");
        NOTES.add("TC_EVT_019 NOT RUN category D: threshold file import is not implemented.");
        NOTES.add("TC_EVT_020 NOT RUN category C: a threshold min/max editor was not found.");
        NOTES.add("TC_EVT_021 NOT RUN category C: a threshold min/max editor was not found.");
        NOTES.add("TC_EVT_022 NOT RUN category D: threshold colour editing is not implemented.");
        NOTES.add("TC_EVT_023 NOT RUN: a manual threshold was not added through the desktop window.");
        NOTES.add("TC_EVT_024 NOT RUN: parameter search before a threshold was not driven.");
        NOTES.add("TC_EVT_025 NOT RUN category C: threshold delete was not found as a control.");
        NOTES.add("TC_EVT_026 NOT RUN: a saved threshold rule was not reopened from the desktop window.");
        NOTES.add("TC_EXP_001 NOT RUN category D: PDF export is not implemented.");
        NOTES.add("TC_EXP_002 NOT RUN category D: PDF export is not implemented.");
        NOTES.add("TC_EXP_003 NOT RUN category D: PDF export is not implemented.");
        NOTES.add("TC_EXP_004 NOT RUN category D: PDF export is not implemented.");
        NOTES.add("TC_EXP_005 NOT RUN category D: PDF export is not implemented.");
        NOTES.add("TC_EXP_006 NOT RUN category D: the workbook step exports an empty report to PDF. PDF export is not implemented. The HTML empty document is recorded as HTML-EMPTY.");
        NOTES.add("TC_EXP_007 NOT RUN category D: PDF export cancel is not implemented.");
        NOTES.add("TC_EXP_008 NOT RUN category D: PDF overwrite handling is not implemented.");
        NOTES.add("TC_EXP_009 NOT RUN category D: PDF invalid-name handling is not implemented.");
        NOTES.add("TC_EXP_010 NOT RUN category D: Word export is not implemented.");
        NOTES.add("TC_EXP_011 NOT RUN category D: Word export is not implemented.");
        NOTES.add("TC_EXP_012 NOT RUN category D: Word export is not implemented.");
        NOTES.add("TC_EXP_013 NOT RUN category D: Word export is not implemented.");
        NOTES.add("TC_EXP_014 NOT RUN category D: Word export is not implemented.");
        NOTES.add("TC_EXP_018 NOT RUN category D: the workbook step times a PDF export. The BRD one-minute KPI is not a confirmed UAT criterion. HTML render time is recorded as HTML-TIMING.");
        NOTES.add("TC_EXP_024 NOT RUN category D: large PDF/plot export is not implemented.");
        NOTES.add("TC_EXP_025 NOT RUN category D: Word section order is not implemented.");
        NOTES.add("TC_EXP_026 NOT RUN category D: export retry after a PDF or Word failure is not implemented.");
        NOTES.add("TC_EXP_017 NOT RUN: the HTML file was not opened in a browser.");
        NOTES.add("TC_EXP_020 NOT RUN category D: the HTML report is offline and has no interactive plot runtime.");
        NOTES.add("TC_CFG_005 NOT RUN category C: carried forward. Analysis Storage has no Cancel.");
        NOTES.add("INV-STALE-FOCUS NOT RUN: a saved focus id that is absent from the recording list was not driven on a live graph.");
        Path out = Path.of("..", "docs", "qa-handoff", "batch5-execution.txt").toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join(System.lineSeparator(), NOTES) + System.lineSeparator());
    }

    @Test
    @Timeout(60)
    void graphEmptyHoverStackedOverlayAndUnknownOrder() throws Exception {
        JavaFxTestSupport.runOnFx("graph workflow", () -> {
            FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
            loader.load();
            Label title = (Label) loader.getNamespace().get("graphEmptyTitle");
            Label body = (Label) loader.getNamespace().get("graphEmptyBody");
            assertEquals("Graphs & Analysis", title.getText());
            assertEquals("No signals plotted yet.", body.getText());
            assertTrue(loader.getNamespace().get("detailsToggleButton") == null);
            assertTrue(loader.getNamespace().get("bottomWorkspace") == null);

            SignalCatalogKey speed = key("VehicleSpeed", "file-a.mf4");
            SignalCatalogKey coolant = key("Coolant", "file-b.mf4");
            GraphRenderSeries speedSeries = series(speed, "km/h", bucket(SECOND, 72.96875));
            GraphRenderSeries coolantSeries = series(coolant, "degC", bucket(SECOND, 80));
            AutomotiveSignalCanvas canvas = new AutomotiveSignalCanvas();
            canvas.setMinSize(800, 420);
            canvas.setPrefSize(800, 420);
            new Scene(canvas, 800, 420);
            canvas.resize(800, 420);
            canvas.prepareSharedViewport(0, 2 * SECOND);
            AtomicReference<Double> zoom = new AtomicReference<>();
            canvas.setInteractionHandlers((factor, at) -> zoom.set(factor), (a, b) -> { }, (a, b) -> { });
            canvas.acceptWheel(120, 200);
            assertEquals(0.72, zoom.get(), 0.0);
            canvas.setTracePresentation(Map.of(speed, "Vehicle speed", coolant, "Coolant"), Map.of(speed, 0, coolant, 1));
            canvas.setSeries(List.of(speedSeries));
            assertEquals(1, canvas.seriesCountForTest());
            canvas.setSeries(List.of(speedSeries, coolantSeries));
            assertEquals(2, canvas.seriesCountForTest());
            assertEquals("Vehicle speed", canvas.seriesLabelForTest(0));
            assertEquals("Coolant", canvas.seriesLabelForTest(1));
            Color before = canvas.seriesColorForTest(0);
            assertEquals(GraphSeriesPalette.at(0), before);
            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.STACKED);
            assertEquals(2, canvas.stackedFramesForTest().size());
            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.OVERLAY_MULTI_AXIS);
            assertEquals(AutomotiveSignalCanvas.LayoutMode.OVERLAY_MULTI_AXIS, canvas.getLayoutMode());
            assertEquals(before, canvas.seriesColorForTest(0));
            canvas.setLayoutMode(AutomotiveSignalCanvas.LayoutMode.STACKED);

            canvas.setHiddenKeys(List.of(coolant));
            assertEquals(1, canvas.stackedFramesForTest().size());
            canvas.setHiddenKeys(List.of());
            assertEquals(2, canvas.stackedFramesForTest().size());

            AutomotiveSignalCanvas.HoverPoint hover = null;
            for (double x = 40; x < 760 && hover == null; x += 8) {
                canvas.movePointerForTest(x, 80);
                AutomotiveSignalCanvas.HoverPoint point = canvas.hoverPointProperty().get();
                if (point != null && point.timestampNanos() == SECOND && point.signalName().equals("Vehicle speed")) {
                    hover = point;
                }
            }
            assertTrue(hover != null, "hover did not land on the 1 s speed sample");
            assertEquals(72.96875, hover.value(), 0);
            assertEquals("km/h", hover.unit());
            assertEquals("file-a.mf4", hover.measurement());

            GraphBucket categorical = new GraphBucket(
                    SECOND, Double.NaN, SECOND, Double.NaN, SECOND, Double.NaN, SECOND, Double.NaN, 1,
                    SignalValidity.VALID, SignalValidity.VALID, SignalValidity.VALID, SignalValidity.VALID,
                    "FALSE", "FALSE", "FALSE", "FALSE");
            canvas.setSeries(List.of(series(key("Fan", "file-a.mf4"), "", categorical)));
            canvas.setTracePresentation(Map.of(), Map.of());
            AutomotiveSignalCanvas.HoverPoint label = null;
            for (double x = 40; x < 760 && label == null; x += 8) {
                canvas.movePointerForTest(x, 80);
                AutomotiveSignalCanvas.HoverPoint point = canvas.hoverPointProperty().get();
                if (point != null && "FALSE".equals(point.categoricalLabel())) {
                    label = point;
                }
            }
            assertTrue(label != null, "categorical hover did not report FALSE");

            canvas.prepareSharedViewport(0, 40 * SECOND);
            canvas.setUnknownOrderOverview(true);
            assertEquals("Slot position is not elapsed time", canvas.timeAxisCaptionForTest());

            note("TC_VIZ_010 PASS empty graph reads Graphs & Analysis / No signals plotted yet.");
            note("TC_VIZ_009 PASS the plotted series changed from 1 signal to 2, and hiding Coolant left 1 stacked frame.");
            note("TC_VIZ_034 PASS stacked frames=2 and overlay mode keeps the speed colour " + before + ".");
            note("VIZ-HOVER PASS pointer reports 1.000 s, 72.96875 km/h, file-a.mf4. Categorical hover reports FALSE.");
            note("VIZ-UNKNOWN-ORDER PASS axis caption is Slot position is not elapsed time.");
            note("VIZ-PLOT-SETTINGS PASS the Plot Settings button and panel are absent from graph-view.fxml.");
            note("VIZ-ZOOM PASS a positive wheel reports zoom factor 0.72 at the pointer time.");
        });
    }

    @Test
    void thresholdContextGapsAndSeparateMeasurements() {
        AnalysisEngine engine = new AnalysisEngine();
        SignalCatalogKey speed = key("VehicleSpeed", "file-a.mf4");
        InMemoryAnalysisSampleSource consecutive = new InMemoryAnalysisSampleSource();
        consecutive.add(speed, 0, 1);
        consecutive.add(speed, SECOND, 5);
        consecutive.add(speed, 2 * SECOND, 6);
        consecutive.add(speed, 3 * SECOND, 1);
        AnalysisResult one = threshold(engine, consecutive, speed, ThresholdOperator.GREATER_THAN, 4);
        assertEquals(1, one.findings().size());
        assertEquals(SECOND, one.findings().getFirst().startTimestampNanos());
        assertEquals(2 * SECOND, one.findings().getFirst().endTimestampNanos());
        assertEquals(6d, one.findings().getFirst().numericMetrics().get("peak"), 0);
        assertEquals(2d, one.findings().getFirst().numericMetrics().get("sampleCount"), 0);
        assertEquals(speed, one.findings().getFirst().signalKeys().getFirst());

        InMemoryAnalysisSampleSource below = new InMemoryAnalysisSampleSource();
        below.add(speed, SECOND, 3.9);
        assertEquals(0, threshold(engine, below, speed, ThresholdOperator.GREATER_THAN, 4).findings().size());
        InMemoryAnalysisSampleSource above = new InMemoryAnalysisSampleSource();
        above.add(speed, SECOND, 4.1);
        assertEquals(1, threshold(engine, above, speed, ThresholdOperator.GREATER_THAN, 4).findings().size());

        InMemoryAnalysisSampleSource gapped = new InMemoryAnalysisSampleSource();
        gapped.add(speed, SECOND, 9);
        gapped.put(speed, List.of(
                new SignalSamplePoint(SECOND, 9, null, null, SignalValidity.VALID),
                new SignalSamplePoint(2 * SECOND, Double.NaN, 255L, "invalid", SignalValidity.INVALID_RAW_CODE),
                new SignalSamplePoint(3 * SECOND, 9, null, null, SignalValidity.VALID)));
        AnalysisResult split = threshold(engine, gapped, speed, ThresholdOperator.GREATER_THAN, 4);
        assertEquals(2, split.findings().size());
        assertEquals(SECOND, split.findings().get(0).startTimestampNanos());
        assertEquals(SECOND, split.findings().get(0).endTimestampNanos());
        assertEquals(3 * SECOND, split.findings().get(1).startTimestampNanos());
        assertEquals(255L, gapped.querySamples(speed, 1, 1).getFirst().rawValue());

        InMemoryAnalysisSampleSource text = new InMemoryAnalysisSampleSource();
        text.put(speed, List.of(new SignalSamplePoint(SECOND, Double.NaN, 0L, "FALSE", SignalValidity.VALID)));
        assertEquals(0, threshold(engine, text, speed, ThresholdOperator.GREATER_THAN, 0).findings().size());
        assertEquals("FALSE", text.querySamples(speed, 0, 1).getFirst().textualValue());

        SignalCatalogKey other = key("VehicleSpeed", "file-b.mf4");
        InMemoryAnalysisSampleSource second = new InMemoryAnalysisSampleSource();
        second.add(other, 10 * SECOND, 9);
        AnalysisResult otherEvent = threshold(engine, second, other, ThresholdOperator.GREATER_THAN, 4);
        assertEquals(1, otherEvent.findings().size());
        assertEquals(10 * SECOND, otherEvent.findings().getFirst().startTimestampNanos());
        assertEquals("file-b.mf4", otherEvent.findings().getFirst().signalKeys().getFirst().sourceFile());
        assertFalse(otherEvent.findings().getFirst().signalKeys().getFirst().equals(speed));

        note("TC_EVT_006 PASS one event for consecutive 5 then 6, from 1.000 s to 2.000 s, peak 6, 2 samples, signal VehicleSpeed.");
        note("EVT-BOUNDARY PASS 3.9 is not greater than 4. 4.1 is one event.");
        note("EVT-GAP PASS an invalid sample between two values of 9 produces two events, at 1.000 s and 3.000 s. Raw 255 stays stored.");
        note("EVT-CATEGORICAL PASS a FALSE sample produces no threshold event and the text stays FALSE.");
        note("EVT-SEPARATE PASS file-b.mf4 event is at 10.000 s and is not the file-a.mf4 event.");
    }

    @Test
    void htmlReportKeepsIdentityUnitsAndEscaping() {
        Instant when = Instant.parse("2026-10-18T22:40:00Z");
        EngineeringInvestigation investigation = EngineeringInvestigation.builder("p-batch5")
                .name("Events <alpha> & \"beta\"")
                .description("Note")
                .conclusion("Rise follows the pedal.")
                .evidence(List.of(evidenceItem(
                        "e1", EvidenceKind.ANALYSIS_RESULT, "threshold-1",
                        "Threshold 1.000–2.000 s peak 6")))
                .build();
        EngineeringReportModel model = EngineeringReportModelBuilder.build(investigation, new EngineeringReportModelBuilder.Context(
                "StellarAI 1.0.1",
                "qa",
                new EngineeringReportModel.ProjectSection("Batch5 Project", 2, 0, 2, 2, "Ready", 0),
                List.of(
                        new EngineeringReportModel.SourceRow("file-a.mf4", "MF4", "", "1 KB", "Ready"),
                        new EngineeringReportModel.SourceRow("file-b.mf4", "MF4", "", "1 KB", "Ready")),
                List.of(
                        new EngineeringReportModel.SignalRow(
                                "VehicleSpeed", "km/h", "file-a.mf4", "23.0859375", "85.0234375", "68.4", "—", "—"),
                        new EngineeringReportModel.SignalRow(
                                "Fan", "", "file-a.mf4", "—", "—", "—", "—", "—", "FALSE")),
                List.of(), List.of(), List.of(), List.of(), List.of()));
        long started = System.nanoTime();
        String html = EngineeringHtmlReportRenderer.render(model);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("</html>"));
        assertFalse(html.contains("<script"));
        assertTrue(html.contains("Batch5 Project"));
        assertTrue(html.contains("file-a.mf4"));
        assertTrue(html.contains("file-b.mf4"));
        assertTrue(html.contains("km/h"));
        assertTrue(html.contains("23.0859375"));
        assertTrue(html.contains("85.0234375"));
        assertTrue(html.contains("FALSE"));
        assertTrue(html.contains("Events &lt;alpha&gt; &amp; &quot;beta&quot;"));
        assertTrue(html.contains("Threshold 1.000–2.000 s peak 6"));
        assertFalse(html.contains("Events <alpha>"));
        String again = EngineeringHtmlReportRenderer.render(model);
        assertEquals(withoutGeneratedClock(html), withoutGeneratedClock(again));

        EngineeringInvestigation empty = EngineeringInvestigation.builder("p-empty").name("Empty").build();
        String blank = EngineeringHtmlReportRenderer.render(EngineeringReportModelBuilder.build(
                empty, new EngineeringReportModelBuilder.Context(
                "StellarAI 1.0.1", "", new EngineeringReportModel.ProjectSection("Empty", 0, 0, 0, 0, "", 0),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of())));
        assertTrue(blank.startsWith("<!DOCTYPE html>"));
        assertFalse(blank.contains("file-a.mf4"));

        String plain = ReportFileNames.suggest("Batch5 Project", "Events", when);
        assertTrue(plain.endsWith("_Report.html"), plain);
        assertTrue(plain.startsWith("Batch5_Project_Events_"), plain);
        String special = ReportFileNames.suggest("A<B>", "C:D", when);
        assertFalse(special.contains("<"));
        assertFalse(special.contains(":"));
        assertTrue(elapsedMs < 60_000, elapsedMs + " ms");

        note("HTML-EMPTY PASS an empty investigation still renders a complete HTML document and does not include file-a.mf4. Workbook TC_EXP_006 asks for a PDF and stays NOT RUN.");
        note("HTML-TIMING the same HTML render took " + elapsedMs + " ms. Workbook TC_EXP_018 times a PDF export and stays NOT RUN.");
        note("TC_EXP_019 PASS the report contains Batch5 Project, both files, km/h, 23.0859375 to 85.0234375, FALSE, and the threshold sentence.");
        note("TC_EXP_022 PASS default name starts Batch5_Project_Events_ and ends _Report.html. Actual: " + plain);
        note("TC_EXP_023 PASS title is escaped as Events &lt;alpha&gt; &amp; &quot;beta&quot;. Filename " + special + " has no < or :.");
        note("EXP-REGENERATE PASS the same model rendered twice produced the same HTML.");
        note("EXP-OFFLINE PASS the document has no script element.");
    }

    @Test
    void missingSourceIsReportedAndTheOtherFileStays() throws Exception {
        Path home = Files.createTempDirectory("stellar-batch5-pm");
        previousHome = System.getProperty("stellarai.home");
        previousIsolate = System.getProperty("stellarai.test.isolate");
        previousMetadata = System.getProperty("stellarai.metadata.db");
        System.setProperty("stellarai.home", home.toAbsolutePath().toString());
        System.setProperty("stellarai.test.isolate", "true");
        System.clearProperty("stellarai.metadata.db");
        AnalysisStorageRoots.clear();
        Path db = home.resolve("meta.db");
        Path kept = home.resolve("kept.mf4");
        Path gone = home.resolve("gone.mf4");
        Files.writeString(kept, "kept");
        Files.writeString(gone, "gone");
        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db);
        DesktopAppService service = new DesktopAppService(new DesktopSession(), store);
        try {
            service.newProject("Batch5 Sources");
            String projectId = service.currentProjectId();
            service.workspace().addFile(WorkspaceFile.restore("kept-id", kept, FileKind.MEASUREMENT_MF4, Files.size(kept)));
            service.workspace().addFile(WorkspaceFile.restore("gone-id", gone, FileKind.MEASUREMENT_MF4, Files.size(gone)));
            assertTrue(service.saveProject().saved());
            Files.delete(gone);
            service.shutdown();
            store.close();

            store = new SqliteProjectMetadataStore(db);
            service = new DesktopAppService(new DesktopSession(), store);
            var opened = service.openSavedProject(projectId);
            assertTrue(opened.opened(), opened.warnings().toString());
            assertTrue(opened.warnings().stream().anyMatch(warning -> warning.contains("Missing file") && warning.contains("gone.mf4")),
                    opened.warnings().toString());
            WorkspaceFile missing = service.workspace().measurementFiles().stream()
                    .filter(file -> "gone-id".equals(file.id()))
                    .findFirst()
                    .orElseThrow();
            WorkspaceFile present = service.workspace().measurementFiles().stream()
                    .filter(file -> "kept-id".equals(file.id()))
                    .findFirst()
                    .orElseThrow();
            assertEquals(FileValidationStatus.INVALID, missing.status());
            assertTrue(missing.validationMessage().contains("no longer available"));
            assertEquals(kept.toAbsolutePath().normalize(), present.path());
            assertTrue(Files.isRegularFile(present.path()));

            EngineeringInvestigation created = EngineeringInvestigation.builder(projectId)
                    .name("Batch5 evidence")
                    .signalKeyBlobs(List.of("VehicleSpeed|file-a.mf4"))
                    .evidence(List.of(evidenceItem(
                            "e1", EvidenceKind.ANALYSIS_RESULT, "threshold-1",
                            "Threshold 1.000–2.000 s peak 6")))
                    .build();
            var saved = service.saveInvestigation(created);
            assertTrue(service.saveProject().saved());
            String investigationId = saved.investigationId();
            service.shutdown();
            store.close();
            store = new SqliteProjectMetadataStore(db);
            service = new DesktopAppService(new DesktopSession(), store);
            assertTrue(service.openSavedProject(projectId).opened());
            EngineeringInvestigation restored = service.engineeringInvestigations()
                    .find(service.metadataStore(), investigationId).orElseThrow();
            assertEquals(List.of("Threshold 1.000–2.000 s peak 6"),
                    restored.evidence().stream().map(EngineeringEvidenceItem::caption).toList());
            assertEquals(List.of("VehicleSpeed|file-a.mf4"), restored.signalKeyBlobs());

            assertTrue(GraphInvestigationCodec.compatibleTracesBlob("GIV1\n"));
            assertTrue(GraphInvestigationCodec.compatibleTracesBlob("GIV2\n"));
            assertFalse(GraphInvestigationCodec.compatibleTracesBlob("GIV9\n"));

            note("INV-MISSING-SOURCE PASS reopen warns Missing file for gone.mf4, marks that source INVALID, and leaves kept.mf4 in place.");
            note("INV-EVIDENCE PASS the threshold caption and signal blob survive a new service.");
            note("INV-GIV PASS GIV1 and GIV2 blobs are accepted. GIV9 is rejected.");
        } finally {
            service.shutdown();
            deleteTree(home);
        }
    }

    private static String withoutGeneratedClock(String html) {
        return html.replaceAll("(<th>Generated</th><td>)[^<]*", "$1TIME");
    }

    private static EngineeringEvidenceItem evidenceItem(String id, EvidenceKind kind, String ref, String caption) {
        return new EngineeringEvidenceItem(id, "inv", kind, ref, 0, false, "", "fp", 1L, caption, caption, Instant.now());
    }

    private static AnalysisResult threshold(AnalysisEngine engine, InMemoryAnalysisSampleSource source,
                                            SignalCatalogKey key, ThresholdOperator operator, double value) {
        AnalysisResult result = engine.execute(AnalysisRequest.builder(AnalysisType.THRESHOLD)
                .addSignal(key)
                .parameters(AnalysisParameters.empty().withThreshold(operator, value))
                .build(), source, null);
        assertTrue(result.status().successful(), result.message());
        return result;
    }

    private static GraphRenderSeries series(SignalCatalogKey key, String unit, GraphBucket bucket) {
        return new GraphRenderSeries(key, unit, 1, true, 0, 2 * SECOND, List.of(bucket));
    }

    private static GraphBucket bucket(long timestamp, double value) {
        return new GraphBucket(timestamp, value, timestamp, value, timestamp, value, timestamp, value, 1,
                SignalValidity.VALID, SignalValidity.VALID, SignalValidity.VALID, SignalValidity.VALID);
    }

    private static SignalCatalogKey key(String name, String file) {
        return new SignalCatalogKey(name, SignalSourceType.MF4_NATIVE, "MEAS", 0, 0, file, "");
    }

    private static void note(String line) {
        NOTES.add(line);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // Disposable temp home only.
                }
            });
        } catch (Exception ignored) {
            // Disposable temp home only.
        }
    }

}
