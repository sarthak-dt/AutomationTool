package com.stellar.platform.desktop.presentation;

/**
 * Outcome of pointing a project source at a newly located file.
 * Same filename is never enough when a processed fingerprint exists.
 */
public enum RelocateSourceResult {
    REATTACHED,
    REJECTED,
    DIFFERENT_CONTENT;

    public boolean accepted() {
        return this == REATTACHED;
    }
}
