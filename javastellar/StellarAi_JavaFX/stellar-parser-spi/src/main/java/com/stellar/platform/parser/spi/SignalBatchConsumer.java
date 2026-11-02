package com.stellar.platform.parser.spi;

import com.stellar.platform.domain.CanonicalMeasurementSample;

import java.util.List;

/**
 * Receives bounded batches of {@link CanonicalMeasurementSample} values produced directly by a
 * measurement reader (native channels), without going through DBC matching.
 * <p>
 * Together with {@link CanFrameConsumer}, this is the parser SPI fan-out:
 * {@code CanonicalCanFrame} and {@code CanonicalMeasurementSample}.
 */
@FunctionalInterface
public interface SignalBatchConsumer {

    void onBatch(List<CanonicalMeasurementSample> batch) throws Exception;

    SignalBatchConsumer NOOP = batch -> {
    };
}
