package com.stellar.platform.processing.align;

import java.util.List;
import java.util.Objects;

/**
 * Bounded window of timestamp-aligned rows for multi-signal analysis.
 * Operates on already-decoded catalog samples — never reparses measurement sources.
 */
public final class AlignedSignalPage {

    private final List<AlignedSignalRow> rows;
    private final int offset;
    private final int limit;
    private final int totalAlignedRows;
    private final int signalCount;

    public AlignedSignalPage(
            List<AlignedSignalRow> rows,
            int offset,
            int limit,
            int totalAlignedRows,
            int signalCount
    ) {
        this.rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        this.offset = Math.max(0, offset);
        this.limit = Math.max(1, limit);
        this.totalAlignedRows = Math.max(0, totalAlignedRows);
        this.signalCount = Math.max(0, signalCount);
    }

    public static AlignedSignalPage empty() {
        return new AlignedSignalPage(List.of(), 0, 1, 0, 0);
    }

    public List<AlignedSignalRow> rows() {
        return rows;
    }

    /** Global aligned-row index of the first row in this page. */
    public int offset() {
        return offset;
    }

    public int limit() {
        return limit;
    }

    /** Total rows on the aligned timeline (independent of page size). */
    public int totalAlignedRows() {
        return totalAlignedRows;
    }

    public int signalCount() {
        return signalCount;
    }

    public boolean hasPrevious() {
        return offset > 0;
    }

    public boolean hasNext() {
        return offset + rows.size() < totalAlignedRows;
    }
}
