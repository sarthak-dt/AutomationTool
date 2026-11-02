package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.presentation.DbcMappingWidthPolicy.ActionDensity;
import com.stellar.platform.desktop.presentation.DbcMappingWidthPolicy.ColumnBudget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbcMappingWidthPolicyTest {

    @Test
    void wideInnerKeepsFullActionLabels() {
        assertEquals(ActionDensity.WIDE, DbcMappingWidthPolicy.densityForInnerWidth(1100));
        assertEquals(ActionDensity.WIDE, DbcMappingWidthPolicy.densityForInnerWidth(960));
        ColumnBudget budget = DbcMappingWidthPolicy.budget(1100);
        assertEquals(ActionDensity.WIDE, budget.density());
        assertEquals(DbcMappingWidthPolicy.COL_ACTIONS_WIDE, budget.actions(), 0.01);
        double sum = budget.channel() + budget.ids() + budget.dbc() + budget.match()
                + budget.state() + budget.actions()
                + DbcMappingWidthPolicy.COL_GAP * DbcMappingWidthPolicy.COL_GAP_COUNT;
        assertTrue(sum <= 1100 + 1.0, "budget exceeds inner: " + sum);
    }

    @Test
    void narrowOnlyBelowLastResortThreshold() {
        assertEquals(ActionDensity.NARROW_ICON, DbcMappingWidthPolicy.densityForInnerWidth(800));
        assertEquals(ActionDensity.WIDE, DbcMappingWidthPolicy.densityForInnerWidth(850));
    }

    @Test
    void actionsReservedBeforeDbcGrows() {
        ColumnBudget a = DbcMappingWidthPolicy.budget(1000);
        ColumnBudget b = DbcMappingWidthPolicy.budget(1200);
        assertEquals(a.actions(), b.actions(), 0.01);
        assertTrue(b.dbc() >= a.dbc() - 0.01);
    }
}
