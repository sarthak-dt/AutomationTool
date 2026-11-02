package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.desktop.support.JavaFxTestSupport;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphToolbarOverflowTest {

    @BeforeAll
    static void startFx() throws Exception {
        // Default implicitExit shuts the toolkit down when a test closes its last Stage.
        // The next test's runLater then never runs, and the latch times out on the test thread.
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void secondaryControlsOverflowAtSupportedLaptopWidth() {
        double primary = 700;
        double secondary = 640;
        assertTrue(GraphToolbarOverflow.shouldOverflow(1100, primary, secondary));
        assertTrue(GraphToolbarOverflow.shouldOverflow(1280, primary, secondary));
        assertTrue(GraphToolbarOverflow.shouldOverflow(1600, primary, secondary));
        assertFalse(GraphToolbarOverflow.shouldOverflow(1920, 400, 200));
        assertTrue(GraphToolbarOverflow.shouldOverflow(1920, 1400, 600));
    }

    @Test
    void openMenuStaysShowingAcrossRepeatedLayout() throws Exception {
        JavaFxTestSupport.runOnFx("overflow menu survives layout", () -> {
            Stage stage = new Stage();
            try {
                HBox secondary = new HBox(new Button("Create Investigation"));
                MenuButton more = new MenuButton("More");
                HBox toolbar = new HBox(8, secondary, more);
                toolbar.setPrefWidth(1280);
                stage.setScene(new Scene(toolbar, 1280, 240));
                stage.show();
                List<GraphToolbarOverflow.OverflowAction> actions = List.of(
                        new GraphToolbarOverflow.OverflowAction("nav", "Signals", () -> { }),
                        new GraphToolbarOverflow.OverflowAction("invest", "Create Investigation", () -> { }),
                        new GraphToolbarOverflow.OverflowAction("invest", "Clear View", () -> { }));
                GraphToolbarOverflow.apply(toolbar, secondary, more, actions);
                assertFalse(more.getItems().isEmpty());
                MenuItem first = more.getItems().get(0);
                int count = more.getItems().size();
                more.show();
                assertTrue(more.isShowing());
                for (int i = 0; i < 20; i++) {
                    GraphToolbarOverflow.apply(toolbar, secondary, more, actions);
                    assertTrue(more.isShowing(), "layout pulse " + i + " hid the menu");
                    assertEquals(count, more.getItems().size());
                    assertSame(first, more.getItems().get(0));
                }
                more.hide();
                assertFalse(more.isShowing());
                GraphToolbarOverflow.apply(toolbar, secondary, more, actions);
                assertEquals(count, more.getItems().size());
                more.show();
                assertTrue(more.isShowing());
                assertEquals(1, more.getItems().stream().filter(item -> item == first).count());
                more.hide();
            } finally {
                stage.close();
            }
        });
    }

    @Test
    void selectingAnItemRunsOnceAndCloses() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        MenuButton[] menu = new MenuButton[1];
        Stage[] stage = new Stage[1];
        JavaFxTestSupport.runOnFx("prepare overflow menu", () -> {
            stage[0] = new Stage();
            menu[0] = new MenuButton("More");
            HBox toolbar = new HBox(menu[0]);
            toolbar.setPrefWidth(1100);
            stage[0].setScene(new Scene(toolbar, 1100, 200));
            stage[0].show();
            GraphToolbarOverflow.apply(toolbar, null, menu[0], List.of(
                    new GraphToolbarOverflow.OverflowAction("view", "Clear View", runs::incrementAndGet)));
        });
        try {
            // Show and fire on separate pulses. Hiding the popup inside the same
            // native WinWindow.setVisible call can block the JavaFX thread.
            selectAndClose(menu[0], runs, 1);
            selectAndClose(menu[0], runs, 2);
        } finally {
            JavaFxTestSupport.runOnFx("close overflow stage", () -> stage[0].close());
        }
    }

    private static void selectAndClose(MenuButton more, AtomicInteger runs, int expected) throws Exception {
        JavaFxTestSupport.runOnFx("open more menu " + expected, () -> {
            assertFalse(more.isShowing());
            more.show();
            assertTrue(more.isShowing(), "More menu did not open");
        });
        JavaFxTestSupport.runOnFx("select more item " + expected, () -> {
            assertTrue(more.isShowing(), "More menu closed before selection");
            more.getItems().get(0).fire();
            assertEquals(expected, runs.get(), "More action must run once per selection");
            assertFalse(more.isShowing(), "More menu stayed open after selection");
        });
    }
}
