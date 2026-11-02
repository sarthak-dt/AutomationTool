package com.stellar.platform.desktop.processing;

import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.parse.DbcParseException;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.desktop.project.MeasurementSourceCapability;
import com.stellar.platform.desktop.project.ProcessingSummary;
import com.stellar.platform.desktop.project.ProjectProcessingStatus;
import com.stellar.platform.desktop.project.WorkspaceFile;
import com.stellar.platform.desktop.signals.SignalPreviewBuffer;
import com.stellar.platform.domain.CanonicalMeasurementSample;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.processing.ProcessingSelection;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.parser.spi.NativeSignalCapableReader;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.SimpleCancellationToken;
import com.stellar.platform.processing.BatchSignalConsumer;
import com.stellar.platform.processing.ExecutionMode;
import com.stellar.platform.processing.MeasurementDecodePipeline;
import com.stellar.platform.processing.ProcessingProgressListener;
import com.stellar.platform.processing.ParserRegistry;
import com.stellar.platform.processing.ProcessingRequest;
import com.stellar.platform.processing.ProcessingResult;
import com.stellar.platform.processing.ProcessingStage;
import com.stellar.platform.processing.ProcessingStatistics;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;
import com.stellar.platform.processing.catalog.SignalSeriesStore;
import com.stellar.platform.processing.graph.GraphSignalSessionStore;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Desktop gateway into {@link MeasurementDecodePipeline}. LOCAL mode only;
 * REMOTE is handled by {@link RemoteProcessingGateway}.
 */
public final class LocalProcessingGateway implements ProcessingGateway {

    private final MeasurementDecodePipeline pipeline;
    private final DbcParser dbcParser;
    private final AtomicReference<SimpleCancellationToken> activeToken = new AtomicReference<>();

    public LocalProcessingGateway(MeasurementDecodePipeline pipeline) {
        this(pipeline, new DbcParser());
    }

    public LocalProcessingGateway(MeasurementDecodePipeline pipeline, DbcParser dbcParser) {
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.dbcParser = Objects.requireNonNull(dbcParser, "dbcParser");
    }

    public static LocalProcessingGateway createDefault() {
        return new LocalProcessingGateway(MeasurementDecodePipeline.createDefault());
    }

    public MeasurementDecodePipeline pipeline() {
        return pipeline;
    }

    /**
     * Cancels the in-flight LOCAL job via the shared {@link SimpleCancellationToken}.
     */
    @Override
    public void cancel() {
        SimpleCancellationToken token = activeToken.get();
        if (token != null) {
            token.cancel();
        }
    }

    /**
     * Runs LOCAL decode synchronously (caller must invoke off the JavaFX Application Thread).
     */
    public GatewayResult process(
            WorkspaceFile measurement,
            WorkspaceFile definition,
            int batchSize,
            int previewCapacity,
            ProcessingProgressListener progressListener,
            Consumer<List<CanonicalSignal>> previewBatchListener
    ) {
        return process(
                measurement,
                definition,
                batchSize,
                new SignalPreviewBuffer(previewCapacity),
                progressListener,
                previewBatchListener
        );
    }

    public GatewayResult process(
            WorkspaceFile measurement,
            WorkspaceFile definition,
            int batchSize,
            SignalPreviewBuffer previewBuffer,
            ProcessingProgressListener progressListener,
            Consumer<List<CanonicalSignal>> previewBatchListener
    ) {
        return process(measurement, definition, batchSize, previewBuffer, progressListener, previewBatchListener, null);
    }

