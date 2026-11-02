package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.ChannelSelectionModel;
import com.stellar.platform.desktop.presentation.SourceInventoryMatchState;
import com.stellar.platform.desktop.presentation.SourceInventoryModel;
import com.stellar.platform.desktop.ui.inventory.ChannelSelectionPane;
import com.stellar.platform.dbc.model.CanIdKey;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.inventory.CanDefinitionMatchingService;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Project Files interaction regression: selection must remain enabled after READY,
 * and selection sync must not starve the FX thread via notification loops.
 * Page scroll hierarchy is asserted via FXML text (full FXMLLoader of project-files-view
 * is covered by layout structure tests to avoid FX-thread load hangs).
 */
class ProjectFilesInteractionTest {

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
    void projectFilesRemainsInteractiveAfterSelectionReady() throws Exception {
        assertProjectFilesScrollHierarchy();

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                ChannelSelectionPane selectionPane = new ChannelSelectionPane();
                ScrollPane scroll = new ScrollPane(selectionPane);
                scroll.setFitToWidth(true);
                scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
                scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
                scroll.getStyleClass().add("project-files-sources-scroll");

                BorderPane projectPage = new BorderPane();
                VBox contentColumn = new VBox(10, scroll);
                projectPage.setCenter(contentColumn);
                projectPage.setBottom(new Region());
                assertNotNull(projectPage.getBottom(), "Sticky processing action bar slot must exist");

                Scene scene = new Scene(projectPage, 1100, 720);
                var css = ProjectFilesController.class.getResource("/css/stellar-desktop.css");
                assertNotNull(css);
                scene.getStylesheets().add(css.toExternalForm());

                SourceInventoryModel inventoryModel = new SourceInventoryModel();
                ChannelSelectionModel selectionModel = new ChannelSelectionModel();
                CanSourceInventory inventory = sampleInventory();
                inventoryModel.put("f1", inventory);
                inventoryModel.focus("f1");
                inventoryModel.setMatchState(readyMatch(inventory));
                selectionModel.bindSource("f1", inventory.sourcePath(), inventory);

                selectionPane.setModels(inventoryModel, selectionModel);
                selectionPane.setOnSelectionChanged(() -> {
                    // Must remain a lightweight UI callback (hint/button only in production).
                });

                // Simulate Project Files sync loop: idempotent bind must not flood listeners.
                AtomicInteger modelNotifications = new AtomicInteger();
                selectionModel.addListener(modelNotifications::incrementAndGet);
                for (int i = 0; i < 20; i++) {
                    selectionModel.bindSource("f1", inventory.sourcePath(), inventory);
                }
                assertEquals(0, modelNotifications.get(),
                        "Idempotent bindSource must not notify (prevents FX thread freeze)");

                scroll.applyCss();
                scroll.resize(1100, 320);
                scroll.layout();
                selectionPane.refresh();
                scroll.layout();

                assertFalse(selectionPane.isDisabled());
                assertFalse(selectionPane.isMouseTransparent());
                assertFalse(scroll.isDisabled(), "Secondary Project Files ScrollPane must be enabled");

                Button selectAll = findButton(selectionPane, "Select All");
                Button selectMatched = findButton(selectionPane, "Select All Matched");
                Button clear = findButton(selectionPane, "Clear Selection");
                assertNotNull(selectAll);
                assertNotNull(selectMatched);
                assertNotNull(clear);
                assertFalse(selectAll.isDisabled());
                assertFalse(selectMatched.isDisabled());

                selectAll.fire();
                assertEquals(3, selectionModel.selectedCount());
                selectionPane.refresh(); // apply enablement after model change (listener is async)

                assertFalse(clear.isDisabled());
                clear.fire();
                assertFalse(selectionModel.hasSelection());
                selectionPane.refresh();

                selectMatched.fire();
                assertTrue(selectionModel.hasSelection());
                selectionPane.refresh();

                List<CheckBox> boxes = findAll(selectionPane, CheckBox.class);
                assertTrue(boxes.size() >= 3, "Expected channel Select checkboxes");
                for (CheckBox box : boxes) {
                    assertFalse(box.isDisabled(), "Checkbox must be enabled after DBC READY");
                    assertFalse(isCoveredBySiblingOverlay(box),
                            "Checkbox must not be covered by an inactive overlay region");
                }

                Parent content = (Parent) scroll.getContent();
                assertTrue(content instanceof Region);
                Region page = (Region) content;
                page.autosize();
                // Secondary scroll has a bounded viewport in Project Files; confirm it scrolls
                // when the selection pane exceeds that viewport.
                scroll.resize(1100, 120);
                scroll.layout();
                double contentHeight = page.prefHeight(page.getWidth());
                double viewportHeight = scroll.getHeight();
                assertTrue(contentHeight > viewportHeight,
                        "Scroll content must exceed viewport: content="
                                + contentHeight + " viewport=" + viewportHeight);
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

    private static void assertProjectFilesScrollHierarchy() throws Exception {
        Path fxml = Path.of("src/main/resources/fxml/project-files-view.fxml");
        if (!Files.isRegularFile(fxml)) {
            fxml = Path.of("stellar-desktop/src/main/resources/fxml/project-files-view.fxml");
        }
        String text = Files.readString(fxml);
        assertTrue(text.contains("project-files-sources-scroll"),
                "Sources workspace ScrollPane must exist for measurement/definition lists");
        assertTrue(text.contains("fx:id=\"workspaceHost\""),
                "exclusive workspace host StackPane must exist");
        assertTrue(text.contains("<top>"),
                "persistent Project Files chrome must live in BorderPane top");
        assertTrue(text.contains("SourceInventoryWorkspace"),
                "SourceInventoryWorkspace must remain a stable workspace root");
        assertTrue(text.contains("DbcMappingTablePane"),
                "DbcMappingTablePane must remain a stable workspace root");
        assertTrue(text.contains("project-files-workspace-nav"),
                "Project Files sub-navigation must exist");
        assertTrue(text.contains("managed=\"false\"") && text.contains("processingActionBar"),
                "ProcessingActionBar must remain retired from current Project Files chrome");
    }

    private static boolean isCoveredBySiblingOverlay(Node control) {
        Node n = control;
        while (n != null) {
            if (n instanceof Region region
                    && region.isVisible()
                    && !region.isMouseTransparent()
                    && region.getStyleClass().stream().anyMatch(s ->
                    s.contains("overlay") || s.contains("blocking") || s.contains("glass"))) {
                return true;
            }
            n = n.getParent();
        }
        return false;
    }

    private static CanSourceInventory sampleInventory() {
        return new CanSourceInventory(
                Path.of("sample.mf4"),
                "sample.mf4",
                MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY,
                3,
                100,
                12,
                true,
                List.of(channel(1), channel(2), channel(3)),
                List.of()
        );
    }

    private static CanChannelInventory channel(int id) {
        CanMessageInventory m1 = new CanMessageInventory(new CanMessageKey(id, 0x10, false), 5, 8, 8, false);
        CanMessageInventory m2 = new CanMessageInventory(new CanMessageKey(id, 0x20, false), 5, 8, 8, false);
        return new CanChannelInventory(id, "Channel " + id, 10, 2, 2, 0, 0, List.of(m1, m2));
    }

    private static SourceInventoryMatchState readyMatch(CanSourceInventory inventory) {
        DbcDatabase dbc = new DbcDatabase(
                "",
                List.of(),
                List.of(new DbcMessage(CanIdKey.fromFrame(0x10, false), "M10", 8, "", List.of(), "")),
                List.of()
        );
        return SourceInventoryMatchState.ofSummary(
                "x.dbc",
                new CanDefinitionMatchingService().match(inventory, dbc),
                dbc
        );
    }

    private static Button findButton(Parent root, String text) {
        for (Button b : findAll(root, Button.class)) {
            if (text.equals(b.getText())) {
                return b;
            }
        }
        return null;
    }

    private static <T> List<T> findAll(Parent root, Class<T> type) {
        List<T> found = new java.util.ArrayList<>();
        Deque<Node> q = new ArrayDeque<>();
        q.add(root);
        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            if (type.isInstance(n)) {
                found.add(type.cast(n));
            }
            if (n instanceof Parent p) {
                q.addAll(p.getChildrenUnmodifiable());
            }
            if (n instanceof ScrollPane sp && sp.getContent() != null) {
                q.add(sp.getContent());
            }
        }
        return found;
    }
}
