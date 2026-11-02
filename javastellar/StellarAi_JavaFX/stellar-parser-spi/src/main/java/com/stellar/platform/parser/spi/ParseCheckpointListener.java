package com.stellar.platform.parser.spi;

/**
 * Invoked after a parser has delivered a complete consumer batch and advanced past those records.
 * {@code next} is the first uncommitted record. Processing must persist output before treating
 * this cursor as resumable.
 */
@FunctionalInterface
public interface ParseCheckpointListener {

    ParseCheckpointListener NONE = (next, recordsConsumed, lastTimestampNanos) -> {
    };

    void onSafeCheckpoint(ParserResumeCursor next, long recordsConsumed, Long lastTimestampNanos)
            throws Exception;
}
