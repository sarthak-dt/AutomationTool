package com.stellar.platform.api.support;

import com.stellar.platform.api.dto.JobDto;
import com.stellar.platform.api.dto.JobStatusDto;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory async job ledger shared by LOCAL adapters and the server. */
public final class InMemoryJobTracker {

    private final Map<String, JobDto> jobs = new ConcurrentHashMap<>();

    public JobDto create(String projectId, String kind) {
        Objects.requireNonNull(projectId, "projectId");
        Instant now = Instant.now();
        JobDto job = new JobDto(
                UUID.randomUUID().toString(),
                projectId,
                kind,
                JobStatusDto.QUEUED,
                "",
                now,
                now,
                0.0
        );
        jobs.put(key(projectId, job.jobId()), job);
        return job;
    }

    public Optional<JobDto> get(String projectId, String jobId) {
        return Optional.ofNullable(jobs.get(key(projectId, jobId)));
    }

    public JobDto update(String projectId, String jobId, JobStatusDto status, String message, Double progress) {
        String k = key(projectId, jobId);
        JobDto current = jobs.get(k);
        if (current == null) {
            throw new IllegalArgumentException("Unknown job: " + jobId);
        }
        JobDto next = new JobDto(
                current.jobId(),
                current.projectId(),
                current.kind(),
                status,
                message == null ? current.message() : message,
                current.createdAt(),
                Instant.now(),
                progress == null ? current.progressFraction() : progress
        );
        jobs.put(k, next);
        return next;
    }

    /**
     * Marks any in-flight QUEUED/RUNNING jobs as FAILED after process/application restart.
     * Completed jobs are durable via artifact metadata, not this in-memory ledger.
     */
    public int failRunning(String message) {
        String detail = message == null || message.isBlank()
                ? "Interrupted by application restart"
                : message;
        int count = 0;
        for (Map.Entry<String, JobDto> entry : jobs.entrySet()) {
            JobDto current = entry.getValue();
            if (current.status() != JobStatusDto.QUEUED && current.status() != JobStatusDto.RUNNING) {
                continue;
            }
            jobs.put(entry.getKey(), new JobDto(
                    current.jobId(),
                    current.projectId(),
                    current.kind(),
                    JobStatusDto.FAILED,
                    detail,
                    current.createdAt(),
                    Instant.now(),
                    current.progressFraction()
            ));
            count++;
        }
        return count;
    }

    private static String key(String projectId, String jobId) {
        return projectId + "::" + jobId;
    }
}
