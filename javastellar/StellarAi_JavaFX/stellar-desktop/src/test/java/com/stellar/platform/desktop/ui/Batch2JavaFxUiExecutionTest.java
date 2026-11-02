package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopVersion;
import com.stellar.platform.desktop.presentation.AlignedSampleRow;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.WorkspaceDisplayMode;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.graph.GraphSeriesPalette;
import com.stellar.platform.desktop.ui.graph.map.RouteCanvas;
import com.stellar.platform.desktop.ui.graph.map.MapRoutePane;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.graph.timeline.UnknownOrderLayout;
import com.stellar.platform.processing.map.RouteSegment;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 2 QA execution on the real JavaFX shell.
 * Uses the existing isolated 73-file Xenon project. Does not reprocess it.
 */
@Timeout(value = 25, unit = TimeUnit.MINUTES)
class Batch2JavaFxUiExecutionTest {

    private static final String XENON_SHA =
            "F66753745C79EC35D7AA87CE96B877D549640C15FD52286585887F2D6ED62CF5";
    private static final Path EVIDENCE = Path.of(
            "D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\batch2-ui-execution.txt");
    private static final List<String> LINES = new ArrayList<>();
    private static final List<String> FAILURES = new ArrayList<>();

    private static DesktopAppService service;
    private static Stage stage;
    private static Scene scene;

