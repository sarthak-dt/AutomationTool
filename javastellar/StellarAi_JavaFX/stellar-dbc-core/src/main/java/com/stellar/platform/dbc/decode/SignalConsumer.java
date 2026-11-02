package com.stellar.platform.dbc.decode;

import com.stellar.platform.domain.CanonicalSignal;

/**
 * Streaming consumer for decoded signals (batch-friendly, back-pressure ready).
 */
@FunctionalInterface
public interface SignalConsumer {

    void onSignal(CanonicalSignal signal) throws Exception;
}
