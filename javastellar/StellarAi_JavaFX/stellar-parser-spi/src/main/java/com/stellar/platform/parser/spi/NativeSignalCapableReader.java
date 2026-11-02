package com.stellar.platform.parser.spi;

/**
 * Optional capability for readers that can emit native {@link com.stellar.platform.domain.CanonicalMeasurementSample}
 * batches in addition to {@link com.stellar.platform.domain.CanonicalCanFrame} batches.
 */
public interface NativeSignalCapableReader extends AutomotiveFileReader {

    void readFramesAndSignals(
            java.nio.file.Path path,
            ReaderContext context,
            CanFrameConsumer frameConsumer,
            SignalBatchConsumer nativeMeasurementConsumer,
            WarningConsumer warningConsumer
    ) throws ParserException;
}
