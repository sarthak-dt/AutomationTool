package com.stellar.platform.processing;

import com.stellar.platform.domain.processing.ProcessingSelection;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.SimpleCancellationToken;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Input for a streaming measurement → DBC → signal decode job.
 */
public final class ProcessingRequest {

    public static final int DEFAULT_BATCH_SIZE = 4096;

    private final Path measurementPath;
    private final Path dbcPath;
    private final ExecutionMode executionMode;
    private final int batchSize;
    private final CancellationToken cancellationToken;
    private final ProcessingSelection selection;

    public ProcessingRequest(
            Path measurementPath,
            Path dbcPath,
            ExecutionMode executionMode,
            int batchSize,
            CancellationToken cancellationToken
    ) {
        this(measurementPath, dbcPath, executionMode, batchSize, cancellationToken, null);
    }

    public ProcessingRequest(
            Path measurementPath,
            Path dbcPath,
            ExecutionMode executionMode,
            int batchSize,
            CancellationToken cancellationToken,
            ProcessingSelection selection
    ) {
        this.measurementPath = Objects.requireNonNull(measurementPath, "measurementPath");
        this.dbcPath = Objects.requireNonNull(dbcPath, "dbcPath");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be >= 1");
        }
        this.batchSize = batchSize;
        this.cancellationToken = cancellationToken == null ? CancellationToken.NONE : cancellationToken;
        this.selection = selection;
    }

    public static ProcessingRequest local(Path measurementPath, Path dbcPath) {
        return local(measurementPath, dbcPath, DEFAULT_BATCH_SIZE, new SimpleCancellationToken(), null);
    }

    public static ProcessingRequest local(
            Path measurementPath,
            Path dbcPath,
            int batchSize,
            CancellationToken cancellationToken
    ) {
        return local(measurementPath, dbcPath, batchSize, cancellationToken, null);
    }

    public static ProcessingRequest local(
            Path measurementPath,
            Path dbcPath,
            int batchSize,
            CancellationToken cancellationToken,
            ProcessingSelection selection
    ) {
        return new ProcessingRequest(
                measurementPath, dbcPath, ExecutionMode.LOCAL, batchSize, cancellationToken, selection);
    }

    public Path measurementPath() {
        return measurementPath;
    }

    public Path dbcPath() {
        return dbcPath;
    }

    public ExecutionMode executionMode() {
        return executionMode;
    }

    public int batchSize() {
        return batchSize;
    }

    public CancellationToken cancellationToken() {
        return cancellationToken;
    }

    /**
     * When empty, the pipeline processes all channels (legacy / tests).
     * Desktop LOCAL runs always supply an explicit selection.
     */
    public Optional<ProcessingSelection> selection() {
        return Optional.ofNullable(selection);
    }
}
