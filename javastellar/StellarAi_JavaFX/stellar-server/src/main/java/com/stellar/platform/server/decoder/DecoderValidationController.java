package com.stellar.platform.server.decoder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The only external decoder-validation route.
 * Job status, signal lists, processing, and pagination are not separate endpoints.
 */
@RestController
@ConditionalOnProperty(name = "stellar.decoder.validation-api", havingValue = "true")
public class DecoderValidationController {

    private final DecoderValidationService service;

    public DecoderValidationController(DecoderValidationService service) {
        this.service = service;
    }

    @PostMapping(path = "/api/v1/decoder/validate", consumes = "application/json", produces = "application/json")
    public Map<String, Object> validate(@RequestBody(required = false) Map<String, Object> body) {
        return service.validate(body);
    }
}
