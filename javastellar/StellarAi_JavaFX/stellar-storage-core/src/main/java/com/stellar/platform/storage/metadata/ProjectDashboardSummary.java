package com.stellar.platform.storage.metadata;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Cheap dashboard row for a saved project. Built from SQLite metadata counts only —
 * never triggers MF4/ASC/BLF parsing or waveform loading.
 */
public record ProjectDashboardSummary(
        String projectId,
        String name,
        Instant createdAt,
        Instant updatedAt,
        int measurementFileCount,
        int definitionFileCount,
        int signalCount,
        String lastProcessingStatus,
        String fileTypesLabel,
        long canFrameCount,
        int inventorySourceCount,
        int mappingCount,
        String projectRoot,
        ProjectSummaryAvailability availability
) {
    public ProjectDashboardSummary {
        projectId = requireText(projectId, "projectId");
        name = name == null || name.isBlank() ? "Untitled Project" : name;
        createdAt = Objects.requireNonNullElseGet(createdAt, Instant::now);
        updatedAt = Objects.requireNonNullElse(updatedAt, createdAt);
        measurementFileCount = Math.max(0, measurementFileCount);
        definitionFileCount = Math.max(0, definitionFileCount);
        signalCount = Math.max(0, signalCount);
        lastProcessingStatus = lastProcessingStatus == null ? "" : lastProcessingStatus;
        fileTypesLabel = fileTypesLabel == null ? "" : fileTypesLabel;
        canFrameCount = Math.max(0L, canFrameCount);
        inventorySourceCount = Math.max(0, inventorySourceCount);
        mappingCount = Math.max(0, mappingCount);
        projectRoot = projectRoot == null ? "" : projectRoot;
        availability = availability == null ? ProjectSummaryAvailability.AVAILABLE : availability;
    }

    /** Convenience for callers without extended aggregates. */
    public ProjectDashboardSummary(
            String projectId,
            String name,
            Instant createdAt,
            Instant updatedAt,
            int measurementFileCount,
            int definitionFileCount,
            int signalCount,
            String lastProcessingStatus
    ) {
        this(projectId, name, createdAt, updatedAt, measurementFileCount, definitionFileCount,
                signalCount, lastProcessingStatus, "", 0L, 0, 0, "", ProjectSummaryAvailability.AVAILABLE);
    }

    public ProjectDashboardSummary(
            String projectId,
            String name,
            Instant createdAt,
            Instant updatedAt,
            int measurementFileCount,
            int definitionFileCount,
            int signalCount,
            String lastProcessingStatus,
            String fileTypesLabel,
            long canFrameCount,
            int inventorySourceCount,
            int mappingCount
    ) {
        this(projectId, name, createdAt, updatedAt, measurementFileCount, definitionFileCount,
                signalCount, lastProcessingStatus, fileTypesLabel, canFrameCount, inventorySourceCount,
                mappingCount, "", ProjectSummaryAvailability.AVAILABLE);
    }

    public static ProjectDashboardSummary unavailable(
            String projectId,
            String name,
            Instant createdAt,
            Instant updatedAt,
            String projectRoot,
            ProjectSummaryAvailability availability
    ) {
        return new ProjectDashboardSummary(
                projectId, name, createdAt, updatedAt,
                0, 0, 0, "", "", 0L, 0, 0,
                projectRoot,
                availability == null ? ProjectSummaryAvailability.ERROR : availability);
    }

    public ProjectDashboardSummary locatedAt(Path root, ProjectSummaryAvailability status) {
        String path = root == null ? projectRoot : root.toAbsolutePath().normalize().toString();
        return new ProjectDashboardSummary(
                projectId, name, createdAt, updatedAt,
                measurementFileCount, definitionFileCount, signalCount, lastProcessingStatus,
                fileTypesLabel, canFrameCount, inventorySourceCount, mappingCount,
                path, status);
    }

    public boolean countsAvailable() {
        return availability.countsAvailable();
    }

    public boolean hasPersistedContent() {
        return measurementFileCount > 0 || definitionFileCount > 0
                || signalCount > 0 || canFrameCount > 0 || inventorySourceCount > 0;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
