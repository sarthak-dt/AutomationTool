package com.stellar.platform.desktop.presentation;

import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import com.stellar.platform.storage.metadata.FileFingerprint;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import com.stellar.platform.storage.metadata.ProcessingPlanFingerprint;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Shared reopen validation for durable lossless artifacts.
 * Incomplete / mismatched artifacts are never treated as reusable.
 */
final class DurableArtifactLifecycle {

    enum Outcome {
        VALID,
        STALE,
        MISSING
    }

    record Check(Outcome outcome, String warning) {
        Check {
            warning = warning == null ? "" : warning;
        }

        boolean reusable() {
            return outcome == Outcome.VALID;
        }
    }

    private DurableArtifactLifecycle() {
    }

    static Check validateOnDisk(
            ProcessingArtifactMetadata artifact,
            Path storeRoot,
            Path measurementPath,
            Path definitionPath,
            String expectedPlanFingerprint
    ) {
        Objects.requireNonNull(artifact, "artifact");
        if (ProcessingArtifactMetadata.STATUS_STALE.equals(artifact.status())
                || ProcessingArtifactMetadata.STATUS_MISSING.equals(artifact.status())) {
            return new Check(Outcome.STALE,
                    "Stale analysis artifact for " + artifact.sourceId() + " — reprocessing required.");
        }
        if (storeRoot == null) {
            return new Check(Outcome.MISSING,
                    "Analysis artifact reference unreadable for " + artifact.sourceId()
                            + " — reprocessing required.");
        }
        if (GraphSignalSessionStore.hasIncompleteFlag(storeRoot)) {
            return new Check(Outcome.MISSING,
                    "Analysis artifact missing or incomplete for " + artifact.sourceId()
                            + " — reprocessing required.");
        }
        if (!LosslessGraphSignalStore.hasValidIndex(storeRoot)) {
            return new Check(Outcome.MISSING,
                    "Analysis artifact missing or incomplete for " + artifact.sourceId()
                            + " — reprocessing required.");
        }
        if (!LosslessGraphSignalStore.segmentsReadable(storeRoot)) {
            return new Check(Outcome.STALE,
                    "Analysis artifact payloads missing for " + artifact.sourceId()
                            + " — reprocessing required.");
        }
        int layout = LosslessGraphSignalStore.indexLayoutVersion(storeRoot);
        boolean currentGeneration = ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT.equals(artifact.artifactVersion())
                && GraphSignalSessionStore.hasCurrentDecoderGeneration(storeRoot);
        boolean validityLayout = layout == LosslessGraphSignalStore.INDEX_LAYOUT_VALIDITY;
        if (!currentGeneration || !validityLayout) {
            if (ProcessingArtifactMetadata.ARTIFACT_VERSION_V2.equals(artifact.artifactVersion())
                    && validityLayout
                    && LosslessGraphSignalStore.payloadMatchesValidityLayout(storeRoot)) {
                return new Check(Outcome.STALE,
                        "Analysis artifact predates the current decoder generation — reprocessing required ("
                                + artifact.sourceId() + "). Original measurement files were not changed.");
            }
            return new Check(Outcome.STALE,
                    "Analysis artifact predates signal-validity storage — reprocessing required ("
                            + artifact.sourceId() + "). Original measurement files were not changed.");
        }
        if (!LosslessGraphSignalStore.payloadMatchesValidityLayout(storeRoot)) {
            return new Check(Outcome.STALE,
                    "Analysis artifact index and sample records disagree — reprocessing required ("
                            + artifact.sourceId() + "). Original measurement files were not changed.");
        }
        if (measurementPath != null && !Files.isRegularFile(measurementPath)) {
            return new Check(Outcome.STALE,
                    "Measurement file is unavailable for " + artifact.sourceId()
                            + " — reprocessing required when the file is restored. The file was not deleted.");
        }
        if (measurementPath != null
                && !artifact.sourceFingerprint().isBlank()
                && !FileFingerprint.matches(artifact.sourceFingerprint(), measurementPath)) {
            return new Check(Outcome.STALE,
                    "Source fingerprint mismatch for " + artifact.sourceId()
                            + " — reprocessing required.");
        }
        if (definitionPath != null
                && !artifact.dbcFingerprint().isBlank()
                && !FileFingerprint.matches(artifact.dbcFingerprint(), definitionPath)) {
            return new Check(Outcome.STALE,
                    "Definition fingerprint mismatch for " + artifact.sourceId()
                            + " — reprocessing required.");
        }
        // Blank expected means this caller is not checking plan provenance (hydrator
        // verify). Seed/reopen against a live job still passes the job fingerprint.
        if (expectedPlanFingerprint != null && !expectedPlanFingerprint.isBlank()
                && !ProcessingPlanFingerprint.matches(artifact.planFingerprint(), expectedPlanFingerprint)) {
            return new Check(Outcome.STALE,
                    "Processing-plan fingerprint mismatch for " + artifact.sourceId()
                            + " — reprocessing required.");
        }
        return new Check(Outcome.VALID, "");
    }
}
