package com.stellar.platform.api;

import com.stellar.platform.api.dto.SignalCatalogPageDto;
import com.stellar.platform.api.dto.SignalCatalogQuery;
import com.stellar.platform.api.dto.SignalSamplePageDto;
import com.stellar.platform.api.dto.SignalStatsResultDto;
import com.stellar.platform.api.dto.SignalViewportRequestDto;
import com.stellar.platform.api.dto.SignalViewportResultDto;

/**
 * Bounded catalog, sample, range, and viewport queries after a successful process job.
 * Never returns unbounded multi-GB payloads.
 */
public interface SignalQueryApi {

    default SignalCatalogPageDto catalog(String projectId, String query, int offset, int limit) {
        return catalog(projectId, new SignalCatalogQuery(query, offset, limit));
    }

    SignalCatalogPageDto catalog(String projectId, SignalCatalogQuery query);

    SignalSamplePageDto samples(String projectId, String signalKey, int offset, int limit);

    SignalSamplePageDto range(String projectId, String signalKey, long fromNanos, long toNanos, int limit);

    /** Viewport LOD buckets for Graphs — does not transfer every retained sample. */
    SignalViewportResultDto viewport(String projectId, SignalViewportRequestDto request);

    /** Existing analysis features over a viewport frame (no new metrics). */
    SignalStatsResultDto viewportStats(String projectId, SignalViewportRequestDto request);
}
