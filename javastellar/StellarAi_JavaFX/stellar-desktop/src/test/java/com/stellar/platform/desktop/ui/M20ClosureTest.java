package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EngineeringReportTemplate;
import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.EngineerEventDocuments;
import com.stellar.platform.desktop.presentation.GraphEventGps;
import com.stellar.platform.desktop.presentation.GraphInvestigationCodec;
import com.stellar.platform.desktop.presentation.InvestigationEventCodec;
import com.stellar.platform.desktop.presentation.ReportEvidenceAssets;
import com.stellar.platform.desktop.processing.LocalProcessingGateway;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
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
import javafx.scene.control.Label;
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
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Closes the two M20 gaps. The earlier blank snapshot photographed a graph whose
 * explorer checkboxes were never checked. This test checks CH01 and CH02 on the
 * Graph screen before Capture Snapshot.
 */
class M20ClosureTest {

    private static final Path MF4 = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/tata_sierra_0002391_20260921_133054 (1).mf4");
    private static final Path DBC = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/TEMP DBC 1 (1).dbc");

    @TempDir Path temp;
    private DesktopAppService service;
    private Stage stage;
    private GraphController graph;
    private InvestigationController investigation;
    private ActiveSignalWorkspacePane signals;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        JavaFxTestSupport.runOnFx("dispose closure", () -> {
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
            signals = null;
        });
        if (service != null) {
            service.shutdown();
            service = null;
        }
        JavaFxTestSupport.drainFxPulse();
    }

    @Test
    @Timeout(600)
    void plottedSnapshotAndTimestampJumps() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC));
        assertEquals("1.0.3", DesktopVersion.displayVersion());
        Path out = Path.of(System.getProperty("java.io.tmpdir"), "m20-closure");
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
        TreeSet<Long> times = new TreeSet<>();
        TreeSet<Long> ch01Times = new TreeSet<>();
        collect(store, ch01.key(), times, ch01Times);
        collect(store, ch02.key(), times, null);
        collect(store, latitude.key(), times, null);
        long first = times.first();
        long last = times.last();
        SignalSamplePoint ch01First = store.querySamples(ch01.key(), 0, 1).getFirst();
        SignalSamplePoint ch01Tenth = store.querySamples(ch01.key(), 10, 1).getFirst();
        SignalSamplePoint ch01Eleventh = store.querySamples(ch01.key(), 11, 1).getFirst();
        SignalSamplePoint ch01Later = store.querySamples(ch01.key(), 400, 1).getFirst();
        long between = (ch01Tenth.timestampNanos() + ch01Eleventh.timestampNanos()) / 2L;
        step("service", "alignedUnion=" + times.size() + " first=" + first + " last=" + last
                + " between=" + between);

        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("project.db")));
        service.newProject("M20 Closure");
        service.addMeasurementPaths(List.of(MF4), DesktopAppService.PlatformUi.NOOP);
        service.addDefinitionPaths(List.of(DBC));
        service.signalsModel().replaceCatalog(store);
        assertTrue(service.signalsModel().addPhysicalToWorkspace(ch01.key()));
        assertTrue(service.signalsModel().addPhysicalToWorkspace(ch02.key()));
        assertTrue(service.signalsModel().addPhysicalToWorkspace(latitude.key()));
        String projectId = service.currentProjectId();

        signals = showSignals();
        assertEquals(5_000, service.signalsModel().samplePageSize());
        jumpAndCheck("5000 exact CH01", seconds(ch01First.timestampNanos()), times, ch01Times, 5_000);
        jumpAndCheck("5000 between samples", seconds(between), times, ch01Times, 5_000);
        jumpAndCheck("5000 first", seconds(first), times, ch01Times, 5_000);
        jumpAndCheck("5000 last", seconds(last), times, ch01Times, 5_000);
        Jump outHigh = jump(seconds(last + 5_000_000_000L), null);
        assertTrue(outHigh.feedback().contains("outside"), outHigh.toString());
        step("ui", "5000 out-of-range feedback=" + outHigh.feedback() + " page " + outHigh.pageBefore() + " -> " + outHigh.pageAfter());
        Jump invalid = jump("-1", null);
        assertTrue(invalid.feedback().contains("Invalid"), invalid.toString());
        step("ui", "5000 negative feedback=" + invalid.feedback());
        JavaFxTestSupport.runOnFx("switch to 1000 rows", () -> rowsCombo().setValue(1_000));
        awaitPageSize(1_000);
        jumpAndCheck("1000 exact CH01", seconds(ch01First.timestampNanos()), times, ch01Times, 1_000);
        jumpAndCheck("1000 between samples", seconds(between), times, ch01Times, 1_000);
        jumpAndCheck("1000 first", seconds(first), times, ch01Times, 1_000);
        jumpAndCheck("1000 last", seconds(last), times, ch01Times, 1_000);
        Jump outHigh1000 = jump(seconds(last + 5_000_000_000L), null);
        assertTrue(outHigh1000.feedback().contains("outside"), outHigh1000.toString());
        step("ui", "1000 out-of-range feedback=" + outHigh1000.feedback()
                + " page " + outHigh1000.pageBefore() + " -> " + outHigh1000.pageAfter());
        shot(out.resolve("01-signal-1000.png"));
        JavaFxTestSupport.runOnFx("toolbar stays compact", () -> {
            assertEquals("Go", signals.indexGoButtonForTest().getText());
            assertTrue(signals.commandLabelsReadableForTest());
            String toolbar = signals.toolbarStructureForTest();
            assertFalse(toolbar.contains("Go to Index"));
            assertFalse(toolbar.contains("Aligned Timeline"));
            assertFalse(toolbar.contains("This Signal"));
        });
        closeStage();

        openGraph();
        awaitExplorer();
        JavaFxTestSupport.runOnFx("check CH01 and CH02", () -> {
            searchAndCheck(ch01.key(), "CH01_FRONT_BUMPER");
            searchAndCheck(ch02.key(), "CH02");
            if (graph.isSelectedForTest(latitude.key())) {
                graph.fireExplorerCheckboxForTest(latitude.key());
            }
            assertTrue(graph.isSelectedForTest(ch01.key()));
            assertTrue(graph.isSelectedForTest(ch02.key()));
            assertFalse(graph.isSelectedForTest(latitude.key()));
        });
        awaitTraces(2);
        shot(out.resolve("02-graph-traces.png"));
        step("ui", "renderedTracks=" + graph.lastRenderedCountForTest()
                + " sourceSamples=" + graph.renderedSourceSampleCountForTest()
                + " viewport=" + graph.viewportStartForTest() + ".." + graph.viewportEndForTest());
        assertTrue(graph.viewportEndForTest() > graph.viewportStartForTest());
        assertTrue(graph.renderedSourceSampleCountForTest() > 1_000);

        JavaFxTestSupport.runOnFx("add point and window on the plotted graph", () -> {
            assertTrue(graph.commitCreateInvestigationForTest("M20 Closure"));
            graph.pinEventSignalsForTest(List.of(ch01.key(), ch02.key()));
            graph.selectCursorAForTest(ch01First.timestampNanos());
            Dialog<?> pointDialog = graph.addEventDialogForTest();
            fill(pointDialog, "Closure point", "Thermal observation", "plotted point", false);
            assertFalse(graph.commitAddEventForTest(pointDialog).isBlank());
            graph.selectCursorAForTest(ch01First.timestampNanos());
            graph.selectCursorBForTest(ch01Later.timestampNanos());
            Dialog<?> windowDialog = graph.addEventDialogForTest();
            fill(windowDialog, "Closure window", "Thermal observation", "plotted window", true);
            assertFalse(graph.commitAddEventForTest(windowDialog).isBlank());
            graph.reloadEventsForTest();
            graph.selectEventIndexForTest(indexOf("Closure window"));
            graph.zoomToSelectedEventForTest();
        });
        awaitTraces(2);
        assertTrue(graph.viewportEndForTest() > graph.viewportStartForTest());
        assertFalse(graph.overlayLabelsForTest().isEmpty(), graph.overlayLabelsForTest().toString());
        step("ui", "eventViewport=" + graph.viewportStartForTest() + ".." + graph.viewportEndForTest()
                + " overlays=" + graph.overlayLabelsForTest()
                + " domain=" + graph.timelineGroupForTest());
        shot(out.resolve("03-graph-events.png"));

        JavaFxTestSupport.runOnFx("capture the plotted snapshot", () ->
                assertTrue(graph.commitCaptureSnapshotForTest("Closure graph")));
        var snapshotItem = investigations().stream().flatMap(item -> item.evidence().stream())
                .filter(item -> item.kind() == EvidenceKind.GRAPH_SNAPSHOT).findFirst().orElseThrow();
        Path snapshotPng = InvestigationStoragePaths.snapshot(
                AnalysisStorageRoots.projectArtifactsRoot(projectId),
                snapshotItem.investigationId(), snapshotItem.referenceId());
        Files.copy(snapshotPng, out.resolve("snapshot-plotted.png"));
        String snapshotHash = ReportEvidenceAssets.sha256(snapshotPng);
        Pixels pixels = pixels(snapshotPng);
        step("ui", "snapshot " + snapshotItem.referenceId() + " hash=" + snapshotHash + " " + pixels);
        assertTrue(pixels.blue() > 200, pixels.toString());
        assertTrue(pixels.purple() > 200, pixels.toString());
        assertTrue(pixels.marker() > 20, pixels.toString());

        EngineeringBookmarkMetadata point = bookmark("Closure point");
        JavaFxTestSupport.runOnFx("link the plotted snapshot", () -> {
            Dialog<?> link = graph.linkSnapshotDialogForTest(point.bookmarkId());
            graph.selectLinkSnapshotForTest(link, snapshotItem.referenceId());
            graph.commitLinkSnapshotForTest(link);
        });
        assertEquals(List.of(snapshotItem.referenceId()),
                EngineerEventDocuments.decode(bookmark("Closure point")).snapshotIds());

        List<SignalSamplePoint> lat = store.queryTimestampRange(latitude.key(), 0L, ch01Later.timestampNanos(), 400);
        List<SignalSamplePoint> lon = store.queryTimestampRange(longitude.key(), 0L, ch01Later.timestampNanos(), 400);
        MapRoute route = new MapRoute(MapRouteBuilder.validateAndBuild(lat, lon, List.of()).model());
        String eventSource = bookmark("Closure point").sourceId();
        String eventFile = EngineerEventDocuments.decode(bookmark("Closure point")).sourceFile();
        JavaFxTestSupport.runOnFx("install decoded route", () -> graph.installProjectRoutesForTest(
                new ProjectRoute(List.of(new RouteSegment(eventSource, eventFile, route)), lat.size(), lon.size())));
        Dialog<?>[] gps = new Dialog<?>[1];
        JavaFxTestSupport.runOnFx("request GPS", () -> {
            gps[0] = graph.gpsDialogForTest(point.bookmarkId());
            graph.requestGpsForTest(gps[0]);
        });
        awaitGps(gps[0]);
        JavaFxTestSupport.runOnFx("link GPS", () ->
                assertEquals("", graph.commitGpsLinkForTest(gps[0]), graph.gpsDetailForTest(gps[0])));
        assertEquals(GraphEventGps.MATCHED, GraphEventGps.read(bookmark("Closure point")).status());
        closeStage();

        service.saveInvestigation(investigations().stream().filter(item -> "M20 Closure".equals(item.name()))
                .findFirst().orElseThrow().toBuilder()
                .signalKeyBlobs(List.of(
                        GraphInvestigationCodec.encodeKey(ch01.key()),
                        GraphInvestigationCodec.encodeKey(ch02.key())))
                .build());
        openInvestigation();
        JavaFxTestSupport.runOnFx("include events and write the observation", () -> {
            chooseEngineeringTemplate();
            selectEngineerEvent("Closure point");
            button("Include in Report").fire();
            selectEngineerEvent("Closure window");
            button("Include in Report").fire();
            ((TextField) stage.getScene().getRoot().lookup("#observationTitle")).setText("Closure note");
            ((TextArea) stage.getScene().getRoot().lookup("#observationNote"))
                    .setText("Both bumper traces were plotted before the snapshot.");
            button("Add Observation").fire();
        });
        JavaFxTestSupport.drainFxPulse();
        Path htmlDir = out.resolve("html");
        Path pdfDir = out.resolve("pdf");
        Files.createDirectories(htmlDir);
        Files.createDirectories(pdfDir);
        export("m20-closure", htmlDir, false);
        export("m20-closure", pdfDir, true);
        Path html = Files.list(htmlDir).filter(path -> path.toString().endsWith(".html")).findFirst().orElseThrow();
        Path pdf = Files.list(pdfDir).filter(path -> path.toString().endsWith(".pdf")).findFirst().orElseThrow();
        String htmlText = Files.readString(html);
        assertTrue(htmlText.contains("Closure point"));
        assertTrue(htmlText.contains("Closure window"));
        assertTrue(htmlText.contains("Closure note"));
        assertTrue(htmlText.contains("observed=45"));
        assertTrue(htmlText.contains("84940000"));
        Path reportImage = Files.walk(htmlDir).filter(path -> path.getFileName().toString().startsWith("evidence-"))
                .findFirst().orElseThrow();
        assertEquals(snapshotHash, ReportEvidenceAssets.sha256(reportImage));
        Pixels reportPixels = pixels(reportImage);
        assertTrue(reportPixels.blue() > 200 && reportPixels.purple() > 200, reportPixels.toString());
        int pages;
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            pages = document.getNumberOfPages();
            String pdfText = new PDFTextStripper().getText(document);
            assertTrue(pdfText.contains("Closure point"));
            assertTrue(pdfText.contains("observed=45"));
            PDFRenderer renderer = new PDFRenderer(document);
            for (int page = 0; page < pages; page++) {
                ImageIO.write(renderer.renderImageWithDPI(page, 110), "png",
                        out.resolve("pdf-page-" + (page + 1) + ".png").toFile());
            }
        }
        assertEquals(snapshotHash, ReportEvidenceAssets.sha256(snapshotPng));
        var reopened = service.openSavedProject(projectId);
        assertTrue(reopened.opened(), reopened.warnings().toString());
        assertEquals(snapshotHash, ReportEvidenceAssets.sha256(snapshotPng));
        assertEquals(ch01First.timestampNanos(), bookmark("Closure point").timestampNanos());
        step("ui", "htmlBytes=" + Files.size(html) + " pdfBytes=" + Files.size(pdf)
                + " pages=" + pages + " hashUnchanged=true");
        System.out.println("M20C ARTIFACTS " + out.toAbsolutePath());
    }

    private void jumpAndCheck(String label, String requested, TreeSet<Long> times, TreeSet<Long> ch01Times, int pageSize)
            throws Exception {
        long nanos = new BigDecimal(requested).movePointRight(9).longValue();
        Long ceiling = times.ceiling(nanos);
        int expected = ceiling == null ? times.size() : times.headSet(ceiling).size();
        Jump jump = jump(requested, ceiling);
        assertTrue(jump.feedback().isBlank(), label + " " + jump);
        assertEquals(expected, jump.index(), label + " " + jump);
        assertEquals(ceiling.longValue(), jump.timestampNanos(), label + " " + jump);
        int page = expected / pageSize + 1;
        assertTrue(jump.pageAfter().startsWith(page + " /"), label + " " + jump);
        boolean ch01Present = ch01Times.contains(ceiling);
        assertEquals(ch01Present, jump.ch01Present(), label + " emptyCell=" + !ch01Present + " " + jump);
        step("ui", label + " requested=" + requested + " expectedIndex=" + expected
                + " actualIndex=" + jump.index() + " page " + jump.pageBefore() + " -> " + jump.pageAfter()
                + " feedback=" + (jump.feedback().isBlank() ? "none" : jump.feedback())
                + " ch01Cell=" + (jump.ch01Present() ? "value" : "empty"));
    }

    private Jump jump(String requested, Long expectedTimestamp) throws Exception {
        String[] before = new String[1];
        JavaFxTestSupport.runOnFx("jump " + requested, () -> {
            before[0] = signals.pageLabelForTest();
            signals.selectNavigateTimeForTest();
            signals.indexFieldForTest().setText(requested);
            signals.indexGoButtonForTest().fire();
        });
        long deadline = System.nanoTime() + 20_000_000_000L;
        Jump last = new Jump(before[0], "", "", -1, -1, false);
        while (System.nanoTime() < deadline) {
            JavaFxTestSupport.drainFxPulse();
            Jump[] holder = new Jump[1];
            JavaFxTestSupport.runOnFx("read jump", () -> holder[0] = readJump(before[0]));
            last = holder[0];
            if (!last.feedback().isBlank()) {
                return last;
            }
            if (expectedTimestamp != null && last.timestampNanos() == expectedTimestamp) {
                return last;
            }
            Thread.sleep(80);
        }
        return last;
    }

    private Jump readJump(String pageBefore) {
        String feedback = "";
        for (Node node : signals.lookupAll(".label")) {
            if (node instanceof Label label && label.getStyleClass().contains("nav-feedback")
                    && label.getText() != null) {
                feedback = label.getText();
            }
        }
        var row = signals.table().getSelectionModel().getSelectedItem();
        if (row == null) {
            return new Jump(pageBefore, signals.pageLabelForTest(), feedback, -1, -1, false);
        }
        return new Jump(pageBefore, signals.pageLabelForTest(), feedback, row.index(), row.timestampNanos(),
                row.physicalAt(0) != null);
    }

    private void awaitPageSize(int pageSize) throws Exception {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (System.nanoTime() < deadline) {
            JavaFxTestSupport.drainFxPulse();
            if (service.signalsModel().samplePageSize() == pageSize && !signals.table().getItems().isEmpty()
                    && signals.table().getItems().size() <= pageSize) {
                return;
            }
            Thread.sleep(80);
        }
        assertEquals(pageSize, service.signalsModel().samplePageSize());
    }

    private ComboBox<Integer> rowsCombo() {
        for (Node node : signals.lookupAll(".combo-box")) {
            if (node instanceof ComboBox<?> combo && combo.getItems().contains(5_000)) {
                @SuppressWarnings("unchecked")
                ComboBox<Integer> rows = (ComboBox<Integer>) combo;
                return rows;
            }
        }
        throw new AssertionError("rows combo was not found");
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

    private void awaitTraces(int tracks) throws Exception {
        long deadline = System.nanoTime() + 40_000_000_000L;
        while (System.nanoTime() < deadline) {
            int[] count = new int[1];
            long[] samples = new long[1];
            JavaFxTestSupport.runOnFx("trace count", () -> {
                count[0] = graph.lastRenderedCountForTest();
                samples[0] = graph.renderedSourceSampleCountForTest();
            });
            if (count[0] >= tracks && samples[0] > 0) {
                return;
            }
            JavaFxTestSupport.drainFxPulse();
            Thread.sleep(100);
        }
        throw new AssertionError("traces did not render tracks=" + graph.lastRenderedCountForTest());
    }

    private static void collect(SignalSeriesStore store, SignalCatalogKey key, TreeSet<Long> all, TreeSet<Long> also) {
        int offset = 0;
        while (true) {
            List<SignalSamplePoint> page = store.querySamples(key, offset, 4_096);
            if (page.isEmpty()) {
                return;
            }
            for (SignalSamplePoint sample : page) {
                all.add(sample.timestampNanos());
                if (also != null) {
                    also.add(sample.timestampNanos());
                }
            }
            offset += page.size();
            if (page.size() < 4_096) {
                return;
            }
        }
    }

    private static String seconds(long nanos) {
        return BigDecimal.valueOf(nanos).movePointLeft(9).stripTrailingZeros().toPlainString();
    }

    private static Pixels pixels(Path png) throws Exception {
        BufferedImage image = ImageIO.read(png.toFile());
        int blue = 0;
        int purple = 0;
        int marker = 0;
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
                if (r > 140 && g > 90 && b < 100 && r > b + 40) {
                    marker++;
                }
            }
        }
        return new Pixels(image.getWidth(), image.getHeight(), blue, purple, marker);
    }

    private void step(String kind, String detail) {
        System.out.println("M20C STEP kind=" + kind + " " + detail);
    }

    private ActiveSignalWorkspacePane showSignals() throws Exception {
        ActiveSignalWorkspacePane[] pane = new ActiveSignalWorkspacePane[1];
        JavaFxTestSupport.runOnFx("show signals", () -> {
            pane[0] = new ActiveSignalWorkspacePane();
            stage = new Stage();
            stage.setScene(new Scene(pane[0], 1280, 800));
            stage.show();
            pane[0].setModel(service.signalsModel());
        });
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            JavaFxTestSupport.drainFxPulse();
            if (!pane[0].table().getItems().isEmpty()) {
                signals = pane[0];
                return pane[0];
            }
            Thread.sleep(80);
        }
        throw new AssertionError("signal table stayed empty");
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

    private int indexOf(String title) {
        List<String> rows = graph.eventRowsForTest();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).contains(title)) {
                return i;
            }
        }
        return -1;
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

    private record Jump(String pageBefore, String pageAfter, String feedback, long index, long timestampNanos,
                         boolean ch01Present) {
        @Override
        public String toString() {
            return "page " + pageBefore + " -> " + pageAfter + " feedback=" + feedback
                    + " index=" + index + " ts=" + timestampNanos + " ch01=" + ch01Present;
        }
    }

    private record Pixels(int width, int height, int blue, int purple, int marker) {
        @Override
        public String toString() {
            return width + "x" + height + " blue=" + blue + " purple=" + purple + " marker=" + marker;
        }
    }
}
