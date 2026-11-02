package com.stellar.platform.desktop.presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalsWorkspacePreferencesTest {

    @Test
    void obsoleteTimelineValuesDoNotSelectADisplayMode() {
        assertTrue(SignalsWorkspacePreferences.obsoleteTimeline("THIS_SIGNAL"));
        assertTrue(SignalsWorkspacePreferences.obsoleteTimeline("This Signal"));
        assertTrue(SignalsWorkspacePreferences.obsoleteTimeline("Aligned Timeline"));
        assertTrue(SignalsWorkspacePreferences.obsoleteTimeline("ALIGNED"));
        assertFalse(SignalsWorkspacePreferences.obsoleteTimeline("PHYSICAL"));
        assertFalse(SignalsWorkspacePreferences.obsoleteTimeline("RAW"));
        assertFalse(SignalsWorkspacePreferences.obsoleteTimeline("AUTO"));
        assertTrue(SignalsWorkspacePreferences.displayMode("THIS_SIGNAL").isEmpty());
        assertTrue(SignalsWorkspacePreferences.displayMode("This Signal").isEmpty());
        assertTrue(SignalsWorkspacePreferences.precision("THIS_SIGNAL").isEmpty());
        assertEquals(WorkspaceDisplayMode.PHYSICAL, SignalsWorkspacePreferences.displayMode("PHYSICAL").orElseThrow());
        assertEquals(WorkspaceDisplayMode.RAW, SignalsWorkspacePreferences.displayMode("RAW").orElseThrow());
        assertEquals(WorkspacePrecision.DEC_3, SignalsWorkspacePreferences.precision("DEC_3").orElseThrow());
        assertTrue(SignalsWorkspacePreferences.displayMode("not-a-mode").isEmpty());
    }
}
