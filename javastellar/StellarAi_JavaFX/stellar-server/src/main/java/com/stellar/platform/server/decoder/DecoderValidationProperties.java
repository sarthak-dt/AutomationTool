package com.stellar.platform.server.decoder;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime limits for the decoder validation API.
 * {@code allowedRoots} is a semicolon-separated list so Windows drive letters stay intact.
 */
@ConfigurationProperties(prefix = "stellar.decoder")
public class DecoderValidationProperties {

    private boolean validationApi;
    private String applicationVersion = "1.0.4";
    private int maxPageSize = 10_000;
    private int defaultPageSize = 10_000;
    private int cursorTtlSeconds = 1_800;
    private int maxConcurrentDecodes = 1;
    private int maxSnapshots = 8;
    private long maxDecodedSamples = 5_000_000L;
    private int maxSignals = 100_000;
    private int maxRequestBytes = 1_048_576;
    private int cleanupIntervalSeconds = 30;
    private long decodeStartDelayMs;
    /** Age before an unowned session directory may be removed. Owned dead sessions are not delayed. */
    private long unownedSessionMinAgeSeconds = 86_400;
    private String allowedRoots = "";
    private String metadataDb = "";
    private String sessionDirectory = "";
    private String datetimeZone = "Asia/Kolkata";
    private ZoneId datetimeZoneId = ZoneId.of("Asia/Kolkata");

    public boolean isValidationApi() {
        return validationApi;
    }

    public void setValidationApi(boolean validationApi) {
        this.validationApi = validationApi;
    }

    public String getApplicationVersion() {
        return applicationVersion;
    }

    public void setApplicationVersion(String applicationVersion) {
        this.applicationVersion = applicationVersion;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public int getDefaultPageSize() {
        return defaultPageSize;
    }

    public void setDefaultPageSize(int defaultPageSize) {
        this.defaultPageSize = defaultPageSize;
    }

    public int getCursorTtlSeconds() {
        return cursorTtlSeconds;
    }

    public void setCursorTtlSeconds(int cursorTtlSeconds) {
        this.cursorTtlSeconds = cursorTtlSeconds;
    }

    public int getMaxConcurrentDecodes() {
        return maxConcurrentDecodes;
    }

    public void setMaxConcurrentDecodes(int maxConcurrentDecodes) {
        this.maxConcurrentDecodes = maxConcurrentDecodes;
    }

    public int getMaxSnapshots() {
        return maxSnapshots;
    }

    public void setMaxSnapshots(int maxSnapshots) {
        this.maxSnapshots = maxSnapshots;
    }

    public long getMaxDecodedSamples() {
        return maxDecodedSamples;
    }

    public void setMaxDecodedSamples(long maxDecodedSamples) {
        this.maxDecodedSamples = maxDecodedSamples;
    }

    public int getMaxSignals() {
        return maxSignals;
    }

    public void setMaxSignals(int maxSignals) {
        this.maxSignals = maxSignals;
    }

    public int getMaxRequestBytes() {
        return maxRequestBytes;
    }

    public void setMaxRequestBytes(int maxRequestBytes) {
        this.maxRequestBytes = maxRequestBytes;
    }

    public int getCleanupIntervalSeconds() {
        return cleanupIntervalSeconds;
    }

    public void setCleanupIntervalSeconds(int cleanupIntervalSeconds) {
        this.cleanupIntervalSeconds = cleanupIntervalSeconds;
    }

    public long getDecodeStartDelayMs() {
        return decodeStartDelayMs;
    }

    public void setDecodeStartDelayMs(long decodeStartDelayMs) {
        this.decodeStartDelayMs = decodeStartDelayMs;
    }

    public long getUnownedSessionMinAgeSeconds() {
        return unownedSessionMinAgeSeconds;
    }

    public void setUnownedSessionMinAgeSeconds(long unownedSessionMinAgeSeconds) {
        this.unownedSessionMinAgeSeconds = unownedSessionMinAgeSeconds;
    }

    public String getAllowedRoots() {
        return allowedRoots;
    }

    public void setAllowedRoots(String allowedRoots) {
        this.allowedRoots = allowedRoots == null ? "" : allowedRoots;
    }

    public String getMetadataDb() {
        return metadataDb;
    }

    public void setMetadataDb(String metadataDb) {
        this.metadataDb = metadataDb == null ? "" : metadataDb;
    }

    public String getSessionDirectory() {
        return sessionDirectory;
    }

    public void setSessionDirectory(String sessionDirectory) {
        this.sessionDirectory = sessionDirectory == null ? "" : sessionDirectory;
    }

    public String getDatetimeZone() {
        return datetimeZone;
    }

    /**
     * Retained for older launch scripts. {@code sourceDatetime} does not use this zone.
     * An unrecognized id fails configuration binding so the API does not start with a guessed zone.
     */
    public void setDatetimeZone(String datetimeZone) {
        if (datetimeZone == null || datetimeZone.isBlank()) {
            this.datetimeZone = "Asia/Kolkata";
            this.datetimeZoneId = ZoneId.of("Asia/Kolkata");
            return;
        }
        String trimmed = datetimeZone.trim();
        try {
            this.datetimeZoneId = ZoneId.of(trimmed);
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException(
                    "stellar.decoder.datetime-zone is not a valid time zone: " + trimmed, ex);
        }
        this.datetimeZone = this.datetimeZoneId.getId();
    }

    public ZoneId datetimeZoneId() {
        return datetimeZoneId;
    }

    public List<Path> allowedRootPaths() {
        List<Path> roots = new ArrayList<>();
        for (String part : allowedRoots.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                roots.add(Path.of(trimmed));
            }
        }
        return List.copyOf(roots);
    }
}
