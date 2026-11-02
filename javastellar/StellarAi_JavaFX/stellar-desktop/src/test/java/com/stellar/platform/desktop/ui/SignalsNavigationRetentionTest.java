package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.SignalBrowserFilter;
import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.desktop.presentation.WorkspaceDisplayMode;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.desktop.ui.signals.SignalsLayoutMetrics;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Signals → Graphs → Signals must keep the current table page without another sample query.
 */
class SignalsNavigationRetentionTest {

    @TempDir Path temp;

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
    @Timeout(180)
    void graphsRoundTripKeepsTheTableAndDoesNotReload() throws Exception {
        Harness harness = open();
        try {
            fx(() -> prepare(harness, "run.mf4", "VehicleSpeed", 12));
            awaitSettled(harness);
            fx(() -> nextPage(harness));
            awaitWindow(harness, 5);

            int[] before = snapshot(harness);
            String search = fxValue(() -> harness.signals().browserPaneForTest().searchField().getText());
            String measurement = fxValue(() -> harness.service.signalsModel().browserFilter().sourceFile());
            int window = fxValueInt(() -> harness.service.signalsModel().alignedWindowStart());
            long firstIndex = fxValueLong(() -> harness.signals().workspacePaneForTest().table().getItems().getFirst().index());

            fx(() -> {
                for (int i = 0; i < 20; i++) {
                    harness.shell.show(NavigationPage.GRAPHS);
                    harness.shell.show(NavigationPage.SIGNALS);
                }
            });

            int[] after = snapshot(harness);
            assertEquals(before[0], after[0], "sample queries");
            assertEquals(before[1], after[1], "catalog replacements");
            assertTrue(after[2] > 0, "table was cleared");
            assertEquals(before[2], after[2], "row count");
            assertEquals(before[3], after[3], "favorites listeners grew");
            assertEquals(before[4], after[4], "recents listeners grew");
            assertEquals("Speed", search);
            assertEquals("Speed", fxValue(() -> harness.signals().browserPaneForTest().searchField().getText()));
            assertEquals("run.mf4", measurement);
            assertEquals("run.mf4", fxValue(() -> harness.service.signalsModel().browserFilter().sourceFile()));
            assertEquals(window, fxValueInt(() -> harness.service.signalsModel().alignedWindowStart()));
            assertEquals(firstIndex, fxValueLong(() ->
                    harness.signals().workspacePaneForTest().table().getItems().getFirst().index()));
            assertEquals(WorkspaceDisplayMode.RAW.name(), fxValue(() ->
                    harness.signals().workspacePaneForTest().displayModeForTest().name()));
            assertEquals(5, fxValueInt(() -> harness.signals().workspacePaneForTest().rowsValueForTest()));
            fx(() -> assertColumnsReadable(harness));
        } finally {
            close(harness);
        }
    }

