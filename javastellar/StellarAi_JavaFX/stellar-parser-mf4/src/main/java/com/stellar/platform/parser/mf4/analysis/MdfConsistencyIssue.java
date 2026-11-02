package com.stellar.platform.parser.mf4.analysis;

import java.util.Objects;

/**
 * One typed consistency finding.
 */
public record MdfConsistencyIssue(
        String code,
        MdfIssueSeverity severity,
        String message,
        Long relatedAddress
) {

    public MdfConsistencyIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
    }
}
