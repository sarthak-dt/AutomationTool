package com.stellar.platform.processing.align;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;
import com.stellar.platform.processing.catalog.SignalSeriesStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded multi-signal alignment for the Signals workspace.
 *
 * <p>Builds the timestamp union only for the requested page/window. Memory scales with
 * {@code selectedSignals × pageSize}, never with the full project duration.</p>
 */
public final class AlignedSignalWorkspaceQuery {

    public enum Mode {
        FIRST,
        NEXT,
        PREVIOUS,
        AT_TIMESTAMP,
        AT_INDEX
    }

    public record Request(
            List<SignalCatalogKey> keys,
            int pageSize,
            Mode mode,
            long timestampNanos,
            long index,
            int previousWindowStart,
            int previousRowCount
    ) {
        public Request {
            keys = keys == null ? List.of() : List.copyOf(keys);
            pageSize = Math.max(1, pageSize);
            mode = mode == null ? Mode.FIRST : mode;
            previousWindowStart = Math.max(0, previousWindowStart);
            previousRowCount = Math.max(0, previousRowCount);
        }

        public static Request first(List<SignalCatalogKey> keys, int pageSize) {
            return new Request(keys, pageSize, Mode.FIRST, 0L, 0L, 0, 0);
        }

        public static Request next(List<SignalCatalogKey> keys, int pageSize, long afterTimestamp, int windowStart, int rowCount) {
            return new Request(keys, pageSize, Mode.NEXT, afterTimestamp, 0L, windowStart, rowCount);
        }

        public static Request previous(List<SignalCatalogKey> keys, int pageSize, long beforeTimestamp, int windowStart) {
            return new Request(keys, pageSize, Mode.PREVIOUS, beforeTimestamp, 0L, windowStart, 0);
        }

        public static Request atTimestamp(List<SignalCatalogKey> keys, int pageSize, long timestampNanos) {
            return new Request(keys, pageSize, Mode.AT_TIMESTAMP, timestampNanos, 0L, 0, 0);
        }

        public static Request atIndex(List<SignalCatalogKey> keys, int pageSize, long index) {
            return new Request(keys, pageSize, Mode.AT_INDEX, 0L, Math.max(0L, index), 0, 0);
        }
    }

    public record Result(
            List<List<SignalSamplePoint>> pageSeries,
            List<AlignedSignalRow> rows,
            int windowStartIndex,
            int totalAlignedRows,
            int pageSize,
            boolean hasMoreBefore,
            boolean hasMoreAfter,
            int samplesRead
    ) {
        public Result {
            pageSeries = pageSeries == null ? List.of() : List.copyOf(pageSeries);
            rows = rows == null ? List.of() : List.copyOf(rows);
            windowStartIndex = Math.max(0, windowStartIndex);
            totalAlignedRows = Math.max(0, totalAlignedRows);
            pageSize = Math.max(1, pageSize);
            samplesRead = Math.max(0, samplesRead);
        }

        public static Result empty(int pageSize) {
            return new Result(List.of(), List.of(), 0, 0, pageSize, false, false, 0);
        }
    }

    private AlignedSignalWorkspaceQuery() {
    }

