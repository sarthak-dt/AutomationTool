package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalInspectorReuseTest {

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
    void focusChangesReuseSectionNodesInsteadOfAccumulatingThem() throws Exception {
        onFx(() -> {
            SignalsScreenModel model = new SignalsScreenModel();
            InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 1_000);
            store.accept(List.of(canonical("SIG_A", 1), canonical("SIG_B", 2)));
            model.replaceCatalog(store);
            List<SignalCatalogEntry> entries = model.catalog().entries();
            assertTrue(entries.size() >= 2);
            SignalCatalogEntry first = entries.get(0);
            SignalCatalogEntry second = entries.get(1);
            SignalInspectorPane pane = new SignalInspectorPane();
            pane.setModel(model);

            model.setFocusedSignalKey(first.key());
            pane.refresh();
            int afterFirst = pane.sectionNodeCountForTest();
            assertTrue(afterFirst > 0);

            model.setFocusedSignalKey(second.key());
            pane.refresh();
            assertEquals(afterFirst, pane.sectionNodeCountForTest());

            model.setFocusedSignalKey(first.key());
            pane.refresh();
            assertEquals(afterFirst, pane.sectionNodeCountForTest());

            pane.dispose();
            assertTrue(pane.disposedForTest());
            assertEquals(0, model.favoritesListenerCountForTest());
        });
    }

    private static CanonicalSignal canonical(String name, int channel) {
        return new CanonicalSignal(
                name, "MSG", 1_000L * channel, 0x100, channel, 10.0, 10L, "rpm", null,
                SourceReference.ofLine(channel));
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
}
