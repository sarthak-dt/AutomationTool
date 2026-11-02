package com.stellar.platform.processing;

import com.stellar.platform.dbc.decode.DbcDecoder;
import com.stellar.platform.dbc.decode.DecodingResult;
import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcDiagnostic;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.ProcessingWarning;
import com.stellar.platform.domain.SourceReference;
import com.stellar.platform.domain.processing.ProcessingSelection;
import com.stellar.platform.parser.spi.AutomotiveFileReader;
import com.stellar.platform.parser.spi.NativeSignalCapableReader;
import com.stellar.platform.parser.spi.ParseCheckpointListener;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/**
 * Format-independent pipeline after parser SPI fan-out:
 * {@code AutomotiveFileReader → CanonicalCanFrame batch → DbcDecoder → CanonicalSignal}
 * and {@code NativeSignalCapableReader → CanonicalMeasurementSample → CanonicalSignal}.
 * Both paths share the same downstream signal consumer/catalog.
 * <p>
 * Diagnostics are aggregated online — memory scales with unique diagnostic codes, not record count.
 */
public final class MeasurementDecodePipeline {

    private final ParserRegistry parserRegistry;
    private final DbcDecoder dbcDecoder;

    public MeasurementDecodePipeline(ParserRegistry parserRegistry) {
        this(parserRegistry, new DefaultDbcDecoder());
    }

    public MeasurementDecodePipeline(ParserRegistry parserRegistry, DbcDecoder dbcDecoder) {
        this.parserRegistry = Objects.requireNonNull(parserRegistry, "parserRegistry");
        this.dbcDecoder = Objects.requireNonNull(dbcDecoder, "dbcDecoder");
    }

    public static MeasurementDecodePipeline createDefault() {
        return new MeasurementDecodePipeline(ParserRegistry.load());
    }

    public ProcessingResult process(
            Path measurementPath,
            DbcDatabase database,
            ReaderContext readerContext,
            BatchSignalConsumer signalConsumer
    ) throws ParserException {
        return process(measurementPath, database, readerContext, signalConsumer, ProcessingProgressListener.NOOP);
    }

    public ProcessingResult process(
            Path measurementPath,
            DbcDatabase database,
            ReaderContext readerContext,
            BatchSignalConsumer signalConsumer,
            ProcessingProgressListener progressListener
    ) throws ParserException {
        return process(measurementPath, database, readerContext, signalConsumer, progressListener, null);
    }

    public ProcessingResult process(
            Path measurementPath,
            DbcDatabase database,
            ReaderContext readerContext,
            BatchSignalConsumer signalConsumer,
            ProcessingProgressListener progressListener,
            ProcessingSelection selection
    ) throws ParserException {
        return process(measurementPath, database, readerContext, signalConsumer, progressListener, selection,
                NativeMeasurementPolicy.INCLUDE);
    }

    public ProcessingResult process(
            Path measurementPath,
            DbcDatabase database,
            ReaderContext readerContext,
            BatchSignalConsumer signalConsumer,
            ProcessingProgressListener progressListener,
            ProcessingSelection selection,
            NativeMeasurementPolicy nativeMeasurementPolicy
    ) throws ParserException {
        Objects.requireNonNull(measurementPath, "measurementPath");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(readerContext, "readerContext");
        Objects.requireNonNull(signalConsumer, "signalConsumer");
        AutomotiveFileReader reader = parserRegistry.findReader(measurementPath)
                .orElseThrow(() -> new ParserException("No AutomotiveFileReader supports: " + measurementPath));
        return process(reader, measurementPath, database, readerContext, signalConsumer, progressListener, selection,
                nativeMeasurementPolicy);
    }

    public ProcessingResult process(
            AutomotiveFileReader reader,
            Path measurementPath,
            DbcDatabase database,
            ReaderContext readerContext,
            BatchSignalConsumer signalConsumer
    ) throws ParserException {
        return process(reader, measurementPath, database, readerContext, signalConsumer, ProcessingProgressListener.NOOP);
    }

