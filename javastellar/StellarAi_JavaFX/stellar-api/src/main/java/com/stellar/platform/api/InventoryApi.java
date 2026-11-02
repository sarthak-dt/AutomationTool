package com.stellar.platform.api;

import com.stellar.platform.api.dto.InventoryJobRequest;
import com.stellar.platform.api.dto.InventoryResultDto;
import com.stellar.platform.api.dto.JobDto;

import java.util.Optional;

/**
 * CAN source inventory. Long-running work is modeled as async jobs; LOCAL may complete
 * synchronously under the job handle.
 */
public interface InventoryApi {

    JobDto startInventoryJob(String projectId, InventoryJobRequest request);

    JobDto getInventoryJob(String projectId, String jobId);

    Optional<InventoryResultDto> getSourceInventory(String projectId, String sourceId);
}
