package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EngineerEventDefinition;
import com.stellar.platform.analysis.investigation.InvestigationReportConfig;
import com.stellar.platform.analysis.investigation.eval.EvaluationOutcome;
import com.stellar.platform.analysis.investigation.eval.EvaluationRule;
import com.stellar.platform.analysis.investigation.eval.EvaluationSample;
import com.stellar.platform.analysis.investigation.eval.EvaluationSeries;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Report-tab Evaluate action saves a draft rule and a result from stored samples. */
class EvaluationRuleUiTest {

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
        JavaFxTestSupport.runOnFx("dispose evaluation", () -> {
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
        }
        JavaFxTestSupport.drainFxPulse();
    }

    @Test
    @Timeout(60)
    void evaluateButtonSavesADraftAndReopensTheResult() throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        service.newProject("Evaluation UI");
        var created = service.createInvestigation("Events", "QA", "FULL");
        service.saveInvestigation(created.toBuilder().reportConfig(InvestigationReportConfig.engineeringV2()).build());
        String eventId = service.saveEngineerEvent(new EngineerEventDefinition(
                "", "Window", "Thermal observation", "src", "file.mf4", 0L, 1_000_000_000L,
                List.of("CH01"), "", "", "", List.of(), false, "Draft", "", "", ""));
        JavaFxTestSupport.runOnFx("load investigation", () -> {
            FXMLLoader loader = new FXMLLoader(InvestigationController.class.getResource("/fxml/investigation-view.fxml"));
            Parent root = loader.load();
            controller = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1200, 800));
            stage.show();
            controller.setAppService(service, null);
            controller.setOpenGeneratedReportForTest(false);
            controller.refresh();
            long buttons = stage.getScene().getRoot().lookupAll(".button").stream()
                    .filter(node -> node instanceof Button button && "Evaluate".equals(button.getText()) && !button.isDisabled())
                    .count();
            assertEquals(1, buttons);
        });
        EvaluationRule[] saved = new EvaluationRule[1];
        JavaFxTestSupport.runOnFx("save the draft rule", () -> {
            EvaluationRuleEditor editor = controller.evaluationEditorForTest(eventId);
            assertEquals("Evaluate event", editor.dialog().getTitle());
            assertTrue(editor.dialog().getDialogPane().getButtonTypes().stream()
                    .anyMatch(button -> "Run evaluation".equals(button.getText())));
            editor.fill("QA procedure", "1", "CH01", EvaluationRule.ComparisonOperator.MINIMUM_WITHIN_RANGE,
                    "0", "5", "", "", "C", "", "1", "1",
                    "QA-only fixture. Not a customer-approved criterion.");
            saved[0] = controller.saveEvaluationForTest(editor);
        });
        assertNotNull(saved[0]);
        assertEquals(1, saved[0].version());
        assertTrue(saved[0].exploratory());
        var event = com.stellar.platform.desktop.presentation.EngineerEventDocuments.decode(
                service.findEngineeringBookmark(eventId).orElseThrow());
        var result = service.evaluateAndStore(event, saved[0], List.of(new EvaluationSeries("CH01", "C", "src", List.of(
                new EvaluationSample(0L, 1.0d, true, ""),
                new EvaluationSample(1_000_000_000L, 2.0d, true, "")))), true, true);
        assertEquals(EvaluationOutcome.MEETS_CRITERION, result.outcome());
        String projectId = service.currentProjectId();
        service.closeProject();
        assertTrue(service.openSavedProject(projectId).opened());
        assertEquals(1, service.listEvaluationRules().size());
        assertEquals("QA procedure", service.listEvaluationRules().getFirst().procedureName());
        Path html = service.generateInvestigationReport(
                service.engineeringInvestigations().find(service.metadataStore(), created.investigationId()).orElseThrow(),
                "Ada", temp.resolve("ui.html"));
        String text = Files.readString(html);
        assertTrue(text.contains("Expected vs Actual"));
        assertTrue(text.contains("Meets criterion"));
        assertTrue(text.contains("QA-only fixture"));
        assertFalse(text.contains("PASS"));
    }
}
