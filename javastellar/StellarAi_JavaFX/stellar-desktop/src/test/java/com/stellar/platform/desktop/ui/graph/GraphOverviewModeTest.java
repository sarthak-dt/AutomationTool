package com.stellar.platform.desktop.ui.graph;

import com.stellar.platform.processing.graph.timeline.RecordingDescriptor;
import com.stellar.platform.processing.graph.timeline.RecordingGroupIndex;
import com.stellar.platform.processing.graph.timeline.TimelineGroup;
import com.stellar.platform.processing.graph.timeline.UnknownOrderLayout;
import com.stellar.platform.processing.map.time.ClockKind;
import com.stellar.platform.processing.map.time.ClockStatus;
import com.stellar.platform.processing.map.time.RecordingClock;
import com.stellar.platform.processing.map.time.TimeUnitKind;
import com.stellar.platform.processing.map.time.TimestampSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphOverviewModeTest {

    @Test
    void unknownClockDoesNotForceFileFocusAndUtcStaysAvailable() {
        assertEquals(TimelineGroup.VERIFIED_UTC, GraphOverviewMode.activeGroup(2, 1, null));
        assertEquals(TimelineGroup.UNKNOWN_ORDER, GraphOverviewMode.activeGroup(0, 73, null));
        assertEquals(TimelineGroup.UNKNOWN_ORDER,
                GraphOverviewMode.activeGroup(2, 73, TimelineGroup.UNKNOWN_ORDER));
        assertEquals(TimelineGroup.VERIFIED_UTC,
                GraphOverviewMode.activeGroup(2, 73, TimelineGroup.VERIFIED_UTC));
        assertFalse(GraphOverviewMode.showFileNavigation(false, 73));
        assertTrue(GraphOverviewMode.showFileNavigation(true, 73));
        assertFalse(GraphOverviewMode.showFileNavigation(true, 1));
    }

    @Test
    void zoomedOutSlotsUseFewPixelsAndAFilledSlotUsesTheCanvas() {
        long slot = UnknownOrderLayout.EQUAL_SLOT_SPAN;
        assertEquals(8, GraphOverviewMode.slotQueryPixels(0, slot, 0, 300L * slot, 2400));
        assertEquals(2400, GraphOverviewMode.slotQueryPixels(0, slot, 0, slot, 2400));
        assertTrue(GraphOverviewMode.slotQueryPixels(0, slot, 0, 300L * slot, 2400) < 2400);
    }

    @Test
    void oneRecordingUsesItsOwnClockAndSeveralUnknownRecordingsStayInSlots() {
        assertTrue(GraphOverviewMode.singleRecordingStoredClock(0, 1, false));
        assertFalse(GraphOverviewMode.singleRecordingStoredClock(0, 1, true));
        assertFalse(GraphOverviewMode.singleRecordingStoredClock(0, 2, false));
        assertFalse(GraphOverviewMode.singleRecordingStoredClock(1, 1, false));
        assertEquals(TimelineGroup.UNKNOWN_ORDER, GraphOverviewMode.activeGroup(0, 2, null));
        assertEquals(TimelineGroup.VERIFIED_UTC, GraphOverviewMode.activeGroup(2, 0, null));

        long storedStart = 63_840_000L;
        long storedEnd = 1_799_846_550_000L;
        RecordingGroupIndex one = RecordingGroupIndex.of(List.of(unknown("sierra.mf4", storedStart, storedEnd)));
        long slot = UnknownOrderLayout.EQUAL_SLOT_SPAN;
        assertEquals(slot, one.unknownLayout().layoutEnd());
        assertArrayEquals(new long[]{storedStart, storedEnd},
                GraphOverviewMode.storedWindowForSingleRecording(one.unknownLayout(), 0L, slot, true));
        assertArrayEquals(new long[]{storedStart, storedEnd},
                GraphOverviewMode.storedWindowForSingleRecording(
                        one.unknownLayout(), storedStart, storedEnd, false));

        RecordingGroupIndex several = RecordingGroupIndex.of(List.of(
                unknown("a.mf4", 0L, 5_000_000_000L),
                unknown("b.mf4", 20_000_000L, 9_000_000_000L)));
        assertEquals(2, several.unknownLayout().size());
        assertNull(GraphOverviewMode.storedWindowForSingleRecording(
                several.unknownLayout(), 0L, 2 * slot, true));
        assertEquals(2 * slot, several.unknownLayout().layoutEnd());
        assertEquals(5_000_000_000L, several.unknownLayout().slot(0).storedEndNanos());
        assertEquals(20_000_000L, several.unknownLayout().slot(1).storedStartNanos());
    }

    @Test
    void comparableClocksShareATimelineWithoutUsingTheLayoutSlot() {
        long tenSeconds = 10_000_000_000L;
        RecordingGroupIndex index = RecordingGroupIndex.of(List.of(
                utc("left.mf4", 1_000_000_000_000L, 0L, tenSeconds),
                utc("right.mf4", 1_000_000_000_000L + 5_000_000_000L, 0L, tenSeconds)));
        assertEquals(2, index.size(TimelineGroup.VERIFIED_UTC));
        assertEquals(0, index.size(TimelineGroup.UNKNOWN_ORDER));
        assertFalse(GraphOverviewMode.singleRecordingStoredClock(2, 0, false));
        long span = index.utcTimeline().displayEnd() - index.utcTimeline().displayStart();
        assertEquals(15_000_000_000L, span);
        assertNotEquals(UnknownOrderLayout.EQUAL_SLOT_SPAN, span);
    }

    private static RecordingDescriptor unknown(String file, long start, long end) {
        return new RecordingDescriptor(file, file, RecordingClock.unordered(
                file, TimestampSource.NONE, ClockKind.RELATIVE_ONLY, TimeUnitKind.NANOSECONDS, "no verified clock"),
                start, end);
    }

    private static RecordingDescriptor utc(String file, long utcStart, long storedStart, long storedEnd) {
        RecordingClock clock = new RecordingClock(
                file, TimestampSource.MF4_HD, ClockKind.UTC, TimeUnitKind.NANOSECONDS,
                0, utcStart, ClockStatus.COMPARABLE, "");
        return new RecordingDescriptor(file, file, clock, storedStart, storedEnd);
    }
}
