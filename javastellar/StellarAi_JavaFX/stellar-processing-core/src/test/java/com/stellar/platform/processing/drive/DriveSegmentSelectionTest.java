package com.stellar.platform.processing.drive;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriveSegmentSelectionTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void defaultIsFirstToLastGpsSample() {
        DriveSegmentSelection selection = new DriveSegmentSelection();
        selection.applyFullDrive(0, 99, 0L, 99 * SECOND);

        assertEquals(0, selection.indexA());
        assertEquals(99, selection.indexB());
        assertTrue(selection.fullDrive());
        assertFalse(selection.userDefined());
        assertEquals(DriveSegmentSelection.Phase.FULL_DRIVE, selection.phase());
        assertEquals(0L, selection.startTimestampNanos());
        assertEquals(99 * SECOND, selection.endTimestampNanos());
    }

    @Test
    void firstClickSetsAAndSecondClickSetsB() {
        DriveSegmentSelection selection = new DriveSegmentSelection();
        selection.applyFullDrive(0, 50, 0L, 50 * SECOND);
        selection.enterSelectMode();

        assertEquals(DriveSegmentSelection.Phase.WAITING_FOR_A, selection.phase());
        assertEquals(DriveSegmentSelection.ClickResult.A_COMMITTED,
                selection.clickNearestSample(10, 10 * SECOND));
        assertEquals(10, selection.indexA());
        assertEquals(-1, selection.indexB());
        assertTrue(selection.awaitingB());
        assertEquals(DriveSegmentSelection.Phase.WAITING_FOR_B, selection.phase());
        assertFalse(selection.complete());

        assertEquals(DriveSegmentSelection.ClickResult.SEGMENT_COMMITTED,
                selection.clickNearestSample(40, 40 * SECOND));
        assertEquals(10, selection.indexA());
        assertEquals(40, selection.indexB());
        assertTrue(selection.userDefined());
        assertEquals(DriveSegmentSelection.Phase.SEGMENT_SELECTED, selection.phase());
        assertTrue(selection.complete());
        assertFalse(selection.selectMode());
        assertEquals(10 * SECOND, selection.startTimestampNanos());
        assertEquals(40 * SECOND, selection.endTimestampNanos());
        assertEquals(30 * SECOND, selection.activeSegment().durationNanos());
        assertNotEquals(selection.indexA(), selection.indexB());
        assertNotEquals(selection.startTimestampNanos(), selection.endTimestampNanos());
    }

    @Test
    void sample100AndSample500YieldPositiveDuration() {
        DriveSegmentSelection selection = new DriveSegmentSelection();
        selection.applyFullDrive(0, 600, 0L, 600 * SECOND);
        selection.enterSelectMode();
        selection.clickNearestSample(100, 100 * SECOND);
        assertEquals(DriveSegmentSelection.ClickResult.SEGMENT_COMMITTED,
                selection.clickNearestSample(500, 500 * SECOND));

        assertEquals(100, selection.indexA());
        assertEquals(500, selection.indexB());
        assertTrue(selection.startTimestampNanos() != selection.endTimestampNanos());
        assertTrue(selection.activeSegment().durationNanos() > 0);
    }

    @Test
    void reversedClicksAreNormalized() {
        DriveSegmentSelection selection = new DriveSegmentSelection();
        selection.applyFullDrive(0, 80, 0L, 80 * SECOND);
        selection.enterSelectMode();
        selection.clickNearestSample(70, 70 * SECOND);
        selection.clickNearestSample(20, 20 * SECOND);

        assertEquals(20, selection.indexA());
        assertEquals(70, selection.indexB());
        assertEquals(20 * SECOND, selection.startTimestampNanos());
        assertEquals(70 * SECOND, selection.endTimestampNanos());
        DriveSegmentSelection.ActiveSegment active = selection.activeSegment();
        assertEquals(20, active.startIndex());
        assertEquals(70, active.endIndex());
        assertEquals(50 * SECOND, active.durationNanos());
    }

    @Test
    void sameSampleDoesNotCompleteTheRange() {
        DriveSegmentSelection selection = new DriveSegmentSelection();
        selection.applyFullDrive(0, 80, 0L, 80 * SECOND);
        selection.enterSelectMode();
        selection.clickNearestSample(15, 15 * SECOND);

        assertEquals(DriveSegmentSelection.ClickResult.WAITING_FOR_DIFFERENT_B,
                selection.clickNearestSample(15, 15 * SECOND));
        assertEquals(DriveSegmentSelection.Phase.WAITING_FOR_B, selection.phase());
        assertTrue(selection.awaitingB());
        assertFalse(selection.complete());
        assertEquals(-1, selection.indexB());
        assertEquals(15, selection.markerAIndex());
        assertEquals(-1, selection.markerBIndex());
    }

    @Test
    void ordinaryClickAfterSegmentLeavesActiveRangeUnchanged() {
        DriveSegmentSelection selection = committed(100, 500);

        assertEquals(DriveSegmentSelection.ClickResult.POINT_ONLY,
                selection.clickNearestSample(250, 250 * SECOND));
        assertEquals(100, selection.indexA());
        assertEquals(500, selection.indexB());
        assertEquals(100 * SECOND, selection.startTimestampNanos());
        assertEquals(500 * SECOND, selection.endTimestampNanos());
        assertEquals(DriveSegmentSelection.Phase.SEGMENT_SELECTED, selection.phase());
    }

    @Test
    void selectSegmentAgainStartsANewPair() {
        DriveSegmentSelection selection = committed(100, 500);
        selection.enterSelectMode();
        assertEquals(DriveSegmentSelection.Phase.WAITING_FOR_A, selection.phase());

        selection.clickNearestSample(12, 12 * SECOND);
        assertEquals(DriveSegmentSelection.Phase.WAITING_FOR_B, selection.phase());
        selection.clickNearestSample(48, 48 * SECOND);

        assertEquals(12, selection.indexA());
        assertEquals(48, selection.indexB());
        assertEquals(12 * SECOND, selection.startTimestampNanos());
        assertEquals(48 * SECOND, selection.endTimestampNanos());
    }

    @Test
    void clearReturnsStartToEnd() {
        DriveSegmentSelection selection = new DriveSegmentSelection();
        selection.applyFullDrive(0, 80, 0L, 80 * SECOND);
        selection.enterSelectMode();
        selection.clickNearestSample(5, 5 * SECOND);
        selection.clickNearestSample(15, 15 * SECOND);
        selection.clear(0, 80, 0L, 80 * SECOND);

        assertEquals(0, selection.indexA());
        assertEquals(80, selection.indexB());
        assertTrue(selection.fullDrive());
        assertFalse(selection.selectMode());
        assertEquals(DriveSegmentSelection.Phase.FULL_DRIVE, selection.phase());
        assertEquals(0L, selection.startTimestampNanos());
        assertEquals(80 * SECOND, selection.endTimestampNanos());
    }

    @Test
    void setAAndSetBUseNearestSampleIndices() {
        DriveSegmentSelection selection = new DriveSegmentSelection();
        selection.applyFullDrive(0, 40, 0L, 40 * SECOND);
        selection.setA(12, 12 * SECOND);
        assertTrue(selection.setB(8, 8 * SECOND));

        assertEquals(8, selection.indexA());
        assertEquals(12, selection.indexB());
        assertTrue(selection.userDefined());
        assertEquals(8 * SECOND, selection.startTimestampNanos());
        assertEquals(12 * SECOND, selection.endTimestampNanos());
    }

    @Test
    void setBSameSampleKeepsWaitingForB() {
        DriveSegmentSelection selection = new DriveSegmentSelection();
        selection.applyFullDrive(0, 40, 0L, 40 * SECOND);
        selection.enterSelectMode();
        selection.clickNearestSample(12, 12 * SECOND);

        assertFalse(selection.setB(12, 12 * SECOND));
        assertEquals(DriveSegmentSelection.Phase.WAITING_FOR_B, selection.phase());
        assertFalse(selection.complete());
    }

    @Test
    void markersMatchActiveSegmentEndpoints() {
        DriveSegmentSelection selection = committed(100, 500);
        DriveSegmentSelection.ActiveSegment active = selection.activeSegment();

        assertEquals(active.startIndex(), selection.markerAIndex());
        assertEquals(active.endIndex(), selection.markerBIndex());
        assertEquals(active.startTimestampNanos(), selection.startTimestampNanos());
        assertEquals(active.endTimestampNanos(), selection.endTimestampNanos());
    }

    private static DriveSegmentSelection committed(int a, int b) {
        DriveSegmentSelection selection = new DriveSegmentSelection();
        selection.applyFullDrive(0, 600, 0L, 600 * SECOND);
        selection.enterSelectMode();
        selection.clickNearestSample(a, a * SECOND);
        selection.clickNearestSample(b, b * SECOND);
        return selection;
    }
}
