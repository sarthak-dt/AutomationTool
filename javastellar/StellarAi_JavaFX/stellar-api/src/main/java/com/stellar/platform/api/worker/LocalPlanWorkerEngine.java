package com.stellar.platform.api.worker;

import com.stellar.platform.api.support.ArtifactDiagnosticsCodec;
import com.stellar.platform.api.support.ProcessingPlanDtoMapper;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.execution.PlanExecutionContext;
import com.stellar.platform.processing.execution.PlanExecutionPolicy;
import com.stellar.platform.processing.execution.PlanJobLifecycleListener;
import com.stellar.platform.processing.execution.ProcessingResultRegistry;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import com.stellar.platform.storage.ArtifactStorageRefs;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.AuditEventMetadata;
import com.stellar.platform.storage.metadata.AuditEventType;
import com.stellar.platform.storage.metadata.FileFingerprint;
import com.stellar.platform.storage.metadata.ProcessingArtifactMetadata;
import com.stellar.platform.storage.metadata.ProcessingJobMetadata;
import com.stellar.platform.storage.metadata.ProcessingPlanFingerprint;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;
import com.stellar.platform.storage.metadata.SignalCatalogMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Reconstructs {@link ProjectPlanExecutor} work from a typed descriptor.
 * Runs only in the worker JVM — no JavaFX, no Runnable transport.
 */
public final class LocalPlanWorkerEngine {

    public record Outcome(String status, int jobs, int successful, String message) {
        public String toJson() {
            return "{\"status\":\"" + escape(status) + "\",\"jobs\":" + jobs
                    + ",\"successful\":" + successful + ",\"message\":\"" + escape(message) + "\"}";
        }

