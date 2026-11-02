package com.stellar.platform.api.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.stellar.platform.api.DefinitionMatchApi;
import com.stellar.platform.api.dto.ChannelMappingDto;
import com.stellar.platform.api.dto.DefinitionMatchRequest;
import com.stellar.platform.api.dto.DefinitionMatchResultDto;
import com.stellar.platform.api.dto.UpdateChannelMappingRequest;

import java.util.List;

public final class HttpDefinitionMatchApi implements DefinitionMatchApi {

    private final HttpTransport transport;

    public HttpDefinitionMatchApi(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public DefinitionMatchResultDto match(String projectId, DefinitionMatchRequest request) {
        return transport.post(
                "/api/v1/projects/" + HttpTransport.enc(projectId) + "/definitions/match",
                request,
                DefinitionMatchResultDto.class
        );
    }

    @Override
    public List<ChannelMappingDto> listMappings(String projectId) {
        return transport.get(
                "/api/v1/projects/" + HttpTransport.enc(projectId) + "/mappings",
                new TypeReference<>() {
                }
        );
    }

    @Override
    public ChannelMappingDto updateMapping(
            String projectId,
            String sourceId,
            int channelId,
            UpdateChannelMappingRequest request
    ) {
        return transport.put(
                "/api/v1/projects/" + HttpTransport.enc(projectId)
                        + "/mappings/" + HttpTransport.enc(sourceId) + "/" + channelId,
                request,
                ChannelMappingDto.class
        );
    }
}
