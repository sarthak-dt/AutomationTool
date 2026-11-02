package com.stellar.platform.storage.api;

import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.storage.metadata.ChannelMappingMetadata;
import com.stellar.platform.storage.metadata.DataQualityFindingMetadata;
import com.stellar.platform.storage.metadata.HistoricalDbcMappingSummary;

import java.util.List;

/** Inventory, channel mappings, and historical DBC knowledge queries. */
public interface InventoryRepository {
    void saveInventory(String projectId, String sourceId, CanSourceInventory inventory);

    void upsertChannelMapping(ChannelMappingMetadata mapping);

    void deleteChannelMapping(String projectId, String sourceId, int channelId);

    /**
     * Historical confirmed/manual mappings that covered similar observed CAN identities.
     * Supporting evidence only — never auto-confirms a DBC.
     */
    default List<HistoricalDbcMappingSummary> findHistoricalDbcMappings(
            String excludeProjectId,
            List<Long> observedCanIds
    ) {
        return List.of();
    }

    default void upsertDataQualityFinding(DataQualityFindingMetadata finding) {
    }

    default List<DataQualityFindingMetadata> listDataQualityFindings(String projectId) {
        return List.of();
    }

    default void deleteDataQualityFindingsForSource(String projectId, String sourceId) {
    }
}