    public ProcessingResult process(
            AutomotiveFileReader reader,
            Path measurementPath,
            DbcDatabase database,
            ReaderContext readerContext,
            BatchSignalConsumer signalConsumer,
            ProcessingProgressListener progressListener
    ) throws ParserException {
        return process(reader, measurementPath, database, readerContext, signalConsumer, progressListener, null);
    }

    public ProcessingResult process(
            AutomotiveFileReader reader,
            Path measurementPath,
            DbcDatabase database,
            ReaderContext readerContext,
            BatchSignalConsumer signalConsumer,
            ProcessingProgressListener progressListener,
            ProcessingSelection selection
    ) throws ParserException {
        return process(reader, measurementPath, database, readerContext, signalConsumer, progressListener, selection,
                NativeMeasurementPolicy.INCLUDE);
    }

    public ProcessingResult process(
            AutomotiveFileReader reader,
            Path measurementPath,
            DbcDatabase database,
            ReaderContext readerContext,
            BatchSignalConsumer signalConsumer,
            ProcessingProgressListener progressListener,
            ProcessingSelection selection,
            NativeMeasurementPolicy nativeMeasurementPolicy
    ) throws ParserException {
        Objects.requireNonNull(database, "database");
        return processResolved(
                reader,
                measurementPath,
                readerContext,
                signalConsumer,
                progressListener,
                selection,
                ignoredChannel -> database,
                null,
                nativeMeasurementPolicy
        );
    }

    /**
     * Executes multiple channel→DBC routes for one measurement in a single source stream pass.
     * Every emitted signal remains bound to its original channel/source and is delivered to the
     * supplied consumer unchanged. No timestamp alignment, normalization or sample merging occurs.
     */
    public RoutedProcessingResult processRouted(
            Path measurementPath,
            Map<Integer, DbcDatabase> databasesByChannel,
            ReaderContext readerContext,
            BatchSignalConsumer signalConsumer,
            ProcessingProgressListener progressListener
    ) throws ParserException {
        Objects.requireNonNull(measurementPath, "measurementPath");
        AutomotiveFileReader reader = parserRegistry.findReader(measurementPath)
                .orElseThrow(() -> new ParserException("No AutomotiveFileReader supports: " + measurementPath));
        return processRouted(
                reader, measurementPath, databasesByChannel, readerContext, signalConsumer, progressListener);
    }

    /**
     * Reader-injected variant used by plan execution/tests while preserving the same routed path.
     */
    public RoutedProcessingResult processRouted(
            AutomotiveFileReader reader,
            Path measurementPath,
            Map<Integer, DbcDatabase> databasesByChannel,
            ReaderContext readerContext,
            BatchSignalConsumer signalConsumer,
            ProcessingProgressListener progressListener
    ) throws ParserException {
        Map<Integer, DbcDatabase> routes = validateRoutes(databasesByChannel);
        Map<Integer, MutableRouteStatistics> mutableRouteStats = new LinkedHashMap<>();
        for (Integer channelId : routes.keySet()) {
            mutableRouteStats.put(channelId, new MutableRouteStatistics());
        }
        ProcessingSelection selection = ProcessingSelection.forSource(
                ProcessingSelection.sourceIdentity(measurementPath), routes.keySet());
        ProcessingResult result = processResolved(
                reader,
                measurementPath,
                readerContext,
                signalConsumer,
                progressListener,
                selection,
                routes::get,
                mutableRouteStats,
                NativeMeasurementPolicy.INCLUDE
        );
        Map<Integer, RouteStatistics> snapshots = new LinkedHashMap<>();
        for (Map.Entry<Integer, MutableRouteStatistics> entry : mutableRouteStats.entrySet()) {
            snapshots.put(entry.getKey(), entry.getValue().snapshot());
        }
        return new RoutedProcessingResult(result, snapshots);
    }

