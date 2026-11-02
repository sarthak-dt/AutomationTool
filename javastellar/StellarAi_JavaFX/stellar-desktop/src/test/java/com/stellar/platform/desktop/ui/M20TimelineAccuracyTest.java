package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EngineeringFormats;
import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EngineeringReportTemplate;
import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.GraphSnapshotMetadata;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.EngineerEventDocuments;
import com.stellar.platform.desktop.presentation.GraphEventGps;
import com.stellar.platform.desktop.presentation.GraphInvestigationCodec;
import com.stellar.platform.desktop.presentation.GraphSnapshotLinks;
import com.stellar.platform.desktop.presentation.InvestigationEventCodec;
import com.stellar.platform.desktop.presentation.ReportEvidenceAssets;
import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.graph.timeline.UnknownOrderLayout;
import com.stellar.platform.processing.map.MapRoute;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.ProjectRoute;
import com.stellar.platform.processing.map.RouteSegment;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import com.stellar.platform.desktop.support.JavaFxTestSupport.FxWork;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One Sierra recording must plot and report its own sample timestamps.
 * The unknown-order slot stays a file position and is not the recording duration.
 */
class M20TimelineAccuracyTest {

    private static final Path MF4 = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/tata_sierra_0002391_20260921_133054 (1).mf4");
    private static final Path DBC = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/TEMP DBC 1 (1).dbc");
    private static final long SLOT = UnknownOrderLayout.EQUAL_SLOT_SPAN;

