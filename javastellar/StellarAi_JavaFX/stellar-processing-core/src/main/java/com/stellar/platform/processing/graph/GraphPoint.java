package com.stellar.platform.processing.graph;

/** One immutable physical-value point supplied to the graph pipeline. */
public record GraphPoint(long timestampNanos, double value) { }
