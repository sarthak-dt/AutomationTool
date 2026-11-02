package com.stellar.platform.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

/**
 * Optional auth gate. When {@code stellar.security.auth-required=true}, rejects requests
 * without a passing {@link AuthHook}. Secrets from headers are never logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthHookFilter extends OncePerRequestFilter {

    private final AuthHook authHook;
    private final boolean authRequired;

    public AuthHookFilter(AuthHook authHook, SecurityBaselineProperties properties) {
        this.authHook = Objects.requireNonNull(authHook, "authHook");
        this.authRequired = properties.isAuthRequired();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && (path.equals("/health")
                || path.equals("/ready")
                || path.equals("/version")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui"));
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!authRequired) {
            filterChain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        if (authHook.allow(header)) {
            filterChain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthorized\"}");
    }
}