    /**
     * One physical source scan feeding several independent per-job decode states.
     * <p>
     * Each route keeps exactly the selection, DBC, native policy, statistics, diagnostics,
     * signal consumer and checkpoint listener it would have in its own single-channel
     * {@link #process} call; only the reader pass is shared. Results are returned in route order.
     */
    public List<ProcessingResult> processFanout(
            AutomotiveFileReader reader,
            Path measurementPath,
            List<FanoutRoute> routes,
            ReaderContext readerContext,
            ProcessingProgressListener progressListener
    ) throws ParserException {
        Objects.requireNonNull(measurementPath, "measurementPath");
        Objects.requireNonNull(readerContext, "readerContext");
        if (routes == null || routes.isEmpty()) {
            throw new IllegalArgumentException("At least one fan-out route is required");
        }
        AutomotiveFileReader sourceReader = reader != null
                ? reader
                : parserRegistry.findReader(measurementPath)
                .orElseThrow(() -> new ParserException("No AutomotiveFileReader supports: " + measurementPath));
        ProcessingProgressListener progress = progressListener == null
                ? ProcessingProgressListener.NOOP
                : progressListener;
        String format = sourceReader.formatKey() == null ? "" : sourceReader.formatKey().toLowerCase();
        boolean mf4 = format.contains("mf4") || format.contains("mdf");
        String sourceIdentity = ProcessingSelection.sourceIdentity(measurementPath);

        List<DecodeRuntime> runtimes = new ArrayList<>(routes.size());
        List<ParseCheckpointListener> checkpointListeners = new ArrayList<>(routes.size());
        for (int i = 0; i < routes.size(); i++) {
            FanoutRoute route = routes.get(i);
            ReaderContext routeContext = route.checkpointListener() == ParseCheckpointListener.NONE
                    ? readerContext
                    : readerContext.withResume(null, route.checkpointListener(), readerContext.batchSize());
            DbcDatabase database = route.database();
            DecodeRuntime runtime = new DecodeRuntime(
                    measurementPath,
                    routeContext,
                    route.signalConsumer(),
                    i == 0 ? progress : ProcessingProgressListener.NOOP,
                    ProcessingSelection.forSource(sourceIdentity, List.of(route.channelId())),
                    ignoredChannel -> database,
                    null,
                    mf4,
                    route.nativeMeasurementPolicy()
            );
            runtimes.add(runtime);
            ParseCheckpointListener listener = runtime.checkpointAwareContext().checkpointListener();
            if (listener != ParseCheckpointListener.NONE) {
                checkpointListeners.add(listener);
            }
        }
        ReaderContext parseContext = checkpointListeners.isEmpty()
                ? readerContext
                : readerContext.withResume(null, (cursor, records, ts) -> {
                    for (ParseCheckpointListener listener : checkpointListeners) {
                        listener.onSafeCheckpoint(cursor, records, ts);
                    }
                }, readerContext.batchSize());

        progress.onStage(ProcessingStage.READING);
        if (mf4) {
            progress.onStage(ProcessingStage.MF4_METADATA_DISCOVERY);
            progress.onStage(ProcessingStage.MF4_SCHEMA_BUILDING);
            progress.onStage(ProcessingStage.MF4_DECODING);
        }
        if (sourceReader instanceof NativeSignalCapableReader nativeReader) {
            nativeReader.readFramesAndSignals(
                    measurementPath,
                    parseContext,
                    frames -> {
                        for (DecodeRuntime runtime : runtimes) {
                            runtime.acceptFrames(frames);
                        }
                    },
                    samples -> {
                        for (DecodeRuntime runtime : runtimes) {
                            runtime.acceptNativeSignals(samples);
                        }
                    },
                    warning -> {
                        for (DecodeRuntime runtime : runtimes) {
                            runtime.acceptWarning(warning);
                        }
                    }
            );
        } else {
            sourceReader.readFrames(
                    measurementPath,
                    parseContext,
                    frames -> {
                        for (DecodeRuntime runtime : runtimes) {
                            runtime.acceptFrames(frames);
                        }
                    },
                    warning -> {
                        for (DecodeRuntime runtime : runtimes) {
                            runtime.acceptWarning(warning);
                        }
                    }
            );
        }
        List<ProcessingResult> results = new ArrayList<>(runtimes.size());
        for (DecodeRuntime runtime : runtimes) {
            results.add(runtime.finish());
        }
        return results;
    }

