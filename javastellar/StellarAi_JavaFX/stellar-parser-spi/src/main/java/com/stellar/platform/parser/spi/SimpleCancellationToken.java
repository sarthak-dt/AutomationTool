package com.stellar.platform.parser.spi;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mutable cancellation token for tests and interactive cancellation.
 */
public final class SimpleCancellationToken implements CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public boolean isCancellationRequested() {
        return cancelled.get();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SimpleCancellationToken that)) {
            return false;
        }
        return cancelled.get() == that.cancelled.get();
    }

    @Override
    public int hashCode() {
        return Objects.hash(cancelled.get());
    }
}
