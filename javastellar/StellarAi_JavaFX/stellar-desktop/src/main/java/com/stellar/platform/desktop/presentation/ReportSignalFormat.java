package com.stellar.platform.desktop.presentation;

/**
 * Report cells for numeric extrema.
 * A stored sample with no usable range is {@code n/a}. A missing catalog row stays an em dash.
 */
public final class ReportSignalFormat {

    private ReportSignalFormat() {
    }

    public static String extremum(Double value, long sampleCount) {
        if (value != null && Double.isFinite(value)) {
            return String.format(java.util.Locale.ROOT, "%.4g", value);
        }
        return sampleCount > 0 ? CategoricalSummary.UNAVAILABLE : AlignedSampleRow.MISSING;
    }
}
