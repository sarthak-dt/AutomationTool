package com.stellar.platform.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lightweight project-id → project-root map. Not a database and not shared project state.
 * Each project's SQLite file still lives only under that project's {@code .stellar/} directory.
 */
public final class ProjectRootIndex {

    private static final String FILE_NAME = "project-roots.tsv";

    private ProjectRootIndex() {
    }

    public static Path indexFile() {
        if (com.stellar.platform.storage.analysis.AnalysisStorageRoots.isConfigured()) {
            return com.stellar.platform.storage.analysis.AnalysisStorageRoots.configRoot().resolve(FILE_NAME);
        }
        return StellarAiPaths.homeRoot().resolve(FILE_NAME);
    }

    public static void register(String projectId, Path projectRoot) {
        if (projectId == null || projectId.isBlank() || projectRoot == null) {
            return;
        }
        Map<String, Path> rows = readAll();
        rows.put(projectId.trim(), projectRoot.toAbsolutePath().normalize());
        writeAll(rows);
    }

    public static void unregister(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        Map<String, Path> rows = readAll();
        if (rows.remove(projectId.trim()) != null) {
            writeAll(rows);
        }
    }

    public static java.util.Collection<Path> knownRoots() {
        return List.copyOf(readAll().values());
    }

    public static Map<String, Path> entries() {
        return Map.copyOf(readAll());
    }

    public static Optional<Path> resolve(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(readAll().get(projectId.trim()));
    }

    public static Optional<ProjectStoragePaths> locate(String projectId) {
        return resolve(projectId).map(ProjectStoragePaths::of).filter(ProjectStoragePaths::databaseExists);
    }

    private static Map<String, Path> readAll() {
        LinkedHashMap<String, Path> rows = new LinkedHashMap<>();
        Path file = indexFile();
        if (!Files.isRegularFile(file)) {
            return rows;
        }
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                rows.put(line.substring(0, tab), Path.of(line.substring(tab + 1)));
            }
        } catch (IOException ignored) {
            return rows;
        }
        return rows;
    }

    private static void writeAll(Map<String, Path> rows) {
        try {
            Files.createDirectories(indexFile().getParent());
            StringBuilder out = new StringBuilder();
            for (Map.Entry<String, Path> row : rows.entrySet()) {
                out.append(row.getKey()).append('\t').append(row.getValue()).append('\n');
            }
            Files.writeString(indexFile(), out.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot update project root index " + indexFile(), ex);
        }
    }
}
