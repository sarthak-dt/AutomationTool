package com.stellar.platform.desktop.ui.graph.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapProjectStatusTest {

    @Test
    void allMeasurementsStatusDoesNotListFilenames() {
        String status = MapRoutePane.compactProjectGpsStatus(73, 73);
        assertTrue(status.contains("GPS detected"));
        assertTrue(status.contains("Independent segments"));
        assertFalse(status.contains(".dat"));
        assertFalse(status.contains("Partition"));
    }

    @Test
    void partialGpsDoesNotListFilenames() {
        String status = MapRoutePane.compactProjectGpsStatus(73, 70);
        assertTrue(status.contains("70 of 73 GPS-capable"));
        assertFalse(status.contains(".dat"));
    }
}
