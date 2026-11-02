package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.signals.ActiveSignalWorkspacePane;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import javafx.scene.control.TableColumn;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The deferred column reload must settle on the membership that exists when it runs.
 */
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class P0Phase5DeferredMembershipTest {

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void rapidEditsCollapseToOneReloadAndClearEmptiesTheTable() throws Exception {
        SignalsScreenModel model = new SignalsScreenModel();
        model.replaceCatalog(catalog());
        ActiveSignalWorkspacePane workspace = new ActiveSignalWorkspacePane();
        workspace.setModel(model);
        List<SignalCatalogKey> keys = model.displayedCatalog().stream()
                .limit(8)
                .map(SignalCatalogEntry::key)
                .toList();
        Stage[] shown = new Stage[1];
        JavaFxTestSupport.runOnFx("show", () -> {
            shown[0] = new Stage();
            shown[0].setScene(new javafx.scene.Scene(workspace, 900, 480));
            shown[0].show();
        });
        try {
            JavaFxTestSupport.runOnFx("burst", () -> {
                workspace.addPhysicalKeysAfterPaint(keys);
                workspace.removePhysicalKeysAfterPaint(List.of(keys.get(3)));
                workspace.addPhysicalKeysAfterPaint(List.of(keys.get(3)));
                workspace.removePhysicalKeysAfterPaint(keys.subList(6, 8));
            });
            assertEquals(6, model.workspaceKeys().size());
            awaitSignalColumns(workspace, 6);
            JavaFxTestSupport.runOnFx("clear", () ->
                    workspace.removePhysicalKeysAfterPaint(List.copyOf(model.workspaceKeys())));
            assertTrue(model.workspaceKeys().isEmpty());
            awaitSignalColumns(workspace, 0);
            AtomicInteger tableColumns = new AtomicInteger(-1);
            JavaFxTestSupport.runOnFx("empty table", () ->
                    tableColumns.set(workspace.table().getColumns().size()));
            assertEquals(0, tableColumns.get());
        } finally {
            JavaFxTestSupport.runOnFx("close", () -> shown[0].close());
            model.shutdown();
        }
    }

    private static void awaitSignalColumns(ActiveSignalWorkspacePane workspace, int expected) throws Exception {
        int found = -1;
        for (int pulse = 0; pulse < 8; pulse++) {
            JavaFxTestSupport.drainFxPulse();
            AtomicInteger size = new AtomicInteger();
            JavaFxTestSupport.runOnFx("count", () -> {
                int signals = 0;
                for (TableColumn<?, ?> column : workspace.table().getColumns()) {
                    if (column.getUserData() instanceof SignalCatalogKey) {
                        signals++;
                    }
                }
                size.set(signals);
            });
            found = size.get();
            if (found == expected) {
                return;
            }
        }
        assertEquals(expected, found);
    }

    private static InMemorySignalCatalogStore catalog() {
        InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("sierra.mf4", "sierra.dbc", 16);
        List<CanonicalSignal> samples = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            samples.add(new CanonicalSignal(
                    "Signal" + i, "Msg", 1_000_000L, 0x100, 1,
                    i, (long) i, "u", null, SourceReference.ofLine(1)));
        }
        store.accept(samples);
        return store;
    }
}
