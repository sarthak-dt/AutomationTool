package com.stellar.platform.desktop.ui;

import com.stellar.platform.analysis.investigation.EngineeringEvidenceItem;
import com.stellar.platform.analysis.investigation.EngineeringInvestigation;
import com.stellar.platform.analysis.investigation.EngineeringObservation;
import com.stellar.platform.analysis.investigation.EvidenceKind;
import com.stellar.platform.analysis.investigation.InvestigationStoragePaths;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestigationWorkspaceUiTest {

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
        JavaFxTestSupport.runOnFx("dispose investigations", () -> {
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
    void snapshotLabelOmitsTheTechnicalPrefix() {
        EngineeringEvidenceItem snapshot = new EngineeringEvidenceItem(
                "e", "inv", EvidenceKind.GRAPH_SNAPSHOT, "snap", 0, false, "", "", 0L, "11", "11", Instant.now());
        EngineeringEvidenceItem other = new EngineeringEvidenceItem(
                "d", "inv", EvidenceKind.DIAGNOSTIC_FINDING, "find", 1, false, "", "", 0L, "Rule hit", "", Instant.now());
        EngineeringEvidenceItem legacy = new EngineeringEvidenceItem(
                "old", "inv", EvidenceKind.GRAPH_SNAPSHOT, "old-snap", 2, false, "", "", 0L, "", "Graph snapshot", Instant.now());
        assertEquals("1. 11", InvestigationController.evidenceLabel(snapshot));
        assertFalse(InvestigationController.evidenceLabel(snapshot).contains("GRAPH_SNAPSHOT"));
        assertEquals("2. DIAGNOSTIC_FINDING — Rule hit", InvestigationController.evidenceLabel(other));
        assertEquals("3. GRAPH_SNAPSHOT — Graph snapshot", InvestigationController.evidenceLabel(legacy));
    }

    @Test
    @Timeout(60)
    void renameKeepsTheInvestigationAndPdfUsesTheEnteredName() throws Exception {
        open();
        service.newProject("Investigations A");
        EngineeringInvestigation created = service.createInvestigation("Coast", "Driver report", "CURRENT");
        EngineeringInvestigation stored = service.saveInvestigation(created.toBuilder()
                .observations(List.of(new EngineeringObservation(
                        null, created.investigationId(), null, null, "Brake", "pad note", Instant.now(), Instant.now())))
                .evidence(List.of(
                        new EngineeringEvidenceItem(null, created.investigationId(), EvidenceKind.GRAPH_SNAPSHOT,
                                "snap-a", 0, false, "", "", 0L, "11", "11", Instant.now()),
                        new EngineeringEvidenceItem(null, created.investigationId(), EvidenceKind.GRAPH_SNAPSHOT,
                                "snap-b", 1, false, "", "", 0L, "Brake Pressure", "Brake Pressure", Instant.now())))
                .build());
        service.newProject("Investigations B");
        EngineeringInvestigation other = service.createInvestigation("Other project", "", "FULL");
        assertTrue(service.saveProject().saved());

        JavaFxTestSupport.runOnFx("reject a blank or cross-project rename", () -> {
            controller.refresh();
            var dialog = controller.renameDialogForTest("Coast");
            assertEquals("Rename Investigation", dialog.getTitle());
            TextField field = renameField(dialog);
            assertEquals("Coast", field.getText());
            field.setText("   ");
            Button rename = (Button) dialog.getDialogPane().lookupButton(
                    dialog.getDialogPane().getButtonTypes().stream()
                            .filter(type -> "Rename".equals(type.getText())).findFirst().orElseThrow());
            assertTrue(rename.isDisabled());
            assertTrue(dialog.getDialogPane().getButtonTypes().contains(ButtonType.CANCEL));
            assertFalse(controller.commitRenameForTest(stored, "   "));
            assertFalse(controller.commitRenameForTest(stored, "Hijack"));
        });
        assertEquals("Coast", service.engineeringInvestigations()
                .find(service.metadataStore(), stored.investigationId()).orElseThrow().name());
        assertEquals("Other project", service.engineeringInvestigations()
                .find(service.metadataStore(), other.investigationId()).orElseThrow().name());

        service.closeProject();
        assertTrue(service.openSavedProject(stored.projectId()).opened());
        JavaFxTestSupport.runOnFx("rename the selected investigation", () -> {
            controller.setAppService(service, null);
            controller.refresh();
            EngineeringInvestigation current = service.engineeringInvestigations()
                    .find(service.metadataStore(), stored.investigationId()).orElseThrow();
            assertTrue(controller.commitRenameForTest(current, "  Renamed coast  "));
            var menu = controller.investigationContextMenuForTest(current);
            assertEquals("Rename Investigation", menu.getItems().getFirst().getText());
            Parent root = stage.getScene().getRoot();
            List<String> labels = new ArrayList<>();
            for (var node : root.lookupAll(".button")) {
                if (node instanceof Labeled labeled && labeled.getText() != null && !labeled.getText().isBlank()) {
                    labels.add(labeled.getText());
                }
            }
            assertTrue(labels.contains("Open Graph Analysis"));
            assertFalse(labels.contains("Open Graph Context"));
            assertFalse(labels.contains("Focus on Graph"));
            assertFalse(labels.contains("Cancel Generation"));
            assertEquals(labels.indexOf("Generate HTML Report") + 1, labels.indexOf("Generate PDF Report"));
        });

        EngineeringInvestigation renamed = service.engineeringInvestigations()
                .find(service.metadataStore(), stored.investigationId()).orElseThrow();
        assertEquals(stored.investigationId(), renamed.investigationId());
        assertEquals("Renamed coast", renamed.name());
        assertEquals(List.of("11", "Brake Pressure"), renamed.evidence().stream().map(EngineeringEvidenceItem::caption).toList());
        assertEquals("pad note", renamed.observations().getFirst().note());
        assertEquals("1. 11", InvestigationController.evidenceLabel(renamed.evidence().getFirst()));
        assertEquals("2. Brake Pressure", InvestigationController.evidenceLabel(renamed.evidence().get(1)));

        Path shot = InvestigationStoragePaths.snapshot(
                AnalysisStorageRoots.projectArtifactsRoot(renamed.projectId()),
                renamed.investigationId(), renamed.evidence().getFirst().referenceId());
        Files.createDirectories(shot.getParent());
        BufferedImage picture = new BufferedImage(60, 30, BufferedImage.TYPE_INT_RGB);
        var graphics = picture.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 60, 30);
        graphics.dispose();
        assertTrue(ImageIO.write(picture, "png", shot.toFile()));
        assertTrue(Files.isRegularFile(shot), shot.toString());

        Path folder = temp.resolve("reports");
        Files.createDirectories(folder);
        Path html = service.generateInvestigationReport(renamed, "Ada", folder.resolve("Renamed coast.html"));
        String htmlText = Files.readString(html);
        Path pdf = service.generateInvestigationPdfReport(renamed, "Ada", folder.resolve("Renamed coast.pdf"));
        assertTrue(pdf.getFileName().toString().endsWith(".pdf"));
        assertEquals("%PDF", new String(Files.readAllBytes(pdf), 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            String text = new PDFTextStripper().getText(document);
            for (String fact : List.of("Renamed coast", "Driver report", "pad note", "11", "Brake Pressure")) {
                assertTrue(text.contains(fact), fact);
                assertTrue(htmlText.contains(fact), fact);
            }
            boolean embedded = false;
            for (var page : document.getPages()) {
                if (page.getResources() == null) {
                    continue;
                }
                for (var name : page.getResources().getXObjectNames()) {
                    PDXObject object = page.getResources().getXObject(name);
                    if (object instanceof PDImageXObject) {
                        embedded = true;
                    }
                }
            }
            assertTrue(embedded, text);
        }
        assertThrows(Exception.class, () -> service.engineeringInvestigations().writePdfReport(
                service.metadataStore(), renamed, "Ada", "", pdf, null, null, List.of()));

        assertTrue(service.saveProject().saved());
        String projectId = service.currentProjectId();
        service.closeProject();
        assertTrue(service.openSavedProject(projectId).opened());
        EngineeringInvestigation reopened = service.engineeringInvestigations()
                .find(service.metadataStore(), stored.investigationId()).orElseThrow();
        assertEquals("Renamed coast", reopened.name());
        assertEquals(List.of("11", "Brake Pressure"), reopened.evidence().stream().map(EngineeringEvidenceItem::caption).toList());
        assertEquals("Other project", service.engineeringInvestigations()
                .find(service.metadataStore(), other.investigationId()).orElseThrow().name());
    }

    private static TextField renameField(javafx.scene.control.Dialog<?> dialog) {
        if (dialog.getDialogPane().getContent() instanceof VBox body) {
            for (Node node : body.getChildren()) {
                if (node instanceof TextField field) {
                    return field;
                }
            }
        }
        throw new AssertionError("Rename dialog has no name field");
    }

    private void open() throws Exception {
        System.setProperty("stellarai.home", temp.resolve("home").toAbsolutePath().toString());
        System.setProperty("stellarai.metadata.db", temp.resolve("meta.db").toString());
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        JavaFxTestSupport.runOnFx("load investigations", () -> {
            FXMLLoader loader = new FXMLLoader(InvestigationController.class.getResource("/fxml/investigation-view.fxml"));
            Parent root = loader.load();
            controller = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1366, 768));
            stage.show();
            controller.setAppService(service, null);
        });
        JavaFxTestSupport.drainFxPulse();
    }
}
