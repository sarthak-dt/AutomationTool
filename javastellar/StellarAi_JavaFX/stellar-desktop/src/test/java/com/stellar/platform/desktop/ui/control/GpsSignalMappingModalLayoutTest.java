package com.stellar.platform.desktop.ui.control;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.map.GpsMappingDraft;
import com.stellar.platform.processing.map.GpsSignalRole;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scene-relative GPS mapping overlay: one dialog instance for normal window and Focus Mode.
 */
class GpsSignalMappingModalLayoutTest {

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    void dialogWidthClampsToSceneAndEmergencyMinimum() {
        assertEquals(1065.48, GpsSignalMappingModal.dialogWidth(1366), 0.01);
        assertEquals(1123.2, GpsSignalMappingModal.dialogWidth(1440), 0.01);
        assertEquals(1248.0, GpsSignalMappingModal.dialogWidth(1600), 0.01);
        assertEquals(1280.0, GpsSignalMappingModal.dialogWidth(1920), 0.01);
        assertEquals(852.0, GpsSignalMappingModal.dialogWidth(900), 0.01);
        assertEquals(752.0, GpsSignalMappingModal.dialogWidth(800), 0.01);
        assertTrue(GpsSignalMappingModal.dialogWidth(1366) <= 1366 - 48);
    }

    @Test
    void dialogHeightStaysInsideScene() {
        assertEquals(660.48, GpsSignalMappingModal.dialogHeight(768), 0.01);
        assertEquals(774.0, GpsSignalMappingModal.dialogHeight(900), 0.01);
        assertEquals(860.0, GpsSignalMappingModal.dialogHeight(1080), 0.01);
        assertTrue(GpsSignalMappingModal.dialogHeight(768) <= 768 - 32);
        assertTrue(GpsSignalMappingModal.dialogHeight(1080) <= 860);
    }

    @Test
    void modalCentersOnApplicationRootAndKeepsFooterVisible() throws Exception {
        Harness harness = showAt(1366, 768);
        runOnFx(() -> {
            assertSame(harness.root, harness.modal.getParent());
            assertTrue(harness.modal.footerOutsideScrollForTest());
            assertTrue(harness.modal.bodyVerticalScrollOnlyForTest());
            assertFalse(harness.modal.stackedBodyForTest(), "1366 desktop must keep two columns");
            assertDialogInsideScene(harness.modal, harness.root.getWidth(), harness.root.getHeight());
            assertTrue(harness.modal.dialogForTest().getBottom().getBoundsInParent().getHeight() >= 50);
        });
        close(harness);
    }

    @Test
    void sameInstanceSurvivesResizeAndFocusSizedScene() throws Exception {
        Harness harness = showAt(1366, 768);
        runOnFx(() -> {
            harness.modal.setDraft(GpsMappingDraft.resolve("p1", "drive.mf4", catalog(), List.of()));
            harness.modal.pickRoleForTest(GpsSignalRole.LATITUDE, "GPS_LAT_INFO");
            harness.modal.pickRoleForTest(GpsSignalRole.LONGITUDE, "GPS_LONG_INFO");
            harness.modal.pickRoleForTest(GpsSignalRole.ALTITUDE, "GPS_ALTITUDE");
            harness.modal.pickRoleForTest(GpsSignalRole.GPS_SPEED, "SPEED_OVER_GROUND");
        });
        assertEquals("GPS_LAT_INFO", harness.modal.assignedSignalForTest(GpsSignalRole.LATITUDE));
        assertEquals("GPS_LONG_INFO", harness.modal.assignedSignalForTest(GpsSignalRole.LONGITUDE));
        runOnFx(() -> resizeHarness(harness, 1920, 1080));
        runOnFx(() -> {
            assertSame(harness.root, harness.modal.getParent());
            assertTrue(harness.modal.isVisible());
            assertEquals("GPS_LAT_INFO", harness.modal.assignedSignalForTest(GpsSignalRole.LATITUDE));
            assertEquals("GPS_LONG_INFO", harness.modal.assignedSignalForTest(GpsSignalRole.LONGITUDE));
            assertEquals("GPS_ALTITUDE", harness.modal.assignedSignalForTest(GpsSignalRole.ALTITUDE));
            assertEquals("SPEED_OVER_GROUND", harness.modal.assignedSignalForTest(GpsSignalRole.GPS_SPEED));
            assertDialogInsideScene(harness.modal, harness.root.getWidth(), harness.root.getHeight());
        });
        runOnFx(() -> resizeHarness(harness, 1366, 768));
        runOnFx(() -> {
            assertSame(harness.root, harness.modal.getParent());
            assertEquals("GPS_LAT_INFO", harness.modal.assignedSignalForTest(GpsSignalRole.LATITUDE));
            assertEquals("GPS_LONG_INFO", harness.modal.assignedSignalForTest(GpsSignalRole.LONGITUDE));
            assertDialogInsideScene(harness.modal, harness.root.getWidth(), harness.root.getHeight());
        });
        close(harness);
    }

