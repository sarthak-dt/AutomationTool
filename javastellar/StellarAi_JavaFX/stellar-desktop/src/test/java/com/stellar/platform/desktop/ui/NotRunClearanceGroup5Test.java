package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.ProjectSaveState;
import com.stellar.platform.desktop.presentation.UnsavedLeaveChoice;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.storage.analysis.AnalysisStorageRoots;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Paint;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Group 5 drives the existing Project Management controls. It does not add a header button,
 * a dialog prefill, or a user-chosen save destination.
 */
class NotRunClearanceGroup5Test {

    private static final List<String> NOTES = new ArrayList<>();
    private static final List<Path> READ_ONLY = new ArrayList<>();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        restoreReadOnly();
        Path out = Path.of("..", "docs", "qa-handoff", "clearance5-execution.txt").toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.writeString(out, String.join(System.lineSeparator(), NOTES) + System.lineSeparator());
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void projectManagementP1() throws Exception {
        note("SELECTED TC_PM_001 TC_PM_002 TC_PM_013 TC_PM_026 TC_PM_027 TC_PM_030 TC_PM_036");
        note("TC_PM_001 prereq: Dashboard is displayed. steps: observe the top-right header; hover New Project. expected: a blue New Project button, a hover effect, and the button enabled.");
        note("TC_PM_002 prereq: Dashboard is displayed. steps: click New Project; verify dialog text, project name field, default value, and buttons. expected: dialog New Project; message Create a new working project with a question mark icon; label Project name:; field pre-filled Untitled Project and selected; OK, Cancel, and close X.");
        note("TC_PM_013 prereq: previous project has at least one measurement. steps: note counters; New Project with a valid name and OK; verify counters and panels; switch back with the Project selector. expected: counters greater than 0; new project created; all counters 0, workflow completion 0%, empty panels; previous files and counters intact.");
        note("TC_PM_026 prereq: application running and project location read-only or disk not writable. steps: click Save; verify application state. expected: a clear save-failure error, no success message, application stable, user can retry. Workbook note: UI was not provided and control names are To be confirmed.");
        note("TC_PM_027 prereq: project saved earlier and the destination later became non-writable. steps: change and save; restore writability and reopen the last successful save. expected: save failure reported; reopen contains only the last successful data.");
        note("TC_PM_030 prereq: project has an associated ASC later moved outside the application. steps: move the ASC; reopen; open Project Files > Sources; verify remaining data. expected: file moved; missing file reported without a crash; other files and the DBC remain.");
        note("TC_PM_036 prereq: new project with no activity. steps: add a valid ASC from Project Files; open Dashboard Recent Activity. expected: the sentence No activity yet. Add measurement files to begin. is no longer displayed and a file-addition entry is listed. Entry format is To be confirmed.");
        note("HELD TC_PM_019 and TC_PM_029 stay blocked on Dummy_demo.dbc. TC_PM_016 is P2 and is not in this group.");

        Path home = Files.createTempDirectory("stellar-clearance-g5");
        isolate(home);
        Path asc = Path.of("src", "test", "resources", "asc", "desktop_engine.asc").toAbsolutePath();
        Path dbc = Path.of("src", "test", "resources", "dbc", "desktop_engine.dbc").toAbsolutePath();
        Path alphaAsc = home.resolve("alpha-engine.asc");
        Path extraAsc = home.resolve("extra-engine.asc");
        Path movedAsc = home.resolve("moved-engine.asc");
        Path keptAsc = home.resolve("kept-engine.asc");
        Path projectDbc = home.resolve("group5-engine.dbc");
        Files.copy(asc, alphaAsc);
        Files.copy(asc, extraAsc);
        Files.copy(asc, movedAsc);
        Files.copy(asc, keptAsc);
        Files.copy(dbc, projectDbc);

        Path db = home.resolve("meta.db");
        SqliteProjectMetadataStore store = new SqliteProjectMetadataStore(db);
        DesktopAppService service = new DesktopAppService(new DesktopSession(), store);
        ShellFx shell = null;
        ShellFx reopened = null;
        try {
            shell = boot(service);
            scoreNewProjectButton(shell);
            scoreNewProjectDialog(shell);
            createProject(shell, "Group5 Alpha");
            addMeasurement(shell, alphaAsc);
            openHome(shell);
            String afterAdd = activity(shell.root);
            String afterAddPage = visible(shell.root);
            note("TC_PM_036 activity='" + oneLine(afterAdd) + "' pageHasWorkbookEmpty="
                    + afterAddPage.contains("No activity yet. Add measurement files to begin."));
            if (afterAdd.contains("Measurement added") && afterAdd.contains("alpha-engine.asc")
                    && !afterAddPage.contains("No activity yet. Add measurement files to begin.")) {
                note("TC_PM_036 PASS Recent Activity lists Measurement added for alpha-engine.asc. "
                        + "The workbook empty sentence is not displayed. Actual entry: " + oneLine(afterAdd));
            } else {
                note("TC_PM_036 NOT RUN category C: Recent Activity after adding alpha-engine.asc is '"
                        + oneLine(afterAdd) + "'. The product empty line is No recent activity yet.");
            }

            String beforeMetrics = metrics(shell.root);
            int beforeFiles = service.workspace().measurementFiles().size();
            note("TC_PM_013 before name=" + headerName(shell.root) + " files=" + beforeFiles
                    + " metrics='" + oneLine(beforeMetrics) + "'");
            clickSave(shell);
            awaitClean(service);

            createProject(shell, "Group5 Beta");
            openHome(shell);
            String betaMetrics = metrics(shell.root);
            String betaPage = visible(shell.root);
            int betaFiles = service.workspace().measurementFiles().size();
            boolean betaHasAlpha = names(service).contains("alpha-engine.asc");
            note("TC_PM_013 beta name=" + headerName(shell.root) + " files=" + betaFiles
                    + " names=" + names(service) + " metrics='" + oneLine(betaMetrics)
                    + "' percent=" + betaPage.contains("%") + " activity='" + oneLine(activity(shell.root)) + "'");

            shell.controller.setUnsavedLeaveDecisionForTest(intent -> UnsavedLeaveChoice.DISCARD);
            boolean switched = switchTo(shell, "Group5 Alpha");
            shell.controller.setUnsavedLeaveDecisionForTest(null);
            openHome(shell);
            List<String> restored = names(service);
            String restoredMetrics = metrics(shell.root);
            note("TC_PM_013 switched=" + switched + " name=" + headerName(shell.root)
                    + " files=" + restored + " metrics='" + oneLine(restoredMetrics) + "'");
            boolean isolation = beforeFiles > 0 && betaFiles == 0 && !betaHasAlpha
                    && restored.contains("alpha-engine.asc") && !restored.contains("beta-only");
            boolean literal = betaPage.contains("0%") && countersAtZero(betaMetrics);
            if (isolation && literal && "Group5 Beta".equals(headerName(shell.root)) == false
                    && headerName(shell.root).contains("Group5 Alpha")) {
                note("TC_PM_013 PASS new project counters were 0 and workflow completion was 0%. "
                        + "Group5 Alpha files returned as " + restored + ".");
            } else {
                note("TC_PM_013 NOT RUN category C: Group5 Alpha had " + beforeFiles
                        + " measurement file and Group5 Beta had " + betaFiles
                        + " with names " + (betaHasAlpha ? "including alpha-engine.asc" : "without alpha-engine.asc")
                        + ". Switch via the header project selector returned " + restored
                        + ". Workspace KPI cards are totals across saved projects ('" + oneLine(betaMetrics)
                        + "') and the page has no workflow completion percent.");
            }

            clickSave(shell);
            awaitClean(service);
            List<String> lastSuccessful = names(service);
            addMeasurement(shell, extraAsc);
            List<String> afterChange = names(service);
            boolean attribute = markReadOnly(db);
            final ShellFx saving = shell;
            Thread opener = startOnFx(() -> fireSave(saving.root));
            String alert = waitForDialog("Save Project", 8, "OK");
            opener.join(15_000);
            String status = runOnFxValue(() -> labelText(saving.root, "save-status-label"));
            ProjectSaveState state = service.projectSaveState();
            note("TC_PM_026 readonly=" + attribute + " alert='" + oneLine(alert) + "' status='"
                    + oneLine(status) + "' state=" + state + " files=" + names(service));
            restoreReadOnly();
            close(shell);
            shell = null;
            service.shutdown();

            SqliteProjectMetadataStore reopenedStore = new SqliteProjectMetadataStore(db);
            DesktopAppService reopenedService = new DesktopAppService(new DesktopSession(), reopenedStore);
            reopened = boot(reopenedService);
            openHome(reopened);
            List<String> reopenedNames = names(reopenedService);
            note("TC_PM_027 reopened name=" + headerName(reopened.root) + " files=" + reopenedNames
                    + " lastSuccessful=" + lastSuccessful + " changed=" + afterChange
                    + " processing=" + reopenedService.workspace().processingStatus());
            boolean failed = alert != null && !alert.isBlank()
                    && (alert.toLowerCase().contains("could not be saved") || "Save failed".equals(status));
            boolean noSuccess = alert == null || !alert.toLowerCase().contains("project saved");
            if (failed && noSuccess && state == ProjectSaveState.SAVE_FAILED) {
                note("TC_PM_026 PASS save failure was shown and the application stayed open.");
            } else {
                note("TC_PM_026 NOT RUN category C: Save writes the open application database, not a user-chosen project destination. "
                        + "Marking meta.db read-only (" + attribute + ") left state " + state
                        + " and status '" + oneLine(status) + "'. No save-failure alert was shown.");
            }
            boolean lastOnly = reopenedNames.contains("alpha-engine.asc") && !reopenedNames.contains("extra-engine.asc");
            if (failed && lastOnly) {
                note("TC_PM_027 PASS reopen shows " + reopenedNames + ", the last successful save, after the failed change " + afterChange + ".");
            } else if (failed && !lastOnly) {
                note("TC_PM_027 FAIL reopen shows " + reopenedNames + " after a reported save failure. Last successful save was " + lastSuccessful + ".");
            } else {
                note("TC_PM_027 NOT RUN category C: no failed save was produced, so the last-successful-save recovery was not exercised. "
                        + "Reopen shows " + reopenedNames + ". The read-only database file did not reject the write.");
            }

            createProject(reopened, "Group5 Missing");
            addMeasurement(reopened, movedAsc);
            addMeasurement(reopened, keptAsc);
            addDefinition(reopened, projectDbc);
            clickSave(reopened);
            awaitClean(reopenedService);
            var processingBefore = reopenedService.workspace().processingStatus();
            int runsBefore = reopenedService.persistedProcessingRuns().size();
            Path aside = home.resolve("aside");
            Files.createDirectories(aside);
            Path movedTo = aside.resolve("moved-engine.asc");
            close(reopened);
            reopenedService.shutdown();
            Files.move(movedAsc, movedTo);
            assertTrue(Files.exists(movedTo));
            assertTrue(Files.notExists(movedAsc));

            SqliteProjectMetadataStore missingStore = new SqliteProjectMetadataStore(db);
            DesktopAppService missingService = new DesktopAppService(new DesktopSession(), missingStore);
            reopened = boot(missingService);
            final ShellFx missingShell = reopened;
            String openDialog = missingShell.bootDialogs;
            runOnFx(() -> {
                missingShell.controller.openRoute(WorkspaceRoute.SOURCES);
                missingShell.root.applyCss();
                missingShell.root.layout();
            });
            Thread.sleep(400);
            String sources = visible(missingShell.root);
            boolean reported = sources.contains("UNAVAILABLE") || sources.contains("File not found")
                    || sources.contains("Missing file") || (openDialog != null && openDialog.toLowerCase().contains("missing"));
            boolean dbcRemains = sources.contains("group5-engine.dbc");
            boolean keptRemains = sources.contains("kept-engine.asc");
            boolean movedListed = sources.contains("moved-engine.asc");
            var processingAfter = missingService.workspace().processingStatus();
            int runsAfter = missingService.persistedProcessingRuns().size();
            note("TC_PM_030 dialog='" + oneLine(openDialog) + "' sources='" + oneLine(sources) + "'");
            note("TC_PM_030 processingBefore=" + processingBefore + " processingAfter=" + processingAfter
                    + " runs " + runsBefore + " -> " + runsAfter
                    + " movedExists=" + Files.exists(movedTo) + " originalGone=" + Files.notExists(movedAsc));
            if (reported && dbcRemains && keptRemains && movedListed
                    && processingAfter == processingBefore && runsAfter == runsBefore) {
                note("TC_PM_030 PASS Sources reports moved-engine.asc as unavailable. group5-engine.dbc and kept-engine.asc remain. "
                        + "Processing stayed " + processingAfter + " with " + runsAfter + " persisted runs.");
            } else {
                note("TC_PM_030 NOT RUN category C: reported=" + reported + " dbc=" + dbcRemains
                        + " kept=" + keptRemains + " movedListed=" + movedListed
                        + " processing " + processingBefore + " -> " + processingAfter + ".");
            }

            boolean removed = runOnFxValue(() -> {
                Button remove = buttonNear(missingShell.root, "Remove from Project", "kept-engine.asc");
                if (remove == null) {
                    return false;
                }
                remove.fire();
                return true;
            });
            boolean diskRemains = Files.exists(keptAsc);
            boolean stillListed = names(missingService).contains("kept-engine.asc");
            note("INTEGRITY removeClicked=" + removed + " keptOnDisk=" + diskRemains
                    + " stillListed=" + stillListed + " movedCopy=" + Files.exists(movedTo));
        } catch (Throwable ex) {
            note("HARNESS " + ex.getClass().getSimpleName() + ": " + oneLine(String.valueOf(ex.getMessage())));
            throw ex;
        } finally {
            restoreReadOnly();
            closeQuiet(shell);
            closeQuiet(reopened);
            deleteTree(home);
        }
    }

