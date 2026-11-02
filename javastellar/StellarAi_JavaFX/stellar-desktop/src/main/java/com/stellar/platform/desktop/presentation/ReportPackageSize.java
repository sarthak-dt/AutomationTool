package com.stellar.platform.desktop.presentation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Sum of regular files that belong to one generated report. Missing reports return -1. */
public final class ReportPackageSize {

    private ReportPackageSize() {
    }

    public static long bytes(Path destination) {
        if (destination == null) {
            return -1L;
        }
        try {
            if (!Files.exists(destination)) {
                return -1L;
            }
            if (Files.isDirectory(destination)) {
                return sumTree(destination);
            }
            Path parent = destination.getParent();
            String name = destination.getFileName() == null ? "" : destination.getFileName().toString();
            if ("report.html".equalsIgnoreCase(name) && parent != null && Files.isDirectory(parent)) {
                return sumTree(parent);
            }
            long total = Files.size(destination);
            if (parent != null) {
                Path assets = parent.resolve("assets");
                if (Files.isDirectory(assets)) {
                    total += sumTree(assets);
                }
            }
            return total;
        } catch (IOException ex) {
            return -1L;
        }
    }

    private static long sumTree(Path root) throws IOException {
        long total = 0L;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName() == null ? "" : path.getFileName().toString();
                if (name.endsWith(".writing") || name.endsWith(".staging") || name.endsWith(".partial")) {
                    continue;
                }
                total += Files.size(path);
            }
        }
        return total;
    }
}
