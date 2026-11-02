package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.ui.UiFormat;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalSourceType;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Pure presentation helpers for the Signals screen selected-signal header and index labels.
 * No catalog or sample I/O - safe to call on the JavaFX thread.
 */
public final class SignalsSelectionPresentation {

    public static final String NONE_TITLE = "Selected Signal: None";
    /** Em dash used when a metadata field is not applicable. */
    public static final String EMPTY_META = "\u2014";

    private SignalsSelectionPresentation() {
    }

    public static String selectedTitle(List<SignalCatalogEntry> selected) {
        if (selected == null || selected.isEmpty()) {
            return NONE_TITLE;
        }
        if (selected.size() == 1) {
            return "Selected Signal: " + nullToEmpty(selected.getFirst().signalName());
        }
        return "Selected Signal: " + nullToEmpty(selected.getFirst().signalName())
                + " (+" + (selected.size() - 1) + " more)";
    }

    /**
     * Compact single-line metadata for a selected catalog entry.
     * Omits the signal name (already in {@link #selectedTitle}) and page "Showing"
     * range (already in {@link #pageStatus}).
     */
    public static String selectedSummary(SignalCatalogEntry entry, SignalSamplePageSnapshot page) {
        if (entry == null) {
            return "";
        }
        return "Source: " + (entry.sourceType() == null ? EMPTY_META : entry.sourceType().name())
                + "  ·  Unit: " + blankToEmDash(entry.unit())
                + "  ·  Message: " + displayOrEmDash(entry.messageName())
                + "  ·  CAN ID: " + formatCanId(entry)
                + "  ·  Samples: " + formatCount(entry.sampleCount())
                + "  ·  Measurement: " + blankToEmDash(entry.sourceFile());
    }

    public static String selectedSummary(SignalCatalogEntry entry) {
        return selectedSummary(entry, null);
    }

    public static String selectedSummary(List<SignalCatalogEntry> selected, SignalSamplePageSnapshot page) {
        if (selected == null || selected.isEmpty()) {
            return "";
        }
        if (selected.size() == 1) {
            return selectedSummary(selected.getFirst(), page);
        }
        return "Multiple signals selected (" + selected.size() + "). "
                + "Sample rows below may include bounded pages from each selection.  ·  Primary: "
                + selectedSummary(selected.getFirst(), null);
    }

    public static String selectedSummary(List<SignalCatalogEntry> selected) {
        return selectedSummary(selected, null);
    }

    public static String pageStatus(SignalSamplePageSnapshot page) {
        if (page == null || page.totalSamples() <= 0) {
            return "";
        }
        if (page.samples().isEmpty()) {
            return "Showing none of " + formatCount(page.totalSamples())
                    + "  |  Page " + page.pageNumber() + " of " + page.pageCount();
        }
        return "Showing " + formatCount(page.firstGlobalIndex())
                + '\u2013' + formatCount(page.lastGlobalIndex())
                + " of " + formatCount(page.totalSamples())
                + "  |  Page " + page.pageNumber() + " of " + page.pageCount();
    }

    /**
     * Zero-based global sample index for a visible table row.
     * {@code pageOffset} is the retained-window query offset; {@code windowStartIndex}
     * is the global index of the retained window start (0 when not truncated).
     */
    public static long globalIndex(long windowStartIndex, int pageOffset, int rowIndexInPage) {
        if (pageOffset < 0 || rowIndexInPage < 0 || windowStartIndex < 0) {
            return -1L;
        }
        return windowStartIndex + pageOffset + rowIndexInPage;
    }

    /** @deprecated Use {@link #globalIndex(long, int, int)}; kept temporarily for call-site migration. */
    public static int visibleRowNumber(int pageOffset, int rowIndexInPage) {
        long idx = globalIndex(0L, pageOffset, rowIndexInPage);
        return idx < 0 ? 0 : (int) idx;
    }

    static String formatCanId(SignalCatalogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (entry.sourceType() == SignalSourceType.MF4_NATIVE) {
            return EMPTY_META;
        }
        return UiFormat.canId(entry.canId());
    }

    private static String formatCount(long value) {
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        nf.setGroupingUsed(true);
        return nf.format(value);
    }

    private static String blankToEmDash(String value) {
        if (value == null || value.isBlank()) {
            return EMPTY_META;
        }
        return value;
    }

    private static String displayOrEmDash(String value) {
        return blankToEmDash(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
