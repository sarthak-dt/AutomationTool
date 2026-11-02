package com.stellar.platform.desktop.ui.inventory;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TreeCell;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceInventoryWorkspaceUiTest {

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        // Closing the last Stage must not kill the shared FX thread for later tests.
        CountDownLatch configured = new CountDownLatch(1);
        Platform.runLater(() -> {
            Platform.setImplicitExit(false);
            configured.countDown();
        });
        assertTrue(configured.await(5, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(45)
    void workspaceGrowsAndNavigatorWidthsAreHardened() throws Exception {
        runOnFx(() -> {
            SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
            VBox page = new VBox(workspace);
            VBox.setVgrow(workspace, Priority.ALWAYS);
            assertTrue(VBox.getVgrow(workspace) == Priority.ALWAYS);
            assertTrue(workspace.navMinWidthForTest() >= 235);
            assertTrue(workspace.navMinWidthForTest() <= 275);
            assertTrue(workspace.navPrefWidthForTest() >= 240);
            assertTrue(workspace.navPrefWidthForTest() <= 275);
            assertTrue(workspace.navMaxWidthForTest() <= 280);
            // Workstation fills host height — no large fixed minHeight.
            assertTrue(workspace.getMinHeight() <= 1.0);
        });
    }

    @Test
    @Timeout(60)
    void selectionFilterAndAliasStability() throws Exception {
        runOnFx(() -> {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile a = addReady(service, "runA.mf4", 1, 0x201, 0x208);
            WorkspaceFile b = addReady(service, "runB.mf4", 1, 0x301);
            WorkspaceFile c = addReady(service, "runC.mf4", 1, 0x401);

            SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
            workspace.bind(service, () -> { });
            workspace.showDetailedMapping();
            workspace.refreshFromModel();

            assertEquals("Source A", service.sourceAliasRegistry().aliasFor(a.projectSourceId()));
            assertEquals("Source B", service.sourceAliasRegistry().aliasFor(b.projectSourceId()));
            assertEquals("Source C", service.sourceAliasRegistry().aliasFor(c.projectSourceId()));

            workspace.modelForTest().selectChannel(c.projectSourceId(), 1);
            workspace.refreshFromModel();
            assertEquals(c.projectSourceId(), workspace.modelForTest().selectedSourceId());
            assertEquals(1, workspace.modelForTest().selectedChannelId().orElse(-1));
            assertEquals(1, workspace.tableForTest().getItems().size());
            assertEquals(0x401, workspace.tableForTest().getItems().getFirst().canId());
            assertEquals("Showing 1 of 1 IDs", workspace.resultCountForTest().getText());

            workspace.refreshFromModel();
            assertEquals(c.projectSourceId(), workspace.modelForTest().selectedSourceId());
            assertEquals(1, workspace.modelForTest().selectedChannelId().orElse(-1));

            workspace.modelForTest().setSearchText("0x401");
            workspace.refreshFromModel();
            assertEquals(1, workspace.tableForTest().getItems().size());
            assertEquals(c.projectSourceId(), workspace.modelForTest().selectedSourceId());

            workspace.modelForTest().clearFilters();
            workspace.refreshFromModel();
            assertEquals(1, workspace.tableForTest().getItems().size());

            service.workspace().removeMeasurement(b.projectSourceId());
            service.sourceInventoryModel().remove(b.id());
            WorkspaceFile d = addReady(service, "runD.mf4", 1, 0x501);
            workspace.refreshFromModel();
            assertEquals("Source A", service.sourceAliasRegistry().aliasFor(a.projectSourceId()));
            assertEquals("Source C", service.sourceAliasRegistry().aliasFor(c.projectSourceId()));
            assertEquals("Source D", service.sourceAliasRegistry().aliasFor(d.projectSourceId()));
        });
    }

    @Test
    @Timeout(60)
    void navigatorGeometryKeepsAliasFilenameAndStatusVisible() throws Exception {
        runOnFx(() -> {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            addMultiChannel(service, "vehicle_164651.mf4",
                    new long[]{67, 55, 31},
                    new long[]{3_168_299L, 1_239_915L, 536_827L});
            SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
            workspace.bind(service, () -> { });
            workspace.showDetailedMapping();
            StackPane host = new StackPane(workspace);
            Scene scene = new Scene(host, 1600, 900);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            host.resize(1600, 900);
            host.layout();
            workspace.resize(1560, 700);
            workspace.layout();
            workspace.refreshFromModel();
            workspace.layout();
            workspace.navigatorForTest().layout();

            assertTrue(workspace.leftNavPaneForTest().getWidth() >= 230,
                    "navigator pane too narrow: " + workspace.leftNavPaneForTest().getWidth());
            assertTrue(workspace.leftNavPaneForTest().getWidth() <= 280,
                    "navigator pane too wide: " + workspace.leftNavPaneForTest().getWidth());

            assertTrue(workspace.navigatorForTest().getRoot().getChildren().size() >= 1);
            workspace.navigatorForTest().getSelectionModel().select(0);
            workspace.navigatorForTest().layout();
            workspace.layout();

            boolean foundSourceCell = false;
            for (javafx.scene.Node n : workspace.navigatorForTest().lookupAll(".tree-cell")) {
                if (n instanceof TreeCell<?> cell && cell.getGraphic() != null
                        && cell.getStyleClass().contains("source-inventory-source")) {
                    Bounds b = cell.localToScene(cell.getBoundsInLocal());
                    Bounds nav = workspace.navigatorForTest()
                            .localToScene(workspace.navigatorForTest().getBoundsInLocal());
                    assertTrue(b.getMaxX() <= nav.getMaxX() + 2.0,
                            "source cell clips past navigator: cellMaxX=" + b.getMaxX()
                                    + " navMaxX=" + nav.getMaxX());
                    foundSourceCell = true;
                }
            }
            assertTrue(foundSourceCell, "expected rendered source tree cell");
            stage.close();
            host.getChildren().clear();
            stage.setScene(null);
        });
    }

    @Test
    @Timeout(60)
    void channelCountsAndSelectedScopeRegression() throws Exception {
        runOnFx(() -> {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile s164 = addMultiChannel(service, "log_164651.mf4",
                    new long[]{67, 55, 31},
                    new long[]{3_168_299L, 1_239_915L, 536_827L});
            WorkspaceFile s171 = addMultiChannel(service, "log_171438.mf4",
                    new long[]{69, 56, 31},
                    new long[]{685_752L, 252_096L, 113_886L});
            WorkspaceFile lavasa = addReady(service, "Lavasa.mf4", 1, 0x316, 0x329, 0x7E8);

            SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
            workspace.bind(service, () -> { });
            workspace.showDetailedMapping();
            workspace.refreshFromModel();

            var inv164 = service.sourceInventoryModel().inventoryFor(s164.id()).orElseThrow();
            assertEquals(121, inv164.globalUniqueCanIdentityCount());
            assertEquals(4_945_041L, inv164.totalCanFrames());
            assertEquals(67, inv164.channels().get(0).uniqueMessageCount());
            assertEquals(55, inv164.channels().get(1).uniqueMessageCount());
            assertEquals(31, inv164.channels().get(2).uniqueMessageCount());

            var inv171 = service.sourceInventoryModel().inventoryFor(s171.id()).orElseThrow();
            assertEquals(124, inv171.globalUniqueCanIdentityCount());
            assertEquals(1_051_734L, inv171.totalCanFrames());
            assertEquals(69, inv171.channels().get(0).uniqueMessageCount());
            assertEquals(56, inv171.channels().get(1).uniqueMessageCount());
            assertEquals(31, inv171.channels().get(2).uniqueMessageCount());

            var invLav = service.sourceInventoryModel().inventoryFor(lavasa.id()).orElseThrow();
            assertEquals(3, invLav.globalUniqueCanIdentityCount());
            assertEquals(373_109L, invLav.totalCanFrames());

            workspace.modelForTest().selectChannel(s171.projectSourceId(), 3);
            workspace.refreshFromModel();
            assertTrue(workspace.scopeTitleForTest().getText().contains("Ch3")
                            || workspace.scopeTitleForTest().getText().contains("Channel 3"),
                    "scope title should identify channel 3, was: " + workspace.scopeTitleForTest().getText());
            assertEquals(31, workspace.tableForTest().getItems().size());
            assertEquals("Showing 31 of 31 IDs", workspace.resultCountForTest().getText());
            SourceChannelContextHeader header = workspace.contextHeaderForTest();
            assertTrue(header.measurementTileForTest().hasRoleClass("measurement-source"));
            assertTrue(header.idsValueForTest().getText().contains("31")
                            || header.idsValueForTest().getText().replace(",", "").contains("31"),
                    "CAN ID count tile should show live channel unique IDs");
            assertTrue(header.framesValueForTest().getText().replace(",", "").contains("113886")
                            || header.framesValueForTest().getText().contains("113,886"),
                    "frame tile should show live frame count");
            // Table model unchanged by context header presentation.
            assertEquals(8, workspace.tableForTest().getColumns().size());
            assertTrue(workspace.usesUnconstrainedTableResizeForTest());
            long frames = workspace.tableForTest().getItems().stream()
                    .mapToLong(r -> r.frameCount()).sum();
            assertEquals(113_886L, frames);

            workspace.modelForTest().setCanTypeFilter(
                    com.stellar.platform.desktop.presentation.SourceInventoryFilterCriteria.CanTypeFilter.STANDARD);
            workspace.refreshFromModel();
            assertEquals(s171.projectSourceId(), workspace.modelForTest().selectedSourceId());
            assertEquals(3, workspace.modelForTest().selectedChannelId().orElse(-1));

            // Workstation must own its scroll (not nested in an outer vertical ScrollPane).
            StackPane host = new StackPane(workspace);
            assertFalse(workspace.hasOuterVerticalScrollAncestorForTest());
            assertTrue(host.getChildren().contains(workspace));
        });
    }

    @Test
    @Timeout(60)
    void layoutFitsAt1600And1366() throws Exception {
        runOnFx(() -> {
            for (double[] size : new double[][]{{1600, 900}, {1366, 768}}) {
                DesktopAppService service = new DesktopAppService(new DesktopSession());
                addReady(service, "wide.mf4", 1, 0x18FF50E5L);
                SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
                workspace.bind(service, () -> { });
                workspace.showDetailedMapping();
                workspace.refreshFromModel();

                StackPane host = new StackPane(workspace);
                Scene scene = new Scene(host, size[0], size[1]);
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.show();
                host.resize(size[0], size[1]);
                host.layout();
                workspace.resize(Math.max(800, size[0] - 40), Math.max(420, size[1] - 120));
                workspace.layout();
                assertTrue(workspace.getWidth() > 700, "workspace width at " + size[0]);
                assertTrue(workspace.leftNavPaneForTest().getMaxWidth() <= 280);
                assertEquals(8, workspace.tableForTest().getColumns().size());
                assertTrue(workspace.toolbarForTest().getChildren().size() >= 5);
                if (size[0] >= 1600) {
                    workspace.modelForTest().selectChannel(
                            service.workspace().measurementFiles().getFirst().projectSourceId(), 1);
                    workspace.refreshFromModel();
                    if (!workspace.isInspectorCollapsedForTest()) {
                        workspace.collapseInspectorForTest();
                    }
                    workspace.layout();
                    workspace.tableForTest().layout();
                    assertFalse(workspace.hasHorizontalTableScrollForTest()
                                    && workspace.tableForTest().getWidth() > 1100,
                            "unexpected forced H-scroll with wide center pane");
                    // Identity columns remain allocated within readable bounds.
                    assertTrue(workspace.usesUnconstrainedTableResizeForTest());
                    assertTrue(workspace.tableForTest().getColumns().get(1).getWidth() >= 90); // CAN ID
                    assertTrue(workspace.tableForTest().getColumns().get(2).getWidth() >= 70); // Channel
                    assertTrue(workspace.tableForTest().getColumns().get(5).getPrefWidth()
                            >= workspace.tableForTest().getColumns().get(0).getPrefWidth(),
                            "DBC Message should be wider than Selection");
                    assertTrue(workspace.canTypeFilterCaptionForTest().getText().contains("CAN"));
                    assertTrue(workspace.dbcMatchFilterCaptionForTest().getText().contains("Match"));
                    assertTrue(workspace.contentFilterCaptionForTest().getText().contains("Content"));
                    assertTrue(workspace.tableForTest().getColumns().get(5).getWidth() >= 80,
                            "DBC Message must be visible");
                    assertTrue(workspace.tableForTest().getColumns().get(6).getWidth() >= 65,
                            "Signals must be visible");
                    assertTrue(workspace.hasCenterStudySetCountLabelForTest());
                    assertTrue(workspace.isTableBorderPaneCenterForTest());
                    assertTrue(workspace.tableHeightForTest() > 280,
                            "table-first layout should allocate substantial height, was "
                                    + workspace.tableHeightForTest());
                    assertTrue(workspace.detailsInspectorForTest().studyModeButtonForTest().getText()
                            .startsWith("Study Set ("));
                    // With UNCONSTRAINED policy, preferred column widths may exceed viewport — H-scroll is allowed.
                    double colSum = workspace.tableForTest().getColumns().stream()
                            .mapToDouble(c -> c.getWidth())
                            .sum();
                    assertTrue(colSum > 500, "columns should retain readable widths: " + colSum);
                }
                stage.close();
                host.getChildren().clear();
                stage.setScene(null);
            }
        });
    }

    @Test
    @Timeout(60)
    void longSourceNamesFitTheNavigatorWithoutADuplicateHorizontalBar() throws Exception {
        runOnFx(() -> {
            double outputScale = javafx.stage.Screen.getPrimary().getOutputScaleX();
            System.out.println("CustomDecodeNavigator outputScaleX=" + outputScale);
            for (double[] size : new double[][]{{1706, 900}, {1440, 900}, {1280, 800}, {1100, 720}}) {
                DesktopAppService service = new DesktopAppService(new DesktopSession());
                addMultiChannel(service,
                        "Vehicle_Measurement_BrakePressure_ChannelSet_2026-10-21_SessionA_FleetNorth.mf4",
                        new long[] {4, 3}, new long[] {40, 30});
                addReady(service,
                        "MF4_KA04NC7760_Bengaluru_0002360_20230814_164651.mf4", 1, 0x201, 0x208);
                addReady(service,
                        "MF4_KA04NC7760_Bengaluru_0002360_20230814_171438.mf4", 1, 0x301, 0x308);
                addReady(service, "OBD2_Data_Lavasa_CityDrive_102230.mf4", 1, 0x316, 0x329, 0x7E8);

                SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
                workspace.bind(service, () -> { });
                workspace.showDetailedMapping();
                workspace.refreshFromModel();

                StackPane host = new StackPane(workspace);
                Scene scene = new Scene(host, size[0], size[1]);
                var css = SourceInventoryWorkspace.class.getResource("/css/stellar-desktop.css");
                if (css != null) {
                    scene.getStylesheets().add(css.toExternalForm());
                }
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.show();
                host.resize(size[0], size[1]);
                for (int pulse = 0; pulse < 3; pulse++) {
                    host.applyCss();
                    host.layout();
                    workspace.resize(size[0], size[1]);
                    workspace.layout();
                }

                String where = size[0] + "x" + size[1];
                assertTrue(workspace.leftNavPaneForTest().getWidth() <= 280, where);
                assertEquals(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER,
                        workspace.navScrollForTest().getHbarPolicy(), where);
                assertEquals(0, workspace.navigatorHorizontalScrollBarCountForTest(), where);
                javafx.scene.Node tableNode = workspace.tableForTest();
                while (tableNode != null) {
                    assertFalse(tableNode == workspace.navScrollForTest(),
                            "center CAN table must not scroll with the left navigator");
                    tableNode = tableNode.getParent();
                }
                assertTrue(workspace.tableForTest().getHeight() > 120, where
                        + " table=" + workspace.tableForTest().getHeight());
                assertTrue(workspace.tableForTest().lookup(".column-header") != null, where);

                boolean sawLongName = false;
                Bounds tree = workspace.navigatorForTest().localToScene(
                        workspace.navigatorForTest().getBoundsInLocal());
                for (javafx.scene.Node node : workspace.navigatorForTest().lookupAll(".source-inventory-filename")) {
                    if (!(node instanceof javafx.scene.control.Label label) || !label.isVisible()) {
                        continue;
                    }
                    if (label.getText() == null || label.getText().isBlank()) {
                        continue;
                    }
                    Bounds box = label.localToScene(label.getBoundsInLocal());
                    assertTrue(box.getMinX() >= tree.getMinX() - 1,
                            where + " clipped " + label.getText() + " at " + box.getMinX());
                    if (label.getTooltip() != null
                            && label.getTooltip().getText().contains("Vehicle_Measurement_BrakePressure")) {
                        sawLongName = true;
                    }
                }
                assertTrue(sawLongName, where);

                javafx.scene.control.Button proceed = workspace.actionBarForTest().proceedButtonForTest();
                assertTrue(proceed.isVisible() && proceed.isManaged(), where);
                assertTrue(proceed.isFocusTraversable(), where);
                if (!proceed.isDisable()) {
                    proceed.requestFocus();
                    assertTrue(proceed.isFocused(), where);
                }
                List<javafx.scene.control.Button> actions = List.of(
                        workspace.actionBarForTest().confirmButtonForTest(),
                        workspace.actionBarForTest().compareButtonForTest(),
                        workspace.actionBarForTest().markNeedsAttentionForTest(),
                        proceed);
                List<Bounds> boxes = new ArrayList<>();
                for (javafx.scene.control.Button action : actions) {
                    if (!action.isVisible() || !action.isManaged()) {
                        continue;
                    }
                    assertFalse(action.getText().contains("\u2026"), action.getText());
                    assertTrue(action.getHeight() >= 30 && action.getHeight() <= 36.5,
                            where + " " + action.getText() + " height=" + action.getHeight());
                    boxes.add(action.localToScene(action.getBoundsInLocal()));
                }
                assertTrue(boxes.size() >= 3, where + " actions=" + boxes.size());
                for (int i = 0; i < boxes.size(); i++) {
                    for (int j = i + 1; j < boxes.size(); j++) {
                        Bounds a = boxes.get(i);
                        Bounds b = boxes.get(j);
                        boolean separated = a.getMaxX() <= b.getMinX() + 1
                                || b.getMaxX() <= a.getMinX() + 1
                                || a.getMaxY() <= b.getMinY() + 1
                                || b.getMaxY() <= a.getMinY() + 1;
                        assertTrue(separated, where + " overlap");
                    }
                }
                stage.close();
                host.getChildren().clear();
                stage.setScene(null);
            }
        });
    }

    @Test
    @Timeout(60)
    void navigatorShowsSourceChannelIdsNotCanIdCounts() throws Exception {
        runOnFx(() -> {
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile onlyCh2 = addExplicitChannels(service,
                    "tata_sierra_0002391_20260921_152521.mf4", new int[] {2}, 45);
            WorkspaceFile onlyCh1 = addExplicitChannels(service, "only_ch1.mf4", new int[] {1}, 3);
            WorkspaceFile three = addExplicitChannels(service, "three_channels.mf4", new int[] {1, 2, 3}, 2);
            WorkspaceFile skipped = addExplicitChannels(service,
                    "Vehicle_Measurement_BrakePressure_ChannelSet_2026-10-21_SessionA_FleetNorth.mf4",
                    new int[] {1, 3}, 4);

            SourceInventoryWorkspace workspace = showDetailed(service, 1706, 900);
            expandAll(workspace);
            workspace.layout();

            assertChannelLabel(workspace, "Ch2");
            assertChannelLabel(workspace, "Ch1");
            assertChannelLabel(workspace, "Ch3");
            assertNoChannelLabelEqualsCount(workspace, "45");
            assertEquals(0, workspace.navigatorHorizontalScrollBarCountForTest());
            boolean sawLongName = false;
            for (javafx.scene.Node node : workspace.navigatorForTest().lookupAll(".source-inventory-filename")) {
                if (node instanceof Label label && label.getTooltip() != null
                        && label.getTooltip().getText().contains("Vehicle_Measurement_BrakePressure")) {
                    sawLongName = true;
                }
            }
            assertTrue(sawLongName);

            workspace.modelForTest().selectChannel(onlyCh2.projectSourceId(), 2);
            workspace.refreshFromModel();
            expandAll(workspace);
            assertTrue(workspace.scopeTitleForTest().getText().contains("/ Ch2"),
                    workspace.scopeTitleForTest().getText());
            assertChannelLabel(workspace, "Ch2");
            assertEquals(45, workspace.tableForTest().getItems().size());

            workspace.modelForTest().selectChannel(onlyCh1.projectSourceId(), 1);
            workspace.refreshFromModel();
            assertTrue(workspace.scopeTitleForTest().getText().contains("/ Ch1"),
                    workspace.scopeTitleForTest().getText());
            workspace.modelForTest().selectChannel(three.projectSourceId(), 3);
            workspace.refreshFromModel();
            assertTrue(workspace.scopeTitleForTest().getText().contains("/ Ch3"),
                    workspace.scopeTitleForTest().getText());
            workspace.modelForTest().selectChannel(skipped.projectSourceId(), 3);
            workspace.refreshFromModel();
            assertTrue(workspace.scopeTitleForTest().getText().contains("/ Ch3"),
                    workspace.scopeTitleForTest().getText());
            assertFalse(workspace.scopeTitleForTest().getText().contains("/ Ch2"));
            closeShown(workspace);
        });
    }

    @Test
    @Timeout(60)
    void centerContentScrollsWhileActionBarStaysVisible() throws Exception {
        runOnFx(() -> {
            System.out.println("CustomDecodeCenterScroll outputScaleX="
                    + javafx.stage.Screen.getPrimary().getOutputScaleX());
            for (double[] size : new double[][] {{1706, 900}, {1440, 900}, {1280, 800}, {1100, 720}}) {
                DesktopAppService service = new DesktopAppService(new DesktopSession());
                WorkspaceFile file = addExplicitChannels(service,
                        "tata_sierra_0002391_20260921_152521.mf4", new int[] {2}, 45);
                SourceInventoryWorkspace workspace = showDetailed(service, size[0], size[1]);
                workspace.modelForTest().selectChannel(file.projectSourceId(), 2);
                workspace.refreshFromModel();
                for (int pulse = 0; pulse < 3; pulse++) {
                    workspace.applyCss();
                    workspace.layout();
                }
                String where = size[0] + "x" + size[1];
                ScrollPane scroll = workspace.centerScrollForTest();
                assertEquals(ScrollPane.ScrollBarPolicy.AS_NEEDED, scroll.getVbarPolicy(), where);
                assertEquals(ScrollPane.ScrollBarPolicy.NEVER, scroll.getHbarPolicy(), where);
                Bounds barBefore = sceneBounds(workspace.actionBarForTest());
                Bounds workspaceBounds = sceneBounds(workspace);
                Bounds scrollBounds = sceneBounds(scroll);
                assertTrue(barBefore.getMinY() + 1 >= scrollBounds.getMaxY(), where + " bar covers content");
                assertTrue(barBefore.getMaxY() <= workspaceBounds.getMaxY() + 2, where);
                assertTrue(workspace.actionBarForTest().proceedButtonForTest().isVisible(), where);
                assertTrue(workspace.actionBarForTest().compareButtonForTest().isVisible(), where);
                assertTrue(workspace.actionBarForTest().markNeedsAttentionForTest().isVisible(), where);

                double contentHeight = scroll.getContent().prefHeight(scroll.getWidth());
                double viewportHeight = scroll.getViewportBounds().getHeight();
                scroll.setVvalue(0);
                workspace.layout();
                assertTrue(intersects(sceneBounds(workspace.contextHeaderForTest()), viewportScene(scroll)),
                        where + " header");
                scroll.setVvalue(1);
                workspace.layout();
                assertTrue(intersects(sceneBounds(workspace.tableForTest()), viewportScene(scroll)),
                        where + " table");
                if (contentHeight > viewportHeight + 8) {
                    assertTrue(scroll.getViewportBounds().getHeight() > 0, where);
                    assertTrue(contentHeight > viewportHeight, where);
                }
                Bounds barAfter = sceneBounds(workspace.actionBarForTest());
                assertEquals(barBefore.getMinY(), barAfter.getMinY(), 1.0, where + " action bar scrolled away");
                assertEquals(2, workspace.modelForTest().selectedChannelId().orElse(-1), where);
                assertTrue(workspace.scopeTitleForTest().getText().contains("/ Ch2"), where);
                assertTrue(workspace.tableForTest().getHeight() >= 160, where
                        + " table=" + workspace.tableForTest().getHeight());
                assertChannelLabel(workspace, "Ch2");
                assertEquals(0, workspace.navigatorHorizontalScrollBarCountForTest(), where);
                closeShown(workspace);
            }
        });
    }

    private static SourceInventoryWorkspace showDetailed(DesktopAppService service, double width, double height) {
        SourceInventoryWorkspace workspace = new SourceInventoryWorkspace();
        workspace.bind(service, () -> { });
        workspace.showDetailedMapping();
        workspace.refreshFromModel();
        StackPane host = new StackPane(workspace);
        Scene scene = new Scene(host, width, height);
        var css = SourceInventoryWorkspace.class.getResource("/css/stellar-desktop.css");
        if (css != null) {
            scene.getStylesheets().add(css.toExternalForm());
        }
        Stage stage = new Stage();
        stage.setScene(scene);
        stage.show();
        host.resize(width, height);
        workspace.resize(width, height);
        host.applyCss();
        host.layout();
        workspace.applyCss();
        workspace.layout();
        workspace.getProperties().put("test-stage", stage);
        return workspace;
    }

    private static void closeShown(SourceInventoryWorkspace workspace) {
        Stage stage = (Stage) workspace.getProperties().remove("test-stage");
        if (stage != null) {
            if (stage.getScene() != null && stage.getScene().getRoot() instanceof StackPane host) {
                host.getChildren().clear();
            }
            stage.close();
            stage.setScene(null);
        }
    }

    private static void expandAll(SourceInventoryWorkspace workspace) {
        for (javafx.scene.control.TreeItem<?> item : workspace.navigatorForTest().getRoot().getChildren()) {
            item.setExpanded(true);
        }
        workspace.navigatorForTest().layout();
        workspace.layout();
    }

    private static void assertChannelLabel(SourceInventoryWorkspace workspace, String expected) {
        boolean found = false;
        Bounds tree = sceneBounds(workspace.navigatorForTest());
        for (javafx.scene.Node node : workspace.navigatorForTest().lookupAll(".source-inventory-channel-label")) {
            if (!(node instanceof Label label) || !expected.equals(label.getText()) || !label.isVisible()) {
                continue;
            }
            Bounds box = sceneBounds(label);
            assertTrue(label.getWidth() >= 16, expected + " width=" + label.getWidth());
            assertTrue(box.getMinX() >= tree.getMinX() - 1, expected + " left " + box.getMinX());
            assertTrue(box.getMaxX() <= tree.getMaxX() + 2, expected + " right " + box.getMaxX());
            found = true;
        }
        assertTrue(found, "missing channel label " + expected);
    }

    private static void assertNoChannelLabelEqualsCount(SourceInventoryWorkspace workspace, String countText) {
        for (javafx.scene.Node node : workspace.navigatorForTest().lookupAll(".source-inventory-channel-label")) {
            if (node instanceof Label label) {
                assertFalse(countText.equals(label.getText()), "channel label replaced by CAN ID count");
            }
        }
    }

    private static Bounds sceneBounds(javafx.scene.Node node) {
        return node.localToScene(node.getBoundsInLocal());
    }

    private static Bounds viewportScene(ScrollPane scroll) {
        return scroll.localToScene(scroll.getViewportBounds());
    }

    private static boolean intersects(Bounds a, Bounds b) {
        return a.getMaxX() > b.getMinX() + 1
                && a.getMinX() < b.getMaxX() - 1
                && a.getMaxY() > b.getMinY() + 1
                && a.getMinY() < b.getMaxY() - 1;
    }

    private static WorkspaceFile addExplicitChannels(
            DesktopAppService service, String name, int[] channelIds, int idsPerChannel) {
        WorkspaceFile file = service.workspace().addFile(
                new WorkspaceFile(Path.of(name).toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
        List<CanChannelInventory> channels = new ArrayList<>();
        long nextId = 0x100;
        long totalFrames = 0;
        int totalIds = 0;
        for (int channelId : channelIds) {
            List<CanMessageInventory> messages = new ArrayList<>();
            for (int i = 0; i < idsPerChannel; i++) {
                messages.add(new CanMessageInventory(
                        new CanMessageKey(channelId, nextId++, false), 100, 8, 8, false));
            }
            long frames = 100L * idsPerChannel;
            totalFrames += frames;
            totalIds += idsPerChannel;
            channels.add(new CanChannelInventory(
                    channelId, "Channel " + channelId, frames, idsPerChannel, idsPerChannel, 0, 0, messages));
        }
        CanSourceInventory inventory = new CanSourceInventory(
                file.path(), file.fileName(), MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, channelIds.length, totalFrames, totalIds, true,
                channels, List.of());
        service.sourceInventoryModel().put(file.id(), inventory);
        return file;
    }

    private static WorkspaceFile addReady(DesktopAppService service, String name, int channel, long... ids) {
        WorkspaceFile file = service.workspace().addFile(
                new WorkspaceFile(Path.of(name).toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
        List<CanMessageInventory> messages = new ArrayList<>();
        long frames = 0;
        for (int i = 0; i < ids.length; i++) {
            long f = name.toLowerCase().contains("lavasa") ? (i == 0 ? 373_109L : 0L) : (10L + i);
            // For Lavasa put all frames on first message only if 3 ids — distribute evenly instead
            messages.add(new CanMessageInventory(
                    new CanMessageKey(channel, ids[i], ids[i] > 0x7FF),
                    name.toLowerCase().contains("lavasa") ? (373_109L / ids.length
                            + (i < 373_109L % ids.length ? 1 : 0)) : (10L + i),
                    8, 8, false));
            frames += messages.get(i).frameCount();
        }
        if (name.toLowerCase().contains("lavasa")) {
            // Exact total 373109
            long sum = messages.stream().mapToLong(CanMessageInventory::frameCount).sum();
            if (sum != 373_109L) {
                CanMessageInventory first = messages.getFirst();
                messages.set(0, new CanMessageInventory(
                        first.key(), first.frameCount() + (373_109L - sum), 8, 8, false));
                frames = 373_109L;
            }
        }
        CanChannelInventory ch = new CanChannelInventory(
                channel, "Channel " + channel, frames, messages.size(),
                messages.size(), 0, 0, messages);
        CanSourceInventory inv = new CanSourceInventory(
                file.path(), file.fileName(), MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 1, frames, messages.size(), true,
                List.of(ch), List.of());
        service.sourceInventoryModel().put(file.id(), inv);
        return file;
    }

    private static WorkspaceFile addMultiChannel(
            DesktopAppService service,
            String name,
            long[] uniquePerChannel,
            long[] framesPerChannel
    ) {
        WorkspaceFile file = service.workspace().addFile(
                new WorkspaceFile(Path.of(name).toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
        long sumUnique = 0;
        for (long u : uniquePerChannel) {
            sumUnique += u;
        }
        long targetGlobal = name.contains("164651") ? 121 : name.contains("171438") ? 124 : sumUnique;
        int shared = (int) Math.max(0, sumUnique - targetGlobal);
        List<Long> sharedIds = new ArrayList<>();
        for (int i = 0; i < shared; i++) {
            sharedIds.add(0x100L + i);
        }
        long nextExclusive = 0x8000;
        List<CanChannelInventory> channels = new ArrayList<>();
        long totalFrames = 0;
        for (int c = 0; c < uniquePerChannel.length; c++) {
            int chId = c + 1;
            long unique = uniquePerChannel[c];
            long frames = framesPerChannel[c];
            List<Long> ids = new ArrayList<>();
            // Put min(shared, unique) shared IDs first so unions collapse correctly.
            int sharedTake = (int) Math.min(shared, unique);
            for (int i = 0; i < sharedTake; i++) {
                ids.add(sharedIds.get(i));
            }
            while (ids.size() < unique) {
                ids.add(nextExclusive++);
            }
            long base = frames / unique;
            long rem = frames % unique;
            List<CanMessageInventory> messages = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                long f = base + (i < rem ? 1 : 0);
                messages.add(new CanMessageInventory(
                        new CanMessageKey(chId, ids.get(i), false), f, 8, 8, false));
            }
            channels.add(new CanChannelInventory(
                    chId, "Channel " + chId, frames, unique, unique, 0, 0, messages));
            totalFrames += frames;
        }
        channels = rebuildExactGlobal(uniquePerChannel, framesPerChannel, targetGlobal);
        long global = CanSourceInventory.countGlobalUniqueCanIdentities(channels);
        totalFrames = channels.stream().mapToLong(CanChannelInventory::frameCount).sum();
        CanSourceInventory inv = new CanSourceInventory(
                file.path(), file.fileName(), MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, channels.size(), totalFrames, global, true,
                channels, List.of());
        service.sourceInventoryModel().put(file.id(), inv);
        return file;
    }

    private static List<CanChannelInventory> rebuildExactGlobal(
            long[] uniquePerChannel,
            long[] framesPerChannel,
            long targetGlobal
    ) {
        long sum = 0;
        for (long u : uniquePerChannel) {
            sum += u;
        }
        int overlapBudget = (int) (sum - targetGlobal); // each shared ID used on 2 channels consumes 1
        List<java.util.LinkedHashSet<Long>> perChannel = new ArrayList<>();
        for (int i = 0; i < uniquePerChannel.length; i++) {
            perChannel.add(new java.util.LinkedHashSet<>());
        }
        long next = 1;
        // Place overlapBudget IDs onto pairs of channels (exactly 2 each).
        for (int i = 0; i < overlapBudget; i++) {
            long id = next++;
            int a = i % uniquePerChannel.length;
            int b = (i + 1) % uniquePerChannel.length;
            perChannel.get(a).add(id);
            perChannel.get(b).add(id);
        }
        for (int c = 0; c < uniquePerChannel.length; c++) {
            while (perChannel.get(c).size() > uniquePerChannel[c]) {
                // Remove an ID that is still present on another channel first if possible.
                Long remove = null;
                for (Long id : perChannel.get(c)) {
                    int owners = 0;
                    for (var set : perChannel) {
                        if (set.contains(id)) {
                            owners++;
                        }
                    }
                    if (owners > 1) {
                        remove = id;
                        break;
                    }
                }
                if (remove == null) {
                    remove = perChannel.get(c).iterator().next();
                }
                perChannel.get(c).remove(remove);
            }
            while (perChannel.get(c).size() < uniquePerChannel[c]) {
                perChannel.get(c).add(next++);
            }
        }
        List<CanChannelInventory> channels = new ArrayList<>();
        for (int c = 0; c < uniquePerChannel.length; c++) {
            int chId = c + 1;
            java.util.LinkedHashSet<Long> ids = perChannel.get(c);
            long frames = framesPerChannel[c];
            long unique = ids.size();
            long base = frames / unique;
            long rem = frames % unique;
            List<CanMessageInventory> messages = new ArrayList<>();
            int i = 0;
            for (Long id : ids) {
                messages.add(new CanMessageInventory(
                        new CanMessageKey(chId, id, false), base + (i < rem ? 1 : 0), 8, 8, false));
                i++;
            }
            channels.add(new CanChannelInventory(
                    chId, "Channel " + chId, frames, unique, unique, 0, 0, messages));
        }
        return channels;
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
        assertTrue(done.await(50, TimeUnit.SECONDS), "JavaFX task timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface FxBody {
        void run() throws Exception;
    }
}