    @Override
    public GatewayResult process(
            WorkspaceFile measurement,
            WorkspaceFile definition,
            int batchSize,
            SignalPreviewBuffer previewBuffer,
            ProcessingProgressListener progressListener,
            Consumer<List<CanonicalSignal>> previewBatchListener,
            ProcessingSelection selection
    ) {
        Objects.requireNonNull(measurement, "measurement");
        if (MeasurementSourceCapability.supportsDirectProcessing(measurement.kind())) {
            return processDirect(measurement, batchSize, previewBuffer, progressListener, previewBatchListener, null);
        }
        Objects.requireNonNull(definition, "definition");
        if (measurement.status() == com.stellar.platform.desktop.project.FileValidationStatus.UNSUPPORTED) {
            String message = measurement.validationMessage();
            if (message == null || message.isBlank()) {
                message = "Detected " + measurement.formatDisplayName()
                        + ", but this version is not currently supported.";
            }
            return failedResult(
                    measurement.formatDisplayName(),
                    measurement.path(),
                    definition.path(),
                    Instant.now(),
                    previewBuffer == null ? new SignalPreviewBuffer(SignalPreviewBuffer.DEFAULT_CAPACITY) : previewBuffer,
                    message,
                    message,
                    measurement);
        }
        SignalPreviewBuffer preview = previewBuffer == null
                ? new SignalPreviewBuffer(SignalPreviewBuffer.DEFAULT_CAPACITY)
                : previewBuffer;
        ProcessingProgressListener progress = progressListener == null
                ? ProcessingProgressListener.NOOP
                : progressListener;

        Instant started = Instant.now();
        progress.onStage(ProcessingStage.VALIDATING);

        SimpleCancellationToken token = new SimpleCancellationToken();
        activeToken.set(token);

        List<String> technicalErrors = new CopyOnWriteArrayList<>();

        Path measurementPath = measurement.path();
        Path dbcPath = definition.path();
        String format = measurement.formatDisplayName();

        ProcessingRequest request = ProcessingRequest.local(measurementPath, dbcPath, batchSize, token, selection);
        if (request.executionMode() != ExecutionMode.LOCAL) {
            throw new IllegalStateException("Only LOCAL execution is supported in this slice");
        }

        try {
            DbcDatabase database;
            try {
                database = dbcParser.parse(dbcPath);
            } catch (DbcParseException e) {
                String tech = e.getMessage() == null ? e.toString() : e.getMessage();
                technicalErrors.add(tech);
                progress.onStage(ProcessingStage.FAILED);
                return failedResult(
                        format, measurementPath, dbcPath, started, preview,
                        "DBC file could not be parsed.",
                        tech,
                        measurement
                );
            }
            if (database.messages().isEmpty()) {
                String tech = "DBC contained no messages after parse: " + dbcPath;
                technicalErrors.add(tech);
                progress.onStage(ProcessingStage.FAILED);
                return failedResult(
                        format, measurementPath, dbcPath, started, preview,
                        "DBC file is malformed or contains no messages.",
                        tech,
                        measurement
                );
            }

            ReaderContext context = new ReaderContext(request.batchSize(), request.cancellationToken());
            GraphSignalSessionStore catalogStore = GraphSignalSessionStore.create(
                    measurementPath.getFileName().toString(),
                    dbcPath.getFileName().toString()
            );
            BatchSignalConsumer consumer = batch -> {
                preview.accept(batch);
                catalogStore.accept(batch);
                if (previewBatchListener != null) {
                    previewBatchListener.accept(batch);
                }
            };

            ProcessingResult result = pipeline.process(
                    measurementPath,
                    database,
                    context,
                    consumer,
                    progress,
                    selection
            );

            Duration elapsed = Duration.between(started, Instant.now());
            boolean recoveryAttempted = recoveryAttempted(result) || measurement.recoveryCandidate();
            boolean recoveryUsed = recoveryAttempted;
            String recoveryState = recoveryState(recoveryAttempted, result);
            String mf4Status = measurement.mf4FinalizationLabel().isBlank()
                    ? (recoveryAttempted ? "UNFINALIZED" : "")
                    : measurement.mf4FinalizationLabel();

            ProjectProcessingStatus status;
            String userMessage;
            if (result.cancelled()) {
                status = ProjectProcessingStatus.CANCELLED;
                userMessage = "Processing cancelled.";
                progress.onStage(ProcessingStage.CANCELLED);
            } else {
                status = mapOutcome(result.outcome());
                userMessage = outcomeMessage(result);
                if (!result.completedSuccessfully()) {
                    progress.onStage(ProcessingStage.FAILED);
                }
            }

            String selectedLabel = selection == null || selection.isEmpty()
                    ? "All channels"
                    : selection.summaryLabel();
            ProcessingSummary summary = new ProcessingSummary(
                    format,
                    measurementPath,
                    dbcPath,
                    result.statistics(),
                    elapsed,
                    status,
                    result.aggregatedDiagnostics(),
                    userMessage,
                    String.join("\n", technicalErrors),
                    recoveryUsed,
                    recoveryState,
                    mf4Status,
                    selectedLabel,
                    0L
            );
            return new GatewayResult(summary, preview, result, null, catalogStore);
        } catch (ParserException e) {
            String tech = e.getMessage() == null ? e.toString() : e.getMessage();
            progress.onStage(token.isCancellationRequested() ? ProcessingStage.CANCELLED : ProcessingStage.FAILED);
            return failedResult(
                    format,
                    measurementPath,
                    dbcPath,
                    started,
                    preview,
                    token.isCancellationRequested()
                            ? "Processing cancelled."
                            : "Processing failed: " + friendlyParserMessage(e),
                    tech,
                    measurement
            );
        } catch (RuntimeException e) {
            String tech = e.getMessage() == null ? e.toString() : e.getMessage();
            progress.onStage(ProcessingStage.FAILED);
            return failedResult(
                    format, measurementPath, dbcPath, started, preview,
                    "Unexpected processing error.",
                    tech,
                    measurement
            );
        } finally {
            activeToken.compareAndSet(token, null);
        }
    }

