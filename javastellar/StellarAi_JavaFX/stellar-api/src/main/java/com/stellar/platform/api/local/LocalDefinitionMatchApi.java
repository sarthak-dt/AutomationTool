package com.stellar.platform.api.local;

import com.stellar.platform.api.DefinitionMatchApi;
import com.stellar.platform.api.assets.FileAssetStore;
import com.stellar.platform.api.dto.ChannelMappingDto;
import com.stellar.platform.api.dto.ChannelMatchDto;
import com.stellar.platform.api.dto.DefinitionMatchRequest;
import com.stellar.platform.api.dto.DefinitionMatchResultDto;
import com.stellar.platform.api.dto.UpdateChannelMappingRequest;
import com.stellar.platform.api.support.MetadataStoreFactory;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.inventory.CanChannelMatchSummary;
import com.stellar.platform.domain.inventory.CanDefinitionMatchSummary;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.processing.inventory.CanDefinitionMatchingService;
import com.stellar.platform.storage.metadata.ChannelMappingMetadata;
import com.stellar.platform.storage.metadata.SavedProjectSnapshot;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class LocalDefinitionMatchApi implements DefinitionMatchApi {

    private final MetadataStoreFactory stores;
    private final CanDefinitionMatchingService matchingService;
    private final DbcParser dbcParser;
    private final FileAssetStore assets;

    public LocalDefinitionMatchApi(MetadataStoreFactory stores, FileAssetStore assets) {
        this(stores, new CanDefinitionMatchingService(), new DbcParser(), assets);
    }

    public LocalDefinitionMatchApi(
            MetadataStoreFactory stores,
            CanDefinitionMatchingService matchingService,
            DbcParser dbcParser,
            FileAssetStore assets
    ) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.matchingService = Objects.requireNonNull(matchingService, "matchingService");
        this.dbcParser = Objects.requireNonNull(dbcParser, "dbcParser");
        this.assets = Objects.requireNonNull(assets, "assets");
    }

    @Override
    public DefinitionMatchResultDto match(String projectId, DefinitionMatchRequest request) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(request, "request");
        SavedProjectSnapshot snapshot = stores.store().loadProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown project: " + projectId));
        CanSourceInventory inventory = snapshot.inventories().get(request.sourceId());
        if (inventory == null) {
            throw new IllegalArgumentException("No inventory for source: " + request.sourceId());
        }
        Path definitionPath = assets.resolveReadablePath(projectId, request.definitionAssetId());
        DbcDatabase dbc;
        try {
            dbc = dbcParser.parse(definitionPath);
        } catch (com.stellar.platform.dbc.parse.DbcParseException e) {
            throw new IllegalArgumentException("DBC parse failed: " + e.getMessage(), e);
        }
        CanDefinitionMatchSummary summary = matchingService.match(inventory, dbc);
        List<ChannelMatchDto> channels = new ArrayList<>();
        for (CanChannelMatchSummary ch : summary.perChannelResults()) {
            double coverage = ch.observedCount() == 0
                    ? 0.0
                    : (ch.matchedCount() * 1.0) / ch.observedCount();
            ChannelMatchDto channelDto = new ChannelMatchDto(
                    ch.channelId(),
                    ch.displayName(),
                    ch.observedCount(),
                    ch.matchedCount(),
                    coverage,
                    summary.matchingStatus().name(),
                    request.definitionAssetId()
            );
            channels.add(channelDto);
            stores.store().upsertChannelMapping(new ChannelMappingMetadata(
                    projectId,
                    request.sourceId(),
                    ch.channelId(),
                    summary.matchingStatus().name(),
                    summary.matchingStatus().name(),
                    request.definitionAssetId(),
                    request.definitionAssetId(),
                    ch.observedCount(),
                    ch.matchedCount(),
                    coverage
            ));
        }
        double globalCoverage = summary.globalObservedUniqueCount() == 0
                ? 0.0
                : (summary.globalMatchedUniqueCount() * 1.0) / summary.globalObservedUniqueCount();
        return new DefinitionMatchResultDto(
                projectId,
                request.sourceId(),
                request.definitionAssetId(),
                summary.globalObservedUniqueCount(),
                summary.globalMatchedUniqueCount(),
                globalCoverage,
                channels
        );
    }

    @Override
    public List<ChannelMappingDto> listMappings(String projectId) {
        return stores.store().loadProject(projectId)
                .map(SavedProjectSnapshot::channelMappings)
                .orElse(List.of())
                .stream()
                .map(LocalDefinitionMatchApi::toDto)
                .toList();
    }

    @Override
    public ChannelMappingDto updateMapping(
            String projectId,
            String sourceId,
            int channelId,
            UpdateChannelMappingRequest request
    ) {
        ChannelMappingMetadata metadata = new ChannelMappingMetadata(
                projectId,
                sourceId,
                channelId,
                request.status(),
                request.recommendationKind(),
                request.recommendedDefinitionId(),
                request.mappedDefinitionId(),
                request.observedUniqueMessageCount(),
                request.matchedUniqueMessageCount(),
                request.coverageRatio()
        );
        stores.store().upsertChannelMapping(metadata);
        return toDto(metadata);
    }

    private static ChannelMappingDto toDto(ChannelMappingMetadata metadata) {
        return new ChannelMappingDto(
                metadata.projectId(),
                metadata.sourceId(),
                metadata.channelId(),
                metadata.status(),
                metadata.recommendationKind(),
                metadata.recommendedDefinitionId() == null ? "" : metadata.recommendedDefinitionId(),
                metadata.mappedDefinitionId() == null ? "" : metadata.mappedDefinitionId(),
                metadata.observedUniqueMessageCount(),
                metadata.matchedUniqueMessageCount(),
                metadata.coverageRatio()
        );
    }
}
