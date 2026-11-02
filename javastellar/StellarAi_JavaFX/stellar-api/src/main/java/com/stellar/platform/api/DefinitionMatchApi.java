package com.stellar.platform.api;

import com.stellar.platform.api.dto.ChannelMappingDto;
import com.stellar.platform.api.dto.DefinitionMatchRequest;
import com.stellar.platform.api.dto.DefinitionMatchResultDto;
import com.stellar.platform.api.dto.UpdateChannelMappingRequest;

import java.util.List;

/** Inventory × DBC matching and persisted channel mappings. */
public interface DefinitionMatchApi {

    DefinitionMatchResultDto match(String projectId, DefinitionMatchRequest request);

    List<ChannelMappingDto> listMappings(String projectId);

    ChannelMappingDto updateMapping(String projectId, String sourceId, int channelId, UpdateChannelMappingRequest request);
}
