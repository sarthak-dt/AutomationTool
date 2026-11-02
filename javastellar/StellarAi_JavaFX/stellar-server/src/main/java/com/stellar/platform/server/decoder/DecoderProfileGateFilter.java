package com.stellar.platform.server.decoder;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The decoder profile serves one route. Other paths, including the standard server API,
 * are rejected here so they are not reachable on this process.
 */
@Component
@Profile("decoder")
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@ConditionalOnProperty(name = "stellar.decoder.validation-api", havingValue = "true")
public class DecoderProfileGateFilter extends OncePerRequestFilter {

    static final String PATH = "/api/v1/decoder/validate";

    private final DecoderValidationProperties properties;

    public DecoderProfileGateFilter(DecoderValidationProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri != null && uri.length() > 1 && uri.endsWith("/")) {
            uri = uri.substring(0, uri.length() - 1);
        }
        if (PATH.equals(uri)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String body = """
                {"schemaVersion":"1.0","status":"FAILED","applicationVersion":"%s","error":{"code":"INVALID_REQUEST","message":"That path is not available in the decoder profile.","details":{}}}
                """.formatted(properties.getApplicationVersion()).trim();
        response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
    }
}
