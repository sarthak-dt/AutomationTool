package com.stellar.platform.processing.graph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

/**
 * Replaces a durable graph store only after the staged generation proves it is a
 * complete validity-layout artifact. The previous generation is parked beside the
 * destination and put back if promotion fails.
 */
public final class ArtifactStoreCommit {

    private ArtifactStoreCommit() {
    }

    public static void promote(Path stagingRoot, Path finalRoot) {
        if (stagingRoot == null || finalRoot == null) {
            throw new IllegalStateException("Staging and final artifact paths are required");
        }
        Path staging = stagingRoot.toAbsolutePath().normalize();
        Path dest = finalRoot.toAbsolutePath().normalize();
        if (staging.equals(dest)) {
            throw new IllegalStateException(
                    "Refusing artifact commit: staging root equals final root (would destroy samples): " + dest);
        }
        if (!Files.isDirectory(staging)) {
            throw new IllegalStateException("Staging artifact directory missing: " + staging);
        }
        String mismatch = LosslessGraphSignalStore.layoutMismatch(staging);
        if (mismatch != null) {
            throw new IllegalStateException(
                    "Staging artifact failed layout verification — index and sample records disagree: "
                            + mismatch + " @ " + staging);
        }
        Path backup = dest.resolveSibling(dest.getFileName().toString() + ".previous");
        boolean movedAside = false;
        try {
            retireLeftoverBackup(dest, backup);
            if (dest.getParent() != null) {
                Files.createDirectories(dest.getParent());
            }
            if (Files.exists(dest)) {
                Files.move(dest, backup);
                movedAside = true;
            }
            moveTree(staging, dest);
            if (!LosslessGraphSignalStore.payloadMatchesValidityLayout(dest)) {
                throw new IllegalStateException(
                        "Promoted artifact failed layout verification — index and sample records disagree: " + dest);
            }
            Files.deleteIfExists(dest.resolve(GraphSignalSessionStore.INCOMPLETE_FLAG));
            if (movedAside) {
                deleteTreeBestEffort(backup);
            }
        } catch (RuntimeException | IOException ex) {
            try {
                restore(dest, backup, movedAside);
            } catch (IOException restoreFailed) {
                throw new IllegalStateException(
                        "Cannot commit analysis artifact to " + dest + " and could not restore " + backup,
                        restoreFailed);
            }
            if (ex instanceof IllegalStateException illegal) {
                throw illegal;
            }
            throw new IllegalStateException("Cannot commit analysis artifact to " + dest, ex);
        }
    }

    private static void retireLeftoverBackup(Path dest, Path backup) throws IOException {
        if (!Files.exists(backup)) {
            return;
        }
        if (Files.exists(dest) && LosslessGraphSignalStore.payloadMatchesValidityLayout(dest)) {
            deleteTreeOrThrow(backup);
            return;
        }
        throw new IllegalStateException(
                "Previous artifact generation is still parked at " + backup
                        + ". It was not deleted.");
    }

    private static void moveTree(Path source, Path dest) throws IOException {
        try {
            Files.move(source, dest, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.FileSystemException ex) {
            Files.move(source, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restore(Path dest, Path backup, boolean movedAside) throws IOException {
        if (!movedAside || !Files.exists(backup)) {
            return;
        }
        if (Files.exists(dest)) {
            deleteTreeOrThrow(dest);
        }
        Files.move(backup, dest);
    }

    private static void deleteTreeOrThrow(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void deleteTreeBestEffort(Path root) {
        try {
            deleteTreeOrThrow(root);
        } catch (IOException ignored) {
            // The promoted generation is already verified. A leftover backup must not fail the commit.
        }
    }
}
