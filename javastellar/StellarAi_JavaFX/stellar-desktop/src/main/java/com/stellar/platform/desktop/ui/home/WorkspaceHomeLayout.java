package com.stellar.platform.desktop.ui.home;

import com.stellar.platform.desktop.ui.RecentProjectsTableLayout;
import javafx.scene.Node;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Presentation-only Workspace geometry.
 * Wide: Projects | Quick Actions / Recent Activity, then Get Started.
 * Split: Projects full width, then Quick Actions beside Recent Activity.
 * Stacked: those panels one under another when two lower panels cannot fit.
 */
public final class WorkspaceHomeLayout {

    public static final String WIDE = "workspace-wide";
    public static final String SPLIT = "workspace-split";
    public static final String STACKED = "workspace-stacked";
    /** Retained for callers that still say "compact" for any non-rail layout. */
    public static final String COMPACT = SPLIT;
    /**
     * Content width below this cannot keep a readable side rail beside Projects.
     * 1366 and 1536 windows (expanded sidebar) stay in the lower-band layout.
     * 1920 can still use the right rail.
     */
    public static final double STACK_BELOW = 1500;
    /** Content width below this stacks the two lower panels instead of pairing them. */
    public static final double STACK_LOWER_BELOW = 720;
    public static final double RAIL_MIN = 340;
    static final double RAIL_WIDTH = 360;
    static final double RAIL_MAX = 400;
    /**
     * Minimum data rows reserved for Projects before Quick Actions.
     * The table then grows with leftover viewport height.
     */
    public static final int PROJECT_ROWS_MIN = 3;
    /** Finite ceiling. {@code Double.MAX_VALUE} overflows JavaFX layout and collapses the panel. */
    private static final double PROJECT_AREA_MAX = 10_000;

    private WorkspaceHomeLayout() {
    }

    public static void install(
            ScrollPane rootScroll,
            VBox pageRoot,
            HBox kpiRow,
            HBox kpiRowSecond,
            VBox workspaceStack,
            HBox workspaceMain,
            VBox browserPane,
            VBox rightColumn,
            VBox quickActionsPane,
            VBox activityPane,
            FlowPane workflowRow,
            GridPane projectsTable
    ) {
        if (pageRoot == null || workspaceStack == null || workspaceMain == null
                || browserPane == null || rightColumn == null) {
            return;
        }
        HBox lowerBand = new HBox(16);
        lowerBand.getStyleClass().add("workspace-lower-band");
        lowerBand.setFillHeight(true);
        lowerBand.setMinWidth(0);
        lowerBand.setMaxWidth(Double.MAX_VALUE);
        bindWrap(workflowRow);
        Runnable relayout = () -> apply(
                pageRoot.getWidth(), rootScroll, pageRoot, kpiRow, kpiRowSecond, workspaceStack, workspaceMain,
                browserPane, rightColumn, quickActionsPane, activityPane, lowerBand,
                workflowRow, projectsTable);
        relayout.run();
        pageRoot.widthProperty().addListener((o, a, width) -> relayout.run());
        if (pageRoot.getScene() != null) {
            pageRoot.getScene().heightProperty().addListener((o, a, h) -> relayout.run());
        } else {
            pageRoot.sceneProperty().addListener((obs, oldScene, scene) -> {
                if (scene != null) {
                    scene.heightProperty().addListener((o, a, h) -> relayout.run());
                }
                relayout.run();
            });
        }
        if (kpiRow != null) {
            kpiRow.widthProperty().addListener((o, a, w) -> relayout.run());
        }
        if (workflowRow != null) {
            workflowRow.widthProperty().addListener((o, a, w) -> relayout.run());
        }
    }

