package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.AlignedSampleRow;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.SignalsSelectionPresentation;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Active Signal Workspace selection / alignment wiring.
 * Catalog selection alone does not populate the workspace; add-to-workspace does.
 */
class SignalsControllerSelectionTest {

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
    void addingSignalsToWorkspaceBuildsTimestampAlignedRows() throws Exception {
        AtomicReference<SignalsController> controllerRef = new AtomicReference<>();
        AtomicReference<DesktopAppService> serviceRef = new AtomicReference<>();

        runOnFx(() -> {
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("demo.mf4", "demo.dbc", 1_000);
            store.accept(List.of(
                    canSignal("SIG_A", "MSG", 0x100, 1_000_000_000L, 10.0, 1L),
                    canSignal("SIG_A", "MSG", 0x100, 2_000_000_000L, 20.0, 2L),
                    canSignal("SIG_A", "MSG", 0x100, 3_000_000_000L, 30.0, 3L),
                    canSignal("SIG_B", "MSG", 0x200, 2_000_000_000L, 40.0, 4L),
                    canSignal("SIG_B", "MSG", 0x200, 4_000_000_000L, 0.0, 5L)
            ));
            service.signalsModel().replaceCatalog(store);

            FXMLLoader loader = new FXMLLoader(SignalsController.class.getResource("/fxml/signals-view.fxml"));
            loader.load();
            SignalsController controller = loader.getController();
            controller.setAppService(service, null);
            controller.refresh();
            controllerRef.set(controller);
            serviceRef.set(service);
        });

        SignalsController controller = controllerRef.get();
        assertEquals("Active Signal Workspace (0 signals)", fxGet(() -> title(controller).getText()));
        assertTrue(fxGet(() -> samples(controller).getItems().isEmpty()));

        runOnFx(() -> {
            TableView<SignalCatalogEntry> catalog = catalog(controller);
            workspace(controller).addEntries(List.of(find(catalog.getItems(), "SIG_A")));
        });
        awaitFx(() -> "Active Signal Workspace (1 signal)".equals(title(controller).getText())
                && samples(controller).getItems().size() == 3);

        assertEquals("1 signal", fxGet(() -> workspace(controller).statusSelectedLabelForTest().getText()));
        assertTrue(fxGet(() -> workspace(controller).tableColumnCountForTest() >= 2),
                "Timestamp + signal column required for a workspace member");
        assertEquals(3, fxGet(() -> samples(controller).getItems().size()));
        assertEquals(10.0, fxGet(() -> samples(controller).getItems().get(0).physicalAt(0)), 1e-9);
        assertEquals(30.0, fxGet(() -> samples(controller).getItems().get(2).physicalAt(0)), 1e-9);

        runOnFx(() -> {
            TableView<SignalCatalogEntry> catalog = catalog(controller);
            workspace(controller).addEntries(List.of(find(catalog.getItems(), "SIG_B")));
        });
        awaitFx(() -> "Active Signal Workspace (2 signals)".equals(title(controller).getText())
                && samples(controller).getItems().size() == 4);

        List<AlignedSampleRow> rows = fxGet(() -> List.copyOf(samples(controller).getItems()));
        assertEquals(4, rows.size());
        AlignedSampleRow at2s = rows.stream().filter(r -> r.timestampNanos() == 2_000_000_000L).findFirst().orElseThrow();
        assertEquals(20.0, at2s.physicalAt(0), 1e-9);
        assertEquals(40.0, at2s.physicalAt(1), 1e-9);
        AlignedSampleRow at4s = rows.stream().filter(r -> r.timestampNanos() == 4_000_000_000L).findFirst().orElseThrow();
        assertNull(at4s.physicalAt(0));
        assertEquals(0.0, at4s.physicalAt(1), 1e-9);

        runOnFx(() -> workspace(controller).addEntries(List.of(find(catalog(controller).getItems(), "SIG_A"))));
        awaitFx(() -> "Active Signal Workspace (2 signals)".equals(title(controller).getText()));
        assertEquals(2, serviceRef.get().signalsModel().workspaceKeys().size());

        runOnFx(() -> {
            serviceRef.get().signalsModel().clearWorkspace();
            workspace(controller).reload();
        });
        awaitFx(() -> "Active Signal Workspace (0 signals)".equals(title(controller).getText())
                && samples(controller).getItems().isEmpty());
    }

