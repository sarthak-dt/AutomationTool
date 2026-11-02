package com.stellar.platform.desktop.ui.signals;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Signal Browser chrome: search and selection stay readable; context filters stay off the default column. */
class SignalBrowserChromeTest {

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
    @Timeout(45)
    void filterActionsAreFullyReadableAtBrowserWidths() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                for (double width : new double[] {300, 320, 380, 1280}) {
                    SignalBrowserPane pane = new SignalBrowserPane();
                    Scene scene = new Scene(pane, width, 640);
                    var css = SignalBrowserPane.class.getResource("/css/stellar-desktop.css");
                    assertNotNull(css);
                    scene.getStylesheets().add(css.toExternalForm());
                    pane.applyCss();
                    pane.resize(width, 640);
                    pane.layout();

                    ToggleButton filters = (ToggleButton) field(pane, "filtersToggle");
                    ToggleButton favorites = (ToggleButton) field(pane, "favoritesToggle");
                    ToggleButton recent = (ToggleButton) field(pane, "recentToggle");
                    Button reset = (Button) field(pane, "clearFiltersButton");
                    TextField search = (TextField) field(pane, "searchField");
                    ComboBox<?> source = (ComboBox<?>) field(pane, "sourceCombo");
                    ComboBox<?> dbc = (ComboBox<?>) field(pane, "dbcCombo");
                    ComboBox<?> type = (ComboBox<?>) field(pane, "typeCombo");

                    assertEquals("Filters", filters.getText());
                    assertEquals("Reset Filters", reset.getText());
                    assertFalse(pane.measurementChooserInDefaultColumnForTest());
                    assertFalse(pane.dbcFilterInDefaultColumnForTest());
                    assertFalse(pane.typeFilterInDefaultColumnForTest());
                    assertEquals("", pane.filtersBadgeTextForTest());

                    assertTrue(favorites.getText().contains("Favorites"), "Favorites label at " + width);
                    assertEquals("Recent", recent.getText(), "Recent label at " + width);
                    assertEquals("Recently used signals", tooltipText(recent));

                    assertEquals("Search signals...", search.getPromptText());
                    assertFalse(search.getPromptText().contains("message"));
                    assertEquals(
                            "Search by signal name, message, CAN ID or unit",
                            search.getTooltip().getText());

                    assertNotNull(source);
                    assertNotNull(dbc);
                    assertNotNull(type);

                    @SuppressWarnings("unchecked")
                    TableView<?> table = (TableView<?>) field(pane, "catalogTable");
                    table.resize(Math.max(1, width - 24), 400);
                    pane.layout();
                    assertEquals(5, table.getColumns().size(),
                            "Browser must show Favorite/Name/Source/Ch/CAN ID");
                    double colSum = 0;
                    for (TableColumn<?, ?> col : table.getColumns()) {
                        colSum += col.getWidth() > 0 ? col.getWidth() : col.getPrefWidth();
                    }
                    assertTrue(
                            colSum <= table.getWidth() + 2,
                            "Browser columns overflow table at " + width + ": " + colSum + " vs " + table.getWidth());
                    TableColumn<?, ?> sourceCol = table.getColumns().get(2);
                    assertEquals("Source", sourceCol.getText());
                    double sourceW = sourceCol.getWidth() > 0 ? sourceCol.getWidth() : sourceCol.getPrefWidth();
                    assertTrue(sourceW >= 48, "Source column too narrow at " + width);
                    TableColumn<?, ?> channel = table.getColumns().get(3);
                    assertEquals("Ch", channel.getText());
                    TableColumn<?, ?> canId = table.getColumns().get(4);
                    assertEquals("CAN ID", canId.getText());
                }
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(40, TimeUnit.SECONDS), "Signal Browser chrome layout did not finish");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static String tooltipText(ToggleButton button) {
        Tooltip tip = button.getTooltip();
        assertNotNull(tip);
        return tip.getText();
    }

    private static Object field(SignalBrowserPane pane, String name) throws Exception {
        Field f = SignalBrowserPane.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(pane);
    }
}