    static void apply(
            double pageWidth,
            ScrollPane rootScroll,
            VBox pageRoot,
            HBox kpiRow,
            HBox kpiRowSecond,
            VBox workspaceStack,
            HBox workspaceMain,
            VBox browserPane,
            VBox rightColumn,
            VBox quickActionsPane,
            VBox activityPane,
            HBox lowerBand,
            FlowPane workflowRow,
            GridPane projectsTable
    ) {
        boolean measured = pageWidth > 0;
        boolean rail = !measured || pageWidth >= STACK_BELOW;
        boolean stacked = measured && pageWidth < STACK_LOWER_BELOW;
        String mode = rail ? WIDE : stacked ? STACKED : SPLIT;
        pageRoot.getStyleClass().removeAll(WIDE, SPLIT, STACKED, "workspace-compact");
        pageRoot.getStyleClass().add(mode);

        if (rootScroll != null) {
            rootScroll.setFitToHeight(true);
            rootScroll.setFitToWidth(true);
        }

        double contentWidth = contentWidth(pageRoot, pageWidth);
        arrangeKpiRows(kpiRow, kpiRowSecond, kpiColumns(flowWidth(kpiRow, contentWidth)));
        keepSingleRow(workflowRow, contentWidth >= 1000);
        sizeFlowChildren(workflowRow, contentWidth, 180, 4, 12);

        if (quickActionsPane != null && activityPane != null) {
            placeSidePanels(mode, rightColumn, quickActionsPane, activityPane, lowerBand);
        }

        workspaceMain.setMinWidth(0);
        workspaceStack.setMinWidth(0);
        workspaceStack.setMinHeight(Region.USE_COMPUTED_SIZE);
        pageRoot.setMinHeight(Region.USE_COMPUTED_SIZE);
        if (rail) {
            HBox.setHgrow(browserPane, Priority.ALWAYS);
            HBox.setHgrow(rightColumn, Priority.NEVER);
            VBox.setVgrow(workspaceMain, Priority.ALWAYS);
            VBox.setVgrow(browserPane, Priority.ALWAYS);
            workspaceMain.setFillHeight(true);
            browserPane.setMinWidth(0);
            browserPane.setMaxWidth(Double.MAX_VALUE);
            browserPane.setMaxHeight(PROJECT_AREA_MAX);
            rightColumn.getStyleClass().add("workspace-side-rail");
            rightColumn.setMinWidth(RAIL_MIN);
            rightColumn.setPrefWidth(RAIL_WIDTH);
            rightColumn.setMaxWidth(RAIL_MAX);
            rightColumn.setMinHeight(Region.USE_PREF_SIZE);
            if (browserPane.getParent() != workspaceMain || !workspaceMain.getChildren().contains(rightColumn)) {
                workspaceMain.getChildren().setAll(browserPane, rightColumn);
            }
            if (workspaceMain.getParent() != workspaceStack) {
                workspaceStack.getChildren().setAll(workspaceMain);
            }
            if (projectsTable != null) {
                RecentProjectsTableLayout.apply(projectsTable);
            }
            growProjectTable(browserPane, rootScroll);
        } else {
            rightColumn.getStyleClass().remove("workspace-side-rail");
            HBox.setHgrow(browserPane, Priority.ALWAYS);
            HBox.setHgrow(rightColumn, Priority.NEVER);
            VBox.setVgrow(browserPane, Priority.ALWAYS);
            fillWidth(browserPane);
            browserPane.setMinHeight(0);
            browserPane.setMaxHeight(PROJECT_AREA_MAX);
            growProjectTable(browserPane, rootScroll);
            Node lower = stacked || quickActionsPane == null ? rightColumn : lowerBand;
            fillWidth(lower instanceof Region region ? region : rightColumn);
            if (lower == rightColumn) {
                rightColumn.setMinWidth(0);
                rightColumn.setPrefWidth(Region.USE_COMPUTED_SIZE);
                rightColumn.setMaxWidth(Double.MAX_VALUE);
            }
            if (browserPane.getParent() != workspaceStack || lower.getParent() != workspaceStack) {
                workspaceStack.getChildren().setAll(browserPane, lower);
            }
            if (projectsTable != null) {
                if (stacked) {
                    RecentProjectsTableLayout.applyCompact(projectsTable);
                } else {
                    RecentProjectsTableLayout.apply(projectsTable);
                }
            }
        }
    }

