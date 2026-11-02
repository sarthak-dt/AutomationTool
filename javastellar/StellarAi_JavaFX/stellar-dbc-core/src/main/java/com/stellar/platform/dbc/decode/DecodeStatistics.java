package com.stellar.platform.dbc.decode;

/**
 * Aggregate decode statistics for a run / batch.
 */
public final class DecodeStatistics {

    private long totalFrames;
    private long matchedFrames;
    private long unmatchedFrames;
    private long decodedSignals;
    private long malformedFrames;

    public void recordFrame() {
        totalFrames++;
    }

    public void recordMatched() {
        matchedFrames++;
    }

    public void recordUnmatched() {
        unmatchedFrames++;
    }

    public void recordSignals(int count) {
        decodedSignals += count;
    }

    public void recordMalformed() {
        malformedFrames++;
    }

    public long totalFrames() {
        return totalFrames;
    }

    public long matchedFrames() {
        return matchedFrames;
    }

    public long unmatchedFrames() {
        return unmatchedFrames;
    }

    public long decodedSignals() {
        return decodedSignals;
    }

    public long malformedFrames() {
        return malformedFrames;
    }

    public DecodeStatistics snapshot() {
        DecodeStatistics copy = new DecodeStatistics();
        copy.totalFrames = totalFrames;
        copy.matchedFrames = matchedFrames;
        copy.unmatchedFrames = unmatchedFrames;
        copy.decodedSignals = decodedSignals;
        copy.malformedFrames = malformedFrames;
        return copy;
    }
}
