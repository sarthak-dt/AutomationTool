package com.stellar.platform.api.dto;

import java.util.List;

/**
 * One page of catalog metadata. {@code total} is the full filtered match count (may exceed
 * {@link SignalCatalogQuery#MAX_PAGE_SIZE}); clients must advance {@code offset} to load more.
 */
public record SignalCatalogPageDto(
        String projectId,
        String query,
        String sourceType,
        String sourceFile,
        int offset,
        int limit,
        int total,
        List<SignalCatalogEntryDto> entries
) {
    public SignalCatalogPageDto {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId must not be blank");
        }
        query = query == null ? "" : query;
        sourceType = sourceType == null ? "" : sourceType;
        sourceFile = sourceFile == null ? "" : sourceFile;
        offset = Math.max(0, offset);
        limit = Math.max(1, Math.min(limit <= 0 ? SignalCatalogQuery.DEFAULT_PAGE_SIZE : limit,
                SignalCatalogQuery.MAX_PAGE_SIZE));
        total = Math.max(0, total);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    /** Backward-compatible constructor without source filters. */
    public SignalCatalogPageDto(
            String projectId,
            String query,
            int offset,
            int limit,
            int total,
            List<SignalCatalogEntryDto> entries
    ) {
        this(projectId, query, "", "", offset, limit, total, entries);
    }
}
