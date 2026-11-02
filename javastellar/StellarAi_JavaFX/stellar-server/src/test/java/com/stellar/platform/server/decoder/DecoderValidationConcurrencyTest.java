package com.stellar.platform.server.decoder;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DecoderValidationConcurrencyTest {

    private static final Path ROOT = create("decoder-concurrent-");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("stellar.decoder.validation-api", () -> "true");
        registry.add("stellar.decoder.application-version", () -> "1.0.4");
        registry.add("stellar.decoder.metadata-db", () -> ROOT.resolve("database").resolve("stellar.db").toString());
        registry.add("stellar.decoder.allowed-roots", () -> ROOT.toString());
        registry.add("stellar.decoder.session-directory", () -> ROOT.resolve("sessions").toString());
        registry.add("stellar.decoder.max-concurrent-decodes", () -> "1");
        registry.add("stellar.decoder.decode-start-delay-ms", () -> "2000");
        registry.add("stellar.decoder.max-page-size", () -> "1000");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void files() throws Exception {
        Files.createDirectories(ROOT.resolve("sessions"));
        Files.writeString(ROOT.resolve("one.dbc"), """
                VERSION ""
                BU_: ECU
                BO_ 256 EngineData: 8 ECU
                 SG_ EngineSpeed : 0|16@1+ (1,0) [0|65535] "rpm" ECU
                """);
        Files.writeString(ROOT.resolve("one.asc"), """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  0.100000 1 100 Rx d 2 10 00
                """);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (Files.exists(ROOT)) {
            try (var paths = Files.walk(ROOT)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                        // best-effort
                    }
                });
            }
        }
    }

    @Test
    void aSecondDecodeIsRejectedWhileOneIsRunning() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> first = pool.submit(this::post);
            Future<ResponseEntity<String>> second = pool.submit(this::post);
            ResponseEntity<String> a = first.get();
            ResponseEntity<String> b = second.get();
            int limited = 0;
            int ok = 0;
            for (ResponseEntity<String> response : java.util.List.of(a, b)) {
                if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                    limited++;
                    assertTrue(response.getBody().contains("RESOURCE_LIMIT"));
                } else if (response.getStatusCode() == HttpStatus.OK) {
                    ok++;
                }
            }
            assertEquals(1, ok, a.getBody() + "\n" + b.getBody());
            assertEquals(1, limited, a.getBody() + "\n" + b.getBody());
        } finally {
            pool.shutdownNow();
        }
    }

    private ResponseEntity<String> post() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = mapper.writeValueAsString(Map.of(
                "filePath", ROOT.resolve("one.asc").toString(),
                "dbcPath", ROOT.resolve("one.dbc").toString()));
        return rest.exchange("http://127.0.0.1:" + port + "/api/v1/decoder/validate",
                HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private static Path create(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DecoderValidationShutdownTest {

    private static final Path ROOT = create("decoder-shutdown-");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("stellar.decoder.validation-api", () -> "true");
        registry.add("stellar.decoder.application-version", () -> "1.0.4");
        registry.add("stellar.decoder.metadata-db", () -> ROOT.resolve("missing.db").toString());
        registry.add("stellar.decoder.allowed-roots", () -> ROOT.toString());
        registry.add("stellar.decoder.session-directory", () -> ROOT.resolve("sessions").toString());
        registry.add("stellar.decoder.max-concurrent-decodes", () -> "1");
        registry.add("stellar.decoder.decode-start-delay-ms", () -> "4000");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private DecoderValidationService service;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void files() throws Exception {
        Files.createDirectories(ROOT.resolve("sessions"));
        Files.writeString(ROOT.resolve("one.dbc"), """
                VERSION ""
                BU_: ECU
                BO_ 256 EngineData: 8 ECU
                 SG_ EngineSpeed : 0|16@1+ (1,0) [0|65535] "rpm" ECU
                """);
        Files.writeString(ROOT.resolve("one.asc"), """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  0.100000 1 100 Rx d 2 10 00
                """);
    }

    @Test
    void shutdownCancelsAnInFlightDecode() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<ResponseEntity<String>> running = pool.submit(() -> {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                String json = mapper.writeValueAsString(Map.of(
                        "filePath", ROOT.resolve("one.asc").toString(),
                        "dbcPath", ROOT.resolve("one.dbc").toString()));
                return rest.exchange("http://127.0.0.1:" + port + "/api/v1/decoder/validate",
                        HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
            });
            Thread.sleep(300);
            service.shutdown();
            ResponseEntity<String> response = running.get();
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(), response.getBody());
            assertTrue(response.getBody().contains("INTERNAL_ERROR"));
            assertFalseCompleted(response.getBody());
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertFalseCompleted(String body) {
        assertTrue(body == null || !body.contains("\"status\":\"COMPLETED\""));
    }

    private static Path create(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}
