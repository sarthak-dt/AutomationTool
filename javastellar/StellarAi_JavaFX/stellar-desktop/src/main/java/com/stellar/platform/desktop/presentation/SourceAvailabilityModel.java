package com.stellar.platform.desktop.presentation;

import com.stellar.platform.desktop.project.ProjectSourceId;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sparse record of measurement sources whose file is no longer present at its recorded location.
 *
 * <p>Sources are available by default, so nothing is stored for a healthy project. Entries are
 * added only where an existence check has actually been performed — at project hydration, or when
 * the Sources workspace explicitly asks for a refresh. Resume routing never consults the file
 * system, so this model stays empty until something needs real availability.</p>
 */
public final class SourceAvailabilityModel {

    private final Set<ProjectSourceId> unavailable = new LinkedHashSet<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    public synchronized boolean available(ProjectSourceId sourceId) {
        return sourceId == null || !unavailable.contains(sourceId);
    }

    public void markUnavailable(ProjectSourceId sourceId) {
        if (sourceId == null) {
            return;
        }
        boolean changed;
        synchronized (this) {
            changed = unavailable.add(sourceId);
        }
        if (changed) {
            notifyListeners();
        }
    }

    public void markAvailable(ProjectSourceId sourceId) {
        if (sourceId == null) {
            return;
        }
        boolean changed;
        synchronized (this) {
            changed = unavailable.remove(sourceId);
        }
        if (changed) {
            notifyListeners();
        }
    }

    /** Applies a completed availability sweep in one step so the UI updates once. */
    public void replaceUnavailable(Collection<ProjectSourceId> missing) {
        Set<ProjectSourceId> next = missing == null ? Set.of() : new LinkedHashSet<>(missing);
        boolean changed;
        synchronized (this) {
            changed = !unavailable.equals(next);
            if (changed) {
                unavailable.clear();
                unavailable.addAll(next);
            }
        }
        if (changed) {
            notifyListeners();
        }
    }

    public synchronized int unavailableCount() {
        return unavailable.size();
    }

    public void clear() {
        boolean changed;
        synchronized (this) {
            changed = !unavailable.isEmpty();
            unavailable.clear();
        }
        if (changed) {
            notifyListeners();
        }
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
