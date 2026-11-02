package com.stellar.platform.desktop.validation;

import java.util.Objects;

/**
 * User-facing validation finding before local processing starts.
 */
public record ValidationIssue(
        ValidationIssueCode code,
        Severity severity,
        String message,
        String technicalDetail
) {

    public enum Severity {
        ERROR,
        WARNING,
        INFO
    }

    public ValidationIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        technicalDetail = technicalDetail == null ? "" : technicalDetail;
    }

    public static ValidationIssue error(ValidationIssueCode code, String message, String technicalDetail) {
        return new ValidationIssue(code, Severity.ERROR, message, technicalDetail);
    }

    public static ValidationIssue warning(ValidationIssueCode code, String message, String technicalDetail) {
        return new ValidationIssue(code, Severity.WARNING, message, technicalDetail);
    }

    public static ValidationIssue info(ValidationIssueCode code, String message) {
        return new ValidationIssue(code, Severity.INFO, message, "");
    }
}
