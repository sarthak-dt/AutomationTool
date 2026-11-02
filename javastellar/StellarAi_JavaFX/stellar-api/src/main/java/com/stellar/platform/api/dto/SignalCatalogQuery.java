package com.stellar.platform.api.dto;

/**
 * Bounded catalog query. Page size is capped; total may exceed one page — clients must page.
 * Does not load sample arrays.
 */
public record SignalCatalogQuery(
        String query,
        String sourceType,
        String sourceFile,
        int offset,
        int limit
) {
    public static final int MAX_PAGE_SIZE = 2000;
    public static final int DEFAULT_PAGE_SIZE = 200;

    public SignalCatalogQuery {
        query = query == null ? "" : query;
        sourceType = sourceType == null ? "" : sourceType.trim();
        sourceFile = sourceFile == null ? "" : sourceFile.trim();
        offset = Math.max(0, offset);
        limit = Math.max(1, Math.min(limit <= 0 ? DEFAULT_PAGE_SIZE : limit, MAX_PAGE_SIZE));
    }

    public SignalCatalogQuery(String query, int offset, int limit) {
        this(query, "", "", offset, limit);
    }
}
