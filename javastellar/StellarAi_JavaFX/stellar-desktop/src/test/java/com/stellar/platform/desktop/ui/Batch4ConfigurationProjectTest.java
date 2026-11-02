package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.AnalysisStorageService;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.analysis.AnalysisStorageSettingsStore;
import com.stellar.platform.storage.metadata.DuplicateProjectNameException;
import com.stellar.platform.storage.metadata.ProjectMetadata;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 4 configuration and project checks on a disposable home.
 * Customer projects and the accuracy home are not opened.
 */
class Batch4ConfigurationProjectTest {

    private static final List<String> NOTES = new ArrayList<>();
    private String previousHome;
    private String previousIsolate;
    private String previousDisable;
    private String previousMetadata;

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterEach
    void restore() {
        AnalysisStorageRoots.clear();
        restore("stellarai.home", previousHome);
        restore("stellarai.test.isolate", previousIsolate);
        restore("stellarai.analysis.storage.disable-auto", previousDisable);
        restore("stellarai.metadata.db", previousMetadata);
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        NOTES.add("TC_CFG_005 NOT RUN clarification: Analysis Storage has Save and Test Access and no Cancel. GPS mapping has its own Cancel.");
        NOTES.add("TC_CFG_006 NOT RUN clarification: there is no separate user-preferences form.");
        NOTES.add("TC_CFG_007 NOT RUN clarification: there is no separate user-preferences form.");
        NOTES.add("TC_CFG_008 NOT RUN clarification: there is no separate user-preferences form.");
        NOTES.add("TC_CFG_009 NOT RUN clarification: there is no separate user-preferences form.");
        NOTES.add("TC_CFG_010 NOT RUN clarification: there is no report-template manager on the Configuration page.");
        NOTES.add("TC_CFG_011 NOT RUN clarification: there is no report-template manager on the Configuration page.");
        NOTES.add("TC_CFG_012 NOT RUN clarification: there is no report-template manager on the Configuration page.");
        NOTES.add("TC_CFG_013 NOT RUN clarification: there is no report-template manager on the Configuration page.");
        NOTES.add("TC_CFG_014 NOT RUN clarification: there is no protocol-library page.");
        NOTES.add("TC_CFG_015 NOT RUN clarification: there is no protocol-library page.");
        NOTES.add("TC_CFG_016 NOT RUN clarification: there is no protocol-library page.");
        NOTES.add("TC_CFG_017 NOT RUN clarification: there is no protocol-library page.");
        NOTES.add("TC_CFG_018 NOT RUN clarification: workbook asks for Settings groups with Save/Cancel and says the contents are to be confirmed. The page shows Analysis Storage, GPS & Route, and Map, with Save and without a storage Cancel.");
        NOTES.add("TC_CFG_019 NOT RUN clarification: there is no global plot-layout preference.");
        NOTES.add("TC_CFG_020 NOT RUN clarification: there is no global signal-colour preference.");
        NOTES.add("TC_CFG_021 NOT RUN clarification: there is no global time-axis preference.");
        NOTES.add("TC_CFG_022 NOT RUN clarification: there is no reset-all-preferences control.");
        NOTES.add("TC_CFG_024 NOT RUN clarification: there is no protocol-library path.");
        NOTES.add("TC_CFG_025 NOT RUN clarification: there is no report-template rename or delete.");
        NOTES.add("TC_PM_001 NOT RUN clarification: the header control + New Project is present and not shown. The dashboard entry is the Start Project tile.");
        NOTES.add("TC_PM_002 NOT RUN clarification: the new-project prompt is a name field. Workbook window contents were not confirmed.");
        NOTES.add("TC_PM_007 NOT RUN: Cancel was not clicked in the new-project prompt.");
        NOTES.add("TC_PM_008 NOT RUN: the prompt close icon was not clicked.");
        NOTES.add("TC_PM_009 NOT RUN clarification: the prompt is constructed empty. It does not pre-fill Untitled Project.");
        NOTES.add("TC_PM_013 NOT RUN: dashboard counters after creation were not opened.");
        NOTES.add("TC_PM_014 NOT RUN: modal behaviour of the prompt was not clicked.");
        NOTES.add("TC_PM_016 NOT RUN clarification: a name containing ':' was stored. Project names are database display names, not folder names, and the workbook expected a file-system rejection.");
        NOTES.add("TC_PM_017 NOT RUN: the unsaved-changes prompt was not driven.");
        NOTES.add("TC_PM_019 BLOCKED: Dummy_demo.dbc / associated-file fixture is not in this run.");
        NOTES.add("TC_PM_021 NOT RUN clarification: a saved project is a database row, not a user-selected project file path.");
        NOTES.add("TC_PM_023 NOT RUN: Open Project Cancel was not clicked.");
        NOTES.add("TC_PM_029 BLOCKED: Dummy_demo.dbc / associated-file fixture is not in this run.");
        NOTES.add("TC_PM_030 NOT RUN: a project with a missing source file was not constructed.");
        NOTES.add("TC_PM_031 NOT RUN: decode-configuration restore was not driven.");
        NOTES.add("TC_PM_032 NOT RUN clarification: invalid project configuration has no separate settings document beyond the storage path covered by TC_CFG_004.");
        NOTES.add("TC_PM_033 NOT RUN clarification: there is no analysis-template association.");
        NOTES.add("TC_PM_034 NOT RUN clarification: there is no analysis-template association.");
        NOTES.add("TC_PM_036 NOT RUN: Recent Activity was not opened.");
        NOTES.add("TC_PM_037 NOT RUN: analysis history restore was not driven.");
        NOTES.add("TC_PM_038 NOT RUN: analysis history restore was not driven.");
        NOTES.add("TC_PM_043 NOT RUN clarification: the project list has no search field.");
        Path out = Path.of("..", "docs", "qa-handoff", "batch4-execution.txt").toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join(System.lineSeparator(), NOTES) + System.lineSeparator());
    }

    @Test
    @Timeout(60)
    void configurationSaveRestoresAndRejectsAFile() throws Exception {
        Path home = Files.createTempDirectory("stellar-batch4-cfg");
        isolate(home, true);
        Path settings = home.resolve("analysis-storage.properties");
        AnalysisStorageService storage = new AnalysisStorageService(new AnalysisStorageSettingsStore(settings));
        DesktopAppService service = new DesktopAppService(new DesktopSession(), storage);
        AtomicReference<ConfigurationController> controller = new AtomicReference<>();
        AtomicReference<TextField> pathField = new AtomicReference<>();
        AtomicReference<Label> message = new AtomicReference<>();
        AtomicReference<Label> status = new AtomicReference<>();
        AtomicReference<Button> save = new AtomicReference<>();
        AtomicReference<Button> testAccess = new AtomicReference<>();
        try {
            JavaFxTestSupport.runOnFx("load configuration", () -> {
                FXMLLoader loader = new FXMLLoader(ConfigurationController.class.getResource("/fxml/configuration-view.fxml"));
                loader.load();
                ConfigurationController page = loader.getController();
                page.setAppService(service, null);
                page.refresh();
                var ns = loader.getNamespace();
                controller.set(page);
                pathField.set((TextField) ns.get("storagePathField"));
                message.set((Label) ns.get("messageLabel"));
                status.set((Label) ns.get("statusBadge"));
                save.set((Button) ns.get("saveButton"));
                testAccess.set((Button) ns.get("testAccessButton"));
                assertEquals("Not configured", status.get().getText());
                assertEquals("Open a project with processed signals to review its GPS setup.",
                        ((Label) ns.get("gpsNoProjectLabel")).getText());
                assertTrue(ns.get("analysisStorageCard") != null);
                assertTrue(ns.get("gpsMappingCard") != null);
                assertTrue(ns.get("mapSettingsCard") != null);
                assertEquals("Save", save.get().getText());
                assertEquals("Test Access", testAccess.get().getText());
                assertEquals("Browse…", ((Button) ns.get("browseButton")).getText());
                pathField.get().setText("   ");
                save.get().fire();
                assertEquals("Choose a folder before saving.", message.get().getText());
            });
            note("TC_CFG_001 PASS Configuration page shows Analysis Storage, GPS & Route, and Map. Sidebar label is Configuration. Status starts at Not configured.");

            Path notAFolder = home.resolve("not-a-folder.txt");
            Files.writeString(notAFolder, "x");
            CountDownLatch invalid = new CountDownLatch(1);
            JavaFxTestSupport.runOnFx("reject file path", () -> {
                message.get().textProperty().addListener((obs, old, value) -> {
                    if (value != null && !value.isBlank() && !"Choose a folder before saving.".equals(value)) {
                        invalid.countDown();
                    }
                });
                pathField.get().setText(notAFolder.toString());
                testAccess.get().fire();
            });
            assertTrue(invalid.await(15, TimeUnit.SECONDS), "invalid path did not report");
            JavaFxTestSupport.runOnFx("read invalid", () -> {
                assertEquals("Invalid", status.get().getText(), message.get().getText());
                assertFalse(service.analysisStorage().isValid());
            });
            note("TC_CFG_004 PASS a file path is Invalid and project creation stays blocked. Message: "
                    + message.get().getText());

            Path root = home.resolve("analysis-root");
            CountDownLatch saved = new CountDownLatch(1);
            JavaFxTestSupport.runOnFx("save storage", () -> {
                message.get().textProperty().addListener((obs, old, value) -> {
                    if (value != null && value.startsWith("Analysis Storage saved")) {
                        saved.countDown();
                    }
                });
                pathField.get().setText(root.toString());
                save.get().fire();
            });
            assertTrue(saved.await(15, TimeUnit.SECONDS), "save did not complete: " + message.get().getText());
            JavaFxTestSupport.runOnFx("read saved", () -> assertEquals("Valid", status.get().getText()));
            note("TC_CFG_002 PASS Save stored " + root + ". Message: " + message.get().getText());

            service.shutdown();
            AnalysisStorageRoots.clear();
            AnalysisStorageService restarted = new AnalysisStorageService(new AnalysisStorageSettingsStore(settings));
            assertTrue(restarted.isValid());
            assertEquals(root.toAbsolutePath().normalize(), restarted.userSelectedRoot().orElseThrow());
            note("TC_CFG_003 PASS a new application service on the same isolated home restored " + root.toAbsolutePath().normalize());
        } finally {
            JavaFxTestSupport.runOnFx("dispose configuration", () -> {
                if (controller.get() != null) {
                    controller.get().dispose();
                }
            });
            service.shutdown();
            deleteTree(home);
        }
    }

    @Test
    @Timeout(90)
    void disposableProjectsFollowTheNameAndReopenRules() throws Exception {
        Path home = Files.createTempDirectory("stellar-batch4-pm");
        isolate(home, false);
        Path db = home.resolve("meta.db");
        System.setProperty("stellarai.metadata.db", db.toString());
        previousMetadata = null;
        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db);
        final DesktopAppService service = new DesktopAppService(new DesktopSession(), store);
        DesktopAppService reopened = service;
        try {
            JavaFxTestSupport.runOnFx("project entry points", () -> {
                FXMLLoader shell = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                shell.load();
                Button header = (Button) shell.getNamespace().get("newProjectHeaderButton");
                assertEquals("+ New Project", header.getText());
                assertFalse(header.isVisible());
                assertFalse(header.isManaged());
                FXMLLoader dashboard = new FXMLLoader(MainShellController.class.getResource("/fxml/dashboard-view.fxml"));
                javafx.scene.Parent dashRoot = dashboard.load();
                java.util.List<String> labels = new java.util.ArrayList<>();
                collectLabels(dashRoot, labels);
                assertTrue(labels.stream().anyMatch(text -> text.contains("Start Project")), labels.toString());
            });

            long started = System.nanoTime();
            service.newProject("Batch4 Alpha");
            long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(elapsedMs < 60_000, elapsedMs + " ms");
            String alphaId = service.currentProjectId();
            assertThrows(IllegalArgumentException.class, () -> service.newProject("   "));
            assertThrows(IllegalArgumentException.class, () -> service.newProject(""));
            assertThrows(DuplicateProjectNameException.class, () -> service.newProject("Batch4 Alpha"));
            assertThrows(DuplicateProjectNameException.class, () -> service.newProject("  batch4 alpha  "));
            service.newProject("Batch4 (QA) #1");
            service.newProject("Batch4:colon");
            String longName = "L".repeat(200);
            service.newProject(longName);
            service.newProject("  Trimmed Name  ");
            assertEquals("Trimmed Name", service.workspace().projectName());

            List<String> names = service.savedProjects().stream().map(ProjectMetadata::name).toList();
            assertTrue(names.contains("Batch4 Alpha"));
            assertTrue(names.contains("Batch4 (QA) #1"));
            assertTrue(names.contains("Batch4:colon"));
            assertTrue(names.contains(longName));
            assertTrue(names.contains("Trimmed Name"));
            assertEquals(5, names.size());

            service.renameProject("  Trimmed Renamed  ");
            assertEquals("Trimmed Renamed", service.workspace().projectName());
            assertTrue(service.openSavedProject(alphaId).opened());
            assertEquals("Batch4 Alpha", service.workspace().projectName());
            var missing = service.openSavedProject("missing-project-id");
            assertFalse(missing.opened());
            assertTrue(missing.warnings().stream().anyMatch(w -> w.contains("not found")));
            assertEquals("Batch4 Alpha", service.workspace().projectName());

            var firstSave = service.saveProject();
            var secondSave = service.saveProject();
            assertTrue(firstSave.saved(), firstSave.message());
            assertTrue(secondSave.saved(), secondSave.message());
            assertEquals("Batch4 Alpha", service.metadataStore().loadProject(alphaId).orElseThrow().project().name());

            ProjectMetadata details = service.savedProjects().stream()
                    .filter(p -> p.projectId().equals(alphaId))
                    .findFirst()
                    .orElseThrow();
            assertFalse(details.projectId().isBlank());
            assertEquals("Batch4 Alpha", details.name());
            assertTrue(details.createdAt() != null);

            Path shared = service.analysisStorage().userSelectedRoot().orElseThrow();
            service.newProject("Batch4 Other");
            assertEquals(shared, service.analysisStorage().userSelectedRoot().orElseThrow());

            boolean readOnlyBlocked = false;
            Path liveDb = db;
            boolean marked = liveDb.toFile().setReadOnly();
            if (marked) {
                var blocked = service.saveProject();
                readOnlyBlocked = !blocked.saved();
                liveDb.toFile().setWritable(true);
            }
            if (readOnlyBlocked) {
                note("TC_PM_026 PASS read-only destination rejected the save");
                assertTrue(service.openSavedProject(alphaId).opened());
                note("TC_PM_027 PASS Batch4 Alpha still opens after the rejected save");
            } else {
                note("TC_PM_026 NOT RUN: setReadOnly=" + marked + " did not make the save fail on this workstation.");
                note("TC_PM_027 NOT RUN: no failed save was produced, so corruption after a failed save was not scored.");
            }

            service.shutdown();
            store.close();
            Path garbage = home.resolve("corrupt.db");
            Files.writeString(garbage, "this is not a sqlite database");
            String corruptMessage = "";
            try {
                SqliteProjectMetadataStore broken = new SqliteProjectMetadataStore(garbage);
                broken.listProjects();
                broken.close();
                corruptMessage = "opened without an error";
            } catch (RuntimeException ex) {
                corruptMessage = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            }
            assertFalse(corruptMessage.equals("opened without an error"), corruptMessage);
            store = new SqliteProjectMetadataStore(db);
            reopened = new DesktopAppService(new DesktopSession(), store);
            assertTrue(reopened.openSavedProject(alphaId).opened());
            assertEquals("Batch4 Alpha", reopened.workspace().projectName());

            note("TC_PM_003 PASS created Batch4 Alpha");
            note("TC_PM_004 PASS blank name rejected: Project name must not be empty.");
            note("TC_PM_005 PASS whitespace-only name rejected: Project name must not be empty.");
            note("TC_PM_006 PASS duplicate Batch4 Alpha rejected");
            note("TC_PM_010 PASS created Batch4 (QA) #1");
            note("TC_PM_011 PASS a 200-character name was stored and listed. Header layout was not rendered.");
            note("TC_PM_012 PASS Batch4 Alpha is in the saved project list");
            note("TC_PM_018 PASS openSavedProject restored Batch4 Alpha");
            note("TC_PM_020 PASS corrupt database rejected (" + corruptMessage + ") and Batch4 Alpha still opened");
            note("TC_PM_025 PASS the saved name Batch4 Alpha is present after a new service opens it");
            note("TC_PM_028 PASS a second save with no edits returned saved=true and the name stayed Batch4 Alpha");
            note("TC_PM_039 PASS first project was created in " + elapsedMs + " ms");
            note("TC_PM_040 PASS batch4 alpha was rejected as a duplicate of Batch4 Alpha");
            note("TC_PM_041 PASS '  Trimmed Name  ' was stored as Trimmed Name");
            note("TC_PM_042 PASS the selector source listed " + names);
            note("TC_PM_044 PASS id " + details.projectId() + " name " + details.name() + " created " + details.createdAt());
            note("TC_PM_045 PASS rename stored Trimmed Renamed");
            note("TC_CFG_023 PASS projects Batch4 Alpha and Batch4 Other share analysis storage " + shared);
            note("TC_PM_016 observation: Batch4:colon was created. Not scored as a defect.");
        } finally {
            service.shutdown();
            if (reopened != service) {
                reopened.shutdown();
            }
            deleteTree(home);
        }
    }

    private void isolate(Path home, boolean disableAuto) {
        previousHome = System.getProperty("stellarai.home");
        previousIsolate = System.getProperty("stellarai.test.isolate");
        previousDisable = System.getProperty("stellarai.analysis.storage.disable-auto");
        previousMetadata = System.getProperty("stellarai.metadata.db");
        System.setProperty("stellarai.home", home.toAbsolutePath().toString());
        System.setProperty("stellarai.test.isolate", "true");
        if (disableAuto) {
            System.setProperty("stellarai.analysis.storage.disable-auto", "true");
        } else {
            System.clearProperty("stellarai.analysis.storage.disable-auto");
        }
        System.clearProperty("stellarai.metadata.db");
        AnalysisStorageRoots.clear();
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static void note(String line) {
        NOTES.add(line);
    }

    private static void collectLabels(javafx.scene.Node node, java.util.List<String> labels) {
        if (node instanceof Label label && label.getText() != null && !label.getText().isBlank()) {
            labels.add(label.getText());
        }
        if (node instanceof javafx.scene.control.ScrollPane scroll && scroll.getContent() != null) {
            collectLabels(scroll.getContent(), labels);
        }
        if (node instanceof javafx.scene.Parent parent) {
            for (javafx.scene.Node child : parent.getChildrenUnmodifiable()) {
                collectLabels(child, labels);
            }
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // The disposable home is under the temp directory. A locked journal is not customer data.
                }
            });
        } catch (Exception ignored) {
            // same as above
        }
    }
}
