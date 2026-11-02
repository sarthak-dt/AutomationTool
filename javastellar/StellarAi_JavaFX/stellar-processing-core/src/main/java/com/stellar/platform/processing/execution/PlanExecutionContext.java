package com.stellar.platform.processing.execution;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.domain.processing.plan.ProcessingJob;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ParseCheckpointListener;
import com.stellar.platform.parser.spi.ParserResumeCursor;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.ProcessingRequest;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Resolves source/definition artifacts for plan execution without UI coupling.
 * Prefer already-parsed {@link DbcDatabase} instances — no recommendation logic.
 */
public final class PlanExecutionContext {

    private final MeasurementDecodePipeline pipeline;
    private final Function<String, Path> measurementPathResolver;
    private final Function<String, Optional<DbcDatabase>> definitionDatabaseResolver;
    private final Function<String, Path> definitionPathResolver;
    private final Function<Path, Optional<AutomotiveFileReader>> readerResolver;
    private final int batchSize;
    private final CancellationToken cancellationToken;
    private final ProcessingProgressListener progressListener;
    private final SignalStoreFactory signalStoreFactory;
    private final JobResumeSupport resumeSupport;

    @FunctionalInterface
    public interface SignalStoreFactory {
        com.stellar.platform.processing.graph.GraphSignalSessionStore create(
                ProcessingJob job,
                String measurementDisplayName,
                String definitionDisplayName
        );
    }

    public PlanExecutionContext(
            MeasurementDecodePipeline pipeline,
            Function<String, Path> measurementPathResolver,
            Function<String, Optional<DbcDatabase>> definitionDatabaseResolver,
            Function<String, Path> definitionPathResolver,
            Function<Path, Optional<AutomotiveFileReader>> readerResolver,
            int batchSize,
            CancellationToken cancellationToken,
            ProcessingProgressListener progressListener
    ) {
        this(pipeline, measurementPathResolver, definitionDatabaseResolver, definitionPathResolver,
                readerResolver, batchSize, cancellationToken, progressListener, null);
    }

    public PlanExecutionContext(
            MeasurementDecodePipeline pipeline,
            Function<String, Path> measurementPathResolver,
            Function<String, Optional<DbcDatabase>> definitionDatabaseResolver,
            Function<String, Path> definitionPathResolver,
            Function<Path, Optional<AutomotiveFileReader>> readerResolver,
            int batchSize,
            CancellationToken cancellationToken,
            ProcessingProgressListener progressListener,
            SignalStoreFactory signalStoreFactory
    ) {
        this(pipeline, measurementPathResolver, definitionDatabaseResolver, definitionPathResolver,
                readerResolver, batchSize, cancellationToken, progressListener, signalStoreFactory,
                JobResumeSupport.NONE);
    }

