package com.stellar.platform.desktop.ui;

import javafx.geometry.HPos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared Recent Projects table column model — header and every data row must use
 * these exact constraints so values stay under their headers at all widths.
 */
public final class RecentProjectsTableLayout {
    public static final int COL_PROJECT = 0;
    public static final int COL_FILES = 1;
    public static final int COL_TYPES = 2;
    public static final int COL_SIGNALS = 3;
    public static final int COL_UPDATED = 4;
    public static final int COL_STAGE = 5;
    public static final int COL_STATUS = 6;
    public static final int COL_ACTION = 7;
    public static final int COLUMN_COUNT = 8;

    public static final double ROW_HEIGHT = 54;
    /** Column header inside the project table. Viewport height must include it or the next data row is cut in half. */
    public static final double HEADER_HEIGHT = 32;
    /** Adjacent cells so an active row paints as one band, not blue bars in every gap. */
    public static final double H_GAP = 0;
    public static final double OPEN_BTN_WIDTH = 72;
    /**
     * Status must stay wide enough for "Ready with warnings" plus cell padding.
     * Percent widths are not used: a fit-to-width grid ignores column minimums
     * and the Status cell collapses to its percentage.
     */
    public static final double STATUS_MIN_WIDTH = 176;

    private static final HPos[] ALIGN = {
            HPos.LEFT, HPos.RIGHT, HPos.LEFT, HPos.RIGHT,
            HPos.LEFT, HPos.LEFT, HPos.CENTER, HPos.RIGHT
    };

    private RecentProjectsTableLayout() {
    }

    public static List<ColumnConstraints> columnConstraints() {
        return columnConstraints(false);
    }

    public static List<ColumnConstraints> compactColumnConstraints() {
        return columnConstraints(true);
    }

    public static void apply(GridPane grid) {
        apply(grid, false);
    }

    public static void applyCompact(GridPane grid) {
        apply(grid, true);
    }

    private static void apply(GridPane grid, boolean compact) {
        grid.getColumnConstraints().setAll(columnConstraints(compact));
        grid.setHgap(H_GAP);
        grid.setVgap(0);
        grid.setMinWidth(0);
        grid.setMaxWidth(Double.MAX_VALUE);
    }

    private static List<ColumnConstraints> columnConstraints(boolean compact) {
        List<ColumnConstraints> cols = new ArrayList<>(COLUMN_COUNT);
        cols.add(sized(compact ? 72 : 96, compact ? 160 : 220, Double.MAX_VALUE, HPos.LEFT, Priority.ALWAYS));
        cols.add(sized(compact ? 40 : 44, compact ? 48 : 52, compact ? 56 : 64, HPos.RIGHT, Priority.NEVER));
        cols.add(sized(compact ? 64 : 80, compact ? 80 : 96, compact ? 104 : 128, HPos.LEFT, Priority.NEVER));
        cols.add(sized(compact ? 40 : 48, compact ? 48 : 56, compact ? 60 : 72, HPos.RIGHT, Priority.NEVER));
        cols.add(sized(compact ? 96 : 108, compact ? 108 : 120, compact ? 132 : 148, HPos.LEFT, Priority.NEVER));
        cols.add(sized(compact ? 108 : 128, compact ? 120 : 144, compact ? 148 : 168, HPos.LEFT, Priority.NEVER));
        cols.add(sized(STATUS_MIN_WIDTH, STATUS_MIN_WIDTH, compact ? 196 : 220, HPos.CENTER, Priority.NEVER));
        cols.add(sized(compact ? 132 : 148, compact ? 148 : 168, compact ? 168 : 188, HPos.RIGHT, Priority.NEVER));
        return cols;
    }

    public static void place(GridPane grid, Node node, int column, int row) {
        GridPane.setHalignment(node, ALIGN[column]);
        GridPane.setValignment(node, VPos.CENTER);
        GridPane.setFillWidth(node, true);
        grid.add(node, column, row);
    }

    private static ColumnConstraints sized(double min, double pref, double max, HPos align, Priority hgrow) {
        ColumnConstraints c = new ColumnConstraints();
        c.setMinWidth(min);
        c.setPrefWidth(pref);
        c.setMaxWidth(max);
        c.setHalignment(align);
        c.setHgrow(hgrow);
        c.setFillWidth(true);
        return c;
    }
}