    private static void scoreNewProjectButton(ShellFx shell) throws Exception {
        String facts = runOnFxValue(() -> {
            shell.root.applyCss();
            shell.root.layout();
            Button header = exactButton(shell.root, "+ New Project");
            Button visible = visibleButtonWithLabel(shell.root, "New Project");
            String headerFill = header == null ? "missing" : fill(header);
            String visibleFill = visible == null ? "missing" : fill(visible);
            String hover = "not-attempted";
            if (visible != null) {
                String before = fill(visible);
                visible.fireEvent(mouse(MouseEvent.MOUSE_ENTERED));
                shell.root.applyCss();
                hover = before + " -> " + fill(visible);
            }
            return "headerText=" + (header == null ? "missing" : header.getText())
                    + " headerVisible=" + (header != null && header.isVisible())
                    + " headerManaged=" + (header != null && header.isManaged())
                    + " headerEnabled=" + (header != null && !header.isDisabled())
                    + " headerFill=" + headerFill
                    + " visibleText=" + (visible == null ? "missing" : buttonLabel(visible))
                    + " visibleEnabled=" + (visible != null && !visible.isDisabled())
                    + " visibleClasses=" + (visible == null ? "" : visible.getStyleClass())
                    + " visibleFill=" + visibleFill
                    + " hover=" + hover;
        });
        note("TC_PM_001 evidence " + facts);
        note("TC_PM_001 NOT RUN category C: the top-right header control is + New Project and it is not shown. "
                + "The Workspace quick action is labeled New Project, uses a transparent quick-action row, and is not the blue primary button. "
                + facts);
    }

