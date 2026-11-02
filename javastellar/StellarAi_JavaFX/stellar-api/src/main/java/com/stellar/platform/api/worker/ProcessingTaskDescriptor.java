package com.stellar.platform.api.worker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed work that can cross the desktop ↔ worker JVM boundary.
 * Never contains Runnables, controllers, catalogs or sample arrays.
 */
public record ProcessingTaskDescriptor(
        String taskId,
        String projectId,
        String runId,
        Kind kind,
        long sleepMillis,
        String projectDbPath,
        String analysisStorageRoot,
        String artifactRoot,
        List<String> sourcePaths,
        List<String> definitionPaths,
        long resumeAfterRecords,
        String checkpointCursor,
        String planFingerprint,
        String sourceFingerprint,
        String dbcFingerprint,
        String artifactVersion,
        boolean forceReprocess,
        int protocolVersion,
        String planJson,
        Map<String, String> sourcePathById,
        Map<String, String> definitionPathById,
        int interruptAfterCheckpoints,
        int processingBatchSize
) {
    public enum Kind {
        /** Handshake / crash-detection fixture. Does no decode. */
        NOOP,
        /** Blocks for {@code sleepMillis} so the parent can observe liveness. */
        SLEEP,
        /** Reconstructs processing from project metadata + file paths. */
        PLAN
    }

    public ProcessingTaskDescriptor {
        taskId = taskId == null || taskId.isBlank() ? UUID.randomUUID().toString() : taskId;
        projectId = projectId == null ? "" : projectId;
        runId = runId == null ? "" : runId;
        kind = kind == null ? Kind.NOOP : kind;
        sleepMillis = Math.max(0L, sleepMillis);
        projectDbPath = projectDbPath == null ? "" : projectDbPath;
        analysisStorageRoot = analysisStorageRoot == null ? "" : analysisStorageRoot;
        artifactRoot = artifactRoot == null ? "" : artifactRoot;
        sourcePaths = sourcePaths == null ? List.of() : List.copyOf(sourcePaths);
        definitionPaths = definitionPaths == null ? List.of() : List.copyOf(definitionPaths);
        resumeAfterRecords = Math.max(0L, resumeAfterRecords);
        checkpointCursor = checkpointCursor == null ? "" : checkpointCursor;
        planFingerprint = planFingerprint == null ? "" : planFingerprint;
        sourceFingerprint = sourceFingerprint == null ? "" : sourceFingerprint;
        dbcFingerprint = dbcFingerprint == null ? "" : dbcFingerprint;
        artifactVersion = artifactVersion == null || artifactVersion.isBlank() ? "1" : artifactVersion;
        protocolVersion = protocolVersion <= 0 ? WorkerProtocol.VERSION : protocolVersion;
        planJson = planJson == null ? "" : planJson;
        sourcePathById = copyMap(sourcePathById);
        definitionPathById = copyMap(definitionPathById);
        interruptAfterCheckpoints = Math.max(0, interruptAfterCheckpoints);
        processingBatchSize = Math.max(0, processingBatchSize);
    }

    public static ProcessingTaskDescriptor noop() {
        return new ProcessingTaskDescriptor(
                UUID.randomUUID().toString(), "", "", Kind.NOOP, 0L,
                "", "", "", List.of(), List.of(), 0L, "", "", "", "", "1", false,
                WorkerProtocol.VERSION, "", Map.of(), Map.of(), 0, 0);
    }

    public static ProcessingTaskDescriptor sleep(long millis) {
        return new ProcessingTaskDescriptor(
                UUID.randomUUID().toString(), "", "", Kind.SLEEP, millis,
                "", "", "", List.of(), List.of(), 0L, "", "", "", "", "1", false,
                WorkerProtocol.VERSION, "", Map.of(), Map.of(), 0, 0);
    }

    public static ProcessingTaskDescriptor plan(
            String taskId,
            String projectId,
            String runId,
            String projectDbPath,
            String analysisStorageRoot,
            String planJson,
            Map<String, String> sourcePathById,
            Map<String, String> definitionPathById,
            boolean forceReprocess
    ) {
        return new ProcessingTaskDescriptor(
                taskId, projectId, runId, Kind.PLAN, 0L,
                projectDbPath, analysisStorageRoot, "",
                List.copyOf(sourcePathById == null ? List.of() : sourcePathById.values()),
                List.copyOf(definitionPathById == null ? List.of() : definitionPathById.values()),
                0L, "", "", "", "", "1", forceReprocess,
                WorkerProtocol.VERSION, planJson,
                sourcePathById == null ? Map.of() : sourcePathById,
                definitionPathById == null ? Map.of() : definitionPathById,
                0, 0);
    }

    public ProcessingTaskDescriptor withTaskId(String id) {
        return new ProcessingTaskDescriptor(
                Objects.requireNonNull(id), projectId, runId, kind, sleepMillis,
                projectDbPath, analysisStorageRoot, artifactRoot, sourcePaths, definitionPaths,
                resumeAfterRecords, checkpointCursor, planFingerprint, sourceFingerprint,
                dbcFingerprint, artifactVersion, forceReprocess,
                protocolVersion, planJson, sourcePathById, definitionPathById,
                interruptAfterCheckpoints, processingBatchSize);
    }

    public ProcessingTaskDescriptor withInterruptAfterCheckpoints(int count) {
        return new ProcessingTaskDescriptor(
                taskId, projectId, runId, kind, sleepMillis,
                projectDbPath, analysisStorageRoot, artifactRoot, sourcePaths, definitionPaths,
                resumeAfterRecords, checkpointCursor, planFingerprint, sourceFingerprint,
                dbcFingerprint, artifactVersion, forceReprocess,
                protocolVersion, planJson, sourcePathById, definitionPathById,
                count, processingBatchSize);
    }

    public ProcessingTaskDescriptor withProcessingBatchSize(int size) {
        return new ProcessingTaskDescriptor(
                taskId, projectId, runId, kind, sleepMillis,
                projectDbPath, analysisStorageRoot, artifactRoot, sourcePaths, definitionPaths,
                resumeAfterRecords, checkpointCursor, planFingerprint, sourceFingerprint,
                dbcFingerprint, artifactVersion, forceReprocess,
                protocolVersion, planJson, sourcePathById, definitionPathById,
                interruptAfterCheckpoints, size);
    }

    private static Map<String, String> copyMap(Map<String, String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(raw));
    }
}