    private ProcessingResult processResolved(
            AutomotiveFileReader reader,
            Path measurementPath,
            ReaderContext readerContext,
            BatchSignalConsumer signalConsumer,
            ProcessingProgressListener progressListener,
            ProcessingSelection selection,
            IntFunction<DbcDatabase> databaseResolver,
            Map<Integer, MutableRouteStatistics> routeStats,
            NativeMeasurementPolicy nativeMeasurementPolicy
    ) throws ParserException {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(measurementPath, "measurementPath");
        Objects.requireNonNull(readerContext, "readerContext");
        Objects.requireNonNull(signalConsumer, "signalConsumer");
        Objects.requireNonNull(databaseResolver, "databaseResolver");
        ProcessingProgressListener progress = progressListener == null
                ? ProcessingProgressListener.NOOP
                : progressListener;

        String format = reader.formatKey() == null ? "" : reader.formatKey().toLowerCase();
        boolean mf4 = format.contains("mf4") || format.contains("mdf");
        DecodeRuntime runtime = new DecodeRuntime(
                measurementPath,
                readerContext,
                signalConsumer,
                progress,
                selection,
                databaseResolver,
                routeStats,
                mf4,
                nativeMeasurementPolicy == null ? NativeMeasurementPolicy.INCLUDE : nativeMeasurementPolicy
        );
        ReaderContext parseContext = runtime.checkpointAwareContext();

        progress.onStage(ProcessingStage.READING);
        if (mf4) {
            progress.onStage(ProcessingStage.MF4_METADATA_DISCOVERY);
            progress.onStage(ProcessingStage.MF4_SCHEMA_BUILDING);
            progress.onStage(ProcessingStage.MF4_DECODING);
        }

        if (reader instanceof NativeSignalCapableReader nativeReader) {
            nativeReader.readFramesAndSignals(
                    measurementPath,
                    parseContext,
                    runtime::acceptFrames,
                    runtime::acceptNativeSignals,
                    runtime::acceptWarning
            );
        } else {
            reader.readFrames(
                    measurementPath,
                    parseContext,
                    runtime::acceptFrames,
                    runtime::acceptWarning
            );
        }
        return runtime.finish();
    }

    private final class DecodeRuntime {
        private final ReaderContext readerContext;
        private final BatchSignalConsumer signalConsumer;
        private final ProcessingProgressListener progress;
        private final ProcessingSelection selection;
        private final String sourceIdentity;
        private final IntFunction<DbcDatabase> databaseResolver;
        private final Map<Integer, MutableRouteStatistics> routeStats;
        private final boolean mf4;
        private final NativeMeasurementPolicy nativeMeasurementPolicy;
        private final ProcessingStatistics stats = new ProcessingStatistics();
        private final DiagnosticAccumulator diagnostics = new DiagnosticAccumulator();
        private final List<CanonicalSignal> signalBatch;
        private boolean cancelled;
        private boolean recoverySeen;

        private DecodeRuntime(
                Path measurementPath,
                ReaderContext readerContext,
                BatchSignalConsumer signalConsumer,
                ProcessingProgressListener progress,
                ProcessingSelection selection,
                IntFunction<DbcDatabase> databaseResolver,
                Map<Integer, MutableRouteStatistics> routeStats,
                boolean mf4,
                NativeMeasurementPolicy nativeMeasurementPolicy
        ) {
            this.readerContext = readerContext;
            this.signalConsumer = signalConsumer;
            this.progress = progress;
            this.selection = selection;
            this.sourceIdentity = ProcessingSelection.sourceIdentity(measurementPath);
            this.databaseResolver = databaseResolver;
            this.routeStats = routeStats;
            this.mf4 = mf4;
            this.nativeMeasurementPolicy = nativeMeasurementPolicy;
            this.signalBatch = new ArrayList<>(Math.max(1, readerContext.batchSize()));
        }

        private ReaderContext checkpointAwareContext() {
            if (readerContext.checkpointListener() == ParseCheckpointListener.NONE) {
                return readerContext;
            }
            ParseCheckpointListener original = readerContext.checkpointListener();
            return readerContext.withResume(readerContext.resumeFrom(), (cursor, records, ts) -> {
                if (!signalBatch.isEmpty()) {
                    flushSignals(signalConsumer, signalBatch, stats, routeStats);
                }
                if (signalConsumer instanceof GraphSignalSessionStore session) {
                    session.checkpoint();
                }
                original.onSafeCheckpoint(cursor, records, ts);
            }, readerContext.checkpointRecordInterval());
        }

