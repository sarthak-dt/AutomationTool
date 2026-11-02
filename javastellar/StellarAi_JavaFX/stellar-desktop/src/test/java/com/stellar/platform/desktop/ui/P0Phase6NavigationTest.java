package com.stellar.platform.desktop.ui;

import com.stellar.platform.desktop.presentation.DesktopAppService;
import com.stellar.platform.desktop.presentation.NavigationPage;
import com.stellar.platform.desktop.presentation.WorkspaceRoute;
import com.stellar.platform.desktop.session.DesktopSession;
import com.stellar.platform.desktop.support.JavaFxTestSupport;
import com.stellar.platform.desktop.testsupport.DesktopTestIsolation;
import com.stellar.platform.storage.metadata.SqliteProjectMetadataStore;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Production sidebar Graph navigation, normal use, no JFR. */
@Timeout(value = 8, unit = TimeUnit.MINUTES)
class P0Phase6NavigationTest {

    private static final Path OUT = Path.of("target", "p0-phase6-nav.txt");
    private static final List<String> LINES = new ArrayList<>();

    @BeforeAll
    static void startFx() throws Exception {
        JavaFxTestSupport.startToolkit();
    }

    @Test
    void openingFramePrecedesGraphAndCancelDropsTheLoad() throws Exception {
        Path temp = Files.createTempDirectory("p0-phase6-nav");
        DesktopTestIsolation.isolateHome(temp.resolve("home"));
        DesktopAppService service = new DesktopAppService(
                new DesktopSession(), new SqliteProjectMetadataStore(temp.resolve("meta.db")));
        Stage[] stage = new Stage[1];
        MainShellController[] shell = new MainShellController[1];
        try {
            JavaFxTestSupport.runOnFx("shell", () -> {
                FXMLLoader loader = new FXMLLoader(MainShellController.class.getResource("/fxml/main-shell.fxml"));
                Parent root = loader.load();
                shell[0] = loader.getController();
                Scene scene = new Scene(root, 1366, 768);
                var css = MainShellController.class.getResource("/css/stellar-desktop.css");
                if (css != null) {
                    scene.getStylesheets().add(css.toExternalForm());
                }
                stage[0] = new Stage();
                stage[0].setScene(scene);
                stage[0].show();
                shell[0].init(service);
                service.newProject("p0-phase6-nav");
            });
            long[] feedback = new long[8];
            long[] ready = new long[8];
            for (int i = 0; i < 8; i++) {
                int cycle = i;
                JavaFxTestSupport.runOnFx("signals " + cycle, () ->
                        shell[0].openRoute(WorkspaceRoute.SIGNALS));
                JavaFxTestSupport.drainFxPulse();
                JavaFxTestSupport.runOnFx("graphs " + cycle, () -> {
                    TwoPulse.arm(stage[0].getScene());
                    shell[0].openGraphsFromNavigation();
                    assertTrue(shell[0].graphOpeningVisibleForTest(), "opening frame");
                    assertNull(shell[0].loadedGraphControllerForTest(), "graph waits for the next pulse");
                });
                TwoPulse.Stamps stamps = TwoPulse.await();
                feedback[cycle] = stamps.feedback;
                ready[cycle] = stamps.ready;
                JavaFxTestSupport.runOnFx("attached " + cycle, () -> {
                    assertNotNull(shell[0].loadedGraphControllerForTest());
                    assertTrue(!shell[0].graphOpeningVisibleForTest());
                    assertEquals(NavigationPage.GRAPHS, shell[0].visiblePage());
                });
            }
            LINES.add(stats("nav-feedback-ms", feedback, 0));
            LINES.add(stats("nav-feedback-warm-ms", feedback, 1));
            LINES.add(stats("nav-graph-ready-ms", ready, 0));
            LINES.add(stats("nav-graph-ready-warm-ms", ready, 1));
            LINES.add("nav-cold-feedback-ms=" + (feedback[0] / 1_000_000L)
                    + " graph-ready-ms=" + (ready[0] / 1_000_000L));

            JavaFxTestSupport.runOnFx("return signals", () -> shell[0].openRoute(WorkspaceRoute.SIGNALS));
            JavaFxTestSupport.drainFxPulse();
            JavaFxTestSupport.runOnFx("cancel", () -> {
                shell[0].openGraphsFromNavigation();
                assertTrue(shell[0].graphOpeningVisibleForTest());
                shell[0].openRoute(WorkspaceRoute.HOME);
            });
            JavaFxTestSupport.drainFxPulse();
            JavaFxTestSupport.drainFxPulse();
            JavaFxTestSupport.runOnFx("cancelled", () -> {
                assertNull(shell[0].loadedGraphControllerForTest());
                assertEquals(NavigationPage.HOME, shell[0].visiblePage());
                assertTrue(!shell[0].graphOpeningVisibleForTest());
            });
            LINES.add("cancel-left-graph-unattached=true");
        } finally {
            Files.createDirectories(OUT.getParent());
            Files.writeString(OUT, String.join(System.lineSeparator(), LINES) + System.lineSeparator());
            JavaFxTestSupport.runOnFx("dispose", () -> {
                if (shell[0] != null) {
                    shell[0].dispose();
                }
                if (stage[0] != null) {
                    stage[0].close();
                }
            });
            service.shutdown();
            DesktopTestIsolation.resetProcessState();
        }
    }

    private static String stats(String name, long[] nanos, int from) {
        long[] sorted = new long[nanos.length - from];
        for (int i = from; i < nanos.length; i++) {
            sorted[i - from] = nanos[i] / 1_000_000L;
        }
        Arrays.sort(sorted);
        return name + " n=" + sorted.length
                + " p50=" + pct(sorted, 50)
                + " p95=" + pct(sorted, 95)
                + " max=" + sorted[sorted.length - 1];
    }

    private static long pct(long[] sorted, int percentile) {
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static final class TwoPulse {
        private static final AtomicReference<TwoPulse> LATEST = new AtomicReference<>();
        private final Scene scene;
        private final CountDownLatch done = new CountDownLatch(1);
        private final Stamps stamps = new Stamps();
        private int posts;
        private Runnable listener;
        private long start;

        static void arm(Scene scene) {
            TwoPulse watch = new TwoPulse(scene);
            LATEST.set(watch);
            watch.start = System.nanoTime();
            watch.listener = () -> {
                watch.posts++;
                long now = System.nanoTime();
                if (watch.posts == 1) {
                    watch.stamps.feedback = now - watch.start;
                } else if (watch.posts >= 2) {
                    watch.stamps.ready = now - watch.start;
                    watch.scene.removePostLayoutPulseListener(watch.listener);
                    watch.done.countDown();
                }
            };
            scene.addPostLayoutPulseListener(watch.listener);
        }

        static Stamps await() throws Exception {
            TwoPulse watch = LATEST.get();
            assertTrue(watch.done.await(12, TimeUnit.SECONDS), "navigation pulses");
            return watch.stamps;
        }

        private TwoPulse(Scene scene) {
            this.scene = scene;
        }

        static final class Stamps {
            long feedback;
            long ready;
        }
    }
}
