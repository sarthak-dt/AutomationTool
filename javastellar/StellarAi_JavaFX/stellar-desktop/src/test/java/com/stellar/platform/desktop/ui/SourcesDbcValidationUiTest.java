package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.FileValidationStatus;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Visible Sources workspace checks for definition import. The file chooser is not opened;
 * the test calls the same controller path the Add Definitions button uses after a selection.
 */
class SourcesDbcValidationUiTest {

    @TempDir
    Path temp;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(90)
    void sourcesScreenRejectsMalformedDbcAndKeepsSparseAndCustomerDbcs() throws Exception {
        Path home = temp.resolve("home");
        Path db = temp.resolve("meta.db");
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        System.setProperty("stellarai.metadata.db", db.toString());

        Path corrupt = temp.resolve("corrupt.dbc");
        Files.write(corrupt, new byte[]{0x00, 0x01, 0x02, 0x03, 0x41, 0x42});
        Path sparse = temp.resolve("sparse.dbc");
        Files.writeString(sparse, """
                VERSION ""

                NS_ :
                \tCM_
                BS_:

                BU_: NodeA
                """);
        Path customer = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\dbcfiles\\1.dbc");
        Path asc = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData\\ASC\\"
                + "ASCII_eCC21_KA04NC7760_Bengaluru_09_08_2023_0002360_20230814_145851.asc");

        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            DesktopAppService service = null;
            try {
                service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
                service.newProject("Batch1Closure");
                FXMLLoader shellLoader = new FXMLLoader(
                        MainShellController.class.getResource("/fxml/main-shell.fxml"));
                shellLoader.load();
                MainShellController shell = shellLoader.getController();
                shell.init(service);
                FXMLLoader pfLoader = new FXMLLoader(
                        ProjectFilesController.class.getResource("/fxml/project-files-view.fxml"));
                BorderPane view = pfLoader.load();
                ProjectFilesController sources = pfLoader.getController();
                sources.setAppService(service, shell);
                sources.openSourcesWorkspace();
                Stage stage = new Stage();
                stage.setScene(new Scene(view, 1280, 800));
                stage.show();

                sources.addDefinitionPathsForTest(List.of(corrupt));
                String rejected = sources.addResultLabelForTest().getText();
                assertTrue(rejected.contains("not a DBC"), rejected);
                assertTrue(sources.addResultLabelForTest().isVisible());
                assertTrue(sources.definitionNamesForTest().isEmpty(), sources.definitionNamesForTest().toString());
                assertTrue(service.workspace().definitionFiles().isEmpty());

                sources.addDefinitionPathsForTest(List.of(sparse));
                assertEquals(List.of("sparse.dbc"), sources.definitionNamesForTest());
                assertEquals(FileValidationStatus.READY, service.workspace().definitionFiles().getFirst().status());
                assertEquals(FileKind.DEFINITION_DBC, service.workspace().definitionFiles().getFirst().kind());

                if (Files.isRegularFile(customer)) {
                    sources.addDefinitionPathsForTest(List.of(customer));
                    assertTrue(sources.definitionNamesForTest().contains("1.dbc"),
                            sources.definitionNamesForTest().toString());
                    sources.addDefinitionPathsForTest(List.of(customer));
                    String duplicate = sources.addResultLabelForTest().getText();
                    assertTrue(duplicate.contains("already added"), duplicate);
                    assertEquals(1, sources.definitionNamesForTest().stream().filter(name -> name.equals("1.dbc")).count());
                }

                if (Files.isRegularFile(asc)) {
                    sources.addMeasurementPathsForTest(List.of(asc));
                    var list = sources.measurementSourceListForTest();
                    list.setMinHeight(280);
                    list.setPrefHeight(280);
                    view.applyCss();
                    view.layout();
                    String visible = collectText(list);
                    assertTrue(visible.contains("ASCII_eCC21"), visible);
                    assertEquals("1", sources.measurementCountBadgeForTest().getText());
                    assertEquals(asc.toAbsolutePath().normalize(),
                            service.workspace().measurementFiles().getFirst().path().toAbsolutePath().normalize());
                    assertEquals(Files.size(asc), Files.size(service.workspace().measurementFiles().getFirst().path()));
                }
                stage.close();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                if (service != null) {
                    service.shutdown();
                }
                done.countDown();
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "JavaFX Sources scenario timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static String collectText(Node node) {
        StringBuilder sb = new StringBuilder();
        if (node instanceof Labeled labeled && labeled.getText() != null) {
            sb.append(labeled.getText()).append('\n');
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                sb.append(collectText(child));
            }
        }
        return sb.toString();
    }
}
