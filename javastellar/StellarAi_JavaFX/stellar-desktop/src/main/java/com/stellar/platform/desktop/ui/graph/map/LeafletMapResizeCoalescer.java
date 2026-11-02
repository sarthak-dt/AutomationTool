package com.stellar.platform.desktop.ui.graph.map;

import javafx.application.Platform;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coalesces Leaflet {@code invalidateSize} requests onto the next two JavaFX pulses so
 * TabPane/SplitPane layout can settle. Does not sleep and does not fire on every layout tick.
 */
final class LeafletMapResizeCoalescer {

    private final AtomicInteger generation = new AtomicInteger();

    int schedule(Runnable action) {
        Objects.requireNonNull(action, "action");
        int gen = generation.incrementAndGet();
        Platform.runLater(() -> {
            if (gen != generation.get()) {
                return;
            }
            Platform.runLater(() -> {
                if (gen == generation.get()) {
                    action.run();
                }
            });
        });
        return gen;
    }

    int generation() {
        return generation.get();
    }
}
