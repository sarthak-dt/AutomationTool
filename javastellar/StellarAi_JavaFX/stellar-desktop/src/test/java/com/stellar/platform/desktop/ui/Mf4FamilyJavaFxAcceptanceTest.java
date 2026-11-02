package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.project.ProjectInventoryJobState;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One isolated JavaFX project per MF4 family that does not already have a physical-oracle run.
 * No DBC is attached unless the family folder contains a dedicated definition. A disabled
 * Start Processing control is recorded as blocked, not as decoded signals.
 */
class Mf4FamilyJavaFxAcceptanceTest {

    private static final Path ROOT = Path.of("C:\\Users\\DT - User\\Downloads\\DriveTechData");
    private static final Path EVIDENCE = Path.of("D:\\DesktopApp\\DriveT\\docs\\qa-handoff\\mf4-family-javafx.txt");
    private static final StringBuilder NOTES = new StringBuilder();

    private record Family(String name, Path file) {
    }

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @AfterAll
    static void writeEvidence() throws Exception {
        Files.createDirectories(EVIDENCE.getParent());
        Files.writeString(EVIDENCE, NOTES.toString());
    }

    @Test
    @Timeout(value = 45, unit = TimeUnit.MINUTES)
    void eachRemainingFamilyReachesATerminalJavaFxState() throws Exception {
        note("started=" + Instant.now());
        List<Family> families = List.of(
                new Family("Bengaluru", ROOT.resolve("MF4Files\\Bengaluru_MF4\\MF4_KA04NC7760_Bengaluru_0002360_20230814_171438.mf4")),
                new Family("Citroen", ROOT.resolve("MF4Files\\Citroen_MF4\\72E9519F_00000025_00000028-6440871C.MF4")),
                new Family("i10", ROOT.resolve("MF4Files\\i10_MF4\\i10_cinfig_00_7E0_0002259_20231223_105925.mf4")),
                new Family("TVS_BMW", ROOT.resolve("MF4Files\\TVS_BMW_MF4\\TVS_bmw_0002211_20250217_153502.mf4")),
                new Family("b1-3", ROOT.resolve("dbcfiles\\b1-3.mf4")));
        for (Family family : families) {
            accept(family);
        }
        note("finished=" + Instant.now());
    }

    private static void accept(Family family) throws Exception {
        note("--- " + family.name);
        assertTrue(Files.isRegularFile(family.file), family.file.toString());
        String before = sha256(family.file);
        note("file=" + family.file.getFileName() + " size=" + Files.size(family.file) + " sha256=" + before);
        note("header=" + header(family.file));
        Path home = Files.createTempDirectory("stellar-mf4-" + family.name);
        Path db = home.resolve("meta.db");
        assertFalse(home.toString().toLowerCase(Locale.ROOT).contains("stellaraie2e"));
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        System.setProperty("stellarai.metadata.db", db.toString());
        DesktopAppService service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
        ShellFx shell = boot(service);
        try {
            createProject(shell, "MF4 " + family.name);
            addMeasurement(shell, family.file);
            awaitInventory(service, 8);
            WorkspaceFile measurement = service.workspace().measurementFiles().stream()
                    .filter(file -> family.file.getFileName().toString().equals(file.fileName()))
                    .findFirst()
                    .orElseThrow();
            ProjectInventoryJobState inventory = service.inventoryJobState(measurement.projectSourceId());
            note("import kind=" + measurement.kind()
                    + " finalization=" + measurement.mf4FinalizationLabel()
                    + " recoveryCandidate=" + measurement.recoveryCandidate()
                    + " inventory=" + inventory);
            boolean started = startProcessing(shell);
            note("startProcessing=" + started + " dbc=none");
            ProjectProcessingStatus status = started ? awaitProcessing(service, 12) : service.workspace().processingStatus();
            int catalog = service.signalsModel().catalog().entries().size();
            int warnings = service.processingWarningReport().count();
            String names = service.signalsModel().catalog().entries().stream()
                    .limit(12)
                    .map(entry -> entry.signalName() + ":" + entry.sampleCount())
                    .toList()
                    .toString();
            note("processing=" + status + " catalog=" + catalog + " warnings=" + warnings + " signals=" + names);
            if (catalog > 0) {
                runOnFx(() -> shell.controller.openRoute(WorkspaceRoute.GRAPHS));
                note("graph=OPENED signals=" + catalog);
            } else {
                note("graph=NOT_APPLICABLE catalog=0");
                note("analytics=NOT_APPLICABLE");
                note("physicalOracle=NOT_APPLICABLE");
            }
            String projectId = service.currentProjectId();
            runOnFx(() -> {
                Button save = buttonStarting(shell.root, "Save");
                if (save != null && !save.isDisabled()) {
                    save.fire();
                }
            });
            await("saved " + family.name, () -> service.projectSaveState() != null
                    && !service.projectSaveState().name().contains("SAVING"), 30);
            note("saveState=" + service.projectSaveState());
            close(shell);
            service.shutdown();
            DesktopAppService reopened = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
            ShellFx again = boot(reopened);
            try {
                runOnFx(() -> again.controller.requestOpenSavedProject(projectId));
                await("reopen " + family.name, () -> reopened.workspace().measurementFiles().stream()
                        .anyMatch(file -> family.file.getFileName().toString().equals(file.fileName())), 90);
                note("reopen files=" + reopened.workspace().measurementFiles().stream().map(WorkspaceFile::fileName).toList()
                        + " catalog=" + reopened.signalsModel().catalog().entries().size()
                        + " processing=" + reopened.workspace().processingStatus());
            } finally {
                close(again);
                reopened.shutdown();
            }
            assertEquals(before, sha256(family.file), family.name);
            note("hashUnchanged=true");
            String physical = catalog > 0 ? "NOT_RUN_NO_INDEPENDENT_ORACLE" : "NOT_APPLICABLE";
            note("RESULT inventory=" + inventory
                    + " processing=" + (started ? status : "BLOCKED")
                    + " physical=" + physical
                    + " catalog=" + catalog);
        } finally {
            close(shell);
            try {
                service.shutdown();
            } catch (RuntimeException ignored) {
                // A reopen already closed this store.
            }
        }
    }

