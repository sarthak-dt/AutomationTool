package com.stellar.platform.desktop.ui.signals;

import com.stellar.platform.desktop.presentation.SignalsScreenModel;
import com.stellar.platform.desktop.ui.SignalsController;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adaptive Signals workbench: wide keeps three panes, smaller widths give the table priority.
 */
class SignalsResponsiveWorkspaceTest {

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
    void wideModeKeepsThreePanes() throws Exception {
        onFx(1920, 1080, (root, controller, workbench) -> {
            SignalBrowserPane browser = find(root, SignalBrowserPane.class);
            SignalInspectorPane inspector = find(root, SignalInspectorPane.class);
            assertEquals(SignalsWorkspaceMode.WIDE, controller.workspaceModeForTest());
            assertEquals(3, workbench.getChildren().size());
            assertFalse(inspector.isCollapsed());
            assertTrue(inspector.getMinWidth() >= SignalsLayoutMetrics.INSPECTOR_MIN_WIDTH - 1);
            assertTrue(browser.getMaxWidth() <= SignalsLayoutMetrics.BROWSER_MAX_WIDTH + 1);
            assertEquals(Priority.NEVER, HBox.getHgrow(browser));
            assertEquals(Priority.NEVER, HBox.getHgrow(inspector));
            assertEquals(Priority.ALWAYS, HBox.getHgrow(find(root, ActiveSignalWorkspacePane.class)));
        });
    }

    @Test
    @Timeout(30)
    void standardModeGivesTheTablePriority() throws Exception {
        onFx(1366, 768, (root, controller, workbench) -> {
            assertEquals(SignalsWorkspaceMode.STANDARD, controller.workspaceModeForTest());
            SignalInspectorPane inspector = find(root, SignalInspectorPane.class);
            ActiveSignalWorkspacePane workspace = find(root, ActiveSignalWorkspacePane.class);
            SignalBrowserPane browser = find(root, SignalBrowserPane.class);
            assertTrue(inspector.isCollapsed(), "empty details must collapse on a standard laptop");
            assertTrue(inspector.getMaxWidth() <= SignalsLayoutMetrics.DETAILS_RAIL_WIDTH + 1);
            assertTrue(workspace.getWidth() > browser.getWidth(),
                    "center " + workspace.getWidth() + " browser " + browser.getWidth());
            assertTrue(browser.getMaxWidth() <= SignalsLayoutMetrics.BROWSER_STANDARD_MAX_WIDTH + 1);
        });
    }

    @Test
    @Timeout(30)
    void compactModeCollapsesEmptyDetails() throws Exception {
        onFx(1100, 700, (root, controller, workbench) -> {
            assertEquals(SignalsWorkspaceMode.COMPACT, controller.workspaceModeForTest());
            SignalInspectorPane inspector = find(root, SignalInspectorPane.class);
            assertTrue(inspector.isCollapsed());
            assertTrue(workbench.getChildren().getFirst() instanceof SignalBrowserPane);
        });
    }

