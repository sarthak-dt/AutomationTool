package com.stellar.platform.server.decoder;

import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import com.stellar.platform.storage.metadata.ProjectNameNormalizer;

import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads an existing project catalog without migrating or writing it.
 * JDBC is opened with {@code mode=ro} and {@code query_only}. Inventory READY is ignored.
 */
final class ReadOnlyProjectCatalog implements AutoCloseable {

    static final int REQUIRED_SCHEMA = 21;

    private final Connection connection;
    private final Path analysisRoot;
    private final List<Path> allowedRoots;

    private ReadOnlyProjectCatalog(Connection connection, Path analysisRoot, List<Path> allowedRoots) {
        this.connection = connection;
        this.analysisRoot = analysisRoot;
        this.allowedRoots = allowedRoots;
    }

    static ReadOnlyProjectCatalog open(Path database, List<Path> allowedRoots) {
        Path absolute = database.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new DecoderValidationException(
                    "INTERNAL_ERROR",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Project catalog is not available.");
        }
        String slash = absolute.toString().replace('\\', '/');
        if (!slash.startsWith("/")) {
            slash = "/" + slash;
        }
        try {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:file:" + slash + "?mode=ro");
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only=ON");
                statement.execute("PRAGMA busy_timeout=5000");
            }
            return new ReadOnlyProjectCatalog(connection, analysisRoot(absolute), allowedRoots);
        } catch (SQLException ex) {
            throw new DecoderValidationException(
                    "INTERNAL_ERROR",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Project catalog could not be opened.");
        }
    }