    @Test
    @Timeout(30)
    void searchDoesNotClearWorkspaceMembership() throws Exception {
        AtomicReference<SignalsController> controllerRef = new AtomicReference<>();

        runOnFx(() -> {
            DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
            InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("demo.mf4", "demo.dbc", 1_000);
            store.accept(List.of(
                    canSignal("ALPHA", "M", 1L, 1L, 1.0, 1L),
                    canSignal("BETA", "M", 2L, 2L, 2.0, 2L)
            ));
            service.signalsModel().replaceCatalog(store);

            FXMLLoader loader = new FXMLLoader(SignalsController.class.getResource("/fxml/signals-view.fxml"));
            loader.load();
            SignalsController controller = loader.getController();
            controller.setAppService(service, null);
            controller.refresh();
            controllerRef.set(controller);
        });

        SignalsController controller = controllerRef.get();

        runOnFx(() -> workspace(controller).addEntries(List.of(find(catalog(controller).getItems(), "ALPHA"))));
        awaitFx(() -> "Active Signal Workspace (1 signal)".equals(title(controller).getText())
                && samples(controller).getItems().size() == 1);

        runOnFx(() -> {
            searchField(controller).setText("BETA");
            invokeSearch(controller);
        });
        awaitFx(() -> catalog(controller).getItems().size() == 1
                && "BETA".equals(catalog(controller).getItems().getFirst().signalName())
                && "Active Signal Workspace (1 signal)".equals(title(controller).getText())
                && samples(controller).getItems().size() == 1);
    }

    @Test
    void globalIndexHelperIsZeroBasedAndAdvances() {
        assertEquals(0L, SignalsSelectionPresentation.globalIndex(0, 0, 0));
        assertEquals(1L, SignalsSelectionPresentation.globalIndex(0, 0, 1));
        assertEquals(2L, SignalsSelectionPresentation.globalIndex(0, 0, 2));
        assertEquals(10L, SignalsSelectionPresentation.globalIndex(0, 10, 0));
    }

    private static SignalCatalogEntry find(List<SignalCatalogEntry> items, String name) {
        return items.stream().filter(e -> name.equals(e.signalName())).findFirst().orElseThrow();
    }

    private static CanonicalSignal canSignal(
            String name,
            String message,
            long canId,
            long ts,
            double phys,
            long raw
    ) {
        return new CanonicalSignal(
                name,
                message,
                ts,
                canId,
                0,
                phys,
                raw,
                "u",
                null,
                SourceReference.ofLine(1)
        );
    }

    private static void invokeSearch(SignalsController controller) {
        try {
            Method m = SignalsController.class.getDeclaredMethod("onSearch");
            m.setAccessible(true);
            m.invoke(controller);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static TableView<SignalCatalogEntry> catalog(SignalsController controller) {
        return (TableView<SignalCatalogEntry>) field(controller, "catalogTable");
    }

    @SuppressWarnings("unchecked")
    private static TableView<AlignedSampleRow> samples(SignalsController controller) {
        return (TableView<AlignedSampleRow>) field(controller, "sampleTable");
    }

    private static ActiveSignalWorkspacePane workspace(SignalsController controller) {
        return (ActiveSignalWorkspacePane) field(controller, "workspacePane");
    }

    private static Label title(SignalsController controller) {
        return (Label) field(controller, "selectedTitleLabel");
    }

    private static TextField searchField(SignalsController controller) {
        return (TextField) field(controller, "searchField");
    }

    private static Object field(SignalsController controller, String name) {
        try {
            Field f = SignalsController.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(controller);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void runOnFx(ThrowingRunnable action) throws Exception {
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
        assertTrue(done.await(15, TimeUnit.SECONDS), "FX action timed out");
        if (error.get() != null) {
            if (error.get() instanceof RuntimeException re) {
                throw re;
            }
            if (error.get() instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(error.get());
        }
    }

    private static <T> T fxGet(Supplier<T> supplier) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        runOnFx(() -> value.set(supplier.get()));
        return value.get();
    }

    private static void awaitFx(FxCondition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        AssertionError last = null;
        while (System.nanoTime() < deadline) {
            AtomicBoolean ok = new AtomicBoolean(false);
            AtomicReference<Throwable> error = new AtomicReference<>();
            CountDownLatch pulse = new CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    ok.set(condition.getAsBoolean());
                } catch (Throwable t) {
                    error.set(t);
                } finally {
                    pulse.countDown();
                }
            });
            assertTrue(pulse.await(2, TimeUnit.SECONDS));
            if (error.get() != null) {
                last = new AssertionError(error.get());
            } else if (ok.get()) {
                return;
            }
            Thread.sleep(40);
        }
        if (last != null) {
            throw last;
        }
        throw new AssertionError("Condition not met within timeout");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface FxCondition {
        boolean getAsBoolean() throws Exception;
    }
}
