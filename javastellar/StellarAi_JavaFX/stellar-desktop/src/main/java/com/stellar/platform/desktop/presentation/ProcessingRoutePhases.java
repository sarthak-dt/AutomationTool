package com.stellar.platform.desktop.presentation;

import java.util.Optional;

/**
 * The semantic contract between a transient processing {@link WorkspaceRoute} and the phase the
 * Processing workspace must present when that route opens.
 *
 * <p>A route states an intent; live model state states the truth. Where the two disagree the truth
 * wins — opening Processing Result on a project whose run is still executing shows the run, not a
 * stale result.</p>
 */
public final class ProcessingRoutePhases {

    private ProcessingRoutePhases() {
    }

    /** The phase a route asks for, or empty when the route does not target the Processing host. */
    public static Optional<ProcessingWorkspacePhase> intentOf(WorkspaceRoute route) {
        if (route == null) {
            return Optional.empty();
        }
        return switch (route) {
            case REVIEW_AND_PROCESS -> Optional.of(ProcessingWorkspacePhase.REVIEW);
            case PROCESSING_RESUME -> Optional.of(ProcessingWorkspacePhase.INTERRUPTED);
            case PROCESSING_RESULT -> Optional.of(ProcessingWorkspacePhase.COMPLETED);
            default -> Optional.empty();
        };
    }

    /**
     * Resolves the phase actually presented for a route.
     *
     * @param route       requested destination
     * @param mode        the current page mode owned by the selection workspace model
     * @param interrupted the last persisted run for this project never reached a terminal status
     */
    public static ProcessingWorkspacePhase resolve(
            WorkspaceRoute route,
            ProcessingPageMode mode,
            boolean interrupted
    ) {
        // The route selects how the model is prepared before this call; once prepared, the model
        // is the only thing that decides what is shown. Deriving the phase from the route as well
        // would let the two disagree — for example hiding a result that just completed under the
        // Review route the user originally arrived on.
        return ProcessingWorkspacePhase.of(mode, interrupted);
    }
}
