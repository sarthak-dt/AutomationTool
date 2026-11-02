package com.stellar.platform.analysis.investigation;

/** Evidence kind. References existing entities — never duplicated waveforms. */
public enum EvidenceKind {
    SIGNAL,
    TIME_RANGE,
    ANNOTATION,
    ANALYSIS_RESULT,
    DIAGNOSTIC_FINDING,
    SPECTRAL_RESULT,
    GRAPH_SNAPSHOT,
    ROUTE_MAP,
    ENGINEERING_NOTE
}
