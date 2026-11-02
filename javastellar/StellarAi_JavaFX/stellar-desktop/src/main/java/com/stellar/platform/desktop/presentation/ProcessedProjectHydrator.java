package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.processing.execution.ProcessingResultRegistry;
import com.stellar.platform.storage.ArtifactStorageRefs;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Verifies persisted processing artifact manifests on reopen.
 * Heavy signal samples are opened later via Analysis Storage (not here).
 */
public final class ProcessedProjectHydrator {

    public record HydrationResult(
            int validArtifacts,
            int staleArtifacts,
            int missingArtifacts,
            MultiSourceProcessingResult batch,
            List<String> warnings
    ) {
        public HydrationResult {
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }

        public boolean hasValidSignals() {
            return validArtifacts > 0;
        }

        public boolean reprocessingRequired() {
            return staleArtifacts > 0 || missingArtifacts > 0
                    || warnings.stream().anyMatch(w -> w.toLowerCase().contains("reprocessing required"));
        }
    }

    private final DesktopAppService appService;

    public ProcessedProjectHydrator(DesktopAppService appService) {
        this.appService = Objects.requireNonNull(appService, "appService");
    }

    /**
     * Compatibility entry — counts manifests only; does not load sample arrays.
     */
    public HydrationResult hydrate(
            List<ProcessingArtifactMetadata> artifacts,
            ProcessingResultRegistry registry
    ) {
        Objects.requireNonNull(registry, "registry");
        return verifyArtifacts(artifacts == null ? List.of() : artifacts, List.of());
    }

    public HydrationResult verifySnapshot(SavedProjectSnapshot snapshot) {
        PersistedSourceVerifier.Result verified = PersistedSourceVerifier.verify(snapshot);
        return verifyArtifacts(snapshot.processingArtifacts(), verified.warnings());
    }

    private HydrationResult verifyArtifacts(
            List<ProcessingArtifactMetadata> artifacts,
            List<String> baseWarnings
    ) {
        List<String> warnings = new ArrayList<>(baseWarnings);
        int valid = 0;
        int stale = 0;
        int missing = 0;
        boolean storageOk = appService.analysisStorage().isValid();

        for (ProcessingArtifactMetadata artifact : artifacts) {
            if (artifact == null) {
                continue;
            }
            if (!ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE.equals(artifact.artifactType())) {
                continue;
            }
            if (!storageOk) {
                missing++;
                continue;
            }
            Path root;
            try {
                root = ArtifactStorageRefs.resolveLocal(artifact.resolutionKey());
            } catch (RuntimeException ex) {
                missing++;
                warnings.add("Analysis artifact reference unreadable for " + artifact.sourceId()
                        + " — reprocessing required.");
                continue;
            }
            Path measurement = appService.workspace().findFile(artifact.sourceId())
                    .map(f -> f.path())
                    .orElse(null);
            Path definition = artifact.definitionId() == null || artifact.definitionId().isBlank()
                    ? null
                    : appService.workspace().findFile(artifact.definitionId()).map(f -> f.path()).orElse(null);
            DurableArtifactLifecycle.Check check = DurableArtifactLifecycle.validateOnDisk(
                    artifact, root, measurement, definition, "");
            // Plan-fingerprint is enforced at seed time against the live ProcessingJob intent.
            switch (check.outcome()) {
                case VALID -> valid++;
                case STALE -> {
                    stale++;
                    if (!check.warning().isBlank()) {
                        warnings.add(check.warning());
                    }
                }
                case MISSING -> {
                    missing++;
                    if (!check.warning().isBlank()) {
                        warnings.add(check.warning());
                    }
                }
            }
        }

        if (!storageOk && !artifacts.isEmpty()) {
            warnings.add("Analysis storage unavailable — processed signal artifacts cannot be loaded.");
        } else if (stale > 0 || missing > 0) {
            warnings.add("Reprocessing required for one or more sources before signals are trustworthy.");
        }

        return new HydrationResult(valid, stale, missing, null, warnings);
    }
}
