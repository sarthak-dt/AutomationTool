package com.stellar.platform.desktop.presentation;

/** User-visible project persistence lifecycle (not UI labels). */
public enum ProjectSaveState {
    CLEAN,
    DIRTY,
    SAVING,
    SAVE_FAILED
}
