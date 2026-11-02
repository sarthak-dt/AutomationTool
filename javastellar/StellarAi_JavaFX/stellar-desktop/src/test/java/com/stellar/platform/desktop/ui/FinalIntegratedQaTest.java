package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.EngineeringFormats;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.desktop.DesktopLogging;
import com.stellar.platform.desktop.presentation.AlignedSampleRow;
import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.EngineerEventDocuments;
import com.stellar.platform.desktop.presentation.GraphInvestigationCodec;
import com.stellar.platform.desktop.presentation.GraphInvestigationState;
import com.stellar.platform.desktop.presentation.InvestigationEventCodec;
import com.stellar.platform.desktop.presentation.WorkspaceDisplayMode;
import com.stellar.platform.desktop.presentation.WorkspacePrecision;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.testsupport.DesktopTestIsolation;
import com.stellar.platform.desktop.ui.graph.AutomotiveSignalCanvas;
import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.export.AlignedSignalCsvExporter;
import com.stellar.platform.processing.graph.GraphBucket;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import com.stellar.platform.processing.graph.MinMaxLodReducer;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.scene.Node;
import javafx.stage.Stage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Final v1.0.3 engineer journey on the validated multiplex ASC fixture.
 * Processing, CSV, save, and report writing go through DesktopAppService.
 * Workspace Go to Timestamp, the in-chart legend, Add Event, and the graph
 * snapshot use the production JavaFX controls. Evidence stays in
 * {@code java.io.tmpdir/stellar-v103-final-qa}.
 */
class FinalIntegratedQaTest {

    private static final long FIRST_SAMPLE = 100_000_000L;
    private static final long LAST_SAMPLE = 400_000_000L;
    private static final long POINT = 200_000_000L;
    private static final long WINDOW_START = 300_000_000L;
    private static final long WINDOW_END = 400_000_000L;
    private static final String DBC = """
            VERSION ""
            NS_ :
            BS_:
            BU_: ECU
            VAL_TABLE_ GroupState 0 "Sleep" 1 "Drive" ;
            BO_ 256 Battery: 1 ECU
             SG_ HVBAT_STATE : 0|8@1+ (1,0) [0|15] "" ECU
            BO_ 1552 Power: 2 ECU
             SG_ Selector M : 0|8@1+ (1,0) [0|3] "" ECU
             SG_ Speed m0 : 8|8@1+ (1,0) [0|255] "km/h" ECU
            SGTYPE_ GroupType : 8@1+ (1,0) [0|1] "" 0 , GroupState ;
            SIG_TYPE_REF_ 1552 Selector : GroupType ;
            VAL_ 256 HVBAT_STATE 0 "Sleep"
            3 "Ready" ;
            """;
    private static final String ASC = """
            date Thu Jan 01 12:00:00.000 am 2026
            base hex timestamps absolute
              0.100000000 1 100 Rx d 1 00
              0.200000000 1 100 Rx d 1 03
              0.300000000 1 610 Rx d 2 00 28
              0.360000000 1 610 Rx d 2 00 32
              0.400000000 1 610 Rx d 2 01 28
            """;
    private static final List<String> EXPECTED = List.of(
            "HVBAT_STATE|100000000|0|0.0|Sleep|VALID",
            "HVBAT_STATE|200000000|3|3.0|Ready|VALID",
            "Selector|300000000|0|0.0|Sleep|VALID",
            "Selector|360000000|0|0.0|Sleep|VALID",
            "Selector|400000000|1|1.0|Drive|VALID",
            "Speed|300000000|40|40.0||VALID",
            "Speed|360000000|50|50.0||VALID"
    );

    @org.junit.jupiter.api.io.TempDir
    Path temp;

