package com.stellar.platform.desktop;

import com.stellar.platform.api.worker.FirstPartyRuntimeModules;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.catalog.SignalSeriesStore;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detects a stale {@code stellar-processing-core} SNAPSHOT on the desktop classpath.
 *
 * <p>{@code mvn -pl stellar-desktop javafx:run} after verify-only loads
 * {@code ~/.m2} JARs. Desktop compiled against {@code physicalIdentities} then
 * throws {@code NoSuchMethodError} on Resume / Analyze.</p>
 */
public final class ProcessingCoreCompatibility {

    private ProcessingCoreCompatibility() {
    }

    public static boolean physicalIdentitiesApiPresent() {
        try {
            Method method = ProjectMergedSignalStore.class.getMethod("physicalIdentities", SignalCatalogKey.class);
            return Set.class.isAssignableFrom(method.getReturnType());
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }

    public static boolean physicalCatalogApiPresent() {
        try {
            Method method = ProjectMergedSignalStore.class.getMethod("physicalCatalog", String.class, String.class);
            return java.util.List.class.isAssignableFrom(method.getReturnType());
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }

    /**
     * The Signals page links {@code AlignedSignalCsvExporter.ExportResult} while FXMLLoader
     * builds the controller. A stale processing-core SNAPSHOT has the outer exporter missing
     * or has no nested record, and that surfaces as {@code NoClassDefFoundError} during project resume.
     */
    public static boolean alignedCsvExportResultPresent() {
        try {
            Class<?> exporter = Class.forName("com.stellar.platform.processing.export.AlignedSignalCsvExporter");
            Class<?> result = Class.forName(
                    "com.stellar.platform.processing.export.AlignedSignalCsvExporter$ExportResult");
            Class<?> mode = Class.forName(
                    "com.stellar.platform.processing.export.AlignedSignalCsvExporter$ValueMode");
            Method export = exporter.getMethod(
                    "export",
                    SignalSeriesStore.class,
                    List.class,
                    mode,
                    Path.class,
                    AtomicBoolean.class);
            return result.equals(export.getReturnType());
        } catch (ReflectiveOperationException | LinkageError ex) {
            return false;
        }
    }

    public static String loadedExporterLocation() {
        try {
            Class<?> exporter = Class.forName("com.stellar.platform.processing.export.AlignedSignalCsvExporter");
            return FirstPartyRuntimeModules.codeSourceLocation(exporter);
        } catch (ReflectiveOperationException | LinkageError ex) {
            return loadedStoreLocation();
        }
    }

    public static String loadedStoreLocation() {
        return FirstPartyRuntimeModules.codeSourceLocation(ProjectMergedSignalStore.class);
    }

    public static String incompatibleClasspathMessage() {
        return """
                The installed processing-core module is older than this desktop build.
                Resume and Analyze Selected cannot work until sibling SNAPSHOTs are installed.

                Loaded ProjectMergedSignalStore: %s

                From the repository root run:
                  powershell -NoProfile -ExecutionPolicy Bypass -File .\\scripts\\run-desktop.ps1

                Isolated `mvn -pl stellar-desktop javafx:run` after `mvn verify` (no install)
                mixes current stellar-desktop classes with a stale processing-core JAR.
                """.formatted(loadedStoreLocation());
    }
}
