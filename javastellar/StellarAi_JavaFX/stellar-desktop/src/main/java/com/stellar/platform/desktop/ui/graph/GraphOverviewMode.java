package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.processing.graph.timeline.TimelineGroup;
import com.stellar.platform.processing.graph.timeline.UnknownOrderLayout;

/**
 * Which timeline the Graph overview is showing. File focus is optional and is not
 * chosen just because one recording has an unknown clock.
 */
public final class GraphOverviewMode {

    private GraphOverviewMode() {
    }

    /**
     * UTC when that group has recordings, unless the user asked for Unknown Order and it exists.
     * An empty preferred group falls through to the group that has recordings.
     */
    public static TimelineGroup activeGroup(int utcRecordings, int unknownRecordings, TimelineGroup preferred) {
        if (preferred == TimelineGroup.UNKNOWN_ORDER && unknownRecordings > 0) {
            return TimelineGroup.UNKNOWN_ORDER;
        }
        if (preferred == TimelineGroup.VERIFIED_UTC && utcRecordings > 0) {
            return TimelineGroup.VERIFIED_UTC;
        }
        if (utcRecordings > 0) {
            return TimelineGroup.VERIFIED_UTC;
        }
        return TimelineGroup.UNKNOWN_ORDER;
    }

    /**
     * One recording has no cross-file order. Its sample timestamps are elapsed time on that
     * recording's own clock. Two or more recordings without a verified shared clock stay on
     * equal-width layout slots. Looking similar is not comparability.
     */
    public static boolean singleRecordingStoredClock(int utcRecordings, int unknownRecordings, boolean fileFocus) {
        return !fileFocus && utcRecordings == 0 && unknownRecordings == 1;
    }

    /**
     * Measurement-relative window for a one-recording overview.
     * A window wider than one layout slot is already elapsed time: a single recording's slot is
     * only {@link UnknownOrderLayout#EQUAL_SLOT_SPAN} wide. A slot-sized layout window is mapped
     * back onto the recording's stored clock. Returns null unless the layout is exactly one slot.
     */
    public static long[] storedWindowForSingleRecording(
            UnknownOrderLayout layout, long windowStart, long windowEnd, boolean layoutDomain) {
        if (layout == null || layout.size() != 1 || windowEnd <= windowStart) {
            return null;
        }
        UnknownOrderLayout.Slot slot = layout.slot(0);
        long storedStart = slot.storedStartNanos();
        long storedEnd = slot.storedEndNanos() > storedStart ? slot.storedEndNanos() : storedStart + 1L;
        if (windowEnd - windowStart > UnknownOrderLayout.EQUAL_SLOT_SPAN) {
            return new long[]{windowStart, windowEnd};
        }
        boolean insideStored = windowStart >= storedStart && windowEnd <= storedEnd;
        if (!layoutDomain && insideStored) {
            return new long[]{windowStart, windowEnd};
        }
        long[] mapped = layout.storedQuery(slot, windowStart, windowEnd);
        if (mapped != null && mapped[1] > mapped[0]) {
            return mapped;
        }
        return new long[]{storedStart, storedEnd};
    }

    public static boolean showFileNavigation(boolean fileFocusEnabled, int recordingCount) {
        return fileFocusEnabled && recordingCount > 1;
    }

    /**
     * LOD pixel budget for one slot. A zoomed-out overview gets a few pixels per recording.
     * A slot that fills the canvas gets the canvas width, so zoom asks for more detail.
     */
    public static int slotQueryPixels(
            long layoutStart, long layoutEnd, long viewStart, long viewEnd, int canvasWidth) {
        int width = Math.max(1, canvasWidth);
        long slot = Math.max(1L, layoutEnd - layoutStart);
        long view = Math.max(1L, viewEnd - viewStart);
        double fraction = Math.min(1.0, slot / (double) view);
        return (int) Math.max(1L, Math.min(width, Math.ceil(width * fraction)));
    }
}
