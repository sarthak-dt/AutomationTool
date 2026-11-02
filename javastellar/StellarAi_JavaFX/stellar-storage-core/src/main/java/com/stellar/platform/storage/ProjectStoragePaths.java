package com.stellar.platform.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Single place that derives project-local metadata paths.
 *
 * <pre>
 *   projectRoot/
 *     .stellar/
 *       stellar.db
 * </pre>
 *
 * Paths are always {@link Path} operations — never string-concatenated slashes.
 */
public final class ProjectStoragePaths {

    public static final String METADATA_DIRECTORY_NAME = ".stellar";
    public static final String DATABASE_FILE_NAME = "stellar.db";

    private final Path projectRoot;

    private ProjectStoragePaths(Path projectRoot) {
        this.projectRoot = projectRoot;
    }

    public static ProjectStoragePaths of(Path projectRoot) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        Path normalized = projectRoot.toAbsolutePath().normalize();
        if (normalized.getNameCount() == 0) {
            throw new IllegalArgumentException("projectRoot must be a concrete directory");
        }
        return new ProjectStoragePaths(normalized);
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public Path metadataDirectory() {
        return projectRoot.resolve(METADATA_DIRECTORY_NAME);
    }

    public Path databasePath() {
        return metadataDirectory().resolve(DATABASE_FILE_NAME);
    }

    public void ensureMetadataDirectory() {
        try {
            Files.createDirectories(metadataDirectory());
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create project metadata directory " + metadataDirectory(), ex);
        }
    }

    public boolean databaseExists() {
        return Files.isRegularFile(databasePath());
    }

    public boolean isLocatedUnderProjectRoot(Path candidate) {
        if (candidate == null) {
            return false;
        }
        Path normalized = candidate.toAbsolutePath().normalize();
        return normalized.startsWith(projectRoot);
    }
}
