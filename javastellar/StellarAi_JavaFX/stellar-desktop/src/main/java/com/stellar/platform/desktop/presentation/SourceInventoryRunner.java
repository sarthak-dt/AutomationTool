package com.stellar.platform.desktop.presentation;

import com.stellar.platform.api.FileAssetApi;
import com.stellar.platform.api.InventoryApi;
import com.stellar.platform.api.StellarPlatformClient;
import com.stellar.platform.api.assets.FileAssetUploader;
import com.stellar.platform.api.dto.FileAssetDto;
import com.stellar.platform.api.dto.InventoryJobRequest;
import com.stellar.platform.api.dto.InventoryResultDto;
import com.stellar.platform.api.dto.JobDto;
import com.stellar.platform.api.dto.JobStatusDto;
import com.stellar.platform.api.support.InventoryDomainMapper;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.inventory.SourceInventoryProgressListener;
import com.stellar.platform.processing.inventory.SourceInventoryService;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Abstraction over {@link SourceInventoryService} for orchestration/tests.
 * LOCAL uses in-process inventory; REMOTE uploads/registers an asset then polls inventory jobs.
 */
@FunctionalInterface
public interface SourceInventoryRunner {

    CanSourceInventory inventory(
            String sourceId,
            Path measurementPath,
            ReaderContext readerContext,
            SourceInventoryProgressListener progressListener
    );

    static SourceInventoryRunner of(SourceInventoryService service) {
        Objects.requireNonNull(service, "service");
        return (sourceId, path, context, progressListener) ->
                service.inventory(path, context, progressListener);
    }

    static SourceInventoryRunner remote(StellarPlatformClient client, Supplier<String> projectIdSupplier) {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(projectIdSupplier, "projectIdSupplier");
        InventoryApi inventoryApi = client.inventory();
        FileAssetApi assets = client.assets();
        return (sourceId, path, context, progressListener) -> {
            String projectId = projectIdSupplier.get();
            if (projectId == null || projectId.isBlank()) {
                throw new IllegalStateException("REMOTE inventory requires a project id");
            }
            String sid = sourceId == null || sourceId.isBlank() ? path.getFileName().toString() : sourceId;
            FileAssetDto asset = FileAssetUploader.ensureMeasurementAsset(
                    assets, projectId, sid, path, false);
            int batch = context == null ? 512 : context.batchSize();
            JobDto job = inventoryApi.startInventoryJob(
                    projectId,
                    new InventoryJobRequest(sid, asset.fileAssetId(), batch)
            );
            SourceInventoryProgressListener progress = progressListener == null
                    ? SourceInventoryProgressListener.NOOP
                    : progressListener;
            while (true) {
                JobDto status = inventoryApi.getInventoryJob(projectId, job.jobId());
                if (status.status() == JobStatusDto.QUEUED || status.status() == JobStatusDto.RUNNING) {
                    try {
                        Thread.sleep(200L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("REMOTE inventory interrupted", e);
                    }
                    continue;
                }
                if (status.status() == JobStatusDto.FAILED) {
                    throw new IllegalStateException("REMOTE inventory failed: " + status.message());
                }
                InventoryResultDto dto = inventoryApi.getSourceInventory(projectId, sid)
                        .orElseThrow(() -> new IllegalStateException("REMOTE inventory result missing"));
                CanSourceInventory domain = InventoryDomainMapper.toDomain(dto);
                progress.onInventoryUpdated(domain);
                return domain;
            }
        };
    }
}