    public static Result query(SignalSeriesStore store, Request request) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(request, "request");
        List<SignalCatalogKey> keys = request.keys();
        int pageSize = request.pageSize();
        if (keys.isEmpty()) {
            return Result.empty(pageSize);
        }
        return switch (request.mode()) {
            case FIRST -> pageFromIndexes(store, keys, zeros(keys.size()), pageSize, 0);
            case AT_INDEX -> pageAtIndex(store, keys, request.index(), pageSize);
            case AT_TIMESTAMP -> pageAtTimestamp(store, keys, request.timestampNanos(), pageSize);
            case NEXT -> nextPage(store, keys, request);
            case PREVIOUS -> pageBeforeTimestamp(store, keys, request.timestampNanos(), pageSize, request.previousWindowStart());
        };
    }

    /**
     * The next window continues at the aligned row after the one just shown.
     * The primary signal's sample index is not that row number when other signals
     * add timestamps, so the displayed index must advance by the row count.
     */
    private static Result nextPage(SignalSeriesStore store, List<SignalCatalogKey> keys, Request request) {
        Result page = pageAtTimestamp(store, keys, increment(request.timestampNanos()), request.pageSize());
        if (request.previousRowCount() <= 0 || page.rows().isEmpty()) {
            return page;
        }
        int origin = request.previousWindowStart() + request.previousRowCount();
        if (origin == page.windowStartIndex()) {
            return page;
        }
        List<AlignedSignalRow> rows = remap(page.rows(), origin);
        int total = page.hasMoreAfter()
                ? Math.max(page.totalAlignedRows(), origin + rows.size() + 1)
                : origin + rows.size();
        return new Result(
                page.pageSeries(),
                rows,
                origin,
                total,
                page.pageSize(),
                origin > 0,
                page.hasMoreAfter(),
                page.samplesRead());
    }

    /**
     * {@code index} is the global aligned-row index. Signals that share one timeline
     * use a direct sample seek, because that index and the sample index are the same.
     * Different timelines are paged from the start so a sparser primary signal cannot
     * move the window to the wrong timestamp.
     */
    private static Result pageAtIndex(SignalSeriesStore store, List<SignalCatalogKey> keys, long index, int pageSize) {
        long safeIndex = Math.max(0L, index);
        if (keys.size() == 1 || sharedTimeline(store, keys)) {
            return pageAtPrimarySampleIndex(store, keys, safeIndex, pageSize);
        }
        return pageAtAlignedIndex(store, keys, safeIndex, pageSize);
    }

    private static Result pageAtPrimarySampleIndex(
            SignalSeriesStore store,
            List<SignalCatalogKey> keys,
            long index,
            int pageSize
    ) {
        SignalCatalogKey primary = keys.getFirst();
        int retained = Math.max(0, store.retainedSampleCount(primary));
        long safeIndex = Math.min(Math.max(0L, index), Math.max(0, retained - 1L));
        int start = (int) ((safeIndex / pageSize) * pageSize);
        List<SignalSamplePoint> primaryPage = store.queryPageFromIndex(primary, start, pageSize);
        if (primaryPage.isEmpty()) {
            return pageFromIndexes(store, keys, zeros(keys.size()), pageSize, 0);
        }
        long fromTs = primaryPage.getFirst().timestampNanos();
        return pageAtTimestamp(store, keys, fromTs, pageSize);
    }

    private static Result pageAtAlignedIndex(
            SignalSeriesStore store,
            List<SignalCatalogKey> keys,
            long index,
            int pageSize
    ) {
        int pageStart = (int) ((Math.min(index, Integer.MAX_VALUE) / pageSize) * pageSize);
        Result page = pageFromIndexes(store, keys, zeros(keys.size()), pageSize, 0);
        int guard = 0;
        while (page.hasMoreAfter()
                && page.windowStartIndex() + page.rows().size() <= pageStart
                && guard++ < 1_000_000) {
            if (page.rows().isEmpty()) {
                break;
            }
            Request next = Request.next(
                    keys,
                    pageSize,
                    page.rows().getLast().timestampNanos(),
                    page.windowStartIndex(),
                    page.rows().size());
            Result following = nextPage(store, keys, next);
            if (following.rows().isEmpty() || following.windowStartIndex() <= page.windowStartIndex()) {
                break;
            }
            page = following;
        }
        return page;
    }

    /** True when every signal has the same samples in time, so aligned index equals sample index. */
    private static boolean sharedTimeline(SignalSeriesStore store, List<SignalCatalogKey> keys) {
        int count = Math.max(0, store.retainedSampleCount(keys.getFirst()));
        if (count <= 0) {
            return true;
        }
        int[] probes = count == 1 ? new int[] {0} : new int[] {0, count / 2, count - 1};
        long[] expected = new long[probes.length];
        for (int probe = 0; probe < probes.length; probe++) {
            List<SignalSamplePoint> sample = store.querySamples(keys.getFirst(), probes[probe], 1);
            if (sample.isEmpty()) {
                return false;
            }
            expected[probe] = sample.getFirst().timestampNanos();
        }
        for (int signal = 1; signal < keys.size(); signal++) {
            if (store.retainedSampleCount(keys.get(signal)) != count) {
                return false;
            }
            for (int probe = 0; probe < probes.length; probe++) {
                List<SignalSamplePoint> sample = store.querySamples(keys.get(signal), probes[probe], 1);
                if (sample.isEmpty() || sample.getFirst().timestampNanos() != expected[probe]) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Result pageAtTimestamp(
            SignalSeriesStore store,
            List<SignalCatalogKey> keys,
            long timestampNanos,
            int pageSize
    ) {
        int[] starts = new int[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            starts[i] = (int) Math.min(Integer.MAX_VALUE, store.findSampleIndexAtOrAfter(keys.get(i), timestampNanos));
        }
        // A shared clock makes the primary sample index the aligned index.
        // Different clocks keep the chronological union index, so an earlier
        // sample on another signal is not labeled as the same row.
        int windowStart = keys.size() <= 1 || sharedTimeline(store, keys)
                ? starts[0]
                : unionIndexAtOrAfter(store, keys, timestampNanos);
        return pageFromIndexes(store, keys, starts, pageSize, windowStart);
    }

    private static Result pageBeforeTimestamp(
            SignalSeriesStore store,
            List<SignalCatalogKey> keys,
            long beforeTimestamp,
            int pageSize,
            int previousWindowStart
    ) {
        int[] starts = new int[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            int at = (int) Math.min(Integer.MAX_VALUE, store.findSampleIndexAtOrAfter(keys.get(i), beforeTimestamp));
            starts[i] = Math.max(0, at - pageSize);
        }
        List<List<SignalSamplePoint>> lookback = new ArrayList<>(keys.size());
        int samplesRead = 0;
        for (int i = 0; i < keys.size(); i++) {
            int lookbackCount = pageSize > Integer.MAX_VALUE / 2 ? Integer.MAX_VALUE : pageSize * 2;
            List<SignalSamplePoint> page = readWindow(store, keys.get(i), starts[i], lookbackCount);
            samplesRead += page.size();
            lookback.add(page);
        }
        AlignedSignalPage aligned = AlignedSignalQuery.query(lookback, 0, Integer.MAX_VALUE);
        List<AlignedSignalRow> before = new ArrayList<>();
        for (AlignedSignalRow row : aligned.rows()) {
            if (row.timestampNanos() < beforeTimestamp) {
                before.add(row);
            }
        }
        int from = Math.max(0, before.size() - pageSize);
        List<AlignedSignalRow> pageRows = before.subList(from, before.size());
        int windowStart = Math.max(0, previousWindowStart - pageRows.size());
        return finish(store, keys, lookback, remap(pageRows, windowStart), windowStart, pageSize, samplesRead);
    }

    /**
     * Reads {@code limit} samples even when a store returns a shorter page than requested
     * while samples remain. A 1,000-row query cap must not become the workspace step.
     */
    private static List<SignalSamplePoint> readWindow(
            SignalSeriesStore store,
            SignalCatalogKey key,
            int start,
            int limit
    ) {
        int safeStart = Math.max(0, start);
        int safeLimit = Math.max(1, limit);
        int retained = Math.max(0, store.retainedSampleCount(key));
        if (retained > 0 && safeStart >= retained) {
            return List.of();
        }
        int want = retained > 0 ? Math.min(safeLimit, retained - safeStart) : safeLimit;
        List<SignalSamplePoint> out = new ArrayList<>(Math.min(want, 8_192));
        int offset = safeStart;
        while (out.size() < want) {
            List<SignalSamplePoint> chunk = store.queryPageFromIndex(key, offset, want - out.size());
            if (chunk.isEmpty()) {
                break;
            }
            out.addAll(chunk);
            int next = offset + chunk.size();
            if (next <= offset) {
                break;
            }
            offset = next;
            if (retained > 0 && offset >= retained) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static Result pageFromIndexes(
            SignalSeriesStore store,
            List<SignalCatalogKey> keys,
            int[] starts,
            int pageSize,
            int windowStart
    ) {
        List<List<SignalSamplePoint>> series = new ArrayList<>(keys.size());
        int samplesRead = 0;
        boolean exhausted = true;
        int maxRetained = 0;
        for (int i = 0; i < keys.size(); i++) {
            SignalCatalogKey key = keys.get(i);
            int retained = Math.max(0, store.retainedSampleCount(key));
            maxRetained = Math.max(maxRetained, retained);
            List<SignalSamplePoint> page = readWindow(store, key, starts[i], pageSize);
            samplesRead += page.size();
            series.add(page);
            if (starts[i] + page.size() < retained) {
                exhausted = false;
            }
        }
        AlignedSignalPage aligned = AlignedSignalQuery.query(series, 0, pageSize);
        List<AlignedSignalRow> remapped = remap(aligned.rows(), windowStart);
        int total = exhausted
                ? windowStart + remapped.size()
                : Math.max(maxRetained, windowStart + remapped.size() + 1);
        return finish(store, keys, series, remapped, windowStart, pageSize, samplesRead, total);
    }

    private static Result finish(
            SignalSeriesStore store,
            List<SignalCatalogKey> keys,
            List<List<SignalSamplePoint>> series,
            List<AlignedSignalRow> rows,
            int windowStart,
            int pageSize,
            int samplesRead
    ) {
        int maxRetained = 0;
        for (SignalCatalogKey key : keys) {
            maxRetained = Math.max(maxRetained, store.retainedSampleCount(key));
        }
        int total = windowStart + rows.size();
        if (hasMoreAfter(store, keys, rows)) {
            total = Math.max(total + 1, maxRetained);
        }
        return finish(store, keys, series, rows, windowStart, pageSize, samplesRead, total);
    }

    private static Result finish(
            SignalSeriesStore store,
            List<SignalCatalogKey> keys,
            List<List<SignalSamplePoint>> series,
            List<AlignedSignalRow> rows,
            int windowStart,
            int pageSize,
            int samplesRead,
            int total
    ) {
        return new Result(
                series,
                rows,
                windowStart,
                total,
                pageSize,
                hasMoreBefore(store, keys, rows),
                hasMoreAfter(store, keys, rows),
                samplesRead);
    }

    private static boolean hasMoreAfter(
            SignalSeriesStore store,
            List<SignalCatalogKey> keys,
            List<AlignedSignalRow> rows
    ) {
        if (rows.isEmpty()) {
            return false;
        }
        long last = increment(rows.getLast().timestampNanos());
        for (SignalCatalogKey key : keys) {
            long idx = store.findSampleIndexAtOrAfter(key, last);
            if (idx < store.retainedSampleCount(key)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasMoreBefore(
            SignalSeriesStore store,
            List<SignalCatalogKey> keys,
            List<AlignedSignalRow> rows
    ) {
        if (rows.isEmpty()) {
            return false;
        }
        long first = rows.getFirst().timestampNanos();
        for (SignalCatalogKey key : keys) {
            if (store.findSampleIndexAtOrAfter(key, first) > 0) {
                return true;
            }
        }
        return false;
    }

    private static List<AlignedSignalRow> remap(List<AlignedSignalRow> rows, int windowStart) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<AlignedSignalRow> out = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            AlignedSignalRow row = rows.get(i);
            Double[] physical = new Double[row.signalCount()];
            Long[] raw = new Long[row.signalCount()];
            String[] textual = new String[row.signalCount()];
            for (int s = 0; s < row.signalCount(); s++) {
                physical[s] = row.physicalAt(s);
                raw[s] = row.rawAt(s);
                textual[s] = row.textualAt(s);
            }
            out.add(new AlignedSignalRow(windowStart + i, row.timestampNanos(), physical, raw, textual, row.validityCopy()));
        }
        return List.copyOf(out);
    }

    private static int[] zeros(int n) {
        return new int[n];
    }

    private static long increment(long timestampNanos) {
        return timestampNanos == Long.MAX_VALUE ? timestampNanos : timestampNanos + 1L;
    }

    /**
     * Exact chronological union size for the selected keys.
     * Streams timestamp chunks and discards them. Sample values are not copied into the table.
     * One selected key is that signal's retained sample count.
     */
    public static int countExactUnion(SignalSeriesStore store, List<SignalCatalogKey> keys) {
        Objects.requireNonNull(store, "store");
        if (keys == null || keys.isEmpty()) {
            return 0;
        }
        if (keys.size() == 1) {
            return Math.max(0, store.retainedSampleCount(keys.getFirst()));
        }
        List<TimestampCursor> cursors = new ArrayList<>();
        for (SignalCatalogKey key : keys) {
            int retained = Math.max(0, store.retainedSampleCount(key));
            if (retained > 0) {
                cursors.add(new TimestampCursor(store, key, retained));
            }
        }
        long count = 0;
        long last = 0;
        boolean seen = false;
        while (true) {
            int best = -1;
            long bestTs = 0;
            for (int i = 0; i < cursors.size(); i++) {
                TimestampCursor cursor = cursors.get(i);
                if (!cursor.hasCurrent()) {
                    continue;
                }
                long ts = cursor.current();
                if (best < 0 || ts < bestTs) {
                    best = i;
                    bestTs = ts;
                }
            }
            if (best < 0) {
                break;
            }
            if (!seen || bestTs != last) {
                count++;
                last = bestTs;
                seen = true;
            }
            cursors.get(best).advance();
        }
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    /**
     * Aligned index of the first unique timestamp at or after {@code timestampNanos}.
     * Stops at that timestamp. Sample values are not copied into the table.
     */
    private static int unionIndexAtOrAfter(SignalSeriesStore store, List<SignalCatalogKey> keys, long timestampNanos) {
        List<TimestampCursor> cursors = new ArrayList<>();
        for (SignalCatalogKey key : keys) {
            int retained = Math.max(0, store.retainedSampleCount(key));
            if (retained > 0) {
                cursors.add(new TimestampCursor(store, key, retained));
            }
        }
        long count = 0;
        long last = 0;
        boolean seen = false;
        while (true) {
            int best = -1;
            long bestTs = 0;
            for (int i = 0; i < cursors.size(); i++) {
                TimestampCursor cursor = cursors.get(i);
                if (!cursor.hasCurrent()) {
                    continue;
                }
                long ts = cursor.current();
                if (best < 0 || ts < bestTs) {
                    best = i;
                    bestTs = ts;
                }
            }
            if (best < 0 || bestTs >= timestampNanos) {
                break;
            }
            if (!seen || bestTs != last) {
                count++;
                last = bestTs;
                seen = true;
            }
            cursors.get(best).advance();
        }
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    /** Chunked timestamp reader. Holds one page of ticks, not the series. */
    private static final class TimestampCursor {
        private static final int CHUNK = 4_096;
        private final SignalSeriesStore store;
        private final SignalCatalogKey key;
        private final int retained;
        private int nextIndex;
        private long[] stamps = new long[0];
        private int position;
        private int length;

        private TimestampCursor(SignalSeriesStore store, SignalCatalogKey key, int retained) {
            this.store = store;
            this.key = key;
            this.retained = retained;
        }

        private boolean hasCurrent() {
            if (position < length) {
                return true;
            }
            load();
            return position < length;
        }

        private long current() {
            return stamps[position];
        }

        private void advance() {
            position++;
        }

        private void load() {
            if (nextIndex >= retained) {
                length = 0;
                position = 0;
                return;
            }
            int want = Math.min(CHUNK, retained - nextIndex);
            List<SignalSamplePoint> page = store.queryPageFromIndex(key, nextIndex, want);
            if (page.isEmpty()) {
                nextIndex = retained;
                length = 0;
                position = 0;
                return;
            }
            if (stamps.length < page.size()) {
                stamps = new long[page.size()];
            }
            for (int i = 0; i < page.size(); i++) {
                stamps[i] = page.get(i).timestampNanos();
            }
            length = page.size();
            position = 0;
            nextIndex += page.size();
        }
    }
}
