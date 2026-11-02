package com.stellar.platform.api;

import com.stellar.platform.api.dto.CreateProjectRequest;
import com.stellar.platform.api.dto.ProjectDto;
import com.stellar.platform.api.local.LocalStellarPlatformClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProjectApiTest {

    @TempDir
    Path temp;

    @Test
    void createAndListProjects() {
        LocalStellarPlatformClient client = LocalStellarPlatformClient.createDefault(temp);
        try {
            ProjectDto created = client.projects().createProject(new CreateProjectRequest("API Slice"));
            assertFalse(created.projectId().isBlank());
            assertEquals("API Slice", created.name());
            assertTrue(client.projects().getProject(created.projectId()).isPresent());
            List<?> listed = client.projects().listProjects();
            assertFalse(listed.isEmpty());
        } finally {
            client.close();
        }
    }
}
