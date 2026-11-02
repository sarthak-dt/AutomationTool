package com.stellar.platform.storage.api;

import java.nio.file.Path;

/**
 * Resolves portable artifact storage references to local filesystem paths.
 * Desktop today: {@code ~/.stellarai/...}. Future server: shared artifact root.
 */
@FunctionalInterface
public interface ArtifactStorageResolver {
    Path resolve(String storageRef);

    /** Default local desktop resolver using {@link com.stellar.platform.storage.StellarAiPaths}. */
    static ArtifactStorageResolver localHome() {
        return com.stellar.platform.storage.ArtifactStorageRefs::resolveLocal;
    }
}
