package com.stellar.platform.desktop.support;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared JavaFX test lifecycle. The 50s timeouts seen in
 * {@code Phase6CloseSwitchExitTest} and {@code ProjectCreateOpenShellTest} were
 * suite-lifecycle contention, not a product deadlock:
 *
 * <ul>
 *   <li>Surefire {@code reuseForks=false} isolates test <em>classes</em>; the
 *       hang was a {@code Platform.runLater} latch that never completed inside
 *       one class.</li>
 *   <li>Those classes passed in isolation and on a later full-suite rerun.</li>
 *   <li>No graph/inventory executor leak in those tests (Graphs is lazy-loaded).</li>
 *   <li>Gaps that made the suite non-deterministic: save-state listeners left
 *       subscribed after {@code dispose}, executors not joined before SQLite
 *       close (WAL locks, {@code @TempDir} cleanup failure), {@code shutdown()}
 *       invoked on the FX thread, and SAVE tests opening a Tooltip without a
 *       Window.</li>
 * </ul>
 *
 * <p>This harness keeps the toolkit alive ({@code implicitExit=false}), runs
 * UI work on FX, then shuts the service down on the <em>test</em> thread so
 * executor join cannot starve later {@code runLater} pulses.</p>
 */
public final class JavaFxTestSupport {

    public static final long SCENARIO_TIMEOUT_SECONDS = 50L;

    private JavaFxTestSupport() {
    }

    /**
     * Copies the live window list by index. {@code Dialog.showAndWait} mutates
     * {@link Window#getWindows()} from its nested loop, so a for-each over that
     * list throws {@link IndexOutOfBoundsException}.
     */
    public static List<Window> snapshotWindows() {
        ObservableList<Window> live = Window.getWindows();
        List<Window> copy = new ArrayList<>();
        for (int i = 0; i < live.size(); i++) {
            if (i >= live.size()) {
                break;
            }
            copy.add(live.get(i));
        }
        return List.copyOf(copy);
    }

    public static void startToolkit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try {
            Platform.startup(started::countDown);
        } catch (IllegalStateException alreadyStarted) {
            started.countDown();
        }
        assertTrue(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        CountDownLatch configured = new CountDownLatch(1);
        Platform.runLater(() -> {
            Platform.setImplicitExit(false);
            configured.countDown();
        });
        assertTrue(configured.await(10, TimeUnit.SECONDS),
                "JavaFX implicitExit=false was not applied; later runLater calls can hang");
    }

    public static void runOnFx(String label, FxWork work) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            started.countDown();
            try {
                work.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(SCENARIO_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            boolean pulseStarted = started.getCount() == 0;
            throw new AssertionError("JavaFX scenario timed out (" + label + "). pulseStarted="
                    + pulseStarted + " implicitExit=" + Platform.isImplicitExit()
                    + " liveStellarThreads=" + liveStellarThreadNames());
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
    }

    /**
     * Wait until a later pulse runs. Use after background teardown so leftover
     * {@code runLater} work from dying executors cannot starve the next test.
     */
    public static void drainFxPulse() throws Exception {
        CountDownLatch drained = new CountDownLatch(1);
        Platform.runLater(drained::countDown);
        assertTrue(drained.await(5, TimeUnit.SECONDS),
                "FX pulse did not drain; liveStellarThreads=" + liveStellarThreadNames());
    }

    public static List<String> liveStellarThreadNames() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name != null && name.startsWith("stellar-"))
                .sorted()
                .collect(Collectors.toList());
    }

    public static void assertNoLiveStellarThreads(String context) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        List<String> live = liveStellarThreadNames();
        while (!live.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50L);
            live = liveStellarThreadNames();
        }
        assertTrue(live.isEmpty(),
                context + " leaked stellar threads: " + live);
    }

    @FunctionalInterface
    public interface FxWork {
        void run() throws Exception;
    }
}
