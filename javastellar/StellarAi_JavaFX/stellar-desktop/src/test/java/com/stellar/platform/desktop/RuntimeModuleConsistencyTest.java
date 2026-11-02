package com.stellar.platform.desktop;

import com.stellar.platform.api.worker.FirstPartyRuntimeModules;
import com.stellar.platform.api.worker.ProcessIsolatedLocalWorker;
import com.stellar.platform.api.worker.WorkerProtocol;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeModuleConsistencyTest {

    @Test
    void currentClasspathSatisfiesRequiredMethods() throws Exception {
        assertDoesNotThrow(RuntimeModuleConsistency::logAndVerify);
        assertNotNull(WorkerProtocol.class.getDeclaredMethod("isSuccessfulTerminal", String.class));
        assertNotNull(ProcessIsolatedLocalWorker.class.getDeclaredMethod("requestCancel", String.class));
        assertNotNull(SqliteProjectMetadataStore.class.getDeclaredMethod("checkpointWal"));
        assertTrue(WorkerProtocol.isSuccessfulTerminal(WorkerProtocol.COMPLETED));
        String desktop = FirstPartyRuntimeModules.codeSourceLocation(DesktopAppService.class);
        String api = FirstPartyRuntimeModules.codeSourceLocation(WorkerProtocol.class);
        String gpsResolver = FirstPartyRuntimeModules.codeSourceLocation(
                com.stellar.platform.processing.map.GpsSignalRoleResolver.class);
        assertFalse(desktop.isBlank());
        assertFalse(api.isBlank());
        assertFalse(gpsResolver.isBlank());
        assertTrue(ProcessingCoreCompatibility.physicalIdentitiesApiPresent());
        assertTrue(ProcessingCoreCompatibility.physicalCatalogApiPresent());
        assertTrue(ProcessingCoreCompatibility.alignedCsvExportResultPresent());
        assertTrue(ProcessingCoreCompatibility.loadedExporterLocation().contains("stellar-processing-core"));
        assertNotNull(com.stellar.platform.processing.catalog.ProjectMergedSignalStore.class
                .getMethod("physicalIdentities", com.stellar.platform.domain.catalog.SignalCatalogKey.class));
        assertFalse(ProcessingCoreCompatibility.loadedStoreLocation().isBlank());
        assertTrue(com.stellar.platform.processing.map.GpsSignalRoleResolver.scoreRole(
                com.stellar.platform.processing.map.GpsSignalRole.LATITUDE,
                new com.stellar.platform.domain.catalog.SignalCatalogEntry(
                        new com.stellar.platform.domain.catalog.SignalCatalogKey(
                                "GPS_LAT_INFO",
                                com.stellar.platform.domain.catalog.SignalSourceType.DBC_CAN,
                                "GPS_COORDINATE", 1, 1, "probe.mf4", "probe.dbc"),
                        "deg", 10, 0L, 1_000L, 13.0, 14.0, "probe.mf4", "probe.dbc")) > 0);
        String locations = RuntimeModuleConsistency.locations(api, api, api, desktop);
        assertTrue(locations.contains("Loaded class locations"));
        assertTrue(locations.contains("run-desktop.ps1"));
        assertTrue(locations.contains("Isolated `mvn -pl stellar-desktop javafx:run`"));
        assertTrue(ProcessingCoreCompatibility.incompatibleClasspathMessage().contains("run-desktop.ps1"));
    }
}
