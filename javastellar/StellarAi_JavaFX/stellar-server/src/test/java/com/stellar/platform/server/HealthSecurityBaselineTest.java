package com.stellar.platform.server;

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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthSecurityBaselineTest {

    private static final Path DATA_ROOT;

    static {
        try {
            DATA_ROOT = Files.createTempDirectory("stellar-health-" + UUID.randomUUID());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("stellar.server.data-root", () -> DATA_ROOT.toAbsolutePath().toString());
        registry.add("stellar.security.auth-required", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void healthReadyAndVersionAreCheapAndUp() {
        ResponseEntity<Map> health = rest.getForEntity(url("/health"), Map.class);
        assertEquals(HttpStatus.OK, health.getStatusCode());
        assertEquals("UP", health.getBody().get("status"));

        ResponseEntity<Map> ready = rest.getForEntity(url("/ready"), Map.class);
        assertEquals(HttpStatus.OK, ready.getStatusCode());
        assertEquals("READY", ready.getBody().get("status"));
        assertNotNull(ready.getBody().get("metrics"));

        ResponseEntity<Map> version = rest.getForEntity(url("/version"), Map.class);
        assertEquals(HttpStatus.OK, version.getStatusCode());
        assertTrue(String.valueOf(version.getBody().get("version")).contains("0.1.0"));
        assertEquals("stellar-server", version.getBody().get("name"));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