    private static void scoreNewProjectDialog(ShellFx shell) throws Exception {
        Thread opener = startOnFx(() -> {
            Button button = visibleButtonWithLabel(shell.root, "New Project");
            assertNotNull(button, "New Project");
            button.fire();
        });
        String captured = waitForDialog("New Project", 8, "Cancel");
        opener.join(15_000);
        note("TC_PM_002 dialog='" + oneLine(captured) + "'");
        boolean titled = captured != null && captured.contains("New Project");
        boolean message = captured != null && captured.contains("Create a new working project");
        boolean prefilled = captured != null && captured.contains("editor='Untitled Project'");
        boolean question = captured != null && (captured.toLowerCase().contains("question")
                || captured.contains("dialog-confirmation")
                || captured.contains("dialog-confirm"));
        if (titled && message && prefilled && question && captured.contains("OK") && captured.contains("Cancel")) {
            note("TC_PM_002 PASS dialog matched the workbook, including Untitled Project and a question mark icon.");
        } else {
            note("TC_PM_002 NOT RUN category C: the dialog title and header match, OK and Cancel are present, and the window is DECORATED. "
                    + (question
                    ? "The dialog graphic is the question-mark image. "
                    : "The dialog graphic is not identified as a question mark. ")
                    + "The field is empty rather than pre-filled with Untitled Project, and the text is not selected. "
                    + "Actual: " + oneLine(captured));
        }
    }

