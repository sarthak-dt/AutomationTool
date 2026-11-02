package com.stellar.platform.parser.spi;

import java.util.Objects;

/**
 * Options controlling streaming reads from an {@link AutomotiveFileReader}.
 *
 * <p>{@code resumeFrom} and {@code checkpointListener} are optional. Readers that do not
 * implement resume ignore them and scan from the beginning of the file.</p>
 */
public record ReaderContext(
        int batchSize,
        CancellationToken cancellationToken,
        ParserResumeCursor resumeFrom,
        ParseCheckpointListener checkpointListener,
        int checkpointRecordInterval
) {

    public ReaderContext {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }
        cancellationToken = Objects.requireNonNullElse(cancellationToken, CancellationToken.NONE);
        checkpointListener = checkpointListener == null ? ParseCheckpointListener.NONE : checkpointListener;
        if (checkpointRecordInterval < 0) {
            checkpointRecordInterval = 0;
        }
    }

    public ReaderContext(int batchSize, CancellationToken cancellationToken) {
        this(batchSize, cancellationToken, null, ParseCheckpointListener.NONE, 0);
    }

    public static ReaderContext ofBatchSize(int batchSize) {
        return new ReaderContext(batchSize, CancellationToken.NONE);
    }

    public int effectiveCheckpointInterval() {
        return checkpointRecordInterval > 0 ? checkpointRecordInterval : batchSize;
    }

    public ReaderContext withResume(
            ParserResumeCursor cursor,
            ParseCheckpointListener listener,
            int interval
    ) {
        return new ReaderContext(batchSize, cancellationToken, cursor, listener, interval);
    }
}
