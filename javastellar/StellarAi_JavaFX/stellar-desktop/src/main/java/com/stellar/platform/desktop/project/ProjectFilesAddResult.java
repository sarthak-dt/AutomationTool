package com.stellar.platform.desktop.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Result of a multi-file add operation. Valid files are added; others are reported.
 */
public final class ProjectFilesAddResult {

    private final List<WorkspaceFile> added;
    private final List<String> skippedMessages;
    private final List<String> duplicateMessages;
    private final List<String> warningMessages;

    public ProjectFilesAddResult(
            List<WorkspaceFile> added,
            List<String> skippedMessages,
            List<String> duplicateMessages
    ) {
        this(added, skippedMessages, duplicateMessages, List.of());
    }

    public ProjectFilesAddResult(
            List<WorkspaceFile> added,
            List<String> skippedMessages,
            List<String> duplicateMessages,
            List<String> warningMessages
    ) {
        this.added = List.copyOf(Objects.requireNonNull(added, "added"));
        this.skippedMessages = List.copyOf(Objects.requireNonNull(skippedMessages, "skippedMessages"));
        this.duplicateMessages = List.copyOf(Objects.requireNonNull(duplicateMessages, "duplicateMessages"));
        this.warningMessages = List.copyOf(Objects.requireNonNull(warningMessages, "warningMessages"));
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<WorkspaceFile> added() {
        return added;
    }

    public List<String> skippedMessages() {
        return skippedMessages;
    }

    public List<String> duplicateMessages() {
        return duplicateMessages;
    }

    public int addedCount() {
        return added.size();
    }

    public int skippedCount() {
        return skippedMessages.size();
    }

    public int duplicateCount() {
        return duplicateMessages.size();
    }

    public List<String> warningMessages() {
        return warningMessages;
    }

    public int warningCount() {
        return warningMessages.size();
    }

    public String summaryMessage() {
        List<String> parts = new ArrayList<>();
        if (addedCount() > 0) {
            parts.add(addedCount() == 1
                    ? "1 file added"
                    : addedCount() + " files added");
        }
        if (warningCount() > 0) {
            parts.addAll(warningMessages);
        }
        if (duplicateCount() > 0) {
            parts.addAll(duplicateMessages);
        }
        if (skippedCount() > 0) {
            parts.add(skippedCount() == 1
                    ? "1 file skipped"
                    : skippedCount() + " files skipped");
            parts.addAll(skippedMessages);
        }
        if (parts.isEmpty()) {
            return "No files added.";
        }
        return String.join(System.lineSeparator(), parts);
    }

    public static final class Builder {
        private final List<WorkspaceFile> added = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();
        private final List<String> duplicates = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        public Builder added(WorkspaceFile file) {
            added.add(file);
            return this;
        }

        public Builder skipped(String message) {
            skipped.add(message);
            return this;
        }

        public Builder duplicate(String message) {
            duplicates.add(message);
            return this;
        }

        public Builder warning(String message) {
            warnings.add(message);
            return this;
        }

        public ProjectFilesAddResult build() {
            return new ProjectFilesAddResult(added, skipped, duplicates, warnings);
        }
    }
}
