package com.stellar.platform.desktop.validation;

import com.stellar.platform.desktop.project.FileValidationStatus;

import java.util.List;
import java.util.Objects;

/**
 * Per-file validation outcome separating display status from processing eligibility.
 */
public record FileValidationResult(
        FileValidationStatus status,
        boolean processingAllowed,
        List<ValidationIssue> warnings,
        List<ValidationIssue> fatalErrors,
        String summaryMessage
) {

    public FileValidationResult {
        Objects.requireNonNull(status, "status");
        warnings = List.copyOf(Objects.requireNonNullElse(warnings, List.of()));
        fatalErrors = List.copyOf(Objects.requireNonNullElse(fatalErrors, List.of()));
        summaryMessage = summaryMessage == null ? "" : summaryMessage;
    }

    public static FileValidationResult ready(String summary) {
        return new FileValidationResult(FileValidationStatus.READY, true, List.of(), List.of(), summary);
    }

    public static FileValidationResult readyWithWarnings(String summary, List<ValidationIssue> warnings) {
        return new FileValidationResult(
                FileValidationStatus.READY_WITH_WARNINGS,
                true,
                warnings,
                List.of(),
                summary
        );
    }

    public static FileValidationResult invalid(String summary, ValidationIssue fatal) {
        return new FileValidationResult(
                FileValidationStatus.INVALID,
                false,
                List.of(),
                List.of(fatal),
                summary
        );
    }

    public static FileValidationResult unsupported(String summary, ValidationIssue fatal) {
        return new FileValidationResult(
                FileValidationStatus.UNSUPPORTED,
                false,
                List.of(),
                List.of(fatal),
                summary
        );
    }
}
