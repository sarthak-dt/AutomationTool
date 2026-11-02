package com.stellar.platform.desktop.presentation;

import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSamplePoint;

import java.util.List;
import java.util.Objects;

/**
 * One bounded page of samples for a selected catalog signal.
 * Ordering is exactly the catalog query order (never re-sorted in the UI).
 */
public final class SignalSamplePageSnapshot {

    private final SignalCatalogKey key;
    private final List<SignalSamplePoint> samples;
    private final int pageOffset;
    private final int pageSize;
    private final long totalSamples;
    private final long retainedSamples;
    private final long windowStartIndex;

    public SignalSamplePageSnapshot(
            SignalCatalogKey key,
            List<SignalSamplePoint> samples,
            int pageOffset,
            int pageSize,
            long totalSamples,
            long retainedSamples,
            long windowStartIndex
    ) {
        this.key = Objects.requireNonNull(key, "key");
        this.samples = List.copyOf(Objects.requireNonNull(samples, "samples"));
        this.pageOffset = Math.max(0, pageOffset);
        this.pageSize = Math.max(1, pageSize);
        this.totalSamples = Math.max(0L, totalSamples);
        this.retainedSamples = Math.max(0L, retainedSamples);
        this.windowStartIndex = Math.max(0L, windowStartIndex);
    }

    public static SignalSamplePageSnapshot empty(SignalCatalogKey key, int pageSize) {
        return new SignalSamplePageSnapshot(key, List.of(), 0, pageSize, 0L, 0L, 0L);
    }

    public SignalCatalogKey key() {
        return key;
    }

    public List<SignalSamplePoint> samples() {
        return samples;
    }

    /** Offset into the retained window passed to {@code querySamples}. */
    public int pageOffset() {
        return pageOffset;
    }

    public int pageSize() {
        return pageSize;
    }

    /** Catalog sample count (may exceed retained window). */
    public long totalSamples() {
        return totalSamples;
    }

    public long retainedSamples() {
        return retainedSamples;
    }

    /** Global index of retained window start (0 when not truncated). */
    public long windowStartIndex() {
        return windowStartIndex;
    }

    /** Zero-based global series index for a row in this page. */
    public long globalIndex(int rowInPage) {
        if (rowInPage < 0 || rowInPage >= samples.size()) {
            return -1L;
        }
        return windowStartIndex + pageOffset + rowInPage;
    }

    public long firstGlobalIndex() {
        return samples.isEmpty() ? -1L : globalIndex(0);
    }

    public long lastGlobalIndex() {
        return samples.isEmpty() ? -1L : globalIndex(samples.size() - 1);
    }

    /** 1-based page number within the retained window. */
    public int pageNumber() {
        if (retainedSamples <= 0) {
            return 1;
        }
        return (pageOffset / pageSize) + 1;
    }

    public int pageCount() {
        if (retainedSamples <= 0) {
            return 1;
        }
        return (int) ((retainedSamples + pageSize - 1L) / pageSize);
    }

    public boolean hasPrevious() {
        return pageOffset > 0;
    }

    public boolean hasNext() {
        return (long) pageOffset + samples.size() < retainedSamples;
    }

    public int previousPageOffset() {
        return Math.max(0, pageOffset - pageSize);
    }

    public int nextPageOffset() {
        long next = (long) pageOffset + pageSize;
        if (next >= retainedSamples) {
            return pageOffset;
        }
        return (int) next;
    }

    public int lastPageOffset() {
        if (retainedSamples <= 0) {
            return 0;
        }
        long last = ((retainedSamples - 1L) / pageSize) * pageSize;
        return (int) last;
    }

    /**
     * Retained-window offset of the page containing {@code globalIndex}, or -1 if out of range /
     * not retained.
     */
    public static int pageOffsetForGlobalIndex(
            long globalIndex,
            long windowStartIndex,
            long retainedSamples,
            int pageSize
    ) {
        if (pageSize < 1 || retainedSamples <= 0) {
            return -1;
        }
        if (globalIndex < windowStartIndex || globalIndex >= windowStartIndex + retainedSamples) {
            return -1;
        }
        long local = globalIndex - windowStartIndex;
        return (int) ((local / pageSize) * pageSize);
    }
}
