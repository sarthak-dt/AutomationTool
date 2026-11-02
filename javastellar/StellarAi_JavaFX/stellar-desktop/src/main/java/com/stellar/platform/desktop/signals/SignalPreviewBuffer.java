package com.stellar.platform.desktop.signals;

import com.stellar.platform.domain.CanonicalSignal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded ring of recent {@link CanonicalSignal} rows for table preview.
 * Full totals live in processing statistics — never assume this holds all signals.
 */
public final class SignalPreviewBuffer {

    public static final int DEFAULT_CAPACITY = 5_000;

    private final int capacity;
    private final ArrayDeque<CanonicalSignal> window;
    private final ReentrantLock lock = new ReentrantLock();
    private long totalAccepted;

    public SignalPreviewBuffer(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
        this.window = new ArrayDeque<>(Math.min(capacity, 1024));
    }

    public void accept(List<CanonicalSignal> batch) {
        Objects.requireNonNull(batch, "batch");
        lock.lock();
        try {
            for (CanonicalSignal signal : batch) {
                if (window.size() >= capacity) {
                    window.removeFirst();
                }
                window.addLast(signal);
                totalAccepted++;
            }
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    public long totalAccepted() {
        lock.lock();
        try {
            return totalAccepted;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return window.size();
        } finally {
            lock.unlock();
        }
    }

    public boolean truncated() {
        return totalAccepted() > capacity;
    }

    public List<CanonicalSignal> snapshot() {
        lock.lock();
        try {
            return List.copyOf(window);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Filters a snapshot of the preview window (safe to call off the UI thread).
     */
    public List<CanonicalSignal> filter(String signalName, String messageName, String canIdText) {
        String signalQ = normalize(signalName);
        String messageQ = normalize(messageName);
        String canQ = normalize(canIdText);

        List<CanonicalSignal> source = snapshot();
        if (signalQ.isEmpty() && messageQ.isEmpty() && canQ.isEmpty()) {
            return source;
        }

        List<CanonicalSignal> out = new ArrayList<>();
        for (CanonicalSignal s : source) {
            if (!signalQ.isEmpty() && !s.signalName().toLowerCase(Locale.ROOT).contains(signalQ)) {
                continue;
            }
            if (!messageQ.isEmpty() && !s.messageName().toLowerCase(Locale.ROOT).contains(messageQ)) {
                continue;
            }
            if (!canQ.isEmpty() && !matchesCanId(s.canId(), canQ)) {
                continue;
            }
            out.add(s);
        }
        return List.copyOf(out);
    }

    private static boolean matchesCanId(long canId, String query) {
        String dec = Long.toString(canId);
        String hex = Long.toHexString(canId).toLowerCase(Locale.ROOT);
        String hexPrefixed = "0x" + hex;
        return dec.contains(query) || hex.contains(query) || hexPrefixed.contains(query);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
