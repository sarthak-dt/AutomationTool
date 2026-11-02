package com.stellar.platform.storage.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

/**
 * Real read/write probe for Analysis Storage. Does not trust {@code Files.isWritable} alone.
 */
public final class AnalysisStorageValidator {

    public enum Status {
        NOT_CONFIGURED,
        VALID,
        INVALID
    }

    public record Result(
            Status status,
            Path userSelectedRoot,
            Path controlledRoot,
            boolean readable,
            boolean writable,
            Long freeSpaceBytes,
            String message
    ) {
        public Result {
            message = message == null ? "" : message;
        }

        public boolean valid() {
            return status == Status.VALID;
        }
    }

    private AnalysisStorageValidator() {
    }

    public static Result notConfigured() {
        return new Result(Status.NOT_CONFIGURED, null, null, false, false, null,
                "Analysis storage must be configured before creating a project.");
    }

    public static Result validate(Path userSelectedRoot) {
        if (userSelectedRoot == null) {
            return notConfigured();
        }
        Path selected = userSelectedRoot.toAbsolutePath().normalize();
        Path controlled = selected.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME).toAbsolutePath().normalize();

        try {
            if (Files.exists(selected) && !Files.isDirectory(selected)) {
                return invalid(selected, controlled, false, false, null,
                        "Selected path is not a directory.");
            }
            Files.createDirectories(controlled);

            if (!Files.isDirectory(controlled)) {
                return invalid(selected, controlled, false, false, null,
                        "Could not create Analysis Storage directory.");
            }

            boolean readable;
            try (var stream = Files.list(controlled)) {
                readable = true;
            } catch (IOException ex) {
                return invalid(selected, controlled, false, false, freeSpace(controlled),
                        "Directory cannot be listed (read access failed).");
            }

            Path probe = controlled.resolve(".stellarai-write-probe-" + UUID.randomUUID());
            byte[] expected = ("stellar-probe-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
            try {
                Files.write(probe, expected, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                byte[] actual = Files.readAllBytes(probe);
                if (!java.util.Arrays.equals(expected, actual)) {
                    return invalid(selected, controlled, readable, false, freeSpace(controlled),
                            "Write probe verification failed (read-back mismatch).");
                }
            } catch (IOException ex) {
                return invalid(selected, controlled, readable, false, freeSpace(controlled),
                        "Write access failed.");
            } finally {
                try {
                    Files.deleteIfExists(probe);
                } catch (IOException ignored) {
                    // Only the probe file is deleted; never recursive cleanup.
                }
            }

            return new Result(Status.VALID, selected, controlled, true, true, freeSpace(controlled),
                    "Analysis Storage is ready.");
        } catch (IOException | RuntimeException ex) {
            return invalid(selected, controlled, false, false, null,
                    concise(ex));
        }
    }

    /** Lightweight recheck after a previously successful probe (no write test). */
    public static Result lightweightCheck(Path userSelectedRoot) {
        if (userSelectedRoot == null) {
            return notConfigured();
        }
        Path selected = userSelectedRoot.toAbsolutePath().normalize();
        Path controlled = selected.resolve(AnalysisStorageRoots.CONTROLLED_DIR_NAME).toAbsolutePath().normalize();
        if (!Files.isDirectory(controlled)) {
            return invalid(selected, controlled, false, false, null,
                    "Analysis Storage directory is unavailable.");
        }
        try (var stream = Files.list(controlled)) {
            return new Result(Status.VALID, selected, controlled, true, true, freeSpace(controlled),
                    "Analysis Storage is available.");
        } catch (IOException ex) {
            return invalid(selected, controlled, false, false, freeSpace(controlled),
                    "Analysis Storage cannot be read.");
        }
    }

    private static Result invalid(
            Path selected, Path controlled, boolean readable, boolean writable, Long free, String message) {
        return new Result(Status.INVALID, selected, controlled, readable, writable, free, message);
    }

    private static Long freeSpace(Path path) {
        try {
            FileStore store = Files.getFileStore(path);
            return store.getUsableSpace();
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private static String concise(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null || msg.isBlank()) {
            return "Storage validation failed.";
        }
        return msg.length() > 160 ? msg.substring(0, 157) + "..." : msg;
    }
}
