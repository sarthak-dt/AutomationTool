package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.FileValidationStatus;
import com.stellar.platform.desktop.project.MeasurementSourceCapability;
import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.project.WorkspaceFile;

/**
 * Translates the authoritative per-source states into the single {@link SourceStatus}
 * shown in the Sources workspace.
 *
 * <p>Pure function of already-resident state: it never touches the file system, never parses
 * and never triggers inventory. Availability is supplied by {@link SourceAvailabilityModel},
 * which is only refreshed at explicit, bounded moments.</p>
 */
public final class SourceStatusResolver {

    private SourceStatusResolver() {
    }

    /**
     * @param file           the measurement source
     * @param jobState       current lightweight inspection state for the source
     * @param inventoryReady whether channel discovery for this source has completed
     * @param available      whether the file is still present at its recorded location
     * @param processed      whether this source already produced successful processing artifacts
     */
    public static SourceStatus resolve(WorkspaceFile file,
                                       ProjectInventoryJobState jobState,
                                       boolean inventoryReady,
                                       boolean available,
                                       boolean processed) {
        if (file == null) {
            return SourceStatus.NEEDS_ATTENTION;
        }
        if (!available) {
            return SourceStatus.UNAVAILABLE;
        }
        // A format the product does not read yet is an engineer-visible problem, not a transient one.
        if (!file.kind().implemented() || file.status() == FileValidationStatus.UNSUPPORTED) {
            return SourceStatus.NEEDS_ATTENTION;
        }
        if (file.status() == FileValidationStatus.INVALID) {
            return SourceStatus.NEEDS_ATTENTION;
        }
        // Lifecycle processing outcome is independent of discovered channel / frame counts.
        if (processed) {
            return SourceStatus.PROCESSED;
        }
        if (MeasurementSourceCapability.supportsDirectProcessing(file.kind())
                && file.status().processingAllowed()) {
            return SourceStatus.READY;
        }
        // Discovered channels are the real readiness signal; the job queue only describes progress.
        if (inventoryReady) {
            return SourceStatus.READY;
        }
        ProjectInventoryJobState state = jobState == null ? ProjectInventoryJobState.ADDED : jobState;
        return switch (state) {
            case ADDED, WAITING, INDEXING, READY -> SourceStatus.READING;
            case FAILED, REQUIRES_ATTENTION, UNSUPPORTED, CANCELLED -> SourceStatus.NEEDS_ATTENTION;
        };
    }
}
