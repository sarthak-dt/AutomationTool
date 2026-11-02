package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphEventGps;
import com.stellar.platform.desktop.presentation.ProjectInventoryCoordinator;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.support.TestMeasurementFixtures;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.map.MapRouteBuilder;
import com.stellar.platform.processing.map.MapRouteModel;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * M19 measurements. The Report Builder open budget stays below 5,000 ms.
 * Rows are printed as M19 so cold, warm, and unlike workloads stay separate.
 */
class M19WorkloadMeasurementTest {

    private static final Path MF4 = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/tata_sierra_0002391_20260921_133054 (1).mf4");
    private static final Path DBC = Path.of(
            "C:/Users/DT - User/Downloads/Mahima_Time_Issue/TEMP DBC 1 (1).dbc");
    private static final int[] EVENT_COUNTS = {0, 10, 50, 100, 400};

    @TempDir
    Path temp;

    private DesktopAppService service;
    private InvestigationController controller;
    private Stage stage;
    private ExecutorService inventoryPool;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        JavaFxTestSupport.runOnFx("dispose m19", () -> {
            if (controller != null) {
                controller.dispose();
                controller = null;
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
        if (inventoryPool != null) {
            inventoryPool.shutdownNow();
            inventoryPool = null;
        }
        JavaFxTestSupport.drainFxPulse();
    }

    @Test
    @Timeout(300)
    void reportBuilderCyclesEventsAndExportStayResponsive() throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("project.db")));
        service.newProject("M19 workload");
        EngineeringInvestigation investigation = service.saveInvestigation(service.createInvestigation(
                "M19", "workload", "FULL").toBuilder()
                .reportConfig(InvestigationReportConfig.engineeringV2())
                .build());

        long[] openMs = new long[5];
        for (int cycle = 0; cycle < openMs.length; cycle++) {
            long opened = System.nanoTime();
            Phase phase = openScreen();
            openMs[cycle] = (System.nanoTime() - opened) / 1_000_000L;
            System.out.println("M19 OPEN cycle=" + (cycle + 1)
                    + " kind=" + (cycle == 0 ? "cold" : "warm")
                    + " openMs=" + openMs[cycle]
                    + " fxmlMs=" + phase.fxmlMs
                    + " showMs=" + phase.showMs
                    + " refreshMs=" + phase.refreshMs
                    + " drainMs=" + phase.drainMs
                    + " usedBytes=" + usedHeap()
                    + " peakBytes=" + peakHeap()
                    + " gcCount=" + gcCount());
            assertTrue(openMs[cycle] < 5_000, "openMs=" + openMs[cycle]);
            closeScreen();
        }

        int saved = 0;
        for (int target : EVENT_COUNTS) {
            long saveStart = System.nanoTime();
            while (saved < target) {
                saved++;
                String id = service.saveGraphEvent(null, "Event " + saved, "Brake", "note " + saved,
                        saved * 1_000_000L, saved * 1_000_000L + 500_000L, "meas-1", "route.mf4",
                        List.of("CH01_FRONT_BUMPER_GRILL_AMBIENT"), List.of("CH01"));
                assertFalse(id.isBlank());
            }
            long saveMs = (System.nanoTime() - saveStart) / 1_000_000L;
            openScreen();
            long refreshStart = System.nanoTime();
            JavaFxTestSupport.runOnFx("refresh " + target + " events", () -> controller.refresh());
            long refreshMs = (System.nanoTime() - refreshStart) / 1_000_000L;
            long drainStart = System.nanoTime();
            JavaFxTestSupport.drainFxPulse();
            long drainMs = (System.nanoTime() - drainStart) / 1_000_000L;
            long listStart = System.nanoTime();
            int bookmarks = service.metadataStore().listBookmarks(service.currentProjectId()).size();
            long listMs = (System.nanoTime() - listStart) / 1_000_000L;
            System.out.println("M19 EVENTS count=" + target
                    + " saveMs=" + saveMs
                    + " refreshMs=" + refreshMs
                    + " drainMs=" + drainMs
                    + " listBookmarksMs=" + listMs
                    + " stored=" + bookmarks
                    + " usedBytes=" + usedHeap()
                    + " peakBytes=" + peakHeap());
            assertEquals(target, bookmarks);
            assertTrue(refreshMs < 5_000, "refreshMs=" + refreshMs);
            assertTrue(drainMs < 5_000, "drainMs=" + drainMs);
            closeScreen();
        }

