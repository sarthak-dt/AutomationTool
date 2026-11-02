package com.stellar.platform.desktop.ui.graph.map;

/**
 * Lightweight JS tile-health query. LiveConnect events are hints; this snapshot is the
 * auditable source of truth for whether the visible Leaflet tile set actually loaded.
 *
 * <p>Pipe-separated, in order: generation, layerExists, current, complete, validNaturalWidth,
 * failed, visible, tileFirst, tileDone, mapUsable.
 */
record TileHealthSnapshot(
        int generation,
        boolean layerExists,
        int currentTiles,
        int completeTiles,
        int validNaturalWidth,
        int failedTiles,
        int visibleTiles,
        boolean tileFirst,
        boolean tileDone,
        boolean mapUsable
) {

    static TileHealthSnapshot parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.split("\\|", -1);
        if (parts.length < 10) {
            return null;
        }
        try {
            return new TileHealthSnapshot(
                    Integer.parseInt(parts[0].trim()),
                    flag(parts[1]),
                    Integer.parseInt(parts[2].trim()),
                    Integer.parseInt(parts[3].trim()),
                    Integer.parseInt(parts[4].trim()),
                    Integer.parseInt(parts[5].trim()),
                    Integer.parseInt(parts[6].trim()),
                    flag(parts[7]),
                    flag(parts[8]),
                    flag(parts[9]));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    boolean healthy() {
        return layerExists
                && currentTiles > 0
                && completeTiles == currentTiles
                && validNaturalWidth == currentTiles
                && failedTiles == 0
                && tileDone;
    }

    boolean partiallyLoaded() {
        if (!layerExists || currentTiles <= 0) {
            return false;
        }
        if (healthy()) {
            return false;
        }
        return validNaturalWidth > 0
                && (validNaturalWidth < currentTiles || failedTiles > 0 || !tileDone);
    }

    boolean loading() {
        return layerExists && !tileDone && !healthy() && !partiallyLoaded();
    }

    boolean providerFailed() {
        return failedTiles >= 4 && validNaturalWidth == 0;
    }

    private static boolean flag(String raw) {
        String value = raw == null ? "" : raw.trim();
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }
}
