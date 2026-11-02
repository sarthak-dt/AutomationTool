package com.stellar.platform.desktop.ui.graph.map;

import javafx.animation.PauseTransition;
import javafx.util.Duration;

/**
 * Bounded failure detection for Leaflet startup.
 *
 * <p>Normal sequencing is driven by engine and layout events, never by waiting. This timer exists
 * only for the case those events never arrive — a broken WebKit, a script error inside Leaflet, a
 * container that reports a usable size and then never paints. Without it the workspace can sit on
 * "Loading map…" for the rest of the session with no way out, so it is armed the moment Leaflet is
 * asked to initialize and cancelled the moment it reports ready.
 *
 * <p>Runs on the JavaFX application thread and starts no threads of its own.
 */
final class LeafletReadinessWatchdog {

    /** Generous next to a local script evaluation, so a slow machine is never called a failure. */
    static final Duration TIMEOUT = Duration.seconds(15);

    private PauseTransition timer;

    /** Arms the timer once; repeated arming while already waiting keeps the original deadline. */
    void arm(Runnable onTimeout) {
        if (timer != null || onTimeout == null) {
            return;
        }
        timer = new PauseTransition(TIMEOUT);
        timer.setOnFinished(event -> {
            timer = null;
            onTimeout.run();
        });
        timer.playFromStart();
    }

    void cancel() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    boolean armed() {
        return timer != null;
    }
}
