package com.stellar.platform.storage.metadata;

import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.ProcessingFailureClass;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;

import com.stellar.platform.storage.StellarAiPaths;
import com.stellar.platform.storage.sqlite.MigrationV3Baseline;
import com.stellar.platform.storage.sqlite.MigrationV4EnterpriseKnowledge;
import com.stellar.platform.storage.sqlite.MigrationV5UniqueProjectNames;
import com.stellar.platform.storage.sqlite.MigrationV6MapRouteState;
import com.stellar.platform.storage.sqlite.MigrationV7GraphInvestigation;
import com.stellar.platform.storage.sqlite.MigrationV8LastWorkspace;
import com.stellar.platform.storage.sqlite.MigrationV9ArtifactJobOutcome;
import com.stellar.platform.storage.sqlite.MigrationV10PlanFingerprint;
import com.stellar.platform.storage.sqlite.MigrationV11WorkspaceCheckpoint;
import com.stellar.platform.storage.sqlite.MigrationV12ProductionRuntime;
import com.stellar.platform.storage.sqlite.MigrationV13AnalysisEngine;
import com.stellar.platform.storage.sqlite.MigrationV14DiagnosticRules;
import com.stellar.platform.storage.sqlite.MigrationV15DiagnosticWorkflow;
import com.stellar.platform.storage.sqlite.MigrationV16Investigations;
import com.stellar.platform.storage.sqlite.MigrationV17SemanticSignalMapping;
import com.stellar.platform.storage.sqlite.MigrationV18ConfigMappingScope;
import com.stellar.platform.storage.sqlite.MigrationV19CentralIdentity;
import com.stellar.platform.storage.sqlite.MigrationV20AnalysisScope;
import com.stellar.platform.storage.sqlite.MigrationV21EvaluationRules;
import com.stellar.platform.storage.sqlite.SchemaMigrator;
import com.stellar.platform.storage.sqlite.SqliteConnectionSupport;
import com.stellar.platform.storage.sqlite.SqliteIdentitySequences;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite-backed metadata store for the offline desktop application.
 *
 * <p>The schema intentionally contains no decoded signal/sample table. High-volume time-series
 * samples remain in the lossless graph store; SQLite persists only project/file/inventory/mapping
 * and processing audit metadata.</p>
 */
public final class SqliteProjectMetadataStore implements ProjectMetadataStore {
    private static final int SCHEMA_VERSION = 21;

    /** Current application schema. Forward-only; never downgraded. */
    public static int currentSchemaVersion() {
        return SCHEMA_VERSION;
    }
    private final Path databasePath;
    private final Connection connection;
    private boolean closed;

    public SqliteProjectMetadataStore(Path databasePath) {
        this.databasePath = Objects.requireNonNull(databasePath, "databasePath").toAbsolutePath().normalize();
        com.stellar.platform.storage.analysis.AnalysisStorageIsolation.refuseOperatorWorkspace(this.databasePath);
        try {
            Path parent = this.databasePath.getParent();
            if (parent != null) Files.createDirectories(parent);
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.databasePath);
            SqliteConnectionSupport.configure(connection);
            int version = new SchemaMigrator(List.of(
                    new MigrationV3Baseline(),
                    new MigrationV4EnterpriseKnowledge(),
                    new MigrationV5UniqueProjectNames(),
                    new MigrationV6MapRouteState(),
                    new MigrationV7GraphInvestigation(),
                    new MigrationV8LastWorkspace(),
                    new MigrationV9ArtifactJobOutcome(),
                    new MigrationV10PlanFingerprint(),
                    new MigrationV11WorkspaceCheckpoint(),
                    new MigrationV12ProductionRuntime(),
                    new MigrationV13AnalysisEngine(),
                    new MigrationV14DiagnosticRules(),
                    new MigrationV15DiagnosticWorkflow(),
                    new MigrationV16Investigations(),
                    new MigrationV17SemanticSignalMapping(),
                    new MigrationV18ConfigMappingScope(),
                    new MigrationV19CentralIdentity(),
                    new MigrationV20AnalysisScope(),
                    new MigrationV21EvaluationRules()
            )).migrate(connection);
            if (version > SCHEMA_VERSION) {
                throw new IllegalStateException("Metadata schema is newer than this application: v" + version);
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Cannot open StellarAI metadata database " + this.databasePath, e);
        }
    }

    public Path databasePath() {
        return databasePath;
    }

    /**
     * Legacy global database used before project-local {@code .stellar/stellar.db} files.
     * Production project open/create must not write here. Tests may still override
     * {@code stellarai.metadata.db}.
     */
    public static Path defaultDatabasePath() {
        return legacyGlobalDatabasePath();
    }

    public static Path legacyGlobalDatabasePath() {
        String configured = System.getProperty("stellarai.metadata.db", "").trim();
        if (!configured.isEmpty()) return Path.of(configured).toAbsolutePath().normalize();
        return StellarAiPaths.homeRoot().resolve("metadata").resolve("stellar.db");
    }

    /**
     * Candidate locations of the pre-project-local global SQLite file.
     * Includes {@link #legacyGlobalDatabasePath()} and the historical
     * {@code user.home/.stellarai/metadata/stellar.db} when they differ.
     */
    public static List<Path> legacyDatabaseCandidates() {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        paths.add(legacyGlobalDatabasePath());
        // When stellarai.home is overridden (tests / portable home), do not also
        // scan the operator's personal ~/.stellarai database.
        if (System.getProperty("stellarai.home", "").isBlank()
                && !com.stellar.platform.storage.analysis.AnalysisStorageIsolation.enabled()) {
            paths.add(Path.of(System.getProperty("user.home"), ".stellarai", "metadata", "stellar.db")
                    .toAbsolutePath().normalize());
        }
        return List.copyOf(paths);
    }

