package com.stellar.platform.api.worker;

import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.parser.spi.ParseCheckpointListener;
import com.stellar.platform.parser.spi.ParserResumeCursor;
import com.stellar.platform.processing.execution.JobResumeSupport;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;
import com.stellar.platform.processing.graph.LosslessGraphSignalStore;
import com.stellar.platform.storage.ArtifactStorageRefs;
import com.stellar.platform.storage.metadata.AuditEventMetadata;
import com.stellar.platform.storage.metadata.AuditEventType;
import com.stellar.platform.storage.metadata.DurableCheckpointManager;
import com.stellar.platform.storage.metadata.FileFingerprint;
import com.stellar.platform.storage.metadata.ProcessingBatchMetadata;
import com.stellar.platform.storage.metadata.ProcessingBatchStatus;
import com.stellar.platform.storage.metadata.ProcessingCheckpointMetadata;
import com.stellar.platform.storage.metadata.ProcessingJobMetadata;
import com.stellar.platform.storage.metadata.ProcessingPlanFingerprint;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker-owned MF4 mid-file resume. Storage sees only opaque cursor payloads.
 */
final class MidFileJobResume {

    record Decision(
            boolean resume,
            ParserResumeCursor cursor,
            ProcessingCheckpointMetadata checkpoint,
            long ledgerSamples,
            String stageMessage
    ) {
        static Decision fromStart() {
            return new Decision(false, null, null, 0L, "Processing source from beginning");
        }
    }

    private final ProcessingTaskDescriptor task;
    private final ProjectMetadataStore store;
    private final Map<String, Path> measurements;
    private final Map<String, Path> definitions;
    private final LocalPlanWorkerEngine.EventSink sink;
    private final Map<String, Decision> byJobId = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> nextBatch = new ConcurrentHashMap<>();
    private final Map<String, GraphSignalSessionStore> sessions = new ConcurrentHashMap<>();
    private final AtomicInteger committedCheckpoints = new AtomicInteger();

    MidFileJobResume(
            ProcessingTaskDescriptor task,
            ProjectMetadataStore store,
            Map<String, Path> measurements,
            Map<String, Path> definitions,
            LocalPlanWorkerEngine.EventSink sink
    ) {
        this.task = task;
        this.store = store;
        this.measurements = measurements;
        this.definitions = definitions;
        this.sink = sink == null ? ignored -> { } : sink;
    }

    Decision prepare(ProcessingJob job) {
        Decision decision = resolve(job);
        byJobId.put(job.key().toString(), decision);
        long seq = 0L;
        if (store != null) {
            seq = store.findLastCommittedBatch(job.key().toString())
                    .map(ProcessingBatchMetadata::sequence)
                    .orElse(0L);
        }
        if (decision.checkpoint() != null) {
            seq = Math.max(seq, decision.checkpoint().batchSequence());
        }
        nextBatch.put(job.key().toString(), new AtomicLong(seq));
        return decision;
    }

    Decision decision(ProcessingJob job) {
        return byJobId.getOrDefault(job.key().toString(), Decision.fromStart());
    }

    void rememberSession(ProcessingJob job, GraphSignalSessionStore session) {
        sessions.put(job.key().toString(), session);
    }

    JobResumeSupport support() {
        return new JobResumeSupport() {
            @Override
            public Optional<ParserResumeCursor> cursorFor(ProcessingJob job) {
                Decision decision = decision(job);
                return Optional.ofNullable(decision.cursor());
            }

            @Override
            public ParseCheckpointListener listenerFor(ProcessingJob job) {
                if (store == null || job.sourceFormat() != MeasurementSourceFormat.MF4) {
                    return ParseCheckpointListener.NONE;
                }
                return (cursor, recordsConsumed, lastTs) -> persistMidFile(job, cursor, recordsConsumed, lastTs);
            }
        };
    }

    private Decision resolve(ProcessingJob job) {
        if (task.forceReprocess() || store == null || job.sourceFormat() != MeasurementSourceFormat.MF4) {
            return Decision.fromStart();
        }
        String jobId = job.key().toString();
        Optional<ProcessingJobMetadata> meta = store.findProcessingJob(jobId);
        if (meta.isPresent() && meta.get().status() == JobExecutionStatus.CANCELLED) {
            return Decision.fromStart();
        }
        Path measurement = measurements.get(job.projectSourceId());
        String definitionId = job.definitionSourceId().orElse("");
        Path definition = definitionId.isBlank() ? null : definitions.get(definitionId);
        String sourceFp = FileFingerprint.of(measurement);
        String dbcFp = FileFingerprint.of(definition);
        String planFp = ProcessingPlanFingerprint.forJob(
                job.projectSourceId(), job.channelId(), definitionId,
                job.mappingLifecycle().name(), job.sourceFormat().name());
        DurableCheckpointManager manager = new DurableCheckpointManager(store);
        Optional<ProcessingCheckpointMetadata> found = manager.resumableCheckpoint(
                jobId, sourceFp, dbcFp, planFp, DurableCheckpointManager.ARTIFACT_VERSION);
        if (found.isEmpty()) {
            return Decision.fromStart();
        }
        ProcessingCheckpointMetadata checkpoint = found.get();
        Optional<ParserResumeCursor> cursor = ParserResumeCursor.deserialize(checkpoint.cursorPayload());
        if (cursor.isEmpty() || !cursor.get().isMf4V1()) {
            invalidate(jobId, "corrupt or unsupported resume cursor");
            return Decision.fromStart();
        }
        Path finalRoot = ArtifactStorageRefs.jobRoot(
                task.projectId(), job.projectSourceId(), job.channelId(), definitionId);
        if (GraphSignalSessionStore.hasIncompleteFlag(finalRoot) && !LosslessGraphSignalStore.hasValidIndex(finalRoot)) {
            invalidate(jobId, "incomplete artifact has no index");
            return Decision.fromStart();
        }
        Optional<ProcessingBatchMetadata> lastBatch = store.findLastCommittedBatch(jobId);
        long ledgerSamples = lastBatch.map(ProcessingBatchMetadata::samplesProduced).orElse(0L);
        Path index = LosslessGraphSignalStore.indexMatchingSampleCount(finalRoot, ledgerSamples);
        if (index == null || !LosslessGraphSignalStore.segmentsReadable(finalRoot)) {
            invalidate(jobId, "artifact index does not match committed batch");
            return Decision.fromStart();
        }
        try {
            GraphSignalSessionStore.openExistingMatchingSamples(finalRoot, ledgerSamples).close();
        } catch (RuntimeException ex) {
            invalidate(jobId, "cannot reopen artifact for resume: " + ex.getMessage());
            return Decision.fromStart();
        }
        String message = "Resuming from committed batch " + checkpoint.batchSequence();
        store.appendAuditEvent(AuditEventMetadata.of(
                task.projectId(), AuditEventType.PROCESSING_RESUMED, jobId, message));
        return new Decision(true, cursor.get(), checkpoint, ledgerSamples, message);
    }

