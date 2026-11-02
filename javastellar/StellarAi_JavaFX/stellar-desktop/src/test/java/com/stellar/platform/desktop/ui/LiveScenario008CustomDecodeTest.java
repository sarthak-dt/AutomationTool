package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.DesktopLogging;
import com.stellar.platform.desktop.presentation.DecisionWorkspaceMode;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.QuickDecodeDecisionResolver;
import com.stellar.platform.desktop.project.ChannelMappingStatus;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.SourceChannelKey;
import com.stellar.platform.desktop.project.SourceChannelMappingEntry;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.DesktopFixtures;
import com.stellar.platform.desktop.testsupport.DesktopTestIsolation;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import com.stellar.platform.domain.processing.plan.MappingLifecycle;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scenario 008: Auto Decode, then Custom Decode with an explicit DBC.
 * Isolated storage. Does not open E2E project 1001.
 */
class LiveScenario008CustomDecodeTest {

    private static final String AUTO_DBC = "aaa-auto.dbc";
    private static final String MANUAL_DBC = "zzz-manual.dbc";

    @TempDir Path temp;
    private final List<DesktopAppService> services = new ArrayList<>();
    private String previousHome;
    private String previousDisableAuto;

    @AfterEach
    void tearDown() {
        for (DesktopAppService service : services) {
            service.shutdown();
        }
        DesktopLogging.closeForTests();
        DesktopTestIsolation.resetProcessState();
        restore("stellarai.home", previousHome);
        restore("stellarai.analysis.storage.disable-auto", previousDisableAuto);
    }

    @Test
    void autoThenCustomKeepsManualDbcAndStartsProcessing() throws Exception {
        Path storage = temp.resolve("Storage");
        DesktopAppService service = newService(storage);
        service.newProject("Scenario 008");
        assertEquals(DecisionWorkspaceMode.QUICK_DECODE, service.decisionWorkspaceMode());

        WorkspaceFile measurement = addMeasurement(service);
        WorkspaceFile autoDbc = addDbc(service, AUTO_DBC);
        WorkspaceFile manualDbc = addDbc(service, MANUAL_DBC);
        SourceChannelKey channel = SourceChannelKey.of(measurement.projectSourceId(), 0);

        QuickDecodeDecisionResolver.Decision auto = service.quickDecodeDecisionResolver().resolve(channel);
        assertEquals(AUTO_DBC, auto.selected().definitionDisplayName(),
                "Auto Decode must select the stable listing-order DBC");
        assertFalse(auto.manualOverride());
        assertTrue(service.canProceedFromDecisionWorkspace(),
                "Auto Decode is ready before a manual DBC is required");

        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.decisionWorkspaceMode());
        assertFalse(service.canProceedFromDecisionWorkspace(),
                "Custom Decode stays blocked while the exact tie has no explicit DBC");

        service.chooseChannelDefinition(channel, manualDbc.definitionSourceId());
        SourceChannelMappingEntry chosen = service.channelMapping(channel).orElseThrow();
        assertEquals(ChannelMappingStatus.MANUAL, chosen.status());
        assertEquals(manualDbc.definitionSourceId(), chosen.mappedDefinitionId().orElseThrow());
        assertEquals(MANUAL_DBC, chosen.mappedDisplayName());
        assertTrue(service.canProceedFromDecisionWorkspace(),
                "Review & Process is ready after the manual DBC is recorded");

        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.QUICK_DECODE);
        service.definitionMatchingCoordinator().recomputeAll();
        SourceChannelMappingEntry afterAuto = service.channelMapping(channel).orElseThrow();
        assertEquals(ChannelMappingStatus.MANUAL, afterAuto.status(),
                "returning to Auto Decode must not replace the manual DBC");
        assertEquals(manualDbc.definitionSourceId(), afterAuto.mappedDefinitionId().orElseThrow());
        QuickDecodeDecisionResolver.Decision stillManual = service.quickDecodeDecisionResolver().resolve(channel);
        assertTrue(stillManual.manualOverride());
        assertEquals(MANUAL_DBC, stillManual.selected().definitionDisplayName());
        assertEquals(MappingLifecycle.MANUAL, stillManual.planLifecycle());

        service.setDecisionWorkspaceMode(DecisionWorkspaceMode.DETAILED_MAPPING);
        assertTrue(service.canProceedFromDecisionWorkspace());
        service.enterProcessingWorkspaceFromDecision();
        assertEquals(DecisionWorkspaceMode.DETAILED_MAPPING, service.processingDecisionMode());
        ProcessingJob job = service.processingSelectionModel().reviewedPlan().jobs().stream()
                .filter(candidate -> candidate.channelId() == 0)
                .findFirst()
                .orElseThrow();
        assertEquals(manualDbc.definitionSourceId().value(), job.definitionSourceId().orElseThrow());
        assertEquals(MappingLifecycle.MANUAL, job.mappingLifecycle());
        assertNotEquals(autoDbc.definitionSourceId().value(), job.definitionSourceId().orElseThrow());

        service.executeReviewedPlan(Runnable::run, () -> { });
        waitForIdle(service);
        assertTrue(service.signalsModel().catalog() != null && service.signalsModel().catalog().size() > 0,
                "processing must start and publish a catalog");
        assertTrue(service.latestPersistedProcessingRun().isPresent());
    }

    private DesktopAppService newService(Path selected) throws Exception {
        previousHome = System.getProperty("stellarai.home");
        previousDisableAuto = System.getProperty("stellarai.analysis.storage.disable-auto");
        System.setProperty("stellarai.home", temp.resolve("AppHome").toAbsolutePath().toString());
        System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        DesktopTestIsolation.resetProcessState();
        DesktopAppService service = new DesktopAppService(new DesktopSession(ParserRegistry.load()));
        services.add(service);
        Files.createDirectories(selected);
        assertTrue(service.saveAnalysisStorage(selected).valid());
        assertNotEquals(Path.of("D:/StellarAI-E2E/Storage").toAbsolutePath().normalize(),
                service.analysisStorage().userSelectedRoot().orElseThrow());
        return service;
    }

    private WorkspaceFile addMeasurement(DesktopAppService service) throws Exception {
        Path mf4 = DesktopFixtures.writeFinalizedMf4(temp.resolve("scenario008.mf4"));
        WorkspaceFile source = new WorkspaceFile(mf4, FileKind.MEASUREMENT_MF4, Files.size(mf4));
        service.workspace().addFile(source);
        CanSourceInventory inventory = new CanSourceInventory(
                mf4, source.fileName(), MeasurementSourceFormat.MF4, CanInventoryStatus.INVENTORY_READY,
                1, 1, 1, true,
                List.of(new CanChannelInventory(
                        0, "Channel 0", 1, 1, 1, 0, 0,
                        List.of(new CanMessageInventory(new CanMessageKey(0, 0x100L, false), 1, 8, 8, false)))),
                List.of());
        service.sourceInventoryModel().put(source.id(), inventory);
        return source;
    }

    private WorkspaceFile addDbc(DesktopAppService service, String fileName) throws Exception {
        Path path = DesktopFixtures.copyResource("dbc/desktop_engine.dbc", temp.resolve(fileName));
        return service.addPath(path);
    }

    private static void waitForIdle(DesktopAppService service) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (service.isPlanExecutionActive() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(service.isPlanExecutionActive(), "processing run did not reach a terminal state");
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
