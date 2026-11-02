package com.stellar.platform.desktop.project;

import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-memory project file entry shown in Project Files.
 */
public final class WorkspaceFile {

    private final String id;
    private final AtomicReference<Path> path;
    private final FileKind kind;
    private final AtomicReference<Long> sizeBytes;
    private final AtomicReference<FileValidationStatus> status;
    private final AtomicReference<String> validationMessage;
    private final AtomicReference<String> associatedDefinitionId;
    private final AtomicReference<String> mf4FinalizationLabel;
    private final AtomicReference<Boolean> recoveryCandidate;
    private final AtomicReference<String> detectedFormatLabel;

    public WorkspaceFile(Path path, FileKind kind, long sizeBytes) {
        this(kind != null && kind.definition()
                        ? DefinitionSourceId.generate().value()
                        : ProjectSourceId.generate().value(),
                path, kind, sizeBytes);
    }

    /** Recreate a persisted project entry while preserving its stable project identity. */
    public static WorkspaceFile restore(String id, Path path, FileKind kind, long sizeBytes) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        return new WorkspaceFile(id, path, kind, sizeBytes);
    }

    private WorkspaceFile(String id, Path path, FileKind kind, long sizeBytes) {
        this.id = Objects.requireNonNull(id, "id");
        this.path = new AtomicReference<>(
                Objects.requireNonNull(path, "path").toAbsolutePath().normalize());
        this.kind = Objects.requireNonNull(kind, "kind");
        this.sizeBytes = new AtomicReference<>(Math.max(0L, sizeBytes));
        this.status = new AtomicReference<>(
                kind.implemented() ? FileValidationStatus.READY : FileValidationStatus.UNSUPPORTED
        );
        this.validationMessage = new AtomicReference<>(
                kind.implemented() ? "" : "Not yet implemented"
        );
        this.associatedDefinitionId = new AtomicReference<>(null);
        this.mf4FinalizationLabel = new AtomicReference<>("");
        this.recoveryCandidate = new AtomicReference<>(false);
        this.detectedFormatLabel = new AtomicReference<>("");
    }

    public String id() {
        return id;
    }

    /** Typed measurement identity when this entry is a measurement source. */
    public ProjectSourceId projectSourceId() {
        if (!kind.measurement()) {
            throw new IllegalStateException("Not a measurement source: " + kind);
        }
        return ProjectSourceId.of(id);
    }

    /** Typed definition identity when this entry is a definition source. */
    public DefinitionSourceId definitionSourceId() {
        if (!kind.definition()) {
            throw new IllegalStateException("Not a definition source: " + kind);
        }
        return DefinitionSourceId.of(id);
    }

    /** Neutral MF-2 display state for project input lists. */
    public String projectInputStatusLabel() {
        return switch (status()) {
            case READY, READY_WITH_WARNINGS -> "Added";
            case VALIDATING -> "Validating";
            case INVALID -> "Invalid";
            case UNSUPPORTED -> "Unsupported";
        };
    }

    public Path path() {
        return path.get();
    }

    /**
     * Repoints this entry at the same measurement captured under a new location, keeping the
     * project source identity so mappings, inventory and processed artifacts stay attached.
     */
    public void relocate(Path newPath, long newSizeBytes) {
        Path normalized = Objects.requireNonNull(newPath, "newPath").toAbsolutePath().normalize();
        path.set(normalized);
        sizeBytes.set(Math.max(0L, newSizeBytes));
    }

    public String fileName() {
        Path current = path.get();
        Path name = current.getFileName();
        return name == null ? current.toString() : name.toString();
    }

    public FileKind kind() {
        return kind;
    }

    public long sizeBytes() {
        return sizeBytes.get();
    }

    public String displayPath() {
        return path.get().toString();
    }

    public FileValidationStatus status() {
        return status.get();
    }

    public void setStatus(FileValidationStatus status, String message) {
        this.status.set(Objects.requireNonNull(status, "status"));
        this.validationMessage.set(message == null ? "" : message);
    }

    public String validationMessage() {
        return validationMessage.get();
    }

    public String associatedDefinitionId() {
        return associatedDefinitionId.get();
    }

    public void setAssociatedDefinitionId(String definitionId) {
        associatedDefinitionId.set(definitionId);
    }

    public String mf4FinalizationLabel() {
        return mf4FinalizationLabel.get();
    }

    public void setMf4FinalizationLabel(String label) {
        mf4FinalizationLabel.set(label == null ? "" : label);
    }

    public boolean recoveryCandidate() {
        return Boolean.TRUE.equals(recoveryCandidate.get());
    }

    public void setRecoveryCandidate(boolean value) {
        recoveryCandidate.set(value);
    }

    /** Content-detected label such as {@code MDF 3.30}. Empty when the kind name is enough. */
    public String detectedFormatLabel() {
        return detectedFormatLabel.get();
    }

    public void setDetectedFormatLabel(String label) {
        detectedFormatLabel.set(label == null ? "" : label);
    }

    public String formatDisplayName() {
        String detected = detectedFormatLabel.get();
        if (detected != null && !detected.isBlank()) {
            return detected;
        }
        return kind.displayName();
    }
}
