package com.stellar.platform.storage.metadata;

import com.stellar.platform.domain.inventory.CanSourceInventory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Complete metadata-only project snapshot used to reopen a saved desktop project.
 * Decoded signal samples are intentionally not part of this snapshot.
 */
public record SavedProjectSnapshot(
        ProjectMetadata project,
        List<ProjectFileMetadata> files,
        Map<String, CanSourceInventory> inventories,
        List<ChannelMappingMetadata> channelMappings,
        List<ProcessingRunMetadata> processingRuns,
        List<ProcessingArtifactMetadata> processingArtifacts,
        List<SignalCatalogMetadata> signalCatalog,
        List<EngineeringBookmarkMetadata> bookmarks,
        List<DataQualityFindingMetadata> dataQualityFindings
) {
    public SavedProjectSnapshot(
            ProjectMetadata project,
            List<ProjectFileMetadata> files,
            Map<String, CanSourceInventory> inventories,
            List<ChannelMappingMetadata> channelMappings,
            List<ProcessingRunMetadata> processingRuns
    ) {
        this(project, files, inventories, channelMappings, processingRuns, List.of(), List.of(), List.of(), List.of());
    }

    public SavedProjectSnapshot(
            ProjectMetadata project,
            List<ProjectFileMetadata> files,
            Map<String, CanSourceInventory> inventories,
            List<ChannelMappingMetadata> channelMappings,
            List<ProcessingRunMetadata> processingRuns,
            List<ProcessingArtifactMetadata> processingArtifacts
    ) {
        this(project, files, inventories, channelMappings, processingRuns, processingArtifacts,
                List.of(), List.of(), List.of());
    }

    public SavedProjectSnapshot {
        project = Objects.requireNonNull(project, "project");
        files = List.copyOf(files == null ? List.of() : files);
        inventories = Map.copyOf(inventories == null ? Map.of() : inventories);
        channelMappings = List.copyOf(channelMappings == null ? List.of() : channelMappings);
        processingRuns = List.copyOf(processingRuns == null ? List.of() : processingRuns);
        processingArtifacts = List.copyOf(processingArtifacts == null ? List.of() : processingArtifacts);
        signalCatalog = List.copyOf(signalCatalog == null ? List.of() : signalCatalog);
        bookmarks = List.copyOf(bookmarks == null ? List.of() : bookmarks);
        dataQualityFindings = List.copyOf(dataQualityFindings == null ? List.of() : dataQualityFindings);
    }
}
