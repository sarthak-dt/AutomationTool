package com.stellar.platform.api.support;

import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.processing.execution.ProcessingResultRegistry;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import com.stellar.platform.storage.ArtifactStorageRefs;
import com.stellar.platform.storage.metadata.FileFingerprint;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import com.stellar.platform.storage.metadata.ProcessingPlanFingerprint;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Durable per-job recovery for LOCAL/server processing runs.
 * Boundaries: project → source → channel/job → processing run.
 * Incomplete {@code .staging} trees are never treated as reusable.
 */
public final class DurableJobArtifactService {

    private final Path dataRoot;
    private final MetadataStoreFactory stores;

    public DurableJobArtifactService(MetadataStoreFactory stores) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.dataRoot = stores.dataRoot();
    }

    public Path jobFinalRoot(String projectId, String sourceId, int channelId, String definitionId) {
        String logical = ArtifactStorageRefs.forJob(projectId, sourceId, channelId, definitionId);
        Path root = dataRoot.resolve(logical.replace('/', java.io.File.separatorChar))
                .toAbsolutePath()
                .normalize();
        ensureUnderDataRoot(root);
        return root;
    }

    public GraphSignalSessionStore openWriteSession(
            String projectId,
            ProcessingJob job,
            String measurementDisplayName,
            String definitionDisplayName
    ) {
        Objects.requireNonNull(job, "job");
        Path finalRoot = jobFinalRoot(
                projectId,
                job.projectSourceId(),
                job.channelId(),
                job.definitionSourceId().orElse("")
        );
        Path stagingRoot = finalRoot.resolveSibling(finalRoot.getFileName().toString() + ".staging");
        deleteTreeBestEffort(stagingRoot);
        try {
            Files.createDirectories(stagingRoot.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create staging parent for " + stagingRoot, e);
        }
        return GraphSignalSessionStore.createPersistent(
                stagingRoot, measurementDisplayName, definitionDisplayName);
    }

    /**
     * Seeds the in-memory registry from VALID durable jobs so REUSE_COMPLETED survives process restart.
     *
     * @return number of jobs seeded
     */
    public int seedRegistry(
            String projectId,
            ProjectProcessingPlan plan,
            ProcessingResultRegistry registry,
            Function<String, Path> measurementResolver,
            Function<String, Path> definitionResolver
    ) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(registry, "registry");
        ProjectMetadataStore metadata = stores.store();
        int seeded = 0;
        for (ProcessingJob job : plan.jobs()) {
            if (registry.get(job.key()).isPresent()) {
                continue;
            }
            String definitionId = job.definitionSourceId().orElse("");
            Optional<ProcessingArtifactMetadata> found =
                    metadata.findValidArtifact(projectId, job.projectSourceId(), job.channelId(), definitionId);
            if (found.isEmpty()) {
                continue;
            }
            ProcessingArtifactMetadata artifact = found.get();
            if (!ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE.equals(artifact.artifactType())) {
                continue;
            }
            Path root = resolveArtifactRoot(artifact);
            Path measurement = measurementResolver == null
                    ? null
                    : measurementResolver.apply(job.projectSourceId());
            Path definition = definitionId.isBlank() || definitionResolver == null
                    ? null
                    : definitionResolver.apply(definitionId);
            String expectedPlanFp = ProcessingPlanFingerprint.forJob(
                    job.projectSourceId(),
                    job.channelId(),
                    definitionId,
                    job.mappingLifecycle().name(),
                    job.sourceFormat().name());
            if (!isReusable(artifact, root, measurement, definition, expectedPlanFp)) {
                markStale(artifact);
                continue;
            }
            try {
                GraphSignalSessionStore store = GraphSignalSessionStore.openExisting(root);
                MappingLifecycle lifecycle;
                try {
                    lifecycle = MappingLifecycle.valueOf(artifact.mappingLifecycle());
                } catch (RuntimeException ex) {
                    lifecycle = MappingLifecycle.MANUAL;
                }
                JobExecutionStatus status = ArtifactDiagnosticsCodec.parseStatus(artifact.jobExecutionStatus());
                IndependentJobResult result = new IndependentJobResult(
                        job.key(),
                        definitionId.isBlank() ? null : definitionId,
                        definition == null ? "" : definition.getFileName().toString(),
                        lifecycle,
                        status,
                        artifact.sampleCount(),
                        artifact.sampleCount(),
                        artifact.sampleCount(),
                        artifact.sampleCount(),
                        0L,
                        artifact.signalCount(),
                        artifact.startTimestampNanos(),
                        artifact.endTimestampNanos(),
                        null,
                        Duration.ZERO,
                        ArtifactDiagnosticsCodec.decode(artifact.diagnosticsSummary()),
                        artifact.technicalDetail().isBlank()
                                ? "reopened-from-durable-job"
                                : artifact.technicalDetail(),
                        store
                );
                registry.publishJob(result);
                seeded++;
                ProcessingLog.info(
                        "job.reopen",
                        projectId,
                        artifact.processingRunId(),
                        artifact.artifactId(),
                        artifact.sourceId(),
                        null,
                        status.name(),
                        null,
                        null,
                        "seeded durable job channel=" + artifact.channelId()
                );
            } catch (RuntimeException ex) {
                markStale(artifact);
                ProcessingLog.warn(
                        "job.reopen.failed",
                        projectId,
                        artifact.processingRunId(),
                        artifact.artifactId(),
                        artifact.sourceId(),
                        null,
                        "STALE",
                        null,
                        "ARTIFACT_CORRUPT",
                        ex.getMessage()
                );
            }
        }
        return seeded;
    }

    public IndependentJobResult commitSuccessfulJob(
            String projectId,
            String runId,
            IndependentJobResult result,
            Path measurement,
            Path definition,
            String sourceFormat
    ) {
        if (result == null || !result.successful()) {
            return result;
        }
        Object handle = result.catalogHandle().orElse(null);
        if (!(handle instanceof GraphSignalSessionStore store)) {
            return result;
        }
        String definitionId = result.definitionSourceId().orElse("");
        Path finalRoot = jobFinalRoot(projectId, result.projectSourceId(), result.channelId(), definitionId);
        Path storeRoot = store.root();
        String format = sourceFormat == null || sourceFormat.isBlank()
                ? guessFormat(measurement)
                : sourceFormat;
        if (!isStagingArtifactRoot(storeRoot, finalRoot)) {
            try {
                store.finishWriteSession();
            } catch (RuntimeException ignored) {
                // already durable
            }
            upsertMetadata(projectId, runId, result, store, finalRoot, measurement, definition, definitionId, format);
            return result;
        }
        try {
            store.finishWriteSession();
            commitArtifactDirectory(storeRoot, finalRoot);
        } catch (RuntimeException ex) {
            ProcessingLog.warn(
                    "artifact.commit.failed",
                    projectId,
                    runId,
                    null,
                    result.projectSourceId(),
                    null,
                    "FAILED",
                    null,
                    "ARTIFACT_COMMIT",
                    ex.getMessage()
            );
            PlatformMetrics.get().increment("job.outcome.failed");
            return result;
        }
        GraphSignalSessionStore committed;
        try {
            committed = GraphSignalSessionStore.openExisting(finalRoot);
        } catch (RuntimeException ex) {
            ProcessingLog.warn(
                    "artifact.reopen.failed",
                    projectId,
                    runId,
                    null,
                    result.projectSourceId(),
                    null,
                    "FAILED",
                    null,
                    "ARTIFACT_REOPEN",
                    ex.getMessage()
            );
            return result;
        }
        IndependentJobResult rebuilt = rebuildWithHandle(result, committed);
        upsertMetadata(projectId, runId, rebuilt, committed, finalRoot, measurement, definition, definitionId, format);
        long bytes = directorySizeBestEffort(finalRoot);
        PlatformMetrics.get().increment("artifact.bytes", bytes);
        PlatformMetrics.get().increment("job.outcome.success");
        PlatformMetrics.get().increment("decoded.samples", rebuilt.signalsProduced());
        PlatformMetrics.get().increment("frames", rebuilt.sourceFramesRead());
        long elapsedMs = rebuilt.elapsed().toMillis();
        if (elapsedMs > 0L && rebuilt.sourceFramesRead() > 0L) {
            PlatformMetrics.get().setGauge(
                    "frames.per.sec.last",
                    (rebuilt.sourceFramesRead() * 1000L) / elapsedMs
            );
        }
        return rebuilt;
    }

    public void invalidatePlanJobs(String projectId, ProjectProcessingPlan plan) {
        if (plan == null) {
            return;
        }
        ProjectMetadataStore metadata = stores.store();
        for (ProcessingJob job : plan.jobs()) {
            String definitionId = job.definitionSourceId().orElse("");
            metadata.findValidArtifact(projectId, job.projectSourceId(), job.channelId(), definitionId)
                    .ifPresent(this::markStale);
            Path finalRoot = jobFinalRoot(projectId, job.projectSourceId(), job.channelId(), definitionId);
            deleteTreeBestEffort(finalRoot);
            deleteTreeBestEffort(finalRoot.resolveSibling(finalRoot.getFileName().toString() + ".staging"));
        }
    }

    public boolean isStagingOnly(Path root) {
        return root != null && root.getFileName() != null
                && root.getFileName().toString().endsWith(".staging");
    }

    private void upsertMetadata(
            String projectId,
            String runId,
            IndependentJobResult result,
            GraphSignalSessionStore store,
            Path finalRoot,
            Path measurement,
            Path definition,
            String definitionId,
            String sourceFormat
    ) {
        String artifactId = projectId + "|" + result.projectSourceId()
                + "|" + result.channelId() + "|" + definitionId;
        String storageRef = ArtifactStorageRefs.forJob(
                projectId, result.projectSourceId(), result.channelId(), definitionId);
        String planFingerprint = ProcessingPlanFingerprint.forJob(
                result.projectSourceId(),
                result.channelId(),
                definitionId,
                result.mappingLifecycle().name(),
                sourceFormat
        );
        long signalCount = store.snapshot().entries().size();
        long sampleCount = store.losslessStore().totalSampleCount();
        ProcessingArtifactMetadata artifact = new ProcessingArtifactMetadata(
                artifactId,
                projectId,
                result.projectSourceId(),
                result.channelId(),
                definitionId,
                runId == null ? "" : runId,
                ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                finalRoot.toAbsolutePath().normalize().toString(),
                storageRef,
                ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT,
                FileFingerprint.of(measurement),
                FileFingerprint.of(definition),
                planFingerprint,
                signalCount,
                sampleCount,
                result.firstSampleTimestampNanos().orElse(null),
                result.lastSampleTimestampNanos().orElse(null),
                ProcessingArtifactMetadata.STATUS_VALID,
                Instant.now(),
                result.mappingLifecycle().name(),
                result.status().name(),
                ArtifactDiagnosticsCodec.encode(result.diagnostics()),
                result.technicalDetail()
        );
        stores.store().upsertProcessingArtifact(artifact);
    }

    private Path resolveArtifactRoot(ProcessingArtifactMetadata artifact) {
        if (artifact.artifactPath() != null && !artifact.artifactPath().isBlank()) {
            Path absolute = Path.of(artifact.artifactPath()).toAbsolutePath().normalize();
            if (absolute.startsWith(dataRoot) && Files.isDirectory(absolute)) {
                return absolute;
            }
        }
        if (artifact.artifactStorageRef() != null && !artifact.artifactStorageRef().isBlank()) {
            Path logical = dataRoot.resolve(
                            artifact.artifactStorageRef().replace('/', java.io.File.separatorChar))
                    .toAbsolutePath()
                    .normalize();
            ensureUnderDataRoot(logical);
            return logical;
        }
        return jobFinalRoot(
                artifact.projectId(), artifact.sourceId(), artifact.channelId(), artifact.definitionId());
    }

    private boolean isReusable(
            ProcessingArtifactMetadata artifact,
            Path storeRoot,
            Path measurementPath,
            Path definitionPath,
            String expectedPlanFingerprint
    ) {
        if (!ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT.equals(artifact.artifactVersion())
                || !com.stellar.platform.processing.graph.GraphSignalSessionStore.hasCurrentDecoderGeneration(storeRoot)) {
            return false;
        }
        if (!LosslessGraphSignalStore.payloadMatchesValidityLayout(storeRoot)) {
            return false;
        }
        if (!ProcessingArtifactMetadata.STATUS_VALID.equals(artifact.status())) {
            return false;
        }
        if (storeRoot == null || isStagingOnly(storeRoot)) {
            return false;
        }
        if (!LosslessGraphSignalStore.hasValidIndex(storeRoot)) {
            return false;
        }
        if (!LosslessGraphSignalStore.segmentsReadable(storeRoot)) {
            return false;
        }
        if (measurementPath != null
                && !artifact.sourceFingerprint().isBlank()
                && !FileFingerprint.matches(artifact.sourceFingerprint(), measurementPath)) {
            return false;
        }
        if (definitionPath != null
                && !artifact.dbcFingerprint().isBlank()
                && !FileFingerprint.matches(artifact.dbcFingerprint(), definitionPath)) {
            return false;
        }
        return ProcessingPlanFingerprint.matches(artifact.planFingerprint(), expectedPlanFingerprint);
    }

    private void markStale(ProcessingArtifactMetadata artifact) {
        if (artifact == null) {
            return;
        }
        stores.store().upsertProcessingArtifact(artifact.withStatus(ProcessingArtifactMetadata.STATUS_STALE));
    }

    private void ensureUnderDataRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(dataRoot)) {
            throw new IllegalArgumentException("Path escapes processing data root: " + path);
        }
    }

    private static boolean isStagingArtifactRoot(Path storeRoot, Path finalRoot) {
        if (storeRoot == null || finalRoot == null) {
            return false;
        }
        Path storeAbs = storeRoot.toAbsolutePath().normalize();
        Path finalAbs = finalRoot.toAbsolutePath().normalize();
        if (storeAbs.equals(finalAbs)) {
            return false;
        }
        Path expectedStaging = finalAbs.resolveSibling(finalAbs.getFileName().toString() + ".staging");
        return storeAbs.equals(expectedStaging)
                || storeAbs.getFileName().toString().endsWith(".staging");
    }

    private static void commitArtifactDirectory(Path stagingRoot, Path finalRoot) {
        com.stellar.platform.processing.graph.ArtifactStoreCommit.promote(stagingRoot, finalRoot);
    }

    private static void deleteTreeBestEffort(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }

    private static long directorySizeBestEffort(Path root) {
        if (root == null || !Files.isDirectory(root)) {
            return 0L;
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String guessFormat(Path measurement) {
        if (measurement == null) {
            return "UNKNOWN";
        }
        String name = measurement.getFileName().toString().toLowerCase();
        if (name.endsWith(".asc")) {
            return "ASC";
        }
        if (name.endsWith(".mf4") || name.endsWith(".mdf")) {
            return "MF4";
        }
        if (name.endsWith(".blf")) {
            return "BLF";
        }
        return "UNKNOWN";
    }

    private static IndependentJobResult rebuildWithHandle(IndependentJobResult result, Object handle) {
        return new IndependentJobResult(
                result.jobKey(),
                result.definitionSourceId().orElse(null),
                result.definitionDisplayName(),
                result.mappingLifecycle(),
                result.status(),
                result.expectedSelectedChannelFrames(),
                result.sourceFramesRead(),
                result.selectedChannelFrames(),
                result.dbcMatchedFrames(),
                result.dbcUnmatchedFrames(),
                result.signalsProduced(),
                result.firstSampleTimestampNanos().orElse(null),
                result.lastSampleTimestampNanos().orElse(null),
                result.observedSourceTiming().orElse(null),
                result.elapsed(),
                result.diagnostics(),
                result.technicalDetail(),
                handle
        );
    }
}