        private void acceptFrames(List<CanonicalCanFrame> frameBatch) throws ParserException {
            if (readerContext.cancellationToken().isCancellationRequested()) {
                cancelled = true;
                return;
            }
            progress.onStage(ProcessingStage.DBC_DECODING);
            for (CanonicalCanFrame frame : frameBatch) {
                if (readerContext.cancellationToken().isCancellationRequested()) {
                    cancelled = true;
                    break;
                }
                stats.recordSourceFrame();
                if (frame.extended()) {
                    stats.recordExtendedFrame();
                } else {
                    stats.recordStandardFrame();
                }
                classifyFrameQuality(frame, stats);
                if (frame.quality() == FrameQuality.RECOVERED && !recoverySeen) {
                    recoverySeen = true;
                    progress.onStage(ProcessingStage.MF4_RECOVERY);
                    progress.onStage(ProcessingStage.DBC_DECODING);
                }

                if (selection != null && !selection.includes(sourceIdentity, frame.channel())) {
                    stats.recordFrameSkippedBySelection();
                    continue;
                }
                DbcDatabase database = databaseResolver.apply(frame.channel());
                if (database == null) {
                    // Routed execution intentionally ignores channels without a route.
                    stats.recordFrameSkippedBySelection();
                    continue;
                }

                stats.recordFrameSelectedForDecode();
                MutableRouteStatistics route = routeStats == null ? null : routeStats.get(frame.channel());
                if (route != null) {
                    route.selectedFrames++;
                }

                var matched = com.stellar.platform.dbc.decode.MessageMatcher.match(frame, database);
                if (matched.isEmpty()) {
                    stats.recordDbcUnmatched();
                    if (route != null) {
                        route.dbcUnmatchedFrames++;
                    }
                    diagnostics.record(
                            "DBC",
                            "UNMATCHED_CAN_ID",
                            "One or more CAN frames had no matching DBC message",
                            frame.source()
                    );
                    continue;
                }

                DecodingResult decoded = dbcDecoder.decodeMatched(frame, database, matched.get());
                switch (decoded.status()) {
                    case UNMATCHED -> {
                        stats.recordDbcUnmatched();
                        if (route != null) {
                            route.dbcUnmatchedFrames++;
                        }
                        diagnostics.record(
                                "DBC",
                                "UNMATCHED_CAN_ID",
                                "One or more CAN frames had no matching DBC message",
                                frame.source()
                        );
                    }
                    case MALFORMED, ERROR -> {
                        stats.recordMalformedSkipped();
                        stats.recordError();
                        if (route != null) {
                            route.malformedFrames++;
                        }
                        for (DbcDiagnostic d : decoded.diagnostics()) {
                            acceptDiagnostic(
                                    diagnostics,
                                    stats,
                                    ProcessingDiagnostic.ofDbc(d.code().name(), d.message(), frame.source()),
                                    true
                            );
                        }
                    }
                    case DECODED, PARTIAL -> {
                        stats.recordDbcMatched();
                        if (route != null) {
                            route.dbcMatchedFrames++;
                        }
                        for (DbcDiagnostic d : decoded.diagnostics()) {
                            acceptDiagnostic(
                                    diagnostics,
                                    stats,
                                    ProcessingDiagnostic.ofDbc(d.code().name(), d.message(), frame.source()),
                                    !DiagnosticAccumulator.isHighVolumeInformational(d.code().name())
                            );
                        }
                    }
                }

                for (CanonicalSignal signal : decoded.signals()) {
                    signalBatch.add(signal);
                    if (signalBatch.size() >= readerContext.batchSize()) {
                        flushSignals(signalConsumer, signalBatch, stats, routeStats);
                        publishStats(stats, diagnostics, progress);
                    }
                }
            }
            publishStats(stats, diagnostics, progress);
        }

