package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.MeasurementCatalogScopeTest;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Signal Browser action controls stay compact so the catalog keeps the panel. */
class SignalBrowserCompactLayoutTest {

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
    void compactControlsLeaveRoomForTheSignalList() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            Stage stage = new Stage();
            try {
                SignalBrowserPane pane = new SignalBrowserPane();
                SignalsScreenModel model = new SignalsScreenModel();
                model.replaceCatalog(MeasurementCatalogScopeTest.fourFileStore());
                pane.setModel(model);
                pane.refreshFromModel();

                Scene scene = new Scene(pane, 310, 640);
                var css = SignalBrowserPane.class.getResource("/css/stellar-desktop.css");
                assertNotNull(css);
                scene.getStylesheets().add(css.toExternalForm());
                stage.setScene(scene);
                stage.show();
                pane.applyCss();
                pane.resize(310, 640);
                pane.layout();

                assertFalse(pane.measurementChooserInDefaultColumnForTest());
                assertFalse(pane.dbcFilterInDefaultColumnForTest());
                assertFalse(pane.typeFilterInDefaultColumnForTest());
                assertFalse(pane.hideDuplicatesInDefaultColumnForTest());
                List<ButtonBase> controls = List.of(
                        field(pane, "selectAllButton"),
                        field(pane, "deselectAllButton"));
                List<String> labels = List.of("Select All", "Deselect All");
                for (int i = 0; i < controls.size(); i++) {
                    ButtonBase button = controls.get(i);
                    assertEquals(labels.get(i), button.getText());
                    assertFalse(button.getText().contains("..."));
                    assertTrue(button.getHeight() > 0 && button.getHeight() <= 28,
                            button.getText() + " height " + button.getHeight());
                    Bounds bounds = button.localToScene(button.getBoundsInLocal());
                    assertTrue(bounds.getWidth() + 1 >= button.getText().length() * 5.0,
                            button.getText() + " clipped");
                    assertTrue(bounds.getMaxX() <= 312, button.getText() + " overflows the panel");
                    for (int j = i + 1; j < controls.size(); j++) {
                        Bounds other = controls.get(j).localToScene(controls.get(j).getBoundsInLocal());
                        assertFalse(overlaps(bounds, other), button.getText() + " overlaps " + controls.get(j).getText());
                    }
                }

                @SuppressWarnings("unchecked")
                TableView<?> table = (TableView<?>) field(pane, "catalogTable");
                assertTrue(table.getHeight() >= 280, "signal list height " + table.getHeight());
                assertFalse(table.getItems().isEmpty());

                pane.selectAllButtonForTest().fire();
                pane.deselectAllButtonForTest().fire();
                assertEquals("Hide Duplicates", pane.hideDuplicatesTextForTest());
                ButtonBase hide = field(pane, "hideDuplicatesToggle");
                hide.fire();
                assertEquals("1", pane.filtersBadgeTextForTest());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                stage.close();
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static boolean overlaps(Bounds a, Bounds b) {
        return a.getMinX() < b.getMaxX() - 1
                && a.getMaxX() > b.getMinX() + 1
                && a.getMinY() < b.getMaxY() - 1
                && a.getMaxY() > b.getMinY() + 1;
    }
}
