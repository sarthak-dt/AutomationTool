package com.stellar.platform.desktop.presentation;

import java.util.Objects;

/**
 * Immutable snapshot of the processing selection that produced the current Signals catalog.
 * Survives later Project Files channel-selection edits until a new processing run replaces it.
 */
public record SignalsProcessingContext(
        String sourceFile,
        String dbcFile,
        String channelsSummary,
        String mode,
        SignalViewScope viewScope
) {

    public static final String MODE_LOCAL = "LOCAL";
    public static final String MODE_QUICK_DECODE = "AUTO DECODE";

    public SignalsProcessingContext {
        sourceFile = sourceFile == null ? "" : sourceFile;
        dbcFile = dbcFile == null ? "" : dbcFile;
        channelsSummary = channelsSummary == null || channelsSummary.isBlank() ? "\u2014" : channelsSummary;
        mode = mode == null || mode.isBlank() ? MODE_LOCAL : mode;
        viewScope = viewScope == null ? SignalViewScope.JOB : viewScope;
    }

    /** Backward-compatible constructor defaulting to job/local scope. */
    public SignalsProcessingContext(
            String sourceFile,
            String dbcFile,
            String channelsSummary,
            String mode
    ) {
        this(sourceFile, dbcFile, channelsSummary, mode, SignalViewScope.JOB);
    }

    public static SignalsProcessingContext empty() {
        return new SignalsProcessingContext("", "", "\u2014", MODE_LOCAL, SignalViewScope.JOB);
    }

    public static SignalsProcessingContext of(
            String sourceFile,
            String dbcFile,
            String channelsSummary
    ) {
        return new SignalsProcessingContext(sourceFile, dbcFile, channelsSummary, MODE_LOCAL, SignalViewScope.JOB);
    }

    public static SignalsProcessingContext of(
            String sourceFile,
            String dbcFile,
            String channelsSummary,
            SignalViewScope viewScope
    ) {
        return new SignalsProcessingContext(
                sourceFile, dbcFile, channelsSummary, MODE_LOCAL, viewScope);
    }

    public static SignalsProcessingContext quickDecode(
            String sourceSummary,
            String dbcSummary,
            String channelsSummary
    ) {
        return quickDecode(sourceSummary, dbcSummary, channelsSummary, SignalViewScope.PROJECT);
    }

    public static SignalsProcessingContext quickDecode(
            String sourceSummary,
            String dbcSummary,
            String channelsSummary,
            SignalViewScope viewScope
    ) {
        return new SignalsProcessingContext(
                sourceSummary,
                dbcSummary,
                channelsSummary,
                MODE_QUICK_DECODE,
                viewScope == null ? SignalViewScope.PROJECT : viewScope);
    }

    public boolean hasResult() {
        return !sourceFile.isBlank() || !dbcFile.isBlank()
                || (channelsSummary != null && !channelsSummary.isBlank() && !"\u2014".equals(channelsSummary));
    }

    public String compactLabel() {
        return "Source: " + display(sourceFile)
                + "  |  DBC: " + display(dbcFile)
                + "  |  Channels: " + channelsSummary
                + "  |  Mode: " + mode
                + "  |  Scope: " + viewScope.name();
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "\u2014" : value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SignalsProcessingContext that)) {
            return false;
        }
        return Objects.equals(sourceFile, that.sourceFile)
                && Objects.equals(dbcFile, that.dbcFile)
                && Objects.equals(channelsSummary, that.channelsSummary)
                && Objects.equals(mode, that.mode)
                && viewScope == that.viewScope;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceFile, dbcFile, channelsSummary, mode, viewScope);
    }
}