    @Test
    @Timeout(60)
    void projectChangeAndSourceRemovalReloadOnlyTheNewCatalog() throws Exception {
        Harness harness = open();
        try {
            fx(() -> prepare(harness, "run.mf4", "VehicleSpeed", 4));
            awaitSettled(harness);
            int queries = fxValueInt(() -> harness.service.signalsModel().workspaceAlignedQueryCountForTest());
            int catalogs = fxValueInt(() -> harness.service.signalsModel().catalogReplaceCountForTest());

            fx(() -> {
                InMemorySignalCatalogStore replacement = store("other.mf4", "CoolantTemp", 3);
                harness.service.signalsModel().replaceCatalog(replacement);
                harness.service.signalsModel().addToWorkspace(
                        List.of(harness.service.signalsModel().catalog().entries().getFirst().key()));
                harness.signals().refresh();
            });
            awaitSettled(harness);
            int afterProject = fxValueInt(() -> harness.service.signalsModel().workspaceAlignedQueryCountForTest());
            assertTrue(afterProject > queries, "a new catalog must load samples");
            assertTrue(fxValueInt(() -> harness.service.signalsModel().catalogReplaceCountForTest()) > catalogs);
            fx(() -> {
                for (TableColumn<?, ?> column : harness.signals().workspacePaneForTest().table().getColumns()) {
                    assertFalse(String.valueOf(column.getUserData()).contains("VehicleSpeed"));
                }
            });

            int beforeRemoval = fxValueInt(() -> harness.service.signalsModel().workspaceAlignedQueryCountForTest());
            fx(() -> {
                SignalsScreenModel model = harness.service.signalsModel();
                SignalCatalogKey kept = model.workspaceKeys().getFirst();
                InMemorySignalCatalogStore reduced = store("other.mf4", "CoolantTemp", 2);
                model.replaceCatalog(reduced, null, null, true);
                assertFalse(model.workspaceKeys().isEmpty());
                assertEquals(kept.signalName(), model.workspaceKeys().getFirst().signalName());
                harness.signals().refresh();
            });
            awaitSettled(harness);
            assertTrue(fxValueInt(() -> harness.service.signalsModel().workspaceAlignedQueryCountForTest()) > beforeRemoval);

            fx(() -> {
                harness.service.signalsModel().clear();
                assertTrue(harness.service.signalsModel().reusableWorkspaceSnapshot().isEmpty());
                InMemorySignalCatalogStore isolated = store("third.mf4", "OilPressure", 2);
                harness.service.signalsModel().replaceCatalog(isolated);
                harness.service.signalsModel().addToWorkspace(
                        List.of(harness.service.signalsModel().catalog().entries().getFirst().key()));
                harness.signals().refresh();
            });
            awaitSettled(harness);
            fx(() -> {
                String header = String.valueOf(harness.signals().workspacePaneForTest().table().getColumns());
                assertTrue(header.contains("OilPressure") || harness.service.signalsModel().workspaceKeys().getFirst()
                        .signalName().equals("OilPressure"));
                assertFalse(harness.service.signalsModel().workspaceKeys().stream()
                        .anyMatch(key -> key.signalName().equals("CoolantTemp")));
            });
        } finally {
            close(harness);
        }
    }

    private static void prepare(Harness harness, String file, String signal, int rows) {
        harness.service.signalsModel().replaceCatalog(store(file, signal, rows));
        harness.service.signalsModel().setSamplePageSize(5);
        harness.service.signalsModel().setSearchQuery("Speed");
        harness.service.signalsModel().setBrowserFilter(new SignalBrowserFilter.State(
                SignalBrowserFilter.SourceFilter.ALL, "", "", "", false, false, file, ""));
        harness.service.signalsModel().setDisplayMode(WorkspaceDisplayMode.RAW);
        harness.service.signalsModel().addToWorkspace(
                List.of(harness.service.signalsModel().catalog().entries().getFirst().key()));
        harness.shell.show(NavigationPage.SIGNALS);
    }