        byte[] png = png();
        String pngHash = sha256(png);
        investigation = service.attachGraphSnapshot(png, "M19 snapshot");
        Path html = temp.resolve("m19.html");
        Path pdf = temp.resolve("m19.pdf");
        long htmlStart = System.nanoTime();
        service.generateInvestigationReport(investigation, "qa", html);
        long htmlMs = (System.nanoTime() - htmlStart) / 1_000_000L;
        long pdfStart = System.nanoTime();
        service.generateInvestigationPdfReport(investigation, "qa", pdf);
        long pdfMs = (System.nanoTime() - pdfStart) / 1_000_000L;
        String htmlText = Files.readString(html);
        int images = count(htmlText, "<img ");
        Path copied = findPng(html.getParent() == null ? temp : html.getParent());
        String copiedHash = copied == null ? "" : sha256(Files.readAllBytes(copied));
        System.out.println("M19 EXPORT events=400 snapshots=1"
                + " htmlMs=" + htmlMs
                + " pdfMs=" + pdfMs
                + " htmlBytes=" + Files.size(html)
                + " pdfBytes=" + Files.size(pdf)
                + " imgTags=" + images
                + " snapshotHashMatch=" + pngHash.equals(copiedHash)
                + " usedBytes=" + usedHeap()
                + " peakBytes=" + peakHeap()
                + " gcCount=" + gcCount()
                + " stellarThreads=" + stellarThreads());
        assertTrue(htmlMs < 60_000);
        assertTrue(pdfMs < 60_000);
        assertTrue(usedHeap() < 1_536L * 1024 * 1024);
        assertEquals(pngHash, copiedHash);

