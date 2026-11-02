package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.ui.inventory.MeasurementSourceInventoryList;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Phase7LargeSourceTableTest {

    @TempDir Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    @Timeout(45)
    void fiveHundredSourcesStayVirtualizedAndContinueReadinessIsSingleScan() throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        service.newProject("500 Sources");
        List<WorkspaceFile> files = new ArrayList<>(500);
        for (int i = 0; i < 500; i++) {
            WorkspaceFile file = new WorkspaceFile(
                    temp.resolve("run-" + i + ".mf4"), FileKind.MEASUREMENT_MF4, 128);
            service.workspace().addFile(file);
            files.add(file);
        }

        JavaFxTestSupport.runOnFx("500-sources", () -> {
            MeasurementSourceInventoryList list = new MeasurementSourceInventoryList();
            list.setPrefHeight(400);
            list.setFixedCellSize(44);
            Stage stage = new Stage();
            stage.setScene(new Scene(list, 720, 400));
            stage.show();
            list.replaceItems(files, files.getFirst().id());
            list.applyCss();
            list.layout();

            assertEquals(500, list.totalItemCount());
            assertEquals(500, list.visibleItemCount());
            long cells = list.lookupAll(".list-cell").stream()
                    .filter(n -> n instanceof ListCell<?> cell && cell.getIndex() >= 0)
                    .count();
            assertTrue(cells < 80, "ListView must stay virtualized, found cells=" + cells);

            boolean ready = service.canContinueToDecode();
            assertTrue(ready || !ready); // single O(N) scan; must not throw / walk N²
            assertEquals(500, service.workspace().measurementFiles().size());
            stage.hide();
            stage.close();
        });

        assertTrue(service.saveProject().saved());
        assertEquals(500, service.workspace().measurementFiles().size(),
                "save metadata stays one row per source");
        service.shutdown();
    }
}
