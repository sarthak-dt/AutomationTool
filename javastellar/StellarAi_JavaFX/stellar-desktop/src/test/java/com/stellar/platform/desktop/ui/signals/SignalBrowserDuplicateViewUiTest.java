package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.SignalBrowserViewMode;
import com.stellar.platform.desktop.presentation.SignalsProcessingContext;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.processing.catalog.CompositeSignalCatalogStore;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
import javafx.scene.control.ToggleButton;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalBrowserDuplicateViewUiTest {

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        Platform.setImplicitExit(false);
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(30)
    void physicalRowsAreDefaultAndHideDuplicatesIsExplicitViewOnly() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            SignalsScreenModel model = null;
            try {
                model = new SignalsScreenModel();
                SignalBrowserPane pane = new SignalBrowserPane();
                new Scene(pane, 320, 720);
                pane.setModel(model);

                InMemorySignalCatalogStore ch1 = store(1, 41.0);
                InMemorySignalCatalogStore ch2 = store(2, 42.0);
                CompositeSignalCatalogStore composite = CompositeSignalCatalogStore.fromMembers(List.of(
                        new CompositeSignalCatalogStore.Member(ch1, "src-A", "def-A", "run.mf4", "veh.dbc"),
                        new CompositeSignalCatalogStore.Member(ch2, "src-A", "def-A", "run.mf4", "veh.dbc")
                ), "run.mf4", "veh.dbc");
                model.replaceCatalog(composite, "Quick", SignalsProcessingContext.quickDecode("run.mf4", "veh.dbc", "2 Channels"));
                pane.refreshFromModel();

                @SuppressWarnings("unchecked")
                TableView<SignalCatalogEntry> table = (TableView<SignalCatalogEntry>) field(pane, "catalogTable");
                ToggleButton hide = (ToggleButton) field(pane, "hideDuplicatesToggle");
                assertFalse(hide.isSelected());
                assertEquals(SignalBrowserViewMode.ALL_INSTANCES, model.browserViewMode());
                assertEquals(2, table.getItems().size());
                assertEquals(List.of("Ch1", "Ch2"), table.getItems().stream().map(e -> "Ch" + e.channel()).toList());
                assertEquals(5, table.getColumns().size());
                assertEquals("CAN ID", table.getColumns().get(4).getText());

                hide.fire();
                // fire() schedules model filtering through the normal path; force the same projection
                // synchronously for deterministic UI-test verification.
                pane.refreshFromModel();
                assertTrue(hide.isSelected());
                assertEquals(SignalBrowserViewMode.UNIQUE_SIGNAL_NAMES, model.browserViewMode());
                assertEquals(1, table.getItems().size());
                assertEquals(1, table.getItems().getFirst().channel());
                assertEquals(2, model.catalog().size(), "hidden duplicate remains physically present");

                hide.fire();
                pane.refreshFromModel();
                assertEquals(2, table.getItems().size());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                if (model != null) {
                    model.shutdown();
                }
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static InMemorySignalCatalogStore store(int channel, double value) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 50);
        store.accept(List.of(new CanonicalSignal(
                "KILOMETRAGE", "VehicleState", channel, 0x552, channel, value,
                null, "km", null, SourceReference.ofLine(1))));
        return store;
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
