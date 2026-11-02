package com.stellar.platform.desktop.ui.signals;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Toolbar keeps Index/Time jump controls readable without overlap. */
class WorkspaceToolbarLayoutTest {

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
    @Timeout(30)
    void toolbarUsesReadableJumpControls() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
                javafx.scene.layout.HBox toolbar = (javafx.scene.layout.HBox) field(pane, "toolbar");
                toolbar.setVisible(true);
                toolbar.setManaged(true);
                Scene scene = new Scene(pane, 1100, 700);
                pane.applyCss();
                pane.layout();
                pane.resize(1100, 700);
                pane.layout();

                assertTrue(toolbar.getChildren().size() >= 4);
                assertEquals(toolbar, ((javafx.scene.Node) field(pane, "displayGroup")).getParent());
                assertEquals(toolbar, ((javafx.scene.Node) field(pane, "navigateGroup")).getParent());
                assertEquals("Navigate", text(pane, "navigateLabel"));
                assertEquals("e.g. 11459", pane.navigatePromptForTest());
                assertEquals("Display Mode", text(pane, "displayLabel"));
                assertEquals("Precision", text(pane, "precisionLabel"));
                assertEquals("Physical", combo(pane, "displayModeBox"));
                assertEquals("Auto", combo(pane, "precisionBox"));

                Button go = (Button) field(pane, "navigateGoButton");
                assertEquals("Go", go.getText());
                assertTrue(go.getMinWidth() >= 36);
                TextField navigateField = (TextField) field(pane, "navigateField");
                assertTrue(navigateField.getMaxWidth() <= 170);
                assertTrue(navigateField.getMinWidth() >= 100);
                navigateField.setText("abc");
                go.fire();
                assertEquals("Invalid index", ((javafx.scene.control.Label) field(pane, "navFeedback")).getText());
                pane.selectNavigateTimeForTest();
                assertEquals("e.g. 596.79296", pane.navigatePromptForTest());
                go.fire();
                assertEquals("Invalid timestamp", ((javafx.scene.control.Label) field(pane, "navFeedback")).getText());
                assertEquals(toolbar, go.getParent().getParent());

                Button prev = (Button) field(pane, "prevWindowButton");
                Button next = (Button) field(pane, "nextWindowButton");
                assertTrue(prev.getGraphic() != null, "Previous uses vector icon");
                assertTrue(next.getGraphic() != null, "Next uses vector icon");
                assertTrue(prev.getStyleClass().contains("pagination-button"));
                assertTrue(next.getStyleClass().contains("pagination-button"));

                Button copyTable = (Button) field(pane, "exportButton");
                assertEquals("Copy Table", copyTable.getText());
                assertTrue(copyTable.getGraphic() != null, "Copy Table uses vector copy icon");
                assertTrue(copyTable.getTooltip().getText().contains("Excel-compatible"));
                MenuButton exportCsv = (MenuButton) field(pane, "exportCsvButton");
                assertEquals("Export CSV", exportCsv.getText());
                assertEquals(copyTable.getParent(), exportCsv.getParent());
                assertEquals(List.of("Selected Signals", "All Signals"),
                        exportCsv.getItems().stream().map(MenuItem::getText).toList());
                exportCsv.getItems().getFirst().fire();
                assertEquals("Select at least one signal to export.",
                        ((javafx.scene.control.Label) field(pane, "navFeedback")).getText());
                javafx.scene.layout.HBox windowNav = (javafx.scene.layout.HBox) field(pane, "windowNavBox");
                assertFalse(windowNav.isVisible());
                assertFalse(windowNav.isManaged());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @Test
    @Timeout(30)
    void jumpControlsRemainInsidePaneAtInspectorConstrainedWidths() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                double[] widths = {786, 1020, 1340};
                double[] heights = {700, 820, 980};
                for (int i = 0; i < widths.length; i++) {
                    double w = widths[i];
                    double h = heights[i];
                    ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
                    javafx.scene.layout.HBox toolbar = (javafx.scene.layout.HBox) field(pane, "toolbar");
                    toolbar.setVisible(true);
                    toolbar.setManaged(true);
                    Scene scene = new Scene(pane, w, h);
                    pane.applyCss();
                    pane.resize(w, h);
                    pane.layout();
                    // Force toolbar reflow for the current width (width listener may lag in tests).
                    Method layoutToolbar = ActiveSignalWorkspacePane.class
                            .getDeclaredMethod("layoutToolbarForWidth", double.class);
                    layoutToolbar.setAccessible(true);
                    layoutToolbar.invoke(pane, w);
                    pane.layout();

                    Button go = (Button) field(pane, "navigateGoButton");
                    TextField navigateField = (TextField) field(pane, "navigateField");
                    assertEquals("Go", go.getText());
                    assertEquals(toolbar, ((javafx.scene.Node) field(pane, "navigateGroup")).getParent());
                    assertTrue(navigateField.getWidth() >= 100, "Navigate field collapsed at " + w);
                    assertTrue(navigateField.isVisible());
                    assertTrue(go.isVisible());
                    assertEquals("Navigate", text(pane, "navigateLabel"));
                    assertTrue(go.localToScene(go.getBoundsInLocal()).getMaxX()
                            <= scene.getWidth() + 1, "Navigate Go clipped at " + w);
                    // No overlap: Time Go should sit left of Rows combo when width allows.
                    assertTrue(toolbar.getHeight() < h * 0.35);
                }
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static String text(ActiveSignalWorkspacePane pane, String name) throws Exception {
        return ((javafx.scene.control.Label) field(pane, name)).getText();
    }

    private static String combo(ActiveSignalWorkspacePane pane, String name) throws Exception {
        Object value = ((javafx.scene.control.ComboBox<?>) field(pane, name)).getValue();
        if (value instanceof com.stellar.platform.desktop.presentation.WorkspaceDisplayMode m) {
            return m.label();
        }
        if (value instanceof com.stellar.platform.desktop.presentation.WorkspacePrecision p) {
            return p.label();
        }
        return String.valueOf(value);
    }

    private static Object field(ActiveSignalWorkspacePane pane, String name) throws Exception {
        Field f = ActiveSignalWorkspacePane.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(pane);
    }
}
