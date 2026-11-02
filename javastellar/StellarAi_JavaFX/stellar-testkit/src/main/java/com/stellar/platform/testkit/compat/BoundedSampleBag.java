package com.stellar.platform.testkit.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

final class BoundedSampleBag<T> {
    private final int capacity;
    private final List<T> first = new ArrayList<>();
    private final List<T> reservoir = new ArrayList<>();
    private final List<T> extras = new ArrayList<>();
    private final int extraCapacity;
    private long seen;

    BoundedSampleBag(int capacity, int extraCapacity) {
        this.capacity = Math.max(1, capacity);
        this.extraCapacity = Math.max(0, extraCapacity);
    }

    void offer(T item) { offer(item, false); }

    void offer(T item, boolean preferExtra) {
        seen++;
        if (first.size() < capacity) { first.add(item); return; }
        if (preferExtra && extras.size() < extraCapacity) { extras.add(item); return; }
        long index = seen - 1;
        if (reservoir.size() < capacity) { reservoir.add(item); return; }
        long j = ThreadLocalRandom.current().nextLong(index + 1);
        if (j < capacity) { reservoir.set((int) j, item); }
    }

    long seen() { return seen; }

    List<T> snapshot() {
        List<T> out = new ArrayList<>(first.size() + reservoir.size() + extras.size());
        out.addAll(first);
        for (T t : reservoir) { if (!containsRef(out, t)) out.add(t); }
        for (T t : extras) { if (!containsRef(out, t)) out.add(t); }
        return out;
    }

    private static <T> boolean containsRef(List<T> list, T item) {
        for (T t : list) { if (t == item || (t != null && t.equals(item))) return true; }
        return false;
    }
}