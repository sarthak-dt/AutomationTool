package com.stellar.platform.desktop.presentation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Removes obsolete report-generated images from one report asset directory.
 * Only direct children whose names are legacy detail maps are deleted.
 */
public final class ReportGeneratedAssetCleanup {

    private ReportGeneratedAssetCleanup() {
    }

    public static boolean isLegacyDetailMap(String fileName) {
        return fileName != null
                && fileName.startsWith("map-route-detail-")
                && fileName.endsWith(".png");
    }

    public static int removeLegacyDetailMaps(Path assetsDirectory) throws IOException {
        if (assetsDirectory == null || !Files.isDirectory(assetsDirectory)) {
            return 0;
        }
        Path root = assetsDirectory.toAbsolutePath().normalize();
        int removed = 0;
        try (var listing = Files.list(root)) {
            for (Path child : listing.toList()) {
                Path file = child.toAbsolutePath().normalize();
                if (file.getParent() == null || !file.getParent().equals(root) || !Files.isRegularFile(file)) {
                    continue;
                }
                if (isLegacyDetailMap(file.getFileName().toString())) {
                    Files.deleteIfExists(file);
                    removed++;
                }
            }
        }
        return removed;
    }
}
