package com.stellar.platform.server.decoder;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("decoder")
class DecoderProfileIsolationTest {

    private static final Path ROOT = create("decoder-profile-");
    private static final Path SESSIONS = ROOT.resolve("sessions");

    static {
        try {
            Files.createDirectories(SESSIONS);
            Path dead = SESSIONS.resolve("11111111111111111111111111111111");
            Files.createDirectories(dead);
            Files.writeString(dead.resolve(DecoderSessionRecovery.OWNER_FILE), """
                    schema=1
                    pid=2147483647
                    startedAt=1
                    """);
            Path live = SESSIONS.resolve("22222222222222222222222222222222");
            Files.createDirectories(live);
            Files.writeString(live.resolve(DecoderSessionRecovery.OWNER_FILE),
                    "schema=1\npid=" + ProcessHandle.current().pid()
                            + "\nstartedAt=" + ManagementFactory.getRuntimeMXBean().getStartTime() + "\n");
            Files.writeString(SESSIONS.resolve("notes.txt"), "keep");
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("stellar.decoder.validation-api", () -> "true");
        registry.add("stellar.decoder.session-directory", () -> SESSIONS.toString());
        registry.add("stellar.decoder.allowed-roots", () -> ROOT.toString());
        registry.add("stellar.decoder.metadata-db", () -> ROOT.resolve("missing.db").toString());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void decoderProfileHidesStandardRoutesAndRemovesAbandonedSessions() {
        assertFalse(Files.exists(SESSIONS.resolve("11111111111111111111111111111111")));
        assertTrue(Files.exists(SESSIONS.resolve("22222222222222222222222222222222")));
        assertTrue(Files.exists(SESSIONS.resolve("notes.txt")));

        assertEquals(HttpStatus.NOT_FOUND, get("/health").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get("/ready").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get("/version").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get("/api/v1/projects").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get("/v3/api-docs").getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, get("/swagger-ui.html").getStatusCode());
        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, get("/api/v1/decoder/validate").getStatusCode());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> rejected = rest.exchange(url("/api/v1/decoder/validate"), HttpMethod.POST,
                new HttpEntity<>("{}", headers), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        assertTrue(rejected.getBody().contains("INVALID_REQUEST"));
        assertFalse(rejected.getBody().contains("\"status\":\"COMPLETED\""));
    }

    private ResponseEntity<String> get(String path) {
        return rest.getForEntity(url(path), String.class);
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private static Path create(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}
