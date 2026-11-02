package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.align.AlignedSignalPage;
import com.stellar.platform.processing.align.AlignedSignalQuery;
import com.stellar.platform.processing.align.AlignedSignalRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Desktop adapter over {@link AlignedSignalQuery}. Alignment logic lives in processing-core;
 * this class only maps shared rows into presentation {@link AlignedSampleRow} instances.
 */
public final class SignalTimestampAligner {

    private SignalTimestampAligner() {
    }

    public static List<AlignedSampleRow> alignByTimestamp(
            List<List<SignalSamplePoint>> series,
            int maxRows
    ) {
        return alignByTimestamp(series, 0, maxRows);
    }

    public static List<AlignedSampleRow> alignByTimestamp(
            List<List<SignalSamplePoint>> series,
            int startIndex,
            int maxRows
    ) {
        AlignedSignalPage page = AlignedSignalQuery.query(series, startIndex, maxRows);
        return toPresentation(page.rows());
    }

    /**
     * Aligns a page against a cached exact timestamp union. This avoids rebuilding the union
     * during every next/previous/jump operation.
     */
    public static List<AlignedSampleRow> alignByTimestamp(
            List<List<SignalSamplePoint>> series,
            long[] timeline,
            int startIndex,
            int maxRows
    ) {
        AlignedSignalPage page = AlignedSignalQuery.query(series, timeline, startIndex, maxRows);
        return toPresentation(page.rows());
    }

    public static long[] unionTimestamps(List<List<SignalSamplePoint>> series) {
        return AlignedSignalQuery.unionTimestamps(series);
    }

    public static int firstIndexAtOrAfter(long[] timestamps, long timestampNanos) {
        return AlignedSignalQuery.firstIndexAtOrAfter(timestamps, timestampNanos);
    }

    public static int countUnionTimestamps(List<List<SignalSamplePoint>> series) {
        return AlignedSignalQuery.countUnionTimestamps(series);
    }

    private static List<AlignedSampleRow> toPresentation(List<AlignedSignalRow> rows) {
        List<AlignedSampleRow> out = new ArrayList<>(rows.size());
        for (AlignedSignalRow row : rows) {
            Double[] physical = new Double[row.signalCount()];
            Long[] raw = new Long[row.signalCount()];
            String[] textual = new String[row.signalCount()];
            for (int i = 0; i < row.signalCount(); i++) {
                physical[i] = row.physicalAt(i);
                raw[i] = row.rawAt(i);
                textual[i] = row.textualAt(i);
            }
            out.add(new AlignedSampleRow(row.index(), row.timestampNanos(), physical, raw, textual, row.validityCopy()));
        }
        return List.copyOf(out);
    }
}
