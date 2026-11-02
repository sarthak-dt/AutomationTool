package com.stellar.platform.analysis.source;

/** Thrown when a cancellation token is observed mid-analysis. Not a source-data error. */
public final class AnalysisCancelledException extends RuntimeException {
    public AnalysisCancelledException() {
        super("Analysis cancelled");
    }
}
