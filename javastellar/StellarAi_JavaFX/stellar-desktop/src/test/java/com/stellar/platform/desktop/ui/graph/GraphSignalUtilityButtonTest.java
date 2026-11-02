package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.GraphController;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Bounds;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Signals to Plot keeps selection, visibility, and order in one compact row. */
class GraphSignalUtilityButtonTest {

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void utilityMenusStayCompactAtDemoResolutions() throws Exception {
        assertAt("1280x720", 1280, 720);
        assertAt("1366x768", 1366, 768);
    }

    private static void assertAt(String label, double width, double height) throws Exception {
        JavaFxTestSupport.runOnFx(label, () -> {
            Stage stage = new Stage();
            try {
                FXMLLoader loader = new FXMLLoader(GraphController.class.getResource("/fxml/graph-view.fxml"));
                Parent root = loader.load();
                Scene scene = new Scene(root, width, height);
                var css = GraphController.class.getResource("/css/stellar-desktop.css");
                scene.getStylesheets().add(css.toExternalForm());
                stage.setScene(scene);
                stage.show();
                root.applyCss();
                root.resize(width, height);
                root.layout();

                MenuButton select = (MenuButton) loader.getNamespace().get("selectionActionsButton");
                MenuButton visibility = (MenuButton) loader.getNamespace().get("visibilityActionsButton");
                MenuButton order = (MenuButton) loader.getNamespace().get("orderActionsButton");
                ListView<?> list = (ListView<?>) loader.getNamespace().get("signalList");

                assertMenu(label, select, "Select", List.of("Select All", "Deselect All", "Clear All"));
                assertMenu(label, visibility, "Show", List.of("Show All", "Hide All"));
                assertMenu(label, order, "Order", List.of("Move Up", "Move Down"));
                assertFalse(panelHasButton(root, "Move Up"), label + " Move Up still occupies a row");
                assertFalse(panelHasButton(root, "Show All"), label + " Show All still occupies a row");

                Bounds selectBounds = select.localToScene(select.getBoundsInLocal());
                Bounds visibilityBounds = visibility.localToScene(visibility.getBoundsInLocal());
                Bounds listBounds = list.localToScene(list.getBoundsInLocal());
                assertTrue(Math.abs(selectBounds.getMinY() - visibilityBounds.getMinY()) < 4,
                        label + " selection and visibility are not on one row");
                assertTrue(listBounds.getMinY() > selectBounds.getMaxY(), label + " list is not below the action row");
                assertTrue(listBounds.getHeight() >= 280, label + " signal list height " + listBounds.getHeight());
            } finally {
                stage.close();
            }
        });
    }

    private static void assertMenu(String label, MenuButton menu, String text, List<String> items) {
        assertEquals(text, menu.getText());
        assertTrue(menu.getHeight() >= 22 && menu.getHeight() <= 32, label + " " + text + " height " + menu.getHeight());
        assertFalse(menu.getText().contains("..."), label + " " + text + " abbreviated");
        assertTrue(menu.getWidth() >= 48, label + " " + text + " width " + menu.getWidth());
        List<String> actual = menu.getItems().stream()
                .map(MenuItem::getText)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        assertEquals(items, actual);
    }

    private static boolean panelHasButton(Parent root, String text) {
        return root.lookupAll(".graph-signal-panel .button").stream().anyMatch(node ->
                node instanceof Button button && text.equals(button.getText()) && button.isManaged());
    }
}
