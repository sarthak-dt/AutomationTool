package com.stellar.platform.api.local;

import com.stellar.platform.api.InventoryApi;
import com.stellar.platform.api.assets.FileAssetStore;
import com.stellar.platform.api.dto.InventoryJobRequest;
import com.stellar.platform.api.dto.InventoryResultDto;
import com.stellar.platform.api.dto.JobDto;
import com.stellar.platform.api.dto.JobStatusDto;
import com.stellar.platform.api.support.InMemoryJobTracker;
import com.stellar.platform.api.support.InventoryDtoMapper;
import com.stellar.platform.api.support.MetadataStoreFactory;
import com.stellar.platform.api.worker.InProcessLocalWorker;
import com.stellar.platform.api.worker.LocalWorkerBoundary;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.inventory.SourceInventoryService;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

public final class LocalInventoryApi implements InventoryApi {

    private final MetadataStoreFactory stores;
    private final SourceInventoryService inventoryService;
    private final InMemoryJobTracker jobs;
    private final FileAssetStore assets;
    private final LocalWorkerBoundary worker;
    private final ExecutorService executor;
    private final ConcurrentHashMap<String, InventoryResultDto> results = new ConcurrentHashMap<>();

    public LocalInventoryApi(
            MetadataStoreFactory stores,
            SourceInventoryService inventoryService,
            InMemoryJobTracker jobs,
            FileAssetStore assets
    ) {
        this(stores, inventoryService, jobs, assets, InProcessLocalWorker.forInventory());
    }

    LocalInventoryApi(
            MetadataStoreFactory stores,
            SourceInventoryService inventoryService,
            InMemoryJobTracker jobs,
            FileAssetStore assets,
            LocalWorkerBoundary worker
    ) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.executor = worker instanceof InProcessLocalWorker inProcess
                ? inProcess.executor()
                : java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "stellar-api-inventory-fallback");
                    t.setDaemon(true);
                    return t;
                });
    }

    LocalInventoryApi(
            MetadataStoreFactory stores,
            SourceInventoryService inventoryService,
            InMemoryJobTracker jobs,
            FileAssetStore assets,
            ExecutorService executor
    ) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.inventoryService = Objects.requireNonNull(inventoryService, "inventoryService");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.assets = Objects.requireNonNull(assets, "assets");
        this.worker = null;
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void shutdown() {
        if (worker != null) {
            worker.shutdown();
        } else {
            com.stellar.platform.api.support.BoundedExecutors.shutdownQuietly(executor, "stellar-api-inventory", 5L);
        }
    }

    @Override
    public JobDto startInventoryJob(String projectId, InventoryJobRequest request) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(request, "request");
        JobDto job = jobs.create(projectId, "INVENTORY");
        jobs.update(projectId, job.jobId(), JobStatusDto.RUNNING, "Inventory started", 0.0);
        executor.execute(() -> runJob(projectId, job.jobId(), request));
        return jobs.get(projectId, job.jobId()).orElse(job);
    }

    @Override
    public JobDto getInventoryJob(String projectId, String jobId) {
        return jobs.get(projectId, jobId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown inventory job: " + jobId));
    }

    @Override
    public Optional<InventoryResultDto> getSourceInventory(String projectId, String sourceId) {
        InventoryResultDto cached = results.get(resultKey(projectId, sourceId));
        if (cached != null) {
            return Optional.of(cached);
        }
        return stores.store().loadProject(projectId)
                .flatMap(snap -> Optional.ofNullable(snap.inventories().get(sourceId)))
                .map(inv -> InventoryDtoMapper.toDto(projectId, sourceId, inv));
    }

    private void runJob(String projectId, String jobId, InventoryJobRequest request) {
        try {
            Path path = assets.resolveReadablePath(projectId, request.measurementAssetId());
            CanSourceInventory inventory = inventoryService.inventory(
                    path,
                    ReaderContext.ofBatchSize(request.batchSize()),
                    snap -> {
                        double progress = snap.channelDiscoveryComplete() ? 0.9 : 0.4;
                        jobs.update(projectId, jobId, JobStatusDto.RUNNING, snap.status().name(), progress);
                    }
            );
            stores.store().saveInventory(projectId, request.sourceId(), inventory);
            InventoryResultDto dto = InventoryDtoMapper.toDto(projectId, request.sourceId(), inventory);
            results.put(resultKey(projectId, request.sourceId()), dto);
            jobs.update(projectId, jobId, JobStatusDto.SUCCEEDED, inventory.status().name(), 1.0);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            jobs.update(projectId, jobId, JobStatusDto.FAILED, message, null);
        }
    }

    private static String resultKey(String projectId, String sourceId) {
        return projectId + "::" + sourceId;
    }
}
