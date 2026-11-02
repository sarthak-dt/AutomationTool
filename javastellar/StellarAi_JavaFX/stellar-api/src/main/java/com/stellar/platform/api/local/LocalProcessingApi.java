package com.stellar.platform.api.local;

import com.stellar.platform.api.ProcessingApi;
import com.stellar.platform.api.assets.FileAssetStore;
import com.stellar.platform.api.dto.JobDto;
import com.stellar.platform.api.dto.JobStatusDto;
import com.stellar.platform.api.dto.PlanPreviewDto;
import com.stellar.platform.api.dto.PlanPreviewRequest;
import com.stellar.platform.api.dto.ProcessJobRequest;
import com.stellar.platform.api.dto.ProcessResultDto;
import com.stellar.platform.api.dto.ProcessingRunRequest;
import com.stellar.platform.api.dto.ProcessingRunResultDto;
import com.stellar.platform.api.identity.FileAssetStatuses;
import com.stellar.platform.api.support.DurableJobArtifactService;
import com.stellar.platform.api.support.InMemoryJobTracker;
import com.stellar.platform.api.support.MetadataStoreFactory;
import com.stellar.platform.api.support.PlatformMetrics;
import com.stellar.platform.api.support.ProcessSessionRegistry;
import com.stellar.platform.api.support.ProcessingLog;
import com.stellar.platform.api.support.ProcessingPlanDtoMapper;
import com.stellar.platform.api.support.ProcessingRunResultMapper;
import com.stellar.platform.api.worker.InProcessLocalWorker;
import com.stellar.platform.api.worker.LocalWorkerBoundary;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.execution.PlanExecutionContext;
import com.stellar.platform.processing.execution.PlanExecutionPolicy;
import com.stellar.platform.processing.execution.PlanJobLifecycleListener;
import com.stellar.platform.processing.execution.ProcessingResultRegistry;
import com.stellar.platform.processing.execution.ProjectPlanExecutor;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

public final class LocalProcessingApi implements ProcessingApi {

    private final MeasurementDecodePipeline pipeline;
    private final DbcParser dbcParser;
    private final InMemoryJobTracker jobs;
    private final ProcessSessionRegistry sessions;
    private final FileAssetStore assets;
    private final DurableJobArtifactService durableJobs;
    private final MetadataStoreFactory stores;
    private final LocalWorkerBoundary worker;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, ProcessResultDto> results = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProcessingRunResultDto> runResults = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SimpleCancellationToken> tokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProcessingResultRegistry> registries = new ConcurrentHashMap<>();

    public LocalProcessingApi(
            MeasurementDecodePipeline pipeline,
            InMemoryJobTracker jobs,
            ProcessSessionRegistry sessions,
            FileAssetStore assets,
            MetadataStoreFactory stores
    ) {
        this(pipeline, new DbcParser(), jobs, sessions, assets, stores, InProcessLocalWorker.forProcessing());
    }