    @Test
    @Timeout(40)
    void releaseResolutionsKeepControlsReadable() throws Exception {
        for (int[] size : new int[][] {{1280, 720}, {1366, 768}, {1536, 864}, {1920, 1080}}) {
            onFx(size[0], size[1], (root, controller, workbench) -> {
                ActiveSignalWorkspacePane workspace = find(root, ActiveSignalWorkspacePane.class);
                Node toolbar = find(workspace, javafx.scene.layout.HBox.class, "analysis-toolbar");
                toolbar.setManaged(true);
                toolbar.setVisible(true);
                root.applyCss();
                root.layout();
                assertNoClippedLabel(workspace, "Copy Table");
                assertNoClippedLabel(workspace, "Focus Table");
                assertNoClippedLabel(root, "Analyze Signals");
                assertNoClippedLabel(root, "Clear All");
                assertFalse(containsLabel(workspace, "Add Selected"));
                assertFalse(containsLabel(root, "Change Channels"));
                assertFalse(containsLabel(root, "View Processing Summary"));
                assertTrue(workspace.toolbarHeightForTest() <= SignalsLayoutMetrics.TOOLBAR_HEIGHT + 4,
                        size[0] + " toolbar " + workspace.toolbarHeightForTest());
                assertTrue(containsLabel(workspace, "Navigate"));
                assertFalse(containsLabel(workspace, "Go to Index"));
                assertFalse(containsLabel(workspace, "Go to Time (s)"));
                assertTrue(containsLabel(workspace, "Display Mode"));
                StackPane host = find(workspace, StackPane.class, "workspace-table-host");
                assertEquals(Priority.ALWAYS, VBox.getVgrow(host));
                Region footer = find(workspace, Region.class, "workspace-table-footer");
                assertTrue(footer.getPrefHeight() <= 36, "footer " + footer.getPrefHeight());
                TableView<?> table = find(workspace, TableView.class, "workspace-table");
                assertEquals(Double.MAX_VALUE, table.getMaxHeight(), 0.1);
                assertTrue(host.getHeight() > 120, "table host too short at " + size[0] + ": " + host.getHeight());
            });
        }
    }

    @Test
    @Timeout(30)
    void browserCollapseGivesTheCenterTheReleasedWidth() throws Exception {
        onFx(1366, 768, (root, controller, workbench) -> {
            ActiveSignalWorkspacePane workspace = find(root, ActiveSignalWorkspacePane.class);
            int reloads = workspace.reloadCountForTest();
            double before = workspace.getWidth();
            controller.collapseSignalBrowserForTest();
            root.applyCss();
            root.layout();
            assertTrue(controller.signalBrowserCollapsedForTest());
            assertFalse(workbench.getChildren().getFirst() instanceof SignalBrowserPane);
            assertTrue(workspace.getWidth() > before + 40,
                    "center did not grow: before " + before + " after " + workspace.getWidth());
            assertEquals(reloads, workspace.reloadCountForTest());
            controller.expandSignalBrowserForTest();
            root.layout();
            assertFalse(controller.signalBrowserCollapsedForTest());
        });
    }

    @Test
    @Timeout(30)
    void detailsCollapseGivesTheCenterTheReleasedWidth() throws Exception {
        onFx(1920, 1080, (root, controller, workbench) -> {
            ActiveSignalWorkspacePane workspace = find(root, ActiveSignalWorkspacePane.class);
            SignalInspectorPane inspector = find(root, SignalInspectorPane.class);
            int reloads = workspace.reloadCountForTest();
            root.layout();
            double before = workspace.getWidth();
            inspector.setCollapsed(true);
            root.applyCss();
            root.layout();
            assertTrue(inspector.isCollapsed());
            assertTrue(workspace.getWidth() > before + 40,
                    "center did not grow: before " + before + " after " + workspace.getWidth());
            assertEquals(reloads, workspace.reloadCountForTest());
            inspector.setCollapsed(false);
            root.layout();
            assertFalse(inspector.isCollapsed());
        });
    }