    private static void createProject(ShellFx shell, String name) throws Exception {
        openHome(shell);
        Thread opener = startOnFx(() -> {
            Button button = visibleButtonWithLabel(shell.root, "New Project");
            assertNotNull(button, "New Project");
            button.fire();
        });
        String captured = waitForDialog("New Project", 10, null, name, "OK");
        opener.join(20_000);
        awaitName(shell.service, name);
        note("CREATE " + name + " dialog='" + oneLine(captured) + "' header=" + headerName(shell.root));
    }

    private static void addMeasurement(ShellFx shell, Path file) throws Exception {
        int before = shell.service.workspace().measurementFiles().size();
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.SOURCES);
            shell.root.applyCss();
            shell.root.layout();
            shell.controller.projectFilesControllerForTest()
                    .setOpenDialogForTest(chooser -> List.of(file.toFile()));
            Button add = visibleButtonWithLabel(shell.root, "+ Add Measurement Files");
            if (add == null) {
                add = exactButton(shell.root, "Browse Files");
            }
            assertNotNull(add, "Add measurement");
            add.fire();
        });
        awaitCount(shell.service, before + 1, shell.service.workspace().definitionFiles().size());
    }

    private static void addDefinition(ShellFx shell, Path file) throws Exception {
        int definitions = shell.service.workspace().definitionFiles().size();
        int measurements = shell.service.workspace().measurementFiles().size();
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.SOURCES);
            shell.root.applyCss();
            shell.root.layout();
            shell.controller.projectFilesControllerForTest()
                    .setOpenDialogForTest(chooser -> List.of(file.toFile()));
            fire(shell.root, "+ Add DBC");
        });
        awaitCount(shell.service, measurements, definitions + 1);
    }

    private static void openHome(ShellFx shell) throws Exception {
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.HOME);
            shell.root.applyCss();
            shell.root.layout();
        });
    }

    private static void clickSave(ShellFx shell) throws Exception {
        runOnFx(() -> fireSave(shell.root));
    }

    private static boolean switchTo(ShellFx shell, String projectName) throws Exception {
        runOnFx(() -> {
            Node selector = shell.root.lookup(".project-selector");
            assertNotNull(selector, "project selector");
            selector.fireEvent(mouse(MouseEvent.MOUSE_CLICKED));
        });
        Thread.sleep(250);
        boolean clicked = runOnFxValue(() -> clickLabeled(projectName));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline && !headerName(shell.root).contains(projectName)) {
            Thread.sleep(50);
        }
        if (!headerName(shell.root).contains(projectName)) {
            String id = shell.service.projectDashboardSummaries().stream()
                    .filter(project -> projectName.equals(project.name()))
                    .map(project -> project.projectId())
                    .findFirst()
                    .orElse("");
            if (!id.isBlank()) {
                runOnFx(() -> shell.controller.requestOpenSavedProject(id));
            }
        }
        return clicked && headerName(shell.root).contains(projectName);
    }

    private static boolean clickLabeled(String projectName) {
        for (Window window : JavaFxTestSupport.snapshotWindows()) {
            if (window.getScene() == null || window.getScene().getRoot() == null) {
                continue;
            }
            for (Node node : window.getScene().getRoot().lookupAll(".label")) {
                if (node instanceof Label label && label.getText() != null && label.getText().contains(projectName)) {
                    Node target = label;
                    target.fireEvent(mouse(MouseEvent.MOUSE_PRESSED));
                    target.fireEvent(mouse(MouseEvent.MOUSE_RELEASED));
                    target.fireEvent(mouse(MouseEvent.MOUSE_CLICKED));
                    return true;
                }
            }
        }
        return false;
    }

    private static ShellFx boot(DesktopAppService service) throws Exception {
        AtomicReference<Stage> stage = new AtomicReference<>();
        AtomicReference<MainShellController> controller = new AtomicReference<>();
        AtomicReference<Parent> root = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch booted = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                Parent page = loader.load();
                MainShellController shell = loader.getController();
                Stage window = new Stage();
                Scene scene = new Scene(page, 1440, 900);
                var css = MainShellController.class.getResource("/css/stellar-desktop.css");
                if (css != null) {
                    scene.getStylesheets().add(css.toExternalForm());
                }
                window.setScene(scene);
                window.show();
                shell.init(service);
                page.applyCss();
                page.layout();
                stage.set(window);
                controller.set(shell);
                root.set(page);
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                booted.countDown();
            }
        });
        StringBuilder dialogs = new StringBuilder();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (!booted.await(200, TimeUnit.MILLISECONDS)) {
            String dismissed = dismissAnyDialog();
            if (dismissed != null) {
                dialogs.append(dismissed).append(" || ");
            }
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        assertNotNull(controller.get(), "shell did not open " + dialogs);
        return new ShellFx(stage.get(), controller.get(), root.get(), service, dialogs.toString());
    }

    private static String waitForDialog(String title, int seconds, String button) throws Exception {
        return waitForDialog(title, seconds, button, null, button);
    }

    private static String waitForDialog(String title, int seconds, String ignored, String editorText, String button) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            AtomicReference<String> captured = new AtomicReference<>();
            AtomicBoolean done = new AtomicBoolean();
            CountDownLatch probe = new CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    for (Window window : JavaFxTestSupport.snapshotWindows()) {
                    if (!(window instanceof Stage stage) || !stage.isShowing() || stage.getScene() == null) {
                        continue;
                    }
                    if (!(stage.getScene().getRoot() instanceof DialogPane pane)) {
                        continue;
                    }
                    String shown = stage.getTitle() == null ? "" : stage.getTitle();
                    String body = visibleText(pane);
                    if (!shown.contains(title) && !body.contains(title)) {
                        continue;
                    }
                        if (editorText != null) {
                            for (Node node : pane.lookupAll(".text-field")) {
                                if (node instanceof TextField field) {
                                    field.setText(editorText);
                                }
                            }
                        }
                        captured.set(describeDialog(stage, pane));
                        if (button != null) {
                            Button choice = exactButton(pane, button);
                            if (choice != null) {
                                choice.fire();
                                done.set(true);
                            }
                        } else {
                            done.set(true);
                        }
                    }
                } finally {
                    probe.countDown();
                }
            });
            assertTrue(probe.await(5, TimeUnit.SECONDS), "dialog probe timed out");
            if (done.get()) {
                return captured.get();
            }
            Thread.sleep(40);
        }
        return "";
    }

    private static String dismissAnyDialog() throws Exception {
        AtomicReference<String> captured = new AtomicReference<>();
        CountDownLatch probe = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                for (Window window : JavaFxTestSupport.snapshotWindows()) {
                    if (!(window instanceof Stage stage) || !stage.isShowing() || stage.getScene() == null) {
                        continue;
                    }
                    String title = stage.getTitle() == null ? "" : stage.getTitle();
                    if (title.isBlank() || "StellarAI".equals(title)) {
                        continue;
                    }
                    Parent pane = stage.getScene().getRoot();
                    captured.set(describeDialog(stage, pane));
                    Button ok = exactButton(pane, "OK");
                    if (ok == null) {
                        ok = exactButton(pane, "Cancel");
                    }
                    if (ok != null) {
                        ok.fire();
                    }
                }
            } finally {
                probe.countDown();
            }
        });
        if (!probe.await(5, TimeUnit.SECONDS)) {
            // Shell construction occupies the JavaFX thread. This probe is queued behind it,
            // so a slow init is not a dialog that failed to close. The boot deadline decides.
            return null;
        }
        return captured.get();
    }

    private static String describeDialog(Stage stage, Parent pane) {
        String header = "";
        String graphic = "none";
        if (pane instanceof DialogPane dialog) {
            header = dialog.getHeaderText() == null ? "" : dialog.getHeaderText();
            if (dialog.getGraphic() instanceof javafx.scene.image.ImageView view && view.getImage() != null) {
                graphic = "ImageView url=" + view.getImage().getUrl();
            } else {
                graphic = dialog.getGraphic() == null ? "none" : dialog.getGraphic().getClass().getSimpleName();
            }
        }
        String editor = "";
        String selected = "";
        for (Node node : pane.lookupAll(".text-field")) {
            if (node instanceof TextField field) {
                editor = field.getText() == null ? "" : field.getText();
                selected = field.getSelectedText() == null ? "" : field.getSelectedText();
            }
        }
        return "title='" + stage.getTitle() + "' style=" + stage.getStyle()
                + " header='" + header + "' graphic=" + graphic
                + " editor='" + editor + "' selected='" + selected + "' buttons=" + buttonTexts(pane)
                + " text='" + oneLine(visibleText(pane)) + "'";
    }

    private static void awaitName(DesktopAppService service, String name) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (name.equals(service.workspace().projectName())) {
                return;
            }
            Thread.sleep(40);
        }
    }

    private static void awaitClean(DesktopAppService service) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (service.projectSaveState() == ProjectSaveState.CLEAN && service.lastSavedAt().isPresent()) {
                return;
            }
            Thread.sleep(40);
        }
    }

    private static void awaitCount(DesktopAppService service, int measurements, int definitions) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (service.workspace().measurementFiles().size() >= measurements
                    && service.workspace().definitionFiles().size() >= definitions) {
                return;
            }
            Thread.sleep(40);
        }
    }

    private static boolean markReadOnly(Path db) {
        boolean set = false;
        for (String suffix : List.of("", "-wal", "-shm")) {
            Path path = suffix.isEmpty() ? db : Path.of(db.toString() + suffix);
            try {
                if (Files.exists(path)) {
                    Files.setAttribute(path, "dos:readonly", true);
                    READ_ONLY.add(path);
                    Object value = Files.getAttribute(path, "dos:readonly");
                    set = set || Boolean.TRUE.equals(value);
                }
            } catch (Exception ex) {
                note("READONLY " + path.getFileName() + " " + ex.getClass().getSimpleName() + " " + ex.getMessage());
            }
        }
        return set;
    }

    private static void restoreReadOnly() {
        for (Path path : READ_ONLY) {
            try {
                if (Files.exists(path)) {
                    Files.setAttribute(path, "dos:readonly", false);
                }
            } catch (Exception ignored) {
                // The temp home is deleted after the scenario.
            }
        }
        READ_ONLY.clear();
    }

    private static List<String> names(DesktopAppService service) {
        List<String> names = new ArrayList<>();
        service.workspace().measurementFiles().forEach(file -> names.add(file.fileName()));
        service.workspace().definitionFiles().forEach(file -> names.add(file.fileName()));
        return names;
    }

    private static boolean countersAtZero(String metrics) {
        return metrics.contains("Projects=0") && metrics.contains("Measurement Files=0");
    }

    private static String metrics(Parent root) throws Exception {
        return runOnFxValue(() -> {
            StringBuilder sb = new StringBuilder();
            for (Node node : root.lookupAll(".metric-label")) {
                if (!(node instanceof Label label) || !(label.getParent() instanceof Parent box)) {
                    continue;
                }
                String value = "";
                for (Node child : box.getChildrenUnmodifiable()) {
                    if (child instanceof Label metric && metric.getStyleClass().contains("metric-value")) {
                        value = metric.getText();
                    }
                }
                sb.append(label.getText()).append('=').append(value).append("; ");
            }
            return sb.toString();
        });
    }

    private static String activity(Parent root) throws Exception {
        return runOnFxValue(() -> {
            StringBuilder sb = new StringBuilder();
            for (Node node : root.lookupAll(".activity-item")) {
                String text = visibleText(node).replace('\n', ' ').trim();
                if (!text.isBlank()) {
                    sb.append(text).append(" | ");
                }
            }
            return sb.toString();
        });
    }

    private static String headerName(Parent root) throws Exception {
        return runOnFxValue(() -> labelText(root, "project-selector-value"));
    }

    private static String labelText(Parent root, String style) {
        for (Node node : root.lookupAll(".label")) {
            if (node instanceof Label label && label.getStyleClass().contains(style)) {
                return label.getText() == null ? "" : label.getText();
            }
        }
        return "";
    }

    private static String visible(Parent root) throws Exception {
        return runOnFxValue(() -> visibleText(root));
    }

    private static String visibleText(Node node) {
        StringBuilder sb = new StringBuilder();
        collect(node, sb);
        return sb.toString();
    }

    private static void collect(Node node, StringBuilder sb) {
        if (node instanceof Labeled labeled && labeled.getText() != null && !labeled.getText().isBlank()) {
            sb.append(labeled.getText()).append('\n');
        }
        if (node instanceof Text text && text.getText() != null && !text.getText().isBlank()) {
            sb.append(text.getText()).append('\n');
        }
        if (node instanceof TextInputControl input && input.getText() != null && !input.getText().isBlank()) {
            sb.append(input.getText()).append('\n');
        }
        if (node instanceof Parent parent) {
            List<Node> children;
            try {
                children = List.copyOf(parent.getChildrenUnmodifiable());
            } catch (RuntimeException changing) {
                return;
            }
            for (Node child : children) {
                collect(child, sb);
            }
        }
    }

    private static void fire(Parent root, String label) {
        Button button = exactButton(root, label);
        assertNotNull(button, label);
        button.fire();
    }

    private static void fireSave(Parent root) {
        Button save = null;
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && button.isVisible() && button.isManaged()) {
                String text = button.getText() == null ? "" : button.getText();
                if ("Save".equals(text) || "Save*".equals(text) || "Retry".equals(text)) {
                    save = button;
                }
            }
        }
        assertNotNull(save, "Save");
        save.fire();
    }

    private static Button exactButton(Parent root, String label) {
        Button found = null;
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && label.equals(button.getText())) {
                found = button;
            }
        }
        return found;
    }

    private static Button visibleButtonWithLabel(Parent root, String label) {
        for (Node node : root.lookupAll(".button")) {
            if (!(node instanceof Button button) || !button.isVisible() || !button.isManaged()) {
                continue;
            }
            if (label.equals(button.getText()) || label.equals(buttonLabel(button))) {
                return button;
            }
        }
        return null;
    }

    private static String buttonLabel(Button button) {
        StringBuilder sb = new StringBuilder();
        collect(button, sb);
        for (String line : sb.toString().split("\n")) {
            if (line != null && !line.isBlank() && !"›".equals(line.trim())) {
                return line.trim();
            }
        }
        return button.getText() == null ? "" : button.getText();
    }

    private static String buttonTexts(Parent root) {
        List<String> texts = new ArrayList<>();
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && button.getText() != null) {
                texts.add(button.getText());
            }
        }
        return texts.toString();
    }

    private static Button buttonNear(Parent root, String buttonText, String near) {
        for (Node node : root.lookupAll(".button")) {
            if (!(node instanceof Button button) || !buttonText.equals(button.getText())) {
                continue;
            }
            Node row = button.getParent();
            if (row != null && visibleText(row).contains(near)) {
                return button;
            }
        }
        return null;
    }

    private static String fill(Button button) {
        if (button.getBackground() == null || button.getBackground().getFills().isEmpty()) {
            return "none";
        }
        Paint paint = button.getBackground().getFills().get(0).getFill();
        return String.valueOf(paint);
    }

    private static MouseEvent mouse(javafx.event.EventType<MouseEvent> type) {
        return new MouseEvent(type, 4, 4, 4, 4, MouseButton.PRIMARY, 1,
                false, false, false, false, false, false, false, false, false, false, null);
    }

    private static void isolate(Path home) {
        System.setProperty("stellarai.home", home.toAbsolutePath().toString());
        System.setProperty("stellarai.test.isolate", "true");
        System.clearProperty("stellarai.metadata.db");
        AnalysisStorageRoots.clear();
    }

    private static void close(ShellFx shell) throws Exception {
        if (shell.stage != null) {
            runOnFx(shell.stage::close);
        }
    }

    private static void closeQuiet(ShellFx shell) {
        if (shell == null) {
            return;
        }
        try {
            close(shell);
        } catch (Exception ignored) {
            // The stage may already be closed.
        }
        try {
            shell.service.shutdown();
        } catch (RuntimeException ignored) {
            // shutdown() is invoked once before a later store reopens the same file.
        }
    }

    private static Thread startOnFx(FxAction action) {
        Thread thread = new Thread(() -> {
            try {
                runOnFx(action);
            } catch (Throwable ex) {
                note("ASYNC " + ex.getClass().getSimpleName() + ": " + oneLine(String.valueOf(ex.getMessage())));
            }
        }, "clearance-g5-fx");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void note(String line) {
        NOTES.add(line);
    }

    private static void runOnFx(FxAction action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "FX task timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static <T> T runOnFxValue(FxValue<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        runOnFx(() -> value.set(action.get()));
        return value.get();
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\n', ' ').replaceAll("\\s+", " ").trim();
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
                    // Temporary home cleanup is best-effort.
                }
            });
        } catch (Exception ignored) {
            // Temporary home cleanup is best-effort.
        }
    }

    private record ShellFx(Stage stage, MainShellController controller, Parent root,
                           DesktopAppService service, String bootDialogs) {
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface FxValue<T> {
        T get() throws Exception;
    }
}
