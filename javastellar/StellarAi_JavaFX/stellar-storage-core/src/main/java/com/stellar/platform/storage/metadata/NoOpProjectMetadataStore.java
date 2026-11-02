package com.stellar.platform.storage.metadata;

import com.stellar.platform.domain.inventory.CanSourceInventory;

/** No-op implementation used by headless/unit-test service wiring. */
public enum NoOpProjectMetadataStore implements ProjectMetadataStore {
    INSTANCE;

    @Override public void upsertProject(ProjectMetadata project) { }
    @Override public void upsertFile(ProjectFileMetadata file) { }
    @Override public void deleteFile(String projectId, String fileId) { }
    @Override public void deleteProject(String projectId) { }
    @Override public void saveInventory(String projectId, String sourceId, CanSourceInventory inventory) { }
    @Override public void upsertChannelMapping(ChannelMappingMetadata mapping) { }
    @Override public void deleteChannelMapping(String projectId, String sourceId, int channelId) { }
    @Override public void upsertProcessingRun(ProcessingRunMetadata run) { }
}
