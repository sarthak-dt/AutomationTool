package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.GraphInvestigationCodec;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.TabPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Report Builder signal selection on the live investigation screen, then an HTML export. */
class EngineeringReportBuilderSelectionTest {

    @TempDir Path temp;
    private DesktopAppService service;
    private InvestigationController controller;
    private Stage stage;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        JavaFxTestSupport.runOnFx("dispose report builder", () -> {
            if (controller != null) {
                controller.dispose();
                controller = null;
            }
            if (stage != null) {
                stage.close();
                stage = null;
            }
        });
        if (service != null) {
            service.shutdown();
            service = null;
        }
        JavaFxTestSupport.drainFxPulse();
    }

    @Test
    @Timeout(60)
    void selectedSignalsStayIndependentOfTheWorkspaceAndExport() throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        JavaFxTestSupport.runOnFx("load report builder", () -> {
            FXMLLoader loader = new FXMLLoader(InvestigationController.class.getResource("/fxml/investigation-view.fxml"));
            Parent root = loader.load();
            controller = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1366, 768));
            stage.show();
            controller.setAppService(service, null);
        });
        JavaFxTestSupport.drainFxPulse();
        service.newProject("Report builder");
        String ch01 = GraphInvestigationCodec.encodeKey(new SignalCatalogKey(
                "CH01_FRONT_BUMPER_GRILL_AMBIENT", SignalSourceType.DBC_CAN, "TEMP", 256, 2, "mf4", "dbc"));
        String ch02 = GraphInvestigationCodec.encodeKey(new SignalCatalogKey(
                "CH02_FRONT_BUMPER_GRILL", SignalSourceType.DBC_CAN, "TEMP", 256, 2, "mf4", "dbc"));
        EngineeringInvestigation created = service.createInvestigation("Evidence", "Selected signals", "CURRENT");
        EngineeringInvestigation saved = service.saveInvestigation(created.toBuilder()
                .reportConfig(InvestigationReportConfig.engineeringV2())
                .signalKeyBlobs(List.of(ch01, ch02))
                .build());
        assertEquals(List.of(ch01, ch02), saved.signalKeyBlobs());
        EngineeringInvestigation loaded = service.engineeringInvestigations()
                .find(service.metadataStore(), saved.investigationId()).orElseThrow();
        assertEquals(List.of(ch01, ch02), loaded.signalKeyBlobs(), loaded.signalKeyBlobs().toString());
        JavaFxTestSupport.runOnFx("show the saved selection", () -> controller.refresh());
        JavaFxTestSupport.drainFxPulse();
        JavaFxTestSupport.runOnFx("open the report tab", () -> {
            TabPane tabs = (TabPane) stage.getScene().getRoot().lookup(".tab-pane");
            tabs.getTabs().stream().filter(tab -> "Report".equals(tab.getText())).findFirst()
                    .ifPresent(tab -> tabs.getSelectionModel().select(tab));
        });
        JavaFxTestSupport.drainFxPulse();
        JavaFxTestSupport.runOnFx("read the report signal list", () -> {
            ListView<?> signals = reportSignals();
            assertEquals(2, signals.getItems().size());
            assertEquals("CH01_FRONT_BUMPER_GRILL_AMBIENT", signals.getItems().get(0));
            assertEquals("CH02_FRONT_BUMPER_GRILL", signals.getItems().get(1));
            assertTrue(summary().contains("2 signals"));
            signals.getSelectionModel().select(0);
            removeButton().fire();
        });
        JavaFxTestSupport.drainFxPulse();
        EngineeringInvestigation after = service.engineeringInvestigations()
                .find(service.metadataStore(), saved.investigationId()).orElseThrow();
        assertEquals(List.of(ch02), after.signalKeyBlobs());
        Path html = service.generateInvestigationReport(after, "Ada", temp.resolve("builder.html"));
        String text = Files.readString(html);
        assertTrue(text.contains("CH02_FRONT_BUMPER_GRILL"));
        assertTrue(text.contains("Engineering Report V2"));
        assertTrue(!text.contains("CH01_FRONT_BUMPER_GRILL_AMBIENT"));
        service.closeProject();
        assertTrue(service.openSavedProject(after.projectId()).opened());
        EngineeringInvestigation reopened = service.engineeringInvestigations()
                .find(service.metadataStore(), saved.investigationId()).orElseThrow();
        assertEquals(List.of(ch02), reopened.signalKeyBlobs());
    }

    private ListView<?> reportSignals() {
        for (Node node : stage.getScene().getRoot().lookupAll(".list-view")) {
            if (node instanceof ListView<?> list
                    && list.getItems().contains("CH01_FRONT_BUMPER_GRILL_AMBIENT")) {
                return list;
            }
        }
        throw new AssertionError("Report signal list was not shown");
    }

    private String summary() {
        for (Node node : stage.getScene().getRoot().lookupAll(".label")) {
            if (node instanceof javafx.scene.control.Labeled labeled
                    && labeled.getText() != null && labeled.getText().startsWith("Included:")) {
                return labeled.getText();
            }
        }
        throw new AssertionError("Selection summary was not shown");
    }

    private Button removeButton() {
        for (Node node : stage.getScene().getRoot().lookupAll(".button")) {
            if (node instanceof Button button && "Add Signal".equals(button.getText())
                    && button.getParent() instanceof javafx.scene.layout.Pane row) {
                for (Node child : row.getChildren()) {
                    if (child instanceof Button remove && "Remove".equals(remove.getText())) {
                        return remove;
                    }
                }
            }
        }
        throw new AssertionError("Remove report signal was not shown");
    }
}