    LocalProcessingApi(
            MeasurementDecodePipeline pipeline,
            DbcParser dbcParser,
            InMemoryJobTracker jobs,
            ProcessSessionRegistry sessions,
            FileAssetStore assets,
            MetadataStoreFactory stores,
            LocalWorkerBoundary worker
    ) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.dbcParser = Objects.requireNonNull(dbcParser, "dbcParser");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.stores = Objects.requireNonNull(stores, "stores");
        this.durableJobs = new DurableJobArtifactService(stores);
        this.worker = Objects.requireNonNull(worker, "worker");
        this.executor = worker instanceof InProcessLocalWorker inProcess
                ? inProcess.executor()
                : java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "stellar-api-process-fallback");
                    t.setDaemon(true);
                    return t;
                });
        this.jobs.failRunning("Interrupted by application restart");
    }

    /** Test/package constructor retaining an explicit executor. */
    LocalProcessingApi(
            MeasurementDecodePipeline pipeline,
            DbcParser dbcParser,
            InMemoryJobTracker jobs,
            ProcessSessionRegistry sessions,
            FileAssetStore assets,
            MetadataStoreFactory stores,
            ExecutorService executor
    ) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.dbcParser = Objects.requireNonNull(dbcParser, "dbcParser");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.stores = Objects.requireNonNull(stores, "stores");
        this.durableJobs = new DurableJobArtifactService(stores);
        this.worker = null;
        this.executor = Objects.requireNonNull(executor, "executor");
        this.jobs.failRunning("Interrupted by application restart");
    }

    public LocalWorkerBoundary workerBoundary() {
        return worker;
    }

    public void shutdown() {
        if (worker != null) {
            worker.shutdown();
        } else {
            com.stellar.platform.api.support.BoundedExecutors.shutdownQuietly(executor, "stellar-api-process", 5L);
        }
    }

    @Override
    public PlanPreviewDto previewPlan(String projectId, PlanPreviewRequest request) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(request, "request");
        requireProject(projectId);
        boolean valid = isCompleteAsset(projectId, request.measurementAssetId())
                && isCompleteAsset(projectId, request.definitionAssetId());
        String message = valid
                ? "Plan ready: " + request.selectionLabel()
                : "Measurement or definition asset is missing or incomplete";
        return new PlanPreviewDto(
                projectId,
                request.measurementAssetId(),
                request.definitionAssetId(),
                request.selectionLabel(),
                valid,
                message
        );
    }

    @Override
    public JobDto startProcessJob(String projectId, ProcessJobRequest request) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(request, "request");
        requireProject(projectId);
        requireAssetOwned(projectId, request.measurementAssetId());
        requireAssetOwned(projectId, request.definitionAssetId());
        JobDto job = jobs.create(projectId, "PROCESS");
        SimpleCancellationToken token = new SimpleCancellationToken();
        tokens.put(tokenKey(projectId, job.jobId()), token);
        jobs.update(projectId, job.jobId(), JobStatusDto.RUNNING, "Processing started", 0.0);
        ProcessingLog.info(
                "process.start", projectId, job.jobId(), job.jobId(), null,
                request.measurementAssetId(), "RUNNING", null, null, "legacy process job");
        executor.execute(() -> runJob(projectId, job.jobId(), request, token));
        return jobs.get(projectId, job.jobId()).orElse(job);
    }

    @Override
    public JobDto startProcessingRun(String projectId, ProcessingRunRequest request) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(request, "request");
        requireProject(projectId);
        for (String assetId : request.measurementAssetBySourceId().values()) {
            requireAssetOwned(projectId, assetId);
        }
        for (String assetId : request.definitionAssetByDefinitionId().values()) {
            requireAssetOwned(projectId, assetId);
        }
        JobDto job = jobs.create(projectId, "PROCESSING_RUN");
        SimpleCancellationToken token = new SimpleCancellationToken();
        tokens.put(tokenKey(projectId, job.jobId()), token);
        jobs.update(projectId, job.jobId(), JobStatusDto.RUNNING, "Processing run started", 0.0);
        ProcessingLog.info(
                "run.start", projectId, job.jobId(), null, null, null, "RUNNING", null, null,
                "policy=" + request.executionPolicy());
        executor.execute(() -> runPlan(projectId, job.jobId(), request, token));
        return jobs.get(projectId, job.jobId()).orElse(job);
    }

    @Override
    public JobDto getProcessJob(String projectId, String jobId) {
        return jobs.get(projectId, jobId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown process job: " + jobId));
    }

    @Override
    public JobDto cancelProcessJob(String projectId, String jobId) {
        SimpleCancellationToken token = tokens.get(tokenKey(projectId, jobId));
        if (token != null) {
            token.cancel();
        }
        ProcessingLog.info(
                "job.cancel", projectId, jobId, jobId, null, null, "CANCELLED", null, null,
                "Cancellation requested");
        PlatformMetrics.get().increment("job.outcome.cancelled");
        return jobs.update(projectId, jobId, JobStatusDto.CANCELLED, "Cancellation requested", null);
    }

    @Override
    public List<ProcessResultDto> listProcessResults(String projectId) {
        List<ProcessResultDto> out = new ArrayList<>();
        for (Map.Entry<String, ProcessResultDto> e : results.entrySet()) {
            if (e.getKey().startsWith(projectId + "::")) {
                out.add(e.getValue());
            }
        }
        return List.copyOf(out);
    }

    @Override
    public Optional<ProcessResultDto> getProcessResult(String projectId, String jobId) {
        return Optional.ofNullable(results.get(resultKey(projectId, jobId)));
    }

    @Override
    public Optional<ProcessingRunResultDto> getProcessingRunResult(String projectId, String runId) {
        return Optional.ofNullable(runResults.get(resultKey(projectId, runId)));
    }

    private void runPlan(
            String projectId,
            String runId,
            ProcessingRunRequest request,
            SimpleCancellationToken token
    ) {
        long started = System.currentTimeMillis();
        try {
            ProjectProcessingPlan plan = ProcessingPlanDtoMapper.toDomain(request.plan());
            PlanExecutionPolicy policy;
            try {
                policy = PlanExecutionPolicy.valueOf(request.executionPolicy());
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("Unknown executionPolicy: " + request.executionPolicy(), ex);
            }

            Map<String, Path> measurementPaths = new HashMap<>();
            for (Map.Entry<String, String> e : request.measurementAssetBySourceId().entrySet()) {
                measurementPaths.put(e.getKey(), assets.resolveReadablePath(projectId, e.getValue()));
            }
            Map<String, Path> definitionPaths = new HashMap<>();
            Map<String, DbcDatabase> definitionCache = new HashMap<>();
            for (Map.Entry<String, String> e : request.definitionAssetByDefinitionId().entrySet()) {
                Path path = assets.resolveReadablePath(projectId, e.getValue());
                definitionPaths.put(e.getKey(), path);
                definitionCache.put(e.getKey(), dbcParser.parse(path));
            }

            ProcessingResultRegistry registry = registries.computeIfAbsent(
                    projectId, id -> new ProcessingResultRegistry());
            if (policy == PlanExecutionPolicy.FORCE_REPROCESS) {
                durableJobs.invalidatePlanJobs(projectId, plan);
                registry.clear();
            } else if (policy == PlanExecutionPolicy.REUSE_COMPLETED) {
                int seeded = durableJobs.seedRegistry(
                        projectId,
                        plan,
                        registry,
                        measurementPaths::get,
                        definitionPaths::get
                );
                if (seeded > 0) {
                    ProcessingLog.info(
                            "run.seeded", projectId, runId, null, null, null, "RUNNING", null, null,
                            "seededJobs=" + seeded);
                }
            }

            PlanExecutionContext context = PlanExecutionContext.builder(pipeline)
                    .measurementPathResolver(measurementPaths::get)
                    .definitionPathResolver(definitionPaths::get)
                    .definitionDatabaseResolver(id -> resolveDefinitionDatabase(id, definitionCache))
                    .batchSize(request.batchSize())
                    .cancellationToken(token)
                    .signalStoreFactory((job, measurementName, definitionName) ->
                            durableJobs.openWriteSession(projectId, job, measurementName, definitionName))
                    .progressListener(new ProcessingProgressListener() {
                        @Override
                        public void onStage(com.stellar.platform.processing.ProcessingStage stage) {
                            jobs.update(projectId, runId, JobStatusDto.RUNNING, stage.name(), 0.5);
                        }
                    })
                    .build();

            Map<com.stellar.platform.domain.processing.plan.ProcessingJobKey, ProcessingJob> jobsByKey =
                    new HashMap<>();
            for (ProcessingJob job : plan.jobs()) {
                jobsByKey.put(job.key(), job);
            }

            PlanJobLifecycleListener lifecycle = new PlanJobLifecycleListener() {
                @Override
                public void onJobStarting(ProcessingJob job, int index, int total) {
                    // no-op: progress updates come from ProcessingProgressListener
                }

                @Override
                public void onJobFinished(IndependentJobResult result, int index, int total) {
                    if (result == null || !result.successful()) {
                        if (result != null && !result.successful()) {
                            PlatformMetrics.get().increment("job.outcome.failed");
                        }
                        return;
                    }
                    Path measurement = measurementPaths.get(result.projectSourceId());
                    Path definition = result.definitionSourceId().map(definitionPaths::get).orElse(null);
                    ProcessingJob planJob = jobsByKey.get(result.jobKey());
                    String format = planJob == null ? null : planJob.sourceFormat().name();
                    IndependentJobResult durable = durableJobs.commitSuccessfulJob(
                            projectId, runId, result, measurement, definition, format);
                    if (durable != result) {
                        registry.publishJob(durable);
                    }
                    ProcessingLog.info(
                            "job.completed",
                            projectId,
                            runId,
                            result.jobKey().toString(),
                            result.projectSourceId(),
                            request.measurementAssetBySourceId().get(result.projectSourceId()),
                            result.status().name(),
                            result.elapsed().toMillis(),
                            result.diagnostics().isEmpty() ? null : result.diagnostics().getFirst().code().name(),
                            "channel=" + result.channelId()
                    );
                }
            };

            ProjectPlanExecutor executor = new ProjectPlanExecutor(context, registry);
            MultiSourceProcessingResult batch = executor.execute(plan, lifecycle, policy);
            // Prefer registry handles (durable reopen) over staging paths moved on commit.
            MultiSourceProcessingResult durableBatch = withRegistryHandles(batch, registry);
            publishMergedCatalog(projectId, durableBatch);

            String message = "Processing run " + durableBatch.status().name()
                    + " (" + durableBatch.successfulJobs() + "/" + durableBatch.requestedJobs() + " jobs succeeded)";
            ProcessingRunResultDto dto = ProcessingRunResultMapper.toDto(projectId, runId, durableBatch, message);
            runResults.put(resultKey(projectId, runId), dto);

            JobStatusDto status = switch (durableBatch.status()) {
                case SUCCESS -> JobStatusDto.SUCCEEDED;
                case PARTIAL_FAILURE -> JobStatusDto.SUCCEEDED;
                case CANCELLED -> JobStatusDto.CANCELLED;
                case FAILED, BLOCKED -> JobStatusDto.FAILED;
            };
            if (token.isCancellationRequested() && status != JobStatusDto.SUCCEEDED) {
                status = JobStatusDto.CANCELLED;
            }
            long duration = System.currentTimeMillis() - started;
            PlatformMetrics.get().recordDurationMs("processing.duration", duration);
            jobs.update(projectId, runId, status, message, 1.0);
            ProcessingLog.info(
                    "run.finished", projectId, runId, null, null, null, status.name(), duration, null, message);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            ProcessingRunResultDto dto = new ProcessingRunResultDto(
                    projectId,
                    runId,
                    request.plan().planId(),
                    "FAILED",
                    java.time.Instant.now(),
                    0L,
                    List.of(),
                    List.of(),
                    message
            );
            runResults.put(resultKey(projectId, runId), dto);
            jobs.update(
                    projectId,
                    runId,
                    token.isCancellationRequested() ? JobStatusDto.CANCELLED : JobStatusDto.FAILED,
                    message,
                    null
            );
            PlatformMetrics.get().increment("job.outcome.failed");
            ProcessingLog.error("run.failed", projectId, runId, message, e);
        } finally {
            tokens.remove(tokenKey(projectId, runId));
        }
    }

    /**
     * Native-only jobs travel with {@code definitionId == null}. Resolve those to the empty
     * database object the pipeline requires. A requested id that is absent stays unavailable.
     */
    private static Optional<DbcDatabase> resolveDefinitionDatabase(
            String definitionId,
            Map<String, DbcDatabase> cache
    ) {
        if (definitionId == null || definitionId.isBlank()) {
            return Optional.of(ProjectPlanExecutor.emptyDefinitionDatabase());
        }
        return Optional.ofNullable(cache.get(definitionId));
    }

    private static MultiSourceProcessingResult withRegistryHandles(
            MultiSourceProcessingResult batch,
            ProcessingResultRegistry registry
    ) {
        if (batch == null || registry == null) {
            return batch;
        }
        List<IndependentJobResult> merged = new ArrayList<>(batch.jobResults().size());
        for (IndependentJobResult result : batch.jobResults()) {
            merged.add(registry.get(result.jobKey()).orElse(result));
        }
        return new MultiSourceProcessingResult(
                batch.planId(),
                batch.createdAt(),
                batch.status(),
                merged,
                batch.diagnostics(),
                batch.elapsed()
        );
    }

    private void publishMergedCatalog(String projectId, MultiSourceProcessingResult batch) {
        List<ProjectMergedSignalStore.Member> members = new ArrayList<>();
        for (IndependentJobResult result : batch.jobResults()) {
            Object handle = result.catalogHandle().orElse(null);
            if (handle instanceof SignalSeriesStore store) {
                members.add(new ProjectMergedSignalStore.Member(
                        store,
                        result.projectSourceId(),
                        result.definitionSourceId().orElse("unmapped"),
                        result.projectSourceId(),
                        result.definitionDisplayName().isBlank() ? "unmapped" : result.definitionDisplayName()
                ));
            }
        }
        if (members.isEmpty()) {
            return;
        }
        if (members.size() == 1) {
            sessions.put(projectId, members.getFirst().store());
        } else {
            sessions.put(projectId, ProjectMergedSignalStore.fromMembers(members, "project", "merged"));
        }
    }

    private void runJob(
            String projectId,
            String jobId,
            ProcessJobRequest request,
            SimpleCancellationToken token
    ) {
        String measurementAssetId = request.measurementAssetId();
        String definitionAssetId = request.definitionAssetId();
        long started = System.currentTimeMillis();
        try {
            Path measurement = assets.resolveReadablePath(projectId, measurementAssetId);
            Path definition = assets.resolveReadablePath(projectId, definitionAssetId);
            DbcDatabase database = dbcParser.parse(definition);
            InMemorySignalCatalogStore catalog = new InMemorySignalCatalogStore(
                    measurement.getFileName().toString(),
                    definition.getFileName().toString(),
                    request.maxSamplesPerSignal()
            );
            ReaderContext context = new ReaderContext(request.batchSize(), token);
            ProcessingProgressListener progress = new ProcessingProgressListener() {
                @Override
                public void onStage(com.stellar.platform.processing.ProcessingStage stage) {
                    jobs.update(projectId, jobId, JobStatusDto.RUNNING, stage.name(), 0.5);
                }
            };
            ProcessingResult result = pipeline.process(
                    measurement,
                    database,
                    context,
                    catalog::accept,
                    progress
            );
            sessions.put(projectId, catalog);
            ProcessResultDto dto = new ProcessResultDto(
                    projectId,
                    jobId,
                    measurementAssetId,
                    definitionAssetId,
                    result.completedSuccessfully(),
                    result.cancelled(),
                    result.outcome().name(),
                    result.statistics().validFrames(),
                    result.statistics().signalsEmitted(),
                    catalog.snapshot().entries().size(),
                    result.diagnostics().stream()
                            .map(d -> d.code() + ": " + d.message())
                            .toList(),
                    result.completedSuccessfully() ? "Processing completed" : "Processing finished with errors"
            );
            results.put(resultKey(projectId, jobId), dto);
            JobStatusDto status = result.cancelled()
                    ? JobStatusDto.CANCELLED
                    : (result.completedSuccessfully() ? JobStatusDto.SUCCEEDED : JobStatusDto.FAILED);
            long duration = System.currentTimeMillis() - started;
            PlatformMetrics.get().recordDurationMs("processing.duration", duration);
            PlatformMetrics.get().increment("frames", result.statistics().validFrames());
            jobs.update(projectId, jobId, status, dto.message(), 1.0);
            ProcessingLog.info(
                    "process.finished", projectId, jobId, jobId, null, measurementAssetId,
                    status.name(), duration, null, dto.message());
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            ProcessResultDto dto = new ProcessResultDto(
                    projectId,
                    jobId,
                    measurementAssetId,
                    definitionAssetId,
                    false,
                    token.isCancellationRequested(),
                    "FAILED",
                    0L,
                    0L,
                    0,
                    List.of(message),
                    message
            );
            results.put(resultKey(projectId, jobId), dto);
            jobs.update(
                    projectId,
                    jobId,
                    token.isCancellationRequested() ? JobStatusDto.CANCELLED : JobStatusDto.FAILED,
                    message,
                    null
            );
            ProcessingLog.error("process.failed", projectId, jobId, message, e);
        } finally {
            tokens.remove(tokenKey(projectId, jobId));
        }
    }

    private void requireProject(String projectId) {
        if (stores.store().loadProject(projectId).isEmpty()
                && stores.store().listProjects().stream().noneMatch(p -> p.projectId().equals(projectId))) {
            throw new IllegalArgumentException("Unknown project: " + projectId);
        }
    }

    private void requireAssetOwned(String projectId, String assetId) {
        if (assetId == null || assetId.isBlank()) {
            throw new IllegalArgumentException("assetId must not be blank");
        }
        assets.getAsset(projectId, assetId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Asset not found for project " + projectId + ": " + assetId));
    }

    private boolean isCompleteAsset(String projectId, String assetId) {
        return assets.getAsset(projectId, assetId)
                .filter(a -> FileAssetStatuses.COMPLETE.equals(a.status()))
                .isPresent();
    }

    private static String resultKey(String projectId, String jobId) {
        return projectId + "::" + jobId;
    }

    private static String tokenKey(String projectId, String jobId) {
        return projectId + "::" + jobId;
    }
}
