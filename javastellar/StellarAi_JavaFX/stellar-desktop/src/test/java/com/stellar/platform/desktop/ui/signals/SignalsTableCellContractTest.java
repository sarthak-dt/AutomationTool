package com.stellar.platform.desktop.ui.signals;

import javafx.application.Platform;
import javafx.scene.control.TableCell;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalsTableCellContractTest {

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
    @Timeout(20)
    void favoriteAndNameCellsClearPreviousItemOnEmpty() throws Exception {
        onFx(() -> {
            SignalBrowserPane pane = new SignalBrowserPane();
            TableCell<?, Boolean> favorite = pane.favoriteCellForTest();
            invokeUpdateItem(favorite, Boolean.TRUE, false);
            invokeUpdateItem(favorite, null, true);
            assertNull(favorite.getText());
            assertNull(favorite.getGraphic());
            assertNull(favorite.getTooltip());

            TableCell<?, String> name = pane.nameCellForTest();
            invokeUpdateItem(name, "EngineSpeed", false);
            invokeUpdateItem(name, null, true);
            assertNull(name.getText());
            assertNull(name.getGraphic());
            assertNull(name.getTooltip());
            pane.dispose();
        });
    }

    @Test
    @Timeout(20)
    void browserDisposeRemovesModelListeners() throws Exception {
        onFx(() -> {
            com.stellar.platform.desktop.presentation.SignalsScreenModel model =
                    new com.stellar.platform.desktop.presentation.SignalsScreenModel();
            SignalBrowserPane pane = new SignalBrowserPane();
            pane.setModel(model);
            assertTrue(model.favoritesListenerCountForTest() >= 1);
            assertTrue(model.recentsListenerCountForTest() >= 1);
            pane.dispose();
            assertTrue(pane.disposedForTest());
            assertEquals(0, model.favoritesListenerCountForTest());
            assertEquals(0, model.recentsListenerCountForTest());
        });
    }

    private static void invokeUpdateItem(TableCell<?, ?> cell, Object item, boolean empty) {
        try {
            Method method = javafx.scene.control.Cell.class.getDeclaredMethod("updateItem", Object.class, boolean.class);
            method.setAccessible(true);
            method.invoke(cell, item, empty);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    private static void onFx(Runnable action) throws Exception {
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
        assertTrue(done.await(15, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static void assertEquals(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