    @BeforeAll
    static void toolkit() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterAll
    static void shutdown() {
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    @Test
    void signalsGraphAndGpsOnTheIsolatedDesktop() throws Exception {
        try {
            recordEnvironment();
            emptySignalsPage();
            openXenonProject();
            verifyCatalogOracle();
            launchShell();
            driveSignals();
            driveGraph();
            driveMap();
            saveAndReopen();
        } catch (Throwable thrown) {
            note("ABORTED " + thrown);
            thrown.printStackTrace(System.out);
            FAILURES.add("aborted: " + thrown.getMessage());
        } finally {
            writeEvidence();
            closeStage();
        }
        assertTrue(FAILURES.isEmpty(), String.join("\n", FAILURES));
    }

    private static void recordEnvironment() {
        Path home = Path.of(System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-73-home");
        String userHome = System.getProperty("user.home");
        note("started=" + Instant.now());
        note("java.version=" + System.getProperty("java.version"));
        note("java.home=" + System.getProperty("java.home"));
        note("product=" + DesktopVersion.displayVersion());
        note("mavenGav=0.1.0-SNAPSHOT");
        note("launch=mvn -pl stellar-desktop -am test -Dtest=Batch2JavaFxUiExecutionTest -Dsurefire.failIfNoSpecifiedTests=false");
        note("stellarHome=" + home);
        note("user.home=" + userHome);
        boolean profileDot = home.startsWith(Path.of(userHome, ".stellarai"));
        boolean customer = home.startsWith(Path.of("D:\\config")) || home.startsWith(Path.of("D:\\StellarAI-E2E"));
        check("ENV-HOME", !profileDot && !customer && Files.isRegularFile(home.resolve("meta.db")),
                "home=" + home + " profileDot=" + profileDot + " customer=" + customer);
    }

    private static void emptySignalsPage() throws Exception {
        Path home = Files.createTempDirectory("batch2-empty-home");
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        service.newProject("Batch2Empty");
        launchShell();
        fx("empty signals", () -> {
            Button signalsButton = button("Signals");
            String tip = signalsButton.getTooltip() == null ? "" : signalsButton.getTooltip().getText();
            note("emptySignalsDisabled=" + signalsButton.isDisabled() + " tip=" + tip);
            signalsButton.fire();
            SignalsController signals = signals();
            int count = signals == null || signals.browserPaneForTest() == null
                    ? -1
                    : signals.browserPaneForTest().filteredEntries().size();
            note("emptySignalCount=" + count + " page=" + shell().visiblePage());
            boolean held = signalsButton.isDisabled() && signals == null && !tip.isBlank();
            boolean openedEmpty = signals != null && count == 0;
            check("TC_SIGNAL_004", held || openedEmpty,
                    "disabled=" + signalsButton.isDisabled() + " tip=" + tip + " count=" + count);
        });
        closeStage();
        service.shutdown();
        service = null;
    }

    private static void openXenonProject() throws Exception {
        Path home = Path.of(System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-73-home");
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                new SqliteProjectMetadataStore(home.resolve("meta.db")));
        var project = service.metadataStore().listProjects().stream()
                .filter(row -> "M2-73".equals(row.name()))
                .findFirst()
                .orElseThrow();
        assertTrue(service.openSavedProject(project.projectId()).opened());
        note("project=" + project.name() + " id=" + project.projectId());
        note("measurements=" + service.workspace().measurementFiles().size());
        note("processingStatus=" + service.workspace().processingStatus());
        check("PROJECT-73", service.workspace().measurementFiles().size() == 73,
                "files=" + service.workspace().measurementFiles().size());
    }

    private static void verifyCatalogOracle() throws Exception {
        WorkspaceFile xenon = null;
        List<WorkspaceFile> datFiles = service.workspace().measurementFiles().stream()
                .filter(file -> file.fileName().toLowerCase(Locale.ROOT).endsWith(".dat"))
                .toList();
        List<WorkspaceFile> preferred = new ArrayList<>();
        for (WorkspaceFile file : datFiles) {
            if (file.fileName().contains("2020-02-07 16-22-19")) {
                preferred.add(file);
            }
        }
        List<WorkspaceFile> scan = preferred.isEmpty() ? datFiles : preferred;
        for (WorkspaceFile file : scan) {
            String hash = sha256(file.path());
            note("hashCandidate=" + file.fileName() + " " + hash);
            if (XENON_SHA.equalsIgnoreCase(hash)) {
                xenon = file;
                break;
            }
        }
        if (xenon == null && scan != datFiles) {
            for (WorkspaceFile file : datFiles) {
                String hash = sha256(file.path());
                if (XENON_SHA.equalsIgnoreCase(hash)) {
                    xenon = file;
                    note("xenonFile=" + file.fileName());
                    note("xenonHash=" + hash);
                    break;
                }
            }
        }
        check("XENON-HASH", xenon != null, xenon == null ? "reference file not in the 73" : xenon.fileName());
        List<SignalCatalogEntry> catalog = service.signalsModel().catalog().entries();
        long aggregate = catalog.stream().mapToLong(SignalCatalogEntry::sampleCount).sum();
        note("catalogSignals=" + catalog.size());
        note("mergedAggregateSamples=" + aggregate);
        note("singleFileOracle=268069 applies to the reference file alone; this project stores all 73 files");
        check("XENON-COUNT", catalog.size() == 30, "signals=" + catalog.size() + " mergedSamples=" + aggregate);
        SignalCatalogEntry speed = named(catalog, "IN_Vehicle_speed");
        SignalCatalogEntry coolant = named(catalog, "IN_Coolant_temperature");
        SignalCatalogEntry fan = named(catalog, "ETC_Fan2_output");
        note("speedMeta unit=" + speed.unit() + " samples=" + speed.sampleCount());
        note("coolantMeta unit=" + coolant.unit() + " samples=" + coolant.sampleCount());
        note("fanMeta unit=" + fan.unit() + " samples=" + fan.sampleCount());
        AtomicReference<String> speedHit = new AtomicReference<>("missing");
        AtomicReference<String> coolantHit = new AtomicReference<>("missing");
        AtomicReference<String> fanHit = new AtomicReference<>("missing");
        AtomicInteger speedIndex = new AtomicInteger(-1);
        AtomicInteger fanIndex = new AtomicInteger(-1);
        int[] cursor = {0};
        service.signalsModel().catalogStore().forEachSample(speed.key(), sample -> {
            if ("missing".equals(speedHit.get()) && Long.valueOf(9340L).equals(sample.rawValue())) {
                speedHit.set(describe(sample));
                speedIndex.set(cursor[0]);
            }
            cursor[0]++;
        });
        cursor[0] = 0;
        service.signalsModel().catalogStore().forEachSample(coolant.key(), sample -> {
            if ("missing".equals(coolantHit.get()) && Long.valueOf(320L).equals(sample.rawValue())) {
                coolantHit.set(describe(sample));
            }
        });
        cursor[0] = 0;
        service.signalsModel().catalogStore().forEachSample(fan.key(), sample -> {
            if ("missing".equals(fanHit.get())
                    && sample.textualValue() != null
                    && "FALSE".equalsIgnoreCase(sample.textualValue())) {
                fanHit.set(describe(sample));
                fanIndex.set(cursor[0]);
            }
            cursor[0]++;
        });
        note("speedRaw9340 index=" + speedIndex.get() + " " + speedHit.get());
        note("coolantRaw320 " + coolantHit.get());
        note("fanFalse index=" + fanIndex.get() + " " + fanHit.get());
        check("XENON-SPEED", speedHit.get().contains("72.96875") && speedHit.get().contains("VALID"), speedHit.get());
        check("XENON-COOLANT", coolantHit.get().contains("80.0") && coolantHit.get().contains("VALID"), coolantHit.get());
        check("XENON-FAN", fanHit.get().contains("FALSE")
                        && fanHit.get().contains("VALID")
                        && fanHit.get().contains("NaN"),
                fanHit.get());
        System.setProperty("batch2.speedIndex", Integer.toString(speedIndex.get()));
        System.setProperty("batch2.fanIndex", Integer.toString(fanIndex.get()));
    }

    private static void driveSignals() throws Exception {
        fx("open signals", () -> {
            Button signalsButton = button("Signals");
            note("signalsEnabled=" + !signalsButton.isDisabled());
            System.setProperty("batch2.signalsEnabled", Boolean.toString(!signalsButton.isDisabled()));
            if (signalsButton.isDisabled()) {
                shell().openRoute(com.stellar.platform.desktop.presentation.WorkspaceRoute.SIGNALS);
            } else {
                signalsButton.fire();
            }
        });
        await("signals ready", () -> {
            SignalsController signals = signals();
            return signals != null
                    && signals.browserPaneForTest() != null
                    && signals.browserPaneForTest().filteredEntries().size() == 30;
        }, 60);
        fx("signals chrome", () -> {
            SignalsController signals = signals();
            boolean sidebar = button("Signals").getStyleClass().contains("sidebar-item-active")
                    || button("Signals").getStyleClass().stream().anyMatch(style -> style.contains("active"));
            note("signalsSidebarClasses=" + button("Signals").getStyleClass());
            note("signalCountText=" + signals.browserPaneForTest().countTextForTest());
            note("signalContext=" + signals.measurementCardForTest()
                    + " | " + signals.dbcValueForTest()
                    + " | " + signals.channelsCardForTest()
                    + " | " + signals.signalCountForTest());
            note("browserColumns=" + signals.browserPaneForTest().catalogTable().getColumns().stream()
                    .map(column -> String.valueOf(column.getText())).toList());
            check("TC_SIGNAL_001",
                    "true".equals(System.getProperty("batch2.signalsEnabled"))
                            && signals.browserPaneForTest().filteredEntries().size() == 30,
                    "count=" + signals.browserPaneForTest().filteredEntries().size()
                            + " sidebar=" + sidebar
                            + " enabled=" + System.getProperty("batch2.signalsEnabled"));
        });
        search("IN_Vehicle_speed", 1);
        search("vehicle_speed", 1);
        search("VEHICLE_SPEED", 1);
        search("no-such-signal-batch2", 0);
        search("", 30);
        addSignal("IN_Vehicle_speed");
        addSignal("IN_Coolant_temperature");
        addSignal("ETC_Fan2_output");
        await("workspace rows", () -> {
            SignalsController signals = signals();
            return signals != null && !signals.workspacePaneForTest().table().getItems().isEmpty();
        }, 60);
        int speedIndex = scenePropertyInt("speedIndex");
        int fanIndex = scenePropertyInt("fanIndex");
        fx("goto speed", () -> {
            TextField field = signals().workspacePaneForTest().indexFieldForTest();
            field.setText(Integer.toString(speedIndex));
            signals().workspacePaneForTest().indexGoButtonForTest().fire();
        });
        await("speed row", () -> rowContains(speedIndex, "72.96875") || rowContains(speedIndex, "9340"), 30);
        fx("raw mode", () -> setDisplayMode(WorkspaceDisplayMode.RAW));
        await("raw speed", () -> rowContains(speedIndex, "9340"), 20);
        fx("physical mode", () -> setDisplayMode(WorkspaceDisplayMode.PHYSICAL));
        await("physical speed", () -> rowContains(speedIndex, "72.96875"), 20);
        fx("goto fan", () -> {
            signals().workspacePaneForTest().indexFieldForTest().setText(Integer.toString(fanIndex));
            signals().workspacePaneForTest().indexGoButtonForTest().fire();
        });
        await("fan row", () -> rowText().toUpperCase(Locale.ROOT).contains("FALSE"), 30);
        fx("signal values", () -> {
            String rows = rowText();
            note("workspaceRows=" + clip(rows, 1200));
            note("inspector=" + clip(collect(signals().inspectorPaneForTest()), 800));
            boolean numericFan = rows.contains("ETC") && rows.matches("(?s).*FALSE.*\\d+\\.\\d+.*");
            note("TC_SIGNAL_038 page=" + clip(rows, 400));
            note("TC_SIGNAL_CATEGORICAL visibleFalse=" + rows.toUpperCase(Locale.ROOT).contains("FALSE")
                    + " numericLooking=" + numericFan);
        });
        fx("sort and clear", () -> {
            TableView<SignalCatalogEntry> table = signals().browserPaneForTest().catalogTable();
            var name = table.getColumns().stream()
                    .filter(column -> column.getText() != null && column.getText().toLowerCase(Locale.ROOT).contains("signal"))
                    .findFirst()
                    .orElse(null);
            String before = table.getItems().isEmpty() ? "" : table.getItems().getFirst().signalName();
            if (name != null) {
                table.getSortOrder().setAll(name);
                name.setSortType(javafx.scene.control.TableColumn.SortType.ASCENDING);
                table.sort();
            }
            String after = table.getItems().isEmpty() ? "" : table.getItems().getFirst().signalName();
            note("sortBefore=" + before + " sortAfter=" + after);
            check("TC_SIGNAL_022", name != null && !after.isBlank(), "before=" + before + " after=" + after);
            int selected = table.getSelectionModel().getSelectedItems().size();
            table.getSelectionModel().clearSelection();
            if (!table.getItems().isEmpty()) {
                table.getSelectionModel().select(0);
                if (table.getItems().size() > 2) {
                    table.getSelectionModel().select(2);
                }
            }
            note("multiSelectCount=" + table.getSelectionModel().getSelectedItems().size() + " prior=" + selected);
            signals().workspacePaneForTest().clearAllButtonForTest().fire();
        });
        await("cleared", () -> signals().workspacePaneForTest().table().getItems().isEmpty(), 15);
        fx("after clear", () -> note("workspaceAfterClearItems=" + signals().workspacePaneForTest().table().getItems().size()));
    }

    private static void driveGraph() throws Exception {
        fx("open graphs", () -> {
            Button graphsButton = button("Graphs & Analysis");
            note("graphsEnabled=" + !graphsButton.isDisabled());
            if (graphsButton.isDisabled()) {
                shell().openRoute(com.stellar.platform.desktop.presentation.WorkspaceRoute.GRAPHS);
            } else {
                graphsButton.fire();
            }
        });
        GraphController graph = graph();
        await("graph catalog", () -> graph.explorerRowsForTest() != null && graph.explorerRowsForTest().size() >= 30, 90);
        SignalCatalogEntry speed = explorer("IN_Vehicle_speed");
        SignalCatalogEntry coolant = explorer("IN_Coolant_temperature");
        fx("plot two", () -> {
            graph.fireExplorerCheckboxForTest(speed.key());
            graph.fireExplorerCheckboxForTest(coolant.key());
        });
        await("two tracks", () -> graph.logicalTrackCountForTest() == 2
                && graph.segmentCountForTest(0) == 73
                && !graph.graphStatusForTest().startsWith("Loading"), 180);
        fx("stacked facts", () -> {
            var canvas = graph.graphCanvasForTest();
            note("layout=" + graph.layoutModeForTest());
            note("timeline=" + graph.timelineGroupForTest() + " label=" + graph.timelineGroupLabelForTest());
            note("range=" + graph.rangeTextForTest());
            note("status=" + graph.graphStatusForTest());
            note("colors=" + canvas.logicalTrackColorForTest(0) + " / " + canvas.logicalTrackColorForTest(1));
            boolean distinct = graph.logicalTrackCountForTest() >= 2
                    && !canvas.logicalTrackColorForTest(0).equals(canvas.logicalTrackColorForTest(1));
            note("GRAPH-LAYOUT " + graph.layoutModeForTest() + " tracks=" + graph.logicalTrackCountForTest()
                    + " distinct=" + distinct + " " + graph.timelineGroupForTest());
            if (!"UNKNOWN_ORDER".equals(graph.timelineGroupForTest())) {
                button("Unknown Order").fire();
            }
        });
        await("unknown order", () -> "UNKNOWN_ORDER".equals(graph.timelineGroupForTest())
                || graph.graphStatusForTest().contains("UNKNOWN ORDER"), 60);
        fx("unknown order result", () -> note("afterUnknownOrder timeline=" + graph.timelineGroupForTest()
                + " label=" + graph.timelineGroupLabelForTest()
                + " range=" + graph.rangeTextForTest()
                + " status=" + graph.graphStatusForTest()));
        fx("overlay", () -> toggle("Overlay").fire());
        await("overlay mode", () -> "OVERLAY".equals(graph.layoutModeForTest())
                && graph.logicalTrackCountForTest() >= 1
                && !graph.graphStatusForTest().startsWith("Loading"), 120);
        String hover = hover();
        note("hover=" + hover.replace('\n', ' '));
        check("GRAPH-HOVER", hover.startsWith("Pointer:") && !hover.contains("UTC"), hover);
        long before = value(() -> graph.viewportStartForTest());
        fx("zoom pan reset", () -> {
            for (int i = 0; i < 8; i++) {
                graph.zoomInForTest();
            }
        });
        await("zoomed", () -> graph.viewportEndForTest() - graph.viewportStartForTest()
                < UnknownOrderLayout.EQUAL_SLOT_SPAN
                && !graph.graphStatusForTest().startsWith("Loading"), 120);
        note("deepFullResolutionSegments=" + count(() -> graph.fullResolutionSegmentCountForTest()));
        note("deepRange=" + text(() -> graph.rangeTextForTest()));
        fx("pan", () -> graph.panRightForTest());
        await("panned", () -> graph.viewportStartForTest() != before
                && !graph.graphStatusForTest().startsWith("Loading"), 60);
        fx("reset", () -> button("Reset View").fire());
        await("reset", () -> graph.segmentCountForTest(0) == 73
                && !graph.graphStatusForTest().startsWith("Loading"), 180);
        fx("inspector", () -> {
            Button toggle = (Button) scene.lookup("#inspectorHeaderToggleButton");
            if (toggle == null) {
                toggle = buttonContaining("Inspector");
            }
            boolean beforeOpen = graphInspectorOpen();
            if (toggle != null) {
                toggle.fire();
                toggle.fire();
            }
            note("inspectorToggle=" + (toggle == null ? "missing" : toggle.getText())
                    + " before=" + beforeOpen + " after=" + graphInspectorOpen());
        });
        int explorerCount = count(() -> graph.explorerRowsForTest().size());
        fx("select all", () -> graph.selectAllFilteredForTest());
        await("selected", () -> graph.selectedSignalCountForTest() >= explorerCount, 30);
        fx("overlay cap", () -> {
            if (!"OVERLAY".equals(graph.layoutModeForTest())) {
                toggle("Overlay").fire();
            }
            note("selectedCount=" + graph.selectedSignalCountForTest() + " explorer=" + explorerCount);
            note("overlayStatus=" + graph.graphStatusForTest());
        });
        await("overlay notice", () -> graph.graphStatusForTest().contains("Showing"), 90);
        fx("select all result", () -> {
            String status = graph.graphStatusForTest();
            note("selectAllStatus=" + status);
            note("selectedAfterRender=" + graph.selectedSignalCountForTest());
            check("GRAPH-SELECT-ALL", graph.selectedSignalCountForTest() == explorerCount,
                    "selected=" + graph.selectedSignalCountForTest() + " explorer=" + explorerCount);
            note("GRAPH-OVERLAY-CAP " + (explorerCount <= 24 || status.contains("24") || status.contains("Showing")
                    ? "VISIBLE " : "NOT-ON-SCREEN ") + status);
        });
    }

    private static void driveMap() throws Exception {
        fx("map tab", () -> graph().selectMapRouteTab());
        await("routes", () -> {
            String status = map().routeStatusForTest();
            return status.contains("Recording order unknown") && !status.contains("Loading");
        }, 180);
        fx("all routes", () -> {
            MapRoutePane map = map();
            RouteCanvas canvas = map.routeCanvasForTest();
            canvas.paintForTest();
            note("mapStatus=" + map.routeStatusForTest());
            note("mapMapping=" + map.mappingStatusForTest());
            note("endpointPolicy=" + canvas.endpointPolicyForTest());
            note("endpointMarks=" + canvas.endpointMarksForTest().size());
            check("GPS-ALL", canvas.endpointPolicyForTest() == RouteCanvas.EndpointPolicy.GLOBAL
                            && canvas.endpointMarksForTest().size() == 2
                            && "START".equals(canvas.endpointMarksForTest().get(0).role())
                            && "END".equals(canvas.endpointMarksForTest().get(1).role()),
                    map.routeStatusForTest());
        });
        AtomicReference<RouteSegment> chosen = new AtomicReference<>();
        fx("choose route", () -> {
            for (RouteSegment segment : map().gpsSegmentsForTest()) {
                if (segment.hasRoute() && segment.route().model().size() > 1) {
                    chosen.set(segment);
                    return;
                }
            }
        });
        check("GPS-ROUTE-PRESENT", chosen.get() != null,
                chosen.get() == null ? "no multi-point GPS route" : chosen.get().displayName());
        if (chosen.get() == null) {
            return;
        }
        fx("select route", () -> {
            ComboBox<Object> chooser = combo(map());
            if (chooser != null) {
                for (Object item : chooser.getItems()) {
                    if (chosen.get().displayName().equals(String.valueOf(item))) {
                        chooser.setValue(item);
                        break;
                    }
                }
            }
            // setValue does not fire the chooser action when that recording is already
            // selected. showOnly is the verified single-recording path.
            if (map().routeCanvasForTest().endpointPolicyForTest() != RouteCanvas.EndpointPolicy.OWN_ROUTE) {
                map().showOnly(chosen.get());
            }
        });
        fx("one route", () -> {
            RouteCanvas canvas = map().routeCanvasForTest();
            canvas.paintForTest();
            note("oneStatus=" + map().routeStatusForTest());
            note("onePolicy=" + canvas.endpointPolicyForTest());
            note("oneMarks=" + canvas.endpointMarksForTest().stream().map(mark -> mark.role()).toList());
            note("oneMeasurement=" + map().selectedMeasurementIdForTest());
            check("GPS-ONE", canvas.endpointPolicyForTest() == RouteCanvas.EndpointPolicy.OWN_ROUTE
                            && canvas.endpointMarksForTest().size() == 2
                            && "START".equals(canvas.endpointMarksForTest().get(0).role())
                            && "END".equals(canvas.endpointMarksForTest().get(1).role()),
                    map().routeStatusForTest());
        });
        fx("fit", () -> button("Fit Route").fire());
        note("afterFit=" + text(() -> map().routeStatusForTest()));
        fx("restore all", () -> map().showAllRoutes());
        fx("all again", () -> {
            RouteCanvas canvas = map().routeCanvasForTest();
            canvas.paintForTest();
            check("GPS-ALL-RESTORED", canvas.endpointPolicyForTest() == RouteCanvas.EndpointPolicy.GLOBAL
                            && canvas.endpointMarksForTest().size() == 2
                            && "START".equals(canvas.endpointMarksForTest().get(0).role())
                            && "END".equals(canvas.endpointMarksForTest().get(1).role()),
                    map().routeStatusForTest());
        });
    }

    private static void saveAndReopen() throws Exception {
        int beforeArtifacts = service.metadataStore().loadProject(service.currentProjectId())
                .orElseThrow().processingArtifacts().size();
        String projectId = service.currentProjectId();
        int catalog = service.signalsModel().catalog().entries().size();
        fx("save", () -> button("Save").fire());
        await("saved", () -> collect(scene.getRoot()).contains("Saved"), 20);
        note("artifactsBeforeReopen=" + beforeArtifacts + " catalog=" + catalog);
        closeStage();
        service.shutdown();
        service = null;
        Path home = Path.of(System.getProperty("java.io.tmpdir"), "stellar-accuracy", "m2-73-home");
        service = new DesktopAppService(
                new DesktopSession(ParserRegistry.load()),
                new SqliteProjectMetadataStore(home.resolve("meta.db")));
        assertTrue(service.openSavedProject(projectId).opened());
        int afterArtifacts = service.metadataStore().loadProject(projectId).orElseThrow().processingArtifacts().size();
        int reopened = service.signalsModel().catalog().entries().size();
        note("reopenStatus=" + service.workspace().processingStatus());
        note("reopenCatalog=" + reopened);
        note("artifactsAfterReopen=" + afterArtifacts);
        check("PERSIST-CATALOG", reopened == 30 && afterArtifacts == beforeArtifacts,
                "catalog=" + reopened + " artifacts " + beforeArtifacts + " -> " + afterArtifacts);
        launchShell();
        fx("reopen signals", () -> {
            Button signalsButton = button("Signals");
            if (signalsButton.isDisabled()) {
                shell().openRoute(com.stellar.platform.desktop.presentation.WorkspaceRoute.SIGNALS);
            } else {
                signalsButton.fire();
            }
        });
        await("reopen signals ready", () -> signals() != null
                && signals().browserPaneForTest().filteredEntries().size() == 30, 60);
        fx("reopen signal count", () -> note("reopenSignalCount="
                + signals().browserPaneForTest().countTextForTest()));
        fx("reopen graph", () -> {
            Button graphsButton = button("Graphs & Analysis");
            if (graphsButton.isDisabled()) {
                shell().openRoute(com.stellar.platform.desktop.presentation.WorkspaceRoute.GRAPHS);
            } else {
                graphsButton.fire();
            }
        });
        await("reopen graph ready", () -> graph().explorerRowsForTest() != null
                && !graph().explorerRowsForTest().isEmpty(), 90);
        fx("reopen facts", () -> {
            note("reopenTimeline=" + graph().timelineGroupForTest());
            note("reopenSelected=" + graph().selectedSignalCountForTest());
            note("reopenLayout=" + graph().layoutModeForTest());
            note("reopenRange=" + graph().rangeTextForTest());
        });
    }

    private static void search(String query, int expected) throws Exception {
        fx("search " + query, () -> signals().browserPaneForTest().searchField().setText(query));
        await("search " + query, () -> signals().browserPaneForTest().filteredEntries().size() == expected, 15);
        fx("search note " + query, () -> note("search q=" + query
                + " count=" + signals().browserPaneForTest().filteredEntries().size()
                + " text=" + signals().browserPaneForTest().countTextForTest()));
        check("SEARCH-" + (query.isBlank() ? "CLEAR" : query),
                signals().browserPaneForTest().filteredEntries().size() == expected,
                "q=" + query + " count=" + signals().browserPaneForTest().filteredEntries().size());
    }

    private static void addSignal(String name) throws Exception {
        fx("filter " + name, () -> signals().browserPaneForTest().searchField().setText(name));
        await("filter " + name, () -> signals().browserPaneForTest().filteredEntries().size() == 1
                && name.equals(signals().browserPaneForTest().filteredEntries().getFirst().signalName()), 15);
        fx("add " + name, () -> {
            signals().browserPaneForTest().headerSelectionCheckForTest().fire();
            note("added " + name + " via header checkbox");
        });
    }

    private static boolean rowContains(int index, String token) {
        for (AlignedSampleRow row : signals().workspacePaneForTest().table().getItems()) {
            if (row.index() != index) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            text.append(row.index()).append(' ').append(row.timestampNanos());
            for (int i = 0; i < row.signalCount(); i++) {
                text.append(' ').append(row.physicalAt(i)).append(' ').append(row.rawAt(i))
                        .append(' ').append(row.textualAt(i));
            }
            return text.toString().contains(token);
        }
        return false;
    }

    private static String rowText() {
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (AlignedSampleRow row : signals().workspacePaneForTest().table().getItems()) {
            text.append("idx=").append(row.index());
            for (int i = 0; i < row.signalCount(); i++) {
                text.append(" p=").append(row.physicalAt(i))
                        .append(" r=").append(row.rawAt(i))
                        .append(" t=").append(row.textualAt(i));
            }
            text.append('\n');
            if (++shown > 8) {
                break;
            }
        }
        return text.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setDisplayMode(WorkspaceDisplayMode mode) {
        for (Node node : signals().workspacePaneForTest().lookupAll(".combo-box")) {
            if (node instanceof ComboBox box && box.getValue() instanceof WorkspaceDisplayMode) {
                box.setValue(mode);
            }
        }
    }

    private static String hover() throws Exception {
        AtomicReference<String> found = new AtomicReference<>("");
        fx("hover", () -> {
            var canvas = graph().graphCanvasForTest();
            double[] size = canvas.innerCanvasSizeForTest();
            double width = size[0] > 1 ? size[0] : Math.max(400, canvas.getWidth());
            double y = size[1] > 1 ? size[2] + size[1] * 0.45 : 80;
            for (double x = 90; x < width - 10; x += 40) {
                canvas.movePointerForTest(x, y);
                String cursor = graph().liveCursorForTest();
                if (cursor.startsWith("Pointer:") && !cursor.contains("Hover waveform")) {
                    found.set(cursor);
                    return;
                }
            }
            found.set(graph().liveCursorForTest());
        });
        return found.get();
    }

    private static boolean graphInspectorOpen() {
        Node panel = scene.lookup("#inspectorPanel");
        return panel != null && panel.isVisible() && panel.isManaged();
    }

    private static SignalCatalogEntry named(List<SignalCatalogEntry> catalog, String name) {
        return catalog.stream().filter(entry -> name.equals(entry.signalName())).findFirst().orElseThrow();
    }

    private static SignalCatalogEntry explorer(String name) throws Exception {
        AtomicReference<SignalCatalogEntry> found = new AtomicReference<>();
        fx("explorer " + name, () -> {
            for (SignalCatalogEntry entry : graph().explorerRowsForTest()) {
                if (name.equals(entry.signalName())) {
                    found.set(entry);
                    return;
                }
            }
        });
        if (found.get() == null) {
            throw new IllegalStateException("missing explorer row " + name);
        }
        return found.get();
    }

    private static SignalsController signals() {
        return (SignalsController) shell().controllerForTest(NavigationPage.SIGNALS);
    }

    private static GraphController graph() {
        return (GraphController) shell().controllerForTest(NavigationPage.GRAPHS);
    }

    private static MapRoutePane map() {
        return graph().mapRoutePaneForTest();
    }

    private static void launchShell() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                Parent root = loader.load();
                MainShellController shell = loader.getController();
                shell.init(service);
                shell.setUnsavedLeaveDecisionForTest(
                        intent -> com.stellar.platform.desktop.presentation.UnsavedLeaveChoice.DISCARD);
                scene = new Scene(root, 1440, 900);
                scene.getProperties().put("shell", shell);
                scene.getStylesheets().add(
                        MainShellController.class.getResource("/css/stellar-desktop.css").toExternalForm());
                stage = new Stage();
                stage.setTitle(DesktopVersion.windowTitle());
                stage.setScene(scene);
                stage.show();
            } catch (Exception ex) {
                error.set(ex);
            } finally {
                latch.countDown();
            }
        });
        assertTrue(latch.await(30, TimeUnit.SECONDS), "shell did not start");
        if (error.get() != null) {
            throw new IllegalStateException("shell failed", error.get());
        }
    }

    private static MainShellController shell() {
        return (MainShellController) scene.getProperties().get("shell");
    }

    private static Button button(String text) {
        for (Node node : scene.getRoot().lookupAll(".button")) {
            if (node instanceof Button button && text.equals(button.getText()) && button.isVisible()) {
                return button;
            }
        }
        for (Node node : scene.getRoot().lookupAll(".button")) {
            if (node instanceof Button button && text.equals(button.getText())) {
                return button;
            }
        }
        throw new IllegalStateException("button not found: " + text);
    }

    private static Button buttonContaining(String text) {
        for (Node node : scene.getRoot().lookupAll(".button")) {
            if (node instanceof Button button && button.getText() != null && button.getText().contains(text)) {
                return button;
            }
        }
        return null;
    }

    private static ToggleButton toggle(String text) {
        for (Node node : scene.getRoot().lookupAll(".toggle-button")) {
            if (node instanceof ToggleButton button && text.equals(button.getText())) {
                return button;
            }
        }
        throw new IllegalStateException("toggle not found: " + text);
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<Object> combo(Parent parent) {
        for (Node node : parent.lookupAll(".combo-box")) {
            if (node instanceof ComboBox<?> box) {
                return (ComboBox<Object>) box;
            }
        }
        return null;
    }

    private static String collect(Parent parent) {
        StringBuilder text = new StringBuilder();
        collect(parent, text);
        return text.toString();
    }

    private static void collect(Node node, StringBuilder text) {
        if (node instanceof Labeled labeled && labeled.getText() != null) {
            text.append(labeled.getText()).append('\n');
        }
        if (node instanceof TextField field && field.getText() != null) {
            text.append(field.getText()).append('\n');
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, text);
            }
        }
    }

    private static int scenePropertyInt(String key) {
        return Integer.parseInt(System.getProperty("batch2." + key, "-1"));
    }

    private static String describe(SignalSamplePoint sample) {
        return "raw=" + sample.rawValue()
                + " physical=" + sample.physicalValue()
                + " textual=" + sample.textualValue()
                + " validity=" + sample.validity();
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder text = new StringBuilder();
        for (byte value : hash) {
            text.append(String.format("%02X", value));
        }
        return text.toString();
    }

    private static void fx(String label, JavaFxTestSupport.FxWork work) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(120, TimeUnit.SECONDS)) {
            throw new IllegalStateException("JavaFX action timed out (" + label + ")");
        }
        if (error.get() != null) {
            throw new IllegalStateException(label, error.get());
        }
    }

    private static void await(String label, java.util.function.BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            AtomicBoolean ok = new AtomicBoolean();
            AtomicReference<Throwable> error = new AtomicReference<>();
            fx(label, () -> {
                try {
                    ok.set(condition.getAsBoolean());
                } catch (Throwable thrown) {
                    error.set(thrown);
                }
            });
            if (error.get() != null) {
                throw new IllegalStateException(label, error.get());
            }
            if (ok.get()) {
                return;
            }
            Thread.sleep(200);
        }
        note("TIMEOUT " + label);
    }

    private static String text(java.util.function.Supplier<String> supplier) throws Exception {
        AtomicReference<String> value = new AtomicReference<>("");
        fx("text", () -> value.set(supplier.get()));
        return value.get();
    }

    private static int count(java.util.function.IntSupplier supplier) throws Exception {
        AtomicInteger value = new AtomicInteger();
        fx("count", () -> value.set(supplier.getAsInt()));
        return value.get();
    }

    private static long value(java.util.function.LongSupplier supplier) throws Exception {
        AtomicReference<Long> value = new AtomicReference<>(0L);
        fx("value", () -> value.set(supplier.getAsLong()));
        return value.get();
    }

    private static void check(String id, boolean pass, String actual) {
        String line = id + (pass ? " PASS " : " FAIL ") + actual.replace('\n', ' ');
        note(line);
        if (!pass) {
            FAILURES.add(line);
        }
    }

    private static void note(String line) {
        LINES.add(line);
        System.out.println("BATCH2 " + line);
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max);
    }

    private static void closeStage() throws Exception {
        if (stage == null) {
            return;
        }
        fx("close", () -> stage.close());
        stage = null;
    }

    private static void writeEvidence() throws Exception {
        Files.createDirectories(EVIDENCE.getParent());
        Files.writeString(EVIDENCE, String.join(System.lineSeparator(), LINES));
    }
}
