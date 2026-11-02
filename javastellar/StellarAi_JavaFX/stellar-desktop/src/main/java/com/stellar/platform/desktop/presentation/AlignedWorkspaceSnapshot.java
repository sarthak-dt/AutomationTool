package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogEntry;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/** Bounded aligned view for the Active Signal Workspace table. */
public final class AlignedWorkspaceSnapshot {

    private final List<SignalCatalogEntry> signals;
    private final List<AlignedSampleRow> rows;
    private final int windowStartIndex;
    private final int totalAlignedRows;
    private final int pageSize;
    private final Integer focusRowIndex;

    public AlignedWorkspaceSnapshot(List<SignalCatalogEntry> signals, List<AlignedSampleRow> rows) {
        this(signals, rows, 0, rows == null ? 0 : rows.size(), Math.max(1, rows == null ? 1 : rows.size()), null);
    }

    public AlignedWorkspaceSnapshot(
            List<SignalCatalogEntry> signals,
            List<AlignedSampleRow> rows,
            int windowStartIndex,
            int totalAlignedRows,
            int pageSize,
            Integer focusRowIndex
    ) {
        this.signals = List.copyOf(Objects.requireNonNull(signals, "signals"));
        this.rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        this.windowStartIndex = Math.max(0, windowStartIndex);
        this.totalAlignedRows = Math.max(0, totalAlignedRows);
        this.pageSize = Math.max(1, pageSize);
        this.focusRowIndex = focusRowIndex;
    }

    public static AlignedWorkspaceSnapshot empty() {
        return new AlignedWorkspaceSnapshot(List.of(), List.of(), 0, 0, 1, null);
    }

    public List<SignalCatalogEntry> signals() {
        return signals;
    }

    public List<AlignedSampleRow> rows() {
        return rows;
    }

    /** Global aligned-row index of the first row in this window. */
    public int windowStartIndex() {
        return windowStartIndex;
    }

    /** Total aligned rows available from retained workspace samples. */
    public int totalAlignedRows() {
        return totalAlignedRows;
    }

    public int pageSize() {
        return pageSize;
    }

    /** Local row index within {@link #rows()} to highlight after a jump, if any. */
    public OptionalLong focusRowIndex() {
        return focusRowIndex == null ? OptionalLong.empty() : OptionalLong.of(focusRowIndex);
    }

    public boolean hasPrevious() {
        return windowStartIndex > 0;
    }

    public boolean hasNext() {
        return windowStartIndex + rows.size() < totalAlignedRows;
    }

    public int previousWindowStart() {
        return Math.max(0, windowStartIndex - pageSize);
    }

    public int nextWindowStart() {
        int next = windowStartIndex + pageSize;
        if (next >= totalAlignedRows) {
            return windowStartIndex;
        }
        return next;
    }

    public int firstGlobalIndex() {
        return rows.isEmpty() ? -1 : (int) rows.getFirst().index();
    }

    public int lastGlobalIndex() {
        return rows.isEmpty() ? -1 : (int) rows.getLast().index();
    }
}
