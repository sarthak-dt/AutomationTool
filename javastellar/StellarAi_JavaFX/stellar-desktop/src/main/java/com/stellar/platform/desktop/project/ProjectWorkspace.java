package com.stellar.platform.desktop.project;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory working projection for the desktop UI. Durable metadata persistence is handled by the storage layer.
 */
public final class ProjectWorkspace {

    private final AtomicReference<String> projectName = new AtomicReference<>("Untitled Project");
    private final List<WorkspaceFile> measurementFiles = new CopyOnWriteArrayList<>();
    private final List<WorkspaceFile> definitionFiles = new CopyOnWriteArrayList<>();
    private final AtomicReference<ProjectProcessingStatus> processingStatus =
            new AtomicReference<>(ProjectProcessingStatus.IDLE);
    private final AtomicReference<ProcessingSummary> latestSummary = new AtomicReference<>(null);
    private final AtomicReference<String> selectedMeasurementId = new AtomicReference<>(null);
    private final AtomicReference<String> selectedDefinitionId = new AtomicReference<>(null);

    public static ProjectWorkspace createNew(String name) {
        ProjectWorkspace workspace = new ProjectWorkspace();
        workspace.setProjectName(name == null || name.isBlank() ? "Untitled Project" : name.trim());
        return workspace;
    }

    public void reset(String name) {
        projectName.set(name == null || name.isBlank() ? "Untitled Project" : name.trim());
        measurementFiles.clear();
        definitionFiles.clear();
        processingStatus.set(ProjectProcessingStatus.IDLE);
        latestSummary.set(null);
        selectedMeasurementId.set(null);
        selectedDefinitionId.set(null);
    }

    public String projectName() {
        return projectName.get();
    }

    public void setProjectName(String name) {
        projectName.set(Objects.requireNonNull(name, "name").trim());
    }

    public List<WorkspaceFile> measurementFiles() {
        return List.copyOf(measurementFiles);
    }

    public List<WorkspaceFile> definitionFiles() {
        return List.copyOf(definitionFiles);
    }

    public WorkspaceFile addFile(WorkspaceFile file) {
        Objects.requireNonNull(file, "file");
        if (file.kind().measurement()) {
            measurementFiles.add(file);
        } else if (file.kind().definition()) {
            definitionFiles.add(file);
        } else {
            throw new IllegalArgumentException("Unsupported file kind for project: " + file.kind());
        }
        return file;
    }

    /**
     * Finds an existing measurement by normalized absolute path.
     * Filename alone is never used as identity.
     */
    public Optional<WorkspaceFile> findMeasurementByPath(Path path) {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        return measurementFiles.stream().filter(f -> f.path().equals(normalized)).findFirst();
    }

    /**
     * Finds an existing definition by normalized absolute path.
     */
    public Optional<WorkspaceFile> findDefinitionByPath(Path path) {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        return definitionFiles.stream().filter(f -> f.path().equals(normalized)).findFirst();
    }

    public int measurementSourceCount() {
        return measurementFiles.size();
    }

    public int definitionSourceCount() {
        return definitionFiles.size();
    }

    public boolean removeMeasurement(ProjectSourceId sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        return removeFile(sourceId.value());
    }

    public boolean removeDefinition(DefinitionSourceId definitionId) {
        Objects.requireNonNull(definitionId, "definitionId");
        return removeFile(definitionId.value());
    }

    public boolean removeFile(String fileId) {
        boolean removed = measurementFiles.removeIf(f -> f.id().equals(fileId));
        removed = definitionFiles.removeIf(f -> f.id().equals(fileId)) || removed;
        if (Objects.equals(selectedMeasurementId.get(), fileId)) {
            selectedMeasurementId.set(null);
        }
        if (Objects.equals(selectedDefinitionId.get(), fileId)) {
            selectedDefinitionId.set(null);
        }
        return removed;
    }

    public Optional<WorkspaceFile> findFile(String fileId) {
        return allFiles().stream().filter(f -> f.id().equals(fileId)).findFirst();
    }

    public List<WorkspaceFile> allFiles() {
        List<WorkspaceFile> all = new ArrayList<>(measurementFiles.size() + definitionFiles.size());
        all.addAll(measurementFiles);
        all.addAll(definitionFiles);
        return List.copyOf(all);
    }

    public void selectMeasurement(String fileId) {
        selectedMeasurementId.set(fileId);
    }

    /**
     * Highlights a definition. Does not change any measurement's saved association.
     * Callers that mean "use this DBC for the selected CAN measurement" set that
     * association explicitly. Restore uses this method, and must not rebind DAT/LOG
     * or a CAN source that was saved against a different definition.
     */
    public void selectDefinition(String fileId) {
        selectedDefinitionId.set(fileId);
    }

    public Optional<WorkspaceFile> selectedMeasurement() {
        String id = selectedMeasurementId.get();
        return id == null ? Optional.empty() : findFile(id);
    }

    public Optional<WorkspaceFile> selectedDefinition() {
        String id = selectedDefinitionId.get();
        return id == null ? Optional.empty() : findFile(id);
    }

    public ProjectProcessingStatus processingStatus() {
        return processingStatus.get();
    }

    public void setProcessingStatus(ProjectProcessingStatus status) {
        processingStatus.set(Objects.requireNonNull(status, "status"));
    }

    public Optional<ProcessingSummary> latestSummary() {
        return Optional.ofNullable(latestSummary.get());
    }

    public void setLatestSummary(ProcessingSummary summary) {
        latestSummary.set(summary);
    }
}