    /**
     * Direct DAT/LOG processing that writes the catalog into {@code durableRoot}.
     * The caller owns that directory and must commit or delete it. A null root keeps
     * the session-temp store used by callers that have no saved project.
     */
    public GatewayResult process(
            WorkspaceFile measurement,
            WorkspaceFile definition,
            int batchSize,
            SignalPreviewBuffer previewBuffer,
            ProcessingProgressListener progressListener,
            Consumer<List<CanonicalSignal>> previewBatchListener,
            ProcessingSelection selection,
            Path directDurableRoot
    ) {
        if (MeasurementSourceCapability.supportsDirectProcessing(measurement.kind())) {
            return processDirect(
                    measurement, batchSize, previewBuffer, progressListener, previewBatchListener, directDurableRoot);
        }
        return process(
                measurement, definition, batchSize, previewBuffer, progressListener, previewBatchListener, selection);
    }

    private GatewayResult processDirect(
            WorkspaceFile measurement,
            int batchSize,
            SignalPreviewBuffer previewBuffer,
            ProcessingProgressListener progressListener,
            Consumer<List<CanonicalSignal>> previewBatchListener,
            Path durableRoot
    ) {
        SignalPreviewBuffer preview = previewBuffer == null
                ? new SignalPreviewBuffer(SignalPreviewBuffer.DEFAULT_CAPACITY)
                : previewBuffer;
        ProcessingProgressListener progress = progressListener == null
                ? ProcessingProgressListener.NOOP
                : progressListener;
        Instant started = Instant.now();
        Path measurementPath = measurement.path();
        Path noDefinition = Path.of("");
        String format = measurement.formatDisplayName();
        progress.onStage(ProcessingStage.VALIDATING);
        SimpleCancellationToken token = new SimpleCancellationToken();
        activeToken.set(token);
        GraphSignalSessionStore catalogStore = null;
        boolean handOffStore = false;
        try {
            if (measurement.status() == com.stellar.platform.desktop.project.FileValidationStatus.UNSUPPORTED) {
                String message = measurement.validationMessage();
                if (message == null || message.isBlank()) {
                    message = "StellarAI could not determine the measurement structure automatically.";
                }
                progress.onStage(ProcessingStage.FAILED);
                return failedResult(format, measurementPath, noDefinition, started, preview, message, message, measurement);
            }
            ParserRegistry registry = ParserRegistry.load();
            var detected = registry.detect(measurementPath);
            if (!detected.parserSupported()) {
                String message = detected.recognized() && detected.content() != null
                        ? detected.content().unsupportedVersionMessage()
                        : detected.unknownMessage();
                progress.onStage(ProcessingStage.FAILED);
                return failedResult(format, measurementPath, noDefinition, started, preview, message, message, measurement);
            }
            var reader = registry.resolve(measurementPath, detected);
            if (reader.isEmpty()) {
                String message = "StellarAI could not determine the measurement structure automatically.";
                progress.onStage(ProcessingStage.FAILED);
                return failedResult(format, measurementPath, noDefinition, started, preview, message, message, measurement);
            }
            String detectedLabel = detected.displayLabel().isBlank() ? format : detected.displayLabel();
            if (!(reader.get() instanceof NativeSignalCapableReader nativeReader)) {
                String message = "Detected " + detectedLabel
                        + ". Direct processing did not create signals because this container has no measurement channels.";
                progress.onStage(ProcessingStage.FAILED);
                return failedResult(detectedLabel, measurementPath, noDefinition, started, preview,
                        message, message, measurement);
            }
            progress.onStage(ProcessingStage.READING);
            GraphSignalSessionStore writing = durableRoot == null
                    ? GraphSignalSessionStore.create(measurementPath.getFileName().toString(), "")
                    : GraphSignalSessionStore.createPersistent(
                            durableRoot, measurementPath.getFileName().toString(), "");
            catalogStore = writing;
            ProcessingStatistics statistics = new ProcessingStatistics();
            List<CanonicalSignal> pending = new ArrayList<>();
            int flushSize = Math.max(1, batchSize);
            ReaderContext context = new ReaderContext(flushSize, token);
            nativeReader.readFramesAndSignals(
                    measurementPath,
                    context,
                    frames -> { },
                    samples -> {
                        for (CanonicalMeasurementSample sample : samples) {
                            pending.add(sample.toCanonicalSignal(InMemorySignalCatalogStore.DIRECT_MEASUREMENT_MESSAGE));
                            if (pending.size() >= flushSize) {
                                flushDirect(pending, preview, writing, statistics, previewBatchListener);
                            }
                        }
                    },
                    warning -> statistics.recordWarning()
            );
            if (!pending.isEmpty()) {
                flushDirect(pending, preview, catalogStore, statistics, previewBatchListener);
            }
            catalogStore.finishWriteSession();
            if (token.isCancellationRequested()) {
                progress.onStage(ProcessingStage.CANCELLED);
                return failedResult(detectedLabel, measurementPath, noDefinition, started, preview,
                        "Processing cancelled.", "Processing cancelled.", measurement);
            }
            if (statistics.signalsEmitted() == 0) {
                progress.onStage(ProcessingStage.FAILED);
                return failedResult(
                        detectedLabel,
                        measurementPath,
                        noDefinition,
                        started,
                        preview,
                        "Detected " + detectedLabel + ", but timestamp semantics could not be determined.",
                        "No measurement signals were produced.",
                        measurement
                );
            }
            ProjectProcessingStatus status = ProjectProcessingStatus.COMPLETED;
            progress.onStage(ProcessingStage.COMPLETED);
            String message = "Direct processing completed.";
            handOffStore = true;
            return new GatewayResult(
                    directSummary(detectedLabel, measurementPath, noDefinition, statistics, started, status, message),
                    preview,
                    null,
                    null,
                    catalogStore
            );
        } catch (ParserException e) {
            String tech = e.getMessage() == null ? e.toString() : e.getMessage();
            progress.onStage(token.isCancellationRequested() ? ProcessingStage.CANCELLED : ProcessingStage.FAILED);
            return failedResult(
                    format,
                    measurementPath,
                    noDefinition,
                    started,
                    preview,
                    token.isCancellationRequested() ? "Processing cancelled." : tech,
                    tech,
                    measurement
            );
        } catch (RuntimeException e) {
            String tech = e.getMessage() == null ? e.toString() : e.getMessage();
            progress.onStage(ProcessingStage.FAILED);
            return failedResult(format, measurementPath, noDefinition, started, preview,
                    "Unexpected processing error.", tech, measurement);
        } finally {
            if (!handOffStore) {
                discardDirectStore(catalogStore);
            }
            activeToken.compareAndSet(token, null);
        }
    }