    private static InMemorySignalCatalogStore store(String file, String signal, int rows) {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore(file, "veh.dbc", 1_000);
        List<CanonicalSignal> samples = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            samples.add(new CanonicalSignal(
                    signal, "Speed", (i + 1) * 1_000_000_000L, 0x100, 1,
                    10.0 + i, 10L + i, "km/h", null, SourceReference.ofLine(1)));
        }
        store.accept(samples);
        return store;
    }

    private static void nextPage(Harness harness) {
        ActiveSignalWorkspacePane workspace = harness.signals().workspacePaneForTest();
        Button next = workspace.nextWindowButtonForTest();
        assertFalse(next.isDisabled(), "next disabled at " + workspace.pageLabelForTest()
                + " rows " + workspace.table().getItems().size());
        next.fire();
    }

    private static void assertColumnsReadable(Harness harness) {
        boolean signalColumn = false;
        for (TableColumn<?, ?> column : harness.signals().workspacePaneForTest().table().getColumns()) {
            if (column.getUserData() instanceof SignalCatalogKey) {
                signalColumn = true;
                assertTrue(column.getMinWidth() >= SignalsLayoutMetrics.SIGNAL_COL_MIN - 0.1);
            }
        }
        assertTrue(signalColumn);
        assertEquals(TableViewPolicy.UNCONSTRAINED,
                harness.signals().workspacePaneForTest().table().getColumnResizePolicy() == javafx.scene.control.TableView.UNCONSTRAINED_RESIZE_POLICY
                        ? TableViewPolicy.UNCONSTRAINED : TableViewPolicy.OTHER);
    }

    private enum TableViewPolicy { UNCONSTRAINED, OTHER }

    private int[] snapshot(Harness harness) throws Exception {
        int[] values = new int[5];
        fx(() -> {
            SignalsScreenModel model = harness.service.signalsModel();
            values[0] = model.workspaceAlignedQueryCountForTest();
            values[1] = model.catalogReplaceCountForTest();
            values[2] = harness.signals().workspacePaneForTest().table().getItems().size();
            values[3] = model.favoritesListenerCountForTest();
            values[4] = model.recentsListenerCountForTest();
        });
        return values;
    }

    private void awaitSettled(Harness harness) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        int lastQueries = -1;
        int stable = 0;
        while (System.nanoTime() < deadline) {
            int[] now = snapshot(harness);
            if (now[2] > 0 && !fxFlag(() -> harness.service.signalsModel().isWorkspaceLoadBusy()) && now[0] == lastQueries) {
                stable++;
                if (stable >= 2) {
                    return;
                }
            } else {
                stable = 0;
            }
            lastQueries = now[0];
            Thread.sleep(40);
        }
        throw new AssertionError("workspace table did not settle");
    }

    private void awaitWindow(Harness harness, int start) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (fxValueInt(() -> harness.service.signalsModel().alignedWindowStart()) == start
                    && !fxFlag(() -> harness.service.signalsModel().isWorkspaceLoadBusy())) {
                Thread.sleep(60);
                if (fxValueInt(() -> harness.service.signalsModel().alignedWindowStart()) == start) {
                    return;
                }
            }
            Thread.sleep(40);
        }
        throw new AssertionError("window did not reach " + start);
    }

    private Harness open() throws Exception {
        AtomicReference<Harness> ref = new AtomicReference<>();
        fx(() -> {
            try {
                Path home = temp.resolve("signals-nav");
                Path db = temp.resolve("signals-nav.db");
                System.setProperty("stellarai.home", home.toAbsolutePath().toString());
                System.setProperty("stellarai.metadata.db", db.toAbsolutePath().toString());
                DesktopAppService service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                loader.load();
                MainShellController shell = loader.getController();
                shell.init(service);
                ref.set(new Harness(service, shell));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        return ref.get();
    }

    private void close(Harness harness) throws Exception {
        if (harness == null) {
            return;
        }
        fx(() -> {
            harness.shell.dispose();
            harness.service.shutdown();
        });
        System.clearProperty("stellarai.home");
        System.clearProperty("stellarai.metadata.db");
    }

    private void fx(Runnable action) throws Exception {
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
        assertTrue(done.await(180, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private String fxValue(java.util.function.Supplier<String> supplier) throws Exception {
        AtomicReference<String> value = new AtomicReference<>();
        fx(() -> value.set(supplier.get()));
        return value.get();
    }

    private int fxValueInt(java.util.function.IntSupplier supplier) throws Exception {
        AtomicInteger value = new AtomicInteger();
        fx(() -> value.set(supplier.getAsInt()));
        return value.get();
    }

    private long fxValueLong(java.util.function.LongSupplier supplier) throws Exception {
        AtomicReference<Long> value = new AtomicReference<>();
        fx(() -> value.set(supplier.getAsLong()));
        return value.get();
    }

    private boolean fxFlag(java.util.function.BooleanSupplier supplier) throws Exception {
        AtomicReference<Boolean> value = new AtomicReference<>();
        fx(() -> value.set(supplier.getAsBoolean()));
        return Boolean.TRUE.equals(value.get());
    }

    private record Harness(DesktopAppService service, MainShellController shell) {
        SignalsController signals() {
            return (SignalsController) shell.controllerForTest(NavigationPage.SIGNALS);
        }
    }
}
