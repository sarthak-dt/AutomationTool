package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.presentation.DbcMappingWidthPolicy;
import com.stellar.platform.desktop.presentation.DbcMappingWidthPolicy.ActionDensity;
import com.stellar.platform.desktop.presentation.SourceDisplayNameResolver;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.ChannelRecommendationKind;
import com.stellar.platform.desktop.project.DefinitionSourceId;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
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

class DbcMappingLayoutFitTest {

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        CountDownLatch configured = new CountDownLatch(1);
        Platform.runLater(() -> {
            Platform.setImplicitExit(false);
            configured.countDown();
        });
        assertTrue(configured.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(45)
    void wideViewportKeepsActionsFullyVisibleWithoutHorizontalOverflow() throws Exception {
        runOnFx(() -> {
            DbcMappingTablePane pane = new DbcMappingTablePane();
            seedGroups(pane);
            // Do not wrap in an outer ScrollPane — nested fitToWidth scroll surfaces
            // amplify viewportBounds ↔ prefWidth feedback and poison the shared FX thread.
            StackPane host = new StackPane(pane);
            Scene scene = new Scene(host, 1600, 900);
            var css = DbcMappingTablePane.class.getResource("/css/stellar-desktop.css");
            if (css != null) {
                scene.getStylesheets().add(css.toExternalForm());
            }
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.setWidth(1600);
            stage.setHeight(900);
            stage.show();

            host.applyCss();
            host.resize(1600, 900);
            host.layout();
            pane.applyCss();
            pane.resize(1580, 860);
            pane.layout();
            ScrollPane list = pane.groupScrollForTest();
            VBox groupBox = pane.groupBoxForTest();
            list.applyCss();
            list.resize(Math.max(1000, pane.getWidth() - 8), 520);
            list.layout();
            java.lang.reflect.Method render = DbcMappingTablePane.class.getDeclaredMethod("renderVisibleGroups");
            render.setAccessible(true);
            render.invoke(pane);
            list.layout();
            pane.layout();

            double viewport = pane.mappingViewportWidthForTest();
            double inner = pane.sourceGroupInnerWidthForTest();
            assertTrue(inner >= DbcMappingWidthPolicy.WIDE_MIN_INNER - 1,
                    "expected WIDE inner at 1600 scene, inner=" + inner);
            assertEquals(ActionDensity.WIDE, pane.currentBudgetForTest().density());

            for (javafx.scene.Node group : findNodesByStyle(groupBox, "source-mapping-group")) {
                Bounds groupBounds = group.localToScene(group.getBoundsInLocal());
                for (javafx.scene.Node row : findNodesByStyle(group, "channel-mapping-row")) {
                    Bounds rowBounds = row.localToScene(row.getBoundsInLocal());
                    assertTrue(rowBounds.getMaxX() <= groupBounds.getMaxX() + 2.0,
                            "channel row exceeds source group right edge");
                    List<Button> actions = findButtons(row);
                    assertFalse(actions.isEmpty());
                    for (Button button : actions) {
                        Bounds b = button.localToScene(button.getBoundsInLocal());
                        assertTrue(b.getMaxX() <= rowBounds.getMaxX() + 1.5,
                                "action exceeds row: " + button.getText());
                        String text = button.getText() == null ? "" : button.getText();
                        if (!text.isBlank()) {
                            assertFalse(text.contains("...") || text.contains("…"),
                                    "ellipsized action: " + text);
                        }
                    }
                }
            }

            List<Button> actionButtons = findButtons(groupBox);
            boolean sawConfirm = false;
            boolean sawChoose = false;
            boolean sawCompare = false;
            for (Button button : actionButtons) {
                String text = button.getText() == null ? "" : button.getText();
                if ("Confirm".equals(text)) {
                    sawConfirm = true;
                }
                if ("Choose".equals(text)) {
                    sawChoose = true;
                }
                if ("Compare".equals(text)) {
                    sawCompare = true;
                    assertTrue(button.isVisible());
                    assertTrue(button.isManaged());
                }
            }
            assertTrue(sawConfirm && sawChoose && sawCompare,
                    "WIDE mode must show Confirm/Choose/Compare text labels");

            assertEquals(ScrollPane.ScrollBarPolicy.NEVER, list.getHbarPolicy());
            assertTrue(list.isFitToWidth());
            double contentW = groupBox.getLayoutBounds().getWidth();
            double viewW = list.getViewportBounds().getWidth();
            if (viewW > 1) {
                assertTrue(contentW <= viewW + 8.0,
                        "mapping content wider than viewport: content=" + contentW + " view=" + viewW);
            }
            assertEquals(0.0, list.getHvalue(), 0.001);

            CheckBox needs = find(pane.getScene().getRoot(), CheckBox.class);
            assertTrue(needs != null && "Needs Attention".equals(needs.getText()),
                    "Needs Attention label must not be truncated in control text");

            stage.close();
            host.getChildren().clear();
            stage.setScene(null);
        });
    }

    @Test
    @Timeout(45)
    void confirmedRowDoesNotReserveHiddenConfirmWidth() throws Exception {
        runOnFx(() -> {
            DbcMappingTablePane pane = new DbcMappingTablePane();
            ProjectSourceId a = ProjectSourceId.generate();
            List<SourceChannelMappingEntry> channels = List.of(
                    entry(a, 1, ChannelMappingStatus.CONFIRMED, 31, 31, 1.0)
            );
            List<DbcMappingTablePane.SourceMappingGroup> groups = List.of(
                    group(a, "Source A", "…_a.mf4", "a.mf4", channels)
            );
            setGroups(pane, groups, true);
            StackPane host = new StackPane(pane);
            Scene scene = new Scene(host, 1400, 800);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            host.resize(1400, 800);
            host.layout();
            pane.resize(1200, 500);
            pane.layout();
            pane.groupScrollForTest().resize(1100, 400);
            pane.groupScrollForTest().layout();
            java.lang.reflect.Method render = DbcMappingTablePane.class.getDeclaredMethod("renderVisibleGroups");
            render.setAccessible(true);
            render.invoke(pane);
            pane.layout();

            List<Button> actions = findButtons(pane.groupBoxForTest());
            assertTrue(actions.stream().noneMatch(b -> "Confirm".equals(b.getText())));
            assertTrue(actions.stream().anyMatch(b -> "Choose".equals(b.getText())));
            assertTrue(actions.stream().anyMatch(b -> "Compare".equals(b.getText())));
            for (Button b : actions) {
                assertTrue(b.isManaged(), "visible action must be managed");
            }
            stage.close();
            host.getChildren().clear();
            stage.setScene(null);
        });
    }

    private static void runOnFx(FxBody body) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(40, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface FxBody {
        void run() throws Exception;
    }

    private static void seedGroups(DbcMappingTablePane pane) throws Exception {
        ProjectSourceId a = ProjectSourceId.generate();
        ProjectSourceId b = ProjectSourceId.generate();
        String f1 = "MF4_KA04NC7760_Bengaluru_0002360_20230814_164651.mf4";
        String f2 = "MF4_KA04NC7760_Bengaluru_0002360_20230814_171438.mf4";
        List<DbcMappingTablePane.SourceMappingGroup> groups = List.of(
                group(a, "Source A", "…_164651.mf4", f1, List.of(
                        entry(a, 1, ChannelMappingStatus.RECOMMENDED, 6, 67, 6.0 / 67.0),
                        entry(a, 2, ChannelMappingStatus.RECOMMENDED, 9, 55, 9.0 / 55.0),
                        entry(a, 3, ChannelMappingStatus.CONFIRMED, 31, 31, 1.0)
                )),
                group(b, "Source B", "…_171438.mf4", f2, List.of(
                        entry(b, 1, ChannelMappingStatus.RECOMMENDED, 6, 69, 6.0 / 69.0),
                        entry(b, 2, ChannelMappingStatus.RECOMMENDED, 9, 56, 9.0 / 56.0),
                        entry(b, 3, ChannelMappingStatus.CONFIRMED, 31, 31, 1.0)
                ))
        );
        setGroups(pane, groups, true);
    }

    private static void setGroups(
            DbcMappingTablePane pane,
            List<DbcMappingTablePane.SourceMappingGroup> groups,
            boolean expanded
    ) throws Exception {
        Field allGroups = DbcMappingTablePane.class.getDeclaredField("allGroups");
        allGroups.setAccessible(true);
        allGroups.set(pane, groups);
        Field expandedField = DbcMappingTablePane.class.getDeclaredField("expandedBySourceId");
        expandedField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<String, Boolean>) expandedField.get(pane);
        for (DbcMappingTablePane.SourceMappingGroup g : groups) {
            map.put(g.sourceId().value(), expanded);
        }
        Field visible = DbcMappingTablePane.class.getDeclaredField("visibleGroups");
        visible.setAccessible(true);
        @SuppressWarnings("unchecked")
        var observable = (javafx.collections.ObservableList<DbcMappingTablePane.SourceMappingGroup>) visible.get(pane);
        observable.setAll(groups);
        java.lang.reflect.Method render = DbcMappingTablePane.class.getDeclaredMethod("renderVisibleGroups");
        render.setAccessible(true);
        render.invoke(pane);
    }

    private static DbcMappingTablePane.SourceMappingGroup group(
            ProjectSourceId id,
            String alias,
            String compact,
            String full,
            List<SourceChannelMappingEntry> channels
    ) {
        return new DbcMappingTablePane.SourceMappingGroup(
                id,
                new SourceDisplayNameResolver.DisplayLabel(id, alias, compact, full, "C:/data/" + full),
                null,
                channels,
                true,
                channels
        );
    }

    private static SourceChannelMappingEntry entry(
            ProjectSourceId sourceId,
            int channel,
            ChannelMappingStatus status,
            long matched,
            long observed,
            double ratio
    ) {
        DefinitionSourceId def = DefinitionSourceId.generate();
        return new SourceChannelMappingEntry(
                SourceChannelKey.of(sourceId, channel),
                status,
                ChannelRecommendationKind.CLEAR_BEST_MATCH,
                def,
                "demo.dbc",
                status == ChannelMappingStatus.CONFIRMED ? def : null,
                status == ChannelMappingStatus.CONFIRMED ? "demo.dbc" : "",
                observed,
                matched,
                ratio
        );
    }

    private static void pulse(javafx.scene.Parent node) {
        // Intentionally no autosize(): it fights ScrollPane.fitToWidth and can loop with
        // viewportBounds listeners. CSS + layout is enough for geometry assertions.
        node.applyCss();
        if (node.getParent() != null) {
            node.getParent().layout();
        }
        node.layout();
    }

    private static List<Button> findButtons(javafx.scene.Node root) {
        List<Button> out = new ArrayList<>();
        collect(root, Button.class, out);
        return out.stream()
                .filter(b -> b.getStyleClass().contains("mapping-action-button"))
                .toList();
    }

    private static List<javafx.scene.Node> findNodesByStyle(javafx.scene.Node root, String style) {
        List<javafx.scene.Node> out = new ArrayList<>();
        collectNodes(root, style, out);
        return out;
    }

    private static void collectNodes(javafx.scene.Node node, String style, List<javafx.scene.Node> out) {
        if (node.getStyleClass().contains(style)) {
            out.add(node);
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                collectNodes(child, style, out);
            }
        }
    }

    private static <T> T find(javafx.scene.Node root, Class<T> type) {
        List<T> out = new ArrayList<>();
        collect(root, type, out);
        return out.isEmpty() ? null : out.getFirst();
    }

    private static <T> void collect(javafx.scene.Node node, Class<T> type, List<T> out) {
        if (type.isInstance(node)) {
            out.add(type.cast(node));
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                collect(child, type, out);
            }
        }
    }
}
