package com.stellar.platform.desktop.ui.home;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceHomeLayoutTest {

    @Test
    void kpiRowUsesFiveColumnsWhenWide() {
        assertEquals(5, WorkspaceHomeLayout.columnsFor(1200, 140, 5, 12));
    }

    @Test
    void kpiRowWrapsOnLaptopContentWidth() {
        assertEquals(3, WorkspaceHomeLayout.columnsFor(700, 168, 5, 12));
        assertEquals(2, WorkspaceHomeLayout.columnsFor(400, 168, 5, 12));
    }

    @Test
    void kpiRowNeverLeavesOneTileAlone() {
        assertEquals(5, WorkspaceHomeLayout.kpiColumns(1200));
        assertEquals(3, WorkspaceHomeLayout.kpiColumns(800));
        assertEquals(3, WorkspaceHomeLayout.columnsFor(900, 200, 5, 12));
        assertEquals(2, WorkspaceHomeLayout.kpiColumns(400));
    }

    @Test
    void getStartedUsesFourColumnsWhenWide() {
        assertEquals(4, WorkspaceHomeLayout.columnsFor(1400, 200, 4, 12));
        assertEquals(2, WorkspaceHomeLayout.columnsFor(500, 200, 4, 12));
    }
}
