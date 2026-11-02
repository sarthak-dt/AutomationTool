package com.stellar.platform.parser.spi;

/**
 * Cooperative cancellation signal for long-running parse operations.
 */
@FunctionalInterface
public interface CancellationToken {

    /**
     * @return {@code true} if the caller has requested cancellation
     */
    boolean isCancellationRequested();

    /**
     * Token that is never cancelled.
     */
    CancellationToken NONE = () -> false;
}
