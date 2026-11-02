package com.stellar.platform.processing.drive;

/**
 * One evidence-backed finding for a signal in the A→B interval.
 */
public record DriveSignalIssue(
        String signalName,
        DriveSignalIssueKind kind,
        String evidence,
        boolean inferred
) {
    public DriveSignalIssue {
        signalName = signalName == null ? "" : signalName;
        evidence = evidence == null ? "" : evidence;
        if (kind == null) {
            kind = DriveSignalIssueKind.INFERRED_ANOMALY;
        }
    }
}