        long[] nanos = new long[1_000];
        double[] lat = new double[nanos.length];
        double[] lon = new double[nanos.length];
        for (int i = 0; i < nanos.length; i++) {
            nanos[i] = i * 1_000_000L;
            lat[i] = 19.0 + i / 100_000.0;
            lon[i] = 74.0 + i / 100_000.0;
        }
        MapRouteModel route = MapRouteBuilder.validateAndBuild(
                samples(nanos, lat), samples(nanos, lon), List.of()).model();
        GraphEventGps.RouteView view = new GraphEventGps.RouteView(
                route, "meas-1", "route.mf4", "", "LATITUDE", "LONGITUDE");
        var bookmarks = service.metadataStore().listBookmarks(service.currentProjectId());
        long gpsStart = System.nanoTime();
        int matched = 0;
        for (var bookmark : bookmarks) {
            if (GraphEventGps.MATCHED.equals(GraphEventGps.review(bookmark, view).status())) {
                matched++;
            }
        }
        long gpsMs = (System.nanoTime() - gpsStart) / 1_000_000L;
        System.out.println("M19 GPS lookups=400 routeSamples=1000 matched=" + matched
                + " gpsMs=" + gpsMs + " usedBytes=" + usedHeap());
        assertTrue(gpsMs < 5_000, "gpsMs=" + gpsMs);
    }

    @Test
    @Timeout(180)
    void inventoryWaitLeavesTheJavaFxThreadFree() throws Exception {
        assumeTrue(Files.isRegularFile(MF4) && Files.isRegularFile(DBC));
        System.setProperty("stellarai.home", temp.resolve("inv-home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("inv-meta.db").toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("inv.db")));
        service.newProject("M19 inventory");
        service.addDefinitionPaths(List.of(DBC));
        service.addMeasurementPaths(List.of(MF4), DesktopAppService.PlatformUi.NOOP);
        EngineeringInvestigation investigation = service.saveInvestigation(service.createInvestigation(
                "M19 inventory", "wait", "FULL").toBuilder()
                .reportConfig(InvestigationReportConfig.engineeringV2())
                .build());
        AtomicReference<String> reportThread = new AtomicReference<>();
        AtomicBoolean sawFx = new AtomicBoolean();
        Thread report = new Thread(() -> {
            reportThread.set(Thread.currentThread().getName());
            sawFx.set(PlatformIsFx());
            try {
                service.generateInvestigationReport(investigation, "qa", temp.resolve("during-inventory.html"));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        }, "m19-report");
        long started = System.nanoTime();
        report.start();
        long drainStart = System.nanoTime();
        JavaFxTestSupport.drainFxPulse();
        long drainMs = (System.nanoTime() - drainStart) / 1_000_000L;
        report.join(90_000);
        long reportMs = (System.nanoTime() - started) / 1_000_000L;
        String html = Files.readString(temp.resolve("during-inventory.html"));
        System.out.println("M19 INVENTORY kind=in-flight-sierra"
                + " reportThread=" + reportThread.get()
                + " calledOnFx=" + sawFx.get()
                + " fxDrainDuringWaitMs=" + drainMs
                + " reportMs=" + reportMs
                + " observed45=" + html.contains("observed=45")
                + " unavailable=" + html.contains("dbc-coverage-unavailable")
                + " usedBytes=" + usedHeap()
                + " peakBytes=" + peakHeap());
        assertFalse(sawFx.get());
        assertTrue(drainMs < 5_000, "fxDrainDuringWaitMs=" + drainMs);
        assertTrue(html.contains("observed=45"));
        assertFalse(html.contains("dbc-coverage-unavailable"));

        long idleStart = System.nanoTime();
        service.generateInvestigationReport(investigation, "qa", temp.resolve("idle.html"));
        long idleMs = (System.nanoTime() - idleStart) / 1_000_000L;
        System.out.println("M19 INVENTORY kind=already-complete reportMs=" + idleMs
                + " usedBytes=" + usedHeap());

        measureSyntheticInventory();
    }

    private void measureSyntheticInventory() throws Exception {
        int[] sizes = {1, 10, 25, 50, 100};
        inventoryPool = Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "stellar-source-inventory");
            thread.setDaemon(true);
            return thread;
        });
        for (int size : sizes) {
            ProjectInventoryCoordinator coordinator = new ProjectInventoryCoordinator(
                    (sourceId, path, ctx, progress) -> synthetic(path),
                    inventoryPool,
                    progress -> {
                    },
                    2);
            long started = System.nanoTime();
            for (int i = 0; i < size; i++) {
                Path file = temp.resolve("inv-" + size + "-" + i + ".mf4");
                TestMeasurementFixtures.writeStubMeasurement(file);
                coordinator.enqueue(new WorkspaceFile(file, FileKind.MEASUREMENT_MF4, 1), Runnable::run);
            }
            assertTrue(coordinator.awaitIdle(30_000));
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            System.out.println("M19 SYNTHETIC-INVENTORY files=" + size
                    + " elapsedMs=" + elapsed
                    + " decoded=false"
                    + " usedBytes=" + usedHeap()
                    + " peakBytes=" + peakHeap());
        }
    }

    private Phase openScreen() throws Exception {
        long[] phase = new long[3];
        JavaFxTestSupport.runOnFx("open m19", () -> {
            long mark = System.nanoTime();
            FXMLLoader loader = new FXMLLoader(InvestigationController.class.getResource("/fxml/investigation-view.fxml"));
            Parent root = loader.load();
            phase[0] = (System.nanoTime() - mark) / 1_000_000L;
            mark = System.nanoTime();
            controller = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1280, 800));
            stage.show();
            phase[1] = (System.nanoTime() - mark) / 1_000_000L;
            controller.setAppService(service, null);
            controller.setOpenGeneratedReportForTest(false);
            mark = System.nanoTime();
            controller.refresh();
            phase[2] = (System.nanoTime() - mark) / 1_000_000L;
        });
        long drainStart = System.nanoTime();
        JavaFxTestSupport.drainFxPulse();
        return new Phase(phase[0], phase[1], phase[2], (System.nanoTime() - drainStart) / 1_000_000L);
    }

    private void closeScreen() throws Exception {
        JavaFxTestSupport.runOnFx("close m19", () -> {
            if (controller != null) {
                controller.dispose();
                controller = null;
            }
            if (stage != null) {
                stage.close();
                stage = null;
            }
        });
        JavaFxTestSupport.drainFxPulse();
    }

    private static boolean PlatformIsFx() {
        return javafx.application.Platform.isFxApplicationThread();
    }

    private static List<SignalSamplePoint> samples(long[] times, double[] values) {
        List<SignalSamplePoint> out = new ArrayList<>();
        for (int i = 0; i < times.length; i++) {
            out.add(new SignalSamplePoint(times[i], values[i], null, null));
        }
        return out;
    }

    private static byte[] png() throws Exception {
        Path file = Files.createTempFile("m19-snapshot", ".png");
        ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        byte[] bytes = Files.readAllBytes(file);
        Files.deleteIfExists(file);
        return bytes;
    }

    private static Path findPng(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return null;
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".png")).findFirst().orElse(null);
        }
    }

    private static int count(String text, String token) {
        int n = 0;
        int from = 0;
        while (from < text.length()) {
            int at = text.indexOf(token, from);
            if (at < 0) {
                return n;
            }
            n++;
            from = at + token.length();
        }
        return n;
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : hash) {
            out.append(String.format("%02X", b));
        }
        return out.toString();
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

    private static long gcCount() {
        long count = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getCollectionCount() >= 0) {
                count += bean.getCollectionCount();
            }
        }
        return count;
    }

    private static int stellarThreads() {
        int n = 0;
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.isAlive() && thread.getName() != null && thread.getName().startsWith("stellar-")) {
                n++;
            }
        }
        return n;
    }

    private static CanSourceInventory synthetic(Path path) {
        List<CanMessageInventory> messages = List.of(
                new CanMessageInventory(new CanMessageKey(1, 0x100L, false), 10, 8, 8, false));
        CanChannelInventory channel = new CanChannelInventory(1, "Channel 1", 10, 1, 1, 1, 0, messages);
        return new CanSourceInventory(path, path.getFileName().toString(), MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 1, 10, 1, true, List.of(channel), List.of());
    }

    private record Phase(long fxmlMs, long showMs, long refreshMs, long drainMs) {
    }
}
