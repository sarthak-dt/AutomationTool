package com.stellar.platform.server.decoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = DecoderValidationController.class)
@ConditionalOnProperty(name = "stellar.decoder.validation-api", havingValue = "true")
public class DecoderValidationExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DecoderValidationExceptionHandler.class);

    private final DecoderValidationProperties properties;

    public DecoderValidationExceptionHandler(DecoderValidationProperties properties) {
        this.properties = properties;
    }

    @ExceptionHandler(DecoderValidationException.class)
    public ResponseEntity<Map<String, Object>> structured(DecoderValidationException ex) {
        if (ex.status().is5xxServerError()) {
            LOG.error("Decoder validation {}", ex.code(), ex);
        } else {
            LOG.info("Decoder validation {} {}", ex.code(), ex.getMessage());
        }
        return ResponseEntity.status(ex.status()).body(ex.body(properties.getApplicationVersion()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException ex) {
        LOG.info("Decoder validation rejected an unreadable body");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new DecoderValidationException(
                "INVALID_REQUEST", HttpStatus.BAD_REQUEST, "Request body is not valid JSON.")
                .body(properties.getApplicationVersion()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception ex) {
        LOG.error("Decoder validation failed", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new DecoderValidationException(
                "INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, "The decoder validation request failed.")
                .body(properties.getApplicationVersion()));
    }
}
