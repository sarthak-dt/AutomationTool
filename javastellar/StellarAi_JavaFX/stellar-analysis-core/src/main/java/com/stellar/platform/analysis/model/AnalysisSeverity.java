package com.stellar.platform.analysis.model;

/**
 * Finding severity is configured by the analysis request. Ordinary threshold events
 * default to {@link #OBSERVATION} — they are not automatically CRITICAL.
 */
public enum AnalysisSeverity {
    INFO,
    OBSERVATION,
    WARNING,
    CRITICAL
}
