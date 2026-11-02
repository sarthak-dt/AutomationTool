package com.stellar.platform.desktop.validation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of pre-processing selection validation (does not run full decode).
 */
public record InputValidationResult(
        boolean valid,
        boolean processingAllowed,
        List<ValidationIssue> issues,
        Optional<FileValidationResult> measurementResult,
        Optional<FileValidationResult> definitionResult
) {

    public InputValidationResult {
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        measurementResult = measurementResult == null ? Optional.empty() : measurementResult;
        definitionResult = definitionResult == null ? Optional.empty() : definitionResult;
    }

    public static InputValidationResult of(
            boolean processingAllowed,
            List<ValidationIssue> issues,
            FileValidationResult measurementResult,
            FileValidationResult definitionResult
    ) {
        List<ValidationIssue> all = issues == null ? List.of() : issues;
        boolean hasFatal = all.stream().anyMatch(i -> i.severity() == ValidationIssue.Severity.ERROR);
        return new InputValidationResult(
                processingAllowed && !hasFatal,
                processingAllowed && !hasFatal,
                all,
                Optional.ofNullable(measurementResult),
                Optional.ofNullable(definitionResult)
        );
    }

    public List<String> errorMessages() {
        return issues.stream()
                .filter(i -> i.severity() == ValidationIssue.Severity.ERROR)
                .map(ValidationIssue::message)
                .toList();
    }

    public List<String> warningMessages() {
        return issues.stream()
                .filter(i -> i.severity() == ValidationIssue.Severity.WARNING)
                .map(ValidationIssue::message)
                .toList();
    }

    public boolean hasCode(ValidationIssueCode code) {
        return issues.stream().anyMatch(i -> i.code() == code);
    }
}
