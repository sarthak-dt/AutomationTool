package com.stellar.platform.api;

import com.stellar.platform.api.dto.CreateProjectRequest;
import com.stellar.platform.api.dto.ProjectDto;
import com.stellar.platform.api.identity.PlatformIds;
import com.stellar.platform.api.local.LocalStellarPlatformClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SameProjectIdBoundaryTest {

    @TempDir
    Path temp;

    @Test
    void createProjectUsesClientProvidedIdIdempotently() {
        String projectId = PlatformIds.newId();
        LocalStellarPlatformClient client = LocalStellarPlatformClient.createDefault(temp);
        try {
            ProjectDto first = client.projects().createProject(
                    new CreateProjectRequest("Vehicle A", "", projectId));
            ProjectDto second = client.projects().createProject(
                    new CreateProjectRequest("Vehicle A", "", projectId));
            assertEquals(projectId, first.projectId());
            assertEquals(projectId, second.projectId());
            assertTrue(client.projects().getProject(projectId).isPresent());
        } finally {
            client.close();
        }
    }
}
