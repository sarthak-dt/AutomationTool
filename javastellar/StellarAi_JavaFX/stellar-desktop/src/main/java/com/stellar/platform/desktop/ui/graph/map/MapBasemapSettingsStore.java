package com.stellar.platform.desktop.ui.graph.map;

import com.stellar.platform.storage.analysis.AnalysisStorageRoots;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Application-level map settings. Independent of the open project and of GPS mapping.
 *
 * <p>Self-hosted keys ({@code selfHostedUrl}, {@code selfHostedAttribution},
 * {@code selfHostedMaxZoom}) are the production English path. {@code englishTileUrl} is kept as a
 * compatible alias.
 */
public final class MapBasemapSettingsStore {

    public static final String FILE_NAME = "map-settings.properties";

    static final String KEY_LANGUAGE = "language";
    static final String KEY_BOUNDARY = "boundaryProfile";
    static final String KEY_PROVIDER = "provider";
    static final String KEY_ENGLISH_MAP_ID = "englishMapId";
    static final String KEY_ENGLISH_TILE_URL = "englishTileUrl";
    static final String KEY_ENGLISH_ATTRIBUTION = "englishAttribution";
    static final String KEY_ENGLISH_MAX_ZOOM = "englishMaxZoom";
    static final String KEY_SELF_HOSTED_URL = "selfHostedUrl";
    static final String KEY_SELF_HOSTED_ATTRIBUTION = "selfHostedAttribution";
    static final String KEY_SELF_HOSTED_MAX_ZOOM = "selfHostedMaxZoom";
    static final String KEY_ALLOW_STANDARD_FALLBACK = "allowStandardFallback";

    private final Path settingsFile;

    public MapBasemapSettingsStore(Path settingsFile) {
        this.settingsFile = settingsFile == null ? null : settingsFile.toAbsolutePath().normalize();
    }

    /** Resolves {@code StellarAIAnalysis/config} on each call once Analysis Storage is configured. */
    public static MapBasemapSettingsStore production() {
        return new MapBasemapSettingsStore(null);
    }

    public Path settingsFile() {
        Path file = liveFile();
        if (file == null) {
            throw new IllegalStateException("Analysis Storage is not configured");
        }
        return file;
    }

    private Path liveFile() {
        if (settingsFile != null) {
            return settingsFile;
        }
        if (!AnalysisStorageRoots.isConfigured()) {
            return null;
        }
        return AnalysisStorageRoots.uiPreferencesFile(FILE_NAME);
    }

    public MapBasemapSettings load() {
        MapBasemapSettings defaults = MapBasemapSettings.defaults();
        Path file = liveFile();
        if (file == null || !Files.isRegularFile(file)) {
            return overlaySystemProperties(defaults);
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (IOException ignored) {
            return overlaySystemProperties(defaults);
        }
        int maxZoom = parseZoom(firstNonBlank(
                properties.getProperty(KEY_SELF_HOSTED_MAX_ZOOM, ""),
                properties.getProperty(KEY_ENGLISH_MAX_ZOOM, "")), defaults.englishMaxZoom());
        MapBasemapSettings loaded = new MapBasemapSettings(
                MapLanguage.fromPersisted(properties.getProperty(KEY_LANGUAGE)),
                MapBoundaryProfile.fromPersisted(properties.getProperty(KEY_BOUNDARY)),
                properties.getProperty(KEY_PROVIDER, MapProviderIds.AUTO),
                properties.getProperty(KEY_ENGLISH_MAP_ID, ""),
                firstNonBlank(
                        properties.getProperty(KEY_SELF_HOSTED_URL, ""),
                        properties.getProperty(KEY_ENGLISH_TILE_URL, "")),
                firstNonBlank(
                        properties.getProperty(KEY_SELF_HOSTED_ATTRIBUTION, ""),
                        properties.getProperty(KEY_ENGLISH_ATTRIBUTION, "")),
                maxZoom,
                parseBoolean(properties.getProperty(KEY_ALLOW_STANDARD_FALLBACK), true));
        return overlaySystemProperties(loaded);
    }

    public void save(MapBasemapSettings settings) throws IOException {
        MapBasemapSettings safe = settings == null ? MapBasemapSettings.defaults() : settings;
        if (safe.boundaryProfile() == MapBoundaryProfile.INDIA_OFFICIAL
                && !MapBoundaryProfile.indiaOfficialSourceConfigured()) {
            safe = safe.withBoundaryProfile(MapBoundaryProfile.PROVIDER_DEFAULT);
        }
        Path file = liveFile();
        if (file == null) {
            throw new IOException("Analysis Storage is not configured");
        }
        Files.createDirectories(file.getParent());
        Properties properties = new Properties();
        properties.setProperty(KEY_LANGUAGE, safe.language().name());
        properties.setProperty(KEY_BOUNDARY, safe.boundaryProfile().name());
        properties.setProperty(KEY_PROVIDER, safe.providerPreference());
        properties.setProperty(KEY_ENGLISH_MAP_ID, safe.englishMapId());
        properties.setProperty(KEY_SELF_HOSTED_URL, safe.selfHostedUrl());
        properties.setProperty(KEY_ENGLISH_TILE_URL, safe.selfHostedUrl());
        properties.setProperty(KEY_SELF_HOSTED_ATTRIBUTION, safe.selfHostedAttribution());
        properties.setProperty(KEY_ENGLISH_ATTRIBUTION, safe.selfHostedAttribution());
        properties.setProperty(KEY_SELF_HOSTED_MAX_ZOOM, Integer.toString(safe.selfHostedMaxZoom()));
        properties.setProperty(KEY_ENGLISH_MAX_ZOOM, Integer.toString(safe.selfHostedMaxZoom()));
        properties.setProperty(KEY_ALLOW_STANDARD_FALLBACK, Boolean.toString(safe.allowStandardFallback()));
        try (OutputStream out = Files.newOutputStream(file)) {
            properties.store(out, "StellarAI map settings (not secrets)");
        }
    }

    private static MapBasemapSettings overlaySystemProperties(MapBasemapSettings base) {
        String mapId = firstNonBlank(
                System.getProperty("stellarai.map.englishMapId"),
                base.englishMapId());
        String tileUrl = firstNonBlank(
                System.getProperty("stellarai.map.selfHostedUrl"),
                System.getProperty("stellarai.map.english.tile.url"),
                base.selfHostedUrl());
        String attribution = firstNonBlank(
                System.getProperty("stellarai.map.selfHostedAttribution"),
                base.selfHostedAttribution());
        int maxZoom = parseZoom(
                System.getProperty("stellarai.map.selfHostedMaxZoom"),
                base.selfHostedMaxZoom());
        boolean allowStandardFallback = parseBoolean(
                System.getProperty("stellarai.map.allowStandardFallback"),
                base.allowStandardFallback());
        return new MapBasemapSettings(
                base.language(),
                base.boundaryProfile(),
                base.providerPreference(),
                mapId,
                tileUrl,
                attribution,
                maxZoom,
                allowStandardFallback);
    }

    static boolean parseBoolean(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim();
        if ("false".equalsIgnoreCase(value) || "0".equals(value) || "no".equalsIgnoreCase(value)) {
            return false;
        }
        if ("true".equalsIgnoreCase(value) || "1".equals(value) || "yes".equalsIgnoreCase(value)) {
            return true;
        }
        return fallback;
    }

    private static int parseZoom(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback < 1 ? MapBasemapSettings.DEFAULT_MAX_ZOOM : fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback < 1 ? MapBasemapSettings.DEFAULT_MAX_ZOOM : fallback;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
