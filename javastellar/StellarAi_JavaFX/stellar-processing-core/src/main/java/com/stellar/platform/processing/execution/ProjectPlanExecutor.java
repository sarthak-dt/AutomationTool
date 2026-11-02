package com.stellar.platform.processing.execution;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.processing.ProcessingSelection;
import com.stellar.platform.domain.processing.plan.IndependentJobResult;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.MultiSourceProcessingResult;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnostic;
import com.stellar.platform.domain.processing.plan.ProcessingPlanDiagnosticCode;
import com.stellar.platform.domain.processing.plan.ProcessingPlanReadiness;
import com.stellar.platform.domain.processing.plan.ProjectExecutionStatus;
import com.stellar.platform.domain.processing.plan.ProjectProcessingPlan;
import com.stellar.platform.domain.processing.plan.SourceChannelRef;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.BatchSignalConsumer;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.NativeMeasurementPolicy;
import com.stellar.platform.processing.ProcessingDiagnostic;
import com.stellar.platform.processing.ProcessingOutcome;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.ProcessingStatistics;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Executes an immutable {@link ProjectProcessingPlan}.
 * Reuses {@link com.stellar.platform.processing.MeasurementDecodePipeline}.
 * Sequential between measurement sources, deterministic, with no timestamp merging/alignment.
 * <p>
 * All executable channels from the same measurement source are decoded in one streaming file
 * pass, even when different channels use different DBCs. The pipeline routes each channel to its
 * selected DBC and signals are fanned out into independent bounded catalog stores per channel so
 * provenance and result isolation are preserved without redundant source rescans.
 */
public final class ProjectPlanExecutor {

    private static final Logger LOG = Logger.getLogger(ProjectPlanExecutor.class.getName());

    /** Empty DBC used for Auto Decode native-only jobs. Never a stand-in definition identity. */
    private static final DbcDatabase EMPTY_DBC = new DbcDatabase("", List.of(), List.of(), List.of());

    /**
     * Non-null database object for executable native-only jobs ({@code definitionId == null}).
     * Not a definition identity and must never be persisted as a DBC mapping.
     */
    public static DbcDatabase emptyDefinitionDatabase() {
        return EMPTY_DBC;
    }

    private final PlanExecutionContext context;
    private final ProcessingResultRegistry registry;

    public ProjectPlanExecutor(PlanExecutionContext context) {
        this(context, null);
    }

    public ProjectPlanExecutor(PlanExecutionContext context, ProcessingResultRegistry registry) {
        this.context = Objects.requireNonNull(context, "context");
        this.registry = registry;
    }

    public MultiSourceProcessingResult execute(ProjectProcessingPlan plan) {
        return execute(plan, PlanJobLifecycleListener.NOOP);
    }

    public MultiSourceProcessingResult execute(ProjectProcessingPlan plan, PlanJobLifecycleListener listener) {
        return execute(plan, listener, PlanExecutionPolicy.REUSE_COMPLETED);
    }

    /**
     * Executes a plan with explicit result-reuse policy. REUSE_COMPLETED is the normal product
     * path: successful jobs with the same source/channel + effective DBC are treated as immutable
     * completed work and are not decoded again. FORCE_REPROCESS is reserved for an explicit
     * user re-run action.
     */
    public MultiSourceProcessingResult execute(
            ProjectProcessingPlan plan,
            PlanJobLifecycleListener listener,
            PlanExecutionPolicy executionPolicy
    ) {
        Objects.requireNonNull(plan, "plan");
        PlanJobLifecycleListener life = listener == null ? PlanJobLifecycleListener.NOOP : listener;
        PlanExecutionPolicy policy = executionPolicy == null
                ? PlanExecutionPolicy.REUSE_COMPLETED : executionPolicy;
        Instant started = Instant.now();
        if (plan.readiness() != ProcessingPlanReadiness.READY) {
            List<ProcessingPlanDiagnostic> blocked = new ArrayList<>(plan.diagnostics());
            if (blocked.isEmpty()) {
                blocked.add(ProcessingPlanDiagnostic.of(
                        ProcessingPlanDiagnosticCode.NO_EFFECTIVE_DEFINITION,
                        null,
                        "plan readiness=" + plan.readiness()));
            }
            MultiSourceProcessingResult result = MultiSourceProcessingResult.blocked(plan.planId(), blocked);
            if (registry != null) {
                registry.publishBatch(result);
            }
            return result;
        }

        List<ProcessingJob> jobs = plan.jobs();
        int total = jobs.size();
        Map<com.stellar.platform.domain.processing.plan.ProcessingJobKey, IndependentJobResult> byJob =
                new LinkedHashMap<>(Math.max(4, total * 2));

        // Build the execution frontier once. Completed compatible jobs are terminal cache hits: they
        // remain part of the returned batch, but never enter a source group and therefore never
        // cause the old MF4/ASC source to be opened again.
        List<IndexedJob> pending = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            ProcessingJob job = jobs.get(index);
            if (policy == PlanExecutionPolicy.REUSE_COMPLETED && registry != null) {
                Optional<IndependentJobResult> reusable =
                        ProcessingPlanResultReconciler.reusableResult(job, registry);
                if (reusable.isPresent()) {
                    IndependentJobResult result = reusable.get();
                    byJob.put(job.key(), result);
                    life.onJobReused(job, result, index, total);
                    LOG.fine(() -> "job=" + job.key() + " reused without source read");
                    continue;
                }
            }
            pending.add(new IndexedJob(index, job));
        }

