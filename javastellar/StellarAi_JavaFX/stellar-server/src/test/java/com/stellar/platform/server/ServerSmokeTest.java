package com.stellar.platform.server;

import com.stellar.platform.api.dto.CreateProjectRequest;
import com.stellar.platform.api.dto.ProjectDto;
import com.stellar.platform.api.support.ApiJson;
import com.stellar.platform.processing.ParserRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServerSmokeTest {

    private static final Path DATA_ROOT;

    static {
        try {
            DATA_ROOT = Files.createTempDirectory("stellar-server-test-" + UUID.randomUUID());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("stellar.server.data-root", () -> DATA_ROOT.toAbsolutePath().toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ParserRegistry parserRegistry;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void contextLoadsWithDiscoveredParsers() {
        assertNotNull(parserRegistry);
        assertTrue(parserRegistry.providers().size() >= 3,
                "Server classpath should include MF4, ASC, and BLF parser providers");
    }

    @Test
    void createAndGetProjectViaRest() throws Exception {
        CreateProjectRequest request = new CreateProjectRequest("REST Project");
        ResponseEntity<String> createdRaw = rest.postForEntity(
                "http://localhost:" + port + "/api/v1/projects",
                request,
                String.class
        );
        assertEquals(HttpStatus.CREATED, createdRaw.getStatusCode(), createdRaw.getBody());
        ProjectDto created = ApiJson.mapper().readValue(createdRaw.getBody(), ProjectDto.class);
        assertEquals("REST Project", created.name());
        assertFalse(created.projectId().isBlank());

        ResponseEntity<String> fetchedRaw = rest.getForEntity(
                "http://localhost:" + port + "/api/v1/projects/" + created.projectId(),
                String.class
        );
        assertEquals(HttpStatus.OK, fetchedRaw.getStatusCode(), fetchedRaw.getBody());
        ProjectDto fetched = ApiJson.mapper().readValue(fetchedRaw.getBody(), ProjectDto.class);
        assertEquals(created.projectId(), fetched.projectId());
    }

    @Test
    void openApiDocsArePublished() {
        ResponseEntity<String> docs = rest.getForEntity(
                "http://localhost:" + port + "/v3/api-docs",
                String.class
        );
        assertEquals(HttpStatus.OK, docs.getStatusCode(), docs.getBody());
        assertNotNull(docs.getBody());
        assertTrue(docs.getBody().contains("\"openapi\""), docs.getBody());
        assertTrue(docs.getBody().contains("/api/v1/projects"), docs.getBody());
    }
}