    private static void placeSidePanels(
            String mode, VBox rightColumn, VBox quickActionsPane, VBox activityPane, HBox lowerBand
    ) {
        quickActionsPane.setMaxWidth(Double.MAX_VALUE);
        activityPane.setMaxWidth(Double.MAX_VALUE);
        if (SPLIT.equals(mode)) {
            HBox.setHgrow(quickActionsPane, Priority.ALWAYS);
            HBox.setHgrow(activityPane, Priority.ALWAYS);
            VBox.setVgrow(quickActionsPane, Priority.NEVER);
            VBox.setVgrow(activityPane, Priority.NEVER);
            if (quickActionsPane.getParent() != lowerBand) {
                lowerBand.getChildren().setAll(quickActionsPane, activityPane);
            }
            return;
        }
        VBox.setVgrow(quickActionsPane, Priority.NEVER);
        VBox.setVgrow(activityPane, Priority.NEVER);
        activityPane.setMinHeight(0);
        HBox.setHgrow(quickActionsPane, Priority.NEVER);
        HBox.setHgrow(activityPane, Priority.NEVER);
        if (quickActionsPane.getParent() != rightColumn) {
            rightColumn.getChildren().setAll(quickActionsPane, activityPane);
        }
    }

    /**
     * Projects owns leftover height. The table scrolls only after that area is filled.
     * A small minimum keeps the section from collapsing; it is not a one-row cap.
     */
    private static void growProjectTable(VBox browserPane, ScrollPane rootScroll) {
        ScrollPane tableScroll = projectTableScroll(browserPane);
        if (tableScroll == null) {
            return;
        }
        double min = RecentProjectsTableLayout.HEADER_HEIGHT
                + PROJECT_ROWS_MIN * RecentProjectsTableLayout.ROW_HEIGHT;
        double sceneH = 0;
        if (browserPane.getScene() != null) {
            sceneH = browserPane.getScene().getHeight();
            if (sceneH < 400 && browserPane.getScene().getWindow() != null) {
                sceneH = browserPane.getScene().getWindow().getHeight();
            }
        } else if (rootScroll != null && rootScroll.getScene() != null) {
            sceneH = rootScroll.getScene().getHeight();
        }
        int rows = PROJECT_ROWS_MIN;
        if (sceneH >= 980) {
            rows = 6;
        } else if (sceneH >= 640) {
            rows = 4;
        }
        double target = RecentProjectsTableLayout.HEADER_HEIGHT
                + rows * RecentProjectsTableLayout.ROW_HEIGHT;
        tableScroll.setMinHeight(target);
        tableScroll.setPrefHeight(target);
        tableScroll.setMaxHeight(PROJECT_AREA_MAX);
        VBox.setVgrow(tableScroll, Priority.ALWAYS);
        fitProjectTableWidth(tableScroll);
        browserPane.setMinHeight(Region.USE_COMPUTED_SIZE);
        browserPane.setPrefHeight(Region.USE_COMPUTED_SIZE);
        browserPane.setMaxHeight(PROJECT_AREA_MAX);
        if (browserPane.getParent() instanceof VBox stack) {
            stack.setMinHeight(Region.USE_COMPUTED_SIZE);
        }
    }

    private static void fitProjectTableWidth(ScrollPane tableScroll) {
        if (!(tableScroll.getContent() instanceof Region content)) {
            return;
        }
        content.setMinWidth(0);
        content.setMaxWidth(Double.MAX_VALUE);
    }

    private static ScrollPane projectTableScroll(VBox browserPane) {
        if (browserPane == null) {
            return null;
        }
        for (Node child : browserPane.getChildren()) {
            if (child instanceof ScrollPane scroll) {
                return scroll;
            }
        }
        return null;
    }

    private static void bindWrap(FlowPane pane) {
        if (pane == null) {
            return;
        }
        if (!pane.prefWrapLengthProperty().isBound()) {
            pane.prefWrapLengthProperty().bind(pane.widthProperty());
        }
        pane.setMinWidth(0);
        pane.setMaxWidth(Double.MAX_VALUE);
    }

    private static void keepSingleRow(FlowPane pane, boolean singleRow) {
        if (pane == null) {
            return;
        }
        if (singleRow) {
            if (pane.prefWrapLengthProperty().isBound()) {
                pane.prefWrapLengthProperty().unbind();
            }
            pane.setPrefWrapLength(10_000);
        } else {
            bindWrap(pane);
        }
    }

    private static void fillWidth(Region region) {
        region.setMinWidth(0);
        region.setMaxWidth(Double.MAX_VALUE);
    }

