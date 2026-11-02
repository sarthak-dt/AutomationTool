package com.stellar.platform.parser.spi;

import com.stellar.platform.domain.CanonicalCanFrame;

import java.util.List;

/**
 * Receives bounded batches of canonical CAN frames from a streaming reader.
 */
@FunctionalInterface
public interface CanFrameConsumer {

    /**
     * Called with a non-empty batch of frames (size up to {@link ReaderContext#batchSize()}).
     *
     * @param batch immutable batch of frames in file order
     * @throws Exception if the consumer fails; readers wrap non-{@link ParserException} causes
     */
    void onBatch(List<CanonicalCanFrame> batch) throws Exception;
}
