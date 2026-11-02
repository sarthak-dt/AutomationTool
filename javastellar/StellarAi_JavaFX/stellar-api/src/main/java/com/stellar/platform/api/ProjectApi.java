package com.stellar.platform.api;

import com.stellar.platform.api.dto.CreateProjectRequest;
import com.stellar.platform.api.dto.ProjectDto;
import com.stellar.platform.api.dto.ProjectSummaryDto;

import java.util.List;
import java.util.Optional;

/**
 * Project lifecycle contract. Implementations must not expose JavaFX or Spring types.
 */
public interface ProjectApi {

    ProjectDto createProject(CreateProjectRequest request);

    Optional<ProjectDto> getProject(String projectId);

    List<ProjectSummaryDto> listProjects();

    void closeProject(String projectId);
}
