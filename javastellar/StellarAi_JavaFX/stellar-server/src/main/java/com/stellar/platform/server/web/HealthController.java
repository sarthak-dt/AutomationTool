package com.stellar.platform.server.web;

import com.stellar.platform.api.support.MetadataStoreFactory;
import com.stellar.platform.api.support.PlatformMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Cheap liveness / readiness / version probes. No deep dependency checks.
 */
@RestController
@Profile("!decoder")
public class HealthController {

    private final MetadataStoreFactory stores;
    private final String appVersion;

    public HealthController(
            MetadataStoreFactory stores,
            @Value("${stellar.server.version:0.1.0-SNAPSHOT}") String appVersion
    ) {
        this.stores = Objects.requireNonNull(stores, "stores");
        this.appVersion = appVersion == null || appVersion.isBlank() ? "0.1.0-SNAPSHOT" : appVersion;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @GetMapping("/ready")
    public Map<String, Object> ready() {
        boolean dataRootOk = Files.isDirectory(stores.dataRoot()) || Files.exists(stores.dataRoot());
        try {
            Files.createDirectories(stores.dataRoot());
            dataRootOk = true;
        } catch (Exception ignored) {
            dataRootOk = false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", dataRootOk ? "READY" : "NOT_READY");
        body.put("dataRootWritable", dataRootOk);
        body.put("metrics", PlatformMetrics.get().snapshot());
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    @GetMapping("/version")
    public Map<String, Object> version() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", appVersion);
        body.put("name", "stellar-server");
        body.put("java", System.getProperty("java.version", ""));
        return body;
    }
}
