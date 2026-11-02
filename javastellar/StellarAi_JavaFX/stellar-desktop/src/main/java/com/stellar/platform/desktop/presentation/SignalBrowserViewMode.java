package com.stellar.platform.desktop.presentation;

/**
 * Signal Browser presentation only. Never changes the physical signal catalog or sample data.
 */
public enum SignalBrowserViewMode {
    /** Safe default: every physical source/channel signal instance is visible. */
    ALL_INSTANCES,
    /**
     * Optional convenience view: hide later entries with the exact same signal name and keep a
     * deterministic first occurrence. Hidden rows remain in the physical catalog untouched.
     */
    UNIQUE_SIGNAL_NAMES
}
