package com.stellar.platform.processing.drive;

/**
 * Explicit A/B segment state machine.
 *
 * <p>{@link Phase#FULL_DRIVE} is first GPS sample → last GPS sample. {@code Select Segment} moves
 * to {@link Phase#WAITING_FOR_A}; the first valid click commits A, the second distinct click
 * commits B and publishes {@link ActiveSegment}. Further ordinary clicks must not replace A or B.
 */
public final class DriveSegmentSelection {

    public enum Phase {
        FULL_DRIVE,
        WAITING_FOR_A,
        WAITING_FOR_B,
        SEGMENT_SELECTED
    }

    public enum ClickResult {
        IGNORED,
        A_COMMITTED,
        WAITING_FOR_DIFFERENT_B,
        SEGMENT_COMMITTED,
        POINT_ONLY
    }

    /**
     * Committed analysis interval. Marker A/B and {@code DriveSegmentAnalysisService} must both
     * read these endpoints — never a separately stored visual pair.
     */
    public record ActiveSegment(
            int startIndex,
            int endIndex,
            long startTimestampNanos,
            long endTimestampNanos,
            boolean fullDrive
    ) {
        public long durationNanos() {
            return Math.max(0L, endTimestampNanos - startTimestampNanos);
        }

        public boolean zeroLength() {
            return startIndex == endIndex || startTimestampNanos == endTimestampNanos;
        }
    }

    private Phase phase = Phase.FULL_DRIVE;
    private int indexA = -1;
    private int indexB = -1;
    private long timestampA = Long.MIN_VALUE;
    private long timestampB = Long.MIN_VALUE;
    private int pendingAIndex = -1;
    private long pendingATimestamp = Long.MIN_VALUE;
    private boolean userDefined;

    public void applyFullDrive(int firstIndex, int lastIndex) {
        applyFullDrive(firstIndex, lastIndex, Long.MIN_VALUE, Long.MIN_VALUE);
    }

    public void applyFullDrive(int firstIndex, int lastIndex, long firstTimestamp, long lastTimestamp) {
        indexA = firstIndex;
        indexB = lastIndex;
        timestampA = firstTimestamp;
        timestampB = lastTimestamp;
        pendingAIndex = -1;
        pendingATimestamp = Long.MIN_VALUE;
        awaitingCleared();
        userDefined = false;
        phase = Phase.FULL_DRIVE;
        normalize();
    }

    public void enterSelectMode() {
        phase = Phase.WAITING_FOR_A;
        pendingAIndex = -1;
        pendingATimestamp = Long.MIN_VALUE;
    }

    public void exitSelectMode() {
        if (phase == Phase.WAITING_FOR_A || phase == Phase.WAITING_FOR_B) {
            if (userDefined && indexA >= 0 && indexB >= 0 && indexA != indexB) {
                phase = Phase.SEGMENT_SELECTED;
            } else {
                phase = Phase.FULL_DRIVE;
            }
        }
        pendingAIndex = -1;
        pendingATimestamp = Long.MIN_VALUE;
    }

    public boolean selectMode() {
        return phase == Phase.WAITING_FOR_A || phase == Phase.WAITING_FOR_B;
    }

    public boolean awaitingB() {
        return phase == Phase.WAITING_FOR_B;
    }

    public Phase phase() {
        return phase;
    }

    public boolean userDefined() {
        return userDefined;
    }

    public boolean fullDrive() {
        return phase == Phase.FULL_DRIVE;
    }

    public int indexA() {
        return phase == Phase.WAITING_FOR_B ? pendingAIndex : indexA;
    }

    public int indexB() {
        return phase == Phase.WAITING_FOR_B ? -1 : indexB;
    }

    public long timestampA() {
        return phase == Phase.WAITING_FOR_B ? pendingATimestamp : timestampA;
    }

    public long timestampB() {
        return phase == Phase.WAITING_FOR_B ? Long.MIN_VALUE : timestampB;
    }

    public int lowIndex() {
        if (phase == Phase.WAITING_FOR_B) {
            return pendingAIndex;
        }
        if (indexA < 0 || indexB < 0) {
            return indexA;
        }
        return Math.min(indexA, indexB);
    }

    public int highIndex() {
        if (phase == Phase.WAITING_FOR_B) {
            return pendingAIndex;
        }
        if (indexA < 0 || indexB < 0) {
            return indexB;
        }
        return Math.max(indexA, indexB);
    }

    public int markerAIndex() {
        if (phase == Phase.WAITING_FOR_B) {
            return pendingAIndex;
        }
        return indexA;
    }

    public int markerBIndex() {
        if (phase == Phase.WAITING_FOR_B) {
            return -1;
        }
        return indexB;
    }

    public ActiveSegment activeSegment() {
        if (phase == Phase.WAITING_FOR_B) {
            return new ActiveSegment(pendingAIndex, -1, pendingATimestamp, Long.MIN_VALUE, false);
        }
        return new ActiveSegment(lowIndex(), highIndex(), startTimestampNanos(), endTimestampNanos(), fullDrive());
    }

    public long startTimestampNanos() {
        if (phase == Phase.WAITING_FOR_B) {
            return pendingATimestamp;
        }
        if (timestampA == Long.MIN_VALUE || timestampB == Long.MIN_VALUE) {
            return timestampA;
        }
        return Math.min(timestampA, timestampB);
    }

    public long endTimestampNanos() {
        if (phase == Phase.WAITING_FOR_B) {
            return pendingATimestamp;
        }
        if (timestampA == Long.MIN_VALUE || timestampB == Long.MIN_VALUE) {
            return timestampB;
        }
        return Math.max(timestampA, timestampB);
    }

