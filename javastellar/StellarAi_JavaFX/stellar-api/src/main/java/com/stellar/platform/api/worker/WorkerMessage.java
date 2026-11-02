package com.stellar.platform.api.worker;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WorkerMessage(
        String type,
        Integer protocolVersion,
        String token,
        Long pid,
        String taskId,
        ProcessingTaskDescriptor task,
        Double progress,
        String stage,
        Long batchSequence,
        String message,
        String classification,
        Long heapUsedBytes,
        Long heapMaxBytes,
        Integer availableProcessors,
        Integer activeJobs,
        Integer queueDepth,
        Long uptimeMillis,
        String lastCheckpoint,
        Boolean alive
) {
    public static WorkerMessage ofType(String type) {
        return new WorkerMessage(
                type, WorkerProtocol.VERSION, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    public WorkerMessage withToken(String value) {
        return new WorkerMessage(
                type, protocolVersion, value, pid, taskId, task, progress, stage, batchSequence,
                message, classification, heapUsedBytes, heapMaxBytes, availableProcessors,
                activeJobs, queueDepth, uptimeMillis, lastCheckpoint, alive);
    }

    public WorkerMessage withTask(ProcessingTaskDescriptor descriptor) {
        return new WorkerMessage(
                type, protocolVersion, token, pid, descriptor == null ? taskId : descriptor.taskId(),
                descriptor, progress, stage, batchSequence, message, classification,
                heapUsedBytes, heapMaxBytes, availableProcessors, activeJobs, queueDepth,
                uptimeMillis, lastCheckpoint, alive);
    }

    public WorkerMessage withTaskId(String id) {
        return new WorkerMessage(
                type, protocolVersion, token, pid, id, task, progress, stage, batchSequence,
                message, classification, heapUsedBytes, heapMaxBytes, availableProcessors,
                activeJobs, queueDepth, uptimeMillis, lastCheckpoint, alive);
    }
}
