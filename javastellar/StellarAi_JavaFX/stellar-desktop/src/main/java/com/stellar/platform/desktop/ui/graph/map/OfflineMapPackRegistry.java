package com.stellar.platform.desktop.ui.graph.map;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Discovers {@code *.pmtiles} under the StellarAI application maps directory.
 *
 * <p>Default location: Analysis Storage {@code cache/maps} once configured
 * (override with {@code stellarai.maps.dir}).
 * Map packs are never stored inside project SQLite.
 *
 * <p>Optional sidecar {@code {id}.map.properties} declares English / boundary capabilities.
 * Missing sidecars are treated as not English-compliant.
 */
public final class OfflineMapPackRegistry {

    private static final Logger LOG = Logger.getLogger(OfflineMapPackRegistry.class.getName());

    private final Path mapsDirectory;

    public OfflineMapPackRegistry() {
        this(defaultMapsDirectory());
    }

    public OfflineMapPackRegistry(Path mapsDirectory) {
        this.mapsDirectory = Objects.requireNonNull(mapsDirectory, "mapsDirectory").toAbsolutePath().normalize();
    }

    public Path mapsDirectory() {
        return mapsDirectory;
    }

    public static Path defaultMapsDirectory() {
        String configured = System.getProperty("stellarai.maps.dir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured.trim()).toAbsolutePath().normalize();
        }
        if (com.stellar.platform.storage.analysis.AnalysisStorageRoots.isConfigured()) {
            return com.stellar.platform.storage.analysis.AnalysisStorageRoots.cacheRoot()
                    .resolve("maps")
                    .toAbsolutePath()
                    .normalize();
        }
        return Path.of(System.getProperty("user.home"), ".stellarai", "maps").toAbsolutePath().normalize();
    }

    public List<OfflineMapPack> listEnabled() {
        List<OfflineMapPack> packs = new ArrayList<>();
        if (!Files.isDirectory(mapsDirectory)) {
            return List.of();
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(mapsDirectory, "*.pmtiles")) {
            for (Path path : stream) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                String fileName = path.getFileName().toString();
                String id = fileName.substring(0, fileName.length() - ".pmtiles".length());
                packs.add(readPack(id, path));
            }
        } catch (IOException ex) {
            LOG.warning(() -> "Cannot scan offline maps in " + mapsDirectory + ": " + ex.getMessage());
        }
        packs.sort(Comparator.comparing(OfflineMapPack::displayName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(packs);
    }

    public Optional<OfflineMapPack> findCovering(double south, double west, double north, double east) {
        List<OfflineMapPack> enabled = listEnabled();
        for (OfflineMapPack pack : enabled) {
            if (pack.covers(south, west, north, east)) {
                return Optional.of(pack);
            }
        }
        return enabled.stream().findFirst();
    }

    private OfflineMapPack readPack(String id, Path path) {
        Path sidecar = path.resolveSibling(id + ".map.properties");
        if (!Files.isRegularFile(sidecar)) {
            return OfflineMapPack.untagged(id, humanize(id), path, "© Offline map pack · " + humanize(id),
                    null, null, null, null, true);
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(sidecar)) {
            properties.load(in);
        } catch (IOException ex) {
            LOG.fine(() -> "Offline pack sidecar unreadable " + sidecar + ": " + ex.getMessage());
            return OfflineMapPack.untagged(id, humanize(id), path, "© Offline map pack · " + humanize(id),
                    null, null, null, null, true);
        }
        boolean english = Boolean.parseBoolean(properties.getProperty("supportsEnglishLabels", "false"));
        MapBoundaryProfile profile = MapBoundaryProfile.fromPersisted(
                properties.getProperty("boundaryProfile", MapBoundaryProfile.PROVIDER_DEFAULT.name()));
        String attribution = properties.getProperty("attribution", "© Offline map pack · " + humanize(id));
        String styleId = properties.getProperty("styleId", properties.getProperty("providerStyleId", ""));
        String display = properties.getProperty("displayName", humanize(id));
        OfflineMapTileFormat sidecarFormat = OfflineMapTileFormat.fromSidecar(
                firstNonBlank(
                        properties.getProperty("tileFormat"),
                        properties.getProperty("format")));
        PmtilesArchiveProbe.Snapshot header = PmtilesArchiveProbe.probe(path);
        OfflineMapTileFormat format = PmtilesArchiveProbe.resolveFormat(sidecarFormat, header, english);
        int maxZoom = parseInt(properties.getProperty("maxZoom"), header.maxZoom());
        return new OfflineMapPack(
                id,
                display,
                path,
                attribution,
                parseDouble(properties.getProperty("minLatitude")),
                parseDouble(properties.getProperty("minLongitude")),
                parseDouble(properties.getProperty("maxLatitude")),
                parseDouble(properties.getProperty("maxLongitude")),
                true,
                english,
                profile,
                styleId,
                maxZoom,
                format);
    }

    private static Double parseDouble(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
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

    private static String humanize(String id) {
        return id.replace('_', ' ').replace('-', ' ').toLowerCase(Locale.ROOT);
    }
}