    @Test
    @Timeout(30)
    void chipsStayOnOneScrollingRowAndFooterCanSplit() throws Exception {
        AtomicReference<SignalsScreenModel> modelRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            SignalsScreenModel model = new SignalsScreenModel();
            modelRef.set(model);
            try {
                InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 100);
                List<CanonicalSignal> samples = new java.util.ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    samples.add(new CanonicalSignal(
                            "Signal" + i, "Msg", 1_000_000_000L, 0x100, i + 1,
                            i, (long) i, "u", null, SourceReference.ofLine(1)));
                }
                store.accept(samples);
                model.replaceCatalog(store);
                List<SignalCatalogKey> keys = model.catalog().entries().stream()
                        .map(e -> e.key()).toList();
                model.addToWorkspace(keys);
                ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
                pane.setModel(model);
                Scene scene = new Scene(pane, 420, 640);
                assertTrue(scene.getWidth() > 0);
                pane.applyCss();
                pane.resize(420, 640);
                pane.layout();
                assertEquals(6, pane.previewChipCountForTest());
                assertFalse(pane.moreSignalsVisibleForTest());
                ScrollPane chips = pane.chipScrollForTest();
                assertTrue(chips.getMaxHeight() <= SignalsLayoutMetrics.CHIP_ROW_HEIGHT + 16);
                pane.resize(700, 640);
                pane.layout();
                assertTrue(pane.footerTimingWrappedForTest(), "timing metadata should use its own line");
                assertNoClippedLabel(pane, "Copy Table");
                assertNoClippedLabel(pane, "Go");
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (modelRef.get() != null) {
            modelRef.get().shutdown();
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @Test
    @Timeout(40)
    void chipsStayReadableForTwoFourAndManySignals() throws Exception {
        AtomicReference<SignalsScreenModel> modelRef = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            SignalsScreenModel model = new SignalsScreenModel();
            modelRef.set(model);
            try {
                String longName = "Battery_SOC_VeryLongEngineeringSignalName_Bengaluru";
                InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 100);
                java.util.ArrayList<CanonicalSignal> samples = new java.util.ArrayList<>();
                for (int i = 0; i < 12; i++) {
                    samples.add(new CanonicalSignal(
                            i == 0 ? longName : "Signal" + i,
                            "Msg", 1_000_000_000L, 0x100, 1,
                            i, (long) i, "km/h", null, SourceReference.ofLine(1)));
                }
                store.accept(samples);
                model.replaceCatalog(store);
                ActiveSignalWorkspacePane pane = new ActiveSignalWorkspacePane();
                Scene scene = new Scene(pane, 480, 640);
                var css = SignalsResponsiveWorkspaceTest.class.getResource("/css/stellar-desktop.css");
                assertTrue(css != null);
                scene.getStylesheets().add(css.toExternalForm());
                pane.setModel(model);
                for (int count : new int[] {2, 4, 5, 10, 12}) {
                    model.clearWorkspace();
                    model.addToWorkspace(model.catalog().entries().stream()
                            .limit(count)
                            .map(entry -> entry.key())
                            .toList());
                    pane.reload(true);
                    pane.applyCss();
                    pane.resize(480, 640);
                    pane.layout();
                    assertEquals(count, pane.previewChipCountForTest());
                    assertFalse(pane.moreSignalsVisibleForTest());
                    ScrollPane strip = pane.chipScrollForTest();
                    assertFalse(strip.isFitToWidth());
                    assertEquals(ScrollPane.ScrollBarPolicy.AS_NEEDED, strip.getHbarPolicy());
                    assertEquals(ScrollPane.ScrollBarPolicy.NEVER, strip.getVbarPolicy());
                    HBox bar = (HBox) strip.getContent();
                    if (count >= 4) {
                        assertTrue(bar.prefWidth(-1) > strip.getWidth(),
                                count + " chips do not overflow for scroll: " + bar.prefWidth(-1));
                        strip.setHvalue(1);
                        assertTrue(strip.getHmax() > strip.getHmin(), count + " chips are not scrollable");
                    }
                    double rowY = bar.getChildren().isEmpty() ? 0 : bar.getChildren().getFirst().getLayoutY();
                    assertTrue(bar.prefHeight(-1) <= SignalsLayoutMetrics.CHIP_HEIGHT + 8,
                            "chip strip wrapped vertically: " + bar.prefHeight(-1));
                    for (Node node : bar.getChildren()) {
                        assertEquals(rowY, node.getLayoutY(), 1.0, "chips must stay on one row");
                        assertTrue(node instanceof HBox);
                        HBox chip = (HBox) node;
                        assertTrue(chip.getMaxWidth() <= 240.5, "chip max " + chip.getMaxWidth());
                        assertTrue(chip.getMinWidth() >= 160, "chip min " + chip.getMinWidth());
                        assertTrue(chip.getStyle() == null || chip.getStyle().isBlank());
                        Label name = null;
                        boolean badge = false;
                        boolean channel = false;
                        boolean remove = false;
                        for (Node child : chip.getChildren()) {
                            if (child instanceof Label label && label.getStyleClass().contains("signal-chip-name")) {
                                name = label;
                            }
                            if (child instanceof Label label && "DBC".equals(label.getText())) {
                                badge = true;
                            }
                            if (child instanceof Label label && label.getText() != null && label.getText().startsWith("Ch")) {
                                channel = true;
                            }
                            if (child instanceof Button button && "×".equals(button.getText())) {
                                remove = true;
                            }
                        }
                        assertTrue(name != null && badge && channel && remove, "chip structure");
                        assertEquals(OverrunStyle.ELLIPSIS, name.getTextOverrun());
                        assertTrue(name.getTooltip() != null && name.getTooltip().getText().contains(name.getText()));
                        if (longName.equals(name.getText())) {
                            assertTrue(name.getWidth() > 1 && name.getWidth() + 8 < name.prefWidth(name.getHeight()),
                                    "long name was not ellipsized: " + name.getWidth());
                        }
                    }
                }
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
        if (modelRef.get() != null) {
            modelRef.get().shutdown();
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static void assertNoClippedLabel(Parent root, String text) {
        Button button = findButton(root, text);
        assertEquals(text, button.getText());
        assertFalse(button.getText().contains("..."));
        button.applyCss();
        assertTrue(button.getWidth() + 1 >= button.prefWidth(-1) || button.getWidth() <= 1,
                text + " squeezed to " + button.getWidth() + " pref " + button.prefWidth(-1));
    }

    private static boolean containsLabel(Parent root, String text) {
        Deque<Node> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Node node = pending.removeFirst();
            if (node instanceof Label label && text.equals(label.getText())) {
                return true;
            }
            if (node instanceof Parent parent) {
                pending.addAll(parent.getChildrenUnmodifiable());
            }
        }
        return false;
    }

    private static Button findButton(Parent root, String text) {
        Deque<Node> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Node node = pending.removeFirst();
            if (node instanceof Button button && text.equals(button.getText())) {
                return button;
            }
            if (node instanceof Parent parent) {
                pending.addAll(parent.getChildrenUnmodifiable());
            }
        }
        throw new AssertionError("button not found: " + text);
    }

    private void onFx(double width, double height, LayoutCheck check) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(SignalsController.class.getResource("/fxml/signals-view.fxml"));
                VBox root = loader.load();
                SignalsController controller = loader.getController();
                Scene scene = new Scene(root, width, height);
                assertTrue(scene.getWidth() > 0);
                root.applyCss();
                root.resize(width, height);
                root.layout();
                HBox workbench = find(root, HBox.class, "signals-workbench");
                controller.applyWorkspaceWidthForTest(workbench.getWidth() > 1 ? workbench.getWidth() : width - 24);
                root.applyCss();
                root.layout();
                check.check(root, controller, workbench);
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

    private static <T> T find(Parent root, Class<T> type) {
        return find(root, type, null);
    }

    private static <T> T find(Parent root, Class<T> type, String styleClass) {
        Deque<Node> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Node node = pending.removeFirst();
            if (type.isInstance(node) && (styleClass == null || node.getStyleClass().contains(styleClass))) {
                return type.cast(node);
            }
            if (node instanceof Parent parent) {
                pending.addAll(parent.getChildrenUnmodifiable());
            }
        }
        throw new AssertionError(type.getSimpleName() + " not found");
    }

    @FunctionalInterface
    private interface LayoutCheck {
        void check(VBox root, SignalsController controller, HBox workbench) throws Exception;
    }
}