    private final List<DesktopAppService> services = new ArrayList<>();
    private final StringBuilder report = new StringBuilder();
    private Path qa;
    private DesktopAppService service;
    private GraphController graph;
    private Stage stage;
    private final java.util.Map<String, List<SignalSamplePoint>> samplesByName = new java.util.LinkedHashMap<>();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        JavaFxTestSupport.runOnFx("close graph", () -> {
            if (graph != null) {
                graph.dispose();
                graph = null;
            }
            if (stage != null) {
                stage.close();
                stage = null;
            }
        });
        for (DesktopAppService open : services) {
            open.shutdown();
        }
        services.clear();
        DesktopLogging.closeForTests();
        DesktopTestIsolation.resetProcessState();
        System.clearProperty("stellarai.home");
        System.clearProperty("stellarai.analysis.storage.disable-auto");
        if (qa != null) {
            Files.createDirectories(qa);
            Files.writeString(qa.resolve("journey-log.txt"), report.toString(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @Timeout(240)
    void engineerJourneyOnValidatedMultiplexRecording() throws Exception {
        qa = Path.of(System.getProperty("java.io.tmpdir"), "stellar-v103-final-qa");
        assertEquals("stellar-v103-final-qa", qa.getFileName().toString());
        Path historical = Path.of(System.getProperty("java.io.tmpdir"), "dbc-final-qa", "historical-snapshot.png");
        byte[] historicalBefore = Files.exists(historical) ? Files.readAllBytes(historical) : null;
        replaceDirectory(qa);
        step("setup", "evidence=" + qa.toAbsolutePath());

        long processStart = System.nanoTime();
        service = newService(temp.resolve("Storage"));
        service.newProject("Final integrated QA");
        String projectId = service.currentProjectId();
        WorkspaceFile measurement = prepare(service);
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        service.enterProcessingWorkspaceFromDecision();
        assertEquals(1, service.processingSelectionModel().reviewedPlan().jobs().size());
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);
        long processMs = millis(processStart);
        step("service", "process ASC+DBC jobs=1 processMs=" + processMs);

        Path store = storeRoot(projectId);
        List<String> samples = identities(store);
        assertEquals(EXPECTED, samples);
        assertFalse(samples.stream().anyMatch(row -> row.startsWith("Speed|400000000|")),
                "inactive multiplex branch must stay absent");
        assertEquals("km/h", unit(store, "Speed"));
        for (GraphBucket bucket : MinMaxLodReducer.reduce(samples(store, "Speed"), 20)) {
            assertAnalog(bucket.firstValue());
            assertAnalog(bucket.lastValue());
            assertAnalog(bucket.minValue());
            assertAnalog(bucket.maxValue());
        }
        long indexStamp = Files.getLastModifiedTime(store.resolve(LosslessGraphSignalStore.INDEX_FILE_NAME)).toMillis();
        step("service", "samples=" + samples.size() + " speedUnit=km/h inactiveSpeedAt400ms=absent");

        SignalCatalogEntry hvbat = entry(store, "HVBAT_STATE");
        SignalCatalogEntry selector = entry(store, "Selector");
        SignalCatalogEntry speed = entry(store, "Speed");
        List<SignalCatalogKey> selected = List.of(hvbat.key(), selector.key(), speed.key());
        service.signalsModel().setSamplePageSize(2);
        assertTrue(service.signalsModel().addPhysicalToWorkspace(hvbat.key()));
        assertTrue(service.signalsModel().addPhysicalToWorkspace(selector.key()));
        assertTrue(service.signalsModel().addPhysicalToWorkspace(speed.key()));
        assertEquals(selected, service.signalsModel().workspaceKeys());

        Path csv = qa.resolve("signals-physical.csv");
        AlignedSignalCsvExporter.ExportResult exported = new AlignedSignalCsvExporter().export(
                service.signalsModel().catalogStore(),
                List.of(hvbat, selector, speed),
                AlignedSignalCsvExporter.ValueMode.PHYSICAL,
                csv,
                null);
        String csvText = Files.readString(csv);
        assertTrue(exported.rowCount() >= 5, "csv rows " + exported.rowCount());
        assertTrue(csvText.contains("40.0"), csvText);
        assertTrue(csvText.contains("50.0"), csvText);
        assertTrue(csvText.contains("km/h"), csvText);
        assertFalse(csvText.contains("Sleep"), csvText);
        assertFalse(csvText.contains("Drive"), csvText);
        String row400 = csvText.lines().filter(line -> line.contains("400000000")).findFirst().orElseThrow();
        assertFalse(row400.contains("40.0") || row400.contains("50.0"), row400);
        assertTrue(row400.strip().endsWith(","), row400);
        step("service", "csv rows=" + exported.rowCount() + " physicalDoubles=true labelsAbsent=true emptySpeedAt400=true");

        long jumpStart = System.nanoTime();
        captureWorkspace(selected);
        step("ui", "workspace 1366x768 and 1920x1080 jumpMs=" + millis(jumpStart));

        samplesByName.put("HVBAT_STATE", samples(store, "HVBAT_STATE"));
        samplesByName.put("Selector", samples(store, "Selector"));
        samplesByName.put("Speed", samples(store, "Speed"));
        showGraph(1366, 768);
        List<SignalCatalogKey> plotted = List.copyOf(service.signalsModel().workspaceKeys());
        assertEquals(List.of("HVBAT_STATE", "Selector", "Speed"), names(plotted), plotted.toString());
        SignalCatalogKey selectorKey = byName(plotted, "Selector");
        SignalCatalogKey hvbatKey = byName(plotted, "HVBAT_STATE");
        SignalCatalogKey speedKey = byName(plotted, "Speed");
        JavaFxTestSupport.runOnFx("overlay legend at 1366", () -> {
            paint(false);
            shoot("graph-1366-overlay.png");
            assertEquals(3, graph.canvasForTest().sharedLegendHitCountForTest());
            assertTrue(graph.canvasForTest().paintedStateLabelsForTest().contains("Sleep"));
            assertTrue(graph.canvasForTest().paintedStateLabelsForTest().contains("Drive"));
            assertTrue(graph.canvasForTest().clickLegendEntryForTest(selectorKey));
            shoot("graph-1366-selector-hidden.png");
            assertEquals(plotted, service.signalsModel().workspaceKeys());
            assertEquals(plotted, graph.selectedMembershipForTest());
            assertTrue(graph.explorerCheckboxSelectedForTest(selectorKey));
            assertFalse(graph.isVisibleForTest(selectorKey));
            assertTrue(graph.isVisibleForTest(hvbatKey));
            assertTrue(graph.isVisibleForTest(speedKey));
            assertTrue(service.graphInvestigation().isHidden(selectorKey));
        });
        List<String> beforeLeave = keyIdentities(service.signalsModel().workspaceKeys());
        step("ui", "legend hide kept selection " + beforeLeave);

        JavaFxTestSupport.runOnFx("leave graph", () -> {
            graph.onHide();
            stage.close();
            graph.dispose();
            graph = null;
            stage = null;
        });
        showGraph(1366, 768);
        long[] view = new long[6];
        JavaFxTestSupport.runOnFx("return, zoom, stacked", () -> {
            graph.onShow();
            graph.bindMembershipForTest(service);
            graph.applyMembershipFromWorkspaceForTest();
            paint(false);
            shoot("graph-1366-restored.png");
            assertEquals(beforeLeave, keyIdentities(service.signalsModel().workspaceKeys()));
            assertFalse(service.graphInvestigation().isHidden(selectorKey));
            assertTrue(graph.isVisibleForTest(selectorKey));
            graph.setViewportForTest(FIRST_SAMPLE, LAST_SAMPLE);
            long span = graph.viewportEndForTest() - graph.viewportStartForTest();
            long zoomStarted = System.nanoTime();
            graph.zoomInForTest();
            view[0] = graph.viewportStartForTest();
            view[1] = graph.viewportEndForTest();
            graph.panLeftForTest();
            view[2] = graph.viewportStartForTest();
            view[3] = graph.viewportEndForTest();
            assertTrue(graph.tryFitRangeForTest(), graph.rangeTextForTest());
            view[4] = graph.viewportStartForTest();
            view[5] = graph.viewportEndForTest();
            stepUnchecked("ui", "zoomPanFitMs=" + millis(zoomStarted)
                    + " beforeSpan=" + span
                    + " zoom=" + view[0] + ".." + view[1]
                    + " pan=" + view[2] + ".." + view[3]
                    + " fit=" + view[4] + ".." + view[5]);
            assertTrue(view[1] - view[0] < span, "zoom did not shrink the viewport");
            assertTrue(view[4] <= FIRST_SAMPLE && view[5] >= LAST_SAMPLE,
                    "fit " + view[4] + ".." + view[5]);
            paint(true);
            shoot("graph-1366-stacked.png");
            assertEquals(GraphInvestigationState.STACKED, graph.layoutModeForTest());
            assertEquals(plotted, graph.selectedMembershipForTest());
            graph.setLayoutModeForTest(false);
            paint(false);
        });
        widenAndShoot();
        step("ui", "stacked at 1366 and overlay at 1920x1080");

        String pointId;
        String windowId;
        long eventStart = System.nanoTime();
        JavaFxTestSupport.runOnFx("point and window events", () -> {
            graph.pinEventSignalsForTest(plotted);
            graph.selectCursorAForTest(POINT);
            Dialog<?> pointDialog = graph.addEventDialogForTest();
            assertTrue(dialogText(pointDialog).contains(POINT + " ns"));
            fill(pointDialog, "Ready point", "Observation", "HVBAT Ready", false);
            assertFalse(graph.commitAddEventForTest(pointDialog).isBlank());
            graph.pinEventSignalsForTest(List.of(selectorKey));
            graph.selectCursorAForTest(WINDOW_START);
            graph.selectCursorBForTest(WINDOW_END);
            Dialog<?> windowDialog = graph.addEventDialogForTest();
            assertTrue(dialogText(windowDialog).contains(WINDOW_START + " ns"));
            assertTrue(dialogText(windowDialog).contains(WINDOW_END + " ns"));
            fill(windowDialog, "Selector window", "Observation", "Sleep to Drive", true);
            assertFalse(graph.commitAddEventForTest(windowDialog).isBlank());
            graph.reloadEventsForTest();
        });
        pointId = bookmarkId("Ready point");
        windowId = bookmarkId("Selector window");
        assertEquals(POINT, service.findEngineeringBookmark(pointId).orElseThrow().timestampNanos());
        assertEquals(WINDOW_START, service.findEngineeringBookmark(windowId).orElseThrow().timestampNanos());
        assertEquals(WINDOW_END, EngineerEventDocuments.endNanos(service.findEngineeringBookmark(windowId).orElseThrow()));
        JavaFxTestSupport.runOnFx("event markers", () -> {
            assertTrue(graph.eventRowsForTest().stream().anyMatch(row -> row.contains("Ready point")));
            assertTrue(graph.eventRowsForTest().stream().anyMatch(row -> row.contains("Selector window") && row.contains("to")));
            assertTrue(graph.overlayTimesForTest().contains(POINT));
            assertTrue(graph.overlayTimesForTest().contains(WINDOW_START));
        });
        step("ui", "events point=" + pointId + " window=" + windowId + " eventMs=" + millis(eventStart));

        service.graphInvestigation().setViewport(FIRST_SAMPLE, LAST_SAMPLE);
        var created = service.createInvestigation("Final QA", "Integrated journey", "CURRENT");
        var investigation = service.saveInvestigation(created.toBuilder()
                .reportConfig(InvestigationReportConfig.engineeringV2())
                .range(FIRST_SAMPLE, LAST_SAMPLE)
                .build());
        assertEquals(FIRST_SAMPLE, investigation.rangeStartNanos());
        assertEquals(LAST_SAMPLE, investigation.rangeEndNanos());
        step("service", "investigation range " + FIRST_SAMPLE + ".." + LAST_SAMPLE
                + " duration=" + EngineeringFormats.duration(FIRST_SAMPLE, LAST_SAMPLE));

        long snapshotStart = System.nanoTime();
        JavaFxTestSupport.runOnFx("capture graph snapshot", () -> {
            graph.setViewportForTest(FIRST_SAMPLE, LAST_SAMPLE);
            paint(false);
            Method capture = GraphController.class.getDeclaredMethod("captureGraphSnapshot", String.class);
            capture.setAccessible(true);
            capture.invoke(graph, "Mux graph");
        });
        investigation = service.engineeringInvestigations().find(
                service.metadataStore(), investigation.investigationId()).orElseThrow();
        String snapshotId = investigation.evidence().stream()
                .filter(item -> item.kind() == EvidenceKind.GRAPH_SNAPSHOT)
                .findFirst().orElseThrow().referenceId();
        Path snapshotFile = InvestigationStoragePaths.snapshot(
                AnalysisStorageRoots.projectArtifactsRoot(projectId),
                investigation.investigationId(), snapshotId);
        byte[] snapshotBytes = Files.readAllBytes(snapshotFile);
        assertTrue(snapshotBytes.length > 8_000, "snapshot bytes " + snapshotBytes.length);
        String snapshotHash = sha256(snapshotBytes);
        assertEquals("", service.linkGraphEventSnapshot(windowId, snapshotId));
        service.includeEngineerEvent(pointId);
        service.includeEngineerEvent(windowId);
        step("ui", "snapshot bytes=" + snapshotBytes.length + " hash=" + snapshotHash
                + " snapshotMs=" + millis(snapshotStart) + " linkedTo=" + windowId);

        Path previewDir = qa.resolve("preview");
        Path exportDir = qa.resolve("export");
        Files.createDirectories(previewDir);
        Files.createDirectories(exportDir);
        investigation = service.engineeringInvestigations().find(
                service.metadataStore(), investigation.investigationId()).orElseThrow();
        long previewStart = System.nanoTime();
        Path preview = service.previewInvestigationHtml(
                investigation, "QA", previewDir.resolve("engineering-preview.html"));
        long previewMs = millis(previewStart);
        long htmlStart = System.nanoTime();
        Path html = service.generateInvestigationReport(
                investigation, "QA", exportDir.resolve("engineering-report.html"));
        long htmlMs = millis(htmlStart);
        long pdfStart = System.nanoTime();
        Path pdf = service.generateInvestigationPdfReport(
                investigation, "QA", exportDir.resolve("engineering-report.pdf"));
        long pdfMs = millis(pdfStart);
        String previewText = Files.readString(preview);
        String htmlText = Files.readString(html);
        for (String fact : reportFacts()) {
            assertTrue(previewText.contains(fact), "preview missing " + fact);
            assertTrue(htmlText.contains(fact), "html missing " + fact);
        }
        assertFalse(htmlText.toLowerCase().contains("vehicle pass"));
        assertFalse(htmlText.toLowerCase().contains("vehicle fail"));
        assertFalse(htmlText.contains("unit-Speed"), htmlText);
        assertTrue(htmlText.contains("unit-HVBAT"), htmlText);
        Set<String> images = imageSources(htmlText);
        assertFalse(images.isEmpty(), htmlText);
        for (String src : images) {
            assertFalse(src.contains(":"), src);
            assertTrue(src.startsWith("assets/"), src);
            Path image = html.getParent().resolve(src);
            assertTrue(Files.size(image) > 1_000, image.toString());
        }
        assertEquals(images.size(), new LinkedHashSet<>(images).size());
        int pages;
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            pages = document.getNumberOfPages();
            String pdfText = new PDFTextStripper().getText(document);
            String packed = pdfText.replaceAll("\\s+", "");
            assertTrue(packed.contains("Readypoint"), pdfText);
            assertTrue(packed.contains("Selectorwindow"), pdfText);
            assertTrue(pdfText.contains("0.200 s"), pdfText);
            assertTrue(pdfText.contains("0.300 s"), pdfText);
            assertTrue(pdfText.contains("0.400 s"), pdfText);
            assertTrue(pdfText.contains("km/h"), pdfText);
            assertTrue(packed.contains("NoGPSrouteisattached"), pdfText);
            assertTrue(packed.contains("Nocoordinatewasinvented"), pdfText);
            assertTrue(pdfText.contains("MUX_NO_ACTIVE_LEAF"), pdfText);
            assertTrue(pdfText.contains("pass/fail"), pdfText);
            assertFalse(pdfText.toLowerCase().contains("vehicle pass"));
            PDFRenderer renderer = new PDFRenderer(document);
            Path pagesDir = exportDir.resolve("pages");
            Files.createDirectories(pagesDir);
            for (int page = 0; page < pages; page++) {
                BufferedImage rendered = renderer.renderImageWithDPI(page, 110);
                Path pageFile = pagesDir.resolve("pdf-page-" + (page + 1) + ".png");
                ImageIO.write(rendered, "png", pageFile.toFile());
                assertTrue(Files.size(pageFile) > 1_000, pageFile.toString());
            }
        }
        step("service", "previewMs=" + previewMs + " htmlMs=" + htmlMs + " pdfMs=" + pdfMs
                + " pages=" + pages + " images=" + images);

        List<String> order = engineeringOrder(service.signalsModel().workspaceKeys());
        assertEquals(List.of(
                "HVBAT_STATE|Battery|256|1",
                "Selector|Power|1552|1",
                "Speed|Power|1552|1"), order);
        assertFalse(service.graphInvestigation().isHidden(selectorKey));
        long saveStart = System.nanoTime();
        service.persistGraphInvestigation();
        assertTrue(service.saveProject().saved());
        long saveMs = millis(saveStart);
        JavaFxTestSupport.runOnFx("close graph before project close", () -> {
            graph.dispose();
            graph = null;
            if (stage != null) {
                stage.close();
                stage = null;
            }
        });
        service.shutdown();
        services.remove(service);
        AnalysisStorageRoots.clear();
        long reopenStart = System.nanoTime();
        DesktopAppService reopened = newService(temp.resolve("Storage"));
        DesktopAppService.ProjectOpenResult opened = reopened.openSavedProject(projectId);
        long reopenMs = millis(reopenStart);
        assertTrue(opened.opened(), String.join("; ", opened.warnings()));
        assertEquals(samples, identities(store));
        assertEquals(indexStamp, Files.getLastModifiedTime(
                store.resolve(LosslessGraphSignalStore.INDEX_FILE_NAME)).toMillis());
        assertEquals(order, engineeringOrder(reopened.signalsModel().workspaceKeys()));
        SignalCatalogKey reopenedSelector = byName(reopened.signalsModel().workspaceKeys(), "Selector");
        SignalCatalogKey reopenedSpeed = byName(reopened.signalsModel().workspaceKeys(), "Speed");
        assertFalse(reopened.graphInvestigation().isHidden(reopenedSelector));
        assertTrue(reopenedSpeed.sourceDatabase().contains("km/h")
                || "km/h".equals(unit(store, "Speed")));
        assertEquals(POINT, reopened.findEngineeringBookmark(pointId).orElseThrow().timestampNanos());
        assertEquals(WINDOW_END, EngineerEventDocuments.endNanos(
                reopened.findEngineeringBookmark(windowId).orElseThrow()));
        assertEquals(snapshotHash, sha256(Files.readAllBytes(snapshotFile)));
        if (historicalBefore != null) {
            assertEquals(historicalBefore.length, Files.size(historical));
            byte[] historicalAfter = Files.readAllBytes(historical);
            for (int i = 0; i < historicalBefore.length; i++) {
                assertEquals(historicalBefore[i], historicalAfter[i]);
            }
        }
        service = reopened;
        showGraph(1366, 768);
        JavaFxTestSupport.runOnFx("checkbox removes only the clicked signal", () -> {
            graph.onShow();
            graph.bindMembershipForTest(reopened);
            graph.applyMembershipFromWorkspaceForTest();
            SignalCatalogKey liveSelector = byName(reopened.signalsModel().workspaceKeys(), "Selector");
            assertEquals(3, graph.selectedMembershipForTest().size());
            graph.toggleMembershipForTest(liveSelector, false);
            assertFalse(graph.isSelectedForTest(liveSelector));
            assertEquals(2, reopened.signalsModel().workspaceKeys().size());
            graph.toggleMembershipForTest(liveSelector, true);
            assertTrue(graph.isSelectedForTest(liveSelector));
            assertTrue(graph.isVisibleForTest(liveSelector));
        });
        long used = usedHeap();
        assertTrue(used < 1536L * 1024 * 1024, "heap " + used);
        step("service", "saveMs=" + saveMs + " reopenMs=" + reopenMs
                + " heapUsed=" + used + " heapPeak=" + peakHeap()
                + " singleHeapSample=notALeakConclusion");
        System.out.println("FINAL-QA " + qa.toAbsolutePath());
    }

    private void captureWorkspace(List<SignalCatalogKey> selected) throws Exception {
        AtomicReference<ActiveSignalWorkspacePane> paneRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        JavaFxTestSupport.runOnFx("open workspace", () -> {
            ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
            Stage workspace = new Stage();
            workspace.setScene(new Scene(pane, 1366, 768));
            workspace.show();
            pane.resize(1366, 768);
            pane.applyCss();
            pane.layout();
            pane.setModel(service.signalsModel());
            ComboBox<Object> mode = field(pane, "navigateModeBox");
            for (Object item : mode.getItems()) {
                if ("Time (s)".equals(String.valueOf(item))) {
                    mode.getSelectionModel().select(item);
                }
            }
            paneRef.set(pane);
            stageRef.set(workspace);
        });
        awaitTimestamp(paneRef.get(), 100_000_000L, "initial page");
        JavaFxTestSupport.runOnFx("go to timestamp", () -> {
            TextField field = field(paneRef.get(), "navigateField");
            field.setText("0.35");
            Button go = field(paneRef.get(), "navigateGoButton");
            go.fire();
        });
        awaitTimestamp(paneRef.get(), 360_000_000L, "go to 0.35 s");
        JavaFxTestSupport.runOnFx("workspace evidence", () -> {
            ActiveSignalWorkspacePane pane = paneRef.get();
            pane.layout();
            List<Long> times = pane.table().getItems().stream().map(AlignedSampleRow::timestampNanos).toList();
            stepUnchecked("ui", "goToTimestamp=0.35 firstShown=" + times + " pageSize=2");
            assertEquals(360_000_000L, times.getFirst());
            assertTrue(times.size() <= 2, times.toString());
            Set<String> physical = cellText(pane);
            assertTrue(physical.contains("0 = Sleep") || physical.contains(WorkspacePrecision.AUTO.formatPhysical(0.0)),
                    physical.toString());
            assertTrue(physical.contains(WorkspacePrecision.AUTO.formatPhysical(50.0)), physical.toString());
            ImageIO.write(SwingFXUtils.fromFXImage(pane.snapshot(null, null), null), "png",
                    qa.resolve("workspace-1366-physical.png").toFile());
            displayMode(pane).setValue(WorkspaceDisplayMode.RAW);
            pane.layout();
            Set<String> raw = cellText(pane);
            assertTrue(raw.contains("0 (Sleep)") || raw.contains("0"), raw.toString());
            ImageIO.write(SwingFXUtils.fromFXImage(pane.snapshot(null, null), null), "png",
                    qa.resolve("workspace-1366-raw.png").toFile());
            paneRef.get().getScene().getWindow().setWidth(1920);
            paneRef.get().getScene().getWindow().setHeight(1080);
            pane.resize(1920, 1080);
            pane.layout();
            ImageIO.write(SwingFXUtils.fromFXImage(pane.snapshot(null, null), null), "png",
                    qa.resolve("workspace-1920-raw.png").toFile());
            assertEquals(selected, service.signalsModel().workspaceKeys());
            pane.dispose();
            stageRef.get().close();
        });
        assertTrue(Files.size(qa.resolve("workspace-1366-physical.png")) > 1_000);
        assertTrue(Files.size(qa.resolve("workspace-1920-raw.png")) > 1_000);
    }

    private void widenAndShoot() throws Exception {
        JavaFxTestSupport.runOnFx("wider desktop graph", () -> {
            stage.setWidth(1920);
            stage.setHeight(1080);
            Parent root = stage.getScene().getRoot();
            root.resize(1920, 1080);
            root.applyCss();
            root.layout();
            paint(false);
            shoot("graph-1920-overlay.png");
            assertEquals(3, graph.selectedMembershipForTest().size());
            assertTrue(graph.isVisibleForTest(byName(service.signalsModel().workspaceKeys(), "Selector")));
        });
        assertTrue(Files.size(qa.resolve("graph-1920-overlay.png")) > 1_000);
    }

    private void showGraph(int width, int height) throws Exception {
        JavaFxTestSupport.runOnFx("show graph " + width, () -> {
            FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
            Parent root = loader.load();
            graph = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, width, height));
            stage.show();
            graph.setAppService(service, null);
            graph.bindMembershipForTest(service);
            graph.applyMembershipFromWorkspaceForTest();
            graph.setViewportForTest(0L, LAST_SAMPLE);
            root.applyCss();
            root.layout();
        });
        JavaFxTestSupport.drainFxPulse();
    }

    private void paint(boolean stacked) throws Exception {
        graph.setLayoutModeForTest(stacked);
        List<SignalCatalogKey> keys = service.signalsModel().workspaceKeys();
        List<GraphRenderSeries> series = new ArrayList<>();
        java.util.Map<SignalCatalogKey, String> names = new java.util.LinkedHashMap<>();
        for (SignalCatalogKey key : keys) {
            List<SignalSamplePoint> points = service.signalsModel().catalogStore().querySamples(key, 0, 20);
            if (points.isEmpty()) {
                points = samplesByName.getOrDefault(key.signalName(), List.of());
            }
            String unit = "Speed".equals(key.signalName()) ? "km/h" : "";
            names.put(key, key.signalName());
            series.add(new GraphRenderSeries(
                    key, unit, points.size(), true, 0L, LAST_SAMPLE, MinMaxLodReducer.reduce(points, 20)));
        }
        Field rendered = GraphController.class.getDeclaredField("lastRendered");
        rendered.setAccessible(true);
        rendered.set(graph, series);
        AutomotiveSignalCanvas canvas = graph.canvasForTest();
        canvas.setSharedViewport(0L, LAST_SAMPLE);
        canvas.setHiddenKeys(service.graphInvestigation().hiddenKeys());
        canvas.setTracePresentation(names, java.util.Map.of());
        canvas.setSeries(series);
        canvas.flushDeferredPaintForTest();
    }

    private static List<String> names(List<SignalCatalogKey> keys) {
        List<String> names = new ArrayList<>();
        for (SignalCatalogKey key : keys) {
            names.add(key.signalName());
        }
        return names;
    }

    private static SignalCatalogKey byName(List<SignalCatalogKey> keys, String name) {
        for (SignalCatalogKey key : keys) {
            if (name.equals(key.signalName())) {
                return key;
            }
        }
        throw new AssertionError(name + " missing from " + keys);
    }

    private void awaitTimestamp(ActiveSignalWorkspacePane pane, long timestamp, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String last = "";
        while (System.nanoTime() < deadline) {
            AtomicReference<String> seen = new AtomicReference<>("");
            JavaFxTestSupport.runOnFx("poll " + label, () -> {
                if (pane.table().getItems().isEmpty()) {
                    seen.set("empty");
                    return;
                }
                seen.set(Long.toString(pane.table().getItems().getFirst().timestampNanos()));
            });
            last = seen.get();
            if (Long.toString(timestamp).equals(last)) {
                return;
            }
            Thread.sleep(40);
        }
        throw new AssertionError(label + " stayed at " + last);
    }

    private static void assertAnalog(double value) {
        assertTrue(value == 40.0d || value == 50.0d, "speed bucket " + value);
        assertNotEquals(0.0d, value);
    }

    private String bookmarkId(String title) {
        return service.listEngineeringBookmarks().stream()
                .filter(bookmark -> title.equals(InvestigationEventCodec.titleOf(bookmark.observation())))
                .findFirst().orElseThrow().bookmarkId();
    }

    private static List<String> reportFacts() {
        return List.of(
                "Ready point",
                "Selector window",
                "0.200 s",
                "0.300 s to 0.400 s",
                "0.300 s",
                "km/h",
                "HVBAT_STATE",
                "Selector",
                "Speed",
                "No GPS route is attached. No route, geofence, or event location was inferred.",
                "GPS evidence was not attached. No coordinate was invented.",
                "MUX_NO_ACTIVE_LEAF",
                "km/h",
                "No event geofence or pass/fail rule is applied in this report.",
                "None of these rows is a vehicle defect or a pass/fail judgement."
        );
    }

    private static Set<String> imageSources(String html) {
        Set<String> sources = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("src=\"([^\"]+\\.png)\"").matcher(html);
        while (matcher.find()) {
            sources.add(matcher.group(1));
        }
        return sources;
    }

    private void shoot(String name) throws Exception {
        Files.createDirectories(qa);
        ImageIO.write(SwingFXUtils.fromFXImage(stage.getScene().snapshot(null), null), "png",
                qa.resolve(name).toFile());
    }

    private void step(String kind, String text) throws Exception {
        report.append(kind).append(" | ").append(text).append('\n');
        Files.createDirectories(qa);
        Files.writeString(qa.resolve("journey-log.txt"), report.toString(), StandardCharsets.UTF_8);
    }

    private void stepUnchecked(String kind, String text) {
        try {
            step(kind, text);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private WorkspaceFile prepare(DesktopAppService app) throws Exception {
        Path dbcPath = temp.resolve("values.dbc");
        Path ascPath = temp.resolve("values.asc");
        Files.writeString(dbcPath, DBC, StandardCharsets.UTF_8);
        Files.writeString(ascPath, ASC, StandardCharsets.UTF_8);
        WorkspaceFile dbc = new WorkspaceFile(dbcPath, FileKind.DEFINITION_DBC, Files.size(dbcPath));
        app.workspace().addFile(dbc);
        WorkspaceFile source = new WorkspaceFile(ascPath, FileKind.MEASUREMENT_ASC, Files.size(ascPath));
        app.workspace().addFile(source);
        CanSourceInventory inventory = new CanSourceInventory(
                ascPath, source.fileName(), MeasurementSourceFormat.ASC, CanInventoryStatus.INVENTORY_READY,
                5, 1, 2, true,
                List.of(new CanChannelInventory(
                        1, "Channel 1", 5, 2, 2, 0, 0,
                        List.of(
                                new CanMessageInventory(new CanMessageKey(1, 0x100L, false), 2, 1, 1, false),
                                new CanMessageInventory(new CanMessageKey(1, 0x610L, false), 3, 2, 2, false)))),
                List.of());
        app.sourceInventoryModel().put(source.id(), inventory);
        try (SqliteProjectMetadataStore metadata = new SqliteProjectMetadataStore(
                app.activeDatabasePath().orElseThrow())) {
            metadata.saveInventory(app.currentProjectId(), source.id(), inventory);
        }
        app.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
                SourceChannelKey.of(source.projectSourceId(), 1),
                ChannelMappingStatus.CONFIRMED,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                dbc.definitionSourceId(), dbc.fileName(),
                dbc.definitionSourceId(), dbc.fileName(), 2, 5, 1.0));
        return source;
    }

    private DesktopAppService newService(Path selected) throws Exception {
        DesktopTestIsolation.resetProcessState();
        System.setProperty("stellarai.home", temp.resolve("AppHome").toAbsolutePath().toString());
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        DesktopAppService created = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        services.add(created);
        if (created.analysisStorage().userSelectedRoot().isEmpty()) {
            Files.createDirectories(selected);
            assertTrue(created.saveAnalysisStorage(selected).valid());
        }
        return created;
    }

    private static List<String> identities(Path store) throws Exception {
        try (LosslessGraphSignalStore opened = LosslessGraphSignalStore.openExisting(store)) {
            List<String> rows = new ArrayList<>();
            for (SignalCatalogEntry entry : opened.catalogEntries()) {
                for (SignalSamplePoint sample : opened.querySamples(entry.key(), 0, 20)) {
                    rows.add(entry.signalName()
                            + "|" + sample.timestampNanos()
                            + "|" + sample.rawValue()
                            + "|" + sample.physicalValue()
                            + "|" + (sample.textualValue() == null ? "" : sample.textualValue())
                            + "|" + sample.validity());
                }
            }
            rows.sort(Comparator.naturalOrder());
            return rows;
        }
    }

    private static List<SignalSamplePoint> samples(Path store, String name) throws Exception {
        try (LosslessGraphSignalStore opened = LosslessGraphSignalStore.openExisting(store)) {
            return opened.querySamples(entry(opened, name).key(), 0, 20);
        }
    }

    private static SignalCatalogEntry entry(Path store, String name) throws Exception {
        try (LosslessGraphSignalStore opened = LosslessGraphSignalStore.openExisting(store)) {
            return entry(opened, name);
        }
    }

    private static SignalCatalogEntry entry(LosslessGraphSignalStore store, String name) {
        return store.catalogEntries().stream()
                .filter(entry -> name.equals(entry.signalName()))
                .findFirst()
                .orElseThrow();
    }

    private static String unit(Path store, String name) throws Exception {
        return entry(store, name).unit();
    }

    private static Path storeRoot(String projectId) throws Exception {
        Path root = AnalysisStorageRoots.signalStoreRoot(projectId);
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> LosslessGraphSignalStore.INDEX_FILE_NAME.equals(
                            path.getFileName().toString()))
                    .map(Path::getParent)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no signal store under " + root));
        }
    }

    private static List<String> engineeringOrder(List<SignalCatalogKey> keys) {
        List<String> lines = new ArrayList<>();
        for (SignalCatalogKey key : keys) {
            lines.add(key.signalName() + "|" + key.messageName() + "|" + key.canId() + "|" + key.channel());
        }
        return lines;
    }

    private static List<String> keyIdentities(List<SignalCatalogKey> keys) {
        List<String> lines = new ArrayList<>();
        for (SignalCatalogKey key : keys) {
            lines.add(key.signalName()
                    + "|" + key.messageName()
                    + "|" + key.canId()
                    + "|" + key.channel()
                    + "|" + key.sourceFile()
                    + "|" + key.sourceDatabase());
        }
        return lines;
    }

    private static Set<String> cellText(ActiveSignalWorkspacePane pane) {
        Set<String> text = new LinkedHashSet<>();
        for (AlignedSampleRow row : pane.table().getItems()) {
            for (var column : pane.table().getColumns()) {
                var value = column.getCellObservableValue(row);
                if (value != null && value.getValue() != null && !String.valueOf(value.getValue()).isBlank()) {
                    text.add(String.valueOf(value.getValue()));
                }
            }
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<WorkspaceDisplayMode> displayMode(ActiveSignalWorkspacePane pane) {
        for (Node node : pane.lookupAll(".combo-box")) {
            if (node instanceof ComboBox<?> combo && combo.getValue() == WorkspaceDisplayMode.PHYSICAL) {
                return (ComboBox<WorkspaceDisplayMode>) combo;
            }
        }
        throw new AssertionError("physical display combo missing");
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field found = target.getClass().getDeclaredField(name);
        found.setAccessible(true);
        return (T) found.get(target);
    }

    private static void fill(Dialog<?> dialog, String title, String category, String note, boolean window) {
        VBox body = (VBox) dialog.getDialogPane().getContent();
        for (Node node : body.getChildren()) {
            if (node instanceof TextField field) {
                field.setText(title);
            } else if (node instanceof TextArea area) {
                area.setText(note);
            } else if (node instanceof ComboBox<?> combo) {
                select(combo, "shape".equals(combo.getUserData()) ? (window ? "Time window" : "Point") : category);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void select(ComboBox<?> combo, String value) {
        ((ComboBox<String>) combo).getSelectionModel().select(value);
    }

    private static String dialogText(Dialog<?> dialog) {
        StringBuilder text = new StringBuilder();
        VBox body = (VBox) dialog.getDialogPane().getContent();
        for (Node node : body.getChildren()) {
            if (node instanceof Label label) {
                text.append(label.getText()).append('\n');
            }
        }
        return text.toString();
    }

    private static void waitForIdle(DesktopAppService app) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (app.isPlanExecutionActive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(app.isPlanExecutionActive(), "processing run did not reach a terminal state");
    }

    private static void replaceDirectory(Path directory) throws Exception {
        if (Files.exists(directory)) {
            try (Stream<Path> walk = Files.walk(directory)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                });
            }
        }
        Files.createDirectories(directory);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static long millis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static long peakHeap() {
        long peak = 0L;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
                peak += pool.getPeakUsage().getUsed();
            }
        }
        return peak;
    }
}
