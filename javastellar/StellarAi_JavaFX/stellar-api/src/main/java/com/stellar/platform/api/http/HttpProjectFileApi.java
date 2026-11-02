package com.stellar.platform.api.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.stellar.platform.api.ProjectFileApi;
import com.stellar.platform.api.dto.ProjectFileDto;
import com.stellar.platform.api.dto.RegisterFileRequest;

import java.util.List;

public final class HttpProjectFileApi implements ProjectFileApi {

    private final HttpTransport transport;

    public HttpProjectFileApi(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public ProjectFileDto registerFile(String projectId, RegisterFileRequest request) {
        return transport.post(
                "/api/v1/projects/" + HttpTransport.enc(projectId) + "/files",
                request,
                ProjectFileDto.class
        );
    }

    @Override
    public List<ProjectFileDto> listFiles(String projectId) {
        return transport.get(
                "/api/v1/projects/" + HttpTransport.enc(projectId) + "/files",
                new TypeReference<>() {
                }
        );
    }

    @Override
    public void deleteFile(String projectId, String fileId) {
        transport.delete("/api/v1/projects/" + HttpTransport.enc(projectId) + "/files/" + HttpTransport.enc(fileId));
    }
}