        Map<ExecutionGroupKey, List<IndexedJob>> groups = groupExecutableJobs(pending);
        Map<String, Integer> nativeOwners = nativeMeasurementOwners(jobs);

        for (List<IndexedJob> group : groups.values()) {
            if (context.cancellationToken().isCancellationRequested()) {
                break;
            }

            for (IndexedJob indexed : group) {
                life.onJobStarting(indexed.job(), indexed.index(), total);
            }

            List<IndependentJobResult> groupResults = null;
            if (group.size() > 1 && canShareSinglePass(group)) {
                groupResults = executeSharedGroup(group.stream().map(IndexedJob::job).toList(), nativeOwners);
            } else if (group.size() > 1 && canFanOutSinglePass(group)) {
                groupResults = executeFanoutGroup(group.stream().map(IndexedJob::job).toList(), nativeOwners)
                        .orElse(null);
            }
            if (groupResults == null) {
                groupResults = new ArrayList<>(group.size());
                for (IndexedJob indexed : group) {
                    if (context.cancellationToken().isCancellationRequested()) {
                        groupResults.add(cancelledJob(indexed.job(), "cancellation requested before job start"));
                    } else if (!indexed.job().executable()) {
                        groupResults.add(blockedJob(indexed.job(), "job not executable in plan snapshot"));
                    } else {
                        groupResults.add(executeJob(indexed.job(), nativeOwners));
                    }
                }
            }

            Map<com.stellar.platform.domain.processing.plan.ProcessingJobKey, IndexedJob> indexedByKey =
                    new LinkedHashMap<>();
            for (IndexedJob indexed : group) {
                indexedByKey.put(indexed.job().key(), indexed);
            }
            for (IndependentJobResult jobResult : groupResults) {
                byJob.put(jobResult.jobKey(), jobResult);
                if (registry != null) {
                    registry.publishJob(jobResult);
                }
                IndexedJob indexed = indexedByKey.get(jobResult.jobKey());
                if (indexed != null) {
                    life.onJobFinished(jobResult, indexed.index(), total);
                }
                LOG.fine(() -> "job=" + jobResult.jobKey() + " status=" + jobResult.status()
                        + " selectedFrames=" + jobResult.selectedChannelFrames()
                        + " signals=" + jobResult.signalsProduced());
            }
        }

        // Preserve immutable plan order even though same-source/same-DBC work may execute as one group.
        List<IndependentJobResult> results = new ArrayList<>(total);
        for (int index = 0; index < total; index++) {
            ProcessingJob job = jobs.get(index);
            IndependentJobResult result = byJob.get(job.key());
            if (result == null) {
                life.onJobStarting(job, index, total);
                result = context.cancellationToken().isCancellationRequested()
                        ? cancelledJob(job, "cancellation requested before job start")
                        : blockedJob(job, "job was not executed");
                if (registry != null) {
                    registry.publishJob(result);
                }
                life.onJobFinished(result, index, total);
            }
            results.add(result);
        }

