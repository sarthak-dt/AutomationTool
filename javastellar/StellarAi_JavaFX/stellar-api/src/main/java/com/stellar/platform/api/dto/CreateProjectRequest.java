package com.stellar.platform.api.dto;

import com.stellar.platform.api.identity.PlatformIds;

/**
 * Create or ensure a logical project. When {@code projectId} is provided, Local and HTTP
 * adapters MUST use that id (idempotent if the project already exists) so one StellarAI
 * project keeps the same id across compute targets.
 */
public record CreateProjectRequest(String name, String projectRoot, String projectId) {
    public CreateProjectRequest {
        name = name == null || name.isBlank() ? "Untitled Project" : name.trim();
        projectRoot = projectRoot == null ? "" : projectRoot.trim();
        projectId = PlatformIds.optionalId("projectId", projectId);
    }

    public CreateProjectRequest(String name) {
        this(name, "", "");
    }

    public CreateProjectRequest(String name, String projectRoot) {
        this(name, projectRoot, "");
    }
}
