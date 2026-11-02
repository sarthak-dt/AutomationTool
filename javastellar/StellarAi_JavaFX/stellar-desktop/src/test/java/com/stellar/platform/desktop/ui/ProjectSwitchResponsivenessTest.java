package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.project.FileKind;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repeated project switches must keep the JavaFX thread able to run work while
 * metadata and artifact restore run on the project-switch thread.
 */
class ProjectSwitchResponsivenessTest {

    @TempDir
    static Path temp;

    private static DesktopAppService service;
    private static MainShellController shell;

    @BeforeAll
    static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS));
        Platform.setImplicitExit(false);
    }

    @AfterAll
    static void shutdown() {
        MainShellController.asyncProjectSwitchForTest = false;
        if (service != null) {
            service.setProjectOpenPauseForTest(null);
            if (shell != null) {
                shell.dispose();
            }
            service.shutdown();
        }
    }

    @Test
    @Timeout(value = 4, unit = TimeUnit.MINUTES)
    void repeatedSwitchesStayOnTheEventThreadAndKeepProjectIdentity() throws Exception {
        Path home = temp.resolve("home");
        Path db = temp.resolve("switch.db");
        Files.createDirectories(home);
        System.setProperty("stellarai.test.isolate", "true");
        System.setProperty("stellarai.home", home.toString());
        MainShellController.asyncProjectSwitchForTest = true;
        service = new DesktopAppService(new DesktopSession(), new SqliteProjectMetadataStore(db));
        AtomicReference<String> switchThread = new AtomicReference<>();
        service.setProjectOpenPauseForTest(() -> {
            switchThread.set(Thread.currentThread().getName());
            try {
                Thread.sleep(80);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        onFx(() -> {
            FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
            loader.load();
            shell = loader.getController();
            shell.init(service);
            shell.setNewProjectNameForTest("switch-test");
        });

        Project small = createProject("Switch A", "a-small.mf4", "a.dbc", "");
        Project large = createProject("Switch B", "b-large.mf4", "b.dbc", "");
        Project graph = createProject("Switch C", "c-graph.mf4", "c.dbc", "checkpoint-c");
        Project empty = createProject("Switch D", "d-empty.mf4", "d.dbc", "");

        int threadsBefore = Thread.getAllStackTraces().size();
        long heapBefore = usedHeap();
        List<Long> latencies = new ArrayList<>();
        Project[] cycle = {small, large, small, large, graph, small};
        for (int round = 0; round < 10; round++) {
            for (Project project : cycle) {
                if (project == graph) {
                    onFx(() -> shell.show(NavigationPage.GRAPHS));
                } else if (project == empty) {
                    onFx(() -> shell.show(NavigationPage.DECODE));
                } else if (project == large) {
                    onFx(() -> shell.show(NavigationPage.SIGNALS));
                }
                SwitchSample sample = switchTo(project);
                latencies.add(sample.millis());
                assertTrue(sample.fxPulses() > 0, "JavaFX thread did not run during switch to " + project.name());
                assertEquals(project.id(), service.currentProjectId());
                assertTrue(service.workspace().measurementFiles().stream()
                        .anyMatch(file -> project.measurement().equals(file.fileName())));
                assertTrue(service.workspace().definitionFiles().stream()
                        .anyMatch(file -> project.dbc().equals(file.fileName())));
                if (!project.notes().isBlank()) {
                    assertEquals(project.notes(), service.graphInvestigation().notes());
                }
            }
        }
        onFx(() -> shell.show(NavigationPage.DECODE));
        SwitchSample backToSmall = switchTo(small);
        latencies.add(backToSmall.millis());
        assertEquals(small.id(), service.currentProjectId());

        service.setOpenHydrationFailureForTest(new IllegalStateException("injected switch failure"));
        switchAllowingFailure(large);
        assertFalse(large.id().equals(service.currentProjectId()));
        assertEquals("OPEN", shell.lastLifecycleErrorKindForTest());
        SwitchSample recovered = switchTo(large);
        latencies.add(recovered.millis());
        assertEquals(large.id(), service.currentProjectId());
        SwitchSample emptyWorkspace = switchTo(empty);
        latencies.add(emptyWorkspace.millis());
        assertEquals("", service.graphInvestigation().notes());
        assertTrue(emptyWorkspace.fxPulses() > 0);

        service.setProjectOpenPauseForTest(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        CountDownLatch firstAccepted = new CountDownLatch(1);
        onFx(() -> {
            shell.requestOpenSavedProject(small.id());
            firstAccepted.countDown();
        });
        assertTrue(firstAccepted.await(5, TimeUnit.SECONDS));
        onFx(() -> shell.requestOpenSavedProject(large.id()));
        awaitProject(large.id());
        assertEquals(large.id(), service.currentProjectId());
        assertTrue(service.workspace().measurementFiles().stream()
                .anyMatch(file -> large.measurement().equals(file.fileName())));
        assertFalse(service.workspace().measurementFiles().stream()
                .anyMatch(file -> small.measurement().equals(file.fileName())));

        long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
        long p50 = percentile(sorted, 50);
        long p95 = percentile(sorted, 95);
        long max = sorted[sorted.length - 1];
        assertTrue(max < 20_000, "max switch " + max + " ms p50=" + p50 + " p95=" + p95);
        int threadsAfter = Thread.getAllStackTraces().size();
        assertTrue(threadsAfter - threadsBefore < 25,
                "thread growth " + threadsBefore + " -> " + threadsAfter);
        assertTrue(switchThread.get() != null && switchThread.get().startsWith("stellar-project-switch"),
                switchThread.get());
        assertFalse("JavaFX Application Thread".equals(switchThread.get()));
        int pages = shell.loadedPageCountForTest();
        assertTrue(pages > 0 && pages < 8, "loaded pages " + pages);
        assertTrue(service.signalsModel().favoritesListenerCountForTest() <= 2,
                "favorites listeners " + service.signalsModel().favoritesListenerCountForTest());
        System.out.println("PROJECT_SWITCH cycles=10 switches=" + latencies.size()
                + " p50ms=" + p50 + " p95ms=" + p95 + " maxms=" + max
                + " heapBefore=" + heapBefore + " heapAfter=" + usedHeap()
                + " threadsBefore=" + threadsBefore + " threadsAfter=" + threadsAfter
                + " pages=" + pages);
    }

    private static Project createProject(String name, String measurement, String dbc, String notes) throws Exception {
        AtomicReference<Project> created = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                service.newProject(name);
                Path measurementPath = temp.resolve(measurement);
                Path dbcPath = temp.resolve(dbc);
                Files.writeString(measurementPath, measurement);
                Files.writeString(dbcPath, "VERSION \"\"");
                service.workspace().addFile(WorkspaceFile.restore(
                        measurement, measurementPath, FileKind.MEASUREMENT_MF4, Files.size(measurementPath)));
                service.workspace().addFile(WorkspaceFile.restore(
                        dbc, dbcPath, FileKind.DEFINITION_DBC, Files.size(dbcPath)));
                service.graphInvestigation().setNotes(notes);
                service.persistGraphInvestigation();
                assertTrue(service.saveProject().saved(), name);
                created.set(new Project(service.currentProjectId(), name, measurement, dbc, notes));
            } catch (Throwable ex) {
                error.set(ex);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return created.get();
    }

    private static SwitchSample switchTo(Project project) throws Exception {
        AtomicInteger pulses = new AtomicInteger();
        long started = System.nanoTime();
        onFx(() -> shell.requestOpenSavedProject(project.id()));
        while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(20)) {
            CountDownLatch pulse = new CountDownLatch(1);
            Platform.runLater(pulse::countDown);
            if (pulse.await(50, TimeUnit.MILLISECONDS)) {
                pulses.incrementAndGet();
            }
            if (!shell.projectSwitchActiveForTest() && project.id().equals(service.currentProjectId())) {
                break;
            }
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertEquals(project.id(), service.currentProjectId(), project.name());
        assertFalse(shell.projectSwitchActiveForTest(), project.name());
        return new SwitchSample(millis, pulses.get());
    }

    private static void switchAllowingFailure(Project project) throws Exception {
        long started = System.nanoTime();
        onFx(() -> shell.requestOpenSavedProject(project.id()));
        while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(20)) {
            if (!shell.projectSwitchActiveForTest()) {
                return;
            }
            Thread.sleep(30);
        }
        assertFalse(shell.projectSwitchActiveForTest());
    }

    private static void awaitProject(String projectId) throws Exception {
        long started = System.nanoTime();
        while (System.nanoTime() - started < TimeUnit.SECONDS.toNanos(20)) {
            if (!shell.projectSwitchActiveForTest() && projectId.equals(service.currentProjectId())) {
                return;
            }
            Thread.sleep(30);
        }
        assertEquals(projectId, service.currentProjectId());
    }

    private static void onFx(FxBody body) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable ex) {
                error.set(ex);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    private static long usedHeap() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private static long percentile(long[] sorted, int percent) {
        int index = (int) Math.ceil(percent / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private record Project(String id, String name, String measurement, String dbc, String notes) {
    }

    private record SwitchSample(long millis, int fxPulses) {
    }

    @FunctionalInterface
    private interface FxBody {
        void run() throws Exception;
    }
}
