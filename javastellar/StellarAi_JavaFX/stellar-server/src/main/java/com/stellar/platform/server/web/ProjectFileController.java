package com.stellar.platform.server.web;

import com.stellar.platform.api.StellarPlatformClient;
import com.stellar.platform.api.dto.ProjectFileDto;
import com.stellar.platform.api.dto.RegisterFileRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

@RestController
@Profile("!decoder")
@RequestMapping("/api/v1/projects/{projectId}/files")
public class ProjectFileController {

    private final StellarPlatformClient client;

    public ProjectFileController(StellarPlatformClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @GetMapping
    public List<ProjectFileDto> list(@PathVariable("projectId") String projectId) {
        return client.files().listFiles(projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectFileDto register(@PathVariable("projectId") String projectId, @RequestBody RegisterFileRequest request) {
        return client.files().registerFile(projectId, request);
    }

    @DeleteMapping("/{fileId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("projectId") String projectId, @PathVariable("fileId") String fileId) {
        client.files().deleteFile(projectId, fileId);
    }
}
