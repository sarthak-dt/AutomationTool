package com.stellar.platform.desktop.ui.graph;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackQueryWindowTest {

    @Test
    void selectsTheVisibleTracksPlusOverscan() {
        TrackQueryWindow.Range window = TrackQueryWindow.of(200, 0, 500, 140, TrackQueryWindow.OVERSCAN);
        assertEquals(0, window.fromInclusive());
        assertTrue(window.size() <= 8, "window " + window);
        assertTrue(window.size() >= 4, "window " + window);
    }

    @Test
    void keepsEverySelectionAddressableWhileTheWindowStaysBounded() {
        TrackQueryWindow.Range top = TrackQueryWindow.of(300, 0, 480, 120, 2);
        TrackQueryWindow.Range bottom = TrackQueryWindow.of(300, 300 * 120d, 480, 120, 2);
        assertTrue(top.size() < 20, "top " + top);
        assertTrue(bottom.toExclusive() == 300, "bottom " + bottom);
        assertTrue(bottom.size() < 20, "bottom " + bottom);
        assertTrue(bottom.fromInclusive() > top.toExclusive());
    }

    @Test
    void aSmallScrollInsideTheOverscanDoesNotChangeTheWindow() {
        TrackQueryWindow.Range first = TrackQueryWindow.of(80, 0, 500, 140, 2);
        TrackQueryWindow.Range nudged = TrackQueryWindow.of(80, 20, 500, 140, 2);
        assertEquals(first, nudged);
    }
}
