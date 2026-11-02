package com.stellar.platform.server.web;

import com.stellar.platform.api.StellarPlatformClient;
import com.stellar.platform.api.dto.ChannelMappingDto;
import com.stellar.platform.api.dto.DefinitionMatchRequest;
import com.stellar.platform.api.dto.DefinitionMatchResultDto;
import com.stellar.platform.api.dto.UpdateChannelMappingRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@RestController
@Profile("!decoder")
@RequestMapping("/api/v1/projects/{projectId}")
public class DefinitionMatchController {

    private final StellarPlatformClient client;

    public DefinitionMatchController(StellarPlatformClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @PostMapping("/definitions/match")
    public DefinitionMatchResultDto match(
            @PathVariable("projectId") String projectId,
            @RequestBody DefinitionMatchRequest request
    ) {
        return client.definitions().match(projectId, request);
    }

    @GetMapping("/mappings")
    public List<ChannelMappingDto> listMappings(@PathVariable("projectId") String projectId) {
        return client.definitions().listMappings(projectId);
    }

    @PutMapping("/mappings/{sourceId}/{channelId}")
    public ChannelMappingDto updateMapping(
            @PathVariable("projectId") String projectId,
            @PathVariable("sourceId") String sourceId,
            @PathVariable("channelId") int channelId,
            @RequestBody UpdateChannelMappingRequest request
    ) {
        return client.definitions().updateMapping(projectId, sourceId, channelId, request);
    }
}