    /**
     * @return {@code true} when A/B became a completed user segment (second distinct click)
     */
    public boolean clickNearestSample(int nearestIndex) {
        return clickNearestSample(nearestIndex, Long.MIN_VALUE) == ClickResult.SEGMENT_COMMITTED;
    }

    public ClickResult clickNearestSample(int nearestIndex, long timestampNanos) {
        if (nearestIndex < 0) {
            return ClickResult.IGNORED;
        }
        if (phase == Phase.FULL_DRIVE || phase == Phase.SEGMENT_SELECTED) {
            return ClickResult.POINT_ONLY;
        }
        if (phase == Phase.WAITING_FOR_A) {
            pendingAIndex = nearestIndex;
            pendingATimestamp = timestampNanos;
            phase = Phase.WAITING_FOR_B;
            return ClickResult.A_COMMITTED;
        }
        if (phase != Phase.WAITING_FOR_B) {
            return ClickResult.IGNORED;
        }
        if (sameSample(nearestIndex, timestampNanos)) {
            return ClickResult.WAITING_FOR_DIFFERENT_B;
        }
        indexA = pendingAIndex;
        timestampA = pendingATimestamp;
        indexB = nearestIndex;
        timestampB = timestampNanos;
        pendingAIndex = -1;
        pendingATimestamp = Long.MIN_VALUE;
        userDefined = true;
        phase = Phase.SEGMENT_SELECTED;
        normalize();
        return ClickResult.SEGMENT_COMMITTED;
    }

    public void setA(int nearestIndex) {
        setA(nearestIndex, Long.MIN_VALUE);
    }

    public void setA(int nearestIndex, long timestampNanos) {
        if (nearestIndex < 0) {
            return;
        }
        indexA = nearestIndex;
        timestampA = timestampNanos;
        if (indexB >= 0 && indexB != indexA) {
            userDefined = true;
            phase = Phase.SEGMENT_SELECTED;
            pendingAIndex = -1;
            pendingATimestamp = Long.MIN_VALUE;
            normalize();
            return;
        }
        indexB = -1;
        timestampB = Long.MIN_VALUE;
        pendingAIndex = nearestIndex;
        pendingATimestamp = timestampNanos;
        phase = Phase.WAITING_FOR_B;
        userDefined = true;
    }

    public void setB(int nearestIndex) {
        setB(nearestIndex, Long.MIN_VALUE);
    }

    /**
     * @return {@code false} when B is the same sample as A and the range must not complete
     */
    public boolean setB(int nearestIndex, long timestampNanos) {
        if (nearestIndex < 0) {
            return false;
        }
        if (indexA < 0 && pendingAIndex < 0) {
            pendingAIndex = nearestIndex;
            pendingATimestamp = timestampNanos;
            phase = Phase.WAITING_FOR_B;
            userDefined = true;
            return false;
        }
        int aIndex;
        long aTs;
        if (phase == Phase.WAITING_FOR_B && pendingAIndex >= 0) {
            aIndex = pendingAIndex;
            aTs = pendingATimestamp;
        } else {
            aIndex = indexA >= 0 ? indexA : pendingAIndex;
            aTs = timestampA != Long.MIN_VALUE ? timestampA : pendingATimestamp;
        }
        if (nearestIndex == aIndex || (timestampNanos != Long.MIN_VALUE && aTs != Long.MIN_VALUE && timestampNanos == aTs)) {
            if (pendingAIndex < 0) {
                pendingAIndex = aIndex;
                pendingATimestamp = aTs;
            }
            phase = Phase.WAITING_FOR_B;
            return false;
        }
        indexA = aIndex;
        timestampA = aTs;
        indexB = nearestIndex;
        timestampB = timestampNanos;
        pendingAIndex = -1;
        pendingATimestamp = Long.MIN_VALUE;
        userDefined = true;
        phase = Phase.SEGMENT_SELECTED;
        normalize();
        return true;
    }

    public void clear(int firstIndex, int lastIndex) {
        applyFullDrive(firstIndex, lastIndex);
    }

    public void clear(int firstIndex, int lastIndex, long firstTimestamp, long lastTimestamp) {
        applyFullDrive(firstIndex, lastIndex, firstTimestamp, lastTimestamp);
    }

    public boolean complete() {
        if (indexA < 0 || indexB < 0) {
            return false;
        }
        if (phase == Phase.WAITING_FOR_A || phase == Phase.WAITING_FOR_B) {
            return false;
        }
        if (phase == Phase.SEGMENT_SELECTED && (indexA == indexB || zeroLengthTimestamps())) {
            return false;
        }
        return phase == Phase.FULL_DRIVE || phase == Phase.SEGMENT_SELECTED;
    }

    private boolean zeroLengthTimestamps() {
        return timestampA != Long.MIN_VALUE
                && timestampB != Long.MIN_VALUE
                && timestampA == timestampB;
    }

    private boolean sameSample(int nearestIndex, long timestampNanos) {
        if (nearestIndex == pendingAIndex) {
            return true;
        }
        return timestampNanos != Long.MIN_VALUE
                && pendingATimestamp != Long.MIN_VALUE
                && timestampNanos == pendingATimestamp;
    }

    private void awaitingCleared() {
        pendingAIndex = -1;
        pendingATimestamp = Long.MIN_VALUE;
    }

    private void normalize() {
        if (indexA >= 0 && indexB >= 0 && indexB < indexA) {
            int swap = indexA;
            indexA = indexB;
            indexB = swap;
            long ts = timestampA;
            timestampA = timestampB;
            timestampB = ts;
        }
    }
}
