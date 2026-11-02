package com.stellar.platform.dbc.model;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable DBC message (BO_) definition.
 */
public record DbcMessage(
        CanIdKey canId,
        String name,
        int sizeBytes,
        String transmitter,
        List<DbcSignal> signals,
        String comment
) {

    public DbcMessage {
        Objects.requireNonNull(canId, "canId");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("message name must not be blank");
        }
        if (sizeBytes < 0 || sizeBytes > 64) {
            throw new IllegalArgumentException("message size must be in 0..64");
        }
        transmitter = transmitter == null ? "" : transmitter;
        signals = List.copyOf(Objects.requireNonNull(signals, "signals"));
    }

    public boolean extended() {
        return canId.extended();
    }

    public long numericId() {
        return canId.canId();
    }

    public Optional<DbcSignal> multiplexor() {
        return signals.stream().filter(s -> s.multiplexRole() == DbcMultiplexRole.MULTIPLEXOR).findFirst();
    }

    /** Builds a fresh multiplex relationship graph from this message's metadata. */
    public DbcMultiplexGraph multiplexGraph() {
        return DbcMultiplexGraph.build(this);
    }
}