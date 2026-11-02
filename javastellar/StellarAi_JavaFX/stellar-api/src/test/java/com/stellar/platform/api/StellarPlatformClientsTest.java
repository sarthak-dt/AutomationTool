package com.stellar.platform.api;

import com.stellar.platform.api.dto.CreateProjectRequest;
import com.stellar.platform.api.dto.ProjectDto;
import com.stellar.platform.api.http.HttpStellarPlatformClient;
import com.stellar.platform.api.local.LocalStellarPlatformClient;
import com.stellar.platform.processing.ExecutionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StellarPlatformClientsTest {

    @TempDir
    Path temp;

    @Test
    void localModeBuildsLocalClient() {
        StellarPlatformClient client = StellarPlatformClients.create(ExecutionMode.LOCAL, temp, "http://127.0.0.1:9");
        try {
            assertInstanceOf(LocalStellarPlatformClient.class, client);
            ProjectDto project = client.projects().createProject(new CreateProjectRequest("parity"));
            assertTrue(client.projects().getProject(project.projectId()).isPresent());
            assertEquals("parity", project.name());
        } finally {
            if (client instanceof LocalStellarPlatformClient local) {
                local.close();
            }
        }
    }

    @Test
    void remoteModeBuildsHttpClient() {
        StellarPlatformClient client = StellarPlatformClients.create(
                ExecutionMode.REMOTE,
                temp,
                "http://127.0.0.1:18080"
        );
        assertInstanceOf(HttpStellarPlatformClient.class, client);
    }
}
