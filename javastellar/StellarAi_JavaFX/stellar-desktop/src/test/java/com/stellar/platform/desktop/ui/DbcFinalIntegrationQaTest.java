package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.desktop.presentation.AlignedSampleRow;
import com.stellar.platform.desktop.DesktopLogging;
import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationCodec;
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
import com.stellar.platform.domain.SignalValidity;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.graph.GraphRenderSeries;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import com.stellar.platform.processing.graph.MinMaxLodReducer;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project save, close, and reopen for multiline and named-table labels.
 * Workspace and Graph screenshots use the production panes. Report files use the
 * application report writer. This class does not click through the main shell.
 */
class DbcFinalIntegrationQaTest {

    private static final long VIEW_END = 1_200_000_000L;
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

    @TempDir Path temp;
    private final List<DesktopAppService> services = new ArrayList<>();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() {
        for (DesktopAppService service : services) {
            service.shutdown();
        }
        services.clear();
        DesktopLogging.closeForTests();
        DesktopTestIsolation.resetProcessState();
        System.clearProperty("stellarai.home");
        System.clearProperty("stellarai.analysis.storage.disable-auto");
    }

    @Test
    @Timeout(180)
    void namedTableLabelsSurviveSaveCloseAndReopen() throws Exception {
        Path qa = Path.of(System.getProperty("java.io.tmpdir"), "dbc-final-qa");
        replaceDirectory(qa);
        byte[] historical = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 4, 3, 2};
        Path historicalPng = qa.resolve("historical-snapshot.png");
        Files.write(historicalPng, historical);

