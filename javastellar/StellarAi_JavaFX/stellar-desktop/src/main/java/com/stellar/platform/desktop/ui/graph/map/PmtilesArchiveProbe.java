package com.stellar.platform.desktop.ui.graph.map;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads the PMTiles v3 header without loading tile data. Used to refuse vector archives.
 *
 * <p>Layout (spec v3): magic at 0, version at 7, tile type at 99, min zoom at 100, max zoom at 101.
 * Tile type 1 = MVT, 2 = PNG, 3 = JPEG, 4 = WebP, 5 = AVIF.
 */
public final class PmtilesArchiveProbe {

    public static final int HEADER_BYTES = 127;
    private static final String MAGIC = "PMTiles";

    public record Snapshot(OfflineMapTileFormat tileFormat, int minZoom, int maxZoom) {
        public Snapshot {
            tileFormat = tileFormat == null ? OfflineMapTileFormat.UNKNOWN : tileFormat;
            minZoom = Math.max(0, minZoom);
            maxZoom = Math.max(0, maxZoom);
        }

        public static Snapshot unknown() {
            return new Snapshot(OfflineMapTileFormat.UNKNOWN, 0, 0);
        }
    }

    private PmtilesArchiveProbe() {
    }

    public static Snapshot probe(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return Snapshot.unknown();
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] header = in.readNBytes(HEADER_BYTES);
            return parse(header);
        } catch (IOException ignored) {
            return Snapshot.unknown();
        }
    }

    static Snapshot parse(byte[] header) {
        if (header == null || header.length < 102) {
            return Snapshot.unknown();
        }
        String magic = new String(header, 0, Math.min(7, header.length), StandardCharsets.US_ASCII);
        if (!MAGIC.equals(magic)) {
            return Snapshot.unknown();
        }
        int version = header[7] & 0xFF;
        if (version != 3) {
            return Snapshot.unknown();
        }
        int tileType = header[99] & 0xFF;
        int minZoom = header[100] & 0xFF;
        int maxZoom = header[101] & 0xFF;
        OfflineMapTileFormat format = switch (tileType) {
            case 1 -> OfflineMapTileFormat.VECTOR;
            case 2, 3, 4, 5 -> OfflineMapTileFormat.RASTER;
            default -> OfflineMapTileFormat.UNKNOWN;
        };
        return new Snapshot(format, minZoom, maxZoom);
    }

    public static OfflineMapTileFormat resolveFormat(
            OfflineMapTileFormat sidecar, Snapshot header, boolean englishTagged) {
        OfflineMapTileFormat declared = sidecar == null ? OfflineMapTileFormat.UNKNOWN : sidecar;
        Snapshot snap = Objects.requireNonNullElse(header, Snapshot.unknown());
        if (snap.tileFormat() == OfflineMapTileFormat.VECTOR || declared == OfflineMapTileFormat.VECTOR) {
            return OfflineMapTileFormat.VECTOR;
        }
        if (declared == OfflineMapTileFormat.RASTER || snap.tileFormat() == OfflineMapTileFormat.RASTER) {
            return OfflineMapTileFormat.RASTER;
        }
        if (englishTagged) {
            return OfflineMapTileFormat.RASTER;
        }
        return OfflineMapTileFormat.UNKNOWN;
    }
}
