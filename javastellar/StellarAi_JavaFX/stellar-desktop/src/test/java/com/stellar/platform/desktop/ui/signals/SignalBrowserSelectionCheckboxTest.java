package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TableColumn;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Signal Browser selection is a checkbox bound to workspace membership. */
class SignalBrowserSelectionCheckboxTest {

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
    void checkboxReplacesStarAndUsesWorkspaceMembership() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            SignalsScreenModel model = new SignalsScreenModel();
            try {
                InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 40);
                List<CanonicalSignal> samples = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    samples.add(new CanonicalSignal(
                            "Signal" + i, "Msg", 1_000_000_000L, 0x100, 1,
                            i, (long) i, "u", null, SourceReference.ofLine(1)));
                }
                store.accept(samples);
                model.replaceCatalog(store);
                SignalBrowserPane pane = new SignalBrowserPane();
                pane.setModel(model);
                pane.setAddToWorkspaceListener(entries -> {
                    model.addToWorkspace(entries.stream().map(SignalCatalogEntry::key).toList());
                    pane.refreshFavoritePresentation();
                });
                pane.setRemoveFromWorkspaceListener(entries -> {
                    model.removePhysicalKeys(entries.stream().map(SignalCatalogEntry::key).toList());
                    pane.refreshFavoritePresentation();
                });
                pane.setOnSelectAll(() -> {
                    model.addPhysicalKeys(pane.filteredEntries().stream().map(SignalCatalogEntry::key).toList());
                    pane.refreshFavoritePresentation();
                });
                pane.setOnDeselectAll(() -> {
                    model.removePhysicalKeys(pane.filteredEntries().stream().map(SignalCatalogEntry::key).toList());
                    pane.refreshFavoritePresentation();
                });
                Scene scene = new Scene(pane, 420, 640);
                var css = SignalBrowserPane.class.getResource("/css/stellar-desktop.css");
                assertTrue(css != null);
                scene.getStylesheets().add(css.toExternalForm());
                pane.applyCss();
                pane.resize(420, 640);
                pane.layout();

                TableColumn<SignalCatalogEntry, Boolean> column = pane.selectionColumnForTest();
                String header = column.getText() == null ? "" : column.getText();
                assertFalse(header.contains("\u2605") || header.contains("\u2606"), header);
                assertInstanceOf(CheckBox.class, column.getGraphic());
                assertEquals(5, pane.catalogTable().getColumns().size());
                assertTrue(pane.catalogTable().getItems().size() >= 3);

                Set<javafx.scene.Node> boxes = pane.lookupAll(".browser-row-check");
                assertFalse(boxes.isEmpty(), "row checkbox was not created");
                CheckBox first = (CheckBox) boxes.iterator().next();
                assertFalse(first.isSelected());
                int selectionBefore = pane.catalogTable().getSelectionModel().getSelectedItems().size();
                first.fire();
                assertEquals(1, model.workspaceKeys().size(), "check must add the workspace signal");
                assertEquals(selectionBefore, pane.catalogTable().getSelectionModel().getSelectedItems().size(),
                        "checkbox must not create a second table-selection model");
                CheckBox checked = pane.lookupAll(".browser-row-check").stream()
                        .map(CheckBox.class::cast)
                        .filter(CheckBox::isSelected)
                        .findFirst()
                        .orElseThrow();
                checked.fire();
                assertTrue(model.workspaceKeys().isEmpty(), "uncheck must remove the workspace signal");

                pane.selectAllButtonForTest().fire();
                assertEquals(pane.filteredEntries().size(), model.workspaceKeys().size());
                assertTrue(pane.headerSelectionCheckForTest().isSelected());
                assertFalse(pane.headerSelectionCheckForTest().isIndeterminate());
                pane.deselectAllButtonForTest().fire();
                assertTrue(model.workspaceKeys().isEmpty());
                assertFalse(pane.headerSelectionCheckForTest().isSelected());
                pane.dispose();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                model.shutdown();
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }
}
