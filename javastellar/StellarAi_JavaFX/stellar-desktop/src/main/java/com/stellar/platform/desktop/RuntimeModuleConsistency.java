package com.stellar.platform.desktop;

import com.stellar.platform.api.worker.FirstPartyRuntimeModules;
import com.stellar.platform.api.worker.ProcessIsolatedLocalWorker;
import com.stellar.platform.api.worker.WorkerProtocol;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.processing.catalog.ProjectMergedSignalStore;
import com.stellar.platform.processing.map.GpsSignalRole;
import com.stellar.platform.processing.map.GpsSignalRoleResolver;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;

/**
 * Startup diagnostic for mixed StellarAI module binaries.
 *
 * <p>Logs {@code ProtectionDomain} locations, rejects duplicate first-party JARs,
 * and requires the current bytecode contract. Missing methods fail the launch
 * with those locations — they are not caught or shimmed.</p>
 */
public final class RuntimeModuleConsistency {

    private static final System.Logger LOG = System.getLogger(RuntimeModuleConsistency.class.getName());

    private RuntimeModuleConsistency() {
    }

    public static void main(String[] args) {
        try {
            logAndVerify();
        } catch (RuntimeException ex) {
            System.err.println(ex.getMessage());
            System.exit(1);
        }
    }

    public static void logAndVerify() {
        String workerProtocol = FirstPartyRuntimeModules.codeSourceLocation(WorkerProtocol.class);
        String isolatedWorker = FirstPartyRuntimeModules.codeSourceLocation(ProcessIsolatedLocalWorker.class);
        String metadataStore = FirstPartyRuntimeModules.codeSourceLocation(SqliteProjectMetadataStore.class);
        String desktopService = FirstPartyRuntimeModules.codeSourceLocation(DesktopAppService.class);
        String gpsResolver = FirstPartyRuntimeModules.codeSourceLocation(GpsSignalRoleResolver.class);
        String mergedStore = ProcessingCoreCompatibility.loadedStoreLocation();
        LOG.log(System.Logger.Level.INFO, "Runtime module WorkerProtocol: " + workerProtocol);
        LOG.log(System.Logger.Level.INFO, "Runtime module ProcessIsolatedLocalWorker: " + isolatedWorker);
        LOG.log(System.Logger.Level.INFO, "Runtime module SqliteProjectMetadataStore: " + metadataStore);
        LOG.log(System.Logger.Level.INFO, "Runtime module DesktopAppService: " + desktopService);
        LOG.log(System.Logger.Level.INFO, "Runtime module GpsSignalRoleResolver: " + gpsResolver);
        LOG.log(System.Logger.Level.INFO, "Runtime module ProjectMergedSignalStore: " + mergedStore);
        LOG.log(System.Logger.Level.INFO,
                "Runtime module AlignedSignalCsvExporter: " + ProcessingCoreCompatibility.loadedExporterLocation());
        FirstPartyRuntimeModules.rejectDuplicateJars(System.getProperty("java.class.path", ""));
        requireDeclaredMethod(WorkerProtocol.class, "isSuccessfulTerminal", String.class);
        requireDeclaredMethod(ProcessIsolatedLocalWorker.class, "requestCancel", String.class);
        requireDeclaredMethod(SqliteProjectMetadataStore.class, "checkpointWal");
        requireDeclaredMethod(ProjectMergedSignalStore.class, "physicalIdentities", SignalCatalogKey.class);
        requireDeclaredMethod(ProjectMergedSignalStore.class, "physicalCatalog", String.class, String.class);
        if (!WorkerProtocol.isSuccessfulTerminal(WorkerProtocol.COMPLETED)) {
            throw new IllegalStateException("WorkerProtocol.isSuccessfulTerminal rejected COMPLETED from " + workerProtocol);
        }
        if (!StorageCoreCompatibility.lastWorkspaceApiPresent()) {
            throw new IllegalStateException(StorageCoreCompatibility.incompatibleClasspathMessage()
                    + "\n" + locations(workerProtocol, isolatedWorker, metadataStore, desktopService, gpsResolver));
        }
        if (!ProcessingCoreCompatibility.physicalIdentitiesApiPresent()
                || !ProcessingCoreCompatibility.physicalCatalogApiPresent()) {
            throw new IllegalStateException(ProcessingCoreCompatibility.incompatibleClasspathMessage()
                    + "\n" + locations(workerProtocol, isolatedWorker, metadataStore, desktopService, gpsResolver));
        }
        if (!ProcessingCoreCompatibility.alignedCsvExportResultPresent()) {
            throw new IllegalStateException(ProcessingCoreCompatibility.incompatibleClasspathMessage()
                    + "\nMissing AlignedSignalCsvExporter.ExportResult from "
                    + ProcessingCoreCompatibility.loadedExporterLocation()
                    + "\n" + locations(workerProtocol, isolatedWorker, metadataStore, desktopService, gpsResolver));
        }
        requireGpsLatInfoRoleScoring(gpsResolver, workerProtocol, isolatedWorker, metadataStore, desktopService);
    }

