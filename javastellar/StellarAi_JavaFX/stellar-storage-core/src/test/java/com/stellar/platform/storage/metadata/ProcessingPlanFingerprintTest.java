package com.stellar.platform.storage.metadata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessingPlanFingerprintTest {

    @Test
    void sameJobIntentIsStableAndCaseInsensitiveOnLifecycle() {
        String a = ProcessingPlanFingerprint.forJob("src", 0, "def", "CONFIRMED", "ASC");
        String b = ProcessingPlanFingerprint.forJob("src", 0, "def", "confirmed", "asc");
        assertEquals(a, b);
        assertTrue(a.startsWith("v1:"));
    }

    @Test
    void differentChannelOrDefinitionChangesFingerprint() {
        String base = ProcessingPlanFingerprint.forJob("src", 0, "def", "CONFIRMED", "ASC");
        assertFalse(base.equals(ProcessingPlanFingerprint.forJob("src", 1, "def", "CONFIRMED", "ASC")));
        assertFalse(base.equals(ProcessingPlanFingerprint.forJob("src", 0, "other", "CONFIRMED", "ASC")));
    }

    @Test
    void blankExpectedMatchesLegacyRows() {
        assertTrue(ProcessingPlanFingerprint.matches("", "v1:abc"));
        assertTrue(ProcessingPlanFingerprint.matches(null, "v1:abc"));
        assertFalse(ProcessingPlanFingerprint.matches("v1:abc", "v1:xyz"));
    }
}