    private static void sizeFlowChildren(
            FlowPane pane, double pageWidth, double preferred, int maxColumns, double gap
    ) {
        if (pane == null || pageWidth <= 0) {
            return;
        }
        double available = flowWidth(pane, pageWidth);
        int columns = maxColumns == 5
                ? kpiColumns(available)
                : maxColumns == 4 && pageWidth >= 1000
                ? 4
                : columnsFor(available, preferred, maxColumns, gap);
        double cardWidth = Math.floor((available - gap * Math.max(0, columns - 1)) / columns);
        cardWidth = Math.max(96, cardWidth);
        double used = cardWidth * columns + gap * Math.max(0, columns - 1);
        if (used > available + 0.5 && columns > 0) {
            cardWidth = Math.floor(cardWidth - (used - available) / columns);
        }
        for (var child : pane.getChildren()) {
            if (child instanceof Region region) {
                zeroMinWidth(region);
                region.setMinWidth(0);
                region.setPrefWidth(cardWidth);
                region.setMaxWidth(cardWidth);
            }
        }
    }

    private static void zeroMinWidth(Node node) {
        if (node instanceof Region region) {
            region.setMinWidth(0);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                zeroMinWidth(child);
            }
        }
    }

    /**
     * Five tiles when they fit. Otherwise 3, 2, or 1 so a leftover tile is never alone
     * under a row of four.
     */
    static int kpiColumns(double width) {
        if (width >= 980) {
            return 5;
        }
        if (width >= 560) {
            return 3;
        }
        if (width >= 340) {
            return 2;
        }
        return width <= 0 ? 5 : 1;
    }

    static int columnsFor(double width, double preferred, int maxColumns, double gap) {
        if (width <= 0) {
            return maxColumns;
        }
        int fit = 1;
        for (int columns = maxColumns; columns >= 1; columns--) {
            double need = preferred * columns + gap * (columns - 1);
            if (width + 0.5 >= need) {
                fit = columns;
                break;
            }
        }
        if (maxColumns == 5 && fit == 4) {
            return 3;
        }
        return fit;
    }

    private static double contentWidth(VBox pageRoot, double pageWidth) {
        if (pageRoot == null) {
            return pageWidth;
        }
        var insets = pageRoot.getInsets();
        return Math.max(120, pageWidth - insets.getLeft() - insets.getRight());
    }

    private static double flowWidth(Region pane, double contentWidth) {
        if (pane != null && pane.getWidth() > 40) {
            return Math.min(pane.getWidth(), contentWidth);
        }
        return contentWidth;
    }

    /**
     * HBox rows do not wrap, so five tiles stay on one row and a 4+1 split cannot happen.
     * Medium width uses 3 + 2. Narrow width uses 2 + 2 + 1 only below the two-column threshold
     * by stacking the remainder on the second row.
     */
    private static void arrangeKpiRows(HBox first, HBox second, int columns) {
        if (first == null) {
            return;
        }
        List<Node> cards = new ArrayList<>();
        cards.addAll(first.getChildren());
        if (second != null) {
            cards.addAll(second.getChildren());
        }
        if (cards.isEmpty()) {
            return;
        }
        int topCount = columns >= cards.size() || second == null ? cards.size() : Math.max(1, columns);
        if (columns <= 2 && cards.size() == 5) {
            topCount = 2;
        }
        if (columns == 1) {
            topCount = 1;
        }
        List<Node> top = new ArrayList<>(cards.subList(0, topCount));
        List<Node> rest = new ArrayList<>(cards.subList(topCount, cards.size()));
        first.getChildren().setAll(top);
        growKpiCards(first);
        if (second == null) {
            return;
        }
        boolean showRest = !rest.isEmpty();
        second.setVisible(showRest);
        second.setManaged(showRest);
        if (showRest) {
            second.getChildren().setAll(rest);
            growKpiCards(second);
        } else {
            second.getChildren().clear();
        }
    }

    private static void growKpiCards(HBox row) {
        for (Node child : row.getChildren()) {
            if (child instanceof Region region) {
                HBox.setHgrow(region, Priority.ALWAYS);
                region.setMinWidth(0);
                region.setPrefWidth(Region.USE_COMPUTED_SIZE);
                region.setMaxWidth(PROJECT_AREA_MAX);
            }
        }
    }
}