        private static String escape(String value) {
            if (value == null) {
                return "";
            }
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    public interface EventSink {
        void emit(WorkerMessage message);
    }

    public Outcome execute(ProcessingTaskDescriptor task, EventSink events, AtomicBoolean cancelled) {
        Objects.requireNonNull(task, "task");
        EventSink sink = events == null ? ignored -> { } : events;
        if (task.kind() != ProcessingTaskDescriptor.Kind.PLAN) {
            throw new IllegalArgumentException("LocalPlanWorkerEngine requires Kind.PLAN");
        }
        if (task.planJson().isBlank()) {
            throw new IllegalStateException("PLAN task is missing the processing-plan snapshot");
        }
        if (task.analysisStorageRoot().isBlank()) {
            throw new IllegalStateException("PLAN task is missing Analysis Storage root");
        }
        Path analysisRoot = Path.of(task.analysisStorageRoot()).toAbsolutePath().normalize();
        AnalysisStorageRoots.setUserSelectedRoot(analysisRoot);
        AnalysisStorageRoots.ensureControlledDirectory();

        ProjectProcessingPlan plan;
        Map<String, Path> measurements;
        Map<String, Path> definitions;
        Map<String, DbcDatabase> dbcCache = new HashMap<>();
        try {
            plan = ProcessingPlanDtoMapper.toDomain(
                    WorkerJson.MAPPER.readValue(task.planJson(), com.stellar.platform.api.dto.ProcessingPlanDto.class));
            measurements = paths(task.sourcePathById());
            definitions = paths(task.definitionPathById());
            DbcParser dbcParser = new DbcParser();
            for (Map.Entry<String, Path> e : definitions.entrySet()) {
                dbcCache.put(e.getKey(), dbcParser.parse(e.getValue()));
            }
            for (ProcessingJob job : plan.jobs()) {
                System.getLogger(LocalPlanWorkerEngine.class.getName()).log(
                        System.Logger.Level.INFO,
                        () -> "Worker reconstructed job=" + job.key()
                                + " source=" + job.projectSourceId()
                                + " channel=" + job.channelId()
                                + " mapping=" + job.mappingLifecycle()
                                + " definitionId=" + (job.definitionSourceId().isPresent() ? "present" : "absent")
                                + " executable=" + job.executable()
                                + " readiness=" + plan.readiness());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Cannot reconstruct processing plan: "
                    + (e.getMessage() == null ? e.toString() : e.getMessage()), e);
        }

        ProjectMetadataStore store = null;
        ProcessingResultRegistry registry = new ProcessingResultRegistry();
        if (!task.projectDbPath().isBlank()) {
            store = new SqliteProjectMetadataStore(Path.of(task.projectDbPath()));
        }
        try {
            if (store != null && !task.forceReprocess()) {
                seedRegistry(store, task.projectId(), plan, registry, measurements, definitions);
            }
            if (store != null) {
                store.appendAuditEvent(AuditEventMetadata.of(
                        task.projectId(), AuditEventType.PROCESSING_STARTED, task.runId(), "Worker PLAN started"));
            }
            sink.emit(WorkerMessage.ofType(WorkerProtocol.JOB_ACCEPTED).withTaskId(task.taskId()));
            sink.emit(WorkerMessage.ofType(WorkerProtocol.JOB_STARTED).withTaskId(task.taskId()));

            SimpleCancellationToken innerToken = new SimpleCancellationToken();
            CancellationToken token = () -> {
                if (cancelled != null && cancelled.get()) {
                    innerToken.cancel();
                }
                return innerToken.isCancellationRequested();
            };
            if (cancelled != null && cancelled.get()) {
                innerToken.cancel();
                discardStagingGenerations(task, plan);
                return new Outcome("CANCELLED", plan.jobs().size(), 0, "Cancelled");
            }
            MeasurementDecodePipeline pipeline = MeasurementDecodePipeline.createDefault();
            ProjectMetadataStore ledger = store;
            Instant runStarted = Instant.now();
            MidFileJobResume resume = new MidFileJobResume(task, ledger, measurements, definitions, sink);
            for (ProcessingJob job : plan.jobs()) {
                MidFileJobResume.Decision decision = resume.prepare(job);
                sink.emit(progress(task.taskId(), 0d, decision.stageMessage()));
            }
            int batchSize = task.processingBatchSize() > 0
                    ? task.processingBatchSize()
                    : com.stellar.platform.processing.ProcessingRequest.DEFAULT_BATCH_SIZE;
            PlanExecutionContext context = PlanExecutionContext.builder(pipeline)
                    .measurementPathResolver(measurements::get)
                    .definitionPathResolver(definitions::get)
                    .definitionDatabaseResolver(id -> resolveWorkerDefinition(id, dbcCache))
                    .cancellationToken(token)
                    .batchSize(batchSize)
                    .progressListener(new WorkerLiveProgress(sink, task.taskId()))
                    .resumeSupport(resume.support())
                    .signalStoreFactory((job, measurementName, definitionName) ->
                            openStore(task, job, measurementName, definitionName, resume))
                    .build();

            PlanJobLifecycleListener life = new PlanJobLifecycleListener() {
                @Override
                public void onJobStarting(ProcessingJob job, int index, int total) {
                    if (cancelled != null && cancelled.get()) {
                        innerToken.cancel();
                    }
                    persistJob(ledger, task, job, JobExecutionStatus.RUNNING, index, total, null, runStarted);
                    sink.emit(jobProgress(task.taskId(), (double) index / Math.max(1, total),
                            WorkerProtocol.STAGE_JOB_STARTED, job));
                }

                @Override
                public void onJobFinished(IndependentJobResult result, int index, int total) {
                    IndependentJobResult durable = commitIfSuccessful(task, result, measurements, definitions, ledger);
                    if (durable != result) {
                        registry.publishJob(durable);
                    }
                    persistJob(ledger, task, jobOf(plan, result), durable.status(), index, total, durable, runStarted);
                    if (durable.successful()) {
                        resume.markCompleted(jobOf(plan, result));
                        sink.emit(WorkerMessage.ofType(WorkerProtocol.CHECKPOINT_COMMITTED)
                                .withTaskId(task.taskId()));
                    }
                    sink.emit(jobProgress(task.taskId(), (double) (index + 1) / Math.max(1, total),
                            durable.status().name(), jobOf(plan, result)));
                }

                @Override
                public void onJobReused(ProcessingJob job, IndependentJobResult result, int index, int total) {
                    persistJob(ledger, task, job, result.status(), index, total, result, true, runStarted);
                    sink.emit(jobProgress(task.taskId(), (double) (index + 1) / Math.max(1, total), "REUSED", job));
                }
            };

            com.stellar.platform.processing.execution.ProjectPlanExecutor executor =
                    new com.stellar.platform.processing.execution.ProjectPlanExecutor(context, registry);
            PlanExecutionPolicy policy = task.forceReprocess()
                    ? PlanExecutionPolicy.FORCE_REPROCESS
                    : PlanExecutionPolicy.REUSE_COMPLETED;
            MultiSourceProcessingResult batch = executor.execute(plan, life, policy);
            if (cancelled != null && cancelled.get()) {
                resume.discardOnUserCancel(plan.jobs());
                discardStagingGenerations(task, plan);
                return new Outcome("CANCELLED", batch.requestedJobs(), batch.successfulJobs(), "Cancelled");
            }
            if (store != null) {
                AuditEventType type = batch.status() == com.stellar.platform.domain.processing.plan.ProjectExecutionStatus.FAILED
                        ? AuditEventType.PROCESSING_FAILED
                        : AuditEventType.PROCESSING_COMPLETED;
                store.appendAuditEvent(AuditEventMetadata.of(
                        task.projectId(), type, task.runId(), batch.status().name()));
            }
            return new Outcome(
                    batch.status().name(),
                    batch.requestedJobs(),
                    batch.successfulJobs(),
                    batch.status().name());
        } finally {
            try {
                registry.clear();
            } catch (RuntimeException ignored) {
            }
            if (store instanceof SqliteProjectMetadataStore sqlite) {
                try {
                    sqlite.close();
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    /**
     * Native-only jobs travel with {@code definitionId == null}. The pipeline still requires a
     * non-null {@link DbcDatabase}; an empty database is the intentional resolution, not a
     * missing-configuration failure.
     */
    private static Optional<DbcDatabase> resolveWorkerDefinition(String definitionId, Map<String, DbcDatabase> cache) {
        if (definitionId == null || definitionId.isBlank()) {
            return Optional.of(com.stellar.platform.processing.execution.ProjectPlanExecutor.emptyDefinitionDatabase());
        }
        return Optional.ofNullable(cache.get(definitionId));
    }

    private static Map<String, Path> paths(Map<String, String> raw) {
        Map<String, Path> out = new HashMap<>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && !e.getValue().isBlank()) {
                out.put(e.getKey(), Path.of(e.getValue()));
            }
        }
        return out;
    }

    private static GraphSignalSessionStore openStore(
            ProcessingTaskDescriptor task,
            ProcessingJob job,
            String measurementName,
            String definitionName,
            MidFileJobResume resume
    ) {
        Path finalRoot = ArtifactStorageRefs.jobRoot(
                task.projectId(), job.projectSourceId(), job.channelId(), job.definitionSourceId().orElse(""));
        MidFileJobResume.Decision decision = resume.decision(job);
        if (decision.resume()) {
            GraphSignalSessionStore session = GraphSignalSessionStore.openExistingMatchingSamples(
                    finalRoot, decision.ledgerSamples());
            resume.rememberSession(job, session);
            return session;
        }
        // Replacement writes stay on staging. The last VALID final root is replaced only
        // after a successful commit so user-cancel / failed reprocess cannot destroy G1.
        Path staging = finalRoot.resolveSibling(finalRoot.getFileName().toString() + ".staging");
        GraphSignalSessionStore session = GraphSignalSessionStore.createPersistent(
                staging, measurementName, definitionName);
        MidFileJobResume.writeIncompleteFlag(staging);
        resume.rememberSession(job, session);
        return session;
    }

    /** User cancel discards only the incomplete replacement tree. Last VALID final root stays. */
    private static void discardStagingGenerations(ProcessingTaskDescriptor task, ProjectProcessingPlan plan) {
        if (task == null || plan == null) {
            return;
        }
        for (ProcessingJob job : plan.jobs()) {
            Path finalRoot = ArtifactStorageRefs.jobRoot(
                    task.projectId(), job.projectSourceId(), job.channelId(), job.definitionSourceId().orElse(""));
            Path staging = finalRoot.resolveSibling(finalRoot.getFileName().toString() + ".staging");
            deleteTree(staging);
        }
    }

    private static IndependentJobResult commitIfSuccessful(
            ProcessingTaskDescriptor task,
            IndependentJobResult result,
            Map<String, Path> measurements,
            Map<String, Path> definitions,
            ProjectMetadataStore store
    ) {
        if (result == null || !result.successful()) {
            return result;
        }
        Object handle = result.catalogHandle().orElse(null);
        if (!(handle instanceof GraphSignalSessionStore session)) {
            return result;
        }
        String definitionId = result.definitionSourceId().orElse("");
        Path finalRoot = ArtifactStorageRefs.jobRoot(
                task.projectId(), result.projectSourceId(), result.channelId(), definitionId);
        Path storeRoot = session.root();
        session.finishWriteSession();
        if (isStaging(storeRoot, finalRoot)) {
            commitDirectory(storeRoot, finalRoot);
        }
        MidFileJobResume.clearIncompleteFlag(finalRoot);
        GraphSignalSessionStore committed;
        try {
            committed = GraphSignalSessionStore.openExisting(finalRoot);
        } catch (RuntimeException ex) {
            return result;
        }
        IndependentJobResult rebuilt = rebuild(result, committed);
        if (store != null) {
            upsertArtifact(store, task, rebuilt, committed, measurements.get(result.projectSourceId()),
                    result.definitionSourceId().map(definitions::get).orElse(null), definitionId);
        }
        return rebuilt;
    }

    private static void seedRegistry(
            ProjectMetadataStore store,
            String projectId,
            ProjectProcessingPlan plan,
            ProcessingResultRegistry registry,
            Map<String, Path> measurements,
            Map<String, Path> definitions
    ) {
        for (ProcessingJob job : plan.jobs()) {
            if (registry.get(job.key()).isPresent()) {
                continue;
            }
            String definitionId = job.definitionSourceId().orElse("");
            Optional<ProcessingArtifactMetadata> found =
                    store.findValidArtifact(projectId, job.projectSourceId(), job.channelId(), definitionId);
            if (found.isEmpty()) {
                continue;
            }
            ProcessingArtifactMetadata artifact = found.get();
            Path root = ArtifactStorageRefs.resolveLocal(artifact.resolutionKey());
            if (GraphSignalSessionStore.hasIncompleteFlag(root)) {
                continue;
            }
            if (!ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT.equals(artifact.artifactVersion())
                    || !GraphSignalSessionStore.hasCurrentDecoderGeneration(root)
                    || !LosslessGraphSignalStore.payloadMatchesValidityLayout(root)) {
                continue;
            }
            Path measurement = measurements.get(job.projectSourceId());
            Path definition = definitionId.isBlank() ? null : definitions.get(definitionId);
            if (measurement != null && !artifact.sourceFingerprint().isBlank()
                    && !FileFingerprint.matches(artifact.sourceFingerprint(), measurement)) {
                continue;
            }
            if (definition != null && !artifact.dbcFingerprint().isBlank()
                    && !FileFingerprint.matches(artifact.dbcFingerprint(), definition)) {
                continue;
            }
            try {
                GraphSignalSessionStore opened = GraphSignalSessionStore.openExisting(root);
                MappingLifecycle lifecycle;
                try {
                    lifecycle = MappingLifecycle.valueOf(artifact.mappingLifecycle());
                } catch (RuntimeException ex) {
                    lifecycle = MappingLifecycle.MANUAL;
                }
                registry.publishJob(new IndependentJobResult(
                        job.key(),
                        definitionId.isBlank() ? null : definitionId,
                        definition == null ? "" : definition.getFileName().toString(),
                        lifecycle,
                        ArtifactDiagnosticsCodec.parseStatus(artifact.jobExecutionStatus()),
                        artifact.sampleCount(),
                        artifact.sampleCount(),
                        artifact.sampleCount(),
                        artifact.sampleCount(),
                        0L,
                        artifact.signalCount(),
                        artifact.startTimestampNanos(),
                        artifact.endTimestampNanos(),
                        null,
                        java.time.Duration.ZERO,
                        ArtifactDiagnosticsCodec.decode(artifact.diagnosticsSummary()),
                        artifact.technicalDetail().isBlank() ? "reopened-from-durable-job" : artifact.technicalDetail(),
                        opened
                ));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void upsertArtifact(
            ProjectMetadataStore store,
            ProcessingTaskDescriptor task,
            IndependentJobResult result,
            GraphSignalSessionStore session,
            Path measurement,
            Path definition,
            String definitionId
    ) {
        String artifactId = task.projectId() + "|" + result.projectSourceId()
                + "|" + result.channelId() + "|" + definitionId;
        String storageRef = ArtifactStorageRefs.forJob(
                task.projectId(), result.projectSourceId(), result.channelId(), definitionId);
        String format = result.jobKey() == null ? "UNKNOWN" : guessFormat(measurement);
        String planFingerprint = ProcessingPlanFingerprint.forJob(
                result.projectSourceId(), result.channelId(), definitionId,
                result.mappingLifecycle().name(), format);
        store.upsertProcessingArtifact(new ProcessingArtifactMetadata(
                artifactId,
                task.projectId(),
                result.projectSourceId(),
                result.channelId(),
                definitionId,
                task.runId(),
                ProcessingArtifactMetadata.TYPE_LOSSLESS_SIGNAL_STORE,
                "",
                storageRef,
                ProcessingArtifactMetadata.ARTIFACT_VERSION_CURRENT,
                FileFingerprint.of(measurement),
                FileFingerprint.of(definition),
                planFingerprint,
                session.snapshot().entries().size(),
                session.losslessStore().totalSampleCount(),
                result.firstSampleTimestampNanos().orElse(null),
                result.lastSampleTimestampNanos().orElse(null),
                ProcessingArtifactMetadata.STATUS_VALID,
                Instant.now(),
                result.mappingLifecycle().name(),
                result.status().name(),
                ArtifactDiagnosticsCodec.encode(result.diagnostics()),
                result.technicalDetail()
        ));
        persistSignalCatalog(store, task.projectId(), artifactId, result, session);
    }

    private static void persistSignalCatalog(
            ProjectMetadataStore store,
            String projectId,
            String artifactId,
            IndependentJobResult result,
            GraphSignalSessionStore session
    ) {
        Instant now = Instant.now();
        List<SignalCatalogMetadata> rows = new ArrayList<>();
        for (var entry : session.snapshot().entries()) {
            var key = entry.key();
            String signalKey = key.signalName() + "|" + key.sourceType() + "|" + key.messageName()
                    + "|" + key.canId() + "|" + key.channel();
            rows.add(new SignalCatalogMetadata(
                    java.util.UUID.randomUUID().toString(),
                    projectId,
                    result.projectSourceId(),
                    result.channelId(),
                    result.definitionSourceId().orElse(""),
                    artifactId,
                    signalKey,
                    key.signalName(),
                    key.messageName(),
                    key.canId(),
                    key.channel(),
                    key.sourceType().name(),
                    entry.unit(),
                    entry.sampleCount(),
                    entry.firstTimestampNanos(),
                    entry.lastTimestampNanos(),
                    entry.minPhysical(),
                    entry.maxPhysical(),
                    now,
                    now
            ));
        }
        store.replaceSignalCatalog(projectId, artifactId, rows);
    }

    private static void persistJob(
            ProjectMetadataStore store,
            ProcessingTaskDescriptor task,
            ProcessingJob job,
            JobExecutionStatus status,
            int index,
            int total,
            IndependentJobResult result
    ) {
        persistJob(store, task, job, status, index, total, result, false, null);
    }

    private static void persistJob(
            ProjectMetadataStore store,
            ProcessingTaskDescriptor task,
            ProcessingJob job,
            JobExecutionStatus status,
            int index,
            int total,
            IndependentJobResult result,
            Instant runStarted
    ) {
        persistJob(store, task, job, status, index, total, result, false, runStarted);
    }

    private static void persistJob(
            ProjectMetadataStore store,
            ProcessingTaskDescriptor task,
            ProcessingJob job,
            JobExecutionStatus status,
            int index,
            int total,
            IndependentJobResult result,
            boolean reused,
            Instant runStarted
    ) {
        if (store == null || job == null) {
            return;
        }
        Instant now = Instant.now();
        String jobId = job.key().toString();
        Duration elapsed = result == null ? null : result.elapsed();
        ProcessingJobMetadata prior = store.findProcessingJob(jobId).orElse(null);
        if (reused && prior != null && task.runId() != null && !task.runId().equals(prior.runId())) {
            return;
        }
        Instant[] window = executionWindow(prior, status, reused, now, elapsed, task.runId(), runStarted);
        store.upsertProcessingJob(new ProcessingJobMetadata(
                jobId,
                task.projectId(),
                task.runId(),
                job.projectSourceId(),
                job.channelId(),
                job.definitionSourceId().orElse(""),
                status,
                1,
                3,
                total <= 0 ? 0d : (double) (index + 1) / total,
                status.name(),
                "isolated-worker",
                now,
                window[0],
                window[1],
                null,
                result == null ? "" : result.technicalDetail()
        ));
    }

    /**
     * Start and finish instants for a ledger write.
     * <ul>
     *   <li>{@code RUNNING} starts the window at {@code now}. The executor announces every
     *       channel of a source at the moment that source's pass begins.</li>
     *   <li>A terminal write keeps the start written by the matching {@code RUNNING} row and
     *       finishes at {@code now}. Without this the finish write replaced the start and every
     *       job looked like it took zero time.</li>
     *   <li>When the executor measured a positive elapsed and no RUNNING start exists, the
     *       window is {@code now - elapsed} to {@code now}. That is the isolated-worker case:
     *       the desktop reconstructs results with {@code Duration.ZERO} after reopen, so the
     *       ledger must keep the window.</li>
     *   <li>A reused job did no work in this run, so a prior real window is kept as it was.</li>
     *   <li>A later rewrite with {@code Duration.ZERO} (isolated-worker hydrate) must keep a
     *       same-run SUCCESS window. Overwriting it with {@code started==finished} blanks the
     *       first source on Processing Complete.</li>
     *   <li>A queued start is not an execution start. All jobs of a run share that instant, so
     *       using it as every source's start would make later files include earlier files.</li>
     *   <li>A start before the current run began is leftover history. It must not become this
     *       run's window, even when hydrate stamps a 30-minute {@code elapsed} onto the result.</li>
     * </ul>
     */
    public static Instant[] executionWindow(
            ProcessingJobMetadata prior,
            JobExecutionStatus status,
            boolean reused,
            Instant now
    ) {
        return executionWindow(prior, status, reused, now, null, prior == null ? null : prior.runId(), null);
    }

    public static Instant[] executionWindow(
            ProcessingJobMetadata prior,
            JobExecutionStatus status,
            boolean reused,
            Instant now,
            Duration elapsed
    ) {
        return executionWindow(prior, status, reused, now, elapsed, prior == null ? null : prior.runId(), null);
    }

    /**
     * {@code runId} is the run being written. A leftover RUNNING/SUCCESS window from another
     * run must never become this run's start, or Processing Complete shows a 30-minute source
     * on a 10-second run.
     */
    public static Instant[] executionWindow(
            ProcessingJobMetadata prior,
            JobExecutionStatus status,
            boolean reused,
            Instant now,
            Duration elapsed,
            String runId
    ) {
        return executionWindow(prior, status, reused, now, elapsed, runId, null);
    }

    public static Instant[] executionWindow(
            ProcessingJobMetadata prior,
            JobExecutionStatus status,
            boolean reused,
            Instant now,
            Duration elapsed,
            String runId,
            Instant runStarted
    ) {
        if (now == null) {
            now = Instant.now();
        }
        boolean sameRun = prior != null && runId != null && !runId.isBlank()
                && runId.equals(prior.runId());
        boolean elapsedMissing = elapsed == null || elapsed.isZero() || elapsed.isNegative();
        boolean startInRun = prior != null && prior.startedAt() != null
                && (runStarted == null || !prior.startedAt().isBefore(runStarted));
        boolean priorRealSuccess = sameRun && prior.status() != null && prior.status().isSuccessful()
                && startInRun && prior.finishedAt() != null
                && prior.finishedAt().isAfter(prior.startedAt());
        if (!status.isTerminal()) {
            if (sameRun && prior.status() == JobExecutionStatus.RUNNING && startInRun) {
                return new Instant[] {prior.startedAt(), null};
            }
            return new Instant[] {now, null};
        }
        if ((reused || elapsedMissing) && priorRealSuccess) {
            return new Instant[] {prior.startedAt(), prior.finishedAt()};
        }
        if (sameRun && prior.status() == JobExecutionStatus.RUNNING && startInRun
                && !prior.startedAt().isAfter(now)) {
            return new Instant[] {prior.startedAt(), now};
        }
        if (!elapsedMissing) {
            Instant started = now.minus(elapsed);
            boolean elapsedFitsRun = runStarted == null || !started.isBefore(runStarted);
            if (elapsedFitsRun && started.isBefore(now)) {
                return new Instant[] {started, now};
            }
        }
        if (priorRealSuccess) {
            return new Instant[] {prior.startedAt(), prior.finishedAt()};
        }
        return new Instant[] {now, now};
    }

    private static ProcessingJob jobOf(ProjectProcessingPlan plan, IndependentJobResult result) {
        if (plan == null || result == null) {
            return null;
        }
        return plan.jobs().stream()
                .filter(j -> j.key().equals(result.jobKey()))
                .findFirst()
                .orElse(null);
    }

    private static WorkerMessage progress(String taskId, double value, String stage) {
        return jobProgress(taskId, value, stage, null);
    }

    /** Job-scoped events carry the job key in {@code message} so the desktop can update that row live. */
    private static WorkerMessage jobProgress(String taskId, double value, String stage, ProcessingJob job) {
        return new WorkerMessage(
                WorkerProtocol.PROGRESS, WorkerProtocol.VERSION, null, ProcessHandle.current().pid(),
                taskId, null, value, stage, null, job == null ? null : job.key().toString(), null,
                null, null, null, null, null, null, null, true);
    }

    private static IndependentJobResult rebuild(IndependentJobResult result, Object handle) {
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

    private static boolean isStaging(Path storeRoot, Path finalRoot) {
        if (storeRoot == null || finalRoot == null) {
            return false;
        }
        Path storeAbs = storeRoot.toAbsolutePath().normalize();
        Path finalAbs = finalRoot.toAbsolutePath().normalize();
        Path expected = finalAbs.resolveSibling(finalAbs.getFileName().toString() + ".staging");
        return storeAbs.equals(expected) || storeAbs.getFileName().toString().endsWith(".staging");
    }

    private static void commitDirectory(Path stagingRoot, Path finalRoot) {
        com.stellar.platform.processing.graph.ArtifactStoreCommit.promote(stagingRoot, finalRoot);
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static String guessFormat(Path measurement) {
        if (measurement == null || measurement.getFileName() == null) {
            return "UNKNOWN";
        }
        String name = measurement.getFileName().toString().toLowerCase();
        if (name.endsWith(".asc")) {
            return "ASC";
        }
        if (name.endsWith(".mf4") || name.endsWith(".mdf")) {
            return "MF4";
        }
        return "UNKNOWN";
    }
}
