package com.stellar.platform.api.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lightweight in-process metrics. No Prometheus/Kafka exporters.
 */
public final class PlatformMetrics {

    private static final PlatformMetrics INSTANCE = new PlatformMetrics();

    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    public static PlatformMetrics get() {
        return INSTANCE;
    }

    public void increment(String name) {
        increment(name, 1L);
    }

    public void increment(String name, long delta) {
        if (name == null || name.isBlank() || delta == 0L) {
            return;
        }
        counters.computeIfAbsent(name, k -> new LongAdder()).add(delta);
    }

    public void recordDurationMs(String name, long durationMs) {
        if (durationMs < 0L) {
            return;
        }
        increment(name + ".count");
        increment(name + ".totalMs", durationMs);
        gauges.computeIfAbsent(name + ".lastMs", k -> new AtomicLong()).set(durationMs);
    }

    public void setGauge(String name, long value) {
        if (name == null || name.isBlank()) {
            return;
        }
        gauges.computeIfAbsent(name, k -> new AtomicLong()).set(value);
    }

    public long counter(String name) {
        LongAdder adder = counters.get(name);
        return adder == null ? 0L : adder.sum();
    }

    public long gauge(String name) {
        AtomicLong g = gauges.get(name);
        return g == null ? 0L : g.get();
    }

    public Map<String, Long> snapshot() {
        ConcurrentHashMap<String, Long> out = new ConcurrentHashMap<>();
        counters.forEach((k, v) -> out.put(k, v.sum()));
        gauges.forEach((k, v) -> out.put(k, v.get()));
        return Map.copyOf(out);
    }

    /** Test helper. */
    public void resetForTests() {
        counters.clear();
        gauges.clear();
    }
}
