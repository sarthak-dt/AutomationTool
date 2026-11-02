package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.SignalBrowserFilter;
import com.stellar.platform.desktop.presentation.SignalChannelLabels;
import com.stellar.platform.desktop.presentation.SignalsProcessingContext;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
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
 * Regression: channel provenance must not hide Signal Browser rows by default.
 */
class SignalBrowserVisibilityTest {

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
    void defaultAllViewShowsAllCatalogRowsAfterResultReplacement() throws Exception {
        int n = 130;
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                SignalsScreenModel model = new SignalsScreenModel();
                SignalBrowserPane pane = new SignalBrowserPane();
                new Scene(pane, 320, 720);
                pane.setModel(model);

                // Simulate empty -> result replacement (as after LOCAL processing).
                assertEquals(0, pane.catalogTable().getItems().size());

                InMemorySignalCatalogStore store = catalogWithChannelSignals(n, 3);
                model.replaceCatalog(store, "done", SignalsProcessingContext.of("a.mf4", "b.dbc", "Channel 3"));
                pane.refreshFromModel();

                TableView<SignalCatalogEntry> table = pane.catalogTable();
                assertEquals(n, model.catalog().size(), "catalog size");
                assertEquals(n, model.displayedCatalog().size(), "displayedCatalog after sync refresh");
                assertEquals(n, table.getItems().size(), "TableView items after refresh");
                assertEquals(n + " signals across 1 measurement", countText(pane));
                assertFalse(emptyVisible(pane), "empty overlay must be hidden when rows exist");

                assertTrue(table.getItems().stream().allMatch(e -> e.channel() == 3));
                assertTrue(table.getItems().stream().allMatch(e ->
                        "Ch 3".equals(SignalChannelLabels.compact(e))));
                assertEquals(n, table.getItems().stream()
                        .filter(e -> e.sourceType() == SignalSourceType.DBC_CAN).count());

                // DBC tab still works and does not clear the list.
                model.setBrowserFilter(new SignalBrowserFilter.State(
                        SignalBrowserFilter.SourceFilter.DBC, "", "", "", false, false));
                List<SignalCatalogEntry> dbcOnly = model.applyBrowserFilterNow();
                pane.refreshFromModel();
                assertEquals(n, dbcOnly.size());
                assertEquals(n, pane.catalogTable().getItems().size());

                // Search still works.
                model.setSearchQuery("Sig_1");
                model.setBrowserFilter(SignalBrowserFilter.State.none());
                List<SignalCatalogEntry> searched = model.applyBrowserFilterNow();
                assertTrue(searched.size() >= 1);
                assertTrue(searched.size() < n);

                // Provenance display does not alter sample counts.
                assertEquals(1L, table.getItems().getFirst().sampleCount());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @Test
    @Timeout(30)
    void nativeSignalWithoutCanChannelRemainsVisible() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                SignalsScreenModel model = new SignalsScreenModel();
                SignalBrowserPane pane = new SignalBrowserPane();
                new Scene(pane, 320, 640);
                InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("a.mf4", "b.dbc", 50);
                store.accept(List.of(
                        new CanonicalSignal(
                                "NativeSpeed",
                                InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE,
                                1L, 0L, 7, 12.0, null, "km/h", null, new SourceReference(1, 0)
                        ),
                        can("DbcSig", "Msg", 0x10, 3, 1L)
                ));
                model.replaceCatalog(store);
                pane.setModel(model);
                pane.refreshFromModel();

                assertEquals(2, pane.catalogTable().getItems().size());
                SignalCatalogEntry nativeEntry = pane.catalogTable().getItems().stream()
                        .filter(e -> e.sourceType() == SignalSourceType.MF4_NATIVE)
                        .findFirst()
                        .orElseThrow();
                assertEquals("\u2014", SignalChannelLabels.compact(nativeEntry));
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
    void staleAsyncFilterCannotClearRowsAfterCatalogReplacement() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                SignalsScreenModel model = new SignalsScreenModel();
                // Prime an empty async filter result path.
                model.setBrowserFilter(new SignalBrowserFilter.State(
                        SignalBrowserFilter.SourceFilter.ALL, "", "", "", true, false));
                model.searchAsync(ignored -> { });

                InMemorySignalCatalogStore store = catalogWithChannelSignals(40, 3);
                model.replaceCatalog(store);
                model.setBrowserFilter(SignalBrowserFilter.State.none());
                List<SignalCatalogEntry> visible = model.applyBrowserFilterNow();
                assertEquals(40, visible.size(), "default filter must not hide channel-provenanced rows");

                SignalBrowserPane pane = new SignalBrowserPane();
                new Scene(pane, 320, 640);
                pane.setModel(model);
                pane.refreshFromModel();
                assertEquals(40, pane.catalogTable().getItems().size());
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
    void recentlyUsedSectionIsNotManagedOrRendered() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                SignalsScreenModel model = new SignalsScreenModel();
                SignalBrowserPane pane = new SignalBrowserPane();
                new Scene(pane, 320, 720);
                InMemorySignalCatalogStore store = catalogWithChannelSignals(8, 1);
                model.replaceCatalog(store);
                pane.setModel(model);
                pane.refreshFromModel();
                model.touchRecent(pane.catalogTable().getItems().getFirst().key());
                pane.refreshFromModel();

                VBox recent = pane.recentSectionForTest();
                assertFalse(recent.isVisible());
                assertFalse(recent.isManaged());
                assertFalse(pane.getChildren().contains(recent),
                        "Recently Used must be removed from the Signal Browser layout");
                assertTrue(model.recentSignals().size() >= 1,
                        "recent catalog data must remain available even when the section is gone");
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
    void mainSignalBrowserTableReceivesVerticalGrow() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                SignalBrowserPane pane = new SignalBrowserPane();
                new Scene(pane, 320, 720);
                pane.applyCss();
                pane.resize(320, 720);
                pane.layout();
                assertTrue(pane.tableHostGrowsForTest());
                assertEquals(Priority.ALWAYS, VBox.getVgrow(pane.catalogTable().getParent()));
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

    private static InMemorySignalCatalogStore catalogWithChannelSignals(int count, int channel) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("a.mf4", "b.dbc", 2_000);
        List<CanonicalSignal> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            batch.add(can("Sig_" + i, "Msg", 0x100 + i, channel, i + 1L));
        }
        store.accept(batch);
        return store;
    }

    private static CanonicalSignal can(String name, String message, long canId, int channel, long ts) {
        return new CanonicalSignal(
                name, message, ts, canId, channel, 1.0, 1L, "u", null, new SourceReference(1, ts));
    }

    private static String countText(SignalBrowserPane pane) throws Exception {
        return ((Label) field(pane, "countLabel")).getText();
    }

    private static boolean emptyVisible(SignalBrowserPane pane) throws Exception {
        return ((VBox) field(pane, "emptyState")).isVisible();
    }

    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}