    private static boolean startProcessing(ShellFx shell) throws Exception {
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.DECODE);
            shell.root.applyCss();
            shell.root.layout();
            Button proceed = button(shell.root, "Review & Process");
            if (proceed != null && !proceed.isDisabled()) {
                proceed.fire();
            }
        });
        Thread.sleep(400);
        return runOnFxValue(() -> {
            shell.controller.openRoute(WorkspaceRoute.REVIEW_AND_PROCESS);
            shell.root.applyCss();
            shell.root.layout();
            Button run = button(shell.root, "Start Processing");
            if (run != null && !run.isDisabled()) {
                run.fire();
                return true;
            }
            return false;
        });
    }

    private static void awaitInventory(DesktopAppService service, int minutes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
        while (System.nanoTime() < deadline) {
            if (!service.workspace().measurementFiles().isEmpty()
                    && service.workspace().measurementFiles().stream().allMatch(file -> {
                        ProjectInventoryJobState state = service.inventoryJobState(file.projectSourceId());
                        return state == ProjectInventoryJobState.READY
                                || state == ProjectInventoryJobState.REQUIRES_ATTENTION
                                || state == ProjectInventoryJobState.FAILED
                                || state == ProjectInventoryJobState.UNSUPPORTED
                                || state == ProjectInventoryJobState.CANCELLED;
                    })) {
                return;
            }
            Thread.sleep(250);
        }
        note("inventoryWaitExpired");
    }

    private static ProjectProcessingStatus awaitProcessing(DesktopAppService service, int minutes) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(minutes);
        while (System.nanoTime() < deadline) {
            ProjectProcessingStatus status = service.workspace().processingStatus();
            int catalog = service.signalsModel().catalog().entries().size();
            int warnings = service.processingWarningReport().count();
            if (status == ProjectProcessingStatus.IDLE && (catalog > 0 || warnings > 0)) {
                return status;
            }
            if (status == null || status == ProjectProcessingStatus.IDLE
                    || status == ProjectProcessingStatus.RUNNING) {
                Thread.sleep(500);
                continue;
            }
            if (status == ProjectProcessingStatus.COMPLETED
                    || status == ProjectProcessingStatus.COMPLETED_WITH_WARNINGS
                    || status == ProjectProcessingStatus.FAILED
                    || status == ProjectProcessingStatus.CANCELLED
                    || status == ProjectProcessingStatus.RECOVERY_FAILED
                    || status == ProjectProcessingStatus.UNSUPPORTED_STRUCTURE
                    || status == ProjectProcessingStatus.NO_CAN_DATA) {
                return status;
            }
            Thread.sleep(500);
        }
        return service.workspace().processingStatus();
    }

    private static String header(Path file) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(24);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.read(buffer);
        }
        String text = new String(buffer.array(), java.nio.charset.StandardCharsets.ISO_8859_1).replace('\0', ' ').trim();
        return text;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] chunk = new byte[1024 * 1024];
            int read;
            while ((read = input.read(chunk)) >= 0) {
                digest.update(chunk, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest()).toUpperCase(Locale.ROOT);
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
                window.setScene(new Scene(page, 1440, 900));
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
        assertTrue(booted.await(60, TimeUnit.SECONDS), "shell did not open");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return new ShellFx(stage.get(), controller.get(), root.get(), service);
    }

    private static void createProject(ShellFx shell, String name) throws Exception {
        Thread opener = new Thread(() -> {
            try {
                runOnFx(() -> {
                    shell.controller.openRoute(WorkspaceRoute.HOME);
                    shell.root.applyCss();
                    shell.root.layout();
                    Button button = button(shell.root, "New Project");
                    assertNotNull(button, "New Project");
                    button.fire();
                });
            } catch (Throwable thrown) {
                throw new RuntimeException(thrown);
            }
        });
        opener.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(runOnFxValue(() -> fillDialog(name)))) {
                break;
            }
            Thread.sleep(50);
        }
        opener.join(20_000);
        await("project " + name, () -> name.equals(projectName(shell)), 20);
    }

    private static boolean fillDialog(String name) {
        for (javafx.stage.Window window : JavaFxTestSupport.snapshotWindows()) {
            if (!(window instanceof Stage stage) || stage.getScene() == null) {
                continue;
            }
            Parent pane = stage.getScene().getRoot();
            boolean matched = false;
            for (Node node : pane.lookupAll(".text-field")) {
                if (node instanceof javafx.scene.control.TextField field) {
                    field.setText(name);
                    matched = true;
                }
            }
            if (!matched) {
                continue;
            }
            Button ok = button(pane, "OK");
            if (ok != null) {
                ok.fire();
                return true;
            }
        }
        return false;
    }

    private static void addMeasurement(ShellFx shell, Path file) throws Exception {
        runOnFx(() -> {
            shell.controller.openRoute(WorkspaceRoute.SOURCES);
            shell.root.applyCss();
            shell.root.layout();
            shell.controller.projectFilesControllerForTest().setOpenDialogForTest(chooser -> List.of(file.toFile()));
            Button add = button(shell.root, "+ Add Measurement Files");
            if (add == null) {
                add = button(shell.root, "Browse Files");
            }
            assertNotNull(add, "Add Measurement");
            add.fire();
        });
    }

    private static String projectName(ShellFx shell) {
        try {
            return shell.service.workspace().projectName();
        } catch (RuntimeException ex) {
            return "";
        }
    }

    private static Button button(Parent root, String label) {
        Button hidden = null;
        for (Node node : root.lookupAll(".button")) {
            if (!(node instanceof Button button)) {
                continue;
            }
            String text = button.getText() == null ? "" : button.getText();
            if (!text.contains(label)) {
                continue;
            }
            if (button.isVisible() && button.isManaged()) {
                return button;
            }
            hidden = button;
        }
        return hidden;
    }

    private static Button buttonStarting(Parent root, String prefix) {
        for (Node node : root.lookupAll(".button")) {
            if (node instanceof Button button && button.getText() != null && button.getText().startsWith(prefix)) {
                return button;
            }
        }
        return null;
    }

    private static void await(String label, java.util.function.BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out waiting for " + label);
    }

    private static void close(ShellFx shell) throws Exception {
        runOnFx(() -> {
            if (shell.stage != null) {
                shell.stage.close();
            }
        });
    }

    private static void runOnFx(JavaFxTestSupport.FxWork work) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                work.run();
            } catch (Throwable thrown) {
                error.set(thrown);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(60, TimeUnit.SECONDS), "FX work timed out");
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static <T> T runOnFxValue(java.util.function.Supplier<T> work) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        runOnFx(() -> value.set(work.get()));
        return value.get();
    }

    private static void note(String line) {
        NOTES.append(line).append('\n');
    }

    private record ShellFx(Stage stage, MainShellController controller, Parent root, DesktopAppService service) {
    }
}