        DesktopAppService service = newService(temp.resolve("Storage"));
        service.newProject("DBC final QA");
        String projectId = service.currentProjectId();
        WorkspaceFile measurement = prepare(service);
        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        service.enterProcessingWorkspaceFromDecision();
        assertEquals(1, service.processingSelectionModel().reviewedPlan().jobs().size(),
                String.valueOf(service.processingSelectionModel().reviewedPlan().jobs()));
        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);

        Path store = storeRoot(projectId);
        List<String> before = identities(store);
        assertEquals(List.of(
                "HVBAT_STATE|100000000|0|0.0|Sleep|VALID",
                "HVBAT_STATE|200000000|3|3.0|Ready|VALID",
                "Selector|300000000|0|0.0|Sleep|VALID",
                "Selector|360000000|0|0.0|Sleep|VALID",
                "Selector|400000000|1|1.0|Drive|VALID",
                "Speed|300000000|40|40.0||VALID",
                "Speed|360000000|50|50.0||VALID"
        ), before);
        assertEquals("km/h", unit(store, "Speed"));
        long indexStamp = Files.getLastModifiedTime(store.resolve(LosslessGraphSignalStore.INDEX_FILE_NAME)).toMillis();

        long uiStarted = System.nanoTime();
        byte[] graphPng = capturePanes(qa, store);
        long uiMs = (System.nanoTime() - uiStarted) / 1_000_000L;
        assertTrue(graphPng.length > 8_000, "graph screenshot bytes " + graphPng.length);
        assertEquals(historical.length, Files.size(historicalPng));

        service.workspace().selectMeasurement(measurement.id());
        service.graphInvestigation().setViewport(0L, VIEW_END);
        service.signalsModel().addPhysicalToWorkspace(entry(store, "HVBAT_STATE").key());
        service.signalsModel().addPhysicalToWorkspace(entry(store, "Selector").key());
        service.signalsModel().addPhysicalToWorkspace(entry(store, "Speed").key());
        var investigation = service.createInvestigation("DBC labels", "Named table", "CURRENT");
        service.saveInvestigation(investigation.toBuilder()
                .reportConfig(InvestigationReportConfig.engineeringV2())
                .build());
        SignalCatalogKey selectorKey = entry(store, "Selector").key();
        String eventId = service.saveGraphEvent(
                "", "Selector Sleep", "Observation", "named table",
                300_000_000L, 400_000_000L,
                measurement.id(), measurement.fileName(),
                List.of("Selector"), List.of(GraphInvestigationCodec.encodeKey(selectorKey)));
        assertFalse(eventId.isBlank());
        var withSnapshot = service.attachGraphSnapshot(graphPng, "Selector hold");
        String snapshotId = withSnapshot.evidence().stream()
                .filter(item -> item.kind() == EvidenceKind.GRAPH_SNAPSHOT)
                .findFirst().orElseThrow().referenceId();
        assertEquals("", service.linkGraphEventSnapshot(eventId, snapshotId));
        service.includeEngineerEvent(eventId);
        Path snapshotFile = InvestigationStoragePaths.snapshot(
                AnalysisStorageRoots.projectArtifactsRoot(projectId),
                withSnapshot.investigationId(), snapshotId);
        String snapshotHash = sha256(Files.readAllBytes(snapshotFile));
        assertTrue(Files.size(snapshotFile) > 8_000);

        Path html = service.generateInvestigationReport(
                service.engineeringInvestigations().find(
                        service.metadataStore(), withSnapshot.investigationId()).orElseThrow(),
                "QA", qa.resolve("dbc-final-report.html"));
        Path pdf = service.generateInvestigationPdfReport(
                service.engineeringInvestigations().find(
                        service.metadataStore(), withSnapshot.investigationId()).orElseThrow(),
                "QA", qa.resolve("dbc-final-report.pdf"));
        String report = Files.readString(html);
        assertTrue(report.contains("Selector Sleep"), report);
        assertTrue(report.contains("0.300 s to 0.400 s"), report);
        assertTrue(report.contains("No event geofence or pass/fail rule is applied in this report."), report);
        assertTrue(report.contains("None of these rows is a vehicle defect or a pass/fail judgement."), report);
        assertTrue(report.contains("Processing: Ready"), report);
        assertTrue(report.contains("COMPLETED"), report);
        assertFalse(report.toLowerCase().contains("vehicle pass"));
        assertFalse(report.toLowerCase().contains("vehicle fail"));
        assertTrue(Files.size(pdf) > 1_000);
        String health = service.projectHealthDisplay();
        Files.writeString(qa.resolve("manifest.txt"), String.join("\n",
                "projectId=" + projectId,
                "health=" + health,
                "uiMs=" + uiMs,
                "heapUsed=" + usedHeap(),
                "heapPeak=" + peakHeap(),
                "snapshotHash=" + snapshotHash,
                "samples=" + before.size()), StandardCharsets.UTF_8);

        assertTrue(service.saveProject().saved());
        service.shutdown();
        services.remove(service);
        AnalysisStorageRoots.clear();

        DesktopAppService reopened = newService(temp.resolve("Storage"));
        DesktopAppService.ProjectOpenResult opened = reopened.openSavedProject(projectId);
        assertTrue(opened.opened(), String.join("; ", opened.warnings()));
        assertEquals(before, identities(store));
        assertEquals(indexStamp, Files.getLastModifiedTime(
                store.resolve(LosslessGraphSignalStore.INDEX_FILE_NAME)).toMillis());
        assertEquals(eventId, reopened.findEngineeringBookmark(eventId).orElseThrow().bookmarkId());
        assertEquals(300_000_000L, reopened.findEngineeringBookmark(eventId).orElseThrow().timestampNanos());
        assertEquals(snapshotHash, sha256(Files.readAllBytes(snapshotFile)));
        assertArrayEqualsHistorical(historical, Files.readAllBytes(historicalPng));
        assertTrue(reopened.signalsModel().catalog().entries().stream()
                .anyMatch(entry -> "Selector".equals(entry.signalName())));
        assertTrue(usedHeap() < 1536L * 1024 * 1024);
    }

    private byte[] capturePanes(Path qa, Path store) throws Exception {
        List<SignalSamplePoint> selector = samples(store, "Selector");
        List<SignalSamplePoint> speed = samples(store, "Speed");
        SignalCatalogEntry selectorEntry = entry(store, "Selector");
        SignalCatalogEntry speedEntry = entry(store, "Speed");
        byte[][] graph = new byte[1][];
        JavaFxTestSupport.runOnFx("workspace and graph", () -> {
            ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
            Stage stage = new Stage();
            stage.setScene(new Scene(pane, 1366, 768));
            stage.show();
            pane.resize(1366, 768);
            pane.applyCss();
            pane.layout();
            var rebuild = ActiveSignalWorkspacePane.class.getDeclaredMethod("rebuildColumns", List.class);
            rebuild.setAccessible(true);
            rebuild.invoke(pane, List.of(selectorEntry, speedEntry));
            pane.table().getItems().setAll(List.of(
                    row(0, 300_000_000L, selector, speed),
                    row(1, 400_000_000L, selector, speed),
                    row(2, 360_000_000L, selector, speed)));
            pane.layout();
            assertTrue(cellText(pane).contains("0 = Sleep"), cellText(pane).toString());
            assertTrue(cellText(pane).contains("1 = Drive"), cellText(pane).toString());
            assertTrue(cellText(pane).contains(WorkspacePrecision.AUTO.formatPhysical(40.0)));
            assertTrue(cellText(pane).contains(AlignedSampleRow.MISSING));
            ImageIO.write(SwingFXUtils.fromFXImage(pane.snapshot(null, null), null), "png",
                    qa.resolve("workspace-physical.png").toFile());
            displayMode(pane).setValue(WorkspaceDisplayMode.RAW);
            pane.layout();
            assertTrue(cellText(pane).contains("0 (Sleep)"), cellText(pane).toString());
            ImageIO.write(SwingFXUtils.fromFXImage(pane.snapshot(null, null), null), "png",
                    qa.resolve("workspace-raw.png").toFile());
            stage.close();

            AutomotiveSignalCanvas canvas = new AutomotiveSignalCanvas();
            canvas.setTracePresentation(java.util.Map.of(
                    selectorEntry.key(), "Selector", speedEntry.key(), "Speed"), java.util.Map.of());
            canvas.setMinSize(1200, 640);
            canvas.setPrefSize(1200, 640);
            Stage graphStage = new Stage();
            graphStage.setScene(new Scene(canvas, 1200, 640));
            graphStage.show();
            canvas.resize(1200, 640);
            canvas.prepareSharedViewport(0L, VIEW_END);
            canvas.setSeries(List.of(
                    new GraphRenderSeries(selectorEntry.key(), "", selector.size(), true, 0L, VIEW_END,
                            MinMaxLodReducer.reduce(selector, 20)),
                    new GraphRenderSeries(speedEntry.key(), "km/h", speed.size(), true, 0L, VIEW_END,
                            MinMaxLodReducer.reduce(speed, 20))));
            canvas.layout();
            canvas.flushDeferredPaintForTest();
            assertTrue(canvas.paintedStateLabelsForTest().contains("Sleep"));
            assertTrue(canvas.paintedStateLabelsForTest().contains("Drive"));
            WritableImage image = canvas.snapshot(null, null);
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png",
                    qa.resolve("graph-selector.png").toFile());
            graph[0] = Files.readAllBytes(qa.resolve("graph-selector.png"));
            graphStage.close();
        });
        return graph[0];
    }

    private WorkspaceFile prepare(DesktopAppService service) throws Exception {
        Path dbcPath = temp.resolve("values.dbc");
        Path ascPath = temp.resolve("values.asc");
        Files.writeString(dbcPath, DBC, StandardCharsets.UTF_8);
        Files.writeString(ascPath, ASC, StandardCharsets.UTF_8);
        WorkspaceFile dbc = new WorkspaceFile(dbcPath, FileKind.DEFINITION_DBC, Files.size(dbcPath));
        service.workspace().addFile(dbc);
        WorkspaceFile source = new WorkspaceFile(ascPath, FileKind.MEASUREMENT_ASC, Files.size(ascPath));
        service.workspace().addFile(source);
        CanSourceInventory inventory = new CanSourceInventory(
                ascPath, source.fileName(), MeasurementSourceFormat.ASC, CanInventoryStatus.INVENTORY_READY,
                5, 1, 2, true,
                List.of(new CanChannelInventory(
                        1, "Channel 1", 5, 2, 2, 0, 0,
                        List.of(
                                new CanMessageInventory(new CanMessageKey(1, 0x100L, false), 2, 1, 1, false),
                                new CanMessageInventory(new CanMessageKey(1, 0x610L, false), 3, 2, 2, false)))),
                List.of());
        service.sourceInventoryModel().put(source.id(), inventory);
        try (SqliteProjectMetadataStore metadata = new SqliteProjectMetadataStore(
                service.activeDatabasePath().orElseThrow())) {
            metadata.saveInventory(service.currentProjectId(), source.id(), inventory);
        }
        service.definitionMatchingModel().putMapping(new SourceChannelMappingEntry(
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
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        services.add(service);
        if (service.analysisStorage().userSelectedRoot().isEmpty()) {
            Files.createDirectories(selected);
            assertTrue(service.saveAnalysisStorage(selected).valid());
        }
        return service;
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
            SignalCatalogEntry entry = entry(opened, name);
            return opened.querySamples(entry.key(), 0, 20);
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

    private static AlignedSampleRow row(
            int index, long timestamp, List<SignalSamplePoint> selector, List<SignalSamplePoint> speed) {
        SignalSamplePoint selected = at(selector, timestamp);
        SignalSamplePoint analog = find(speed, timestamp);
        return new AlignedSampleRow(
                index, timestamp,
                new Double[] {selected.physicalValue(), analog == null ? null : analog.physicalValue()},
                new Long[] {selected.rawValue(), analog == null ? null : analog.rawValue()},
                new String[] {selected.textualValue(), analog == null ? null : analog.textualValue()},
                new SignalValidity[] {selected.validity(), analog == null ? null : analog.validity()});
    }

    private static SignalSamplePoint at(List<SignalSamplePoint> samples, long timestamp) {
        SignalSamplePoint found = find(samples, timestamp);
        if (found == null) {
            throw new AssertionError("missing " + timestamp);
        }
        return found;
    }

    private static SignalSamplePoint find(List<SignalSamplePoint> samples, long timestamp) {
        for (SignalSamplePoint sample : samples) {
            if (sample.timestampNanos() == timestamp) {
                return sample;
            }
        }
        return null;
    }

    private static java.util.Set<String> cellText(
            com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane pane) {
        java.util.Set<String> text = new java.util.HashSet<>();
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
    private static javafx.scene.control.ComboBox<WorkspaceDisplayMode> displayMode(
            com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane pane) {
        for (javafx.scene.Node node : pane.lookupAll(".combo-box")) {
            if (node instanceof javafx.scene.control.ComboBox<?> combo
                    && combo.getValue() == WorkspaceDisplayMode.PHYSICAL) {
                return (javafx.scene.control.ComboBox<WorkspaceDisplayMode>) combo;
            }
        }
        throw new AssertionError("physical display combo missing");
    }

    private static void waitForIdle(DesktopAppService service) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (service.isPlanExecutionActive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(service.isPlanExecutionActive(), "processing run did not reach a terminal state");
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

    private static void assertArrayEqualsHistorical(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i]);
        }
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
