package com.stellar.platform.analysis.workflow;

import com.stellar.platform.analysis.diagnostics.DiagnosticFinding;

/**
 * Finding range plus context. Does not mutate signal membership.
 */
public record GraphFocusRange(long startNanos, long endNanos) {

    public static final long DEFAULT_CONTEXT_NANOS = 5_000_000_000L;

    public static GraphFocusRange around(DiagnosticFinding finding) {
        return around(finding, DEFAULT_CONTEXT_NANOS);
    }

    public static GraphFocusRange around(DiagnosticFinding finding, long contextNanos) {
        if (finding == null) {
            return new GraphFocusRange(0L, 1L);
        }
        long start = finding.startTimestampNanos();
        long end = finding.endTimestampNanos() == null ? start : finding.endTimestampNanos();
        if (end < start) {
            long t = start;
            start = end;
            end = t;
        }
        long pad = Math.max(1L, contextNanos);
        long span = Math.max(0L, end - start);
        if (pad < span / 4) {
            pad = Math.max(pad, span / 4);
        }
        return new GraphFocusRange(start - pad, end + pad);
    }
}
