package com.stellar.platform.desktop.ui.graph.map;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Lightweight Configuration-page check. Does not open a project or load Map Route.
 */
public final class MapSourceHealthCheck {

    private static final Logger LOG = Logger.getLogger(MapSourceHealthCheck.class.getName());
    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    public record Result(boolean reachable, String userMessage) {
        public static Result ok() {
            return new Result(true, "Map source responded.");
        }

        public static Result fail(String message) {
            return new Result(false, message == null || message.isBlank()
                    ? MapBasemapUserMessages.ENGLISH_UNAVAILABLE
                    : message);
        }
    }

    private MapSourceHealthCheck() {
    }

    public static Result check(
            MapBasemapSettings settings,
            MapSecretStore secrets,
            OfflineMapPackRegistry packs) {
        return check(settings, secrets, packs, MapSourceHealthCheck::fetchSampleTile);
    }

    static Result check(
            MapBasemapSettings settings,
            MapSecretStore secrets,
            OfflineMapPackRegistry packs,
            Function<String, Boolean> tileFetch) {
        MapBasemapSettings current = Objects.requireNonNullElse(settings, MapBasemapSettings.defaults());
        Function<String, Boolean> fetch = tileFetch == null ? MapSourceHealthCheck::fetchSampleTile : tileFetch;

        if (current.prefers(MapProviderIds.SELFHOSTED_ENGLISH_RASTER)
                || (current.prefersAuto() && SelfHostedEnglishRasterProvider.tryCreate(current).isPresent())) {
            Optional<MapTileProvider> selfHosted = SelfHostedEnglishRasterProvider.tryCreate(current);
            if (selfHosted.isEmpty()) {
                return Result.fail("Self-hosted map URL is not configured.");
            }
            return probeProvider(selfHosted.get(), fetch);
        }
        if (current.prefers(MapProviderIds.MAPTILER_ENGLISH_RASTER)
                || current.prefersAuto()) {
            Optional<MapTileProvider> maptiler = MapTilerEnglishRasterProvider.tryCreate(
                    current, secrets == null ? MapSecretStore.empty() : secrets);
            if (maptiler.isPresent()) {
                return probeProvider(maptiler.get(), fetch);
            }
            if (current.prefers(MapProviderIds.MAPTILER_ENGLISH_RASTER)) {
                return Result.fail("Map source is not configured.");
            }
        }
        if (hasEnglishRasterPack(packs, current)) {
            return Result.ok();
        }
        return Result.fail(MapBasemapUserMessages.ENGLISH_UNAVAILABLE);
    }

    private static Result probeProvider(MapTileProvider provider, Function<String, Boolean> fetch) {
        String sample = sampleTileUrl(provider.tileUrlTemplate());
        LOG.fine(() -> "Map source check · " + provider.providerId()
                + " · " + MapSecretStore.redactForLog(sample));
        try {
            if (Boolean.TRUE.equals(fetch.apply(sample))) {
                return Result.ok();
            }
        } catch (RuntimeException ex) {
            LOG.fine(() -> "Map source check failed · " + provider.providerId());
        }
        return Result.fail(MapBasemapUserMessages.ENGLISH_UNAVAILABLE);
    }

    static String sampleTileUrl(String template) {
        if (template == null) {
            return "";
        }
        return template.replace("{z}", "0").replace("{x}", "0").replace("{y}", "0");
    }

    private static boolean hasEnglishRasterPack(OfflineMapPackRegistry packs, MapBasemapSettings settings) {
        if (packs == null) {
            return false;
        }
        MapBoundaryProfile profile = settings.tileMatchingProfile();
        List<OfflineMapPack> enabled = packs.listEnabled();
        for (OfflineMapPack pack : enabled) {
            if (pack.supportsEnglishLabels()
                    && pack.rasterCompatible()
                    && pack.matchesBoundary(profile)) {
                return true;
            }
        }
        return false;
    }

    static boolean fetchSampleTile(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "StellarAI-MapSourceCheck")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                return false;
            }
            byte[] body = response.body();
            return looksLikeImage(body, response.headers().firstValue("Content-Type").orElse(""));
        } catch (Exception ex) {
            return false;
        }
    }

    static boolean looksLikeImage(byte[] body, String contentType) {
        if (contentType != null) {
            String type = contentType.toLowerCase(java.util.Locale.ROOT);
            if (type.contains("image/png") || type.contains("image/jpeg") || type.contains("image/webp")) {
                return true;
            }
        }
        if (body == null || body.length < 8) {
            return false;
        }
        boolean png = body[0] == (byte) 0x89 && body[1] == 0x50 && body[2] == 0x4E && body[3] == 0x47;
        boolean jpeg = body[0] == (byte) 0xFF && body[1] == (byte) 0xD8;
        boolean webp = body.length > 11
                && body[0] == 'R' && body[1] == 'I' && body[2] == 'F' && body[3] == 'F'
                && body[8] == 'W' && body[9] == 'E' && body[10] == 'B' && body[11] == 'P';
        return png || jpeg || webp;
    }
}
