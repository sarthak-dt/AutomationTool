package com.stellar.platform.processing;

/**
 * Streaming-friendly counters for a measurement decode run.
 */
public final class ProcessingStatistics {

    private long sourceFramesRead;
    private long validFrames;
    private long recoveredFrames;
    private long malformedSkippedFrames;
    private long standardFrames;
    private long extendedFrames;
    private long dbcMatchedFrames;
    private long dbcUnmatchedFrames;
    private long signalsEmitted;
    private long warnings;
    private long errors;
    private long uniqueDiagnosticCategories;
    private long totalDiagnosticOccurrences;
    private long nonCanGroupsSkipped;
    private long nativeSignalsReceived;
    private long dbcSignalsReceived;
    private long framesSelectedForDecode;
    private long framesSkippedBySelection;

    public void recordSourceFrame() {
        sourceFramesRead++;
    }

    public void recordValidFrame() {
        validFrames++;
    }

    public void recordRecoveredFrame() {
        recoveredFrames++;
    }

    public void recordMalformedSkipped() {
        malformedSkippedFrames++;
    }

    public void recordStandardFrame() {
        standardFrames++;
    }

    public void recordExtendedFrame() {
        extendedFrames++;
    }

    public void recordDbcMatched() {
        dbcMatchedFrames++;
    }

    public void recordDbcUnmatched() {
        dbcUnmatchedFrames++;
    }

    public void recordFrameSelectedForDecode() {
        framesSelectedForDecode++;
    }

    public void recordFrameSkippedBySelection() {
        framesSkippedBySelection++;
    }

    public void recordSignals(int count) {
        signalsEmitted += Math.max(0, count);
    }

    public void recordNativeSignals(int count) {
        int n = Math.max(0, count);
        nativeSignalsReceived += n;
        signalsEmitted += n;
    }

    public void recordDbcSignals(int count) {
        int n = Math.max(0, count);
        dbcSignalsReceived += n;
        signalsEmitted += n;
    }

    public void recordWarning() {
        warnings++;
    }

    public void recordError() {
        errors++;
    }

    public void recordNonCanGroupSkipped() {
        nonCanGroupsSkipped++;
    }

    public void setDiagnosticTotals(long uniqueCategories, long totalOccurrences) {
        this.uniqueDiagnosticCategories = Math.max(0L, uniqueCategories);
        this.totalDiagnosticOccurrences = Math.max(0L, totalOccurrences);
    }

    public long sourceFramesRead() {
        return sourceFramesRead;
    }

    public long validFrames() {
        return validFrames;
    }

    public long recoveredFrames() {
        return recoveredFrames;
    }

    public long malformedSkippedFrames() {
        return malformedSkippedFrames;
    }

    public long standardFrames() {
        return standardFrames;
    }

    public long extendedFrames() {
        return extendedFrames;
    }

    public long dbcMatchedFrames() {
        return dbcMatchedFrames;
    }

    public long dbcUnmatchedFrames() {
        return dbcUnmatchedFrames;
    }

    public long signalsEmitted() {
        return signalsEmitted;
    }

    public long warnings() {
        return warnings;
    }

    public long errors() {
        return errors;
    }

    public long uniqueDiagnosticCategories() {
        return uniqueDiagnosticCategories;
    }

    public long totalDiagnosticOccurrences() {
        return totalDiagnosticOccurrences;
    }

    public long nonCanGroupsSkipped() {
        return nonCanGroupsSkipped;
    }

    public long nativeSignalsReceived() {
        return nativeSignalsReceived;
    }

    public long dbcSignalsReceived() {
        return dbcSignalsReceived;
    }

    public long framesSelectedForDecode() {
        return framesSelectedForDecode;
    }

    public long framesSkippedBySelection() {
        return framesSkippedBySelection;
    }

    public ProcessingStatistics snapshot() {
        ProcessingStatistics copy = new ProcessingStatistics();
        copy.sourceFramesRead = sourceFramesRead;
        copy.validFrames = validFrames;
        copy.recoveredFrames = recoveredFrames;
        copy.malformedSkippedFrames = malformedSkippedFrames;
        copy.standardFrames = standardFrames;
        copy.extendedFrames = extendedFrames;
        copy.dbcMatchedFrames = dbcMatchedFrames;
        copy.dbcUnmatchedFrames = dbcUnmatchedFrames;
        copy.signalsEmitted = signalsEmitted;
        copy.warnings = warnings;
        copy.errors = errors;
        copy.uniqueDiagnosticCategories = uniqueDiagnosticCategories;
        copy.totalDiagnosticOccurrences = totalDiagnosticOccurrences;
        copy.nonCanGroupsSkipped = nonCanGroupsSkipped;
        copy.nativeSignalsReceived = nativeSignalsReceived;
        copy.dbcSignalsReceived = dbcSignalsReceived;
        copy.framesSelectedForDecode = framesSelectedForDecode;
        copy.framesSkippedBySelection = framesSkippedBySelection;
        return copy;
    }

    @Override
    public String toString() {
        return "ProcessingStatistics{"
                + "sourceFramesRead=" + sourceFramesRead
                + ", validFrames=" + validFrames
                + ", recoveredFrames=" + recoveredFrames
                + ", malformedSkippedFrames=" + malformedSkippedFrames
                + ", standardFrames=" + standardFrames
                + ", extendedFrames=" + extendedFrames
                + ", dbcMatchedFrames=" + dbcMatchedFrames
                + ", dbcUnmatchedFrames=" + dbcUnmatchedFrames
                + ", signalsEmitted=" + signalsEmitted
                + ", warnings=" + warnings
                + ", errors=" + errors
                + ", uniqueDiagnosticCategories=" + uniqueDiagnosticCategories
                + ", totalDiagnosticOccurrences=" + totalDiagnosticOccurrences
                + ", nonCanGroupsSkipped=" + nonCanGroupsSkipped
                + ", nativeSignalsReceived=" + nativeSignalsReceived
                + ", dbcSignalsReceived=" + dbcSignalsReceived
                + ", framesSelectedForDecode=" + framesSelectedForDecode
                + ", framesSkippedBySelection=" + framesSkippedBySelection
                + '}';
    }
}
