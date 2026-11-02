package com.stellar.platform.server.web;

import com.stellar.platform.api.StellarPlatformClient;
import com.stellar.platform.api.dto.BeginUploadRequest;
import com.stellar.platform.api.dto.FileAssetDto;
import com.stellar.platform.api.dto.UploadSessionDto;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Objects;

@RestController
@Profile("!decoder")
@RequestMapping("/api/v1/projects/{projectId}/assets")
public class FileAssetController {

    private final StellarPlatformClient client;

    public FileAssetController(StellarPlatformClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @PostMapping("/uploads")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public UploadSessionDto begin(
            @PathVariable("projectId") String projectId,
            @RequestBody BeginUploadRequest request
    ) {
        return client.assets().beginUpload(projectId, request);
    }

    @PutMapping("/uploads/{uploadSessionId}/chunks/{chunkIndex}")
    public UploadSessionDto putChunk(
            @PathVariable("projectId") String projectId,
            @PathVariable("uploadSessionId") String uploadSessionId,
            @PathVariable("chunkIndex") int chunkIndex,
            @RequestBody byte[] body
    ) {
        return client.assets().putChunk(projectId, uploadSessionId, chunkIndex, body == null ? new byte[0] : body);
    }

    @GetMapping("/uploads/{uploadSessionId}")
    public UploadSessionDto status(
            @PathVariable("projectId") String projectId,
            @PathVariable("uploadSessionId") String uploadSessionId
    ) {
        return client.assets().getUploadStatus(projectId, uploadSessionId);
    }

    @PostMapping("/uploads/{uploadSessionId}/complete")
    public FileAssetDto complete(
            @PathVariable("projectId") String projectId,
            @PathVariable("uploadSessionId") String uploadSessionId
    ) {
        try {
            return client.assets().completeUpload(projectId, uploadSessionId);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    @PostMapping("/uploads/{uploadSessionId}/cancel")
    public UploadSessionDto cancel(
            @PathVariable("projectId") String projectId,
            @PathVariable("uploadSessionId") String uploadSessionId
    ) {
        return client.assets().cancelUpload(projectId, uploadSessionId);
    }

    @GetMapping("/{fileAssetId}")
    public FileAssetDto get(
            @PathVariable("projectId") String projectId,
            @PathVariable("fileAssetId") String fileAssetId
    ) {
        return client.assets().getAsset(projectId, fileAssetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Asset not found"));
    }
}
