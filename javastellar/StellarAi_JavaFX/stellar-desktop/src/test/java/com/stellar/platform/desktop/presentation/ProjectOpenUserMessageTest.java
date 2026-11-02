package com.stellar.platform.desktop.presentation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectOpenUserMessageTest {

    @Test
    void missingPlanFingerprintIsNotACustomerDialog() {
        ProjectOpenUserMessage.Presentation presentation = ProjectOpenUserMessage.fromWarnings(
                "Lavasa",
                List.of("processing-plan fingerprint missing for 4231f217-cbfc-4baf-8a7d-e928dbf278d5",
                        "processing-plan fingerprint missing for 5168d4b2-3a53-45bd-a3cc-a8165b671ca8"));
        assertEquals(ProjectOpenUserMessage.Kind.NONE, presentation.kind());
        assertFalse(presentation.showDialog());
        assertTrue(presentation.body().isBlank());
        assertEquals(2, presentation.technicalDetails().size());
        assertTrue(presentation.technicalDetails().getFirst().contains("4231f217"));
    }

    @Test
    void reusableArtifactNoteIsDropped() {
        ProjectOpenUserMessage.Presentation presentation = ProjectOpenUserMessage.fromWarnings(
                "Lavasa",
                List.of("Reusable analysis artifacts are available without reparsing."));
        assertEquals(ProjectOpenUserMessage.Kind.NONE, presentation.kind());
        assertFalse(presentation.showDialog());
        assertTrue(presentation.technicalDetails().isEmpty());
    }

    @Test
    void genuineIncompatibilityShowsActionableHumanMessageWithoutRawUuids() {
        ProjectOpenUserMessage.Presentation presentation = ProjectOpenUserMessage.fromWarnings(
                "Lavasa",
                List.of("Source fingerprint mismatch for 5168d4b2-3a53-45bd-a3cc-a8165b671ca8 — reprocessing required."));
        assertTrue(presentation.showDialog());
        assertEquals(ProjectOpenUserMessage.Kind.ACTION_REQUIRED, presentation.kind());
        assertTrue(presentation.header().contains("Lavasa"));
        assertFalse(presentation.body().contains("5168d4b2"));
        assertFalse(presentation.body().toLowerCase().contains("fingerprint"));
        assertTrue(presentation.body().contains("Process again"));
        assertTrue(presentation.technicalDetails().getFirst().contains("5168d4b2"));
    }

    @Test
    void planFingerprintMismatchIsActionRequired() {
        ProjectOpenUserMessage.Presentation presentation = ProjectOpenUserMessage.fromWarnings(
                "Lavasa",
                List.of("Processing-plan fingerprint mismatch for src-1 — reprocessing required."));
        assertTrue(presentation.showDialog());
        assertTrue(presentation.body().contains("Process again"));
        assertFalse(presentation.body().contains("src-1"));
    }
}