    private void persistMidFile(
            ProcessingJob job,
            ParserResumeCursor cursor,
            long recordsConsumed,
            Long lastTs
    ) {
        String jobId = job.key().toString();
        GraphSignalSessionStore session = sessions.get(jobId);
        if (store == null || session == null || cursor == null || !cursor.isMf4V1()) {
            return;
        }
        long sequence = nextBatch.computeIfAbsent(jobId, id -> new AtomicLong()).incrementAndGet();
        long samples = session.losslessStore().totalSampleCount();
        String artifactRef = ArtifactStorageRefs.forJob(
                task.projectId(), job.projectSourceId(), job.channelId(),
                job.definitionSourceId().orElse(""));
        Path measurement = measurements.get(job.projectSourceId());
        Path definition = job.definitionSourceId().map(definitions::get).orElse(null);
        ProcessingBatchMetadata batch = new ProcessingBatchMetadata(
                null, task.projectId(), jobId, sequence,
                0L, recordsConsumed, recordsConsumed, samples,
                ProcessingBatchStatus.COMMITTED, artifactRef, Instant.now(), Instant.now());
        new DurableCheckpointManager(store).commitBatch(batch, DurableCheckpointManager.resumable(
                task.projectId(), jobId, job.projectSourceId(),
                FileFingerprint.of(measurement),
                FileFingerprint.of(definition),
                ProcessingPlanFingerprint.forJob(
                        job.projectSourceId(), job.channelId(), job.definitionSourceId().orElse(""),
                        job.mappingLifecycle().name(), job.sourceFormat().name()),
                recordsConsumed, sequence, lastTs, cursor.serialize(), artifactRef));
        session.promoteCommittedIndex();
        // Per-batch checkpoint identity stays on the processing ledger / worker
        // CHECKPOINT_COMMITTED events. User audit must not grow with batch count.
        sink.emit(WorkerMessage.ofType(WorkerProtocol.CHECKPOINT_COMMITTED).withTaskId(task.taskId()));
        sink.emit(new WorkerMessage(
                WorkerProtocol.PROGRESS, WorkerProtocol.VERSION, null, ProcessHandle.current().pid(),
                task.taskId(), null, 0d, "Resuming processing… committed batch " + sequence,
                null, null, null, null, null, null, null, null, null, null, true));
        int n = committedCheckpoints.incrementAndGet();
        if (task.interruptAfterCheckpoints() > 0 && n >= task.interruptAfterCheckpoints()) {
            throw new IllegalStateException("test interrupt after checkpoint " + sequence);
        }
    }

    void invalidate(String jobId, String reason) {
        if (store == null || jobId == null) {
            return;
        }
        new DurableCheckpointManager(store).invalidate(jobId);
        store.appendAuditEvent(AuditEventMetadata.of(
                task.projectId(), AuditEventType.CHECKPOINT_INVALIDATED, jobId, reason));
    }

    void markCompleted(ProcessingJob job) {
        if (store == null || job == null) {
            return;
        }
        new DurableCheckpointManager(store).markCompleted(job.key().toString());
    }

    void discardOnUserCancel(Iterable<ProcessingJob> jobs) {
        if (store == null || jobs == null) {
            return;
        }
        for (ProcessingJob job : jobs) {
            invalidate(job.key().toString(), "user cancel discards resumability");
        }
    }

    static void writeIncompleteFlag(Path root) {
        try {
            Files.createDirectories(root);
            Files.writeString(root.resolve(GraphSignalSessionStore.INCOMPLETE_FLAG), "1");
        } catch (Exception ignored) {
        }
    }

    static void clearIncompleteFlag(Path root) {
        if (root == null) {
            return;
        }
        try {
            Files.deleteIfExists(root.resolve(GraphSignalSessionStore.INCOMPLETE_FLAG));
        } catch (Exception ignored) {
        }
    }
}