    @Override
    public synchronized void upsertProject(ProjectMetadata project) {
        ensureOpen();
        Objects.requireNonNull(project, "project");
        String display;
        String normalized;
        try {
            display = ProjectNameNormalizer.requireDisplayName(project.name());
            normalized = ProjectNameNormalizer.normalized(display);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
        Optional<ProjectMetadata> conflict = findByNormalizedName(normalized);
        if (conflict.isPresent() && !conflict.get().projectId().equals(project.projectId())) {
            throw new DuplicateProjectNameException(display, conflict.get().projectId());
        }
        try {
            long numericId = numericIdForProject(project.projectId()).orElse(0L);
            if (numericId < SqliteIdentitySequences.START_WITH) {
                numericId = parseNumericIdentity(project.projectId()).orElse(0L);
            }
            if (numericId >= SqliteIdentitySequences.START_WITH) {
                adoptNumericId(SqliteIdentitySequences.PROJECTS, numericId);
            } else {
                numericId = allocateNumericId(SqliteIdentitySequences.PROJECTS);
            }
            final long persistedNumeric = numericId;
            executeUpdate("""
                    INSERT INTO projects(project_id, name, normalized_name, created_at, updated_at, last_workspace, last_saved_at, numeric_id)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(project_id) DO UPDATE SET
                      name=excluded.name,
                      normalized_name=excluded.normalized_name,
                      updated_at=excluded.updated_at,
                      last_workspace=excluded.last_workspace,
                      last_saved_at=excluded.last_saved_at,
                      numeric_id=COALESCE(projects.numeric_id, excluded.numeric_id)
                    """, ps -> {
                ps.setString(1, project.projectId());
                ps.setString(2, display);
                ps.setString(3, normalized);
                ps.setString(4, project.createdAt().toString());
                ps.setString(5, project.updatedAt().toString());
                ps.setString(6, project.lastWorkspace());
                if (project.lastSavedAt() == null) {
                    ps.setNull(7, java.sql.Types.VARCHAR);
                } else {
                    ps.setString(7, project.lastSavedAt().toString());
                }
                ps.setLong(8, persistedNumeric);
            });
        } catch (RuntimeException ex) {
            if (isUniqueConstraintViolation(ex)) {
                throw new DuplicateProjectNameException(display, "");
            }
            throw ex;
        }
    }

    @Override
    public synchronized Optional<ProjectMetadata> findByNormalizedName(String normalizedName) {
        ensureOpen();
        if (normalizedName == null || normalizedName.isBlank()) return Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT project_id, name, created_at, updated_at, last_workspace, last_saved_at
                FROM projects WHERE normalized_name=? LIMIT 1
                """)) {
            ps.setString(1, normalizedName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(projectFrom(rs));
            }
        } catch (SQLException e) {
            throw failure("Cannot lookup project by normalized name", e);
        }
    }

    @Override
    public synchronized boolean existsProjectName(String name, String excludeProjectId) {
        ensureOpen();
        String normalized;
        try {
            normalized = ProjectNameNormalizer.normalized(name);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        Optional<ProjectMetadata> found = findByNormalizedName(normalized);
        if (found.isEmpty()) return false;
        return excludeProjectId == null || excludeProjectId.isBlank()
                || !excludeProjectId.equals(found.get().projectId());
    }

    @Override
    public synchronized void deleteProject(String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        inTransaction(() -> {
            // Explicit child cleanup first (safe even if FK cascade is disabled mid-session).
            executeUpdate("DELETE FROM data_quality_findings WHERE project_id=?", ps -> ps.setString(1, projectId));
            executeUpdate("DELETE FROM evaluation_results WHERE project_id=?", ps -> ps.setString(1, projectId));
            executeUpdate("DELETE FROM evaluation_rules WHERE project_id=?", ps -> ps.setString(1, projectId));
            executeUpdate("DELETE FROM engineering_bookmarks WHERE project_id=?", ps -> ps.setString(1, projectId));
            executeUpdate("DELETE FROM signal_catalog WHERE project_id=?", ps -> ps.setString(1, projectId));
            executeUpdate("DELETE FROM processing_artifacts WHERE project_id=?", ps -> ps.setString(1, projectId));
            executeUpdate("DELETE FROM processing_runs WHERE project_id=?", ps -> ps.setString(1, projectId));
            executeUpdate("DELETE FROM channel_mappings WHERE project_id=?", ps -> ps.setString(1, projectId));
            executeUpdate("DELETE FROM channel_messages WHERE project_id=?", ps -> ps.setString(1, projectId));
            executeUpdate("DELETE FROM channel_inventory WHERE project_id=?", ps -> ps.setString(1, projectId));
            executeUpdate("DELETE FROM source_inventory WHERE project_id=?", ps -> ps.setString(1, projectId));
            executeUpdate("DELETE FROM semantic_signal_mapping WHERE project_id=?", ps -> ps.setString(1, projectId));
            executeUpdate("DELETE FROM project_files WHERE project_id=?", ps -> ps.setString(1, projectId));
            executeUpdate("DELETE FROM legacy_project_import WHERE legacy_project_id=?", ps -> ps.setString(1, projectId));
            executeUpdate("DELETE FROM projects WHERE project_id=?", ps -> ps.setString(1, projectId));
        });
    }

    private static boolean isUniqueConstraintViolation(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase(Locale.ROOT);
                if (lower.contains("unique") || lower.contains("constraint")) {
                    return true;
                }
            }
            if (t instanceof SQLException sql && "23000".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized void upsertFile(ProjectFileMetadata file) {
        ensureOpen();
        Objects.requireNonNull(file, "file");
        executeUpdate("""
                INSERT INTO project_files(
                  project_id, file_id, path, kind, size_bytes, validation_status, validation_message,
                  associated_definition_id, finalization_label, recovery_candidate,
                  selected_measurement, selected_definition, updated_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(project_id, file_id) DO UPDATE SET
                  path=excluded.path,
                  kind=excluded.kind,
                  size_bytes=excluded.size_bytes,
                  validation_status=excluded.validation_status,
                  validation_message=excluded.validation_message,
                  associated_definition_id=excluded.associated_definition_id,
                  finalization_label=excluded.finalization_label,
                  recovery_candidate=excluded.recovery_candidate,
                  selected_measurement=excluded.selected_measurement,
                  selected_definition=excluded.selected_definition,
                  updated_at=excluded.updated_at
                """, ps -> {
            ps.setString(1, file.projectId());
            ps.setString(2, file.fileId());
            ps.setString(3, file.path());
            ps.setString(4, file.kind());
            ps.setLong(5, file.sizeBytes());
            ps.setString(6, file.validationStatus());
            ps.setString(7, file.validationMessage());
            setNullable(ps, 8, file.associatedDefinitionId());
            ps.setString(9, file.finalizationLabel());
            ps.setInt(10, file.recoveryCandidate() ? 1 : 0);
            ps.setInt(11, file.selectedMeasurement() ? 1 : 0);
            ps.setInt(12, file.selectedDefinition() ? 1 : 0);
            ps.setString(13, Instant.now().toString());
        });
    }

    @Override
    public synchronized void deleteFile(String projectId, String fileId) {
        ensureOpen();
        inTransaction(() -> {
            executeUpdate("DELETE FROM processing_artifacts WHERE project_id=? AND source_id=?", ps -> {
                ps.setString(1, projectId); ps.setString(2, fileId);
            });
            executeUpdate("DELETE FROM signal_catalog WHERE project_id=? AND source_id=?", ps -> {
                ps.setString(1, projectId); ps.setString(2, fileId);
            });
            executeUpdate("DELETE FROM data_quality_findings WHERE project_id=? AND source_id=?", ps -> {
                ps.setString(1, projectId); ps.setString(2, fileId);
            });
            executeUpdate("DELETE FROM channel_messages WHERE project_id=? AND source_id=?", ps -> {
                ps.setString(1, projectId); ps.setString(2, fileId);
            });
            executeUpdate("DELETE FROM channel_mappings WHERE project_id=? AND source_id=?", ps -> {
                ps.setString(1, projectId); ps.setString(2, fileId);
            });
            executeUpdate("DELETE FROM channel_inventory WHERE project_id=? AND source_id=?", ps -> {
                ps.setString(1, projectId); ps.setString(2, fileId);
            });
            executeUpdate("DELETE FROM source_inventory WHERE project_id=? AND source_id=?", ps -> {
                ps.setString(1, projectId); ps.setString(2, fileId);
            });
            executeUpdate("DELETE FROM project_files WHERE project_id=? AND file_id=?", ps -> {
                ps.setString(1, projectId); ps.setString(2, fileId);
            });
            touchProject(projectId);
        });
    }

    @Override
    public synchronized void saveInventory(String projectId, String sourceId, CanSourceInventory inventory) {
        ensureOpen();
        Objects.requireNonNull(inventory, "inventory");
        inTransaction(() -> {
            executeUpdate("""
                    INSERT INTO source_inventory(
                      project_id, source_id, source_path, source_name, source_format, status,
                      total_can_channels, total_can_frames, total_unique_message_ids,
                      channel_discovery_complete, diagnostic_count, updated_at)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(project_id, source_id) DO UPDATE SET
                      source_path=excluded.source_path,
                      source_name=excluded.source_name,
                      source_format=excluded.source_format,
                      status=excluded.status,
                      total_can_channels=excluded.total_can_channels,
                      total_can_frames=excluded.total_can_frames,
                      total_unique_message_ids=excluded.total_unique_message_ids,
                      channel_discovery_complete=excluded.channel_discovery_complete,
                      diagnostic_count=excluded.diagnostic_count,
                      updated_at=excluded.updated_at
                    """, ps -> {
                ps.setString(1, projectId);
                ps.setString(2, sourceId);
                ps.setString(3, inventory.sourcePath().toString());
                ps.setString(4, inventory.sourceFileName());
                ps.setString(5, inventory.format().name());
                ps.setString(6, inventory.status().name());
                ps.setLong(7, inventory.totalCanChannels());
                ps.setLong(8, inventory.totalCanFrames());
                ps.setLong(9, inventory.globalUniqueCanIdentityCount());
                ps.setInt(10, inventory.channelDiscoveryComplete() ? 1 : 0);
                ps.setInt(11, inventory.diagnostics().size());
                ps.setString(12, Instant.now().toString());
            });
            executeUpdate("DELETE FROM channel_messages WHERE project_id=? AND source_id=?", ps -> {
                ps.setString(1, projectId); ps.setString(2, sourceId);
            });
            executeUpdate("DELETE FROM channel_inventory WHERE project_id=? AND source_id=?", ps -> {
                ps.setString(1, projectId); ps.setString(2, sourceId);
            });
            for (CanChannelInventory channel : inventory.channels()) {
                executeUpdate("""
                        INSERT INTO channel_inventory(
                          project_id, source_id, channel_id, display_name, frame_count,
                          unique_message_count, standard_message_count, extended_message_count,
                          can_fd_frame_count)
                        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, ps -> {
                    ps.setString(1, projectId);
                    ps.setString(2, sourceId);
                    ps.setInt(3, channel.channelId());
                    ps.setString(4, channel.displayName());
                    ps.setLong(5, channel.frameCount());
                    ps.setLong(6, channel.uniqueMessageCount());
                    ps.setLong(7, channel.standardMessageCount());
                    ps.setLong(8, channel.extendedMessageCount());
                    ps.setLong(9, channel.canFdFrameCount());
                });
                for (CanMessageInventory message : channel.messages()) {
                    executeUpdate("""
                            INSERT INTO channel_messages(
                              project_id, source_id, channel_id, can_id, extended, frame_count,
                              min_observed_dlc, max_observed_dlc, can_fd_observed)
                            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """, ps -> {
                        ps.setString(1, projectId);
                        ps.setString(2, sourceId);
                        ps.setInt(3, channel.channelId());
                        ps.setLong(4, message.canId());
                        ps.setInt(5, message.extended() ? 1 : 0);
                        ps.setLong(6, message.frameCount());
                        ps.setInt(7, message.minObservedDlc());
                        ps.setInt(8, message.maxObservedDlc());
                        ps.setInt(9, message.canFdObserved() ? 1 : 0);
                    });
                }
            }
            touchProject(projectId);
        });
    }

    @Override
    public synchronized void upsertChannelMapping(ChannelMappingMetadata mapping) {
        ensureOpen();
        Objects.requireNonNull(mapping, "mapping");
        executeUpdate("""
                INSERT INTO channel_mappings(
                  project_id, source_id, channel_id, status, recommendation_kind,
                  recommended_definition_id, mapped_definition_id,
                  observed_unique_message_count, matched_unique_message_count, coverage_ratio, updated_at)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(project_id, source_id, channel_id) DO UPDATE SET
                  status=excluded.status,
                  recommendation_kind=excluded.recommendation_kind,
                  recommended_definition_id=excluded.recommended_definition_id,
                  mapped_definition_id=excluded.mapped_definition_id,
                  observed_unique_message_count=excluded.observed_unique_message_count,
                  matched_unique_message_count=excluded.matched_unique_message_count,
                  coverage_ratio=excluded.coverage_ratio,
                  updated_at=excluded.updated_at
                """, ps -> {
            ps.setString(1, mapping.projectId());
            ps.setString(2, mapping.sourceId());
            ps.setInt(3, mapping.channelId());
            ps.setString(4, mapping.status());
            ps.setString(5, mapping.recommendationKind());
            setNullable(ps, 6, mapping.recommendedDefinitionId());
            setNullable(ps, 7, mapping.mappedDefinitionId());
            ps.setLong(8, mapping.observedUniqueMessageCount());
            ps.setLong(9, mapping.matchedUniqueMessageCount());
            ps.setDouble(10, mapping.coverageRatio());
            ps.setString(11, Instant.now().toString());
        });
        touchProject(mapping.projectId());
    }

    @Override
    public synchronized void deleteChannelMapping(String projectId, String sourceId, int channelId) {
        ensureOpen();
        executeUpdate("DELETE FROM channel_mappings WHERE project_id=? AND source_id=? AND channel_id=?", ps -> {
            ps.setString(1, projectId); ps.setString(2, sourceId); ps.setInt(3, channelId);
        });
        touchProject(projectId);
    }

    @Override
    public synchronized void upsertProcessingRun(ProcessingRunMetadata run) {
        ensureOpen();
        Objects.requireNonNull(run, "run");
        executeUpdate("""
                INSERT INTO processing_runs(
                  run_id, project_id, status, force_reprocess, job_count, started_at, finished_at, message)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(run_id) DO UPDATE SET
                  status=excluded.status,
                  finished_at=excluded.finished_at,
                  message=excluded.message
                """, ps -> {
            ps.setString(1, run.runId());
            ps.setString(2, run.projectId());
            ps.setString(3, run.status());
            ps.setInt(4, run.forceReprocess() ? 1 : 0);
            ps.setInt(5, run.jobCount());
            ps.setString(6, run.startedAt().toString());
            setNullable(ps, 7, run.finishedAt() == null ? null : run.finishedAt().toString());
            ps.setString(8, run.message());
        });
        touchProject(run.projectId());
    }

    @Override
    public synchronized void upsertProcessingArtifact(ProcessingArtifactMetadata artifact) {
        ensureOpen();
        Objects.requireNonNull(artifact, "artifact");
        executeUpdate("""
                INSERT INTO processing_artifacts(
                  artifact_id, project_id, source_id, channel_id, definition_id, processing_run_id,
                  artifact_type, artifact_path, artifact_storage_ref, artifact_version,
                  source_fingerprint, dbc_fingerprint, plan_fingerprint,
                  signal_count, sample_count, start_timestamp_nanos, end_timestamp_nanos,
                  status, created_at, mapping_lifecycle,
                  job_execution_status, diagnostics_summary, technical_detail)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(artifact_id) DO UPDATE SET
                  processing_run_id=excluded.processing_run_id,
                  artifact_path=excluded.artifact_path,
                  artifact_storage_ref=excluded.artifact_storage_ref,
                  artifact_version=excluded.artifact_version,
                  source_fingerprint=excluded.source_fingerprint,
                  dbc_fingerprint=excluded.dbc_fingerprint,
                  plan_fingerprint=excluded.plan_fingerprint,
                  signal_count=excluded.signal_count,
                  sample_count=excluded.sample_count,
                  start_timestamp_nanos=excluded.start_timestamp_nanos,
                  end_timestamp_nanos=excluded.end_timestamp_nanos,
                  status=excluded.status,
                  created_at=excluded.created_at,
                  mapping_lifecycle=excluded.mapping_lifecycle,
                  job_execution_status=excluded.job_execution_status,
                  diagnostics_summary=excluded.diagnostics_summary,
                  technical_detail=excluded.technical_detail
                """, ps -> {
            ps.setString(1, artifact.artifactId());
            ps.setString(2, artifact.projectId());
            ps.setString(3, artifact.sourceId());
            ps.setInt(4, artifact.channelId());
            ps.setString(5, artifact.definitionId());
            ps.setString(6, artifact.processingRunId());
            ps.setString(7, artifact.artifactType());
            ps.setString(8, artifact.artifactPath());
            ps.setString(9, artifact.artifactStorageRef());
            ps.setString(10, artifact.artifactVersion());
            ps.setString(11, artifact.sourceFingerprint());
            ps.setString(12, artifact.dbcFingerprint());
            ps.setString(13, artifact.planFingerprint());
            ps.setLong(14, artifact.signalCount());
            ps.setLong(15, artifact.sampleCount());
            if (artifact.startTimestampNanos() == null) ps.setNull(16, java.sql.Types.BIGINT);
            else ps.setLong(16, artifact.startTimestampNanos());
            if (artifact.endTimestampNanos() == null) ps.setNull(17, java.sql.Types.BIGINT);
            else ps.setLong(17, artifact.endTimestampNanos());
            ps.setString(18, artifact.status());
            ps.setString(19, artifact.createdAt().toString());
            ps.setString(20, artifact.mappingLifecycle());
            ps.setString(21, artifact.jobExecutionStatus());
            ps.setString(22, artifact.diagnosticsSummary());
            ps.setString(23, artifact.technicalDetail());
        });
        touchProject(artifact.projectId());
    }

    @Override
    public synchronized void markProcessingArtifactsStatus(String projectId, String status) {
        ensureOpen();
        if (projectId == null || projectId.isBlank() || status == null || status.isBlank()) return;
        executeUpdate("UPDATE processing_artifacts SET status=? WHERE project_id=?", ps -> {
            ps.setString(1, status);
            ps.setString(2, projectId);
        });
        touchProject(projectId);
    }

    @Override
    public synchronized void deleteProcessingArtifacts(String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) return;
        executeUpdate("DELETE FROM processing_artifacts WHERE project_id=?", ps -> ps.setString(1, projectId));
        touchProject(projectId);
    }

    @Override
    public synchronized List<ProjectMetadata> listProjects() {
        ensureOpen();
        List<ProjectMetadata> projects = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT project_id, name, created_at, updated_at, last_workspace, last_saved_at
                FROM projects
                ORDER BY updated_at DESC, name COLLATE NOCASE
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                projects.add(projectFrom(rs));
            }
            return List.copyOf(projects);
        } catch (SQLException | RuntimeException e) {
            throw failure("Cannot list saved projects", e);
        }
    }

    @Override
    public synchronized List<ProjectDashboardSummary> listProjectDashboardSummaries() {
        ensureOpen();
        List<ProjectDashboardSummary> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT p.project_id, p.name, p.created_at, p.updated_at,
                       (SELECT COUNT(*) FROM project_files f
                         WHERE f.project_id = p.project_id AND f.kind LIKE 'MEASUREMENT_%') AS measurement_count,
                       (SELECT COUNT(*) FROM project_files f
                         WHERE f.project_id = p.project_id AND f.kind LIKE 'DEFINITION_%') AS definition_count,
                       (SELECT COUNT(DISTINCT sc.signal_key) FROM signal_catalog sc
                         WHERE sc.project_id = p.project_id) AS signal_count,
                       (SELECT r.status FROM processing_runs r
                         WHERE r.project_id = p.project_id
                         ORDER BY r.started_at DESC LIMIT 1) AS last_run_status,
                       (SELECT GROUP_CONCAT(DISTINCT REPLACE(REPLACE(f.kind, 'MEASUREMENT_', ''), 'DEFINITION_', ''))
                         FROM project_files f WHERE f.project_id = p.project_id) AS file_types,
                       (SELECT COALESCE(SUM(si.total_can_frames), 0) FROM source_inventory si
                         WHERE si.project_id = p.project_id) AS can_frames,
                       (SELECT COUNT(*) FROM source_inventory si
                         WHERE si.project_id = p.project_id) AS inventory_count,
                       (SELECT COUNT(*) FROM channel_mappings cm
                         WHERE cm.project_id = p.project_id) AS mapping_count
                FROM projects p
                ORDER BY p.updated_at DESC, p.name COLLATE NOCASE
                """);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String status = rs.getString("last_run_status");
                String types = rs.getString("file_types");
                out.add(new ProjectDashboardSummary(
                        rs.getString("project_id"),
                        rs.getString("name"),
                        Instant.parse(rs.getString("created_at")),
                        Instant.parse(rs.getString("updated_at")),
                        rs.getInt("measurement_count"),
                        rs.getInt("definition_count"),
                        rs.getInt("signal_count"),
                        status == null ? "" : status,
                        types == null ? "" : types.replace(",", " · "),
                        rs.getLong("can_frames"),
                        rs.getInt("inventory_count"),
                        rs.getInt("mapping_count")));
            }
            return List.copyOf(out);
        } catch (SQLException | RuntimeException e) {
            throw failure("Cannot list project dashboard summaries", e);
        }
    }

    @Override
    public synchronized int countProjects() {
        ensureOpen();
        try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM projects");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException | RuntimeException e) {
            throw failure("Cannot count saved projects", e);
        }
    }

    @Override
    public synchronized int signalCatalogCount(String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) {
            return 0;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(DISTINCT signal_key) FROM signal_catalog WHERE project_id = ?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException | RuntimeException e) {
            throw failure("Cannot count signal catalog for " + projectId, e);
        }
    }

    @Override
    public synchronized Optional<String> latestUpdatedProjectId() {
        ensureOpen();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT project_id FROM projects ORDER BY updated_at DESC, name COLLATE NOCASE LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
        } catch (SQLException | RuntimeException e) {
            throw failure("Cannot read the latest saved project", e);
        }
    }

    @Override
    public synchronized Optional<SavedProjectSnapshot> loadProject(String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) return Optional.empty();
        try {
            ProjectMetadata project = readProject(projectId);
            if (project == null) return Optional.empty();
            List<ProjectFileMetadata> files = readFiles(projectId);
            Map<String, List<CanMessageInventory>> messagesByChannel = readChannelMessages(projectId);
            Map<String, List<CanChannelInventory>> channelsBySource = readChannels(projectId, messagesByChannel);
            Map<String, CanSourceInventory> inventories = readInventories(projectId, channelsBySource);
            List<ChannelMappingMetadata> mappings = readMappings(projectId);
            List<ProcessingRunMetadata> runs = readProcessingRuns(projectId);
            List<ProcessingArtifactMetadata> artifacts = readProcessingArtifacts(projectId);
            List<SignalCatalogMetadata> catalog = listSignalCatalog(projectId);
            List<EngineeringBookmarkMetadata> bookmarks = listBookmarks(projectId);
            List<DataQualityFindingMetadata> dq = listDataQualityFindings(projectId);
            return Optional.of(new SavedProjectSnapshot(
                    project, files, inventories, mappings, runs, artifacts, catalog, bookmarks, dq));
        } catch (SQLException | RuntimeException e) {
            throw failure("Cannot load saved project " + projectId, e);
        }
    }

    private ProjectMetadata readProject(String projectId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT project_id, name, created_at, updated_at, last_workspace, last_saved_at FROM projects WHERE project_id=?
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return projectFrom(rs);
            }
        }
    }

    private List<ProjectFileMetadata> readFiles(String projectId) throws SQLException {
        List<ProjectFileMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT file_id, path, kind, size_bytes, validation_status, validation_message,
                       associated_definition_id, finalization_label, recovery_candidate,
                       selected_measurement, selected_definition
                FROM project_files WHERE project_id=? ORDER BY updated_at, path
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ProjectFileMetadata(
                            projectId, rs.getString("file_id"), rs.getString("path"), rs.getString("kind"),
                            rs.getLong("size_bytes"), rs.getString("validation_status"),
                            rs.getString("validation_message"), rs.getString("associated_definition_id"),
                            rs.getString("finalization_label"), rs.getInt("recovery_candidate") != 0,
                            rs.getInt("selected_measurement") != 0, rs.getInt("selected_definition") != 0));
                }
            }
        }
        return List.copyOf(out);
    }

    private Map<String, List<CanMessageInventory>> readChannelMessages(String projectId) throws SQLException {
        Map<String, List<CanMessageInventory>> out = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT source_id, channel_id, can_id, extended, frame_count,
                       min_observed_dlc, max_observed_dlc, can_fd_observed
                FROM channel_messages WHERE project_id=? ORDER BY source_id, channel_id, can_id, extended
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sourceId = rs.getString("source_id");
                    int channelId = rs.getInt("channel_id");
                    String key = sourceId + "#" + channelId;
                    out.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new CanMessageInventory(
                            new CanMessageKey(channelId, rs.getLong("can_id"), rs.getInt("extended") != 0),
                            rs.getLong("frame_count"), rs.getInt("min_observed_dlc"),
                            rs.getInt("max_observed_dlc"), rs.getInt("can_fd_observed") != 0));
                }
            }
        }
        return out;
    }

    private Map<String, List<CanChannelInventory>> readChannels(
            String projectId, Map<String, List<CanMessageInventory>> messagesByChannel) throws SQLException {
        Map<String, List<CanChannelInventory>> out = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT source_id, channel_id, display_name, frame_count, unique_message_count,
                       standard_message_count, extended_message_count, can_fd_frame_count
                FROM channel_inventory WHERE project_id=? ORDER BY source_id, channel_id
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sourceId = rs.getString("source_id");
                    int channelId = rs.getInt("channel_id");
                    List<CanMessageInventory> messages = messagesByChannel.getOrDefault(sourceId + "#" + channelId, List.of());
                    out.computeIfAbsent(sourceId, ignored -> new ArrayList<>()).add(new CanChannelInventory(
                            channelId, rs.getString("display_name"), rs.getLong("frame_count"),
                            rs.getLong("unique_message_count"), rs.getLong("standard_message_count"),
                            rs.getLong("extended_message_count"), rs.getLong("can_fd_frame_count"), messages));
                }
            }
        }
        return out;
    }

    private Map<String, CanSourceInventory> readInventories(
            String projectId, Map<String, List<CanChannelInventory>> channelsBySource) throws SQLException {
        Map<String, CanSourceInventory> out = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT source_id, source_path, source_name, source_format, status, total_can_channels,
                       total_can_frames, total_unique_message_ids, channel_discovery_complete
                FROM source_inventory WHERE project_id=? ORDER BY updated_at, source_id
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sourceId = rs.getString("source_id");
                    out.put(sourceId, new CanSourceInventory(
                            Path.of(rs.getString("source_path")), rs.getString("source_name"),
                            enumValue(MeasurementSourceFormat.class, rs.getString("source_format"), MeasurementSourceFormat.UNKNOWN),
                            enumValue(CanInventoryStatus.class, rs.getString("status"), CanInventoryStatus.FAILED),
                            rs.getLong("total_can_channels"), rs.getLong("total_can_frames"),
                            rs.getLong("total_unique_message_ids"), rs.getInt("channel_discovery_complete") != 0,
                            channelsBySource.getOrDefault(sourceId, List.of()), List.of()));
                }
            }
        }
        return Map.copyOf(out);
    }

    private List<ChannelMappingMetadata> readMappings(String projectId) throws SQLException {
        List<ChannelMappingMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT source_id, channel_id, status, recommendation_kind, recommended_definition_id,
                       mapped_definition_id, observed_unique_message_count, matched_unique_message_count, coverage_ratio
                FROM channel_mappings WHERE project_id=? ORDER BY source_id, channel_id
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ChannelMappingMetadata(
                            projectId, rs.getString("source_id"), rs.getInt("channel_id"), rs.getString("status"),
                            rs.getString("recommendation_kind"), rs.getString("recommended_definition_id"),
                            rs.getString("mapped_definition_id"), rs.getLong("observed_unique_message_count"),
                            rs.getLong("matched_unique_message_count"), rs.getDouble("coverage_ratio")));
                }
            }
        }
        return List.copyOf(out);
    }

    private List<ProcessingRunMetadata> readProcessingRuns(String projectId) throws SQLException {
        List<ProcessingRunMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT run_id, status, force_reprocess, job_count, started_at, finished_at, message
                FROM processing_runs WHERE project_id=? ORDER BY started_at DESC
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String finished = rs.getString("finished_at");
                    out.add(new ProcessingRunMetadata(
                            rs.getString("run_id"), projectId, rs.getString("status"), rs.getInt("force_reprocess") != 0,
                            rs.getInt("job_count"), Instant.parse(rs.getString("started_at")),
                            finished == null || finished.isBlank() ? null : Instant.parse(finished), rs.getString("message")));
                }
            }
        }
        return List.copyOf(out);
    }

    private List<ProcessingArtifactMetadata> readProcessingArtifacts(String projectId) throws SQLException {
        List<ProcessingArtifactMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT artifact_id, source_id, channel_id, definition_id, processing_run_id,
                       artifact_type, artifact_path, artifact_storage_ref, artifact_version,
                       source_fingerprint, dbc_fingerprint, plan_fingerprint,
                       signal_count, sample_count, start_timestamp_nanos, end_timestamp_nanos,
                       status, created_at, mapping_lifecycle,
                       job_execution_status, diagnostics_summary, technical_detail
                FROM processing_artifacts WHERE project_id=?
                ORDER BY source_id, channel_id, definition_id
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapArtifact(projectId, rs));
                }
            }
        }
        return List.copyOf(out);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Enum.valueOf(type, value); }
        catch (IllegalArgumentException ex) { return fallback; }
    }

    /** Test/diagnostic query only: confirms that no sample table is introduced by this store. */
    public synchronized boolean tableExists(String tableName) {
        ensureOpen();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            ps.setString(1, tableName);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException e) {
            throw failure("Cannot inspect SQLite schema", e);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try { connection.close(); }
        catch (SQLException e) { throw failure("Cannot close metadata database", e); }
    }

    /** Make worker-committed WAL rows visible to this connection without reopening. */
    public synchronized void checkpointWal() {
        if (closed) {
            return;
        }
        ensureOpen();
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA wal_checkpoint(PASSIVE)");
        } catch (SQLException ignored) {
            // Reader queries still see committed WAL; checkpoint is best-effort.
        }
    }

    public synchronized long allocateNumericId(String entity) {
        ensureOpen();
        try {
            return SqliteIdentitySequences.next(connection, entity);
        } catch (SQLException e) {
            throw failure("Cannot allocate numeric id for " + entity, e);
        }
    }

    public synchronized Optional<Long> numericIdForProject(String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT numeric_id FROM projects WHERE project_id=?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                long value = rs.getLong(1);
                return rs.wasNull() || value < SqliteIdentitySequences.START_WITH
                        ? Optional.empty() : Optional.of(value);
            }
        } catch (SQLException e) {
            throw failure("Cannot read numeric project id", e);
        }
    }

    public synchronized boolean foreignKeysEnabled() {
        ensureOpen();
        try {
            SqliteConnectionSupport.verifyForeignKeysEnabled(connection);
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public synchronized LegacyProjectImporter.Result importLegacyProject(
            Path legacyDb, String projectId, Connection legacyConnection) {
        ensureOpen();
        try {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                LegacyProjectImporter.Result result = LegacyProjectImporter.copyProject(
                        connection, legacyConnection, legacyDb, projectId);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex instanceof SQLException sql ? sql : new SQLException(ex);
            } finally {
                connection.setAutoCommit(previous);
            }
        } catch (SQLException e) {
            throw failure("Legacy project import failed for " + projectId, e);
        }
    }

    public synchronized List<LegacyProjectImporter.Result> importLegacyDatabase(Path legacyDb) {
        return LegacyProjectImporter.importDatabase(this, legacyDb);
    }

    private void adoptNumericId(String entity, long id) {
        try {
            SqliteIdentitySequences.adopt(connection, entity, id);
        } catch (SQLException e) {
            throw failure("Cannot adopt numeric id " + id + " for " + entity, e);
        }
    }

    private static Optional<Long> parseNumericIdentity(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed >= SqliteIdentitySequences.START_WITH ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private static void configure(Connection connection) throws SQLException {
        SqliteConnectionSupport.configure(connection);
    }

    @Override
    public synchronized Optional<ProcessingArtifactMetadata> findValidArtifact(
            String projectId, String sourceId, int channelId, String definitionId) {
        ensureOpen();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT artifact_id, source_id, channel_id, definition_id, processing_run_id,
                       artifact_type, artifact_path, artifact_storage_ref, artifact_version,
                       source_fingerprint, dbc_fingerprint, plan_fingerprint,
                       signal_count, sample_count,
                       start_timestamp_nanos, end_timestamp_nanos, status, created_at, mapping_lifecycle,
                       job_execution_status, diagnostics_summary, technical_detail
                FROM processing_artifacts
                WHERE project_id=? AND source_id=? AND channel_id=? AND definition_id=? AND status=?
                LIMIT 1
                """)) {
            ps.setString(1, projectId);
            ps.setString(2, sourceId);
            ps.setInt(3, channelId);
            ps.setString(4, definitionId == null ? "" : definitionId);
            ps.setString(5, ProcessingArtifactMetadata.STATUS_VALID);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapArtifact(projectId, rs));
            }
        } catch (SQLException e) {
            throw failure("Cannot lookup processing artifact", e);
        }
    }

    @Override
    public synchronized void replaceSignalCatalog(
            String projectId, String artifactId, List<SignalCatalogMetadata> entries) {
        ensureOpen();
        inTransaction(() -> {
            executeUpdate("DELETE FROM signal_catalog WHERE project_id=? AND artifact_id=?", ps -> {
                ps.setString(1, projectId);
                ps.setString(2, artifactId);
            });
            if (entries == null) return;
            for (SignalCatalogMetadata entry : entries) {
                executeUpdate("""
                        INSERT INTO signal_catalog(
                          signal_catalog_id, project_id, source_id, channel_id, definition_id, artifact_id,
                          signal_key, signal_name, message_name, can_id, signal_channel, source_type, unit,
                          sample_count, start_timestamp_nanos, end_timestamp_nanos, min_value, max_value,
                          created_at, updated_at)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, ps -> {
                    ps.setString(1, entry.signalCatalogId());
                    ps.setString(2, entry.projectId());
                    ps.setString(3, entry.sourceId());
                    ps.setInt(4, entry.channelId());
                    ps.setString(5, entry.definitionId());
                    ps.setString(6, entry.artifactId());
                    ps.setString(7, entry.signalKey());
                    ps.setString(8, entry.signalName());
                    ps.setString(9, entry.messageName());
                    ps.setLong(10, entry.canId());
                    ps.setInt(11, entry.signalChannel());
                    ps.setString(12, entry.sourceType());
                    ps.setString(13, entry.unit());
                    ps.setLong(14, entry.sampleCount());
                    if (entry.startTimestampNanos() == null) ps.setNull(15, java.sql.Types.BIGINT);
                    else ps.setLong(15, entry.startTimestampNanos());
                    if (entry.endTimestampNanos() == null) ps.setNull(16, java.sql.Types.BIGINT);
                    else ps.setLong(16, entry.endTimestampNanos());
                    if (entry.minValue() == null) ps.setNull(17, java.sql.Types.DOUBLE);
                    else ps.setDouble(17, entry.minValue());
                    if (entry.maxValue() == null) ps.setNull(18, java.sql.Types.DOUBLE);
                    else ps.setDouble(18, entry.maxValue());
                    ps.setString(19, entry.createdAt().toString());
                    ps.setString(20, entry.updatedAt().toString());
                });
            }
            touchProject(projectId);
        });
    }

    @Override
    public synchronized List<SignalCatalogMetadata> listSignalCatalog(String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) return List.of();
        List<SignalCatalogMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM signal_catalog WHERE project_id=? ORDER BY signal_name, source_id, channel_id
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapSignalCatalog(rs));
            }
        } catch (SQLException e) {
            throw failure("Cannot list signal catalog", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized List<SignalCatalogMetadata> searchSignalCatalog(String projectId, String query) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) return List.of();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return listSignalCatalog(projectId);
        List<SignalCatalogMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM signal_catalog WHERE project_id=? AND (
                  lower(signal_name) LIKE ? OR lower(message_name) LIKE ? OR lower(source_id) LIKE ?
                  OR lower(definition_id) LIKE ? OR lower(unit) LIKE ?
                  OR printf('%d', can_id) LIKE ? OR lower(printf('0x%x', can_id)) LIKE ?
                ) ORDER BY signal_name, source_id
                """)) {
            String like = "%" + q + "%";
            ps.setString(1, projectId);
            for (int i = 2; i <= 8; i++) ps.setString(i, like);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapSignalCatalog(rs));
            }
        } catch (SQLException e) {
            throw failure("Cannot search signal catalog", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized Optional<SignalProvenance> getSignalProvenance(String projectId, String signalCatalogId) {
        ensureOpen();
        if (projectId == null || signalCatalogId == null) return Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT sc.*, a.source_fingerprint, a.dbc_fingerprint, a.mapping_lifecycle,
                       a.artifact_storage_ref, a.artifact_version, a.artifact_path,
                       a.processing_run_id AS run_id, r.status AS run_status,
                       sf.path AS source_path,
                       df.path AS definition_path
                FROM signal_catalog sc
                LEFT JOIN processing_artifacts a ON a.artifact_id = sc.artifact_id
                LEFT JOIN processing_runs r ON r.run_id = a.processing_run_id
                LEFT JOIN project_files sf ON sf.project_id = sc.project_id AND sf.file_id = sc.source_id
                LEFT JOIN project_files df ON df.project_id = sc.project_id AND df.file_id = sc.definition_id
                WHERE sc.project_id=? AND sc.signal_catalog_id=?
                """)) {
            ps.setString(1, projectId);
            ps.setString(2, signalCatalogId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String sourcePath = rs.getString("source_path");
                String defPath = rs.getString("definition_path");
                String storageRef = rs.getString("artifact_storage_ref");
                if (storageRef == null || storageRef.isBlank()) {
                    storageRef = rs.getString("artifact_path");
                }
                return Optional.of(new SignalProvenance(
                        rs.getString("signal_catalog_id"),
                        rs.getString("signal_name"),
                        rs.getString("message_name"),
                        rs.getLong("can_id"),
                        rs.getString("unit"),
                        projectId,
                        rs.getString("source_id"),
                        sourcePath == null ? "" : sourcePath,
                        fileNameOf(sourcePath),
                        nullToEmpty(rs.getString("source_fingerprint")),
                        rs.getInt("channel_id"),
                        rs.getString("definition_id"),
                        defPath == null ? "" : defPath,
                        fileNameOf(defPath),
                        nullToEmpty(rs.getString("dbc_fingerprint")),
                        nullToEmpty(rs.getString("mapping_lifecycle")),
                        nullToEmpty(rs.getString("run_id")),
                        nullToEmpty(rs.getString("run_status")),
                        nullToEmpty(rs.getString("artifact_id")),
                        nullToEmpty(storageRef),
                        nullToEmpty(rs.getString("artifact_version")),
                        rs.getLong("sample_count"),
                        nullableLong(rs, "start_timestamp_nanos"),
                        nullableLong(rs, "end_timestamp_nanos")
                ));
            }
        } catch (SQLException e) {
            throw failure("Cannot load signal provenance", e);
        }
    }

    @Override
    public synchronized void upsertBookmark(EngineeringBookmarkMetadata bookmark) {
        ensureOpen();
        Objects.requireNonNull(bookmark, "bookmark");
        executeUpdate("""
                INSERT INTO engineering_bookmarks(
                  bookmark_id, project_id, source_id, artifact_id, signal_name, signal_key,
                  timestamp_nanos, event_type, observation, created_at, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(bookmark_id) DO UPDATE SET
                  timestamp_nanos=excluded.timestamp_nanos,
                  event_type=excluded.event_type,
                  observation=excluded.observation,
                  updated_at=excluded.updated_at
                """, ps -> {
            ps.setString(1, bookmark.bookmarkId());
            ps.setString(2, bookmark.projectId());
            ps.setString(3, bookmark.sourceId());
            ps.setString(4, bookmark.artifactId());
            ps.setString(5, bookmark.signalName());
            ps.setString(6, bookmark.signalKey());
            ps.setLong(7, bookmark.timestampNanos());
            ps.setString(8, bookmark.eventType());
            ps.setString(9, bookmark.observation());
            ps.setString(10, bookmark.createdAt().toString());
            ps.setString(11, bookmark.updatedAt().toString());
        });
        touchProject(bookmark.projectId());
    }

    @Override
    public synchronized void deleteBookmark(String projectId, String bookmarkId) {
        ensureOpen();
        executeUpdate("DELETE FROM engineering_bookmarks WHERE project_id=? AND bookmark_id=?", ps -> {
            ps.setString(1, projectId);
            ps.setString(2, bookmarkId);
        });
        touchProject(projectId);
    }

    @Override
    public synchronized List<EngineeringBookmarkMetadata> listBookmarks(String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) return List.of();
        List<EngineeringBookmarkMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM engineering_bookmarks WHERE project_id=? ORDER BY timestamp_nanos
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new EngineeringBookmarkMetadata(
                            rs.getString("bookmark_id"),
                            projectId,
                            rs.getString("source_id"),
                            rs.getString("artifact_id"),
                            rs.getString("signal_name"),
                            rs.getString("signal_key"),
                            rs.getLong("timestamp_nanos"),
                            rs.getString("event_type"),
                            rs.getString("observation"),
                            Instant.parse(rs.getString("created_at")),
                            Instant.parse(rs.getString("updated_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list bookmarks", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void upsertMapRouteState(MapRouteStateMetadata state) {
        ensureOpen();
        Objects.requireNonNull(state, "state");
        executeUpdate("""
                INSERT INTO map_route_state(
                  project_id, source_id, mode, point_timestamp_nanos,
                  range_start_nanos, range_end_nanos, auto_sync, updated_at)
                VALUES(?,?,?,?,?,?,?,?)
                ON CONFLICT(project_id, source_id) DO UPDATE SET
                  mode=excluded.mode,
                  point_timestamp_nanos=excluded.point_timestamp_nanos,
                  range_start_nanos=excluded.range_start_nanos,
                  range_end_nanos=excluded.range_end_nanos,
                  auto_sync=excluded.auto_sync,
                  updated_at=excluded.updated_at
                """, ps -> {
            ps.setString(1, state.projectId());
            ps.setString(2, state.sourceId());
            ps.setString(3, state.mode());
            setNullableLong(ps, 4, state.pointTimestampNanos());
            setNullableLong(ps, 5, state.rangeStartNanos());
            setNullableLong(ps, 6, state.rangeEndNanos());
            ps.setInt(7, state.autoSync() ? 1 : 0);
            ps.setString(8, state.updatedAt().toString());
        });
        // Deliberately no touchProject: a transient view selection must not age the project record.
    }

    @Override
    public synchronized Optional<MapRouteStateMetadata> findMapRouteState(String projectId, String sourceId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) return Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM map_route_state WHERE project_id=? AND source_id=? LIMIT 1
                """)) {
            ps.setString(1, projectId);
            ps.setString(2, sourceId == null ? "" : sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new MapRouteStateMetadata(
                        projectId,
                        rs.getString("source_id"),
                        rs.getString("mode"),
                        nullableLong(rs, "point_timestamp_nanos"),
                        nullableLong(rs, "range_start_nanos"),
                        nullableLong(rs, "range_end_nanos"),
                        rs.getInt("auto_sync") != 0,
                        Instant.parse(rs.getString("updated_at"))
                ));
            }
        } catch (SQLException e) {
            throw failure("Cannot load map route state", e);
        }
    }

    @Override
    public synchronized void upsertGraphInvestigation(GraphInvestigationMetadata state) {
        ensureOpen();
        Objects.requireNonNull(state, "state");
        executeUpdate("""
                INSERT INTO graph_investigation(
                  project_id, layout_mode, viewport_start_nanos, viewport_end_nanos,
                  default_start_nanos, default_end_nanos, cursor_a_nanos, cursor_b_nanos,
                  notes, traces_blob, updated_at,
                  focus_key_blob, analysis_tab, inspector_visible, plot_settings_visible,
                  signals_display_mode, signals_precision, presentation_version)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(project_id) DO UPDATE SET
                  layout_mode=excluded.layout_mode,
                  viewport_start_nanos=excluded.viewport_start_nanos,
                  viewport_end_nanos=excluded.viewport_end_nanos,
                  default_start_nanos=excluded.default_start_nanos,
                  default_end_nanos=excluded.default_end_nanos,
                  cursor_a_nanos=excluded.cursor_a_nanos,
                  cursor_b_nanos=excluded.cursor_b_nanos,
                  notes=excluded.notes,
                  traces_blob=excluded.traces_blob,
                  updated_at=excluded.updated_at,
                  focus_key_blob=excluded.focus_key_blob,
                  analysis_tab=excluded.analysis_tab,
                  inspector_visible=excluded.inspector_visible,
                  plot_settings_visible=excluded.plot_settings_visible,
                  signals_display_mode=excluded.signals_display_mode,
                  signals_precision=excluded.signals_precision,
                  presentation_version=excluded.presentation_version
                """, ps -> {
            ps.setString(1, state.projectId());
            ps.setString(2, state.layoutMode());
            setNullableLong(ps, 3, state.viewportStartNanos());
            setNullableLong(ps, 4, state.viewportEndNanos());
            setNullableLong(ps, 5, state.defaultStartNanos());
            setNullableLong(ps, 6, state.defaultEndNanos());
            setNullableLong(ps, 7, state.cursorANanos());
            setNullableLong(ps, 8, state.cursorBNanos());
            ps.setString(9, state.notes());
            ps.setString(10, state.tracesBlob());
            ps.setString(11, state.updatedAt().toString());
            ps.setString(12, state.focusKeyBlob());
            ps.setInt(13, state.analysisTab());
            ps.setInt(14, state.inspectorVisible() ? 1 : 0);
            ps.setInt(15, state.plotSettingsVisible() ? 1 : 0);
            ps.setString(16, state.signalsDisplayMode());
            ps.setString(17, state.signalsPrecision());
            ps.setInt(18, state.presentationVersion());
        });
    }

    @Override
    public synchronized Optional<GraphInvestigationMetadata> findGraphInvestigation(String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM graph_investigation WHERE project_id=? LIMIT 1
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new GraphInvestigationMetadata(
                        projectId,
                        rs.getString("layout_mode"),
                        nullableLong(rs, "viewport_start_nanos"),
                        nullableLong(rs, "viewport_end_nanos"),
                        nullableLong(rs, "default_start_nanos"),
                        nullableLong(rs, "default_end_nanos"),
                        nullableLong(rs, "cursor_a_nanos"),
                        nullableLong(rs, "cursor_b_nanos"),
                        nullToEmpty(rs.getString("notes")),
                        nullToEmpty(rs.getString("traces_blob")),
                        Instant.parse(rs.getString("updated_at")),
                        optionalString(rs, "focus_key_blob"),
                        optionalInt(rs, "analysis_tab", 0),
                        optionalInt(rs, "inspector_visible", 1) != 0,
                        optionalInt(rs, "plot_settings_visible", 0) != 0,
                        optionalString(rs, "signals_display_mode"),
                        optionalString(rs, "signals_precision"),
                        optionalInt(rs, "presentation_version", GraphInvestigationMetadata.PRESENTATION_VERSION)
                ));
            }
        } catch (SQLException e) {
            throw failure("Cannot load graph investigation", e);
        }
    }

    @Override
    public synchronized void upsertAnalysisScope(AnalysisScopeRecord record) {
        ensureOpen();
        Objects.requireNonNull(record, "record");
        executeUpdate("""
                INSERT INTO analysis_scope(project_id, scope_kind, source_id, file_name, updated_at)
                VALUES(?,?,?,?,?)
                ON CONFLICT(project_id) DO UPDATE SET
                  scope_kind=excluded.scope_kind,
                  source_id=excluded.source_id,
                  file_name=excluded.file_name,
                  updated_at=excluded.updated_at
                """, ps -> {
            ps.setString(1, record.projectId());
            ps.setString(2, record.scopeKind());
            ps.setString(3, record.sourceId());
            ps.setString(4, record.fileName());
            ps.setString(5, record.updatedAt().toString());
        });
    }

    @Override
    public synchronized Optional<AnalysisScopeRecord> findAnalysisScope(String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT project_id, scope_kind, source_id, file_name, updated_at
                FROM analysis_scope WHERE project_id=? LIMIT 1
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Instant updated = Instant.now();
                try {
                    String raw = rs.getString("updated_at");
                    if (raw != null && !raw.isBlank()) {
                        updated = Instant.parse(raw);
                    }
                } catch (RuntimeException ignored) {
                }
                return Optional.of(new AnalysisScopeRecord(
                        rs.getString("project_id"),
                        rs.getString("scope_kind"),
                        rs.getString("source_id"),
                        rs.getString("file_name"),
                        updated));
            }
        } catch (SQLException e) {
            throw failure("Cannot load analysis scope", e);
        }
    }

    @Override
    public synchronized void deleteGraphInvestigation(String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        executeUpdate("DELETE FROM graph_investigation WHERE project_id=?", ps -> ps.setString(1, projectId));
    }

    @Override
    public synchronized void upsertSemanticSignalMapping(SemanticSignalMappingMetadata mapping) {
        ensureOpen();
        Objects.requireNonNull(mapping, "mapping");
        executeUpdate("""
                INSERT INTO semantic_signal_mapping(
                  project_id, source_id, schema_fingerprint, config_group, scope, role, mapping_mode,
                  signal_name, source_type, source_file, message_name, channel, manual_text,
                  user_confirmed, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(project_id, source_id, role) DO UPDATE SET
                  schema_fingerprint=excluded.schema_fingerprint,
                  config_group=excluded.config_group,
                  scope=excluded.scope,
                  mapping_mode=excluded.mapping_mode,
                  signal_name=excluded.signal_name,
                  source_type=excluded.source_type,
                  source_file=excluded.source_file,
                  message_name=excluded.message_name,
                  channel=excluded.channel,
                  manual_text=excluded.manual_text,
                  user_confirmed=excluded.user_confirmed,
                  updated_at=excluded.updated_at
                """, ps -> {
            ps.setString(1, mapping.projectId());
            ps.setString(2, mapping.sourceId());
            ps.setString(3, mapping.schemaFingerprint());
            ps.setString(4, mapping.configGroup());
            ps.setString(5, mapping.scope());
            ps.setString(6, mapping.role());
            ps.setString(7, mapping.mappingMode());
            ps.setString(8, mapping.signalName());
            ps.setString(9, mapping.sourceType());
            ps.setString(10, mapping.sourceFile());
            ps.setString(11, mapping.messageName());
            ps.setInt(12, mapping.channel());
            ps.setString(13, mapping.manualText());
            ps.setInt(14, mapping.userConfirmed() ? 1 : 0);
            ps.setString(15, mapping.updatedAt().toString());
        });
    }

    @Override
    public synchronized List<SemanticSignalMappingMetadata> listSemanticSignalMappings(String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) {
            return List.of();
        }
        List<SemanticSignalMappingMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM semantic_signal_mapping WHERE project_id=? ORDER BY role
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readSemanticMapping(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot list semantic mappings", ex);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized List<SemanticSignalMappingMetadata> listSemanticSignalMappings(
            String projectId, String sourceId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) {
            return List.of();
        }
        List<SemanticSignalMappingMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM semantic_signal_mapping WHERE project_id=? AND source_id=? ORDER BY role
                """)) {
            ps.setString(1, projectId);
            ps.setString(2, sourceId == null ? "" : sourceId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readSemanticMapping(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Cannot list semantic mappings for source", ex);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void deleteSemanticSignalMappings(String projectId, String sourceId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        executeUpdate("DELETE FROM semantic_signal_mapping WHERE project_id=? AND source_id=?", ps -> {
            ps.setString(1, projectId);
            ps.setString(2, sourceId == null ? "" : sourceId);
        });
    }

    @Override
    public synchronized void deleteSemanticSignalMappings(String projectId, String sourceId, String configGroup) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) {
            return;
        }
        if (configGroup == null || configGroup.isBlank()) {
            deleteSemanticSignalMappings(projectId, sourceId);
            return;
        }
        executeUpdate("DELETE FROM semantic_signal_mapping WHERE project_id=? AND source_id=? AND config_group=?",
                ps -> {
                    ps.setString(1, projectId);
                    ps.setString(2, sourceId == null ? "" : sourceId);
                    ps.setString(3, configGroup);
                });
    }

    private static SemanticSignalMappingMetadata readSemanticMapping(ResultSet rs) throws SQLException {
        Instant updated;
        try {
            updated = Instant.parse(rs.getString("updated_at"));
        } catch (RuntimeException ex) {
            updated = Instant.now();
        }
        return new SemanticSignalMappingMetadata(
                rs.getString("project_id"),
                rs.getString("source_id"),
                rs.getString("schema_fingerprint"),
                rs.getString("config_group"),
                rs.getString("scope"),
                rs.getString("role"),
                rs.getString("mapping_mode"),
                rs.getString("signal_name"),
                rs.getString("source_type"),
                rs.getString("source_file"),
                rs.getString("message_name"),
                rs.getInt("channel"),
                rs.getString("manual_text"),
                rs.getInt("user_confirmed") != 0,
                updated);
    }

    @Override
    public synchronized void deleteMapRouteState(String projectId, String sourceId) {
        ensureOpen();
        executeUpdate("DELETE FROM map_route_state WHERE project_id=? AND source_id=?", ps -> {
            ps.setString(1, projectId);
            ps.setString(2, sourceId == null ? "" : sourceId);
        });
    }

    @Override
    public synchronized void deleteBookmarksForProject(String projectId) {
        ensureOpen();
        executeUpdate("DELETE FROM engineering_bookmarks WHERE project_id=?", ps -> ps.setString(1, projectId));
        touchProject(projectId);
    }

    @Override
    public synchronized void insertEvaluationRule(EvaluationRuleRecord record) {
        ensureOpen();
        Objects.requireNonNull(record, "record");
        executeUpdate("""
                INSERT INTO evaluation_rules(project_id, rule_id, version, document, created_at)
                VALUES(?,?,?,?,?)
                """, ps -> {
            ps.setString(1, record.projectId());
            ps.setString(2, record.ruleId());
            ps.setInt(3, record.version());
            ps.setString(4, record.document());
            ps.setString(5, record.createdAt().toString());
        });
        touchProject(record.projectId());
    }

    @Override
    public synchronized List<EvaluationRuleRecord> listEvaluationRules(String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) {
            return List.of();
        }
        List<EvaluationRuleRecord> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT rule_id, version, document, created_at FROM evaluation_rules
                WHERE project_id=? ORDER BY rule_id, version
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new EvaluationRuleRecord(projectId, rs.getString("rule_id"), rs.getInt("version"),
                            rs.getString("document"), Instant.parse(rs.getString("created_at"))));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list evaluation rules", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void insertEvaluationResult(EvaluationResultRecord record) {
        ensureOpen();
        Objects.requireNonNull(record, "record");
        executeUpdate("""
                INSERT INTO evaluation_results(
                  project_id, result_id, event_id, rule_id, rule_version, included, document, created_at)
                VALUES(?,?,?,?,?,?,?,?)
                """, ps -> {
            ps.setString(1, record.projectId());
            ps.setString(2, record.resultId());
            ps.setString(3, record.eventId());
            ps.setString(4, record.ruleId());
            ps.setInt(5, record.ruleVersion());
            ps.setInt(6, record.included() ? 1 : 0);
            ps.setString(7, record.document());
            ps.setString(8, record.createdAt().toString());
        });
        touchProject(record.projectId());
    }

    @Override
    public synchronized List<EvaluationResultRecord> listEvaluationResults(String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) {
            return List.of();
        }
        List<EvaluationResultRecord> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT result_id, event_id, rule_id, rule_version, included, document, created_at
                FROM evaluation_results WHERE project_id=? ORDER BY created_at
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new EvaluationResultRecord(projectId, rs.getString("result_id"), rs.getString("event_id"),
                            rs.getString("rule_id"), rs.getInt("rule_version"), rs.getInt("included") != 0,
                            rs.getString("document"), Instant.parse(rs.getString("created_at"))));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list evaluation results", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void setEvaluationIncluded(String projectId, String resultId, boolean included) {
        ensureOpen();
        executeUpdate("UPDATE evaluation_results SET included=? WHERE project_id=? AND result_id=?", ps -> {
            ps.setInt(1, included ? 1 : 0);
            ps.setString(2, projectId);
            ps.setString(3, resultId);
        });
    }

    @Override
    public synchronized void upsertDataQualityFinding(DataQualityFindingMetadata finding) {
        ensureOpen();
        Objects.requireNonNull(finding, "finding");
        executeUpdate("""
                INSERT INTO data_quality_findings(
                  finding_id, project_id, source_id, channel_id, finding_type, severity,
                  start_timestamp_nanos, end_timestamp_nanos, summary, technical_detail,
                  processing_run_id, created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(finding_id) DO UPDATE SET
                  summary=excluded.summary,
                  technical_detail=excluded.technical_detail,
                  severity=excluded.severity
                """, ps -> {
            ps.setString(1, finding.findingId());
            ps.setString(2, finding.projectId());
            ps.setString(3, finding.sourceId());
            if (finding.channelId() == null) ps.setNull(4, java.sql.Types.INTEGER);
            else ps.setInt(4, finding.channelId());
            ps.setString(5, finding.findingType());
            ps.setString(6, finding.severity());
            if (finding.startTimestampNanos() == null) ps.setNull(7, java.sql.Types.BIGINT);
            else ps.setLong(7, finding.startTimestampNanos());
            if (finding.endTimestampNanos() == null) ps.setNull(8, java.sql.Types.BIGINT);
            else ps.setLong(8, finding.endTimestampNanos());
            ps.setString(9, finding.summary());
            ps.setString(10, finding.technicalDetail());
            ps.setString(11, finding.processingRunId());
            ps.setString(12, finding.createdAt().toString());
        });
        touchProject(finding.projectId());
    }

    @Override
    public synchronized List<DataQualityFindingMetadata> listDataQualityFindings(String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) return List.of();
        List<DataQualityFindingMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM data_quality_findings WHERE project_id=? ORDER BY created_at
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int channel = rs.getInt("channel_id");
                    boolean channelNull = rs.wasNull();
                    out.add(new DataQualityFindingMetadata(
                            rs.getString("finding_id"),
                            projectId,
                            rs.getString("source_id"),
                            channelNull ? null : channel,
                            rs.getString("finding_type"),
                            rs.getString("severity"),
                            nullableLong(rs, "start_timestamp_nanos"),
                            nullableLong(rs, "end_timestamp_nanos"),
                            rs.getString("summary"),
                            rs.getString("technical_detail"),
                            rs.getString("processing_run_id"),
                            Instant.parse(rs.getString("created_at"))
                    ));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list data quality findings", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void deleteDataQualityFindingsForSource(String projectId, String sourceId) {
        ensureOpen();
        executeUpdate("DELETE FROM data_quality_findings WHERE project_id=? AND source_id=?", ps -> {
            ps.setString(1, projectId);
            ps.setString(2, sourceId);
        });
        touchProject(projectId);
    }

    @Override
    public synchronized List<HistoricalDbcMappingSummary> findHistoricalDbcMappings(
            String excludeProjectId,
            List<Long> observedCanIds
    ) {
        ensureOpen();
        // Supporting evidence from confirmed/manual mappings — no new AI logic.
        List<HistoricalDbcMappingSummary> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT mapped_definition_id AS definition_id,
                       COUNT(*) AS use_count,
                       COUNT(DISTINCT project_id) AS project_count,
                       AVG(coverage_ratio) AS avg_coverage
                FROM channel_mappings
                WHERE mapped_definition_id IS NOT NULL AND mapped_definition_id <> ''
                  AND status IN ('CONFIRMED','MANUAL')
                  AND (? IS NULL OR project_id <> ?)
                GROUP BY mapped_definition_id
                ORDER BY use_count DESC, avg_coverage DESC
                LIMIT 25
                """)) {
            ps.setString(1, excludeProjectId);
            ps.setString(2, excludeProjectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String defId = rs.getString("definition_id");
                    out.add(new HistoricalDbcMappingSummary(
                            defId, defId, "", rs.getLong("use_count"),
                            rs.getLong("project_count"), rs.getDouble("avg_coverage")));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot query historical DBC mappings", e);
        }
        return List.copyOf(out);
    }

    private ProcessingArtifactMetadata mapArtifact(String projectId, ResultSet rs) throws SQLException {
        long start = rs.getLong("start_timestamp_nanos");
        boolean startNull = rs.wasNull();
        long end = rs.getLong("end_timestamp_nanos");
        boolean endNull = rs.wasNull();
        String storageRef = optionalColumn(rs, "artifact_storage_ref");
        return new ProcessingArtifactMetadata(
                rs.getString("artifact_id"), projectId, rs.getString("source_id"), rs.getInt("channel_id"),
                rs.getString("definition_id"), rs.getString("processing_run_id"), rs.getString("artifact_type"),
                rs.getString("artifact_path"), storageRef,
                rs.getString("artifact_version"), rs.getString("source_fingerprint"),
                rs.getString("dbc_fingerprint"), optionalColumn(rs, "plan_fingerprint"),
                rs.getLong("signal_count"), rs.getLong("sample_count"),
                startNull ? null : start, endNull ? null : end, rs.getString("status"),
                Instant.parse(rs.getString("created_at")), rs.getString("mapping_lifecycle"),
                optionalColumn(rs, "job_execution_status"),
                optionalColumn(rs, "diagnostics_summary"),
                optionalColumn(rs, "technical_detail"));
    }

    private static String optionalColumn(ResultSet rs, String column) {
        try {
            String value = rs.getString(column);
            return value == null ? "" : value;
        } catch (SQLException missing) {
            return "";
        }
    }

    private static SignalCatalogMetadata mapSignalCatalog(ResultSet rs) throws SQLException {
        return new SignalCatalogMetadata(
                rs.getString("signal_catalog_id"),
                rs.getString("project_id"),
                rs.getString("source_id"),
                rs.getInt("channel_id"),
                rs.getString("definition_id"),
                rs.getString("artifact_id"),
                rs.getString("signal_key"),
                rs.getString("signal_name"),
                rs.getString("message_name"),
                rs.getLong("can_id"),
                rs.getInt("signal_channel"),
                rs.getString("source_type"),
                rs.getString("unit"),
                rs.getLong("sample_count"),
                nullableLong(rs, "start_timestamp_nanos"),
                nullableLong(rs, "end_timestamp_nanos"),
                nullableDouble(rs, "min_value"),
                nullableDouble(rs, "max_value"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String fileNameOf(String path) {
        if (path == null || path.isBlank()) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    // ---------------------------------------------------------------------
    // Durable processing ledger (schema v12): jobs, batches, checkpoints, errors.
    // ---------------------------------------------------------------------

    @Override
    public synchronized void upsertProcessingJob(ProcessingJobMetadata job) {
        ensureOpen();
        Objects.requireNonNull(job, "job");
        executeUpdate("""
                INSERT INTO processing_jobs(
                  job_id, project_id, run_id, source_id, channel_id, definition_id,
                  status, attempt, max_attempts, progress, stage, worker_id,
                  created_at, started_at, finished_at, failure_class, message)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(job_id) DO UPDATE SET
                  run_id=excluded.run_id,
                  project_id=excluded.project_id,
                  source_id=excluded.source_id,
                  channel_id=excluded.channel_id,
                  definition_id=excluded.definition_id,
                  status=excluded.status,
                  attempt=excluded.attempt,
                  max_attempts=excluded.max_attempts,
                  progress=excluded.progress,
                  stage=excluded.stage,
                  worker_id=excluded.worker_id,
                  started_at=excluded.started_at,
                  finished_at=excluded.finished_at,
                  failure_class=excluded.failure_class,
                  message=excluded.message
                """, ps -> {
            ps.setString(1, job.jobId());
            ps.setString(2, job.projectId());
            ps.setString(3, job.runId());
            ps.setString(4, job.sourceId());
            ps.setInt(5, job.channelId());
            ps.setString(6, job.definitionId());
            ps.setString(7, job.status().name());
            ps.setInt(8, job.attempt());
            ps.setInt(9, job.maxAttempts());
            ps.setDouble(10, job.progress());
            ps.setString(11, job.stage());
            ps.setString(12, job.workerId());
            ps.setString(13, job.createdAt().toString());
            setNullableInstant(ps, 14, job.startedAt());
            setNullableInstant(ps, 15, job.finishedAt());
            setNullable(ps, 16, job.failureClass() == null ? null : job.failureClass().name());
            ps.setString(17, job.message());
        });
    }

    @Override
    public synchronized Optional<ProcessingJobMetadata> findProcessingJob(String jobId) {
        ensureOpen();
        if (jobId == null || jobId.isBlank()) return Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM processing_jobs WHERE job_id=?")) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapProcessingJob(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("Cannot read processing job", e);
        }
    }

    @Override
    public synchronized List<ProcessingJobMetadata> listProcessingJobs(String projectId) {
        return queryProcessingJobs(
                "SELECT * FROM processing_jobs WHERE project_id=? ORDER BY created_at, job_id", projectId);
    }

    @Override
    public synchronized List<ProcessingJobMetadata> listUnfinishedProcessingJobs(String projectId) {
        return queryProcessingJobs("""
                SELECT * FROM processing_jobs
                WHERE project_id=? AND status IN ('QUEUED','RUNNING','RETRY_WAIT','INTERRUPTED')
                ORDER BY created_at, job_id
                """, projectId);
    }

    private List<ProcessingJobMetadata> queryProcessingJobs(String sql, String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) return List.of();
        List<ProcessingJobMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapProcessingJob(rs));
            }
        } catch (SQLException e) {
            throw failure("Cannot list processing jobs", e);
        }
        return List.copyOf(out);
    }

    private static ProcessingJobMetadata mapProcessingJob(ResultSet rs) throws SQLException {
        return new ProcessingJobMetadata(
                rs.getString("job_id"),
                rs.getString("project_id"),
                rs.getString("run_id"),
                rs.getString("source_id"),
                rs.getInt("channel_id"),
                rs.getString("definition_id"),
                enumValue(JobExecutionStatus.class, rs.getString("status"), JobExecutionStatus.QUEUED),
                rs.getInt("attempt"),
                rs.getInt("max_attempts"),
                rs.getDouble("progress"),
                rs.getString("stage"),
                rs.getString("worker_id"),
                Instant.parse(rs.getString("created_at")),
                nullableInstant(rs, "started_at"),
                nullableInstant(rs, "finished_at"),
                ProcessingFailureClass.parse(rs.getString("failure_class"), null),
                rs.getString("message"));
    }

    @Override
    public synchronized void upsertProcessingBatch(ProcessingBatchMetadata batch) {
        ensureOpen();
        Objects.requireNonNull(batch, "batch");
        executeUpdate("""
                INSERT INTO processing_batches(
                  batch_id, project_id, job_id, sequence_no, input_start, input_end,
                  records_processed, samples_produced, status, artifact_ref, started_at, committed_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(batch_id) DO UPDATE SET
                  input_end=excluded.input_end,
                  records_processed=excluded.records_processed,
                  samples_produced=excluded.samples_produced,
                  status=excluded.status,
                  artifact_ref=excluded.artifact_ref,
                  committed_at=excluded.committed_at
                """, ps -> {
            ps.setString(1, batch.batchId());
            ps.setString(2, batch.projectId());
            ps.setString(3, batch.jobId());
            ps.setLong(4, batch.sequence());
            ps.setLong(5, batch.inputStart());
            ps.setLong(6, batch.inputEnd());
            ps.setLong(7, batch.recordsProcessed());
            ps.setLong(8, batch.samplesProduced());
            ps.setString(9, batch.status().name());
            ps.setString(10, batch.artifactRef());
            ps.setString(11, batch.startedAt().toString());
            setNullableInstant(ps, 12, batch.committedAt());
        });
    }

    @Override
    public synchronized List<ProcessingBatchMetadata> listProcessingBatches(String jobId) {
        ensureOpen();
        if (jobId == null || jobId.isBlank()) return List.of();
        List<ProcessingBatchMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM processing_batches WHERE job_id=? ORDER BY sequence_no")) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapProcessingBatch(rs));
            }
        } catch (SQLException e) {
            throw failure("Cannot list processing batches", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized Optional<ProcessingBatchMetadata> findLastCommittedBatch(String jobId) {
        ensureOpen();
        if (jobId == null || jobId.isBlank()) return Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM processing_batches
                WHERE job_id=? AND status=?
                ORDER BY sequence_no DESC LIMIT 1
                """)) {
            ps.setString(1, jobId);
            ps.setString(2, ProcessingBatchStatus.COMMITTED.name());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapProcessingBatch(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("Cannot read last committed batch", e);
        }
    }

    private static ProcessingBatchMetadata mapProcessingBatch(ResultSet rs) throws SQLException {
        return new ProcessingBatchMetadata(
                rs.getString("batch_id"),
                rs.getString("project_id"),
                rs.getString("job_id"),
                rs.getLong("sequence_no"),
                rs.getLong("input_start"),
                rs.getLong("input_end"),
                rs.getLong("records_processed"),
                rs.getLong("samples_produced"),
                enumValue(ProcessingBatchStatus.class, rs.getString("status"), ProcessingBatchStatus.OPEN),
                rs.getString("artifact_ref"),
                Instant.parse(rs.getString("started_at")),
                nullableInstant(rs, "committed_at"));
    }

    @Override
    public synchronized void upsertProcessingCheckpoint(ProcessingCheckpointMetadata checkpoint) {
        ensureOpen();
        Objects.requireNonNull(checkpoint, "checkpoint");
        executeUpdate("""
                INSERT INTO processing_checkpoints(
                  checkpoint_id, project_id, job_id, source_id,
                  source_fingerprint, dbc_fingerprint, plan_fingerprint, artifact_version,
                  input_position, batch_sequence, last_timestamp_nanos, cursor_payload,
                  committed_artifact_ref, state, created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(job_id) DO UPDATE SET
                  source_fingerprint=excluded.source_fingerprint,
                  dbc_fingerprint=excluded.dbc_fingerprint,
                  plan_fingerprint=excluded.plan_fingerprint,
                  artifact_version=excluded.artifact_version,
                  input_position=excluded.input_position,
                  batch_sequence=excluded.batch_sequence,
                  last_timestamp_nanos=excluded.last_timestamp_nanos,
                  cursor_payload=excluded.cursor_payload,
                  committed_artifact_ref=excluded.committed_artifact_ref,
                  state=excluded.state,
                  created_at=excluded.created_at
                """, ps -> {
            ps.setString(1, checkpoint.checkpointId());
            ps.setString(2, checkpoint.projectId());
            ps.setString(3, checkpoint.jobId());
            ps.setString(4, checkpoint.sourceId());
            ps.setString(5, checkpoint.sourceFingerprint());
            ps.setString(6, checkpoint.dbcFingerprint());
            ps.setString(7, checkpoint.planFingerprint());
            ps.setString(8, checkpoint.artifactVersion());
            ps.setLong(9, checkpoint.inputPosition());
            ps.setLong(10, checkpoint.batchSequence());
            setNullableLong(ps, 11, checkpoint.lastTimestampNanos());
            ps.setString(12, checkpoint.cursorPayload());
            ps.setString(13, checkpoint.committedArtifactRef());
            ps.setString(14, checkpoint.state().name());
            ps.setString(15, checkpoint.createdAt().toString());
        });
    }

    @Override
    public synchronized Optional<ProcessingCheckpointMetadata> findProcessingCheckpoint(String jobId) {
        ensureOpen();
        if (jobId == null || jobId.isBlank()) return Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM processing_checkpoints WHERE job_id=?")) {
            ps.setString(1, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new ProcessingCheckpointMetadata(
                        rs.getString("checkpoint_id"),
                        rs.getString("project_id"),
                        rs.getString("job_id"),
                        rs.getString("source_id"),
                        rs.getString("source_fingerprint"),
                        rs.getString("dbc_fingerprint"),
                        rs.getString("plan_fingerprint"),
                        rs.getString("artifact_version"),
                        rs.getLong("input_position"),
                        rs.getLong("batch_sequence"),
                        nullableLong(rs, "last_timestamp_nanos"),
                        rs.getString("cursor_payload"),
                        rs.getString("committed_artifact_ref"),
                        enumValue(ProcessingCheckpointState.class, rs.getString("state"),
                                ProcessingCheckpointState.RESUMABLE),
                        Instant.parse(rs.getString("created_at"))));
            }
        } catch (SQLException e) {
            throw failure("Cannot read processing checkpoint", e);
        }
    }

    @Override
    public synchronized void invalidateProcessingCheckpoint(String jobId) {
        ensureOpen();
        if (jobId == null || jobId.isBlank()) return;
        executeUpdate("UPDATE processing_checkpoints SET state=? WHERE job_id=?", ps -> {
            ps.setString(1, ProcessingCheckpointState.INVALIDATED.name());
            ps.setString(2, jobId);
        });
    }

    @Override
    public synchronized void appendProcessingError(ProcessingErrorMetadata error) {
        ensureOpen();
        Objects.requireNonNull(error, "error");
        executeUpdate("""
                INSERT INTO processing_errors(
                  error_id, project_id, run_id, job_id, source_id,
                  classification, severity, retryable, summary, technical_detail, created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(error_id) DO NOTHING
                """, ps -> {
            ps.setString(1, error.errorId());
            ps.setString(2, error.projectId());
            ps.setString(3, error.runId());
            ps.setString(4, error.jobId());
            ps.setString(5, error.sourceId());
            ps.setString(6, error.classification().name());
            ps.setString(7, error.severity());
            ps.setInt(8, error.retryable() ? 1 : 0);
            ps.setString(9, error.summary());
            ps.setString(10, error.technicalDetail());
            ps.setString(11, error.createdAt().toString());
        });
    }

    @Override
    public synchronized List<ProcessingErrorMetadata> listProcessingErrors(String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) return List.of();
        List<ProcessingErrorMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM processing_errors WHERE project_id=? ORDER BY created_at, error_id")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ProcessingErrorMetadata(
                            rs.getString("error_id"),
                            projectId,
                            rs.getString("run_id"),
                            rs.getString("job_id"),
                            rs.getString("source_id"),
                            ProcessingFailureClass.parse(rs.getString("classification"),
                                    ProcessingFailureClass.INTERNAL_ERROR),
                            rs.getString("severity"),
                            rs.getInt("retryable") != 0,
                            rs.getString("summary"),
                            rs.getString("technical_detail"),
                            Instant.parse(rs.getString("created_at"))));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list processing errors", e);
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------------
    // Append-only audit log.
    // ---------------------------------------------------------------------

    @Override
    public synchronized long appendAuditEvent(AuditEventMetadata event) {
        ensureOpen();
        Objects.requireNonNull(event, "event");
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO audit_log(project_id, event_type, entity_id, summary, detail, created_at)
                VALUES(?,?,?,?,?,?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, event.projectId());
            ps.setString(2, event.eventType().name());
            ps.setString(3, event.entityId());
            ps.setString(4, event.summary());
            ps.setString(5, event.detail());
            ps.setString(6, event.createdAt().toString());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw failure("Cannot append audit event", e);
        }
    }

    @Override
    public synchronized List<AuditEventMetadata> listRecentAuditEvents(String projectId, int limit) {
        ensureOpen();
        if (projectId == null || projectId.isBlank() || limit <= 0) return List.of();
        List<AuditEventMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM audit_log WHERE project_id=? ORDER BY audit_id DESC LIMIT ?
                """)) {
            ps.setString(1, projectId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AuditEventMetadata(
                            rs.getLong("audit_id"),
                            projectId,
                            AuditEventType.parse(rs.getString("event_type")),
                            rs.getString("entity_id"),
                            rs.getString("summary"),
                            rs.getString("detail"),
                            Instant.parse(rs.getString("created_at"))));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list audit events", e);
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------------
    // Graph annotations.
    // ---------------------------------------------------------------------

    @Override
    public synchronized void upsertGraphAnnotation(GraphAnnotationMetadata annotation) {
        ensureOpen();
        Objects.requireNonNull(annotation, "annotation");
        executeUpdate("""
                INSERT INTO graph_annotations(
                  annotation_id, project_id, signal_key, annotation_type,
                  start_timestamp_nanos, end_timestamp_nanos, annotation_value,
                  label, note, created_at, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(annotation_id) DO UPDATE SET
                  signal_key=excluded.signal_key,
                  annotation_type=excluded.annotation_type,
                  start_timestamp_nanos=excluded.start_timestamp_nanos,
                  end_timestamp_nanos=excluded.end_timestamp_nanos,
                  annotation_value=excluded.annotation_value,
                  label=excluded.label,
                  note=excluded.note,
                  updated_at=excluded.updated_at
                """, ps -> {
            ps.setString(1, annotation.annotationId());
            ps.setString(2, annotation.projectId());
            ps.setString(3, annotation.signalKey());
            ps.setString(4, annotation.annotationType().name());
            ps.setLong(5, annotation.startTimestampNanos());
            setNullableLong(ps, 6, annotation.endTimestampNanos());
            if (annotation.value() == null) ps.setNull(7, java.sql.Types.REAL);
            else ps.setDouble(7, annotation.value());
            ps.setString(8, annotation.label());
            ps.setString(9, annotation.note());
            ps.setString(10, annotation.createdAt().toString());
            ps.setString(11, annotation.updatedAt().toString());
        });
        touchProject(annotation.projectId());
    }

    @Override
    public synchronized void deleteGraphAnnotation(String projectId, String annotationId) {
        ensureOpen();
        if (projectId == null || annotationId == null) return;
        executeUpdate("DELETE FROM graph_annotations WHERE project_id=? AND annotation_id=?", ps -> {
            ps.setString(1, projectId);
            ps.setString(2, annotationId);
        });
        touchProject(projectId);
    }

    @Override
    public synchronized List<GraphAnnotationMetadata> listGraphAnnotations(String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) return List.of();
        List<GraphAnnotationMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT * FROM graph_annotations WHERE project_id=?
                ORDER BY start_timestamp_nanos, annotation_id
                """)) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new GraphAnnotationMetadata(
                            rs.getString("annotation_id"),
                            projectId,
                            rs.getString("signal_key"),
                            GraphAnnotationType.parse(rs.getString("annotation_type"),
                                    GraphAnnotationType.POINT_MARKER),
                            rs.getLong("start_timestamp_nanos"),
                            nullableLong(rs, "end_timestamp_nanos"),
                            nullableDouble(rs, "annotation_value"),
                            rs.getString("label"),
                            rs.getString("note"),
                            Instant.parse(rs.getString("created_at")),
                            Instant.parse(rs.getString("updated_at"))));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list graph annotations", e);
        }
        return List.copyOf(out);
    }

    // ---------------------------------------------------------------------
    // Export job history.
    // ---------------------------------------------------------------------

    @Override
    public synchronized void upsertExportJob(ExportJobMetadata job) {
        ensureOpen();
        Objects.requireNonNull(job, "job");
        executeUpdate("""
                INSERT INTO export_jobs(
                  export_id, project_id, export_type, scope, status, destination,
                  bytes_written, message, created_at, finished_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(export_id) DO UPDATE SET
                  status=excluded.status,
                  destination=excluded.destination,
                  bytes_written=excluded.bytes_written,
                  message=excluded.message,
                  finished_at=excluded.finished_at
                """, ps -> {
            ps.setString(1, job.exportId());
            ps.setString(2, job.projectId());
            ps.setString(3, job.exportType());
            ps.setString(4, job.scope());
            ps.setString(5, job.status().name());
            ps.setString(6, job.destination());
            ps.setLong(7, job.bytesWritten());
            ps.setString(8, job.message());
            ps.setString(9, job.createdAt().toString());
            setNullableInstant(ps, 10, job.finishedAt());
        });
    }

    @Override
    public synchronized Optional<ExportJobMetadata> findExportJob(String exportId) {
        ensureOpen();
        if (exportId == null || exportId.isBlank()) return Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM export_jobs WHERE export_id=?")) {
            ps.setString(1, exportId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapExportJob(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("Cannot read export job", e);
        }
    }

    @Override
    public synchronized List<ExportJobMetadata> listExportJobs(String projectId, int limit) {
        ensureOpen();
        if (projectId == null || projectId.isBlank() || limit <= 0) return List.of();
        List<ExportJobMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM export_jobs WHERE project_id=? ORDER BY created_at DESC, export_id LIMIT ?")) {
            ps.setString(1, projectId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapExportJob(rs));
            }
        } catch (SQLException e) {
            throw failure("Cannot list export jobs", e);
        }
        return List.copyOf(out);
    }

    private static ExportJobMetadata mapExportJob(ResultSet rs) throws SQLException {
        return new ExportJobMetadata(
                rs.getString("export_id"),
                rs.getString("project_id"),
                rs.getString("export_type"),
                rs.getString("scope"),
                ExportJobStatus.parse(rs.getString("status"), ExportJobStatus.QUEUED),
                rs.getString("destination"),
                rs.getLong("bytes_written"),
                rs.getString("message"),
                Instant.parse(rs.getString("created_at")),
                nullableInstant(rs, "finished_at"));
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        String raw = rs.getString(column);
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw.trim());
        } catch (RuntimeException unparseable) {
            return null;
        }
    }

    private static void setNullableInstant(PreparedStatement ps, int index, Instant value) throws SQLException {
        if (value == null) ps.setNull(index, java.sql.Types.VARCHAR);
        else ps.setString(index, value.toString());
    }

    @Override
    public synchronized void runInTransaction(Runnable work) {
        if (work == null) {
            return;
        }
        inTransaction(work::run);
    }

    @Override
    public synchronized void upsertAnalysisRun(AnalysisRunMetadata run) {
        ensureOpen();
        Objects.requireNonNull(run, "run");
        executeUpdate("""
                INSERT INTO analysis_runs(
                  analysis_id, project_id, analysis_type, status, alignment_policy, analysis_version,
                  range_source, start_timestamp_nanos, end_timestamp_nanos, signal_keys_blob,
                  parameters_blob, invalidation_key, artifact_ref, message, created_at, completed_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(analysis_id) DO UPDATE SET
                  status=excluded.status,
                  artifact_ref=excluded.artifact_ref,
                  message=excluded.message,
                  completed_at=excluded.completed_at
                """, ps -> {
            ps.setString(1, run.analysisId());
            ps.setString(2, run.projectId());
            ps.setString(3, run.analysisType());
            ps.setString(4, run.status());
            ps.setString(5, run.alignmentPolicy());
            ps.setString(6, run.analysisVersion());
            ps.setString(7, run.rangeSource());
            if (run.startTimestampNanos() == null) ps.setObject(8, null); else ps.setLong(8, run.startTimestampNanos());
            if (run.endTimestampNanos() == null) ps.setObject(9, null); else ps.setLong(9, run.endTimestampNanos());
            ps.setString(10, run.signalKeysBlob());
            ps.setString(11, run.parametersBlob());
            ps.setString(12, run.invalidationKey());
            ps.setString(13, run.artifactRef());
            ps.setString(14, run.message());
            ps.setString(15, run.createdAt().toString());
            setNullableInstant(ps, 16, run.completedAt());
        });
    }

    @Override
    public synchronized Optional<AnalysisRunMetadata> findAnalysisRun(String analysisId) {
        ensureOpen();
        if (analysisId == null || analysisId.isBlank()) return Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM analysis_runs WHERE analysis_id=?")) {
            ps.setString(1, analysisId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapAnalysisRun(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("Cannot read analysis run", e);
        }
    }

    @Override
    public synchronized List<AnalysisRunMetadata> listAnalysisRuns(String projectId, int limit) {
        ensureOpen();
        if (projectId == null || projectId.isBlank() || limit <= 0) return List.of();
        List<AnalysisRunMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM analysis_runs WHERE project_id=? ORDER BY created_at DESC LIMIT ?")) {
            ps.setString(1, projectId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(mapAnalysisRun(rs));
            }
        } catch (SQLException e) {
            throw failure("Cannot list analysis runs", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void replaceAnalysisFindings(String analysisId, List<AnalysisFindingRow> findings) {
        ensureOpen();
        if (analysisId == null || analysisId.isBlank()) return;
        executeUpdate("DELETE FROM analysis_findings WHERE analysis_id=?", ps -> ps.setString(1, analysisId));
        if (findings == null) return;
        for (AnalysisFindingRow row : findings) {
            executeUpdate("""
                    INSERT INTO analysis_findings(
                      finding_id, analysis_id, project_id, finding_type, severity,
                      start_timestamp_nanos, end_timestamp_nanos, summary, details, metrics_blob, created_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """, ps -> {
                ps.setString(1, row.findingId());
                ps.setString(2, row.analysisId());
                ps.setString(3, row.projectId());
                ps.setString(4, row.findingType());
                ps.setString(5, row.severity());
                ps.setLong(6, row.startTimestampNanos());
                if (row.endTimestampNanos() == null) ps.setObject(7, null); else ps.setLong(7, row.endTimestampNanos());
                ps.setString(8, row.summary());
                ps.setString(9, row.details());
                ps.setString(10, row.metricsBlob());
                ps.setString(11, row.createdAt().toString());
            });
        }
    }

    @Override
    public synchronized List<AnalysisFindingRow> listAnalysisFindings(String analysisId) {
        ensureOpen();
        if (analysisId == null || analysisId.isBlank()) return List.of();
        List<AnalysisFindingRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM analysis_findings WHERE analysis_id=? ORDER BY start_timestamp_nanos")) {
            ps.setString(1, analysisId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AnalysisFindingRow(
                            rs.getString("finding_id"),
                            rs.getString("analysis_id"),
                            rs.getString("project_id"),
                            rs.getString("finding_type"),
                            rs.getString("severity"),
                            rs.getLong("start_timestamp_nanos"),
                            nullableLong(rs, "end_timestamp_nanos"),
                            rs.getString("summary"),
                            rs.getString("details"),
                            rs.getString("metrics_blob"),
                            Instant.parse(rs.getString("created_at"))));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list analysis findings", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void upsertAnalysisWorkspace(AnalysisWorkspaceMetadata workspace) {
        ensureOpen();
        Objects.requireNonNull(workspace, "workspace");
        executeUpdate("""
                INSERT INTO analysis_workspace(
                  project_id, last_analysis_type, last_analysis_id, result_visible, selected_finding_id, updated_at)
                VALUES(?,?,?,?,?,?)
                ON CONFLICT(project_id) DO UPDATE SET
                  last_analysis_type=excluded.last_analysis_type,
                  last_analysis_id=excluded.last_analysis_id,
                  result_visible=excluded.result_visible,
                  selected_finding_id=excluded.selected_finding_id,
                  updated_at=excluded.updated_at
                """, ps -> {
            ps.setString(1, workspace.projectId());
            ps.setString(2, workspace.lastAnalysisType());
            ps.setString(3, workspace.lastAnalysisId());
            ps.setInt(4, workspace.resultVisible() ? 1 : 0);
            ps.setString(5, workspace.selectedFindingId());
            ps.setString(6, workspace.updatedAt().toString());
        });
    }

    @Override
    public synchronized Optional<AnalysisWorkspaceMetadata> findAnalysisWorkspace(String projectId) {
        ensureOpen();
        if (projectId == null || projectId.isBlank()) return Optional.empty();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM analysis_workspace WHERE project_id=?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new AnalysisWorkspaceMetadata(
                        rs.getString("project_id"),
                        rs.getString("last_analysis_type"),
                        rs.getString("last_analysis_id"),
                        rs.getInt("result_visible") != 0,
                        rs.getString("selected_finding_id"),
                        Instant.parse(rs.getString("updated_at"))));
            }
        } catch (SQLException e) {
            throw failure("Cannot read analysis workspace", e);
        }
    }

    @Override
    public synchronized void upsertDerivedSignalDefinition(DerivedSignalDefinitionMetadata definition) {
        ensureOpen();
        Objects.requireNonNull(definition, "definition");
        executeUpdate("""
                INSERT INTO derived_signal_definitions(
                  derived_id, project_id, identity, expression, alignment_policy, analysis_version, created_at)
                VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(project_id, identity) DO UPDATE SET
                  expression=excluded.expression,
                  alignment_policy=excluded.alignment_policy,
                  analysis_version=excluded.analysis_version
                """, ps -> {
            ps.setString(1, definition.derivedId());
            ps.setString(2, definition.projectId());
            ps.setString(3, definition.identity());
            ps.setString(4, definition.expression());
            ps.setString(5, definition.alignmentPolicy());
            ps.setString(6, definition.analysisVersion());
            ps.setString(7, definition.createdAt().toString());
        });
    }

    @Override
    public synchronized Optional<DerivedSignalDefinitionMetadata> findDerivedSignalDefinition(
            String projectId, String identity
    ) {
        ensureOpen();
        if (projectId == null || projectId.isBlank() || identity == null || identity.isBlank()) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM derived_signal_definitions WHERE project_id=? AND identity=?")) {
            ps.setString(1, projectId);
            ps.setString(2, identity);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new DerivedSignalDefinitionMetadata(
                        rs.getString("derived_id"),
                        rs.getString("project_id"),
                        rs.getString("identity"),
                        rs.getString("expression"),
                        rs.getString("alignment_policy"),
                        rs.getString("analysis_version"),
                        Instant.parse(rs.getString("created_at"))));
            }
        } catch (SQLException e) {
            throw failure("Cannot read derived signal definition", e);
        }
    }

    @Override
    public synchronized void upsertDiagnosticRulePack(DiagnosticRulePackRow pack) {
        ensureOpen();
        Objects.requireNonNull(pack, "pack");
        executeUpdate("""
                INSERT INTO diagnostic_rule_packs(
                  pack_id, pack_version, project_id, name, description, origin,
                  engine_min_version, json_blob, created_at)
                VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT(project_id, pack_id, pack_version) DO UPDATE SET
                  name=excluded.name,
                  description=excluded.description,
                  json_blob=excluded.json_blob
                """, ps -> {
            ps.setString(1, pack.packId());
            ps.setString(2, pack.packVersion());
            ps.setString(3, pack.projectId());
            ps.setString(4, pack.name());
            ps.setString(5, pack.description());
            ps.setString(6, pack.origin());
            ps.setString(7, pack.engineMinVersion());
            ps.setString(8, pack.jsonBlob());
            ps.setString(9, pack.createdAt().toString());
        });
    }

    @Override
    public synchronized Optional<DiagnosticRulePackRow> findDiagnosticRulePack(
            String projectId, String packId, String packVersion
    ) {
        ensureOpen();
        if (blank(projectId) || blank(packId) || blank(packVersion)) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM diagnostic_rule_packs WHERE project_id=? AND pack_id=? AND pack_version=?")) {
            ps.setString(1, projectId);
            ps.setString(2, packId);
            ps.setString(3, packVersion);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapDiagnosticPack(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("Cannot read diagnostic rule pack", e);
        }
    }

    @Override
    public synchronized List<DiagnosticRulePackRow> listDiagnosticRulePacks(String projectId) {
        ensureOpen();
        if (blank(projectId)) {
            return List.of();
        }
        List<DiagnosticRulePackRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM diagnostic_rule_packs WHERE project_id=? ORDER BY name")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapDiagnosticPack(rs));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list diagnostic rule packs", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void upsertDiagnosticRun(DiagnosticRunMetadata run) {
        ensureOpen();
        Objects.requireNonNull(run, "run");
        executeUpdate("""
                INSERT INTO diagnostic_runs(
                  run_id, project_id, pack_id, pack_version, status, range_source,
                  start_timestamp_nanos, end_timestamp_nanos, rules_requested, rules_executed,
                  rules_skipped, invalidation_key, artifact_generation, engine_version,
                  message, created_at, completed_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(run_id) DO UPDATE SET
                  status=excluded.status,
                  message=excluded.message,
                  completed_at=excluded.completed_at
                """, ps -> {
            ps.setString(1, run.runId());
            ps.setString(2, run.projectId());
            ps.setString(3, run.packId());
            ps.setString(4, run.packVersion());
            ps.setString(5, run.status());
            ps.setString(6, run.rangeSource());
            if (run.startTimestampNanos() == null) ps.setObject(7, null); else ps.setLong(7, run.startTimestampNanos());
            if (run.endTimestampNanos() == null) ps.setObject(8, null); else ps.setLong(8, run.endTimestampNanos());
            ps.setInt(9, run.rulesRequested());
            ps.setInt(10, run.rulesExecuted());
            ps.setInt(11, run.rulesSkipped());
            ps.setString(12, run.invalidationKey());
            ps.setLong(13, run.artifactGeneration());
            ps.setString(14, run.engineVersion());
            ps.setString(15, run.message());
            ps.setString(16, run.createdAt().toString());
            setNullableInstant(ps, 17, run.completedAt());
        });
    }

    @Override
    public synchronized Optional<DiagnosticRunMetadata> findDiagnosticRun(String runId) {
        ensureOpen();
        if (blank(runId)) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM diagnostic_runs WHERE run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapDiagnosticRun(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("Cannot read diagnostic run", e);
        }
    }

    @Override
    public synchronized void replaceDiagnosticFindings(String runId, List<DiagnosticFindingRow> findings) {
        ensureOpen();
        if (blank(runId)) {
            return;
        }
        executeUpdate("DELETE FROM diagnostic_findings WHERE run_id=?", ps -> ps.setString(1, runId));
        if (findings == null) {
            return;
        }
        for (DiagnosticFindingRow row : findings) {
            executeUpdate("""
                    INSERT INTO diagnostic_findings(
                      finding_id, run_id, project_id, rule_id, rule_version, severity, category,
                      start_timestamp_nanos, end_timestamp_nanos, summary, details, evidence_blob, created_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, ps -> {
                ps.setString(1, row.findingId());
                ps.setString(2, row.runId());
                ps.setString(3, row.projectId());
                ps.setString(4, row.ruleId());
                ps.setString(5, row.ruleVersion());
                ps.setString(6, row.severity());
                ps.setString(7, row.category());
                ps.setLong(8, row.startTimestampNanos());
                if (row.endTimestampNanos() == null) ps.setObject(9, null); else ps.setLong(9, row.endTimestampNanos());
                ps.setString(10, row.summary());
                ps.setString(11, row.details());
                ps.setString(12, row.evidenceBlob());
                ps.setString(13, row.createdAt().toString());
            });
        }
    }

    @Override
    public synchronized List<DiagnosticFindingRow> listDiagnosticFindings(String runId) {
        ensureOpen();
        if (blank(runId)) {
            return List.of();
        }
        List<DiagnosticFindingRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM diagnostic_findings WHERE run_id=? ORDER BY start_timestamp_nanos")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DiagnosticFindingRow(
                            rs.getString("finding_id"),
                            rs.getString("run_id"),
                            rs.getString("project_id"),
                            rs.getString("rule_id"),
                            rs.getString("rule_version"),
                            rs.getString("severity"),
                            rs.getString("category"),
                            rs.getLong("start_timestamp_nanos"),
                            nullableLong(rs, "end_timestamp_nanos"),
                            rs.getString("summary"),
                            rs.getString("details"),
                            rs.getString("evidence_blob"),
                            Instant.parse(rs.getString("created_at"))));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list diagnostic findings", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void upsertDiagnosticBinding(DiagnosticBindingRow binding) {
        ensureOpen();
        Objects.requireNonNull(binding, "binding");
        executeUpdate("""
                INSERT INTO diagnostic_bindings(project_id, pack_id, role, signal_key_blob, updated_at)
                VALUES(?,?,?,?,?)
                ON CONFLICT(project_id, pack_id, role) DO UPDATE SET
                  signal_key_blob=excluded.signal_key_blob,
                  updated_at=excluded.updated_at
                """, ps -> {
            ps.setString(1, binding.projectId());
            ps.setString(2, binding.packId());
            ps.setString(3, binding.role());
            ps.setString(4, binding.signalKeyBlob());
            ps.setString(5, binding.updatedAt().toString());
        });
    }

    @Override
    public synchronized List<DiagnosticBindingRow> listDiagnosticBindings(String projectId, String packId) {
        ensureOpen();
        if (blank(projectId) || blank(packId)) {
            return List.of();
        }
        List<DiagnosticBindingRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM diagnostic_bindings WHERE project_id=? AND pack_id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, packId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DiagnosticBindingRow(
                            rs.getString("project_id"),
                            rs.getString("pack_id"),
                            rs.getString("role"),
                            rs.getString("signal_key_blob"),
                            Instant.parse(rs.getString("updated_at"))));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list diagnostic bindings", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void upsertDiagnosticWorkspace(DiagnosticWorkspaceMetadata workspace) {
        ensureOpen();
        Objects.requireNonNull(workspace, "workspace");
        executeUpdate("""
                INSERT INTO diagnostic_workspace(
                  project_id, last_pack_id, last_run_id, enabled_rules_blob,
                  findings_visible, selected_finding_id, updated_at)
                VALUES(?,?,?,?,?,?,?)
                ON CONFLICT(project_id) DO UPDATE SET
                  last_pack_id=excluded.last_pack_id,
                  last_run_id=excluded.last_run_id,
                  enabled_rules_blob=excluded.enabled_rules_blob,
                  findings_visible=excluded.findings_visible,
                  selected_finding_id=excluded.selected_finding_id,
                  updated_at=excluded.updated_at
                """, ps -> {
            ps.setString(1, workspace.projectId());
            ps.setString(2, workspace.lastPackId());
            ps.setString(3, workspace.lastRunId());
            ps.setString(4, workspace.enabledRulesBlob());
            ps.setInt(5, workspace.findingsVisible() ? 1 : 0);
            ps.setString(6, workspace.selectedFindingId());
            ps.setString(7, workspace.updatedAt().toString());
        });
    }

    @Override
    public synchronized Optional<DiagnosticWorkspaceMetadata> findDiagnosticWorkspace(String projectId) {
        ensureOpen();
        if (blank(projectId)) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM diagnostic_workspace WHERE project_id=?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new DiagnosticWorkspaceMetadata(
                        rs.getString("project_id"),
                        rs.getString("last_pack_id"),
                        rs.getString("last_run_id"),
                        rs.getString("enabled_rules_blob"),
                        rs.getInt("findings_visible") != 0,
                        rs.getString("selected_finding_id"),
                        Instant.parse(rs.getString("updated_at"))));
            }
        } catch (SQLException e) {
            throw failure("Cannot read diagnostic workspace", e);
        }
    }

    @Override
    public synchronized int invalidateDiagnosticRuns(String projectId, long artifactGeneration) {
        ensureOpen();
        if (blank(projectId)) {
            return 0;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE diagnostic_runs SET status='INVALIDATED' "
                        + "WHERE project_id=? AND artifact_generation<>? AND status='COMPLETED'")) {
            ps.setString(1, projectId);
            ps.setLong(2, artifactGeneration);
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("Cannot invalidate diagnostic runs", e);
        }
    }

    @Override
    public synchronized List<DiagnosticRunMetadata> listDiagnosticRuns(String projectId) {
        ensureOpen();
        if (blank(projectId)) {
            return List.of();
        }
        List<DiagnosticRunMetadata> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM diagnostic_runs WHERE project_id=? ORDER BY created_at DESC")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapDiagnosticRun(rs));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list diagnostic runs", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void deleteDiagnosticRulePack(String projectId, String packId, String packVersion) {
        ensureOpen();
        if (blank(projectId) || blank(packId) || blank(packVersion)) {
            return;
        }
        executeUpdate("DELETE FROM diagnostic_rule_packs WHERE project_id=? AND pack_id=? AND pack_version=?",
                ps -> {
                    ps.setString(1, projectId);
                    ps.setString(2, packId);
                    ps.setString(3, packVersion);
                });
    }

    @Override
    public synchronized void upsertDiagnosticFindingReview(DiagnosticFindingReviewRow review) {
        ensureOpen();
        Objects.requireNonNull(review, "review");
        executeUpdate("""
                INSERT INTO diagnostic_finding_reviews(
                  finding_id, project_id, review_state, engineer_note, updated_at)
                VALUES(?,?,?,?,?)
                ON CONFLICT(finding_id) DO UPDATE SET
                  review_state=excluded.review_state,
                  engineer_note=excluded.engineer_note,
                  updated_at=excluded.updated_at
                """, ps -> {
            ps.setString(1, review.findingId());
            ps.setString(2, review.projectId());
            ps.setString(3, review.reviewState());
            ps.setString(4, review.engineerNote());
            ps.setString(5, review.updatedAt().toString());
        });
    }

    @Override
    public synchronized Optional<DiagnosticFindingReviewRow> findDiagnosticFindingReview(String findingId) {
        ensureOpen();
        if (blank(findingId)) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM diagnostic_finding_reviews WHERE finding_id=?")) {
            ps.setString(1, findingId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapReview(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("Cannot read diagnostic finding review", e);
        }
    }

    @Override
    public synchronized List<DiagnosticFindingReviewRow> listDiagnosticFindingReviews(String projectId) {
        ensureOpen();
        if (blank(projectId)) {
            return List.of();
        }
        List<DiagnosticFindingReviewRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM diagnostic_finding_reviews WHERE project_id=?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapReview(rs));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list diagnostic finding reviews", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void upsertDiagnosticRulePreference(DiagnosticRulePreferenceRow preference) {
        ensureOpen();
        Objects.requireNonNull(preference, "preference");
        executeUpdate("""
                INSERT INTO diagnostic_rule_preferences(
                  project_id, pack_id, rule_id, enabled, updated_at)
                VALUES(?,?,?,?,?)
                ON CONFLICT(project_id, pack_id, rule_id) DO UPDATE SET
                  enabled=excluded.enabled,
                  updated_at=excluded.updated_at
                """, ps -> {
            ps.setString(1, preference.projectId());
            ps.setString(2, preference.packId());
            ps.setString(3, preference.ruleId());
            ps.setInt(4, preference.enabled() ? 1 : 0);
            ps.setString(5, preference.updatedAt().toString());
        });
    }

    @Override
    public synchronized List<DiagnosticRulePreferenceRow> listDiagnosticRulePreferences(
            String projectId, String packId
    ) {
        ensureOpen();
        if (blank(projectId) || blank(packId)) {
            return List.of();
        }
        List<DiagnosticRulePreferenceRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM diagnostic_rule_preferences WHERE project_id=? AND pack_id=?")) {
            ps.setString(1, projectId);
            ps.setString(2, packId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DiagnosticRulePreferenceRow(
                            rs.getString("project_id"),
                            rs.getString("pack_id"),
                            rs.getString("rule_id"),
                            rs.getInt("enabled") != 0,
                            Instant.parse(rs.getString("updated_at"))));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list diagnostic rule preferences", e);
        }
        return List.copyOf(out);
    }

    private static DiagnosticFindingReviewRow mapReview(ResultSet rs) throws SQLException {
        return new DiagnosticFindingReviewRow(
                rs.getString("finding_id"),
                rs.getString("project_id"),
                rs.getString("review_state"),
                rs.getString("engineer_note"),
                Instant.parse(rs.getString("updated_at")));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static DiagnosticRulePackRow mapDiagnosticPack(ResultSet rs) throws SQLException {
        return new DiagnosticRulePackRow(
                rs.getString("project_id"),
                rs.getString("pack_id"),
                rs.getString("pack_version"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("origin"),
                rs.getString("engine_min_version"),
                rs.getString("json_blob"),
                Instant.parse(rs.getString("created_at")));
    }

    private static DiagnosticRunMetadata mapDiagnosticRun(ResultSet rs) throws SQLException {
        return new DiagnosticRunMetadata(
                rs.getString("run_id"),
                rs.getString("project_id"),
                rs.getString("pack_id"),
                rs.getString("pack_version"),
                rs.getString("status"),
                rs.getString("range_source"),
                nullableLong(rs, "start_timestamp_nanos"),
                nullableLong(rs, "end_timestamp_nanos"),
                rs.getInt("rules_requested"),
                rs.getInt("rules_executed"),
                rs.getInt("rules_skipped"),
                rs.getString("invalidation_key"),
                rs.getLong("artifact_generation"),
                rs.getString("engine_version"),
                rs.getString("message"),
                Instant.parse(rs.getString("created_at")),
                nullableInstant(rs, "completed_at"));
    }

    private static AnalysisRunMetadata mapAnalysisRun(ResultSet rs) throws SQLException {
        return new AnalysisRunMetadata(
                rs.getString("analysis_id"),
                rs.getString("project_id"),
                rs.getString("analysis_type"),
                rs.getString("status"),
                rs.getString("alignment_policy"),
                rs.getString("analysis_version"),
                rs.getString("range_source"),
                nullableLong(rs, "start_timestamp_nanos"),
                nullableLong(rs, "end_timestamp_nanos"),
                rs.getString("signal_keys_blob"),
                rs.getString("parameters_blob"),
                rs.getString("invalidation_key"),
                rs.getString("artifact_ref"),
                rs.getString("message"),
                Instant.parse(rs.getString("created_at")),
                nullableInstant(rs, "completed_at"));
    }

    @Override
    public synchronized void upsertEngineeringInvestigation(EngineeringInvestigationRow row) {
        ensureOpen();
        Objects.requireNonNull(row, "row");
        executeUpdate("""
                INSERT INTO engineering_investigations(
                  investigation_id, project_id, name, description, status,
                  range_start_nanos, range_end_nanos, graph_traces_blob, layout_mode,
                  viewport_start_nanos, viewport_end_nanos, cursor_a_nanos, cursor_b_nanos,
                  focus_key_blob, conclusion, report_config, created_at, updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(investigation_id) DO UPDATE SET
                  name=excluded.name,
                  description=excluded.description,
                  status=excluded.status,
                  range_start_nanos=excluded.range_start_nanos,
                  range_end_nanos=excluded.range_end_nanos,
                  graph_traces_blob=excluded.graph_traces_blob,
                  layout_mode=excluded.layout_mode,
                  viewport_start_nanos=excluded.viewport_start_nanos,
                  viewport_end_nanos=excluded.viewport_end_nanos,
                  cursor_a_nanos=excluded.cursor_a_nanos,
                  cursor_b_nanos=excluded.cursor_b_nanos,
                  focus_key_blob=excluded.focus_key_blob,
                  conclusion=excluded.conclusion,
                  report_config=excluded.report_config,
                  updated_at=excluded.updated_at
                """, ps -> {
            ps.setString(1, row.investigationId());
            ps.setString(2, row.projectId());
            ps.setString(3, row.name());
            ps.setString(4, row.description());
            ps.setString(5, row.status());
            setNullableLong(ps, 6, row.rangeStartNanos());
            setNullableLong(ps, 7, row.rangeEndNanos());
            ps.setString(8, row.graphTracesBlob());
            ps.setString(9, row.layoutMode());
            setNullableLong(ps, 10, row.viewportStartNanos());
            setNullableLong(ps, 11, row.viewportEndNanos());
            setNullableLong(ps, 12, row.cursorANanos());
            setNullableLong(ps, 13, row.cursorBNanos());
            ps.setString(14, row.focusKeyBlob());
            ps.setString(15, row.conclusion());
            ps.setString(16, row.reportConfig());
            ps.setString(17, row.createdAt().toString());
            ps.setString(18, row.updatedAt().toString());
        });
    }

    @Override
    public synchronized void replaceInvestigationSignals(String investigationId, List<String> signalKeyBlobs) {
        ensureOpen();
        if (blank(investigationId)) {
            return;
        }
        executeUpdate("DELETE FROM investigation_signals WHERE investigation_id=?",
                ps -> ps.setString(1, investigationId));
        if (signalKeyBlobs == null) {
            return;
        }
        int order = 0;
        for (String blob : signalKeyBlobs) {
            final int sort = order++;
            executeUpdate("""
                    INSERT INTO investigation_signals(investigation_id, sort_order, signal_key_blob)
                    VALUES(?,?,?)
                    """, ps -> {
                ps.setString(1, investigationId);
                ps.setInt(2, sort);
                ps.setString(3, blob == null ? "" : blob);
            });
        }
    }

    @Override
    public synchronized List<String> listInvestigationSignals(String investigationId) {
        ensureOpen();
        if (blank(investigationId)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT signal_key_blob FROM investigation_signals WHERE investigation_id=? ORDER BY sort_order")) {
            ps.setString(1, investigationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(nullToEmpty(rs.getString(1)));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list investigation signals", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void replaceInvestigationEvidence(
            String investigationId, List<InvestigationEvidenceRow> evidence
    ) {
        ensureOpen();
        if (blank(investigationId)) {
            return;
        }
        executeUpdate("DELETE FROM investigation_evidence WHERE investigation_id=?",
                ps -> ps.setString(1, investigationId));
        if (evidence == null) {
            return;
        }
        for (InvestigationEvidenceRow row : evidence) {
            executeUpdate("""
                    INSERT INTO investigation_evidence(
                      evidence_id, investigation_id, kind, reference_id, sort_order, stale, stale_reason,
                      source_fingerprint, artifact_generation, caption, summary, created_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """, ps -> {
                ps.setString(1, row.evidenceId());
                ps.setString(2, investigationId);
                ps.setString(3, row.kind());
                ps.setString(4, row.referenceId());
                ps.setInt(5, row.sortOrder());
                ps.setInt(6, row.stale() ? 1 : 0);
                ps.setString(7, row.staleReason());
                ps.setString(8, row.sourceFingerprint());
                ps.setLong(9, row.artifactGeneration());
                ps.setString(10, row.caption());
                ps.setString(11, row.summary());
                ps.setString(12, row.createdAt().toString());
            });
        }
    }

    @Override
    public synchronized List<InvestigationEvidenceRow> listInvestigationEvidence(String investigationId) {
        ensureOpen();
        if (blank(investigationId)) {
            return List.of();
        }
        List<InvestigationEvidenceRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM investigation_evidence WHERE investigation_id=? ORDER BY sort_order, created_at")) {
            ps.setString(1, investigationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new InvestigationEvidenceRow(
                            rs.getString("evidence_id"),
                            rs.getString("investigation_id"),
                            rs.getString("kind"),
                            rs.getString("reference_id"),
                            rs.getInt("sort_order"),
                            rs.getInt("stale") != 0,
                            rs.getString("stale_reason"),
                            rs.getString("source_fingerprint"),
                            rs.getLong("artifact_generation"),
                            rs.getString("caption"),
                            rs.getString("summary"),
                            Instant.parse(rs.getString("created_at"))));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list investigation evidence", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void replaceInvestigationObservations(
            String investigationId, List<InvestigationObservationRow> observations
    ) {
        ensureOpen();
        if (blank(investigationId)) {
            return;
        }
        executeUpdate("DELETE FROM investigation_observations WHERE investigation_id=?",
                ps -> ps.setString(1, investigationId));
        if (observations == null) {
            return;
        }
        for (InvestigationObservationRow row : observations) {
            executeUpdate("""
                    INSERT INTO investigation_observations(
                      observation_id, investigation_id, start_nanos, end_nanos, title, note, created_at, updated_at)
                    VALUES(?,?,?,?,?,?,?,?)
                    """, ps -> {
                ps.setString(1, row.observationId());
                ps.setString(2, investigationId);
                setNullableLong(ps, 3, row.startNanos());
                setNullableLong(ps, 4, row.endNanos());
                ps.setString(5, row.title());
                ps.setString(6, row.note());
                ps.setString(7, row.createdAt().toString());
                ps.setString(8, row.updatedAt().toString());
            });
        }
    }

    @Override
    public synchronized List<InvestigationObservationRow> listInvestigationObservations(String investigationId) {
        ensureOpen();
        if (blank(investigationId)) {
            return List.of();
        }
        List<InvestigationObservationRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM investigation_observations WHERE investigation_id=? ORDER BY created_at")) {
            ps.setString(1, investigationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new InvestigationObservationRow(
                            rs.getString("observation_id"),
                            rs.getString("investigation_id"),
                            nullableLong(rs, "start_nanos"),
                            nullableLong(rs, "end_nanos"),
                            rs.getString("title"),
                            rs.getString("note"),
                            Instant.parse(rs.getString("created_at")),
                            Instant.parse(rs.getString("updated_at"))));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list investigation observations", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized Optional<EngineeringInvestigationRow> findEngineeringInvestigation(String investigationId) {
        ensureOpen();
        if (blank(investigationId)) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM engineering_investigations WHERE investigation_id=?")) {
            ps.setString(1, investigationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapInvestigation(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("Cannot read investigation", e);
        }
    }

    @Override
    public synchronized List<EngineeringInvestigationRow> listEngineeringInvestigations(String projectId) {
        ensureOpen();
        if (blank(projectId)) {
            return List.of();
        }
        List<EngineeringInvestigationRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM engineering_investigations WHERE project_id=? ORDER BY updated_at DESC, name")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapInvestigation(rs));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list investigations", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized void deleteEngineeringInvestigation(String investigationId) {
        ensureOpen();
        if (blank(investigationId)) {
            return;
        }
        executeUpdate("DELETE FROM engineering_investigations WHERE investigation_id=?",
                ps -> ps.setString(1, investigationId));
    }

    @Override
    public synchronized void upsertInvestigationReport(InvestigationReportRow row) {
        ensureOpen();
        Objects.requireNonNull(row, "row");
        executeUpdate("""
                INSERT INTO investigation_reports(
                  report_id, investigation_id, project_id, name, format, status, destination, message,
                  created_at, finished_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(report_id) DO UPDATE SET
                  name=excluded.name,
                  status=excluded.status,
                  destination=excluded.destination,
                  message=excluded.message,
                  finished_at=COALESCE(investigation_reports.finished_at, excluded.finished_at)
                """, ps -> {
            ps.setString(1, row.reportId());
            ps.setString(2, row.investigationId());
            ps.setString(3, row.projectId());
            ps.setString(4, row.name());
            ps.setString(5, row.format());
            ps.setString(6, row.status());
            ps.setString(7, row.destination());
            ps.setString(8, row.message());
            ps.setString(9, row.createdAt().toString());
            setNullableInstant(ps, 10, row.finishedAt());
        });
    }

    @Override
    public synchronized List<InvestigationReportRow> listInvestigationReports(String investigationId) {
        ensureOpen();
        if (blank(investigationId)) {
            return List.of();
        }
        List<InvestigationReportRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM investigation_reports WHERE investigation_id=? ORDER BY created_at DESC")) {
            ps.setString(1, investigationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapInvestigationReport(rs));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list investigation reports", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized List<InvestigationReportRow> listProjectReports(String projectId) {
        ensureOpen();
        if (blank(projectId)) {
            return List.of();
        }
        List<InvestigationReportRow> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM investigation_reports WHERE project_id=? ORDER BY created_at DESC")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapInvestigationReport(rs));
                }
            }
        } catch (SQLException e) {
            throw failure("Cannot list project reports", e);
        }
        return List.copyOf(out);
    }

    @Override
    public synchronized Optional<InvestigationReportRow> findInvestigationReport(String reportId) {
        ensureOpen();
        if (blank(reportId)) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM investigation_reports WHERE report_id=?")) {
            ps.setString(1, reportId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapInvestigationReport(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw failure("Cannot read investigation report", e);
        }
    }

    @Override
    public synchronized void deleteInvestigationReport(String reportId) {
        ensureOpen();
        if (blank(reportId)) {
            return;
        }
        executeUpdate("DELETE FROM investigation_reports WHERE report_id=?",
                ps -> ps.setString(1, reportId));
    }

    @Override
    public synchronized void upsertInvestigationWorkspace(InvestigationWorkspaceMetadata workspace) {
        ensureOpen();
        Objects.requireNonNull(workspace, "workspace");
        executeUpdate("""
                INSERT INTO investigation_workspace(project_id, last_investigation_id, updated_at)
                VALUES(?,?,?)
                ON CONFLICT(project_id) DO UPDATE SET
                  last_investigation_id=excluded.last_investigation_id,
                  updated_at=excluded.updated_at
                """, ps -> {
            ps.setString(1, workspace.projectId());
            ps.setString(2, workspace.lastInvestigationId());
            ps.setString(3, workspace.updatedAt().toString());
        });
    }

    @Override
    public synchronized Optional<InvestigationWorkspaceMetadata> findInvestigationWorkspace(String projectId) {
        ensureOpen();
        if (blank(projectId)) {
            return Optional.empty();
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT * FROM investigation_workspace WHERE project_id=?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new InvestigationWorkspaceMetadata(
                        rs.getString("project_id"),
                        rs.getString("last_investigation_id"),
                        Instant.parse(rs.getString("updated_at"))));
            }
        } catch (SQLException e) {
            throw failure("Cannot read investigation workspace", e);
        }
    }

    @Override
    public synchronized int countEngineeringInvestigations(String projectId) {
        ensureOpen();
        if (blank(projectId)) {
            return 0;
        }
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM engineering_investigations WHERE project_id=?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw failure("Cannot count investigations", e);
        }
    }

    private static EngineeringInvestigationRow mapInvestigation(ResultSet rs) throws SQLException {
        return new EngineeringInvestigationRow(
                rs.getString("investigation_id"),
                rs.getString("project_id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("status"),
                nullableLong(rs, "range_start_nanos"),
                nullableLong(rs, "range_end_nanos"),
                rs.getString("graph_traces_blob"),
                rs.getString("layout_mode"),
                nullableLong(rs, "viewport_start_nanos"),
                nullableLong(rs, "viewport_end_nanos"),
                nullableLong(rs, "cursor_a_nanos"),
                nullableLong(rs, "cursor_b_nanos"),
                rs.getString("focus_key_blob"),
                rs.getString("conclusion"),
                rs.getString("report_config"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private static InvestigationReportRow mapInvestigationReport(ResultSet rs) throws SQLException {
        return new InvestigationReportRow(
                rs.getString("report_id"),
                rs.getString("investigation_id"),
                rs.getString("project_id"),
                rs.getString("name"),
                rs.getString("format"),
                rs.getString("status"),
                rs.getString("destination"),
                rs.getString("message"),
                Instant.parse(rs.getString("created_at")),
                nullableInstant(rs, "finished_at"));
    }

    public int schemaVersion() {
        ensureOpen();
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT version FROM schema_info LIMIT 1")) {
            if (!rs.next()) {
                return 0;
            }
            return rs.getInt(1);
        } catch (SQLException e) {
            throw failure("Cannot read schema version", e);
        }
    }

    private static ProjectMetadata projectFrom(ResultSet rs) throws SQLException {
        String lastWorkspace;
        try {
            lastWorkspace = nullToEmpty(rs.getString("last_workspace"));
        } catch (SQLException missingColumn) {
            lastWorkspace = "";
        }
        Instant lastSavedAt = null;
        try {
            String raw = rs.getString("last_saved_at");
            if (raw != null && !raw.isBlank()) {
                lastSavedAt = Instant.parse(raw.trim());
            }
        } catch (SQLException | RuntimeException ignored) {
            lastSavedAt = null;
        }
        return new ProjectMetadata(
                rs.getString("project_id"),
                rs.getString("name"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")),
                lastWorkspace,
                lastSavedAt);
    }

    private static String optionalString(ResultSet rs, String column) {
        try {
            return nullToEmpty(rs.getString(column));
        } catch (SQLException missing) {
            return "";
        }
    }

    private static int optionalInt(ResultSet rs, String column, int defaultValue) {
        try {
            int value = rs.getInt(column);
            return rs.wasNull() ? defaultValue : value;
        } catch (SQLException missing) {
            return defaultValue;
        }
    }

    private void inTransaction(SqlRunnable work) {
        try {
            boolean oldAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                work.run();
                connection.commit();
            } catch (RuntimeException | SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(oldAutoCommit);
            }
        } catch (SQLException e) {
            throw failure("SQLite transaction failed", e);
        }
    }

    private void executeUpdate(String sql, StatementBinder binder) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw failure("SQLite metadata write failed", e);
        }
    }

    private void touchProject(String projectId) {
        if (projectId == null || projectId.isBlank()) return;
        executeUpdate("UPDATE projects SET updated_at=? WHERE project_id=?", ps -> {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, projectId);
        });
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) ps.setNull(index, java.sql.Types.BIGINT);
        else ps.setLong(index, value);
    }

    private static void setNullable(PreparedStatement ps, int index, String value) throws SQLException {
        if (value == null || value.isBlank()) ps.setNull(index, java.sql.Types.VARCHAR);
        else ps.setString(index, value);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("metadata store is closed");
    }

    private IllegalStateException failure(String message, Exception cause) {
        return new IllegalStateException(message + " [" + databasePath + "]", cause);
    }

    @FunctionalInterface private interface StatementBinder { void bind(PreparedStatement statement) throws SQLException; }
    @FunctionalInterface private interface SqlRunnable { void run() throws SQLException; }
}
