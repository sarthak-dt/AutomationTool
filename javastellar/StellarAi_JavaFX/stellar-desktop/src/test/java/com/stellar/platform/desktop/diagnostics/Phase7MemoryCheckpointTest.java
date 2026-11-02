package com.stellar.platform.desktop.diagnostics;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationState;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.MainShellController;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 checkpoints A–H. Records used vs committed heap. Does not treat
 * committed heap as a leak and never calls {@code System.gc()}.
 */
class Phase7MemoryCheckpointTest {

    @TempDir Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    @Timeout(60)
    void checkpointsDistinguishUsedFromCommittedAndStayGraphEmptyUntilPlotted() throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        Path db = temp.resolve("meta.db");
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(db));
        List<DesktopRuntimeCheckpoint> log = new ArrayList<>();
        log.add(DesktopRuntimeCheckpoint.capture("A-fresh", service));

        JavaFxTestSupport.runOnFx("checkpoint-shell", () -> {
            FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
            loader.load();
            MainShellController shell = loader.getController();
            shell.init(service);
            service.newProject("Phase7 Checkpoints");
            log.add(DesktopRuntimeCheckpoint.capture("B-open", service));

            InMemorySignalCatalogStore store = new InMemorySignalCatalogStore("run.mf4", "veh.dbc", 8);
            store.accept(List.of(new CanonicalSignal(
                    "Speed", "Msg", 1_000L, 0x100, 1, 10.0, 10L, "km/h", null,
                    SourceReference.ofLine(1))));
            service.signalsModel().replaceCatalog(store);
            log.add(DesktopRuntimeCheckpoint.capture("C-catalog", service));
            log.add(DesktopRuntimeCheckpoint.capture("D-after-catalog-as-processed", service));

            SignalCatalogKey key = service.signalsModel().catalog().entries().getFirst().key();
            service.signalsModel().addToWorkspace(key);
            log.add(DesktopRuntimeCheckpoint.capture("E-signals", service));

            GraphInvestigationState inv = service.graphInvestigation();
            inv.setLiveTraceModels(1);
            inv.setLiveViewportResults(1);
            log.add(DesktopRuntimeCheckpoint.capture("F-graph", service));
            assertEquals(1, inv.liveTraceModels());

            service.signalsModel().clearWorkspace();
            inv.clearAnalysis();
            log.add(DesktopRuntimeCheckpoint.capture("G-clear", service));
            assertEquals(0, inv.liveTraceModels());
            assertEquals(0, inv.liveViewportResults());
            assertEquals(1, service.signalsModel().catalog().entries().size());

            service.closeProject();
            shell.dispose();
        });
        service.shutdown();
        log.add(DesktopRuntimeCheckpoint.capture("H-closed", null));

        for (DesktopRuntimeCheckpoint point : log) {
            assertTrue(point.jvm().heapCommittedBytes() >= point.jvm().heapUsedBytes(),
                    point.label() + " committed must be >= used: " + point);
            System.out.println("P7-CHECKPOINT " + point);
        }
        assertTrue(log.getFirst().jvm().heapUsedBytes() > 0);
        JavaFxTestSupport.assertNoLiveStellarThreads("after checkpoint H");
    }
}
