package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.ui.WorkstationLayoutMetrics;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomDecodeActionBarLayoutTest {

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        CountDownLatch configured = new CountDownLatch(1);
        Platform.runLater(() -> {
            Platform.setImplicitExit(false);
            configured.countDown();
        });
        assertTrue(configured.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(45)
    void reviewAndProcessStaysVisibleAtLaptopWidths() throws Exception {
        runOnFx(() -> {
            DecisionActionBar bar = new DecisionActionBar();
            bar.refresh(new DecisionActionBar.ActionState(
                    "Source 1 · Channel 1",
                    "11_bit_OBD2 2.dbc",
                    "MANUAL",
                    "Confirm Selected DBC",
                    true,
                    false,
                    true,
                    true,
                    "",
                    "Ready to process",
                    0));
            for (double width : new double[] {1280, 1366, 1536, 1920}) {
                BorderPane page = new BorderPane();
            page.setBottom(bar);
            page.getStyleClass().add("detailed-mapping-mode");
            Scene scene = new Scene(page, width, 720);
                var css = DecisionActionBar.class.getResource("/css/stellar-desktop.css");
                if (css != null) {
                    scene.getStylesheets().add(css.toExternalForm());
                }
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.setWidth(width);
                stage.setHeight(720);
                stage.show();
                scene.getRoot().applyCss();
                scene.getRoot().autosize();
                bar.applyCss();
                bar.resize(width - 32, 48);
                bar.layout();
                assertFalse(bar.proceedButtonForTest().isDisable());
                assertTrue(bar.proceedButtonForTest().isVisible());
                assertTrue(bar.proceedButtonForTest().isFocusTraversable());
                bar.proceedButtonForTest().requestFocus();
                assertTrue(bar.proceedButtonForTest().isFocused(), "width=" + width);
                double rendered = bar.proceedButtonForTest().getWidth();
                assertTrue(rendered + 1 >= bar.proceedButtonForTest().prefWidth(-1), "width=" + width);
                assertTrue(rendered < WorkstationLayoutMetrics.PRIMARY_BUTTON_MIN_WIDTH,
                        "width=" + width + " rendered=" + rendered);
                double height = bar.proceedButtonForTest().getHeight();
                assertTrue(height >= 30 && height <= 36.5, "height=" + height + " width=" + width);
                Bounds bounds = bar.proceedButtonForTest().localToScene(bar.proceedButtonForTest().getBoundsInLocal());
                assertTrue(bounds.getMaxX() <= width + 1, "clipped at " + width);
                assertTrue(bar.getStyleClass().contains("workstation-action-bar"));
                stage.close();
            }
        });
    }

    @Test
    @Timeout(60)
    void actionButtonsDoNotOverlapWhenNamesAreLong() throws Exception {
        String measurement = "Vehicle_Measurement_BrakePressure_ChannelSet_2026-10-21_SessionA_FleetNorth.mf4";
        String dbc = "Powertrain_Diagnostic_Database_11_bit_OBD2_Extended_Fleet_Release.dbc";
        double outputScale = Screen.getPrimary().getOutputScaleX();
        System.out.println("CustomDecodeActionBar outputScaleX=" + outputScale);
        for (double font : new double[] {13, 16.25, 19.5}) {
            for (double width : new double[] {1920, 1536, 1366, 1280, 1100, 1024, 960}) {
                runOnFx(() -> assertActionBarSeparated(measurement, dbc, width, font));
            }
        }
    }

    private static void assertActionBarSeparated(String measurement, String dbc, double width, double font) {
        DecisionActionBar bar = new DecisionActionBar();
        bar.refresh(new DecisionActionBar.ActionState(
                "Selected: " + measurement + " / Ch1",
                dbc,
                "RECOMMENDED",
                "Confirm Recommended",
                true,
                false,
                true,
                true,
                "",
                "Ready to process",
                0));
        BorderPane page = new BorderPane(new StackPane(), null, null, bar, null);
        page.getStyleClass().add("detailed-mapping-mode");
        page.setStyle("-fx-font-size: " + font + "px;");
        Scene scene = new Scene(page, width, 720);
        var css = DecisionActionBar.class.getResource("/css/stellar-desktop.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        for (int pulse = 0; pulse < 4; pulse++) {
            page.applyCss();
            page.layout();
        }
        String where = "width=" + width + " font=" + font;
        List<Button> actions = List.of(
                bar.confirmButtonForTest(),
                bar.compareButtonForTest(),
                bar.markNeedsAttentionForTest(),
                bar.proceedButtonForTest());
        Bounds barBounds = bar.localToScene(bar.getBoundsInLocal());
        List<Bounds> boxes = new ArrayList<>();
        for (Button action : actions) {
            assertTrue(action.isVisible() && action.isManaged(), where + " " + action.getText());
            assertTrue(action.getWidth() + 1 >= action.prefWidth(-1),
                    where + " clipped " + action.getText()
                            + " width=" + action.getWidth() + " pref=" + action.prefWidth(-1));
            assertTrue(action.getHeight() >= 30 && action.getHeight() <= 36.5,
                    where + " height " + action.getText() + "=" + action.getHeight());
            Bounds box = action.localToScene(action.getBoundsInLocal());
            assertTrue(box.getMinX() >= barBounds.getMinX() - 1, where + " left " + action.getText());
            assertTrue(box.getMaxX() <= barBounds.getMaxX() + 1, where + " right " + action.getText());
            assertTrue(box.getMinY() >= barBounds.getMinY() - 1, where + " top " + action.getText());
            assertTrue(box.getMaxY() <= barBounds.getMaxY() + 1, where + " bottom " + action.getText());
            boxes.add(box);
        }
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                Bounds a = boxes.get(i);
                Bounds b = boxes.get(j);
                boolean separated = a.getMaxX() <= b.getMinX() + 1
                        || b.getMaxX() <= a.getMinX() + 1
                        || a.getMaxY() <= b.getMinY() + 1
                        || b.getMaxY() <= a.getMinY() + 1;
                assertTrue(separated, where + " overlap " + actions.get(i).getText()
                        + " and " + actions.get(j).getText());
            }
        }
        assertEqualsSelection(bar, measurement, dbc, where);
        assertTrue(bar.dbcLabelWidthForTest() >= 72, where + " dbc hidden " + bar.dbcLabelWidthForTest());
        if (width <= 1100) {
            assertTrue(bar.selectionLabelWidthForTest() + 4 < bar.selectionLabelPrefWidthForTest(),
                    where + " filename did not ellipsize width=" + bar.selectionLabelWidthForTest()
                            + " pref=" + bar.selectionLabelPrefWidthForTest());
        }
        stage.close();
    }

    private static void assertEqualsSelection(DecisionActionBar bar, String measurement, String dbc, String where) {
        assertTrue(bar.selectionTextForTest().contains(measurement), where);
        assertTrue(bar.selectionTooltipForTest().contains(measurement), where);
        assertTrue(bar.dbcTextForTest().contains(dbc), where);
        assertTrue(bar.dbcTooltipForTest().contains(dbc), where);
        assertTrue(bar.dbcTextForTest().startsWith("DBC:"), where);
        assertTrue(bar.confirmButtonForTest().getText().equals("Confirm Recommended"), where);
        assertTrue(bar.compareButtonForTest().getText().equals("Compare"), where);
        assertTrue(bar.markNeedsAttentionForTest().getText().equals("Mark for Review"), where);
        assertTrue(bar.proceedButtonForTest().getText().equals("Review & Process"), where);
    }

    @Test
    @Timeout(45)
    void autoAndCustomShareWorkstationTokens() throws Exception {
        runOnFx(() -> {
            QuickDecodePane auto = new QuickDecodePane();
            assertTrue(auto.getStyleClass().contains("workstation-page"));
            DecisionActionBar custom = new DecisionActionBar();
            assertTrue(custom.getStyleClass().contains("workstation-action-bar"));
            assertTrue(custom.proceedButtonForTest().getStyleClass().contains("workstation-primary-button"));
        });
    }

    private static void runOnFx(FxBody body) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(40, TimeUnit.SECONDS), "JavaFX task timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface FxBody {
        void run() throws Exception;
    }
}
