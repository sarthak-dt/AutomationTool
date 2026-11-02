package com.stellar.platform.analysis.investigation.report;

/** One static signal-analysis image. A blank image reference means the visual is unavailable. */
public record SignalAnalysisSection(String title, String imageReference, String unavailableNote) {

    public static final String UNAVAILABLE = "Signal analysis unavailable.";

    public SignalAnalysisSection {
        title = title == null || title.isBlank() ? "Signal Analysis" : title;
        imageReference = imageReference == null ? "" : imageReference;
        unavailableNote = unavailableNote == null ? "" : unavailableNote;
    }
}
