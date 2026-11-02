package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.storage.metadata.GraphInvestigationMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectWorkspaceCheckpointTest {

    @TempDir
    Path temp;

    @Test
    void graphSessionCheckpointRoundTripsThroughSqlite() {
        SignalCatalogKey a = key("A", 1);
        SignalCatalogKey b = key("B", 2);
        SignalCatalogKey c = key("C", 3);
        GraphInvestigationState state = new GraphInvestigationState();
        state.beginSession();
        state.setLayoutMode(GraphInvestigationState.OVERLAY);
        state.hide(b);
        state.setFocus(c);
        state.setViewport(100_250_000_000L, 175_750_000_000L);
        state.setAnalysisTab(0);
        state.setInspectorVisible(false);
        state.setPlotSettingsVisible(false);
        GraphInvestigationSnapshot snap = state.snapshot("proj-1", List.of(a, b, c));

        try (SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(temp.resolve("stellar.db"))) {
            store.upsertProject(new ProjectMetadata(
                    "proj-1", "Checkpoint", Instant.parse("2026-09-07T17:27:00Z"),
                    Instant.parse("2026-09-07T17:27:00Z"), "GRAPHS",
                    Instant.parse("2026-09-07T17:27:00Z")));
            store.upsertGraphInvestigation(new GraphInvestigationMetadata(
                    "proj-1",
                    snap.layoutMode(),
                    snap.viewportStartNanos(),
                    snap.viewportEndNanos(),
                    snap.defaultStartNanos(),
                    snap.defaultEndNanos(),
                    snap.cursorANanos(),
                    snap.cursorBNanos(),
                    snap.notes(),
                    GraphInvestigationCodec.encodeTraces(snap.traces()),
                    Instant.now(),
                    GraphInvestigationCodec.encodeKey(snap.focusKey()),
                    snap.analysisTab(),
                    snap.inspectorVisible(),
                    snap.plotSettingsVisible(),
                    snap.signalsDisplayMode(),
                    snap.signalsPrecision(),
                    GraphInvestigationMetadata.PRESENTATION_VERSION
            ));

            GraphInvestigationMetadata loaded = store.findGraphInvestigation("proj-1").orElseThrow();
            assertEquals(GraphInvestigationState.OVERLAY, loaded.layoutMode());
            assertEquals(100_250_000_000L, loaded.viewportStartNanos());
            assertEquals(175_750_000_000L, loaded.viewportEndNanos());
            assertEquals(0, loaded.analysisTab());
            assertFalse(loaded.inspectorVisible());
            assertEquals(c, GraphInvestigationCodec.decodeKey(loaded.focusKeyBlob()));
            List<GraphInvestigationSnapshot.Trace> traces =
                    GraphInvestigationCodec.decodeTraces(loaded.tracesBlob());
            assertEquals(3, traces.size());
            assertTrue(traces.get(1).hidden());
            assertEquals("GRAPHS", store.loadProject("proj-1").orElseThrow().project().lastWorkspace());
            assertEquals(Instant.parse("2026-09-07T17:27:00Z"),
                    store.loadProject("proj-1").orElseThrow().project().lastSavedAt());
        }
    }

    @Test
    void explicitSaveCheckpointSurvivesUnsavedNavigationSemantics() {
        WorkspaceRoute checkpoint = WorkspaceRoute.GRAPHS;
        WorkspaceRoute live = WorkspaceRoute.SIGNALS;
        assertEquals(WorkspaceRoute.GRAPHS, checkpoint);
        assertEquals(WorkspaceRoute.SIGNALS, live);
        assertEquals(WorkspaceRoute.GRAPHS, ResumeDestinationResolver.resolve(
                ProjectJourneyState.ANALYSIS_READY,
                checkpoint,
                new ProjectJourneyFacts(true, 1, 1, true, false, false, false, true, true)));
    }

    @Test
    void lastSavedAtDisplayUsesPersistedInstantNotOpenTime() {
        Instant saved = Instant.parse("2026-09-07T17:27:43Z");
        Instant opened = Instant.parse("2026-09-07T18:00:00Z");
        String compact = SaveStatusFormatter.compactStatus(saved, ZoneId.of("UTC"));
        assertTrue(compact.contains("17:27"));
        assertFalse(compact.contains("18:00"));
        assertTrue(SaveStatusFormatter.tooltip(saved, ZoneId.of("UTC")).contains("17:27:43"));
        assertTrue(opened.isAfter(saved));
    }

    @Test
    void missingSavedSignalIsSkippedWithoutNameSubstitution() {
        SignalCatalogKey a = key("A", 1);
        SignalCatalogKey b = key("B", 2);
        SignalCatalogKey c = key("C", 3);
        java.util.Set<SignalCatalogKey> available = java.util.Set.of(a, c);
        GraphInvestigationSnapshot snap = new GraphInvestigationSnapshot(
                "p", GraphInvestigationState.OVERLAY, 1L, 2L, 1L, 2L, null, null, "",
                List.of(
                        new GraphInvestigationSnapshot.Trace(a, false, 0),
                        new GraphInvestigationSnapshot.Trace(b, false, 1),
                        new GraphInvestigationSnapshot.Trace(c, true, 2)
                ),
                c, 0, true, false, "", "");
        List<SignalCatalogKey> resolved = snap.traces().stream()
                .map(GraphInvestigationSnapshot.Trace::key)
                .filter(available::contains)
                .toList();
        GraphInvestigationState state = new GraphInvestigationState();
        state.beginSession();
        state.restore(snap, resolved);
        assertEquals(List.of(a, c), resolved);
        assertTrue(state.isHidden(c));
        assertEquals(c, state.focusKey());
        assertFalse(resolved.contains(b));
    }

    @Test
    void invalidViewportFallsBackToDataRange() {
        GraphInvestigationState state = new GraphInvestigationState();
        state.beginSession();
        state.setViewport(1L, 2L);
        long dataStart = 1_000_000_000L;
        long dataEnd = 2_000_000_000L;
        long start = state.viewportStart();
        long end = state.viewportEnd();
        boolean usable = end > start && end >= dataStart && start <= dataEnd
                && !(end < dataStart || start > dataEnd);
        if (!usable) {
            state.setViewport(dataStart, dataEnd);
        }
        assertTrue(state.viewportEnd() > state.viewportStart());
        assertEquals(dataStart, state.viewportStart());
        assertEquals(dataEnd, state.viewportEnd());
    }

    private static SignalCatalogKey key(String name, long canId) {
        return new SignalCatalogKey(name, SignalSourceType.DBC_CAN, "M", canId, "a.mf4", "veh.dbc");
    }
}
