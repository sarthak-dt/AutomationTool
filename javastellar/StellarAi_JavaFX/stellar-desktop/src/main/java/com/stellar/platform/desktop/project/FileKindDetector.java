package com.stellar.platform.desktop.project;

import com.stellar.platform.processing.DetectedMeasurementFormat;
import com.stellar.platform.processing.ParserRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Product source type comes from the imported file. {@code .dat} stays DAT and {@code .log}
 * stays LOG even when the bytes are MDF, ASC, or BLF. Other measurements still follow content.
 */
public final class FileKindDetector {

    private static volatile ParserRegistry registry = ParserRegistry.load();

    private FileKindDetector() {
    }

    public static FileKind detect(Path path) {
        return inspect(path).kind();
    }

    public static Inspection inspect(Path path) {
        Objects.requireNonNull(path, "path");
        DetectedMeasurementFormat detected = registry.detect(path);
        FileKind product = productSourceKind(path);
        if (product != null) {
            return new Inspection(product, detected);
        }
        if (detected.content() != null) {
            return new Inspection(measurementKind(detected.content().formatKey()), detected);
        }
        FileKind byExtension = extensionOnly(path);
        if (Files.isRegularFile(path) && byExtension.measurement()) {
            return new Inspection(FileKind.UNKNOWN, detected);
        }
        return new Inspection(byExtension, detected);
    }

    static void useRegistryForTests(ParserRegistry replacement) {
        registry = replacement == null ? ParserRegistry.load() : replacement;
    }

    /**
     * DAT and LOG are product source types taken from the filename. Null for every other file.
     */
    public static FileKind productSourceKind(Path path) {
        String extension = extension(path);
        if (extension.equals("dat")) {
            return FileKind.MEASUREMENT_DAT;
        }
        if (extension.equals("log")) {
            return FileKind.MEASUREMENT_LOG;
        }
        return null;
    }

    private static String extension(Path path) {
        if (path == null || path.getFileName() == null) {
            return "";
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1);
    }

    private static FileKind measurementKind(String formatKey) {
        if (formatKey == null) {
            return FileKind.UNKNOWN;
        }
        return switch (formatKey.toLowerCase(Locale.ROOT)) {
            case "mf4", "mdf" -> FileKind.MEASUREMENT_MF4;
            case "asc" -> FileKind.MEASUREMENT_ASC;
            case "blf" -> FileKind.MEASUREMENT_BLF;
            default -> FileKind.UNKNOWN;
        };
    }

    private static FileKind extensionOnly(Path path) {
        Path name = path.getFileName();
        if (name == null) {
            return FileKind.UNKNOWN;
        }
        String lower = name.toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mf4") || lower.endsWith(".mdf")) {
            return FileKind.MEASUREMENT_MF4;
        }
        if (lower.endsWith(".asc")) {
            return FileKind.MEASUREMENT_ASC;
        }
        if (lower.endsWith(".blf")) {
            return FileKind.MEASUREMENT_BLF;
        }
        if (lower.endsWith(".dbc")) {
            return FileKind.DEFINITION_DBC;
        }
        if (lower.endsWith(".csv")) {
            return FileKind.DEFINITION_CSV;
        }
        if (lower.endsWith(".a2l")) {
            return FileKind.DEFINITION_A2L;
        }
        if (lower.endsWith(".odx")) {
            return FileKind.DEFINITION_ODX;
        }
        if (lower.endsWith(".pdx")) {
            return FileKind.DEFINITION_PDX;
        }
        return FileKind.UNKNOWN;
    }

    /**
     * Label shown beside a misleading extension, such as {@code MDF 3.30} on a {@code .dat} file.
     * Empty when the extension already names the supported format.
     */
    public static String detectedLabel(DetectedMeasurementFormat detected) {
        if (detected == null || detected.content() == null) {
            return "";
        }
        boolean standardExtension = switch (detected.originalExtension()) {
            case "mf4", "mdf", "asc", "blf" -> true;
            default -> false;
        };
        if (detected.parserSupported() && standardExtension) {
            return "";
        }
        return detected.displayLabel();
    }

    public record Inspection(FileKind kind, DetectedMeasurementFormat detected) {
        public boolean recognized() {
            return detected != null && detected.recognized();
        }

        public boolean parserSupported() {
            return detected != null && detected.parserSupported();
        }

        public String uiLabel() {
            return detectedLabel(detected);
        }
    }
}
