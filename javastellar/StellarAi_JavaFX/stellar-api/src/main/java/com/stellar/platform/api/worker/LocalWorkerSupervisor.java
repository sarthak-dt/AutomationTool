package com.stellar.platform.api.worker;

import com.stellar.platform.domain.processing.ProcessingFailureClass;
import com.stellar.platform.domain.processing.plan.JobExecutionStatus;
import com.stellar.platform.storage.metadata.AuditEventMetadata;
import com.stellar.platform.storage.metadata.AuditEventType;
import com.stellar.platform.storage.metadata.ProcessingErrorMetadata;
import com.stellar.platform.storage.metadata.ProcessingJobMetadata;
import com.stellar.platform.storage.metadata.ProjectMetadataStore;

import java.time.Instant;
import java.util.Objects;

/**
 * Desktop-side supervisor for the isolated worker. Detects worker death and
 * transitions durable RUNNING jobs to INTERRUPTED. Never falls back to in-process decode.
 */
public final class LocalWorkerSupervisor {

    private final ProjectMetadataStore store;

    public LocalWorkerSupervisor(ProjectMetadataStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public int markRunningJobsInterrupted(String projectId, String reason) {
        if (projectId == null || projectId.isBlank()) {
            return 0;
        }
        Instant now = Instant.now();
        String message = reason == null || reason.isBlank() ? "Processing interrupted" : reason;
        int marked = 0;
        for (ProcessingJobMetadata job : store.listUnfinishedProcessingJobs(projectId)) {
            if (job.status() != JobExecutionStatus.RUNNING && job.status() != JobExecutionStatus.RETRY_WAIT) {
                continue;
            }
            store.upsertProcessingJob(new ProcessingJobMetadata(
                    job.jobId(), job.projectId(), job.runId(), job.sourceId(), job.channelId(),
                    job.definitionId(), JobExecutionStatus.INTERRUPTED, job.attempt(), job.maxAttempts(),
                    job.progress(), job.stage(), job.workerId(), job.createdAt(), job.startedAt(),
                    now, ProcessingFailureClass.WORKER_CRASH, message));
            store.appendProcessingError(ProcessingErrorMetadata.of(
                    projectId, job.runId(), job.jobId(), job.sourceId(),
                    ProcessingFailureClass.WORKER_CRASH,
                    "Processing interrupted — recovery available from checkpoint",
                    message));
            store.appendAuditEvent(AuditEventMetadata.of(
                    projectId, AuditEventType.WORKER_CRASHED, job.jobId(), message));
            store.appendAuditEvent(AuditEventMetadata.of(
                    projectId, AuditEventType.PROCESSING_INTERRUPTED, job.jobId(), message));
            marked++;
        }
        return marked;
    }

    public ProcessIsolatedLocalWorker startOrRestart(ProcessIsolatedLocalWorker previous) {
        if (previous != null) {
            try {
                previous.shutdown();
            } catch (RuntimeException ignored) {
            }
        }
        return ProcessIsolatedLocalWorker.create(WorkerClasspath.resolve());
    }
}
