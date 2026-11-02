package com.stellar.platform.api.http;

import com.stellar.platform.api.InventoryApi;
import com.stellar.platform.api.dto.InventoryJobRequest;
import com.stellar.platform.api.dto.InventoryResultDto;
import com.stellar.platform.api.dto.JobDto;

import java.util.Optional;

public final class HttpInventoryApi implements InventoryApi {

    private final HttpTransport transport;

    public HttpInventoryApi(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public JobDto startInventoryJob(String projectId, InventoryJobRequest request) {
        return transport.post(
                "/api/v1/projects/" + HttpTransport.enc(projectId) + "/inventory/jobs",
                request,
                JobDto.class
        );
    }

    @Override
    public JobDto getInventoryJob(String projectId, String jobId) {
        return transport.get(
                "/api/v1/projects/" + HttpTransport.enc(projectId) + "/inventory/jobs/" + HttpTransport.enc(jobId),
                JobDto.class
        );
    }

    @Override
    public Optional<InventoryResultDto> getSourceInventory(String projectId, String sourceId) {
        try {
            return Optional.ofNullable(transport.get(
                    "/api/v1/projects/" + HttpTransport.enc(projectId) + "/sources/" + HttpTransport.enc(sourceId) + "/inventory",
                    InventoryResultDto.class
            ));
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
