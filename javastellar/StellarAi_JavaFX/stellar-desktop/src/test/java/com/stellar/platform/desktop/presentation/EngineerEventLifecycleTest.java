package com.stellar.platform.desktop.presentation;

import com.stellar.platform.analysis.investigation.EngineerEventDefinition;
import com.stellar.platform.analysis.investigation.EngineerEventInclusion;
import com.stellar.platform.analysis.investigation.EngineerEventValidation;
import com.stellar.platform.analysis.investigation.EngineeringEvidenceItem;
import com.stellar.platform.analysis.investigation.EngineeringFormats;
import com.stellar.platform.storage.metadata.EngineeringBookmarkMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineerEventLifecycleTest {

    @TempDir
    Path temp;

    @Test
    void definitionRoundTripsAndReportInclusionDoesNotDeleteIt() {
        EngineerEventDefinition event = new EngineerEventDefinition(
                "evt-1", "Thermal window", "Thermal observation", "src-1", "sierra.mf4",
                1_196_588_000_000L, 1_799_787_000_000L,
                List.of("CH01_FRONT_BUMPER_GRILL_AMBIENT (°C)", "CH02_HOOD_ENGINE_SIDE (°C)"),
                "Engineer-selected window.", "Temperature rises.", "Measured in the window.",
                List.of("snap-1"), true, "Confirmed",
                "First GNSS sample inside the engineer-selected window.",
                "", "signals-comparison.png");
        Path db = temp.resolve("events.db");
        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db)) {
            store.upsertProject(new ProjectMetadata(
                    "proj", "Events", Instant.now(), Instant.now(), "GRAPHS", null));
            store.upsertBookmark(new EngineeringBookmarkMetadata(
                    event.id(), "proj", event.sourceId(), "", event.signals().getFirst(), event.signals().getFirst(),
                    event.startNanos(), event.category(), EngineerEventDocuments.observation(event),
                    Instant.parse("2026-10-24T12:00:00Z"), Instant.parse("2026-10-24T12:00:00Z")));
        }
        try (SqliteProjectMetadataStore reopened = new SqliteProjectMetadataStore(db)) {
            EngineeringBookmarkMetadata stored = reopened.listBookmarks("proj").getFirst();
            EngineerEventDefinition decoded = EngineerEventDocuments.decode(stored);
            assertEquals("Thermal window", InvestigationEventCodec.titleOf(stored.observation()));
            assertEquals("Engineer-selected window.", InvestigationEventCodec.noteOf(stored.observation()));
            assertFalse(InvestigationEventCodec.noteOf(stored.observation()).contains("STELLAR_EVENT"));
            assertEquals(event.endNanos(), decoded.endNanos());
            assertEquals(event.signals(), decoded.signals());
            assertEquals("Confirmed", decoded.confirmation());
            assertEquals("sierra.mf4", decoded.sourceFile());
            assertTrue(decoded.gpsAttached());
            String edited = EngineerEventDocuments.withTitleAndNote(stored.observation(), "Renamed", "Updated note");
            assertEquals("Renamed", InvestigationEventCodec.titleOf(edited));
            assertEquals("Updated note", InvestigationEventCodec.noteOf(edited));
            assertEquals(event.endNanos(), EngineerEventDocuments.decode(new EngineeringBookmarkMetadata(
                    stored.bookmarkId(), stored.projectId(), stored.sourceId(), stored.artifactId(),
                    stored.signalName(), stored.signalKey(), stored.timestampNanos(), stored.eventType(),
                    edited, stored.createdAt(), stored.updatedAt())).endNanos());
            assertEquals(1, reopened.listBookmarks("proj").size());
        }
        List<EngineeringEvidenceItem> included = EngineerEventInclusion.include(List.of(), "inv", "evt-1", "Thermal window");
        List<EngineeringEvidenceItem> again = EngineerEventInclusion.include(included, "inv", "evt-1", "Thermal window");
        assertEquals(1, again.size());
        assertTrue(EngineerEventInclusion.linked(again, "evt-1"));
        assertTrue(EngineerEventInclusion.exclude(again, "evt-1").isEmpty());
        assertEquals("1196.588 s", EngineeringFormats.seconds(EngineerEventDocuments.parseTime("1196.588")));
        assertEquals(1_196_588_000_000L, EngineerEventDocuments.parseTime("1196588000000"));
    }

    @Test
    void validationRejectsAReversedWindowAndAForeignSignal() {
        EngineerEventDefinition reversed = new EngineerEventDefinition(
                "evt", "Brake", "Braking", "src", "file.mf4",
                20L, 10L, List.of("CH01"), "", "", "", List.of(), false, "Draft", "", "", "");
        assertFalse(EngineerEventValidation.blocking(reversed, 0L, 100L, Set.of("CH01")).isEmpty());
        EngineerEventDefinition foreign = new EngineerEventDefinition(
                "evt", "Brake", "Braking", "src", "file.mf4",
                10L, 20L, List.of("OTHER"), "", "", "", List.of(), false, "Draft", "", "", "");
        assertTrue(EngineerEventValidation.blocking(foreign, 0L, 100L, Set.of("CH01")).getFirst().contains("OTHER"));
        EngineerEventDefinition point = new EngineerEventDefinition(
                "evt", "Mark", "Sensor anomaly", "src", "file.mf4",
                10L, null, List.of("CH01"), "", "", "", List.of("missing"), false, "Draft", "", "", "");
        assertTrue(EngineerEventValidation.blocking(point, 0L, 100L, Set.of("CH01")).isEmpty());
        assertTrue(EngineerEventValidation.warnings(point, java.util.Map.of("missing",
                EngineerEventValidation.SNAPSHOT_MISSING), false).stream()
                .anyMatch(line -> line.contains("missing") && line.contains("not regenerated")));
        assertTrue(EngineerEventValidation.warnings(point, java.util.Map.of(), false).stream()
                .anyMatch(line -> line.contains("Chronological order was not assumed")));
        assertNull(EngineerEventDocuments.decode(null));
    }
}
