package com.stellar.platform.server.web;

import com.stellar.platform.api.StellarPlatformClient;
import com.stellar.platform.api.dto.CreateProjectRequest;
import com.stellar.platform.api.dto.ProjectDto;
import com.stellar.platform.api.dto.ProjectSummaryDto;
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

import java.util.List;
import java.util.Objects;

@RestController
@Profile("!decoder")
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final StellarPlatformClient client;

    public ProjectController(StellarPlatformClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @GetMapping
    public List<ProjectSummaryDto> list() {
        return client.projects().listProjects();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectDto create(@RequestBody CreateProjectRequest request) {
        return client.projects().createProject(request == null ? new CreateProjectRequest("Untitled Project") : request);
    }

    @GetMapping("/{projectId}")
    public ProjectDto get(@PathVariable("projectId") String projectId) {
        return client.projects().getProject(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));
    }

    @PostMapping("/{projectId}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@PathVariable("projectId") String projectId) {
        client.projects().closeProject(projectId);
    }
}