    ProjectMatch findProjects(String projectName) {
        String normalized;
        try {
            normalized = ProjectNameNormalizer.normalized(projectName);
        } catch (IllegalArgumentException ex) {
            throw new DecoderValidationException(
                    "INVALID_REQUEST", HttpStatus.BAD_REQUEST, "projectName is empty.");
        }
        requireSchema();
        List<ProjectRef> matches = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT project_id, name
                FROM projects
                WHERE normalized_name=?
                ORDER BY project_id
                """)) {
            ps.setString(1, normalized);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    matches.add(new ProjectRef(rs.getString("project_id"), rs.getString("name")));
                }
            }
        } catch (SQLException ex) {
            throw catalogFailure();
        }
        return new ProjectMatch(List.copyOf(matches));
    }

    List<SourceRow> sourcesFor(String projectId) {
        requireSchema();
        List<FileRow> files = readMeasurementFiles(projectId);
        List<ArtifactRow> artifacts = readArtifacts(projectId);
        List<SourceRow> sources = new ArrayList<>();
        for (FileRow file : files) {
            List<ArtifactRow> completed = artifacts.stream()
                    .filter(artifact -> artifact.sourceId.equals(file.fileId))
                    .filter(ArtifactRow::completedMetadata)
                    .toList();
            Map<Integer, List<ArtifactRow>> byChannel = new LinkedHashMap<>();
            for (ArtifactRow artifact : completed) {
                byChannel.computeIfAbsent(artifact.channelId, ignored -> new ArrayList<>()).add(artifact);
            }
            if (byChannel.values().stream().anyMatch(rows -> rows.size() > 1)) {
                sources.add(SourceRow.ambiguous(file));
                continue;
            }
            if (byChannel.isEmpty()) {
                sources.add(SourceRow.incomplete(file, "NOT_PROCESSED"));
                continue;
            }
            List<Integer> channels = new ArrayList<>(byChannel.keySet());
            channels.sort(Comparator.naturalOrder());
            for (Integer channel : channels) {
                ArtifactRow artifact = byChannel.get(channel).getFirst();
                sources.add(resolve(file, artifact, channels.size() > 1));
            }
        }
        return List.copyOf(sources);
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Read-only handle. A failed close does not change the database.
        }
    }

    private SourceRow resolve(FileRow file, ArtifactRow artifact, boolean qualifyChannel) {
        String sourceId = qualifyChannel ? file.fileId + "#ch-" + artifact.channelId : file.fileId;
        ResolvedStore store = resolveStore(artifact);
        if (store.outside()) {
            return SourceRow.incomplete(file, sourceId, "STORED_RESULTS_NOT_IN_PROJECT_STORAGE", artifact);
        }
        if (store.path() == null) {
            return SourceRow.incomplete(file, sourceId, "STORE_MISSING", artifact);
        }
        if (GraphSignalSessionStore.hasIncompleteFlag(store.path())) {
            return SourceRow.incomplete(file, sourceId, "INCOMPLETE_FLAG", artifact);
        }
        return new SourceRow(
                sourceId,
                file.fileName,
                "COMPLETED",
                "",
                store.path(),
                artifact.sourceFingerprint,
                artifact.dbcFingerprint,
                readDecoderVersion(store.path()),
                file.measurementPath());
    }

    private ResolvedStore resolveStore(ArtifactRow artifact) {
        List<Path> candidates = new ArrayList<>();
        if (artifact.artifactPath != null && !artifact.artifactPath.isBlank() && looksAbsolute(artifact.artifactPath)) {
            candidates.add(Path.of(artifact.artifactPath));
        }
        if (artifact.storageRef != null && artifact.storageRef.startsWith("projects/") && !looksAbsolute(artifact.storageRef)) {
            candidates.add(analysisRoot.resolve(artifact.storageRef.replace('/', java.io.File.separatorChar)));
        }
        if (artifact.artifactPath != null && !artifact.artifactPath.isBlank() && !looksAbsolute(artifact.artifactPath)) {
            candidates.add(analysisRoot.resolve(artifact.artifactPath));
        }
        boolean sawOutside = false;
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (!insideFence(normalized)) {
                sawOutside = true;
                continue;
            }
            if (!Files.isDirectory(normalized)) {
                continue;
            }
            try {
                Path real = normalized.toRealPath();
                if (!insideFence(real)) {
                    sawOutside = true;
                    continue;
                }
                return ResolvedStore.found(real);
            } catch (IOException ex) {
                continue;
            }
        }
        return sawOutside ? ResolvedStore.OUTSIDE : ResolvedStore.MISSING;
    }

    private boolean insideFence(Path path) {
        if (DecoderValidationService.isSameOrChild(analysisRoot, path)) {
            return true;
        }
        for (Path root : allowedRoots) {
            if (DecoderValidationService.isSameOrChild(root, path)) {
                return true;
            }
        }
        return false;
    }

    private void requireSchema() {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT version FROM schema_info ORDER BY version DESC LIMIT 1")) {
            if (!rs.next() || rs.getInt(1) < REQUIRED_SCHEMA) {
                throw schemaUnsupported();
            }
        } catch (DecoderValidationException ex) {
            throw ex;
        } catch (SQLException ex) {
            throw schemaUnsupported();
        }
    }

    private List<FileRow> readMeasurementFiles(String projectId) {
        List<FileRow> files = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT file_id, path, kind
                FROM project_files
                WHERE project_id=?
                ORDER BY updated_at, path, file_id
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String kind = rs.getString("kind");
                    if (kind == null || !kind.toUpperCase(Locale.ROOT).contains("MEASUREMENT")) {
                        continue;
                    }
                    files.add(new FileRow(
                            rs.getString("file_id"),
                            fileName(rs.getString("path")),
                            measurementPath(rs.getString("path"))));
                }
            }
        } catch (SQLException ex) {
            throw catalogFailure();
        }
        return files;
    }

    private List<ArtifactRow> readArtifacts(String projectId) {
        List<ArtifactRow> artifacts = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT source_id, channel_id, artifact_type, artifact_path, artifact_storage_ref,
                       source_fingerprint, dbc_fingerprint, status, job_execution_status, created_at
                FROM processing_artifacts
                WHERE project_id=?
                ORDER BY source_id, channel_id, created_at, artifact_id
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    artifacts.add(new ArtifactRow(
                            rs.getString("source_id"),
                            rs.getInt("channel_id"),
                            rs.getString("artifact_type"),
                            rs.getString("artifact_path"),
                            rs.getString("artifact_storage_ref"),
                            rs.getString("source_fingerprint"),
                            rs.getString("dbc_fingerprint"),
                            rs.getString("status"),
                            rs.getString("job_execution_status")));
                }
            }
        } catch (SQLException ex) {
            throw catalogFailure();
        }
        return artifacts;
    }

    private static String readDecoderVersion(Path root) {
        Path marker = root.resolve(GraphSignalSessionStore.DECODER_GENERATION_FILE);
        try {
            if (Files.isRegularFile(marker)) {
                String text = Files.readString(marker).trim();
                if (!text.isEmpty() && text.length() <= 32) {
                    return text;
                }
            }
        } catch (IOException ignored) {
            return "";
        }
        return "";
    }

    private static Path analysisRoot(Path database) {
        Path parent = database.getParent();
        if (parent != null && parent.getFileName() != null
                && "database".equalsIgnoreCase(parent.getFileName().toString())
                && parent.getParent() != null) {
            return parent.getParent().toAbsolutePath().normalize();
        }
        return parent == null ? database.getParent() : parent.toAbsolutePath().normalize();
    }

    private static String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        Path file = Path.of(path).getFileName();
        return file == null ? "" : file.toString();
    }

    private static boolean looksAbsolute(String value) {
        if (value.length() >= 2 && value.charAt(1) == ':') {
            return true;
        }
        return value.startsWith("/") || value.startsWith("\\\\");
    }

    private static DecoderValidationException schemaUnsupported() {
        return new DecoderValidationException(
                "SCHEMA_UNSUPPORTED",
                HttpStatus.CONFLICT,
                "The project database schema is older than this read-only API. "
                        + "The API does not migrate project databases.");
    }

    private static DecoderValidationException catalogFailure() {
        return new DecoderValidationException(
                "INTERNAL_ERROR",
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Project catalog could not be read.");
    }

    record ProjectRef(String projectId, String projectName) {
    }

    record ProjectMatch(List<ProjectRef> projects) {
    }

    record SourceRow(
            String sourceId,
            String fileName,
            String completeness,
            String reason,
            Path store,
            String sourceFingerprint,
            String dbcFingerprint,
            String decoderVersion,
            Path measurementPath
    ) {
        static SourceRow incomplete(FileRow file, String reason) {
            return incomplete(file, file.fileId, reason, null);
        }

        static SourceRow incomplete(FileRow file, String sourceId, String reason, ArtifactRow artifact) {
            return new SourceRow(
                    sourceId,
                    file.fileName,
                    "INCOMPLETE",
                    reason,
                    null,
                    artifact == null ? "" : artifact.sourceFingerprint,
                    artifact == null ? "" : artifact.dbcFingerprint,
                    "",
                    file.measurementPath());
        }

        static SourceRow ambiguous(FileRow file) {
            return new SourceRow(file.fileId, file.fileName, "AMBIGUOUS", "AMBIGUOUS_ARTIFACT",
                    null, "", "", "", file.measurementPath());
        }

        boolean completed() {
            return "COMPLETED".equals(completeness);
        }
    }

    private record FileRow(String fileId, String fileName, Path measurementPath) {
    }

    private Path measurementPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            Path path = Path.of(raw).toAbsolutePath().normalize();
            if (!insideFence(path) || !Files.isRegularFile(path)) {
                return null;
            }
            Path real = path.toRealPath();
            if (!insideFence(real) || !Files.isRegularFile(real)) {
                return null;
            }
            return real;
        } catch (IOException | RuntimeException ex) {
            return null;
        }
    }

    private record ArtifactRow(
            String sourceId,
            int channelId,
            String artifactType,
            String artifactPath,
            String storageRef,
            String sourceFingerprint,
            String dbcFingerprint,
            String status,
            String jobExecutionStatus
    ) {
        boolean completedMetadata() {
            if (!ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE.equalsIgnoreCase(artifactType)) {
                return false;
            }
            if (!ProcessingArtifactMetadata.STATUS_VALID.equalsIgnoreCase(status)) {
                return false;
            }
            String job = jobExecutionStatus == null ? "" : jobExecutionStatus.trim().toUpperCase(Locale.ROOT);
            if (job.isEmpty()) {
                return true;
            }
            return "SUCCESS".equals(job) || "SUCCESS_WITH_WARNINGS".equals(job);
        }
    }

    private record ResolvedStore(Path path, boolean outside) {
        static final ResolvedStore MISSING = new ResolvedStore(null, false);
        static final ResolvedStore OUTSIDE = new ResolvedStore(null, true);

        static ResolvedStore found(Path path) {
            return new ResolvedStore(path, false);
        }
    }
}
