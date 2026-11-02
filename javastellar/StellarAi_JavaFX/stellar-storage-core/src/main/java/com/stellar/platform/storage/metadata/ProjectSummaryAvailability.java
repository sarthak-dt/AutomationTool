package com.stellar.platform.storage.metadata;

/**
 * Result of loading dashboard counts for one saved project.
 * Distinguishes a genuine empty project from a database that could not be read.
 */
public enum ProjectSummaryAvailability {
    /** Project-local DB opened and counts were read. Zero is a real zero. */
    AVAILABLE,
    /** Registry/index knows the project but {@code .stellar/stellar.db} is absent. */
    MISSING_DB,
    /** A file exists at the expected path but it is not a readable Stellar metadata DB. */
    INVALID_DB,
    /** Counts were read from the pre-project-local global metadata DB. */
    LEGACY,
    /** Unexpected I/O or SQL failure while reading the project-local DB. */
    ERROR;

    public boolean countsAvailable() {
        return this == AVAILABLE || this == LEGACY;
    }
}