        /**
         * Native measurement channels are source-scoped. Their {@code channel} is the measurement
         * file's channel-group record id, which shares no numbering with the CAN bus ids held by
         * {@link ProcessingSelection}, so neither the selection nor the channel→DBC routing may
         * gate them. Ownership across concurrent channel jobs is decided by the caller's policy.
         */
        private void acceptNativeSignals(List<com.stellar.platform.domain.CanonicalMeasurementSample> nativeBatch)
                throws ParserException {
            if (readerContext.cancellationToken().isCancellationRequested()) {
                cancelled = true;
                return;
            }
            if (!nativeMeasurementPolicy.publishes()) {
                return;
            }
            for (var sample : nativeBatch) {
                CanonicalSignal signal = sample.toCanonicalSignal(InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE);
                signalBatch.add(signal);
                if (signalBatch.size() >= readerContext.batchSize()) {
                    flushSignals(signalConsumer, signalBatch, stats, routeStats);
                    publishStats(stats, diagnostics, progress);
                }
            }
            publishStats(stats, diagnostics, progress);
        }

        private void acceptWarning(ProcessingWarning warning) {
            ProcessingDiagnostic diagnostic = toReaderDiagnostic(warning);
            boolean countAsWarning = !DiagnosticAccumulator.isHighVolumeInformational(warning.code());
            acceptDiagnostic(diagnostics, stats, diagnostic, countAsWarning);
            if (warning.code() != null && warning.code().toUpperCase().contains("NON_CAN")) {
                stats.recordNonCanGroupSkipped();
            }
            if (looksLikeRecovery(warning.code()) && !recoverySeen) {
                recoverySeen = true;
                progress.onStage(ProcessingStage.MF4_RECOVERY);
            }
        }

        private ProcessingResult finish() throws ParserException {
            if (!signalBatch.isEmpty()) {
                flushSignals(signalConsumer, signalBatch, stats, routeStats);
                publishStats(stats, diagnostics, progress);
            }

            if (stats.sourceFramesRead() > 0L
                    && stats.dbcMatchedFrames() == 0L
                    && stats.dbcUnmatchedFrames() > 0L) {
                acceptDiagnostic(
                        diagnostics,
                        stats,
                        ProcessingDiagnostic.ofDbc(
                                "DBC_NO_MATCHING_CAN_IDS",
                                "The selected DBC does not contain definitions for CAN IDs present "
                                        + "in this recording. CAN frames were decoded successfully; "
                                        + "DBC matching produced no messages.",
                                new SourceReference(-1L, -1L)
                        ),
                        true
                );
            }

            progress.onStage(ProcessingStage.FINALIZING);
            stats.setDiagnosticTotals(diagnostics.uniqueCategories(), diagnostics.totalOccurrences());
            boolean cancelledRun = cancelled || readerContext.cancellationToken().isCancellationRequested();
            ProcessingOutcome outcome = classifyOutcome(mf4, cancelledRun, stats, diagnostics);
            stats.setDiagnosticTotals(diagnostics.uniqueCategories(), diagnostics.totalOccurrences());
            List<ProcessingDiagnostic> representatives = diagnostics.representatives();
            boolean success = outcome == ProcessingOutcome.COMPLETED
                    || outcome == ProcessingOutcome.COMPLETED_WITH_WARNINGS;
            if (cancelledRun) {
                progress.onStage(ProcessingStage.CANCELLED);
            } else if (success) {
                progress.onStatistics(stats.snapshot());
            } else {
                progress.onStage(ProcessingStage.FAILED);
            }
            return new ProcessingResult(
                    stats.snapshot(),
                    representatives,
                    diagnostics.snapshot(),
                    cancelledRun,
                    success,
                    outcome
            );
        }
    }

    private static Map<Integer, DbcDatabase> validateRoutes(Map<Integer, DbcDatabase> databasesByChannel) {
        Objects.requireNonNull(databasesByChannel, "databasesByChannel");
        if (databasesByChannel.isEmpty()) {
            throw new IllegalArgumentException("At least one channel→DBC route is required");
        }
        Map<Integer, DbcDatabase> routes = new LinkedHashMap<>();
        for (Map.Entry<Integer, DbcDatabase> entry : databasesByChannel.entrySet()) {
            Integer channelId = entry.getKey();
            if (channelId == null || channelId < 0) {
                throw new IllegalArgumentException("Channel id must be >= 0");
            }
            routes.put(channelId, Objects.requireNonNull(entry.getValue(), "DBC for channel " + channelId));
        }
        return Map.copyOf(routes);
    }

