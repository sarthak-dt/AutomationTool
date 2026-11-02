package com.stellar.platform.desktop.ui.inventory;

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
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression: Confirm must not rebuild mapping root or shift row/action geometry. */
class DbcMappingStabilityTest {

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
    @Timeout(45)
    void confirmDoesNotRebuildRootOrChangeActionColumnWidth() throws Exception {
        runOnFx(() -> {
            DbcMappingTablePane pane = new DbcMappingTablePane();
            ProjectSourceId a = ProjectSourceId.generate();
            ProjectSourceId c = ProjectSourceId.generate();
            DefinitionSourceId def = DefinitionSourceId.generate();
            List<SourceChannelMappingEntry> channelsA = List.of(
                    entry(a, 1, ChannelMappingStatus.RECOMMENDED, def, 6, 67, 6.0 / 67.0),
                    entry(a, 2, ChannelMappingStatus.RECOMMENDED, def, 9, 55, 9.0 / 55.0)
            );
            List<SourceChannelMappingEntry> channelsC = List.of(
                    entry(c, 1, ChannelMappingStatus.CONFIRMED, def, 31, 31, 1.0)
            );
            setGroups(pane, List.of(
                    group(a, "Source A", "…_a.mf4", "a.mf4", channelsA, true),
                    group(c, "Source C", "…_c.mf4", "c.mf4", channelsC, true)
            ));

            StackPane host = new StackPane(pane);
            Scene scene = new Scene(host, 1400, 800);
            // This is a layout-stability regression test, not a native-window test.
            // Keeping the graph attached to a Scene is sufficient for CSS/layout and
            // localToScene(), while avoiding creation of a native Glass/D3D window peer.
            // That makes the test deterministic on Windows CI/workstations.
            host.resize(1400, 800);
            host.applyCss();
            host.layout();
            pane.resize(1200, 600);
            pane.layout();
            pane.groupScrollForTest().resize(1100, 480);
            pane.groupScrollForTest().layout();
            render(pane);
            pane.groupScrollForTest().setVvalue(0.35);
            double scrollBefore = pane.groupScrollForTest().getVvalue();

            Bounds rootBefore = pane.localToScene(pane.getBoundsInLocal());
            Region actionsBefore = findFirstActions(pane);
            assertTrue(actionsBefore != null);
            actionsBefore.applyCss();
            ((Region) actionsBefore.getParent()).applyCss();
            pane.layout();
            double actionsW = Math.max(actionsBefore.getWidth(), actionsBefore.getPrefWidth());
            double rowW = Math.max(((Region) actionsBefore.getParent()).getWidth(),
                    ((Region) actionsBefore.getParent()).getPrefWidth());
            assertTrue(actionsW > 100, "actions width not laid out: " + actionsW);

            pane.resetMappingRootRebuildCountForTest();
            // Simulate confirm by swapping model data + refreshFromModel (presentation path).
            List<SourceChannelMappingEntry> confirmed = List.of(
                    entry(a, 1, ChannelMappingStatus.CONFIRMED, def, 6, 67, 6.0 / 67.0),
                    entry(a, 2, ChannelMappingStatus.RECOMMENDED, def, 9, 55, 9.0 / 55.0)
            );
            setGroups(pane, List.of(
                    group(a, "Source A", "…_a.mf4", "a.mf4", confirmed, true),
                    group(c, "Source C", "…_c.mf4", "c.mf4", channelsC, true)
            ));
            render(pane);
            pane.layout();

            Bounds rootAfter = pane.localToScene(pane.getBoundsInLocal());
            assertEquals(rootBefore.getWidth(), rootAfter.getWidth(), 2.0, "mapping root width jumped");
            assertEquals(rootBefore.getMinX(), rootAfter.getMinX(), 2.0, "mapping root x jumped");

            Region actionsAfter = findFirstActions(pane);
            assertTrue(actionsAfter != null);
            pane.layout();
            double actionsWAfter = Math.max(actionsAfter.getWidth(), actionsAfter.getPrefWidth());
            double rowWAfter = Math.max(((Region) actionsAfter.getParent()).getWidth(),
                    ((Region) actionsAfter.getParent()).getPrefWidth());
            assertEquals(actionsW, actionsWAfter, 2.0, "actions column width changed");
            assertEquals(rowW, rowWAfter, 3.0, "row width changed");

            assertEquals(0, pane.mappingRootRebuildCountForTest(),
                    "Confirm path must reuse source cards (no structural rebuild)");

            assertTrue(Math.abs(pane.groupScrollForTest().getVvalue() - scrollBefore) < 0.05,
                    "scroll jumped");

            var expansion = pane.expansionStateForTest();
            assertTrue(Boolean.TRUE.equals(expansion.get(a.value())));
            assertTrue(Boolean.TRUE.equals(expansion.get(c.value())));

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

    private static void render(DbcMappingTablePane pane) throws Exception {
        Method render = DbcMappingTablePane.class.getDeclaredMethod("renderVisibleGroups");
        render.setAccessible(true);
        render.invoke(pane);
    }

    private static void setGroups(
            DbcMappingTablePane pane,
            List<DbcMappingTablePane.SourceMappingGroup> groups
    ) throws Exception {
        Field allGroups = DbcMappingTablePane.class.getDeclaredField("allGroups");
        allGroups.setAccessible(true);
        allGroups.set(pane, groups);
        Field expandedField = DbcMappingTablePane.class.getDeclaredField("expandedBySourceId");
        expandedField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<String, Boolean>) expandedField.get(pane);
        for (DbcMappingTablePane.SourceMappingGroup g : groups) {
            map.put(g.sourceId().value(), g.expanded());
        }
        Field visible = DbcMappingTablePane.class.getDeclaredField("visibleGroups");
        visible.setAccessible(true);
        @SuppressWarnings("unchecked")
        var observable = (javafx.collections.ObservableList<DbcMappingTablePane.SourceMappingGroup>) visible.get(pane);
        observable.setAll(groups);
    }

    private static DbcMappingTablePane.SourceMappingGroup group(
            ProjectSourceId id,
            String alias,
            String compact,
            String full,
            List<SourceChannelMappingEntry> channels,
            boolean expanded
    ) {
        return new DbcMappingTablePane.SourceMappingGroup(
                id,
                new SourceDisplayNameResolver.DisplayLabel(id, alias, compact, full, "C:/data/" + full),
                null,
                channels,
                expanded,
                channels
        );
    }

    private static SourceChannelMappingEntry entry(
            ProjectSourceId sourceId,
            int channel,
            ChannelMappingStatus status,
            DefinitionSourceId def,
            long matched,
            long observed,
            double ratio
    ) {
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

    private static Region findFirstActions(javafx.scene.Node root) {
        if (root.getStyleClass().contains("channel-mapping-actions") && root instanceof Region region) {
            return region;
        }
        if (root instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                Region found = findFirstActions(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
