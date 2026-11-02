package com.stellar.platform.server.decoder;

import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured failure for {@code POST /api/v1/decoder/validate}.
 * The HTTP body never includes a stack trace or a caller-supplied filesystem path.
 */
public final class DecoderValidationException extends RuntimeException {

    public static final String SCHEMA_VERSION = "1.0";

    private final String code;
    private final HttpStatus status;
    private final Map<String, Object> details;

    public DecoderValidationException(String code, HttpStatus status, String message) {
        this(code, status, message, Map.of());
    }

    public DecoderValidationException(String code, HttpStatus status, String message, Map<String, Object> details) {
        super(message == null || message.isBlank() ? code : message);
        this.code = code;
        this.status = status;
        this.details = Map.copyOf(details == null ? Map.of() : details);
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, Object> details() {
        return details;
    }

    public Map<String, Object> body(String applicationVersion) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", getMessage());
        error.put("details", details);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemaVersion", SCHEMA_VERSION);
        body.put("status", "FAILED");
        body.put("applicationVersion", applicationVersion == null ? "" : applicationVersion);
        body.put("error", error);
        return body;
    }
}