    private static void acceptDiagnostic(
            DiagnosticAccumulator accumulator,
            ProcessingStatistics stats,
            ProcessingDiagnostic diagnostic,
            boolean countAsWarning
    ) {
        long before = accumulator.uniqueCategories();
        accumulator.record(diagnostic);
        if (countAsWarning && accumulator.uniqueCategories() > before) {
            stats.recordWarning();
        }
    }

    private static void publishStats(
            ProcessingStatistics stats,
            DiagnosticAccumulator diagnostics,
            ProcessingProgressListener progress
    ) {
        stats.setDiagnosticTotals(diagnostics.uniqueCategories(), diagnostics.totalOccurrences());
        progress.onStatistics(stats.snapshot());
    }

    private static ProcessingOutcome classifyOutcome(
            boolean mf4,
            boolean cancelledRun,
            ProcessingStatistics stats,
            DiagnosticAccumulator accumulator
    ) {
        if (cancelledRun) {
            return ProcessingOutcome.CANCELLED;
        }
        List<ProcessingDiagnostic> representatives = accumulator.representatives();
        boolean recoveryAttempted = representatives.stream().anyMatch(d -> looksLikeRecovery(d.code()))
                || stats.recoveredFrames() > 0L;
        boolean unsupported = representatives.stream().anyMatch(d ->
                codeContains(d.code(), "UNSUPPORTED") || codeContains(d.message(), "UNSUPPORTED"));
        boolean zeroFrames = stats.sourceFramesRead() == 0L;

        if (mf4 && zeroFrames && stats.nativeSignalsReceived() > 0L) {
            if (!accumulator.isEmpty()) {
                return ProcessingOutcome.COMPLETED_WITH_WARNINGS;
            }
            return ProcessingOutcome.COMPLETED;
        }

        if (mf4 && recoveryAttempted && zeroFrames) {
            if (unsupported) {
                accumulator.record(ProcessingDiagnostic.ofReader(
                        "MF4_UNSUPPORTED_STRUCTURE",
                        "MF4 recovery attempted against physical data but an unsupported structure "
                                + "prevented CanonicalCanFrame emission",
                        new SourceReference(-1L, -1L)
                ));
                stats.recordWarning();
                return ProcessingOutcome.UNSUPPORTED_STRUCTURE;
            }
            boolean noValid = representatives.stream().anyMatch(d ->
                    codeContains(d.code(), "NO_VALID")
                            || codeContains(d.message(), "NO_VALID_RECORDS")
                            || codeContains(d.message(), "no structurally valid"));
            if (noValid) {
                accumulator.record(ProcessingDiagnostic.ofReader(
                        "MF4_RECOVERY_FAILED",
                        "MF4 recovery attempted with physical data present but no CanonicalCanFrame "
                                + "could be recovered",
                        new SourceReference(-1L, -1L)
                ));
                stats.recordWarning();
                return ProcessingOutcome.RECOVERY_FAILED;
            }
            accumulator.record(ProcessingDiagnostic.ofReader(
                    "MF4_NO_CAN_DATA",
                    "MF4 physical data / recovery path produced zero CanonicalCanFrame objects",
                    new SourceReference(-1L, -1L)
            ));
            stats.recordWarning();
            return ProcessingOutcome.NO_CAN_DATA;
        }

        if ((!accumulator.isEmpty() || stats.dbcUnmatchedFrames() > 0L || stats.recoveredFrames() > 0L)
                && stats.sourceFramesRead() > 0L) {
            return ProcessingOutcome.COMPLETED_WITH_WARNINGS;
        }
        return ProcessingOutcome.COMPLETED;
    }

    private static boolean codeContains(String value, String token) {
        return value != null && value.toUpperCase().contains(token);
    }

    private static boolean looksLikeRecovery(String code) {
        if (code == null) {
            return false;
        }
        String upper = code.toUpperCase();
        return upper.contains("RECOVER");
    }