    @Test
    void overlayCoversBorderPaneApplicationRoot() throws Exception {
        AtomicReference<BorderPane> appRef = new AtomicReference<>();
        AtomicReference<GpsSignalMappingModal> modalRef = new AtomicReference<>();
        runOnFx(() -> {
            Label sidebar = new Label("Sidebar");
            sidebar.setPrefWidth(220);
            Label header = new Label("Header");
            header.setPrefHeight(48);
            Label workspace = new Label("Graphs");
            BorderPane app = new BorderPane();
            app.setLeft(sidebar);
            app.setTop(header);
            app.setCenter(workspace);
            GpsSignalMappingModal modal = new GpsSignalMappingModal();
            modal.setDraft(GpsMappingDraft.resolve("p1", "drive.mf4", catalog(), List.of()));
            new Scene(app, 1366, 768);
            app.resize(1366, 768);
            modal.showOnApplicationOverlay(workspace);
            app.applyCss();
            app.layout();
            appRef.set(app);
            modalRef.set(modal);
        });
        runOnFx(() -> {
            GpsSignalMappingModal modal = modalRef.get();
            BorderPane app = appRef.get();
            assertSame(app, modal.getParent(), "overlay must attach to the Scene root, not Graphs");
            assertFalse(modal.isManaged());
            assertTrue(modal.getWidth() >= app.getWidth() - 1, "overlay must span the full window width");
            assertTrue(modal.getHeight() >= app.getHeight() - 1, "overlay must span the full window height");
            assertDialogInsideScene(modal, app.getWidth(), app.getHeight());
            modal.detachOverlay();
        });
    }

    @Test
    void longMeasurementNameDoesNotExpandDialog() throws Exception {
        Harness harness = showAt(1366, 768);
        runOnFx(() -> {
            harness.modal.setMeasurementLabel("OBD2 Data_Lavasa_0002260_20240305_145056.mf4");
            harness.root.applyCss();
            harness.root.layout();
            assertTrue(harness.modal.dialogForTest().getWidth()
                    <= GpsSignalMappingModal.dialogWidth(harness.root.getWidth()) + 2.0);
            assertDialogInsideScene(harness.modal, harness.root.getWidth(), harness.root.getHeight());
        });
        close(harness);
    }

    @Test
    void writesNormalAndFocusSnapshots() throws Exception {
        Harness normal = showAt(1366, 768);
        runOnFx(() -> writeSnapshot(normal, "normal-1366x768.png"));
        close(normal);
        Harness focus = showAt(1920, 1080);
        runOnFx(() -> writeSnapshot(focus, "focus-1920x1080.png"));
        close(focus);
        assertTrue(Files.size(snapshotDir().resolve("normal-1366x768.png")) > 10_000);
        assertTrue(Files.size(snapshotDir().resolve("focus-1920x1080.png")) > 10_000);
    }

