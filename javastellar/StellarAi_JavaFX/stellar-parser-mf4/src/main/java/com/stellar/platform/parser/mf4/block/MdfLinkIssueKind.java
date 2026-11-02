package com.stellar.platform.parser.mf4.block;

/**
 * Classification of link-graph problems discovered during traversal.
 */
public enum MdfLinkIssueKind {
    OUTSIDE_FILE,
    BROKEN_UNREADABLE,
    DUPLICATE_REFERENCE,
    CYCLIC_REFERENCE,
    ZERO_LINK,
    PREMATURE_EOF,
    IMPOSSIBLE_LENGTH,
    ARITHMETIC_OVERFLOW
}
