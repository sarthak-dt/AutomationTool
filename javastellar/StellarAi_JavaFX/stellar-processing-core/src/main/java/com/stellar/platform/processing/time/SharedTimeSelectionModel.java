package com.stellar.platform.processing.time;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Single source of truth for the active time filter across all analysis views.
 *
 * <p>Views observe this instead of keeping private filters, which is what makes map ↔ waveform
 * synchronization two-way without four competing states. Re-entrancy is blocked explicitly: a
 * listener that sets the selection while being notified cannot start a feedback loop, and setting
 * the same selection from a different origin is a no-op.
 */
public final class SharedTimeSelectionModel {

    private final List<Consumer<TimeSelection>> listeners = new CopyOnWriteArrayList<>();
    private volatile TimeSelection selection = TimeSelection.none();
    /** Inclusive analysis interval; independent of the single-timestamp cursor. */
    private volatile TimeSelection analysisRange = TimeSelection.none();
    private volatile MeasurementCursor measurementCursor = MeasurementCursor.EMPTY;
    /** True only after {@link #selectNoOwner}. A blank id from an older point is not this state. */
    private volatile boolean explicitNoOwner;
    private long cursorToken;
    private boolean notifying;

    public TimeSelection selection() {
        return selection;
    }

    /**
     * Active A→B / waveform interval. Survives {@link #selectPoint} so inspecting one timestamp
     * does not destroy the analysis range.
     */
    public TimeSelection analysisRange() {
        return analysisRange;
    }

    /** Measurement identity published with the latest point. Empty when the publisher did not name a file. */
    public MeasurementCursor measurementCursor() {
        return measurementCursor;
    }

    /** The latest point was an explicit gap: no recording owns that absolute time. */
    public boolean explicitNoOwner() {
        return explicitNoOwner;
    }

    /** @return a handle that removes the listener; callers must run it on dispose to avoid leaks. */
    public Runnable addListener(Consumer<TimeSelection> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public void removeListener(Consumer<TimeSelection> listener) {
        listeners.remove(listener);
    }

    public void clearListeners() {
        listeners.clear();
    }

    public int listenerCount() {
        return listeners.size();
    }

    public void clear(TimeSelection.Origin origin) {
        synchronized (this) {
            analysisRange = TimeSelection.none(origin);
            measurementCursor = MeasurementCursor.EMPTY;
            explicitNoOwner = false;
        }
        set(TimeSelection.none(origin));
    }

    public void selectPoint(long timestampNanos, TimeSelection.Origin origin) {
        set(TimeSelection.point(timestampNanos, origin));
    }

    /**
     * Point plus the owning measurement. The same stored timestamp in another file is a new selection.
     * A newer token supersedes a cursor that is still queued.
     */
    public boolean selectMeasurementPoint(
            String measurementId,
            long relativeTimestampNanos,
            Long absoluteTimestampNanos,
            boolean clockComparable,
            boolean placeMarker,
            TimeSelection.Origin origin
    ) {
        TimeSelection next = TimeSelection.point(relativeTimestampNanos, origin);
        MeasurementCursor cursor;
        synchronized (this) {
            if (notifying) {
                return false;
            }
            MeasurementCursor draft = new MeasurementCursor(
                    measurementId, relativeTimestampNanos, absoluteTimestampNanos, clockComparable, cursorToken + 1, placeMarker);
            if (selection.sameSelection(next) && measurementCursor.samePlace(draft) && !explicitNoOwner) {
                return false;
            }
            explicitNoOwner = false;
            cursorToken++;
            cursor = new MeasurementCursor(
                    measurementId, relativeTimestampNanos, absoluteTimestampNanos, clockComparable, cursorToken, placeMarker);
            measurementCursor = cursor;
            selection = next;
            notifying = true;
        }
        try {
            for (Consumer<TimeSelection> listener : listeners) {
                listener.accept(next);
            }
        } finally {
            synchronized (this) {
                notifying = false;
            }
        }
        return true;
    }

    /**
     * Shared-timeline gap. Clears measurement identity and asks listeners to drop a stale marker.
     * The absolute instant is kept so the graph cursor can stay where the user clicked.
     */
    public boolean selectNoOwner(long absoluteTimestampNanos, TimeSelection.Origin origin) {
        TimeSelection next = TimeSelection.point(absoluteTimestampNanos, origin);
        MeasurementCursor cursor;
        synchronized (this) {
            if (notifying) {
                return false;
            }
            MeasurementCursor draft = new MeasurementCursor(
                    "", 0L, absoluteTimestampNanos, true, cursorToken + 1, false);
            if (explicitNoOwner && selection.sameSelection(next) && measurementCursor.samePlace(draft)) {
                return false;
            }
            cursorToken++;
            cursor = new MeasurementCursor("", 0L, absoluteTimestampNanos, true, cursorToken, false);
            measurementCursor = cursor;
            explicitNoOwner = true;
            selection = next;
            notifying = true;
        }
        try {
            for (Consumer<TimeSelection> listener : listeners) {
                listener.accept(next);
            }
        } finally {
            synchronized (this) {
                notifying = false;
            }
        }
        return true;
    }

    public void selectRange(long startNanos, long endNanos, TimeSelection.Origin origin) {
        TimeSelection range = TimeSelection.range(startNanos, endNanos, origin);
        synchronized (this) {
            analysisRange = range;
        }
        set(range);
    }

    /** @return {@code true} when the selection actually changed and listeners were notified. */
    public boolean set(TimeSelection next) {
        Objects.requireNonNull(next, "next");
        synchronized (this) {
            if (next.kind() == TimeSelection.Kind.RANGE) {
                analysisRange = next;
            }
            if (notifying || selection.sameSelection(next)) {
                return false;
            }
            selection = next;
            notifying = true;
        }
        try {
            for (Consumer<TimeSelection> listener : listeners) {
                listener.accept(next);
            }
        } finally {
            synchronized (this) {
                notifying = false;
            }
        }
        return true;
    }

    /** Resets state and listeners; used when the active file or project changes. */
    public void reset() {
        synchronized (this) {
            selection = TimeSelection.none();
            analysisRange = TimeSelection.none();
            measurementCursor = MeasurementCursor.EMPTY;
            explicitNoOwner = false;
            cursorToken = 0;
        }
    }
}
