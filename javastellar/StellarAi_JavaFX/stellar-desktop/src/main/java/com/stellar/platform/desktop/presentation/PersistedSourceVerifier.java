package com.stellar.platform.desktop.presentation;

import com.stellar.platform.storage.metadata.FileFingerprint;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import com.stellar.platform.storage.metadata.ProjectFileMetadata;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Verifies saved file paths and stored counts without parsing sources or
 * opening decoded signal stores.
 */
public final class PersistedSourceVerifier {

    public record Result(List<String> warnings, int verifiedFiles, int unavailableFiles, int changedFiles) {
        public Result {
            warnings = List.copyOf(warnings == null ? List.of() : warnings);
        }

        public boolean allSourcesMatch() {
            return unavailableFiles == 0 && changedFiles == 0;
        }
    }

    private PersistedSourceVerifier() {
    }

    public static Result verify(SavedProjectSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<String> warnings = new ArrayList<>();
        int verified = 0;
        int unavailable = 0;
        int changed = 0;
        for (ProjectFileMetadata stored : snapshot.files()) {
            Path path;
            try {
                path = Path.of(stored.path()).toAbsolutePath().normalize();
            } catch (RuntimeException ex) {
                unavailable++;
                warnings.add("Invalid saved path: " + stored.path());
                continue;
            }
            if (!Files.isRegularFile(path)) {
                unavailable++;
                warnings.add("Source is no longer available: " + path.getFileName()
                        + ". Stored counts were kept for the dashboard and need a new file path.");
                continue;
            }
            long actualSize;
            try {
                actualSize = Files.size(path);
            } catch (Exception ex) {
                unavailable++;
                warnings.add("Cannot read saved source: " + path.getFileName());
                continue;
            }
            if (stored.sizeBytes() > 0 && actualSize != stored.sizeBytes()) {
                changed++;
                warnings.add("File size changed since last save: " + path.getFileName()
                        + " (stored " + stored.sizeBytes() + " bytes, now " + actualSize + ").");
                continue;
            }
            verified++;
        }
        for (ProcessingArtifactMetadata artifact : snapshot.processingArtifacts()) {
            Path source = sourcePath(snapshot, artifact.sourceId());
            if (source == null) {
                continue;
            }
            if (!Files.isRegularFile(source)) {
                continue;
            }
            String fingerprint = artifact.sourceFingerprint();
            if (fingerprint != null && !fingerprint.isBlank() && !FileFingerprint.matches(fingerprint, source)) {
                changed++;
                warnings.add("Measurement changed since last processing: " + source.getFileName()
                        + ". Stored signal counts may be stale until you process again.");
            }
        }
        if (unavailable > 0 || changed > 0) {
            warnings.add("Reopen verified stored paths and counts only. Process again to rebuild analysis.");
        }
        return new Result(warnings, verified, unavailable, changed);
    }

    private static Path sourcePath(SavedProjectSnapshot snapshot, String fileId) {
        return snapshot.files().stream()
                .filter(f -> fileId.equals(f.fileId()))
                .map(f -> {
                    try {
                        return Path.of(f.path()).toAbsolutePath().normalize();
                    } catch (RuntimeException ex) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }
}