    @TempDir Path temp;
    private DesktopAppService service;
    private Stage stage;
    private GraphController graph;
    private InvestigationController investigation;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        JavaFxTestSupport.runOnFx("dispose timeline", () -> {
            if (graph != null) {
                graph.dispose();
                graph = null;
            }
            if (investigation != null) {
                investigation.dispose();
                investigation = null;
            }
            if (stage != null) {
                stage.close();
                stage = null;
            }
        });
        if (service != null) {
            service.shutdown();
            service = null;
        }
        JavaFxTestSupport.drainFxPulse();
    }

    @Test
    @Timeout(600)
    void singleRecordingUsesMeasurementTime() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC));
        assertEquals("1.0.3", DesktopVersion.displayVersion());
        Path previous = Path.of(System.getProperty("java.io.tmpdir"), "m20-closure", "snapshot-plotted.png");
        String previousHash = Files.isRegularFile(previous) ? ReportEvidenceAssets.sha256(previous) : "";
        Path out = Path.of(System.getProperty("java.io.tmpdir"), "m20-timeline");
        replaceDirectory(out);

        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        WorkspaceFile measurement = new WorkspaceFile(MF4, FileKind.MEASUREMENT_MF4, Files.size(MF4));
        WorkspaceFile definition = new WorkspaceFile(DBC, FileKind.DEFINITION_DBC, Files.size(DBC));
        var processed = LocalProcessingGateway.createDefault().process(
                measurement, definition, 512, new SignalPreviewBuffer(64), ProcessingProgressListener.NOOP, null);
        SignalSeriesStore store = processed.catalogStore();
        SignalCatalogEntry ch01 = entry(store, "CH01_FRONT_BUMPER_GRILL_AMBIENT");
        SignalCatalogEntry ch02 = entryPrefix(store, "CH02");
        SignalCatalogEntry latitude = entry(store, "LATITUDE");
        SignalCatalogEntry longitude = entry(store, "LONGITUDE");
        assumeTrue(ch01 != null && ch02 != null && latitude != null && longitude != null);
        long start = Math.min(ch01.firstTimestampNanos(), ch02.firstTimestampNanos());
        long end = Math.max(ch01.lastTimestampNanos(), ch02.lastTimestampNanos());
        String duration = EngineeringFormats.duration(start, end);
        String range = EngineeringFormats.range(start, end);
        SignalSamplePoint ch01First = store.querySamples(ch01.key(), 0, 1).getFirst();
        SignalSamplePoint ch01Later = store.querySamples(ch01.key(), 400, 1).getFirst();
        assertEquals(start, ch01First.timestampNanos());
        assertTrue(end - start > 1_700_000_000_000L, duration);
        assertNotEquals("1.000 s", duration);
        step("service", "start=" + start + " end=" + end + " duration=" + duration + " range=" + range
                + " ch01First=" + ch01First.physicalValue() + " later=" + ch01Later.timestampNanos()
                + "/" + ch01Later.physicalValue());

        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("project.db")));
        service.newProject("M20 Timeline");
        service.addMeasurementPaths(List.of(MF4), DesktopAppService.PlatformUi.NOOP);
        service.addDefinitionPaths(List.of(DBC));
        service.signalsModel().replaceCatalog(store);
        assertTrue(service.signalsModel().addPhysicalToWorkspace(ch01.key()));
        assertTrue(service.signalsModel().addPhysicalToWorkspace(ch02.key()));
        assertTrue(service.signalsModel().addPhysicalToWorkspace(latitude.key()));
        String projectId = service.currentProjectId();

        long opened = System.nanoTime();
        openGraph();
        awaitExplorer();
        JavaFxTestSupport.runOnFx("check CH01 and CH02", () -> {
            searchAndCheck(ch01.key(), "CH01_FRONT_BUMPER");
            searchAndCheck(ch02.key(), "CH02");
            if (graph.isSelectedForTest(latitude.key())) {
                graph.fireExplorerCheckboxForTest(latitude.key());
            }
            graph.fitForTest();
        });
        View full = awaitExact(start, end, 2);
        long graphMs = (System.nanoTime() - opened) / 1_000_000L;
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMb = runtime.maxMemory() / (1024 * 1024);
        step("ui", "graphMs=" + graphMs + " usedMb=" + usedMb + " maxMb=" + maxMb
                + " viewport=" + full.start() + ".." + full.end() + " range=" + full.range()
                + " domain=" + full.domain() + " samples=" + full.samples());
        assertEquals("STORED", full.domain());
        assertEquals(start, full.start());
        assertEquals(end, full.end());
        assertFalse(full.range().contains("not elapsed"), full.range());
        assertFalse(full.range().startsWith("Slots"), full.range());
        assertTrue(full.range().contains("1799."), full.range());
        assertTrue(full.samples() > 1_000);
        assertTrue(maxMb <= 1536);
        shot(out.resolve("01-graph-full.png"));

        JavaFxTestSupport.runOnFx("create investigation from the full recording", () ->
                assertTrue(graph.commitCreateInvestigationForTest("M20 Timeline")));
        EngineeringInvestigation created = investigations().stream()
                .filter(item -> "M20 Timeline".equals(item.name())).findFirst().orElseThrow();
        assertEquals(duration, EngineeringFormats.duration(created.rangeStartNanos(), created.rangeEndNanos()));
        assertEquals(range, EngineeringFormats.range(created.rangeStartNanos(), created.rangeEndNanos()));

        View zoomed = changeView("zoom", () -> graph.zoomInForTest(),
                view -> view.span() < full.span() * 3 / 4 && view.span() > SLOT);
        assertEquals("STORED", zoomed.domain());
        assertTrue(zoomed.span() < full.span() * 3 / 4, zoomed.toString());
        assertTrue(zoomed.span() > SLOT, zoomed.toString());
        assertTrue(zoomed.start() >= start && zoomed.end() <= end, zoomed.toString());
        shot(out.resolve("02-graph-zoom.png"));
        View panned = changeView("pan", () -> graph.panRightForTest(),
                view -> view.start() > zoomed.start() && view.span() > SLOT);
        assertEquals("STORED", panned.domain());
        assertTrue(panned.start() >= zoomed.start(), panned.toString());
        assertTrue(panned.end() <= end + 1, panned.toString());
        shot(out.resolve("03-graph-pan.png"));
        JavaFxTestSupport.runOnFx("fit", () -> graph.fitForTest());
        View fitted = awaitExact(start, end, 2);
        assertEquals(start, fitted.start());
        assertEquals(end, fitted.end());

        JavaFxTestSupport.runOnFx("add point and window", () -> {
            graph.pinEventSignalsForTest(List.of(ch01.key(), ch02.key()));
            graph.selectCursorAForTest(ch01First.timestampNanos());
            Dialog<?> pointDialog = graph.addEventDialogForTest();
            fill(pointDialog, "Timeline point", "Thermal observation", "elapsed point", false);
            assertFalse(graph.commitAddEventForTest(pointDialog).isBlank());
            graph.selectCursorAForTest(ch01First.timestampNanos());
            graph.selectCursorBForTest(ch01Later.timestampNanos());
            Dialog<?> windowDialog = graph.addEventDialogForTest();
            fill(windowDialog, "Timeline window", "Thermal observation", "elapsed window", true);
            assertFalse(graph.commitAddEventForTest(windowDialog).isBlank());
            graph.reloadEventsForTest();
        });
        assertEquals(ch01First.timestampNanos(), bookmark("Timeline point").timestampNanos());
        assertEquals(ch01First.timestampNanos(), bookmark("Timeline window").timestampNanos());
        assertEquals(ch01Later.timestampNanos(), EngineerEventDocuments.endNanos(bookmark("Timeline window")));
        List<Long> overlayTimes = overlayTimes();
        assertTrue(overlayTimes.contains(ch01First.timestampNanos()), overlayTimes.toString());
        double pointFraction = fraction(ch01First.timestampNanos(), fitted);
        double windowFraction = fraction(ch01Later.timestampNanos(), fitted);
        step("ui", "pointFraction=" + pointFraction + " windowFraction=" + windowFraction
                + " slotFraction=" + (ch01First.timestampNanos() / (double) SLOT));
        assertTrue(pointFraction < 0.001d, "point " + pointFraction);
        assertTrue(windowFraction > 0.01d && windowFraction < 0.05d, "window " + windowFraction);
        shot(out.resolve("04-graph-events.png"));

        int windowIndex = indexOf("Timeline window");
        assertTrue(windowIndex >= 0);
        JavaFxTestSupport.runOnFx("zoom to the window", () -> {
            graph.selectEventIndexForTest(windowIndex);
            graph.zoomToSelectedEventForTest();
        });
        View eventView = awaitDomain("STORED", 1);
        assertTrue(eventView.start() <= ch01First.timestampNanos(), eventView.toString());
        assertTrue(eventView.end() >= ch01Later.timestampNanos(), eventView.toString());
        assertTrue(eventView.span() < 120_000_000_000L, eventView.toString());
        assertNotEquals(SLOT, eventView.span());
        shot(out.resolve("05-graph-event-zoom.png"));
        JavaFxTestSupport.runOnFx("fit before snapshot", () -> graph.fitForTest());
        awaitExact(start, end, 2);

        JavaFxTestSupport.runOnFx("capture", () ->
                assertTrue(graph.commitCaptureSnapshotForTest("Timeline graph")));
        var snapshotItem = investigations().stream().flatMap(item -> item.evidence().stream())
                .filter(item -> item.kind() == EvidenceKind.GRAPH_SNAPSHOT).findFirst().orElseThrow();
        Path snapshotPng = InvestigationStoragePaths.snapshot(
                AnalysisStorageRoots.projectArtifactsRoot(projectId),
                snapshotItem.investigationId(), snapshotItem.referenceId());
        Files.copy(snapshotPng, out.resolve("snapshot-elapsed.png"));
        Path sidecar = snapshotPng.resolveSibling(snapshotItem.referenceId() + ".json");
        assertTrue(Files.isRegularFile(sidecar), sidecar.toString());
        GraphSnapshotMetadata meta = GraphSnapshotMetadata.fromJson(Files.readString(sidecar));
        String described = GraphSnapshotLinks.describe(meta, false);
        step("ui", "snapshotBasis=" + meta.timeBasis() + " described=" + described
                + " hash=" + ReportEvidenceAssets.sha256(snapshotPng));
        assertEquals("MEASUREMENT_ELAPSED", meta.timeBasis());
        assertEquals(start, meta.rangeStartNanos());
        assertEquals(end, meta.rangeEndNanos());
        assertTrue(described.contains("measurement-relative elapsed time"), described);
        assertFalse(described.contains("layout slot"), described);
        Pixels pixels = pixels(snapshotPng);
        assertTrue(pixels.blue() > 200 && pixels.purple() > 200, pixels.toString());

        EngineeringBookmarkMetadata point = bookmark("Timeline point");
        JavaFxTestSupport.runOnFx("link snapshot", () -> {
            Dialog<?> link = graph.linkSnapshotDialogForTest(point.bookmarkId());
            graph.selectLinkSnapshotForTest(link, snapshotItem.referenceId());
            graph.commitLinkSnapshotForTest(link);
        });
        assertEquals(List.of(snapshotItem.referenceId()),
                EngineerEventDocuments.decode(bookmark("Timeline point")).snapshotIds());

        List<SignalSamplePoint> lat = store.queryTimestampRange(latitude.key(), 0L, ch01Later.timestampNanos(), 400);
        List<SignalSamplePoint> lon = store.queryTimestampRange(longitude.key(), 0L, ch01Later.timestampNanos(), 400);
        MapRoute route = new MapRoute(MapRouteBuilder.validateAndBuild(lat, lon, List.of()).model());
        String eventSource = bookmark("Timeline point").sourceId();
        String eventFile = EngineerEventDocuments.decode(bookmark("Timeline point")).sourceFile();
        JavaFxTestSupport.runOnFx("install route", () -> graph.installProjectRoutesForTest(
                new ProjectRoute(List.of(new RouteSegment(eventSource, eventFile, route)), lat.size(), lon.size())));
        Dialog<?>[] gps = new Dialog<?>[1];
        JavaFxTestSupport.runOnFx("request GPS", () -> {
            gps[0] = graph.gpsDialogForTest(point.bookmarkId());
            graph.requestGpsForTest(gps[0]);
        });
        awaitGps(gps[0]);
        JavaFxTestSupport.runOnFx("link GPS", () ->
                assertEquals("", graph.commitGpsLinkForTest(gps[0]), graph.gpsDetailForTest(gps[0])));
        assertEquals(GraphEventGps.MATCHED, GraphEventGps.read(bookmark("Timeline point")).status());
        assertEquals(ch01First.timestampNanos(), bookmark("Timeline point").timestampNanos());
        closeStage();

        service.saveInvestigation(investigations().stream().filter(item -> "M20 Timeline".equals(item.name()))
                .findFirst().orElseThrow().toBuilder()
                .signalKeyBlobs(List.of(
                        GraphInvestigationCodec.encodeKey(ch01.key()),
                        GraphInvestigationCodec.encodeKey(ch02.key())))
                .build());
        openInvestigation();
        JavaFxTestSupport.runOnFx("include events", () -> {
            chooseEngineeringTemplate();
            selectEngineerEvent("Timeline point");
            button("Include in Report").fire();
            selectEngineerEvent("Timeline window");
            button("Include in Report").fire();
            ((TextField) stage.getScene().getRoot().lookup("#observationTitle")).setText("Timeline note");
            ((TextArea) stage.getScene().getRoot().lookup("#observationNote"))
                    .setText("CH01 and CH02 use the recording clock.");
            button("Add Observation").fire();
        });
        JavaFxTestSupport.drainFxPulse();
        Path htmlDir = out.resolve("html");
        Path pdfDir = out.resolve("pdf");
        Files.createDirectories(htmlDir);
        Files.createDirectories(pdfDir);
        export("m20-timeline", htmlDir, false);
        export("m20-timeline", pdfDir, true);
        Path html = Files.list(htmlDir).filter(path -> path.toString().endsWith(".html")).findFirst().orElseThrow();
        Path pdf = Files.list(pdfDir).filter(path -> path.toString().endsWith(".pdf")).findFirst().orElseThrow();
        String htmlText = Files.readString(html);
        assertTrue(htmlText.contains(duration), htmlText.substring(0, Math.min(htmlText.length(), 4000)));
        assertTrue(htmlText.contains(range), "missing range " + range);
        assertFalse(htmlText.contains(">1.000 s<"), "layout slot was reported as duration");
        assertTrue(htmlText.contains("measurement-relative elapsed time"), "snapshot caption");
        assertTrue(htmlText.contains("84940000"));
        assertTrue(htmlText.contains("observed=45"));
        assertTrue(htmlText.contains("Timeline point") && htmlText.contains("Timeline window"));
        Path waveform = find(htmlDir, "signals-waveform");
        Files.copy(waveform, out.resolve("signals-waveform.png"));
        Rise rise = rise(waveform);
        step("ui", "waveformRise " + rise);
        assertTrue(rise.rightHigher(), rise.toString());
        int pages;
        String pdfText;
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            pages = document.getNumberOfPages();
            pdfText = new PDFTextStripper().getText(document);
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < pages; page++) {
                ImageIO.write(renderer.renderImageWithDPI(page, 110), "png",
                        out.resolve("pdf-page-" + (page + 1) + ".png").toFile());
            }
        }
        assertTrue(pdfText.contains("Duration: " + duration), "overview duration");
        assertTrue(pdfText.contains("Investigation range: " + range) || pdfText.contains("1799.787"), pdfText);
        assertFalse(pdfText.contains("Duration: 1.000 s"), "layout slot was reported as duration");
        assertTrue(pdfText.contains("observed=45"), pdfText);
        assertTrue(pdfText.contains("Timeline point"), pdfText);
        if (!previousHash.isBlank()) {
            assertEquals(previousHash, ReportEvidenceAssets.sha256(previous));
        }
        step("ui", "htmlBytes=" + Files.size(html) + " pdfBytes=" + Files.size(pdf)
                + " pages=" + pages + " previousSnapshotUntouched=" + !previousHash.isBlank());
        System.out.println("M20T ARTIFACTS " + out.toAbsolutePath());
    }

    private View awaitExact(long start, long end, int tracks) throws Exception {
        long deadline = System.nanoTime() + 45_000_000_000L;
        View last = new View("", 0, 0, "", 0);
        boolean sawLoading = false;
        while (System.nanoTime() < deadline) {
            last = readView();
            if (last.status().startsWith("Loading")) {
                sawLoading = true;
            }
            if (sawLoading && "STORED".equals(last.domain()) && last.start() == start && last.end() == end
                    && last.tracks() >= tracks && last.samples() > 0
                    && !last.status().startsWith("Loading")
                    && !last.range().contains("not elapsed")) {
                return last;
            }
            JavaFxTestSupport.drainFxPulse();
            Thread.sleep(80);
        }
        throw new AssertionError("stored viewport did not settle " + last + " expected " + start + ".." + end);
    }

    private View awaitStored(long minSpan, int tracks) throws Exception {
        long deadline = System.nanoTime() + 45_000_000_000L;
        View last = new View("", 0, 0, "", 0);
        while (System.nanoTime() < deadline) {
            last = readView();
            if ("STORED".equals(last.domain()) && last.span() >= minSpan && last.tracks() >= tracks
                    && last.samples() > 0 && !last.range().contains("not elapsed")) {
                return last;
            }
            JavaFxTestSupport.drainFxPulse();
            Thread.sleep(100);
        }
        throw new AssertionError("stored viewport did not settle " + last);
    }

    private View awaitDomain(String domain, int tracks) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        View last = new View("", 0, 0, "", 0);
        while (System.nanoTime() < deadline) {
            last = readView();
            if (domain.equals(last.domain()) && last.tracks() >= tracks && last.span() > 0
                    && !last.status().startsWith("Loading")) {
                return last;
            }
            JavaFxTestSupport.drainFxPulse();
            Thread.sleep(80);
        }
        throw new AssertionError(last.toString());
    }

    private View changeView(String name, FxWork action, Predicate<View> ready) throws Exception {
        JavaFxTestSupport.runOnFx(name, action);
        long deadline = System.nanoTime() + 30_000_000_000L;
        View last = readView();
        while (System.nanoTime() < deadline) {
            last = readView();
            if ("STORED".equals(last.domain()) && last.samples() > 0
                    && !last.status().startsWith("Loading") && ready.test(last)) {
                return last;
            }
            JavaFxTestSupport.drainFxPulse();
            Thread.sleep(80);
        }
        throw new AssertionError(name + " did not change the viewport " + last);
    }

    private View readView() throws Exception {
        View[] holder = new View[1];
        JavaFxTestSupport.runOnFx("read view", () -> holder[0] = new View(
                graph.timelineGroupForTest(),
                graph.viewportStartForTest(),
                graph.viewportEndForTest(),
                graph.rangeTextForTest(),
                graph.lastRenderedCountForTest(),
                graph.renderedSourceSampleCountForTest(),
                graph.graphStatusForTest()));
        return holder[0];
    }

    private static double fraction(long nanos, View view) {
        return (nanos - view.start()) / (double) view.span();
    }

    private List<Long> overlayTimes() throws Exception {
        List<Long>[] holder = new List[]{List.of()};
        JavaFxTestSupport.runOnFx("overlays", () -> holder[0] = graph.overlayTimesForTest());
        return holder[0];
    }

    private void openGraph() throws Exception {
        JavaFxTestSupport.runOnFx("show graph", () -> {
            FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
            Parent root = loader.load();
            graph = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1366, 768));
            stage.show();
            graph.setAppService(service, null);
            service.workspace().selectedMeasurement()
                    .or(() -> service.workspace().measurementFiles().stream().findFirst())
                    .ifPresent(file -> service.selectMeasurement(file.id()));
            graph.refresh();
        });
        JavaFxTestSupport.drainFxPulse();
    }

    private void openInvestigation() throws Exception {
        JavaFxTestSupport.runOnFx("show report", () -> {
            FXMLLoader loader = new FXMLLoader(InvestigationController.class.getResource("/fxml/investigation-view.fxml"));
            Parent root = loader.load();
            investigation = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1280, 800));
            stage.show();
            investigation.setAppService(service, null);
            investigation.setOpenGeneratedReportForTest(false);
            investigation.refresh();
        });
        JavaFxTestSupport.drainFxPulse();
    }

    private void closeStage() throws Exception {
        JavaFxTestSupport.runOnFx("close", () -> {
            if (stage != null) {
                stage.close();
                stage = null;
            }
        });
    }

    private void awaitExplorer() throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            int[] size = new int[1];
            JavaFxTestSupport.runOnFx("explorer size", () -> size[0] = graph.signalListForTest().getItems().size());
            if (size[0] > 0) {
                return;
            }
            JavaFxTestSupport.drainFxPulse();
            Thread.sleep(80);
        }
        throw new AssertionError("graph explorer stayed empty");
    }

    private void searchAndCheck(SignalCatalogKey key, String query) {
        TextField search = (TextField) stage.getScene().getRoot().lookup("#signalSearch");
        search.setText(query);
        search.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.UNDEFINED, false, false, false, false));
        graph.signalListForTest().layout();
        if (!graph.isSelectedForTest(key)) {
            graph.fireExplorerCheckboxForTest(key);
        }
        assertTrue(graph.isSelectedForTest(key), query);
    }

    private void export(String name, Path directory, boolean pdf) throws Exception {
        JavaFxTestSupport.runOnFx("export", () -> {
            chooseEngineeringTemplate();
            investigation.setReportDestinationForTest(name, directory);
            button(pdf ? "Generate PDF Report" : "Generate HTML Report").fire();
        });
        String extension = pdf ? ".pdf" : ".html";
        long deadline = System.nanoTime() + 180_000_000_000L;
        while (System.nanoTime() < deadline) {
            try (var listing = Files.list(directory)) {
                for (Path path : listing.toList()) {
                    String filename = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                    if (filename.endsWith(extension) && Files.size(path) > 0) {
                        waitUntilEnabled(pdf ? "Generate PDF Report" : "Generate HTML Report");
                        return;
                    }
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("no " + extension + " in " + directory);
    }

    private void waitUntilEnabled(String label) throws Exception {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean[] enabled = new boolean[1];
            JavaFxTestSupport.runOnFx("enabled " + label, () -> enabled[0] = !button(label).isDisabled());
            if (enabled[0]) {
                return;
            }
            Thread.sleep(100);
        }
    }

    private void chooseEngineeringTemplate() {
        @SuppressWarnings("unchecked")
        ComboBox<EngineeringReportTemplate> box =
                (ComboBox<EngineeringReportTemplate>) stage.getScene().getRoot().lookup("#templateBox");
        box.getSelectionModel().select(EngineeringReportTemplate.ENGINEERING_V2);
        stage.getScene().getRoot().lookupAll(".check-box").forEach(node -> {
            if (node instanceof CheckBox check) {
                check.setSelected(true);
            }
        });
    }

    private void selectEngineerEvent(String title) {
        ListView<?> events = (ListView<?>) stage.getScene().getRoot().lookup("#engineerEventList");
        for (int i = 0; i < events.getItems().size(); i++) {
            if (String.valueOf(events.getItems().get(i)).contains(title)) {
                events.getSelectionModel().select(i);
            }
        }
    }

    private String awaitGps(Dialog<?> dialog) throws Exception {
        String before = graph.gpsDetailForTest(dialog);
        long deadline = System.nanoTime() + 8_000_000_000L;
        String detail = before;
        while (System.nanoTime() < deadline) {
            detail = graph.gpsDetailForTest(dialog);
            if (!detail.isBlank() && !detail.equals(before)) {
                return detail;
            }
            JavaFxTestSupport.drainFxPulse();
            Thread.sleep(50);
        }
        return detail;
    }

    private void shot(Path file) throws Exception {
        JavaFxTestSupport.runOnFx("shot", () -> {
            WritableImage image = stage.getScene().snapshot(null);
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        });
    }

    private List<EngineeringInvestigation> investigations() {
        return service.engineeringInvestigations().list(service.metadataStore(), service.currentProjectId());
    }

    private EngineeringBookmarkMetadata bookmark(String title) {
        return service.listEngineeringBookmarks().stream()
                .filter(item -> title.equals(InvestigationEventCodec.titleOf(item.observation())))
                .findFirst().orElseThrow();
    }

    private int indexOf(String title) throws Exception {
        int[] index = new int[]{-1};
        JavaFxTestSupport.runOnFx("event index", () -> {
            List<String> rows = graph.eventRowsForTest();
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).contains(title)) {
                    index[0] = i;
                }
            }
        });
        return index[0];
    }

    private Button button(String text) {
        return stage.getScene().getRoot().lookupAll(".button").stream()
                .filter(node -> node instanceof Button candidate && text.equals(candidate.getText()))
                .map(Button.class::cast).findFirst().orElseThrow();
    }

    private static void fill(Dialog<?> dialog, String title, String category, String note, boolean window) {
        VBox body = (VBox) dialog.getDialogPane().getContent();
        for (Node node : body.getChildren()) {
            if (node instanceof TextField field) {
                field.setText(title);
            } else if (node instanceof TextArea area) {
                area.setText(note);
            } else if (node instanceof ComboBox<?> combo) {
                @SuppressWarnings("unchecked")
                ComboBox<String> choices = (ComboBox<String>) combo;
                choices.getSelectionModel().select("shape".equals(combo.getUserData())
                        ? (window ? "Time window" : "Point") : category);
            }
        }
    }

    private static SignalCatalogEntry entry(SignalSeriesStore store, String name) {
        for (SignalCatalogEntry candidate : store.snapshot().entries()) {
            if (name.equals(candidate.signalName())) {
                return candidate;
            }
        }
        return null;
    }

    private static SignalCatalogEntry entryPrefix(SignalSeriesStore store, String prefix) {
        for (SignalCatalogEntry candidate : store.snapshot().entries()) {
            if (candidate.signalName() != null && candidate.signalName().startsWith(prefix)) {
                return candidate;
            }
        }
        return null;
    }

    private static Path find(Path root, String prefix) throws Exception {
        try (var walk = Files.walk(root)) {
            return walk.filter(path -> path.getFileName().toString().startsWith(prefix)).findFirst()
                    .orElseThrow(() -> new AssertionError("missing " + prefix + " under " + root));
        }
    }

    private static void replaceDirectory(Path out) throws Exception {
        if (Files.exists(out)) {
            try (var walk = Files.walk(out)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (java.io.IOException ignored) {
                    }
                });
            }
        }
        Files.createDirectories(out);
    }

    private static Pixels pixels(Path png) throws Exception {
        BufferedImage image = ImageIO.read(png.toFile());
        int blue = 0;
        int purple = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 255;
                int g = (rgb >> 8) & 255;
                int b = rgb & 255;
                if (b > 90 && b > r + 25 && b > g + 10) {
                    blue++;
                }
                if (r > 80 && b > 80 && Math.abs(r - b) < 50 && r > g + 15) {
                    purple++;
                }
            }
        }
        return new Pixels(image.getWidth(), image.getHeight(), blue, purple);
    }

    private static Rise rise(Path png) throws Exception {
        BufferedImage image = ImageIO.read(png.toFile());
        int width = image.getWidth();
        int height = image.getHeight();
        int top = (int) (height * 0.08);
        int bottom = (int) (height * 0.55);
        double left = meanY(image, (int) (width * 0.28), (int) (width * 0.42), top, bottom);
        double right = meanY(image, (int) (width * 0.78), (int) (width * 0.92), top, bottom);
        return new Rise(width, height, left, right, Double.isFinite(left) && Double.isFinite(right) && right < left - 8);
    }

    private static double meanY(BufferedImage image, int x0, int x1, int y0, int y1) {
        long sum = 0;
        int count = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 255;
                int g = (rgb >> 8) & 255;
                int b = rgb & 255;
                if (b > 140 && b > r + 30 && b > g) {
                    sum += y;
                    count++;
                }
            }
        }
        return count == 0 ? Double.NaN : sum / (double) count;
    }

    private void step(String kind, String detail) {
        System.out.println("M20T STEP kind=" + kind + " " + detail);
    }

    private record View(String domain, long start, long end, String range, int tracks, long samples, String status) {
        View(String domain, long start, long end, String range, int tracks) {
            this(domain, start, end, range, tracks, 0, "");
        }

        long span() {
            return end - start;
        }

        @Override
        public String toString() {
            return domain + " " + start + ".." + end + " span=" + span() + " " + range
                    + " tracks=" + tracks + " samples=" + samples + " " + status;
        }
    }

    private record Pixels(int width, int height, int blue, int purple) {
        @Override
        public String toString() {
            return width + "x" + height + " blue=" + blue + " purple=" + purple;
        }
    }

    private record Rise(int width, int height, double leftY, double rightY, boolean rightHigher) {
        @Override
        public String toString() {
            return width + "x" + height + " leftY=" + leftY + " rightY=" + rightY + " higher=" + rightHigher;
        }
    }
}