    private static void classifyFrameQuality(CanonicalCanFrame frame, ProcessingStatistics stats) {
        FrameQuality quality = frame.quality();
        if (quality == FrameQuality.RECOVERED) {
            stats.recordRecoveredFrame();
        } else if (quality == FrameQuality.VALID || quality == FrameQuality.OK) {
            stats.recordValidFrame();
        } else if (quality == FrameQuality.ERROR || quality == FrameQuality.INCOMPLETE) {
            stats.recordMalformedSkipped();
        } else {
            stats.recordValidFrame();
        }
    }

    private static ProcessingDiagnostic toReaderDiagnostic(ProcessingWarning warning) {
        SourceReference source = warning.source() == null
                ? new SourceReference(-1L, -1L)
                : warning.source();
        String code = warning.code();
        String message = warning.message();
        if (message != null && message.startsWith("[")) {
            int end = message.indexOf(']');
            if (end > 1) {
                code = message.substring(1, end);
            }
        }
        return ProcessingDiagnostic.ofReader(code, message, source);
    }

    private static void flushSignals(
            BatchSignalConsumer consumer,
            List<CanonicalSignal> batch,
            ProcessingStatistics stats,
            Map<Integer, MutableRouteStatistics> routeStats
    ) throws ParserException {
        List<CanonicalSignal> copy = List.copyOf(batch);
        batch.clear();
        try {
            consumer.accept(copy);
            int nativeCount = 0;
            int dbcCount = 0;
            for (CanonicalSignal signal : copy) {
                if (InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE.equals(signal.messageName())) {
                    nativeCount++;
                } else {
                    dbcCount++;
                }
                if (routeStats != null) {
                    MutableRouteStatistics route = routeStats.get(signal.channel());
                    if (route != null) {
                        route.signalsEmitted++;
                    }
                }
            }
            if (nativeCount > 0) {
                stats.recordNativeSignals(nativeCount);
            }
            if (dbcCount > 0) {
                stats.recordDbcSignals(dbcCount);
            }
        } catch (ParserException e) {
            throw e;
        } catch (Exception e) {
            throw new ParserException("Signal consumer failed", e);
        }
    }

    public record RouteStatistics(
            long selectedFrames,
            long dbcMatchedFrames,
            long dbcUnmatchedFrames,
            long malformedFrames,
            long signalsEmitted
    ) {
    }

    /** One independent single-channel decode state inside {@link #processFanout}. */
    public record FanoutRoute(
            int channelId,
            DbcDatabase database,
            BatchSignalConsumer signalConsumer,
            NativeMeasurementPolicy nativeMeasurementPolicy,
            ParseCheckpointListener checkpointListener
    ) {
        public FanoutRoute {
            if (channelId < 0) {
                throw new IllegalArgumentException("Channel id must be >= 0");
            }
            Objects.requireNonNull(database, "database");
            Objects.requireNonNull(signalConsumer, "signalConsumer");
            nativeMeasurementPolicy = nativeMeasurementPolicy == null
                    ? NativeMeasurementPolicy.INCLUDE
                    : nativeMeasurementPolicy;
            checkpointListener = checkpointListener == null ? ParseCheckpointListener.NONE : checkpointListener;
        }
    }

    public record RoutedProcessingResult(
            ProcessingResult processingResult,
            Map<Integer, RouteStatistics> routeStatistics
    ) {
        public RoutedProcessingResult {
            Objects.requireNonNull(processingResult, "processingResult");
            routeStatistics = Map.copyOf(Objects.requireNonNull(routeStatistics, "routeStatistics"));
        }

        public RouteStatistics route(int channelId) {
            return routeStatistics.getOrDefault(channelId, new RouteStatistics(0L, 0L, 0L, 0L, 0L));
        }
    }

    private static final class MutableRouteStatistics {
        private long selectedFrames;
        private long dbcMatchedFrames;
        private long dbcUnmatchedFrames;
        private long malformedFrames;
        private long signalsEmitted;

        private RouteStatistics snapshot() {
            return new RouteStatistics(
                    selectedFrames, dbcMatchedFrames, dbcUnmatchedFrames, malformedFrames, signalsEmitted);
        }
    }
}
