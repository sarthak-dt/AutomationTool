package com.stellar.platform.processing.runtime;

import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Admission control for local processing. Blocks a job when Analysis Storage cannot
 * accept more work instead of failing later as an internal error.
 */
public final class LocalResourceManager {

    public static final long DEFAULT_MIN_FREE_BYTES = 512L * 1024L * 1024L;

    private final Long simulatedFreeBytes;

    public LocalResourceManager() {
        this(null);
    }

    /** Test hook: pretend the volume has this many free bytes. */
    public LocalResourceManager(Long simulatedFreeBytes) {
        this.simulatedFreeBytes = simulatedFreeBytes;
    }

    public record Admission(boolean allowed, String reason) {
        public static Admission permit() {
            return new Admission(true, "");
        }

        public static Admission deny(String reason) {
            return new Admission(false, reason);
        }
    }

    public Admission admit(Path analysisStorageRoot, long sourceBytes, int queueDepth, int queueCapacity) {
        if (queueCapacity > 0 && queueDepth >= queueCapacity) {
            return Admission.deny("Processing queue is full");
        }
        if (analysisStorageRoot == null) {
            return Admission.deny("Analysis Storage is not configured");
        }
        try {
            Path root = Files.exists(analysisStorageRoot) ? analysisStorageRoot : analysisStorageRoot.getParent();
            if (root == null || !Files.exists(root)) {
                return Admission.deny("Analysis Storage is not available");
            }
            FileStore store = Files.getFileStore(root);
            long free = simulatedFreeBytes != null ? simulatedFreeBytes : store.getUsableSpace();
            long needed = Math.max(DEFAULT_MIN_FREE_BYTES, sourceBytes);
            if (free < needed) {
                return Admission.deny("Insufficient analysis storage space");
            }
            return Admission.permit();
        } catch (Exception e) {
            return Admission.deny("Analysis Storage is not available");
        }
    }
}
