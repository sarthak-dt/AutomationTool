package com.stellar.platform.server.web;

import com.stellar.platform.api.StellarPlatformClient;
import com.stellar.platform.api.dto.InventoryJobRequest;
import com.stellar.platform.api.dto.InventoryResultDto;
import com.stellar.platform.api.dto.JobDto;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

@RestController
@Profile("!decoder")
@RequestMapping("/api/v1/projects/{projectId}")
public class InventoryController {

    private final StellarPlatformClient client;

    public InventoryController(StellarPlatformClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @PostMapping("/inventory/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobDto start(@PathVariable("projectId") String projectId, @RequestBody InventoryJobRequest request) {
        return client.inventory().startInventoryJob(projectId, request);
    }

    @GetMapping("/inventory/jobs/{jobId}")
    public JobDto getJob(@PathVariable("projectId") String projectId, @PathVariable("jobId") String jobId) {
        return client.inventory().getInventoryJob(projectId, jobId);
    }

    @GetMapping("/sources/{sourceId}/inventory")
    public InventoryResultDto getInventory(@PathVariable("projectId") String projectId, @PathVariable("sourceId") String sourceId) {
        return client.inventory().getSourceInventory(projectId, sourceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Inventory not found"));
    }
}
