package com.stellar.platform.desktop.presentation;

/**
 * Centralized DBC Mapping width / action-density policy (presentation only).
 * Thresholds are against available mapping <em>inner</em> content width —
 * never Scene width.
 */
public final class DbcMappingWidthPolicy {

    /** Full icon + text labels (required around 1600×900 content). */
    public static final double WIDE_MIN_INNER = 960.0;

    /** Icon-only + tooltip — last resort only. */
    public static final double NARROW_MAX_INNER = 840.0;

    public static final double COL_CHANNEL = 80.0;
    public static final double COL_IDS = 80.0;
    public static final double COL_STATE = 140.0;
    public static final double COL_MATCH_TARGET = 200.0;
    public static final double COL_MATCH_MIN = 180.0;
    public static final double COL_MATCH_MAX = 220.0;
    public static final double COL_DBC_MIN = 120.0;
    public static final double COL_DBC_MAX = 240.0;
    public static final double COL_ACTIONS_WIDE = 272.0;
    public static final double COL_ACTIONS_NARROW = 118.0;
    public static final double COL_GAP = 6.0;
    public static final int COL_GAP_COUNT = 5;

    public enum ActionDensity {
        WIDE,
        NARROW_ICON
    }

    public record ColumnBudget(
            double channel,
            double ids,
            double dbc,
            double match,
            double state,
            double actions,
            ActionDensity density
    ) {
    }

    private DbcMappingWidthPolicy() {
    }

    public static ActionDensity densityForInnerWidth(double innerWidth) {
        if (innerWidth > 0 && innerWidth < NARROW_MAX_INNER) {
            return ActionDensity.NARROW_ICON;
        }
        return ActionDensity.WIDE;
    }

    /**
     * Allocates columns from available source-group inner width.
     * Actions are reserved first; DBC absorbs leftover flex.
     */
    public static ColumnBudget budget(double innerWidth) {
        ActionDensity density = densityForInnerWidth(innerWidth);
        double actions = density == ActionDensity.NARROW_ICON ? COL_ACTIONS_NARROW : COL_ACTIONS_WIDE;
        double gaps = COL_GAP * COL_GAP_COUNT;
        double fixed = COL_CHANNEL + COL_IDS + COL_STATE + actions + gaps;
        double flex = Math.max(COL_DBC_MIN + COL_MATCH_MIN, innerWidth - fixed);

        double match = COL_MATCH_TARGET;
        double dbc = flex - match;
        if (dbc < COL_DBC_MIN) {
            dbc = COL_DBC_MIN;
            match = Math.max(COL_MATCH_MIN, flex - dbc);
        }
        if (match > COL_MATCH_MAX) {
            double overflow = match - COL_MATCH_MAX;
            match = COL_MATCH_MAX;
            dbc += overflow;
        }
        if (dbc > COL_DBC_MAX) {
            dbc = COL_DBC_MAX;
        }
        if (dbc < COL_DBC_MIN) {
            dbc = COL_DBC_MIN;
        }
        if (match < COL_MATCH_MIN) {
            match = COL_MATCH_MIN;
        }
        double sum = COL_CHANNEL + COL_IDS + dbc + match + COL_STATE + actions + gaps;
        if (innerWidth > 0 && sum > innerWidth) {
            double over = sum - innerWidth;
            double shrinkDbc = Math.min(over, Math.max(0, dbc - COL_DBC_MIN));
            dbc -= shrinkDbc;
            over -= shrinkDbc;
            if (over > 0) {
                match = Math.max(COL_MATCH_MIN, match - over);
            }
        }
        return new ColumnBudget(
                COL_CHANNEL,
                COL_IDS,
                dbc,
                match,
                COL_STATE,
                actions,
                density
        );
    }
}
