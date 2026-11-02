package com.stellar.platform.desktop.presentation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestigationEventCodecTest {

    @Test
    void encodesTitleAndNoteWithoutSchemaChange() {
        String stored = InvestigationEventCodec.encode("Unexpected RPM spike", "Seen on overrun");
        assertEquals("Unexpected RPM spike", InvestigationEventCodec.titleOf(stored));
        assertEquals("Seen on overrun", InvestigationEventCodec.noteOf(stored));
        assertEquals("Observation", InvestigationEventCodec.categoryOf(""));
        assertEquals("Braking", InvestigationEventCodec.categoryOf("Braking"));
        assertTrue(InvestigationEventCodec.isManual("Issue"));
        assertFalse(InvestigationEventCodec.isManual("Transition"));
        assertFalse(InvestigationEventCodec.isManual(""));
    }
}
