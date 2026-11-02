package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Selection, visibility, order, colour index and the numeric viewport round-trip on the existing
 * GIV1 blob. Timeline group and file focus are not fields of that blob.
 */
class GraphSelectionPersistenceTest {

    @Test
    void twoHundredSignalsKeepOrderColourAndViewportWithoutANewBlobVersion() {
        GraphInvestigationState state = new GraphInvestigationState();
        List<SignalCatalogKey> keys = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            SignalCatalogKey key = new SignalCatalogKey(
                    "Signal" + i, SignalSourceType.MF4_NATIVE, "DIRECT_MEASUREMENT", 0L, "PROJECT", "");
            keys.add(key);
            assertEquals(i, state.colorIndex(key));
            if (i % 10 == 0) {
                state.hide(key);
            }
        }
        state.setViewport(0L, 73L * 1_000_000_000L);
        GraphInvestigationSnapshot snapshot = state.snapshot("1001", keys);
        String blob = GraphInvestigationCodec.encodeTraces(snapshot.traces());
        assertTrue(blob.startsWith("GIV1"), blob.substring(0, Math.min(8, blob.length())));
        assertEquals(GraphInvestigationCodec.STATE_VERSION, 1);
        assertFalse(blob.contains("timelineGroup"));
        assertFalse(blob.contains("fileFocus"));

        List<GraphInvestigationSnapshot.Trace> decoded = GraphInvestigationCodec.decodeTraces(blob);
        assertEquals(200, decoded.size());
        GraphInvestigationSnapshot reopened = new GraphInvestigationSnapshot(
                snapshot.projectId(), snapshot.layoutMode(), snapshot.viewportStartNanos(), snapshot.viewportEndNanos(),
                snapshot.defaultStartNanos(), snapshot.defaultEndNanos(), snapshot.cursorANanos(), snapshot.cursorBNanos(),
                snapshot.notes(), decoded);
        GraphInvestigationState restored = new GraphInvestigationState();
        restored.restore(reopened, keys);
        for (int i = 0; i < keys.size(); i++) {
            assertEquals(i, restored.colorIndex(keys.get(i)), "colour " + i);
            assertEquals(i % 10 == 0, restored.isHidden(keys.get(i)));
        }
        assertEquals(0L, restored.viewportStart());
        assertEquals(73L * 1_000_000_000L, restored.viewportEnd());
        assertTrue(restored.viewportInitialized());
        assertEquals("", restored.timelineGroup());
        assertEquals("", restored.viewportDomain());
    }

    @Test
    void giv2RestoresTimelineGroupWithoutReadingSlotNumbersAsUtc() {
        GraphInvestigationState state = new GraphInvestigationState();
        SignalCatalogKey key = new SignalCatalogKey(
                "Speed", SignalSourceType.MF4_NATIVE, "DIRECT_MEASUREMENT", 0L, "PROJECT", "");
        state.colorIndex(key);
        state.setViewport(37L * 1_000_000_000L, 38L * 1_000_000_000L);
        state.setTimelineContext("UNKNOWN_ORDER", "missing-file", "UNKNOWN_ORDER");
        GraphInvestigationSnapshot snapshot = state.snapshot("1001", List.of(key));
        String blob = GraphInvestigationCodec.encodeState(
                snapshot.traces(), state.timelineGroup(), state.fileFocusMeasurementId(), state.viewportDomain());
        assertTrue(blob.startsWith("GIV2"));
        assertTrue(GraphInvestigationCodec.compatibleTracesBlob(blob));
        assertTrue(GraphInvestigationCodec.compatibleTracesBlob("GIV1\n"));
        assertFalse(GraphInvestigationCodec.compatibleTracesBlob("GIV9\n"));

        GraphInvestigationCodec.Domain domain = GraphInvestigationCodec.decodeDomain(blob);
        assertEquals("UNKNOWN_ORDER", domain.timelineGroup());
        assertEquals("missing-file", domain.fileFocusMeasurementId());
        assertEquals("UNKNOWN_ORDER", domain.viewportDomain());
        List<GraphInvestigationSnapshot.Trace> traces = GraphInvestigationCodec.decodeTraces(blob);
        assertEquals(1, traces.size());
        assertEquals(0, traces.getFirst().colorIndex());

        GraphInvestigationState restored = new GraphInvestigationState();
        restored.setTimelineContext(domain.timelineGroup(), domain.fileFocusMeasurementId(), domain.viewportDomain());
        assertEquals("UNKNOWN_ORDER", restored.viewportDomain());
        assertEquals("missing-file", restored.fileFocusMeasurementId());
        assertEquals("", GraphInvestigationCodec.decodeDomain("GIV1\nT").timelineGroup());
    }
}
