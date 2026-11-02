package com.stellar.platform.parser.spi;

import com.stellar.platform.domain.FileMetadata;

import java.nio.file.Path;

/**
 * Streaming reader for automotive measurement files that emit {@code CanonicalCanFrame} batches.
 * Concrete format plugins live in separate modules and are discovered via {@link ParserProvider}.
 */
public interface AutomotiveFileReader {

    /**
     * @return a short format key such as {@code "asc"}, {@code "mf4"}, or {@code "blf"}
     */
    String formatKey();

    /**
     * @return {@code true} if this reader can handle the given path
     */
    boolean supports(Path path);

    /**
     * Reads lightweight file metadata without loading frame payloads into memory.
     */
    FileMetadata readMetadata(Path path) throws ParserException;

    /**
     * Streams frames line/record-wise into bounded batches. Must not load the entire file into memory.
     */
    void readFrames(
            Path path,
            ReaderContext context,
            CanFrameConsumer frameConsumer,
            WarningConsumer warningConsumer
    ) throws ParserException;
}