    public PlanExecutionContext(
            MeasurementDecodePipeline pipeline,
            Function<String, Path> measurementPathResolver,
            Function<String, Optional<DbcDatabase>> definitionDatabaseResolver,
            Function<String, Path> definitionPathResolver,
            Function<Path, Optional<AutomotiveFileReader>> readerResolver,
            int batchSize,
            CancellationToken cancellationToken,
            ProcessingProgressListener progressListener,
            SignalStoreFactory signalStoreFactory,
            JobResumeSupport resumeSupport
    ) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.measurementPathResolver = Objects.requireNonNull(measurementPathResolver, "measurementPathResolver");
        this.definitionDatabaseResolver = Objects.requireNonNull(
                definitionDatabaseResolver, "definitionDatabaseResolver");
        this.definitionPathResolver = definitionPathResolver;
        this.readerResolver = readerResolver;
        this.batchSize = batchSize < 1 ? ProcessingRequest.DEFAULT_BATCH_SIZE : batchSize;
        this.cancellationToken = cancellationToken == null ? CancellationToken.NONE : cancellationToken;
        this.progressListener = progressListener == null ? ProcessingProgressListener.NOOP : progressListener;
        this.signalStoreFactory = signalStoreFactory;
        this.resumeSupport = resumeSupport == null ? JobResumeSupport.NONE : resumeSupport;
    }

    public static Builder builder(MeasurementDecodePipeline pipeline) {
        return new Builder(pipeline);
    }

    public MeasurementDecodePipeline pipeline() {
        return pipeline;
    }

    public Path measurementPath(String projectSourceId) {
        Path path = measurementPathResolver.apply(projectSourceId);
        if (path == null) {
            throw new IllegalArgumentException("No measurement path for source: " + projectSourceId);
        }
        return path;
    }

    public Optional<DbcDatabase> definitionDatabase(String definitionSourceId) {
        if (definitionSourceId == null || definitionSourceId.isBlank()) {
            return Optional.empty();
        }
        Optional<DbcDatabase> db = definitionDatabaseResolver.apply(definitionSourceId);
        return db == null ? Optional.empty() : db;
    }

    public Optional<Path> definitionPath(String definitionSourceId) {
        if (definitionPathResolver == null || definitionSourceId == null || definitionSourceId.isBlank()) {
            return Optional.empty();
        }
        Path path = definitionPathResolver.apply(definitionSourceId);
        return Optional.ofNullable(path);
    }

    public Optional<AutomotiveFileReader> readerFor(Path measurementPath) {
        if (readerResolver == null) {
            return Optional.empty();
        }
        Optional<AutomotiveFileReader> reader = readerResolver.apply(measurementPath);
        return reader == null ? Optional.empty() : reader;
    }

    public ReaderContext readerContext() {
        return new ReaderContext(batchSize, cancellationToken);
    }

    public ReaderContext readerContext(ProcessingJob job) {
        ReaderContext base = readerContext();
        if (job == null || resumeSupport == JobResumeSupport.NONE) {
            return base;
        }
        ParserResumeCursor cursor = resumeSupport.cursorFor(job).orElse(null);
        ParseCheckpointListener listener = resumeSupport.listenerFor(job);
        if (cursor == null && (listener == null || listener == ParseCheckpointListener.NONE)) {
            return base;
        }
        return base.withResume(cursor, listener, batchSize);
    }

    public JobResumeSupport resumeSupport() {
        return resumeSupport;
    }

    public CancellationToken cancellationToken() {
        return cancellationToken;
    }

    public ProcessingProgressListener progressListener() {
        return progressListener;
    }

    public int batchSize() {
        return batchSize;
    }

    public com.stellar.platform.processing.graph.GraphSignalSessionStore createSignalStore(
            ProcessingJob job,
            String measurementDisplayName,
            String definitionDisplayName
    ) {
        if (signalStoreFactory != null) {
            return signalStoreFactory.create(job, measurementDisplayName, definitionDisplayName);
        }
        return com.stellar.platform.processing.graph.GraphSignalSessionStore.create(
                measurementDisplayName, definitionDisplayName);
    }

    /** Convenience: catalog measurement/definition display names for a job. */
    public String measurementDisplayName(ProcessingJob job) {
        try {
            Path path = measurementPath(job.projectSourceId());
            return path.getFileName() == null ? path.toString() : path.getFileName().toString();
        } catch (RuntimeException ex) {
            return job.projectSourceId();
        }
    }

    public static final class Builder {
        private final MeasurementDecodePipeline pipeline;
        private Function<String, Path> measurementPathResolver;
        private Function<String, Optional<DbcDatabase>> definitionDatabaseResolver = id -> Optional.empty();
        private Function<String, Path> definitionPathResolver = id -> null;
        private Function<Path, Optional<AutomotiveFileReader>> readerResolver = path -> Optional.empty();
        private int batchSize = ProcessingRequest.DEFAULT_BATCH_SIZE;
        private CancellationToken cancellationToken = CancellationToken.NONE;
        private ProcessingProgressListener progressListener = ProcessingProgressListener.NOOP;
        private SignalStoreFactory signalStoreFactory;
        private JobResumeSupport resumeSupport = JobResumeSupport.NONE;

        private Builder(MeasurementDecodePipeline pipeline) {
            this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        }

        public Builder measurementPathResolver(Function<String, Path> resolver) {
            this.measurementPathResolver = Objects.requireNonNull(resolver, "resolver");
            return this;
        }

        public Builder definitionDatabaseResolver(Function<String, Optional<DbcDatabase>> resolver) {
            this.definitionDatabaseResolver = Objects.requireNonNull(resolver, "resolver");
            return this;
        }

        public Builder definitionPathResolver(Function<String, Path> resolver) {
            this.definitionPathResolver = resolver;
            return this;
        }

        public Builder readerResolver(Function<Path, Optional<AutomotiveFileReader>> resolver) {
            this.readerResolver = resolver;
            return this;
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder cancellationToken(CancellationToken token) {
            this.cancellationToken = token;
            return this;
        }

        public Builder progressListener(ProcessingProgressListener listener) {
            this.progressListener = listener;
            return this;
        }

        public Builder signalStoreFactory(SignalStoreFactory factory) {
            this.signalStoreFactory = factory;
            return this;
        }

        public Builder resumeSupport(JobResumeSupport resumeSupport) {
            this.resumeSupport = resumeSupport == null ? JobResumeSupport.NONE : resumeSupport;
            return this;
        }

        public PlanExecutionContext build() {
            Objects.requireNonNull(measurementPathResolver, "measurementPathResolver");
            return new PlanExecutionContext(
                    pipeline,
                    measurementPathResolver,
                    definitionDatabaseResolver,
                    definitionPathResolver,
                    readerResolver,
                    batchSize,
                    cancellationToken,
                    progressListener,
                    signalStoreFactory,
                    resumeSupport
            );
        }
    }
}
