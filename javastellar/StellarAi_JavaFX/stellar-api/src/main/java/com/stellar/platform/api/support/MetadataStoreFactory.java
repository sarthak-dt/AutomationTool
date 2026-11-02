package com.stellar.platform.api.support;

import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a shared SQLite metadata store under a server/desktop data root.
 * One DB holds all projects for the LOCAL/server adapters in this slice.
 */
public final class MetadataStoreFactory {

    private final Path dataRoot;
    private final ConcurrentHashMap<String, Path> projectRoots = new ConcurrentHashMap<>();
    private volatile ProjectMetadataStore store;
    private volatile Path openedRoot;

    public MetadataStoreFactory(Path dataRoot) {
        this.dataRoot = Objects.requireNonNull(dataRoot, "dataRoot").toAbsolutePath().normalize();
    }

    public Path dataRoot() {
        return dataRoot;
    }

    public synchronized ProjectMetadataStore store() {
        Path root = activeRoot();
        if (store != null && openedRoot != null && !openedRoot.equals(root)) {
            close();
        }
        if (store == null) {
            try {
                Files.createDirectories(root);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot create API data root " + root, e);
            }
            openedRoot = root;
            store = new SqliteProjectMetadataStore(root.resolve("stellar-api.db"));
        }
        return store;
    }

    /**
     * Once Analysis Storage is configured, the LOCAL scratch database follows
     * {@code StellarAIAnalysis/temp/desktop-api} even if this factory was built earlier.
     */
    private Path activeRoot() {
        if (AnalysisStorageRoots.isConfigured()) {
            return AnalysisStorageRoots.tempRoot().resolve("desktop-api").toAbsolutePath().normalize();
        }
        return dataRoot;
    }

    public void rememberProjectRoot(String projectId, Path root) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        if (root != null) {
            projectRoots.put(projectId, root.toAbsolutePath().normalize());
        }
    }

    public Path projectRoot(String projectId) {
        Path remembered = projectRoots.get(projectId);
        if (remembered != null) {
            return remembered;
        }
        return dataRoot.resolve(projectId);
    }

    public synchronized void close() {
        if (store != null) {
            try {
                store.close();
            } finally {
                store = null;
            }
        }
    }
}