    private static void assertDialogInsideScene(GpsSignalMappingModal modal, double sceneW, double sceneH) {
        BorderPane dialog = modal.dialogForTest();
        Bounds bounds = dialog.localToScene(dialog.getBoundsInLocal());
        assertTrue(bounds.getMinX() >= -1, "left clip");
        assertTrue(bounds.getMinY() >= -1, "top clip");
        assertTrue(bounds.getMaxX() <= sceneW + 1, "right clip");
        assertTrue(bounds.getMaxY() <= sceneH + 1, "bottom/footer clip");
        assertTrue(bounds.getWidth() > 800);
        assertTrue(bounds.getHeight() > 500);
        double centerErrorX = Math.abs((bounds.getMinX() + bounds.getMaxX()) / 2.0 - sceneW / 2.0);
        double centerErrorY = Math.abs((bounds.getMinY() + bounds.getMaxY()) / 2.0 - sceneH / 2.0);
        assertTrue(centerErrorX < 40, "dialog must be centered horizontally");
        assertTrue(centerErrorY < 40, "dialog must be centered vertically");
    }

    private static Harness showAt(double width, double height) throws Exception {
        AtomicReference<Harness> ref = new AtomicReference<>();
        runOnFx(() -> {
            Label signals = new Label("Signals");
            signals.setMinWidth(200);
            signals.setPrefWidth(220);
            Label workspace = new Label("Graph workspace");
            Label inspector = new Label("Inspector");
            inspector.setMinWidth(240);
            inspector.setPrefWidth(280);
            SplitPane graphs = new SplitPane(signals, workspace, inspector);
            graphs.setDividerPositions(0.18, 0.72);
            StackPane root = new StackPane(graphs);
            GpsSignalMappingModal modal = new GpsSignalMappingModal();
            modal.setMeasurementLabel("OBD2 Data_Lavasa_0002260_20240305_145056.mf4");
            modal.setDraft(GpsMappingDraft.resolve("p1", "drive.mf4", catalog(), List.of()));
            Scene scene = new Scene(root, width, height);
            var css = GpsSignalMappingModalLayoutTest.class.getResource("/css/stellar-desktop.css");
            if (css != null) {
                scene.getStylesheets().add(css.toExternalForm());
            }
            root.resize(width, height);
            modal.showOnApplicationOverlay(workspace);
            root.applyCss();
            root.layout();
            ref.set(new Harness(scene, root, modal));
        });
        return ref.get();
    }

    private static void resizeHarness(Harness harness, double width, double height) {
        harness.root.resize(width, height);
        harness.root.applyCss();
        harness.root.layout();
    }

    private static void close(Harness harness) throws Exception {
        runOnFx(harness.modal::detachOverlay);
    }

    private static void writeSnapshot(Harness harness, String fileName) {
        try {
            harness.root.applyCss();
            harness.root.layout();
            WritableImage image = harness.root.snapshot(null, null);
            Path file = snapshotDir().resolve(fileName);
            Files.createDirectories(file.getParent());
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file.toFile());
        } catch (Exception ex) {
            throw new IllegalStateException("Could not write " + fileName, ex);
        }
    }

    private static Path snapshotDir() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        if ("stellar-desktop".equals(cwd.getFileName().toString())) {
            cwd = cwd.getParent();
        }
        return cwd.resolve("qa").resolve("gps-mapping-layout");
    }

    private static List<SignalCatalogEntry> catalog() {
        return List.of(
                entry("GPS_LAT_INFO", "deg", 2_000, 12.91, 13.05),
                entry("GPS_LONG_INFO", "deg", 2_000, 77.55, 77.68),
                entry("GPS_ALTITUDE", "m", 2_000, 820, 940),
                entry("SPEED_OVER_GROUND", "km/h", 2_000, 0, 92));
    }

    private static SignalCatalogEntry entry(String name, String unit, int samples, double min, double max) {
        SignalCatalogKey key = new SignalCatalogKey(
                name, SignalSourceType.DBC_CAN, "GPS_COORDINATE_22C", 556, 3, "drive.mf4", "veh.dbc");
        return new SignalCatalogEntry(key, unit, samples, 0L, samples * 1_000_000_000L, min, max, "drive.mf4", "veh.dbc");
    }

    private static void runOnFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(90, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private record Harness(Scene scene, StackPane root, GpsSignalMappingModal modal) {
    }
}
