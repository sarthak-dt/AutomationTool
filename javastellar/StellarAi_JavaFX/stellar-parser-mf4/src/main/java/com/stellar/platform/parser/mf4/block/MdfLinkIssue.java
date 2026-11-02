package com.stellar.platform.parser.mf4.block;

import java.util.Objects;

/**
 * A link-related diagnostic retained in the block graph / inspection report.
 */
public record MdfLinkIssue(
        MdfLinkIssueKind kind,
        long sourceAddress,
        MdfBlockType sourceType,
        int linkIndex,
        long targetAddress,
        String message
) {

    public MdfLinkIssue {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(message, "message");
    }
}