    /** Drops an uncommitted direct store. Persistent roots are not deleted by {@code close()}. */
    private static void discardDirectStore(GraphSignalSessionStore store) {
        if (store == null) {
            return;
        }
        Path root = store.root();
        boolean deleteRetainedRoot = !store.deleteRootOnClose();
        try {
            store.close();
        } catch (RuntimeException ignored) {
            // The caller is already on a failure path.
        }
        if (deleteRetainedRoot) {
            deleteTreeBestEffort(root);
        }
    }

    private static void deleteTreeBestEffort(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup of an uncommitted staging tree.
                }
            });
        } catch (IOException | UncheckedIOException ignored) {
            // Best-effort cleanup of an uncommitted staging tree.
        }
    }

    private static void flushDirect(
            List<CanonicalSignal> pending,
            SignalPreviewBuffer preview,
            GraphSignalSessionStore catalogStore,
            ProcessingStatistics statistics,
            Consumer<List<CanonicalSignal>> previewBatchListener
    ) {
        List<CanonicalSignal> batch = List.copyOf(pending);
        pending.clear();
        preview.accept(batch);
        catalogStore.accept(batch);
        statistics.recordNativeSignals(batch.size());
        if (previewBatchListener != null) {
            previewBatchListener.accept(batch);
        }
    }

    private static ProcessingSummary directSummary(
            String format,
            Path measurementPath,
            Path dbcPath,
            ProcessingStatistics statistics,
            Instant started,
            ProjectProcessingStatus status,
            String userMessage
    ) {
        return new ProcessingSummary(
                format,
                measurementPath,
                dbcPath,
                statistics,
                Duration.between(started, Instant.now()),
                status,
                List.of(),
                userMessage,
                "",
                false,
                "NOT_REQUIRED",
                "",
                "Direct",
                0L
        );
    }

    private static GatewayResult failedResult(
            String format,
            Path measurementPath,
            Path dbcPath,
            Instant started,
            SignalPreviewBuffer preview,
            String userMessage,
            String technical,
            WorkspaceFile measurement
    ) {
        ProcessingStatistics empty = new ProcessingStatistics();
        boolean cancelled = userMessage.toLowerCase().contains("cancel");
        ProjectProcessingStatus status = cancelled
                ? ProjectProcessingStatus.CANCELLED
                : ProjectProcessingStatus.FAILED;
        boolean recoveryUsed = measurement != null && measurement.recoveryCandidate();
        ProcessingSummary summary = new ProcessingSummary(
                format,
                measurementPath,
                dbcPath,
                empty,
                Duration.between(started, Instant.now()),
                status,
                List.of(),
                userMessage,
                technical == null ? "" : technical,
                recoveryUsed,
                recoveryUsed ? "ACTIVE" : "",
                measurement == null ? "" : measurement.mf4FinalizationLabel()
        );
        return new GatewayResult(summary, preview, null, technical, null);
    }

    private static boolean recoveryAttempted(ProcessingResult result) {
        if (result == null) {
            return false;
        }
        if (result.statistics().recoveredFrames() > 0L) {
            return true;
        }
        return result.aggregatedDiagnostics().stream().anyMatch(d -> {
            String code = d.code() == null ? "" : d.code().toUpperCase();
            return code.contains("RECOVERY")
                    || code.contains("RECOVERED")
                    || code.contains("ZERO_DECLARED")
                    || code.contains("NO_VALID_RECORDS");
        });
    }

    /**
     * Final recovery label for the Processing summary.
     * <ul>
     *   <li>{@code NOT_REQUIRED} — recovery never entered, or normal decode already covered the
     *       complete usable CAN region (recoveredFrames=0 with valid frames present)</li>
     *   <li>{@code RECOVERED} / {@code PARTIALLY_RECOVERED} — recovered CanonicalCanFrames</li>
     *   <li>{@code NOT_RECOVERABLE} — recovery was required and failed to produce CAN frames</li>
     *   <li>{@code FAILED} / {@code ACTIVE} — terminal failure / cancel</li>
     * </ul>
     */
    private static String recoveryState(boolean recoveryAttempted, ProcessingResult result) {
        if (!recoveryAttempted) {
            return "NOT_REQUIRED";
        }
        if (result.cancelled()) {
            return "ACTIVE";
        }
        return switch (result.outcome()) {
            case RECOVERY_FAILED, NO_CAN_DATA, UNSUPPORTED_STRUCTURE, FAILED -> "FAILED";
            case COMPLETED_WITH_WARNINGS, COMPLETED -> {
                if (result.statistics().recoveredFrames() > 0L) {
                    boolean partial = result.aggregatedDiagnostics().stream()
                            .anyMatch(d -> d.code() != null
                                    && d.code().toUpperCase().contains("PARTIAL_RECOVERY"));
                    yield partial ? "PARTIALLY_RECOVERED" : "RECOVERED";
                }
                // Exploratory / metadata recovery path ran but normal decoding already produced the
                // full supported CAN dataset — do not brand that as NOT_RECOVERABLE.
                if (result.statistics().validFrames() > 0L) {
                    yield "NOT_REQUIRED";
                }
                yield "NOT_RECOVERABLE";
            }
            default -> "RECOVERED";
        };
    }

    private static ProjectProcessingStatus mapOutcome(com.stellar.platform.processing.ProcessingOutcome outcome) {
        return switch (outcome) {
            case COMPLETED -> ProjectProcessingStatus.COMPLETED;
            case COMPLETED_WITH_WARNINGS -> ProjectProcessingStatus.COMPLETED;
            case NO_CAN_DATA -> ProjectProcessingStatus.NO_CAN_DATA;
            case RECOVERY_FAILED -> ProjectProcessingStatus.RECOVERY_FAILED;
            case UNSUPPORTED_STRUCTURE -> ProjectProcessingStatus.UNSUPPORTED_STRUCTURE;
            case FAILED -> ProjectProcessingStatus.FAILED;
            case CANCELLED -> ProjectProcessingStatus.CANCELLED;
        };
    }

    private static String outcomeMessage(ProcessingResult result) {
        boolean noMatchingIds = result.aggregatedDiagnostics().stream()
                .anyMatch(d -> d.code() != null && d.code().toUpperCase().contains("DBC_NO_MATCHING_CAN_IDS"));
        if (noMatchingIds) {
            return "Processing completed. CAN frames were read, and the selected DBC does not contain "
                    + "definitions for some CAN IDs in this recording.";
        }
        boolean muxInactive = result.aggregatedDiagnostics().stream()
                .anyMatch(d -> d.code() != null && d.code().toUpperCase().contains("MUX_NO_ACTIVE_LEAF"));
        boolean fewLeafSignals = result.statistics().signalsEmitted() > 0
                && result.statistics().dbcMatchedFrames() > 0;
        if (muxInactive && fewLeafSignals) {
            return "Processing completed. CAN ID(s) matched the DBC message, but multiplex metadata did not "
                    + "activate any leaf signals for those payloads — only multiplex selector signal(s) were "
                    + "emitted. Full leaf names remain in the DBC model.";
        }
        return switch (result.outcome()) {
            case COMPLETED -> "Processing completed.";
            case COMPLETED_WITH_WARNINGS -> "Processing completed.";
            case NO_CAN_DATA -> "No CanonicalCanFrame data could be produced from the MF4 file.";
            case RECOVERY_FAILED -> "MF4 recovery failed: physical data present but no valid CAN frames recovered.";
            case UNSUPPORTED_STRUCTURE -> "MF4 contains an unsupported structure that blocked CAN frame decode.";
            case FAILED -> "Processing finished with errors.";
            case CANCELLED -> "Processing cancelled.";
        };
    }

    private static String friendlyParserMessage(ParserException e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            return "unsupported or invalid measurement file";
        }
        if (msg.length() > 160) {
            return msg.substring(0, 160) + "…";
        }
        return msg;
    }

    /**
     * Result envelope for presentation layer.
     */
    public record GatewayResult(
            ProcessingSummary summary,
            SignalPreviewBuffer preview,
            ProcessingResult processingResult,
            String failureTechnicalDetail,
            SignalSeriesStore catalogStore
    ) {
        public GatewayResult {
            Objects.requireNonNull(summary, "summary");
            Objects.requireNonNull(preview, "preview");
        }

        public SignalCatalog catalog() {
            return catalogStore == null ? SignalCatalog.empty() : catalogStore.snapshot();
        }
    }
}
