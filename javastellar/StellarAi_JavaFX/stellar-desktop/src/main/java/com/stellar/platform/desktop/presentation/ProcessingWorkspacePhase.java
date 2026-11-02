package com.stellar.platform.desktop.presentation;

/**
 * Presentation-only phase for the unified Processing Workspace.
 * Maps existing {@link ProcessingPageMode} values; does not replace them.
 *
 * <p>{@link #INTERRUPTED} has no {@code ProcessingPageMode} of its own: a run that ended without
 * a terminal status can only be recognised by combining the live page mode with the persisted run
 * record, which is what {@link #of(ProcessingPageMode, boolean)} does.</p>
 */
public enum ProcessingWorkspacePhase {
    REVIEW,
    RUNNING,
    INTERRUPTED,
    COMPLETED,
    PARTIAL;

    public static ProcessingWorkspacePhase of(ProcessingPageMode mode) {
        if (mode == null) {
            return REVIEW;
        }
        return switch (mode) {
            case SELECTION, PLAN_REVIEW -> REVIEW;
            case EXECUTING -> RUNNING;
            case EXECUTION_RESULT -> COMPLETED;
            case PARTIAL -> PARTIAL;
        };
    }

    /**
     * @param interruptedRun the last persisted run for this project ended while still marked
     *                       running or cancelled, so work remains outstanding
     */
    public static ProcessingWorkspacePhase of(ProcessingPageMode mode, boolean interruptedRun) {
        ProcessingWorkspacePhase phase = of(mode);
        if (!interruptedRun || phase == RUNNING) {
            return phase;
        }
        // Everything the interrupted run was supposed to produce is already present, so there is
        // nothing left to resume: show it as the completed result it actually is.
        if (phase == COMPLETED) {
            return COMPLETED;
        }
        return INTERRUPTED;
    }

    /** True while background execution owns the workspace and navigation must be held. */
    public boolean active() {
        return this == RUNNING;
    }

    /** True when the phase presents outcomes of work that already ran. */
    public boolean showsResults() {
        return this == COMPLETED || this == PARTIAL || this == INTERRUPTED;
    }
}
