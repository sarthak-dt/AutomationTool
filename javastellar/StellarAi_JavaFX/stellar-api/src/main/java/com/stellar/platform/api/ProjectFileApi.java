package com.stellar.platform.api;

import com.stellar.platform.api.dto.ProjectFileDto;
import com.stellar.platform.api.dto.RegisterFileRequest;

import java.util.List;

/** Registers measurement/definition paths for a project (path-based; upload is out of scope). */
public interface ProjectFileApi {

    ProjectFileDto registerFile(String projectId, RegisterFileRequest request);

    List<ProjectFileDto> listFiles(String projectId);

    void deleteFile(String projectId, String fileId);
}
