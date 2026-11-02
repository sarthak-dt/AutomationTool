package com.stellar.platform.api.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.stellar.platform.api.ProjectApi;
import com.stellar.platform.api.dto.CreateProjectRequest;
import com.stellar.platform.api.dto.ProjectDto;
import com.stellar.platform.api.dto.ProjectSummaryDto;

import java.util.List;
import java.util.Optional;

public final class HttpProjectApi implements ProjectApi {

    private final HttpTransport transport;

    public HttpProjectApi(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public ProjectDto createProject(CreateProjectRequest request) {
        return transport.post("/api/v1/projects", request, ProjectDto.class);
    }

    @Override
    public Optional<ProjectDto> getProject(String projectId) {
        try {
            return Optional.ofNullable(transport.get("/api/v1/projects/" + HttpTransport.enc(projectId), ProjectDto.class));
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("HTTP 404")) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public List<ProjectSummaryDto> listProjects() {
        return transport.get("/api/v1/projects", new TypeReference<>() {
        });
    }

    @Override
    public void closeProject(String projectId) {
        transport.post("/api/v1/projects/" + HttpTransport.enc(projectId) + "/close", null, Void.class);
    }
}
