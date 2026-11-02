package com.stellar.platform.storage.metadata;

/** Whether a stored checkpoint may still be used as a resume point. */
public enum ProcessingCheckpointState {
    /** Points at a committed batch boundary and may be resumed if identity still matches. */
    RESUMABLE,
    /** The job ran to completion; the checkpoint is kept only as a record. */
    COMPLETED,
    /** Identity no longer matches, or the user cancelled. Must never be resumed. */
    INVALIDATED
}
