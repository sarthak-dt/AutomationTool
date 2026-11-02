package com.stellar.platform.api.http;

import com.stellar.platform.api.SignalQueryApi;
import com.stellar.platform.api.dto.SignalCatalogPageDto;
import com.stellar.platform.api.dto.SignalCatalogQuery;
import com.stellar.platform.api.dto.SignalSamplePageDto;
import com.stellar.platform.api.dto.SignalStatsResultDto;
import com.stellar.platform.api.dto.SignalViewportRequestDto;
import com.stellar.platform.api.dto.SignalViewportResultDto;

public final class HttpSignalQueryApi implements SignalQueryApi {

    private final HttpTransport transport;

    public HttpSignalQueryApi(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public SignalCatalogPageDto catalog(String projectId, SignalCatalogQuery query) {
        SignalCatalogQuery q = query == null ? new SignalCatalogQuery("", 0, 200) : query;
        return transport.get(
                "/api/v1/projects/" + HttpTransport.enc(projectId)
                        + "/signals/catalog?q=" + HttpTransport.enc(q.query())
                        + "&sourceType=" + HttpTransport.enc(q.sourceType())
                        + "&sourceFile=" + HttpTransport.enc(q.sourceFile())
                        + "&offset=" + q.offset()
                        + "&limit=" + q.limit(),
                SignalCatalogPageDto.class
        );
    }

    @Override
    public SignalSamplePageDto samples(String projectId, String signalKey, int offset, int limit) {
        return transport.get(
                "/api/v1/projects/" + HttpTransport.enc(projectId)
                        + "/signals/" + HttpTransport.enc(signalKey)
                        + "/samples?offset=" + offset + "&limit=" + limit,
                SignalSamplePageDto.class
        );
    }

    @Override
    public SignalSamplePageDto range(String projectId, String signalKey, long fromNanos, long toNanos, int limit) {
        return transport.get(
                "/api/v1/projects/" + HttpTransport.enc(projectId)
                        + "/signals/" + HttpTransport.enc(signalKey)
                        + "/range?from=" + fromNanos + "&to=" + toNanos + "&limit=" + limit,
                SignalSamplePageDto.class
        );
    }

    @Override
    public SignalViewportResultDto viewport(String projectId, SignalViewportRequestDto request) {
        return transport.post(
                "/api/v1/projects/" + HttpTransport.enc(projectId) + "/signals/viewport",
                request,
                SignalViewportResultDto.class
        );
    }

    @Override
    public SignalStatsResultDto viewportStats(String projectId, SignalViewportRequestDto request) {
        return transport.post(
                "/api/v1/projects/" + HttpTransport.enc(projectId) + "/signals/viewport-stats",
                request,
                SignalStatsResultDto.class
        );
    }
}
