package com.stellar.platform.desktop.ui.signals;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.OverrunStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Measurement selector keeps a single arrow. Sibling filter combos must not paint over it.
 */
class MeasurementSelectorArrowTest {

    private static final String LONG_MF4 =
            "MF4_KA04NC7760_Bengaluru_0002360_20230814_164651_processed_measurement_file.mf4";

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(40)
    void measurementDropdownHasOneArrowAndKeepsSelection() throws Exception {
        onBrowser(1280, 720, 280, (pane, combo) -> {
            assertFalse(pane.measurementChooserInDefaultColumnForTest());
            assertOneArrowInside(pane, combo, "1280x720");
            combo.getSelectionModel().select("other.mf4");
            assertEquals("other.mf4", combo.getValue());
            combo.getSelectionModel().select(LONG_MF4);
            assertEquals(LONG_MF4, combo.getValue());
            assertEquals(OverrunStyle.ELLIPSIS, combo.getButtonCell().getTextOverrun());
            assertTrue(combo.getWidth() > 40, "chooser width " + combo.getWidth());
            assertTrue(combo.getButtonCell().getWidth() + 8 < combo.getWidth(),
                    "filename cell was not ellipsized: " + combo.getButtonCell().getWidth()
                            + " combo " + combo.getWidth());
        });
    }

    @Test
    @Timeout(40)
    void noArrowOverlapAtLaptopWidths() throws Exception {
        onBrowser(1366, 768, 280, (pane, combo) -> assertOneArrowInside(pane, combo, "1366x768"));
        onBrowser(1536, 864, 310, (pane, combo) -> assertOneArrowInside(pane, combo, "1536x864"));
        onBrowser(1280, 720, 240, (pane, combo) -> assertOneArrowInside(pane, combo, "compact-240"));
    }

    @Test
    @Timeout(40)
    void dropdownStillOpens() throws Exception {
        onBrowser(1366, 768, 280, (pane, combo) -> {
            assertFalse(combo.isDisabled());
            assertFalse(combo.getItems().isEmpty());
            assertOneArrowInside(pane, combo, "dropdown");
            combo.getSelectionModel().select(LONG_MF4);
            assertEquals(LONG_MF4, combo.getValue());
        });
    }

    private static void assertOneArrowInside(SignalBrowserPane pane, ComboBox<?> combo, String where) {
        List<Node> own = visibleArrowButtons(combo);
        assertEquals(1, own.size(), where + " arrow buttons: " + own.size());
        Bounds comboBounds = sceneBounds(combo);
        Bounds arrowBounds = sceneBounds(own.getFirst());
        assertTrue(contains(comboBounds, arrowBounds),
                where + " arrow left the measurement control " + arrowBounds + " vs " + comboBounds);
        for (Node node : pane.lookupAll(".arrow-button")) {
            if (!node.isVisible() || !node.isManaged() || own.contains(node)) {
                continue;
            }
            if (node.getBoundsInLocal().getWidth() < 1) {
                continue;
            }
            assertFalse(sceneBounds(node).intersects(comboBounds),
                    where + " another arrow overlaps the measurement selector");
        }
    }

    private static List<Node> visibleArrowButtons(ComboBox<?> combo) {
        List<Node> arrows = new ArrayList<>();
        for (Node node : combo.lookupAll(".arrow-button")) {
            if (node.isVisible() && node.isManaged() && node.getBoundsInLocal().getWidth() > 1) {
                arrows.add(node);
            }
        }
        return arrows;
    }

    private static boolean contains(Bounds outer, Bounds inner) {
        return inner.getMinX() >= outer.getMinX() - 1
                && inner.getMaxX() <= outer.getMaxX() + 1
                && inner.getMinY() >= outer.getMinY() - 1
                && inner.getMaxY() <= outer.getMaxY() + 1;
    }

    private static Bounds sceneBounds(Node node) {
        return node.localToScene(node.getBoundsInLocal());
    }

    private static SignalBrowserPane sizedPane(double sceneWidth, double sceneHeight, double browserWidth) {
        SignalBrowserPane pane = new SignalBrowserPane();
        Scene scene = new Scene(pane, sceneWidth, sceneHeight);
        var css = SignalBrowserPane.class.getResource("/css/stellar-desktop.css");
        assertTrue(css != null);
        scene.getStylesheets().add(css.toExternalForm());
        pane.setPrefWidth(browserWidth);
        pane.setMinWidth(browserWidth);
        pane.setMaxWidth(browserWidth);
        pane.resize(browserWidth, sceneHeight);
        pane.applyCss();
        pane.layout();
        ComboBox<String> combo = sourceCombo(pane);
        combo.getItems().setAll("All Measurements (2)", LONG_MF4, "other.mf4");
        combo.getSelectionModel().select(LONG_MF4);
        pane.applyCss();
        pane.layout();
        return pane;
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<String> sourceCombo(SignalBrowserPane pane) {
        try {
            Field field = SignalBrowserPane.class.getDeclaredField("sourceCombo");
            field.setAccessible(true);
            return (ComboBox<String>) field.get(pane);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void onBrowser(double sceneWidth, double sceneHeight, double browserWidth, Check check)
            throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                SignalBrowserPane pane = sizedPane(sceneWidth, sceneHeight, browserWidth);
                assertFalse(pane.measurementChooserInDefaultColumnForTest());
                VBox chooser = pane.measurementChooserRootForTest();
                StackPane host = new StackPane(chooser);
                Scene scene = new Scene(host, 320, 120);
                scene.getStylesheets().setAll(pane.getScene().getStylesheets());
                host.applyCss();
                host.resize(320, 120);
                host.layout();
                check.check(pane, sourceCombo(pane));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS), "layout did not finish");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface Check {
        void check(SignalBrowserPane pane, ComboBox<String> combo);
    }
}
