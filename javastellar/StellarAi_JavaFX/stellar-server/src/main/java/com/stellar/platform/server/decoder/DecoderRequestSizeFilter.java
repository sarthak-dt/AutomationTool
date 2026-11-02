package com.stellar.platform.server.decoder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(name = "stellar.decoder.validation-api", havingValue = "true")
public class DecoderRequestSizeFilter extends OncePerRequestFilter {

    private final DecoderValidationProperties properties;

    public DecoderRequestSizeFilter(DecoderValidationProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if ("POST".equalsIgnoreCase(request.getMethod())
                && "/api/v1/decoder/validate".equals(request.getRequestURI())) {
            long length = request.getContentLengthLong();
            if (length > properties.getMaxRequestBytes()) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                String body = """
                        {"schemaVersion":"1.0","status":"FAILED","applicationVersion":"%s","error":{"code":"INVALID_REQUEST","message":"Request body exceeds the configured limit.","details":{}}}
                        """.formatted(properties.getApplicationVersion()).trim();
                response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