        ProjectExecutionStatus status = classify(results, context.cancellationToken().isCancellationRequested());
        MultiSourceProcessingResult batch = new MultiSourceProcessingResult(
                plan.planId(),
                Instant.now(),
                status,
                results,
                List.of(),
                Duration.between(started, Instant.now())
        );
        if (registry != null) {
            registry.publishBatch(batch);
        }
        return batch;
    }

    /**
     * Group by measurement source while preserving first-seen source order. Non-executable jobs
     * use their own isolation key and therefore cannot contaminate a routed source pass.
     */
    private static Map<ExecutionGroupKey, List<IndexedJob>> groupExecutableJobs(List<IndexedJob> jobs) {
        Map<ExecutionGroupKey, List<IndexedJob>> groups = new LinkedHashMap<>();
        for (IndexedJob indexed : jobs) {
            ProcessingJob job = indexed.job();
            ExecutionGroupKey key = job.executable()
                    ? new ExecutionGroupKey(job.projectSourceId(), "", "")
                    : new ExecutionGroupKey(job.projectSourceId(), "", job.key().toString());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(indexed);
        }
        return groups;
    }

    /**
     * A measurement source's native (non-bus) channels belong to the file, not to a CAN channel,
     * so exactly one job per source publishes them. The lowest executable channel id is chosen
     * because it is stable across runs: a reused artifact from an earlier run therefore still
     * holds the native series, and the combined catalog never sees the same series twice.
     */
    private static Map<String, Integer> nativeMeasurementOwners(List<ProcessingJob> jobs) {
        Map<String, Integer> owners = new LinkedHashMap<>();
        for (ProcessingJob job : jobs) {
            if (!job.executable()) {
                continue;
            }
            owners.merge(job.projectSourceId(), job.channelId(), Math::min);
        }
        return owners;
    }

    private static NativeMeasurementPolicy nativePolicyFor(ProcessingJob job, Map<String, Integer> owners) {
        Integer owner = owners.get(job.projectSourceId());
        // A source with no executable peer still owns its own native channels.
        return owner == null || owner == job.channelId()
                ? NativeMeasurementPolicy.INCLUDE
                : NativeMeasurementPolicy.EXCLUDE;
    }

    private static boolean isNativeOnlyJob(ProcessingJob job) {
        return job.executable()
                && job.mappingLifecycle().allowsNativeOnlyProcessing()
                && job.definitionSourceId().isEmpty();
    }

    private boolean canShareSinglePass(List<IndexedJob> group) {
        if (group.size() < 2) {
            return false;
        }
        Set<Integer> channels = new HashSet<>();
        for (IndexedJob indexed : group) {
            ProcessingJob job = indexed.job();
            if (!job.executable() || !job.mappingLifecycle().executable() || !channels.add(job.channelId())) {
                return false;
            }
            if (context.resumeSupport().cursorFor(job).isPresent()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Groups that cannot use the routed pass (for example a DBC channel plus an Auto Decode
     * native-only channel) may still share one physical scan when every job would otherwise
     * run through {@link #executeJob} from the beginning of the source.
     */
    private boolean canFanOutSinglePass(List<IndexedJob> group) {
        Set<Integer> channels = new HashSet<>();
        for (IndexedJob indexed : group) {
            ProcessingJob job = indexed.job();
            if (!job.executable() || !channels.add(job.channelId())) {
                return false;
            }
            if (!isNativeOnlyJob(job)
                    && (job.definitionSourceId().isEmpty() || !job.mappingLifecycle().executable())) {
                return false;
            }
            if (context.resumeSupport().cursorFor(job).isPresent()) {
                return false;
            }
        }
        return true;
    }

    /**
     * One streaming source pass with one independent decode state per job. Every job keeps the
     * DBC, selection, native policy, store, statistics, diagnostics and checkpoints it has in
     * {@link #executeJob}. Returns empty when a precondition fails so the caller runs the
     * existing per-job path, which reports that failure exactly as before.
     */
    private Optional<List<IndependentJobResult>> executeFanoutGroup(
            List<ProcessingJob> jobs, Map<String, Integer> nativeOwners) {
        if (context.cancellationToken().isCancellationRequested()) {
            return Optional.empty();
        }
        Instant started = Instant.now();
        Path measurementPath;
        try {
            measurementPath = context.measurementPath(jobs.getFirst().projectSourceId());
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        List<String> definitionIds = new ArrayList<>(jobs.size());
        List<String> definitionNames = new ArrayList<>(jobs.size());
        List<DbcDatabase> databases = new ArrayList<>(jobs.size());
        for (ProcessingJob job : jobs) {
            if (isNativeOnlyJob(job)) {
                definitionIds.add(null);
                definitionNames.add("");
                databases.add(EMPTY_DBC);
                continue;
            }
            String definitionId = job.definitionSourceId().orElse(null);
            Optional<DbcDatabase> database = definitionId == null
                    ? Optional.empty()
                    : context.definitionDatabase(definitionId);
            if (database.isEmpty()) {
                return Optional.empty();
            }
            definitionIds.add(definitionId);
            definitionNames.add(job.definitionDisplayName().isBlank() ? definitionId : job.definitionDisplayName());
            databases.add(database.get());
        }

        List<GraphSignalSessionStore> stores = new ArrayList<>(jobs.size());
        try {
            List<MeasurementDecodePipeline.FanoutRoute> routes = new ArrayList<>(jobs.size());
            for (int i = 0; i < jobs.size(); i++) {
                ProcessingJob job = jobs.get(i);
                GraphSignalSessionStore store = context.createSignalStore(
                        job, context.measurementDisplayName(job), definitionNames.get(i));
                stores.add(store);
                routes.add(new MeasurementDecodePipeline.FanoutRoute(
                        job.channelId(),
                        databases.get(i),
                        store,
                        nativePolicyFor(job, nativeOwners),
                        context.readerContext(job).checkpointListener()));
            }
            Optional<AutomotiveFileReader> injected = context.readerFor(measurementPath);
            List<ProcessingResult> pipelineResults = context.pipeline().processFanout(
                    injected.orElse(null), measurementPath, routes, context.readerContext(),
                    context.progressListener());
            List<IndependentJobResult> results = new ArrayList<>(jobs.size());
            for (int i = 0; i < jobs.size(); i++) {
                results.add(completedJobResult(
                        jobs.get(i), definitionIds.get(i), definitionNames.get(i),
                        pipelineResults.get(i), stores.get(i), new ArrayList<>(), started));
            }
            return Optional.of(results);
        } catch (ParserException ex) {
            finishWritersQuietly(stores);
            return Optional.of(failedGroup(jobs, stores, started, "PARSER_FAILURE", ex));
        } catch (RuntimeException ex) {
            finishWritersQuietly(stores);
            return Optional.of(failedGroup(jobs, stores, started, "EXECUTION_FAILURE", ex));
        }
    }

    private static List<IndependentJobResult> failedGroup(
            List<ProcessingJob> jobs,
            List<GraphSignalSessionStore> stores,
            Instant started,
            String code,
            Exception ex
    ) {
        List<IndependentJobResult> failed = new ArrayList<>(jobs.size());
        for (int i = 0; i < jobs.size(); i++) {
            failed.add(failedJob(jobs.get(i), started, new ArrayList<>(), code,
                    ex.getMessage() == null ? ex.toString() : ex.getMessage(),
                    i < stores.size() ? stores.get(i) : null));
        }
        return failed;
    }

    /**
     * Decode all executable channels from one source in a single streaming source pass. Each
     * channel is routed to its own selected DBC, and each channel owns a separate bounded catalog
     * store; samples are never merged, aligned, normalised, shifted, interpolated, or deduplicated.
     */
    private List<IndependentJobResult> executeSharedGroup(
            List<ProcessingJob> jobs, Map<String, Integer> nativeOwners) {
        Instant started = Instant.now();
        ProcessingJob firstJob = jobs.getFirst();
        List<ProcessingPlanDiagnostic> commonDiagnostics = new ArrayList<>();

        Path measurementPath;
        try {
            measurementPath = context.measurementPath(firstJob.projectSourceId());
        } catch (RuntimeException ex) {
            List<IndependentJobResult> failed = new ArrayList<>(jobs.size());
            for (ProcessingJob job : jobs) {
                failed.add(failedJob(job, started, commonDiagnostics, "SOURCE_NOT_READY",
                        "measurement path unavailable: " + ex.getMessage(), null));
            }
            return failed;
        }

        Map<Integer, DbcDatabase> databasesByChannel = new LinkedHashMap<>();
        Map<Integer, String> definitionIdsByChannel = new LinkedHashMap<>();
        Map<Integer, String> definitionNamesByChannel = new LinkedHashMap<>();
        for (ProcessingJob job : jobs) {
            if (!Objects.equals(job.projectSourceId(), firstJob.projectSourceId())
                    || !job.mappingLifecycle().executable()) {
                return jobs.stream().map(j -> executeJob(j, nativeOwners)).toList();
            }
            String definitionId = job.definitionSourceId().orElse(null);
            if (definitionId == null) {
                return jobs.stream().map(j -> executeJob(j, nativeOwners)).toList();
            }
            Optional<DbcDatabase> database = context.definitionDatabase(definitionId);
            if (database.isEmpty()) {
                return jobs.stream().map(j -> executeJob(j, nativeOwners)).toList();
            }
            if (databasesByChannel.putIfAbsent(job.channelId(), database.get()) != null) {
                // A channel may have only one effective mapping in an immutable plan snapshot.
                return jobs.stream().map(j -> executeJob(j, nativeOwners)).toList();
            }
            definitionIdsByChannel.put(job.channelId(), definitionId);
            definitionNamesByChannel.put(
                    job.channelId(),
                    job.definitionDisplayName().isBlank() ? definitionId : job.definitionDisplayName());
        }

        String measurementName = context.measurementDisplayName(firstJob);
        Map<Integer, GraphSignalSessionStore> storesByChannel = new LinkedHashMap<>();
        for (ProcessingJob job : jobs) {
            storesByChannel.put(job.channelId(), context.createSignalStore(
                    job, measurementName, definitionNamesByChannel.get(job.channelId())));
        }
        Integer nativeOwnerChannel = nativeOwners.get(firstJob.projectSourceId());
        GroupSignalFanout fanout = new GroupSignalFanout(
                storesByChannel,
                nativeOwnerChannel == null ? storesByChannel.keySet().iterator().next() : nativeOwnerChannel);

        try {
            MeasurementDecodePipeline.RoutedProcessingResult routedResult;
            Optional<AutomotiveFileReader> injected = context.readerFor(measurementPath);
            if (injected.isPresent()) {
                routedResult = context.pipeline().processRouted(
                        injected.get(), measurementPath, databasesByChannel, context.readerContext(), fanout,
                        context.progressListener());
            } else {
                routedResult = context.pipeline().processRouted(
                        measurementPath, databasesByChannel, context.readerContext(), fanout,
                        context.progressListener());
            }

            ProcessingResult pipelineResult = routedResult.processingResult();
            ProcessingStatistics stats = pipelineResult.statistics();
            Duration elapsed = Duration.between(started, Instant.now());
            List<IndependentJobResult> results = new ArrayList<>(jobs.size());

            for (ProcessingJob job : jobs) {
                List<ProcessingPlanDiagnostic> diagnostics = new ArrayList<>();
                MeasurementDecodePipeline.RouteStatistics route = routedResult.route(job.channelId());
                if (job.frameCount() > 0L && route.selectedFrames() != job.frameCount()) {
                    diagnostics.add(new ProcessingPlanDiagnostic(
                            ProcessingPlanDiagnosticCode.FRAME_COUNT_MISMATCH,
                            job.channel(),
                            "routedSelectedFrames=" + route.selectedFrames()
                                    + " expectedInventoryFrames=" + job.frameCount()));
                }
                appendPipelineDiagnostics(diagnostics, pipelineResult, job.channel());
                GraphSignalSessionStore store = storesByChannel.get(job.channelId());
                if (store != null) {
                    store.finishWriteSession();
                }
                SampleBounds bounds = sampleBounds(store);
                JobExecutionStatus status = mapStatus(pipelineResult, !diagnostics.isEmpty());
                results.add(new IndependentJobResult(
                        job.key(),
                        definitionIdsByChannel.get(job.channelId()),
                        definitionNamesByChannel.get(job.channelId()),
                        job.mappingLifecycle(),
                        status,
                        job.frameCount(),
                        stats.sourceFramesRead(),
                        route.selectedFrames(),
                        route.dbcMatchedFrames(),
                        route.dbcUnmatchedFrames(),
                        route.signalsEmitted(),
                        bounds.first,
                        bounds.last,
                        job.observedTiming().orElse(null),
                        elapsed,
                        diagnostics,
                        pipelineTechnicalDetail(pipelineResult, "shared source routed pass"),
                        store
                ));
            }
            return results;
        } catch (ParserException ex) {
            finishWritersQuietly(storesByChannel.values());
            List<IndependentJobResult> failed = new ArrayList<>(jobs.size());
            for (ProcessingJob job : jobs) {
                failed.add(failedJob(job, started, commonDiagnostics, "PARSER_FAILURE",
                        ex.getMessage() == null ? ex.toString() : ex.getMessage(),
                        storesByChannel.get(job.channelId())));
            }
            return failed;
        } catch (RuntimeException ex) {
            finishWritersQuietly(storesByChannel.values());
            List<IndependentJobResult> failed = new ArrayList<>(jobs.size());
            for (ProcessingJob job : jobs) {
                failed.add(failedJob(job, started, commonDiagnostics, "EXECUTION_FAILURE",
                        ex.getMessage() == null ? ex.toString() : ex.getMessage(),
                        storesByChannel.get(job.channelId())));
            }
            return failed;
        }
    }

    private IndependentJobResult executeJob(ProcessingJob job, Map<String, Integer> nativeOwners) {
        Instant started = Instant.now();
        List<ProcessingPlanDiagnostic> diagnostics = new ArrayList<>();
        Path measurementPath;
        try {
            measurementPath = context.measurementPath(job.projectSourceId());
        } catch (RuntimeException ex) {
            return failedJob(job, started, diagnostics, "SOURCE_NOT_READY",
                    "measurement path unavailable: " + ex.getMessage(), null);
        }

        String definitionId = job.definitionSourceId().orElse(null);
        boolean nativeOnly = isNativeOnlyJob(job);
        DbcDatabase database;
        String definitionName;
        if (nativeOnly) {
            database = EMPTY_DBC;
            definitionName = "";
            definitionId = null;
        } else {
            if (definitionId == null) {
                diagnostics.add(ProcessingPlanDiagnostic.of(
                        ProcessingPlanDiagnosticCode.NO_EFFECTIVE_DEFINITION, job.channel(), "missing definition id"));
                return failedJob(job, started, diagnostics, "NO_EFFECTIVE_DEFINITION",
                        "job has no DefinitionSourceId", null);
            }

            Optional<DbcDatabase> databaseOpt = context.definitionDatabase(definitionId);
            if (databaseOpt.isEmpty()) {
                diagnostics.add(ProcessingPlanDiagnostic.of(
                        ProcessingPlanDiagnosticCode.DEFINITION_NOT_AVAILABLE, job.channel(), definitionId));
                return failedJob(job, started, diagnostics, "DEFINITION_NOT_AVAILABLE",
                        "parsed DBC unavailable for definition " + definitionId, null);
            }
            database = databaseOpt.get();

            // Never auto-confirm: mapping lifecycle is preserved from the plan snapshot.
            MappingLifecycle lifecycle = job.mappingLifecycle();
            if (!lifecycle.executable()) {
                diagnostics.add(ProcessingPlanDiagnostic.of(
                        ProcessingPlanDiagnosticCode.UNCONFIRMED_DEFINITION, job.channel(), lifecycle.name()));
                return blockedJob(job, "mapping lifecycle not executable: " + lifecycle);
            }
            definitionName = job.definitionDisplayName().isBlank()
                    ? definitionId
                    : job.definitionDisplayName();
        }

        ProcessingSelection selection = ProcessingSelection.forSource(
                ProcessingSelection.sourceIdentity(measurementPath),
                List.of(job.channelId())
        );
        NativeMeasurementPolicy nativePolicy = nativePolicyFor(job, nativeOwners);

        String measurementName = context.measurementDisplayName(job);
        GraphSignalSessionStore catalogStore = null;
        try {
            catalogStore = context.createSignalStore(job, measurementName, definitionName);
            BatchSignalConsumer consumer = catalogStore;
            ReaderContext readerContext = context.readerContext(job);
            ProcessingResult pipelineResult;
            Optional<AutomotiveFileReader> injected = context.readerFor(measurementPath);
            if (injected.isPresent()) {
                pipelineResult = context.pipeline().process(
                        injected.get(),
                        measurementPath,
                        database,
                        readerContext,
                        consumer,
                        context.progressListener(),
                        selection,
                        nativePolicy
                );
            } else {
                pipelineResult = context.pipeline().process(
                        measurementPath,
                        database,
                        readerContext,
                        consumer,
                        context.progressListener(),
                        selection,
                        nativePolicy
                );
            }

            return completedJobResult(
                    job, definitionId, definitionName, pipelineResult, catalogStore, diagnostics, started);
        } catch (ParserException ex) {
            finishWriterQuietly(catalogStore);
            return failedJob(job, started, diagnostics, "PARSER_FAILURE",
                    ex.getMessage() == null ? ex.toString() : ex.getMessage(), catalogStore);
        } catch (RuntimeException ex) {
            finishWriterQuietly(catalogStore);
            return failedJob(job, started, diagnostics, "EXECUTION_FAILURE",
                    ex.getMessage() == null ? ex.toString() : ex.getMessage(), catalogStore);
        }
    }

    private static IndependentJobResult completedJobResult(
            ProcessingJob job,
            String definitionId,
            String definitionName,
            ProcessingResult pipelineResult,
            GraphSignalSessionStore catalogStore,
            List<ProcessingPlanDiagnostic> diagnostics,
            Instant started
    ) {
        ProcessingStatistics stats = pipelineResult.statistics();
        if (job.frameCount() > 0L
                && stats.framesSelectedForDecode() != job.frameCount()) {
            diagnostics.add(new ProcessingPlanDiagnostic(
                    ProcessingPlanDiagnosticCode.FRAME_COUNT_MISMATCH,
                    job.channel(),
                    "selectedChannelFrames=" + stats.framesSelectedForDecode()
                            + " expectedInventoryFrames=" + job.frameCount()
            ));
        }
        appendPipelineDiagnostics(diagnostics, pipelineResult, job.channel());

        SampleBounds bounds = sampleBounds(catalogStore);
        JobExecutionStatus status = mapStatus(pipelineResult, !diagnostics.isEmpty());
        catalogStore.finishWriteSession();
        return new IndependentJobResult(
                job.key(),
                definitionId,
                definitionName,
                job.mappingLifecycle(),
                status,
                job.frameCount(),
                stats.sourceFramesRead(),
                stats.framesSelectedForDecode(),
                stats.dbcMatchedFrames(),
                stats.dbcUnmatchedFrames(),
                stats.signalsEmitted(),
                bounds.first,
                bounds.last,
                job.observedTiming().orElse(null),
                Duration.between(started, Instant.now()),
                diagnostics,
                pipelineTechnicalDetail(pipelineResult, ""),
                catalogStore
        );
    }

    private static JobExecutionStatus mapStatus(ProcessingResult result, boolean extraWarnings) {
        if (result.cancelled()) {
            return JobExecutionStatus.CANCELLED;
        }
        return switch (result.outcome()) {
            case COMPLETED -> extraWarnings || !result.diagnostics().isEmpty()
                    ? JobExecutionStatus.SUCCESS_WITH_WARNINGS
                    : JobExecutionStatus.SUCCESS;
            case COMPLETED_WITH_WARNINGS -> JobExecutionStatus.SUCCESS_WITH_WARNINGS;
            case CANCELLED -> JobExecutionStatus.CANCELLED;
            case NO_CAN_DATA, RECOVERY_FAILED, UNSUPPORTED_STRUCTURE, FAILED -> JobExecutionStatus.FAILED;
        };
    }

    private static void appendPipelineDiagnostics(
            List<ProcessingPlanDiagnostic> diagnostics,
            ProcessingResult pipelineResult,
            SourceChannelRef channel
    ) {
        if (diagnostics == null || pipelineResult == null) {
            return;
        }
        for (ProcessingDiagnostic diagnostic : pipelineResult.diagnostics()) {
            String detail = diagnostic.code() + ": " + diagnostic.message();
            diagnostics.add(ProcessingPlanDiagnostic.of(
                    ProcessingPlanDiagnosticCode.PIPELINE_DIAGNOSTIC,
                    channel,
                    detail));
        }
    }

    private static String pipelineTechnicalDetail(ProcessingResult pipelineResult, String fallback) {
        if (pipelineResult == null || pipelineResult.diagnostics().isEmpty()) {
            return fallback == null ? "" : fallback;
        }
        StringBuilder sb = new StringBuilder();
        for (ProcessingDiagnostic diagnostic : pipelineResult.diagnostics()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(diagnostic.layer()).append('/').append(diagnostic.code())
                    .append(": ").append(diagnostic.message());
        }
        if (fallback != null && !fallback.isBlank()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(fallback);
        }
        return sb.toString();
    }

    private static IndependentJobResult blockedJob(ProcessingJob job, String detail) {
        List<ProcessingPlanDiagnostic> diagnostics = new ArrayList<>(job.diagnostics());
        diagnostics.add(ProcessingPlanDiagnostic.of(
                ProcessingPlanDiagnosticCode.NO_EFFECTIVE_DEFINITION, job.channel(), detail));
        return new IndependentJobResult(
                job.key(),
                job.definitionSourceId().orElse(null),
                job.definitionDisplayName(),
                job.mappingLifecycle(),
                JobExecutionStatus.BLOCKED,
                job.frameCount(),
                0, 0, 0, 0, 0,
                null, null,
                job.observedTiming().orElse(null),
                Duration.ZERO,
                diagnostics,
                detail,
                null
        );
    }

    private static IndependentJobResult cancelledJob(ProcessingJob job, String detail) {
        return new IndependentJobResult(
                job.key(),
                job.definitionSourceId().orElse(null),
                job.definitionDisplayName(),
                job.mappingLifecycle(),
                JobExecutionStatus.CANCELLED,
                job.frameCount(),
                0, 0, 0, 0, 0,
                null, null,
                job.observedTiming().orElse(null),
                Duration.ZERO,
                List.of(),
                detail,
                null
        );
    }

    private static IndependentJobResult failedJob(
            ProcessingJob job,
            Instant started,
            List<ProcessingPlanDiagnostic> diagnostics,
            String code,
            String detail,
            com.stellar.platform.processing.catalog.SignalSeriesStore catalog
    ) {
        List<ProcessingPlanDiagnostic> diags = new ArrayList<>(diagnostics == null ? List.of() : diagnostics);
        diags.add(new ProcessingPlanDiagnostic(
                ProcessingPlanDiagnosticCode.SOURCE_NOT_READY, job.channel(), code + ": " + detail));
        return new IndependentJobResult(
                job.key(),
                job.definitionSourceId().orElse(null),
                job.definitionDisplayName(),
                job.mappingLifecycle(),
                JobExecutionStatus.FAILED,
                job.frameCount(),
                0, 0, 0, 0, 0,
                null, null,
                job.observedTiming().orElse(null),
                Duration.between(started, Instant.now()),
                diags,
                detail == null ? "" : detail,
                catalog
        );
    }

    private static ProjectExecutionStatus classify(List<IndependentJobResult> results, boolean cancelled) {
        if (results.isEmpty()) {
            return cancelled ? ProjectExecutionStatus.CANCELLED : ProjectExecutionStatus.FAILED;
        }
        int success = 0;
        int failed = 0;
        int blocked = 0;
        for (IndependentJobResult r : results) {
            if (r.successful()) {
                success++;
            } else if (r.status() == JobExecutionStatus.BLOCKED) {
                blocked++;
            } else {
                failed++;
            }
        }
        if (success > 0 && (failed > 0 || blocked > 0)) {
            return ProjectExecutionStatus.PARTIAL_FAILURE;
        }
        if (success > 0) {
            return ProjectExecutionStatus.SUCCESS;
        }
        if (cancelled) {
            return ProjectExecutionStatus.CANCELLED;
        }
        if (blocked > 0 && failed == 0) {
            return ProjectExecutionStatus.BLOCKED;
        }
        return ProjectExecutionStatus.FAILED;
    }

    /**
     * Derive first/last timestamps from catalog metadata accumulated during streaming decode.
     * This is O(unique signals), does not copy retained sample windows, and remains correct when
     * the bounded sample ring has evicted early data. Never invents 0 and never offsets jobs.
     */
    private static SampleBounds sampleBounds(com.stellar.platform.processing.catalog.SignalSeriesStore store) {
        SignalCatalog catalog = store.snapshot();
        Long first = null;
        Long last = null;
        for (SignalCatalogEntry entry : catalog.entries()) {
            Long entryFirst = entry.firstTimestampNanos();
            Long entryLast = entry.lastTimestampNanos();
            if (entryFirst != null && (first == null || entryFirst < first)) {
                first = entryFirst;
            }
            if (entryLast != null && (last == null || entryLast > last)) {
                last = entryLast;
            }
        }
        return new SampleBounds(first, last);
    }

    private static void finishWriterQuietly(GraphSignalSessionStore store) {
        if (store == null) {
            return;
        }
        try {
            store.finishWriteSession();
        } catch (RuntimeException ignored) {
            // Failure paths must still release open series streams.
        }
    }

    private static void finishWritersQuietly(Iterable<GraphSignalSessionStore> stores) {
        if (stores == null) {
            return;
        }
        for (GraphSignalSessionStore store : stores) {
            finishWriterQuietly(store);
        }
    }

    private static final class GroupSignalFanout implements BatchSignalConsumer {
        private final Map<Integer, GraphSignalSessionStore> storesByChannel;
        private final Map<Integer, List<com.stellar.platform.domain.CanonicalSignal>> buffersByChannel;
        private final Map<Integer, Long> signalCounts = new LinkedHashMap<>();
        private final int nativeOwnerChannel;

        private GroupSignalFanout(
                Map<Integer, GraphSignalSessionStore> storesByChannel, int nativeOwnerChannel) {
            this.storesByChannel = Map.copyOf(storesByChannel);
            this.buffersByChannel = new LinkedHashMap<>();
            this.nativeOwnerChannel = nativeOwnerChannel;
            for (Integer channelId : storesByChannel.keySet()) {
                buffersByChannel.put(channelId, new ArrayList<>());
                signalCounts.put(channelId, 0L);
            }
        }

        @Override
        public void accept(List<com.stellar.platform.domain.CanonicalSignal> batch) {
            if (batch == null || batch.isEmpty()) {
                return;
            }
            for (com.stellar.platform.domain.CanonicalSignal signal : batch) {
                // Native channels carry a channel-group record id, so they are routed by ownership
                // rather than by CAN bus number.
                int destination = InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE.equals(signal.messageName())
                        ? nativeOwnerChannel
                        : signal.channel();
                List<com.stellar.platform.domain.CanonicalSignal> buffer = buffersByChannel.get(destination);
                if (buffer != null) {
                    buffer.add(signal);
                }
            }
            for (Map.Entry<Integer, List<com.stellar.platform.domain.CanonicalSignal>> entry : buffersByChannel.entrySet()) {
                List<com.stellar.platform.domain.CanonicalSignal> channelBatch = entry.getValue();
                if (channelBatch.isEmpty()) {
                    continue;
                }
                storesByChannel.get(entry.getKey()).accept(channelBatch);
                signalCounts.merge(entry.getKey(), (long) channelBatch.size(), Long::sum);
                channelBatch.clear();
            }
        }

        private long signalsFor(int channelId) {
            return signalCounts.getOrDefault(channelId, 0L);
        }
    }

    private record ExecutionGroupKey(String projectSourceId, String definitionSourceId, String isolationKey) {
    }

    private record IndexedJob(int index, ProcessingJob job) {
    }

    private record SampleBounds(Long first, Long last) {
    }
}
