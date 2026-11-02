package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.ProjectSourceId;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.ui.inventory.DbcMappingTablePane;
import com.stellar.platform.desktop.ui.inventory.MeasurementSourceInventoryList;
import com.stellar.platform.desktop.ui.inventory.SourceInventoryWorkspace;
import com.stellar.platform.domain.MeasurementSourceFormat;
import com.stellar.platform.domain.inventory.CanChannelInventory;
import com.stellar.platform.domain.inventory.CanInventoryStatus;
import com.stellar.platform.domain.inventory.CanMessageInventory;
import com.stellar.platform.domain.inventory.CanMessageKey;
import com.stellar.platform.domain.inventory.CanSourceInventory;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceAliasConsistencyTest {

    @BeforeAll
    static void initJavaFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(45)
    void sameProjectSourceIdSharesAliasAcrossSourcesInventoryAndMapping() throws Exception {
        runOnFx(() -> {
            System.setProperty("stellarai.home", java.nio.file.Files.createTempDirectory("stellar-home-").toAbsolutePath().toString());
            DesktopAppService service = new DesktopAppService(new DesktopSession());
            WorkspaceFile a = addReady(service,
                    "MF4_KA04NC7760_Bengaluru_0002360_20230814_164651.mf4", 0x201);
            WorkspaceFile b = addReady(service,
                    "MF4_KA04NC7760_Bengaluru_0002360_20230814_171438.mf4", 0x208);
            WorkspaceFile c = addReady(service, "OBD2_Data_Lavasa_102230.mf4", 0x316);

            MeasurementSourceInventoryList sources = new MeasurementSourceInventoryList();
            sources.bind(service, service.sourceInventoryModel());
            sources.replaceItems(service.workspace().measurementFiles(), null);

            SourceInventoryWorkspace inventory = new SourceInventoryWorkspace();
            inventory.bind(service, () -> { });
            inventory.refreshFromModel();

            DbcMappingTablePane mapping = new DbcMappingTablePane();
            mapping.bind(service, () -> { });
            mapping.refreshFromModel();

            SourceAliasRegistry aliases = service.sourceAliasRegistry();
            assertEquals("Source A", aliases.aliasFor(a.projectSourceId()));
            assertEquals("Source B", aliases.aliasFor(b.projectSourceId()));
            assertEquals("Source C", aliases.aliasFor(c.projectSourceId()));

            assertEquals("Source A", sources.aliasForTest(a.projectSourceId()));
            assertEquals("Source B", sources.aliasForTest(b.projectSourceId()));
            assertEquals("Source C", sources.aliasForTest(c.projectSourceId()));

            // Large-project Sources search is presentation-only: hidden rows must not prune aliases
            // or mutate the complete project source set.
            assertEquals(3, sources.totalItemCount());
            sources.setFilterText("171438");
            assertEquals(1, sources.visibleItemCount());
            assertEquals("Source A", aliases.aliasFor(a.projectSourceId()));
            assertEquals("Source B", aliases.aliasFor(b.projectSourceId()));
            assertEquals("Source C", aliases.aliasFor(c.projectSourceId()));
            sources.setFilterText("");
            assertEquals(3, sources.visibleItemCount());

            assertEquals("Source A", inventory.modelForTest().rebuild(
                    service.workspace().measurementFiles(),
                    service.sourceInventoryModel(),
                    aliases,
                    service::inventoryJobState
            ).sources().get(0).alias());
            assertEquals("Source B", inventory.modelForTest().rebuild(
                    service.workspace().measurementFiles(),
                    service.sourceInventoryModel(),
                    aliases,
                    service::inventoryJobState
            ).sources().get(1).alias());

            // Compact names remain available internally; the Sources row shows the full file name.
            String compactB = sources.compactNameForTest(b.projectSourceId());
            assertTrue(compactB != null && compactB.contains("171438"), compactB);
            assertTrue(compactB.length() < b.fileName().length(), compactB);
            assertEquals(b.fileName(), sources.primaryNameForTest(b.projectSourceId()));
            assertEquals(a.fileName(), sources.primaryNameForTest(a.projectSourceId()));

            // Remove B — A and C stay; next add is D.
            service.workspace().removeMeasurement(b.projectSourceId());
            service.sourceInventoryModel().remove(b.id());
            sources.replaceItems(service.workspace().measurementFiles(), null);
            inventory.refreshFromModel();
            assertEquals("Source A", aliases.aliasFor(a.projectSourceId()));
            assertEquals("Source C", aliases.aliasFor(c.projectSourceId()));
            WorkspaceFile d = addReady(service, "next_source.mf4", 0x401);
            sources.replaceItems(service.workspace().measurementFiles(), null);
            assertEquals("Source D", aliases.aliasFor(d.projectSourceId()));
            assertEquals("Source D", sources.aliasForTest(d.projectSourceId()));

            // New project resets allocator.
            service.newProject("Fresh");
            assertEquals(0, service.sourceAliasRegistry().size());
            WorkspaceFile again = addReady(service, "fresh_a.mf4", 0x101);
            assertEquals("Source A", service.sourceAliasRegistry().aliasFor(again.projectSourceId()));
        });
    }

    private static WorkspaceFile addReady(DesktopAppService service, String name, long canId) {
        WorkspaceFile file = service.workspace().addFile(
                new WorkspaceFile(Path.of(name).toAbsolutePath(), FileKind.MEASUREMENT_MF4, 10));
        CanMessageInventory msg = new CanMessageInventory(new CanMessageKey(1, canId, false), 10, 8, 8, false);
        CanChannelInventory ch = new CanChannelInventory(1, "Channel 1", 10, 1, 1, 0, 0, List.of(msg));
        CanSourceInventory inv = new CanSourceInventory(
                file.path(), file.fileName(), MeasurementSourceFormat.MF4,
                CanInventoryStatus.INVENTORY_READY, 1, 10, 1, true, List.of(ch), List.of());
        service.sourceInventoryModel().put(file.id(), inv);
        return file;
    }

    private static void runOnFx(FxBody body) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(40, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    @FunctionalInterface
    private interface FxBody {
        void run() throws Exception;
    }
}