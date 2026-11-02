package com.stellar.platform.processing;

import com.stellar.platform.domain.CanonicalSignal;

import java.util.List;

/**
 * Streaming consumer for bounded batches of {@link CanonicalSignal}.
 * Implementations must not assume the full measurement fits in memory.
 */
@FunctionalInterface
public interface BatchSignalConsumer {

    void accept(List<CanonicalSignal> signals) throws Exception;
}
