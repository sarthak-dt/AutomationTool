package com.stellar.platform.server.decoder;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DecoderValidationLimitTest {

    private static final Path ROOT = create("decoder-limits-");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("stellar.decoder.validation-api", () -> "true");
        registry.add("stellar.decoder.application-version", () -> "1.0.4");
        registry.add("stellar.decoder.allowed-roots", () -> ROOT.toString());
        registry.add("stellar.decoder.session-directory", () -> ROOT.resolve("sessions").toString());
        registry.add("stellar.decoder.metadata-db", () -> ROOT.resolve("missing.db").toString());
        registry.add("stellar.decoder.max-page-size", () -> "10000");
        registry.add("stellar.decoder.default-page-size", () -> "10000");
        registry.add("stellar.decoder.max-decoded-samples", () -> "2");
        registry.add("stellar.decoder.max-request-bytes", () -> "2048");
        registry.add("stellar.decoder.cursor-ttl-seconds", () -> "1");
        registry.add("stellar.decoder.max-concurrent-decodes", () -> "1");
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
        Files.writeString(ROOT.resolve("two.asc"), """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  0.100000 1 100 Rx d 2 10 00
                  0.200000 1 100 Rx d 2 20 00
                """);
        Files.writeString(ROOT.resolve("one.asc"), """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  0.100000 1 100 Rx d 2 10 00
                """);
        Files.writeString(ROOT.resolve("three.asc"), """
                date Thu Jan 01 12:00:00.000 am 2026
                base hex timestamps absolute
                  0.100000 1 100 Rx d 2 10 00
                  0.200000 1 100 Rx d 2 20 00
                  0.300000 1 100 Rx d 2 30 00
                """);
    }

    @Test
    void oversizedBodyAndPageSizeAndSampleLimitFailWithoutCompleting() throws Exception {
        ResponseEntity<String> hugePage = post(Map.of(
                "filePath", ROOT.resolve("one.asc").toString(),
                "dbcPath", ROOT.resolve("one.dbc").toString(),
                "pageSize", 10001));
        assertEquals(HttpStatus.BAD_REQUEST, hugePage.getStatusCode());
        assertTrue(hugePage.getBody().contains("INVALID_REQUEST"));
        assertNotCompleted(hugePage.getBody());

        String oversized = "{\"filePath\":\"" + ROOT.resolve("one.asc").toString().replace("\\", "\\\\")
                + "\",\"padding\":\"" + "x".repeat(3000) + "\"}";
        assertTrue(oversized.length() > 2048, oversized);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> tooBig = rest.exchange(url(), HttpMethod.POST,
                new HttpEntity<>(oversized, headers), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, tooBig.getStatusCode(), tooBig.getBody());
        assertTrue(tooBig.getBody().contains("INVALID_REQUEST"));
        assertNotCompleted(tooBig.getBody());

        ResponseEntity<String> limited = post(Map.of(
                "filePath", ROOT.resolve("three.asc").toString(),
                "dbcPath", ROOT.resolve("one.dbc").toString()));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, limited.getStatusCode(), limited.getBody());
        assertTrue(limited.getBody().contains("RESOURCE_LIMIT"));
        assertNotCompleted(limited.getBody());
    }

    @Test
    void cursorExpiresAtTheConfiguredTtl() throws Exception {
        ResponseEntity<String> first = post(Map.of(
                "filePath", ROOT.resolve("two.asc").toString(),
                "dbcPath", ROOT.resolve("one.dbc").toString(),
                "pageSize", 1));
        assertEquals(HttpStatus.OK, first.getStatusCode(), first.getBody());
        String cursor = mapper.readTree(first.getBody()).get("nextCursor").asText();
        assertFalse(mapper.readTree(first.getBody()).get("paginationComplete").asBoolean());
        Thread.sleep(1500);
        ResponseEntity<String> expired = post(Map.of("cursor", cursor, "pageSize", 1));
        assertEquals(HttpStatus.GONE, expired.getStatusCode(), expired.getBody());
        assertTrue(expired.getBody().contains("CURSOR_EXPIRED"));
        assertNotCompleted(expired.getBody());
    }

    private static void assertNotCompleted(String body) {
        assertFalse(body.contains("\"status\":\"COMPLETED\""));
    }

    private ResponseEntity<String> post(Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url(), HttpMethod.POST,
                new HttpEntity<>(mapper.writeValueAsString(body), headers), String.class);
    }

    private String url() {
        return "http://127.0.0.1:" + port + "/api/v1/decoder/validate";
    }

    private static Path create(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (Exception ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }
}
