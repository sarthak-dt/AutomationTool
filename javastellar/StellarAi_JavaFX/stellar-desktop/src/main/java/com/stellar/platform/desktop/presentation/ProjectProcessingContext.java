package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.ProcessingSummary;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.ProcessingSelection;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Application-level view of the active project's processing lifecycle.
 * Not static global state — owned by {@link DesktopAppService}.
 * <p>
 * Distinguishes the completed result selection from a pending channel edit,
 * and tracks request generations so stale jobs cannot overwrite newer results.
 */
public final class ProjectProcessingContext {

    private final AtomicLong requestGeneration = new AtomicLong();
    private final AtomicBoolean autoNavigateToSignalsPending = new AtomicBoolean(false);
    private final AtomicReference<String> viewingResultChannels = new AtomicReference<>("");
    private final AtomicReference<String> updatingToChannels = new AtomicReference<>("");
    private final AtomicReference<String> lastFailureMessage = new AtomicReference<>("");
    private final AtomicBoolean reprocessFailure = new AtomicBoolean(false);
    private final AtomicBoolean preserveDisplayedResult = new AtomicBoolean(false);

    public long beginRequest() {
        autoNavigateToSignalsPending.set(false);
        lastFailureMessage.set("");
        reprocessFailure.set(false);
        return requestGeneration.incrementAndGet();
    }

    public long currentGeneration() {
        return requestGeneration.get();
    }

    public boolean isCurrentGeneration(long generation) {
        return generation == requestGeneration.get();
    }

    public void markAutoNavigateToSignals() {
        autoNavigateToSignalsPending.set(true);
    }

    public boolean consumeAutoNavigateToSignals() {
        return autoNavigateToSignalsPending.getAndSet(false);
    }

    public void setPreserveDisplayedResult(boolean preserve) {
        preserveDisplayedResult.set(preserve);
    }

    public boolean preserveDisplayedResult() {
        return preserveDisplayedResult.get();
    }

    public void setViewingResultChannels(String label) {
        viewingResultChannels.set(label == null ? "" : label);
    }

    public String viewingResultChannels() {
        return viewingResultChannels.get();
    }

    public void setUpdatingToChannels(String label) {
        updatingToChannels.set(label == null ? "" : label);
    }

    public String updatingToChannels() {
        return updatingToChannels.get();
    }

    public void clearUpdatingBanner() {
        updatingToChannels.set("");
    }

    public void markReprocessFailure(String message) {
        reprocessFailure.set(true);
        lastFailureMessage.set(message == null ? "Reprocessing failed." : message);
        clearUpdatingBanner();
    }

    public boolean reprocessFailure() {
        return reprocessFailure.get();
    }

    public String lastFailureMessage() {
        return lastFailureMessage.get();
    }

    public void clearFailure() {
        reprocessFailure.set(false);
        lastFailureMessage.set("");
    }

    public void clear() {
        requestGeneration.incrementAndGet();
        autoNavigateToSignalsPending.set(false);
        viewingResultChannels.set("");
        updatingToChannels.set("");
        lastFailureMessage.set("");
        reprocessFailure.set(false);
        preserveDisplayedResult.set(false);
    }

    public CompactProgressSnapshot compactProgress(
            ProcessingScreenModel processingModel,
            ChannelSelectionModel selectionModel
    ) {
        Objects.requireNonNull(processingModel, "processingModel");
        ProcessingScreenModel.ProcessingUiSnapshot snap = processingModel.snapshot();
        String updating = updatingToChannels.get();
        String channels = updating.isBlank() ? snap.selectedChannelsSummary() : updating;
        return new CompactProgressSnapshot(
                snap.running(),
                snap.status(),
                channels,
                approximateProgress(snap),
                viewingResultChannels.get(),
                updating,
                reprocessFailure.get(),
                lastFailureMessage.get(),
                selectionModel == null ? ProcessingSelection.none() : selectionModel.toProcessingSelection()
        );
    }

    public static double approximateProgress(ProcessingScreenModel.ProcessingUiSnapshot snap) {
        if (snap == null) {
            return 0.0;
        }
        if (!snap.running()) {
            return snap.status() == ProjectProcessingStatus.COMPLETED
                    || snap.status() == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS
                    ? 1.0
                    : 0.0;
        }
        return switch (snap.stage()) {
            case IDLE -> 0.0;
            case VALIDATING -> 0.08;
            case READING -> 0.18;
            case MF4_METADATA_DISCOVERY -> 0.28;
            case MF4_SCHEMA_BUILDING -> 0.38;
            case MF4_DECODING, MF4_RECOVERY -> 0.55;
            case DBC_DECODING -> 0.78;
            case FINALIZING -> 0.92;
            case COMPLETED -> 1.0;
            case FAILED, CANCELLED -> 0.0;
        };
    }

    public record CompactProgressSnapshot(
            boolean running,
            ProjectProcessingStatus status,
            String processingChannelsLabel,
            double progressFraction,
            String viewingResultChannels,
            String updatingToChannels,
            boolean reprocessFailure,
            String failureMessage,
            ProcessingSelection pendingSelection
    ) {
        public boolean showUpdateBanner() {
            return running && updatingToChannels != null && !updatingToChannels.isBlank();
        }
    }

    public record InventorySnapshot(
            Optional<CanSourceInventory> inventory,
            SourceInventoryMatchState matchState,
            ProcessingSelection pendingSelection,
            Optional<ProcessingSummary> latestSummary
    ) {
    }
}
