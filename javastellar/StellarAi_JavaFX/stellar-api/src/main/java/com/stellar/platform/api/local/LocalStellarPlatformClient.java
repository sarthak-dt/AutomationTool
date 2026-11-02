package com.stellar.platform.api.local;

import com.stellar.platform.api.DefinitionMatchApi;
import com.stellar.platform.api.FileAssetApi;
import com.stellar.platform.api.InventoryApi;
import com.stellar.platform.api.ProcessingApi;
import com.stellar.platform.api.ProjectApi;
import com.stellar.platform.api.ProjectFileApi;
import com.stellar.platform.api.SignalQueryApi;
import com.stellar.platform.api.StellarPlatformClient;
import com.stellar.platform.api.assets.FileAssetStore;
import com.stellar.platform.api.support.InMemoryJobTracker;
import com.stellar.platform.api.support.MetadataStoreFactory;
import com.stellar.platform.api.support.ProcessSessionRegistry;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.inventory.SourceInventoryService;

import java.nio.file.Path;
import java.util.Objects;

/** LOCAL in-process client wrapping processing-core and storage-core. */
public final class LocalStellarPlatformClient implements StellarPlatformClient, AutoCloseable {

    private final ProjectApi projects;
    private final ProjectFileApi files;
    private final FileAssetApi assets;
    private final InventoryApi inventory;
    private final DefinitionMatchApi definitions;
    private final ProcessingApi processing;
    private final SignalQueryApi signals;
    private final MetadataStoreFactory stores;
    private final FileAssetStore assetStore;
    private final LocalProcessingApi localProcessing;
    private final LocalInventoryApi localInventory;

    public LocalStellarPlatformClient(
            MetadataStoreFactory stores,
            ParserRegistry parserRegistry,
            InMemoryJobTracker jobs,
            ProcessSessionRegistry sessions
    ) {
        this(stores, parserRegistry, jobs, sessions, FileAssetStore.DEFAULT_MAX_UPLOAD_BYTES);
    }

    public LocalStellarPlatformClient(
            MetadataStoreFactory stores,
            ParserRegistry parserRegistry,
            InMemoryJobTracker jobs,
            ProcessSessionRegistry sessions,
            long maxUploadBytes
    ) {
        this.stores = Objects.requireNonNull(stores, "stores");
        Objects.requireNonNull(parserRegistry, "parserRegistry");
        Objects.requireNonNull(jobs, "jobs");
        Objects.requireNonNull(sessions, "sessions");
        this.assetStore = new FileAssetStore(
                stores.dataRoot(),
                FileAssetStore.DEFAULT_CHUNK_SIZE_BYTES,
                maxUploadBytes
        );
        SourceInventoryService inventoryService = new SourceInventoryService(parserRegistry);
        MeasurementDecodePipeline pipeline = new MeasurementDecodePipeline(parserRegistry);
        this.projects = new LocalProjectApi(stores);
        this.files = new LocalProjectFileApi(stores);
        this.assets = new LocalFileAssetApi(assetStore, stores);
        this.localInventory = new LocalInventoryApi(stores, inventoryService, jobs, assetStore);
        this.inventory = localInventory;
        this.definitions = new LocalDefinitionMatchApi(stores, assetStore);
        this.localProcessing = new LocalProcessingApi(pipeline, jobs, sessions, assetStore, stores);
        this.processing = localProcessing;
        this.signals = new LocalSignalQueryApi(sessions);
    }

    public static LocalStellarPlatformClient createDefault(Path dataRoot) {
        return new LocalStellarPlatformClient(
                new MetadataStoreFactory(dataRoot),
                ParserRegistry.load(),
                new InMemoryJobTracker(),
                new ProcessSessionRegistry()
        );
    }

    public MetadataStoreFactory stores() {
        return stores;
    }

    public FileAssetStore assetStore() {
        return assetStore;
    }

    @Override
    public void close() {
        try {
            localProcessing.shutdown();
        } finally {
            try {
                localInventory.shutdown();
            } finally {
                stores.close();
            }
        }
    }

    @Override
    public ProjectApi projects() {
        return projects;
    }

    @Override
    public ProjectFileApi files() {
        return files;
    }

    @Override
    public FileAssetApi assets() {
        return assets;
    }

    @Override
    public InventoryApi inventory() {
        return inventory;
    }

    @Override
    public DefinitionMatchApi definitions() {
        return definitions;
    }

    @Override
    public ProcessingApi processing() {
        return processing;
    }

    @Override
    public SignalQueryApi signals() {
        return signals;
    }
}
