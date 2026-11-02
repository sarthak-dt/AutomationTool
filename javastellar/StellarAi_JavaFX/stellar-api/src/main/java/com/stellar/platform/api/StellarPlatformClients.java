package com.stellar.platform.api;

import com.stellar.platform.api.http.HttpStellarPlatformClient;
import com.stellar.platform.api.local.LocalStellarPlatformClient;
import com.stellar.platform.processing.ExecutionMode;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Builds LOCAL or REMOTE {@link StellarPlatformClient} instances from execution mode /
 * system properties ({@code stellar.execution.mode}, {@code stellar.api.baseUrl}).
 */
public final class StellarPlatformClients {

    public static final String PROP_MODE = "stellar.execution.mode";
    public static final String PROP_BASE_URL = "stellar.api.baseUrl";
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8080";

    private StellarPlatformClients() {
    }

    public static ExecutionMode resolveMode() {
        String raw = System.getProperty(PROP_MODE, ExecutionMode.LOCAL.name());
        try {
            return ExecutionMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            return ExecutionMode.LOCAL;
        }
    }

    public static String resolveBaseUrl() {
        return System.getProperty(PROP_BASE_URL, DEFAULT_BASE_URL);
    }

    public static StellarPlatformClient create(Path localDataRoot) {
        return create(resolveMode(), localDataRoot, resolveBaseUrl());
    }

    public static StellarPlatformClient create(ExecutionMode mode, Path localDataRoot, String baseUrl) {
        Objects.requireNonNull(mode, "mode");
        return switch (mode) {
            case LOCAL -> LocalStellarPlatformClient.createDefault(
                    Objects.requireNonNull(localDataRoot, "localDataRoot"));
            case REMOTE -> new HttpStellarPlatformClient(
                    baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl);
        };
    }
}