    static String locations(
            String workerProtocol,
            String isolatedWorker,
            String metadataStore,
            String desktopService) {
        return locations(
                workerProtocol,
                isolatedWorker,
                metadataStore,
                desktopService,
                FirstPartyRuntimeModules.codeSourceLocation(GpsSignalRoleResolver.class));
    }

    static String locations(
            String workerProtocol,
            String isolatedWorker,
            String metadataStore,
            String desktopService,
            String gpsResolver) {
        return """
                Loaded class locations:
                  WorkerProtocol = %s
                  ProcessIsolatedLocalWorker = %s
                  SqliteProjectMetadataStore = %s
                  DesktopAppService = %s
                  GpsSignalRoleResolver = %s

                Isolated `mvn -pl stellar-desktop javafx:run` loads SNAPSHOTs from ~/.m2 and can
                mix a current stellar-desktop with stale stellar-processing-core / stellar-api.

                From the repository root run:
                  mvn clean install
                  mvn -pl stellar-desktop -am org.openjfx:javafx-maven-plugin:run
                or:
                  powershell -NoProfile -ExecutionPolicy Bypass -File .\\scripts\\run-desktop.ps1
                """.formatted(workerProtocol, isolatedWorker, metadataStore, desktopService, gpsResolver);
    }

    /**
     * Capability probe — not a filename/name hardcode in Map Route discovery. Isolated
     * {@code javafx:run} after verify-only can load a stale processing-core that scores
     * {@code GPS_LAT_INFO} as 0, which the GUI then reports as lat candidates=0.
     */
    private static void requireGpsLatInfoRoleScoring(
            String gpsResolver,
            String workerProtocol,
            String isolatedWorker,
            String metadataStore,
            String desktopService) {
        SignalCatalogEntry latitude = new SignalCatalogEntry(
                new SignalCatalogKey(
                        "GPS_LAT_INFO", SignalSourceType.DBC_CAN, "GPS_COORDINATE", 1, 1, "probe.mf4", "probe.dbc"),
                "deg", 10, 0L, 1_000L, 13.0, 14.0, "probe.mf4", "probe.dbc");
        SignalCatalogEntry accuracy = new SignalCatalogEntry(
                new SignalCatalogKey(
                        "GPS_ACCURACY_LAT_LONG", SignalSourceType.DBC_CAN, "GPS_STATUS", 2, 1, "probe.mf4", "probe.dbc"),
                "m", 10, 0L, 1_000L, 0.0, 5.0, "probe.mf4", "probe.dbc");
        int latScore = GpsSignalRoleResolver.scoreRole(GpsSignalRole.LATITUDE, latitude);
        int accuracyLat = GpsSignalRoleResolver.scoreRole(GpsSignalRole.LATITUDE, accuracy);
        int accuracyLon = GpsSignalRoleResolver.scoreRole(GpsSignalRole.LONGITUDE, accuracy);
        if (latScore <= 0 || accuracyLat > 0 || accuracyLon > 0) {
            throw new IllegalStateException(
                    "Stale GpsSignalRoleResolver from " + gpsResolver
                            + " cannot classify GPS_*_INFO coordinates (latScore=" + latScore
                            + ", accuracyLat=" + accuracyLat
                            + ", accuracyLon=" + accuracyLon + ")."
                            + System.lineSeparator()
                            + locations(workerProtocol, isolatedWorker, metadataStore, desktopService, gpsResolver));
        }
    }

    private static void requireDeclaredMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            type.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException(
                    "Missing " + type.getName() + "." + name + " on "
                            + FirstPartyRuntimeModules.codeSourceLocation(type)
                            + System.lineSeparator()
                            + locations(
                            FirstPartyRuntimeModules.codeSourceLocation(WorkerProtocol.class),
                            FirstPartyRuntimeModules.codeSourceLocation(ProcessIsolatedLocalWorker.class),
                            FirstPartyRuntimeModules.codeSourceLocation(SqliteProjectMetadataStore.class),
                            FirstPartyRuntimeModules.codeSourceLocation(DesktopAppService.class)),
                    ex);
        }
    }
}
