package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalMeasurementSample;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.parser.mf4.analysis.MdfConsistencyAnalyser;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDynamicSchemaBuilder;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDynamicSchemaResult;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraph;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraphDiscovery;
import com.stellar.platform.parser.mf4.id.MdfIdentification;
import com.stellar.platform.parser.mf4.id.MdfIdentificationReader;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.recovery.MdfRecoveryEngine;
import com.stellar.platform.parser.mf4.recovery.MdfRecoveryResult;
import com.stellar.platform.parser.mf4.schema.MdfMetadataSummary;
import com.stellar.platform.parser.mf4.schema.MdfSchemaExtractor;
import com.stellar.platform.parser.spi.CanChannelDiscoveryResult;
import com.stellar.platform.parser.spi.CanFrameConsumer;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ParseCheckpointListener;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.parser.spi.SignalBatchConsumer;
import com.stellar.platform.parser.spi.WarningConsumer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates MF4 → Metadata Discovery → Dynamic Schema Builder →
 * Normal Record Decoder and/or Recovery Engine → {@link CanonicalCanFrame}
 * and native {@link CanonicalMeasurementSample} values.
 * <p>
 * NORMAL and RECOVERED records share {@link #dispatchDecodedRecord}.
 */
public final class MdfDynamicRecordDecoder {

    private final MdfDynamicSchemaBuilder schemaBuilder = new MdfDynamicSchemaBuilder();
    private final MdfRecoveryEngine recoveryEngine = new MdfRecoveryEngine();
    /**
     * One MF4 measurement is decoded at a time. Inventory runs several files in parallel;
     * each recovery pass can hold an inflated DZ block, and overlapping passes exhausted
     * the 1,536 MB heap.
     */
    private static final Semaphore MF4_DECODE_PERMIT = new Semaphore(1, true);
    /**
     * Streaming inventory does not read this list back. Keeping every per-record diagnostic
     * until the file finished held the old generation near the 1,536 MB cap.
     */
    private static final List<MdfDecodeDiagnostic> DISCARDED_DIAGNOSTICS = new AbstractList<>() {
        @Override
        public boolean add(MdfDecodeDiagnostic diagnostic) {
            return true;
        }

        @Override
        public MdfDecodeDiagnostic get(int index) {
            throw new IndexOutOfBoundsException();
        }

        @Override
        public int size() {
            return 0;
        }
    };
    /**
     * One-shot metadata/schema hand-off from early channel discovery to the immediately following
     * streaming decode. This is intentionally not a general file cache: it holds at most one
     * prepared source and is consumed once.
     */
    private final AtomicReference<PreparedPipeline> preparedForNextDecode = new AtomicReference<>();

    public record DecodeResult(
            List<CanonicalCanFrame> frames,
            List<CanonicalMeasurementSample> nativeSignals,
            List<MdfDecodeDiagnostic> diagnostics,
            MdfDynamicSchemaResult schemaResult,
            List<MdfRecoveryResult> recoveryResults,
            long waveformRecordsDecoded
    ) {
        public DecodeResult {
            frames = List.copyOf(frames);
            nativeSignals = List.copyOf(nativeSignals == null ? List.of() : nativeSignals);
            diagnostics = List.copyOf(diagnostics);
            Objects.requireNonNull(schemaResult, "schemaResult");
            recoveryResults = List.copyOf(recoveryResults == null ? List.of() : recoveryResults);
            waveformRecordsDecoded = Math.max(0L, waveformRecordsDecoded);
        }
    }

    /**
     * Metadata-only early discovery: counts CAN bus-event channel groups without streaming records.
     * Channel numeric IDs typically arrive with frames (BusChannel field).
     */
    public CanChannelDiscoveryResult discoverCanChannels(Path path, CancellationToken cancellation)
            throws ParserException {
        Objects.requireNonNull(path, "path");
        try (MdfFileAccess access = MdfFileAccess.open(path)) {
            Pipeline pipeline = prepare(access, cancellation);
            rememberPrepared(path, pipeline);
            int canGroups = 0;
            for (MdfRecordSchema schema : pipeline.schemaResult().recordSchemas()) {
                if (schema.emitsCanonicalCanFrames()) {
                    canGroups++;
                }
            }
            return CanChannelDiscoveryResult.structuresOnly(canGroups);
        }
    }

    public void decode(
            Path path,
            ReaderContext context,
            CanFrameConsumer frameConsumer,
            WarningConsumer warningConsumer
    ) throws ParserException {
        decode(path, context, frameConsumer, SignalBatchConsumer.NOOP, warningConsumer);
    }

    public void decode(
            Path path,
            ReaderContext context,
            CanFrameConsumer frameConsumer,
            SignalBatchConsumer nativeSignalConsumer,
            WarningConsumer warningConsumer
    ) throws ParserException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(frameConsumer, "frameConsumer");
        SignalBatchConsumer signals = nativeSignalConsumer == null
                ? SignalBatchConsumer.NOOP
                : nativeSignalConsumer;
        WarningConsumer warnings = warningConsumer == null ? w -> {
        } : warningConsumer;

        MF4_DECODE_PERMIT.acquireUninterruptibly();
        try (MdfFileAccess access = MdfFileAccess.open(path)) {
            Pipeline pipeline = takePrepared(path);
            if (pipeline == null) {
                pipeline = prepare(access, context.cancellationToken());
            }
            decodePrepared(
                    access,
                    pipeline.schemaResult(),
                    context,
                    frameConsumer,
                    signals,
                    warnings,
                    DISCARDED_DIAGNOSTICS,
                    new ArrayList<>()
            );
        } finally {
            MF4_DECODE_PERMIT.release();
        }
    }

    public DecodeResult decodeAll(Path path, ReaderContext context) throws ParserException {
        Objects.requireNonNull(path, "path");
        ReaderContext ctx = context == null ? ReaderContext.ofBatchSize(256) : context;
        List<CanonicalCanFrame> frames = new ArrayList<>();
        List<CanonicalMeasurementSample> nativeSignals = new ArrayList<>();
        List<MdfDecodeDiagnostic> diagnostics = new ArrayList<>();
        List<MdfRecoveryResult> recoveryResults = new ArrayList<>();

        MF4_DECODE_PERMIT.acquireUninterruptibly();
        try (MdfFileAccess access = MdfFileAccess.open(path)) {
            Pipeline pipeline = takePrepared(path);
            if (pipeline == null) {
                pipeline = prepare(access, ctx.cancellationToken());
            }
            diagnostics.addAll(pipeline.diagnostics());
            long waveformDecoded = decodePrepared(
                    access,
                    pipeline.schemaResult(),
                    ctx,
                    batch -> frames.addAll(batch),
                    batch -> nativeSignals.addAll(batch),
                    w -> {
                    },
                    diagnostics,
                    recoveryResults
            );
            return new DecodeResult(
                    frames,
                    nativeSignals,
                    diagnostics,
                    pipeline.schemaResult(),
                    recoveryResults,
                    waveformDecoded);
        } finally {
            MF4_DECODE_PERMIT.release();
        }
    }

    private void rememberPrepared(Path path, Pipeline pipeline) {
        FileFingerprint fingerprint = fingerprint(path);
        if (fingerprint == null) {
            preparedForNextDecode.set(null);
            return;
        }
        preparedForNextDecode.set(new PreparedPipeline(fingerprint, pipeline));
    }

    private Pipeline takePrepared(Path path) {
        PreparedPipeline candidate = preparedForNextDecode.getAndSet(null);
        if (candidate == null) {
            return null;
        }
        FileFingerprint current = fingerprint(path);
        if (current == null || !current.equals(candidate.fingerprint())) {
            return null;
        }
        return candidate.pipeline();
    }

    private static FileFingerprint fingerprint(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            return new FileFingerprint(
                    normalized,
                    Files.size(normalized),
                    Files.getLastModifiedTime(normalized).toMillis()
            );
        } catch (IOException | RuntimeException ignored) {
            // Fingerprint failure only disables the one-shot optimisation; decode correctness
            // always falls back to a fresh metadata/schema preparation.
            return null;
        }
    }

    private Pipeline prepare(MdfFileAccess access, CancellationToken cancellation) throws ParserException {
        CancellationToken token = cancellation == null ? CancellationToken.NONE : cancellation;
        MdfIdentification id = MdfIdentificationReader.read(access);
        MdfBlockGraph graph = MdfBlockGraphDiscovery.discover(access, token);
        MdfMetadataSummary metadata = MdfSchemaExtractor.extract(access, graph, token);
        MdfConsistencyAnalyser.AnalysisResult analysis = MdfConsistencyAnalyser.analyse(id, graph, metadata);
        MdfDynamicSchemaResult schemaResult = schemaBuilder.build(graph, metadata, analysis);
        return new Pipeline(schemaResult, List.of());
    }

    private long decodePrepared(
            MdfFileAccess access,
            MdfDynamicSchemaResult schemaResult,
            ReaderContext context,
            CanFrameConsumer frameConsumer,
            SignalBatchConsumer nativeSignalConsumer,
            WarningConsumer warnings,
            List<MdfDecodeDiagnostic> sink,
            List<MdfRecoveryResult> recoverySink
    ) throws ParserException {
        Map<Long, List<MdfRecordSchema>> byDg = new LinkedHashMap<>();
        for (MdfRecordSchema schema : schemaResult.recordSchemas()) {
            byDg.computeIfAbsent(schema.dataGroupAddress(), k -> new ArrayList<>()).add(schema);
        }

        List<CanonicalCanFrame> batch = new ArrayList<>(context.batchSize());
        List<CanonicalMeasurementSample> signalBatch = new ArrayList<>(context.batchSize());
        long[] sequence = {0L};
        long[] nativeSequence = {0L};
        List<ParserException> emitFailures = new ArrayList<>(1);
        String fingerprint = Mf4ResumePayload.schemaFingerprint(schemaResult.recordSchemas());
        Optional<Mf4ResumePayload> resumeOpt = Mf4ResumePayload.fromCursor(context.resumeFrom());
        if (context.resumeFrom() != null && resumeOpt.isEmpty()) {
            throw new ParserException("MF4 resume cursor is corrupt or unsupported");
        }
        if (resumeOpt.isPresent()) {
            Mf4ResumePayload resume = resumeOpt.get();
            if (!resume.schemaFingerprint().isBlank() && !resume.schemaFingerprint().equals(fingerprint)) {
                throw new ParserException("MF4 resume cursor schema fingerprint mismatch");
            }
            sequence[0] = resume.framesEmitted();
            nativeSequence[0] = resume.nativeEmitted();
        }
        int[] dgIndex = {0};
        boolean[] batchEmitted = {false};
        long[] recordsConsumed = {resumeOpt.map(Mf4ResumePayload::recordsConsumed).orElse(0L)};
        Long[] lastTs = {resumeOpt.map(Mf4ResumePayload::lastTimestampNanos).orElse(null)};
        MdfStreamingRecordReader.StreamState streamState = new MdfStreamingRecordReader.StreamState();
        int interval = context.effectiveCheckpointInterval();
        long[] lastDgAddress = {resumeOpt.map(Mf4ResumePayload::dgAddress).orElse(0L)};
        Map<MdfRecordSchema, MdfRecordDecodePlan> recoveredPlans = new IdentityHashMap<>();

        for (Map.Entry<Long, List<MdfRecordSchema>> entry : byDg.entrySet()) {
            if (context.cancellationToken().isCancellationRequested()) {
                MdfDecodeDiagnostic cancelled = new MdfDecodeDiagnostic(
                        MdfDecodeDiagnosticCode.CANCELLED,
                        com.stellar.platform.parser.mf4.analysis.MdfIssueSeverity.INFO,
                        "Cancellation requested before/during DG decode",
                        entry.getKey(),
                        null,
                        null,
                        0L
                );
                sink.add(cancelled);
                warnings.onWarning(cancelled.toWarning());
                break;
            }

            int thisDg = dgIndex[0];
            dgIndex[0]++;
            if (resumeOpt.isPresent() && thisDg < resumeOpt.get().dgIndex()) {
                continue;
            }

            lastDgAddress[0] = entry.getKey();
            List<MdfRecordSchema> schemas = entry.getValue();
            List<MdfRecordSchema> normal = schemas.stream()
                    .filter(s -> !MdfRecoveryEngine.requiresRecovery(s))
                    .filter(s -> s.usable() && s.completeRecordLength() > 0)
                    .toList();
            boolean needsRecovery = MdfRecoveryEngine.requiresRecovery(schemas);
            if (needsRecovery && resumeOpt.isPresent() && thisDg == resumeOpt.get().dgIndex()) {
                throw new ParserException(
                        "MF4 mid-file resume is not supported for recovery-candidate data groups");
            }

            // Mixed-DG one-pass merge is deferred: recovery skips normal-owned records by length,
            // then the normal reader scans the same DT again. Correctness over speculative merge.
            if (needsRecovery) {
                MdfRecoveryResult recovery = recoveryEngine.recoverDataGroup(
                        access,
                        entry.getKey(),
                        schemas,
                        context.cancellationToken(),
                        validated -> dispatchDecodedRecord(
                                validated.decoded(),
                                validated.canFields(),
                                recoveredPlans.computeIfAbsent(
                                        validated.decoded().schema(), MdfRecordDecodePlan::compile),
                                true,
                                sequence,
                                nativeSequence,
                                batch,
                                signalBatch,
                                context.batchSize(),
                                frameConsumer,
                                nativeSignalConsumer,
                                warnings,
                                sink,
                                emitFailures,
                                batchEmitted,
                                lastTs
                        ),
                        d -> {
                            sink.add(d.toDecodeDiagnostic());
                            warnings.onWarning(d.toWarning());
                        }
                );
                recoverySink.add(recovery);
                if (!emitFailures.isEmpty()) {
                    throw emitFailures.getFirst();
                }
            }

            List<MdfRecordSchema> streamSchemas = schemas.stream()
                    .filter(s -> s.usable() && s.completeRecordLength() > 0)
                    .toList();
            if (!normal.isEmpty() && !streamSchemas.isEmpty()) {
                try (MdfVlsdEnrichmentContext vlsdContext = MdfVlsdEnrichmentContext.of(schemas)) {
                MdfStreamingRecordReader.StreamResume streamResume = null;
                if (resumeOpt.isPresent() && thisDg == resumeOpt.get().dgIndex()) {
                    Mf4ResumePayload r = resumeOpt.get();
                    streamResume = new MdfStreamingRecordReader.StreamResume(
                            r.segmentIndex(), r.nextByteOffset(), r.remainingCycles());
                }
                MdfStreamingRecordReader.streamDataGroup(
                        access,
                        entry.getKey(),
                        streamSchemas,
                        context.cancellationToken(),
                        (schema, plan, buffer, offset, length, physicalOffset, recordId) -> {
                            if (MdfRecoveryEngine.requiresRecovery(schema)) {
                                return;
                            }
                            MdfDecodedRecord decoded = MdfRecordFieldDecoder.decode(
                                    plan, buffer, offset, length, physicalOffset, recordId);
                            if (plan.hasResolvableVlsd()) {
                                decoded = MdfVlsdResolver.enrich(access, decoded, vlsdContext);
                            }
                            dispatchDecodedRecord(
                                    decoded,
                                    null,
                                    plan,
                                    false,
                                    sequence,
                                    nativeSequence,
                                    batch,
                                    signalBatch,
                                    context.batchSize(),
                                    frameConsumer,
                                    nativeSignalConsumer,
                                    warnings,
                                    sink,
                                    emitFailures,
                                    batchEmitted,
                                    lastTs
                            );
                        },
                        d -> {
                            sink.add(d);
                            warnings.onWarning(d.toWarning());
                        },
                        streamResume,
                        streamState,
                        () -> {
                            recordsConsumed[0]++;
                            if (batchEmitted[0]) {
                                try {
                                    fireCheckpoint(
                                            context, fingerprint, thisDg, entry.getKey(), streamState,
                                            recordsConsumed[0], sequence[0], nativeSequence[0], lastTs[0], interval);
                                } catch (ParserException e) {
                                    emitFailures.add(e);
                                    throw new RuntimeException(e);
                                }
                                batchEmitted[0] = false;
                            }
                        }
                );
                }
                if (!emitFailures.isEmpty()) {
                    throw emitFailures.getFirst();
                }
            }
        }

        if (!batch.isEmpty()) {
            emit(frameConsumer, batch);
            batch.clear();
            batchEmitted[0] = true;
        }
        if (!signalBatch.isEmpty()) {
            emitSignals(nativeSignalConsumer, signalBatch);
            signalBatch.clear();
            batchEmitted[0] = true;
        }
        if (batchEmitted[0]) {
            int lastDg = Math.max(0, dgIndex[0] - 1);
            fireCheckpoint(
                    context, fingerprint, lastDg, lastDgAddress[0],
                    streamState, recordsConsumed[0], sequence[0], nativeSequence[0], lastTs[0], 1);
        }
        return streamState.recordsVisited;
    }

    private static void fireCheckpoint(
            ReaderContext context,
            String fingerprint,
            int dgIndex,
            long dgAddress,
            MdfStreamingRecordReader.StreamState streamState,
            long recordsConsumed,
            long framesEmitted,
            long nativeEmitted,
            Long lastTs,
            int interval
    ) throws ParserException {
        if (context.checkpointListener() == null || context.checkpointListener() == ParseCheckpointListener.NONE) {
            return;
        }
        if (interval > 1 && recordsConsumed > 0 && recordsConsumed % interval != 0) {
            return;
        }
        Mf4ResumePayload payload = new Mf4ResumePayload(
                dgIndex,
                dgAddress,
                streamState.segmentIndex,
                streamState.nextByteOffset,
                recordsConsumed,
                framesEmitted,
                nativeEmitted,
                lastTs,
                fingerprint,
                streamState.remainingCycles
        );
        try {
            context.checkpointListener().onSafeCheckpoint(payload.toCursor(), recordsConsumed, lastTs);
        } catch (ParserException e) {
            throw e;
        } catch (Exception e) {
            throw new ParserException("Parse checkpoint listener failed", e);
        }
    }

    /**
     * Shared NORMAL / RECOVERED schema dispatch.
     *
     * @return {@code true} when a CAN frame or at least one native sample was emitted
     */
    private boolean dispatchDecodedRecord(
            MdfDecodedRecord decoded,
            MdfCanFieldResolver.ResolvedCanFields preResolved,
            MdfRecordDecodePlan plan,
            boolean recovered,
            long[] sequence,
            long[] nativeSequence,
            List<CanonicalCanFrame> batch,
            List<CanonicalMeasurementSample> signalBatch,
            int batchSize,
            CanFrameConsumer frameConsumer,
            SignalBatchConsumer nativeSignalConsumer,
            WarningConsumer warnings,
            List<MdfDecodeDiagnostic> sink,
            List<ParserException> emitFailures,
            boolean[] batchEmitted,
            Long[] lastTs
    ) throws ParserException {
        Objects.requireNonNull(decoded, "decoded");

        for (MdfDecodeDiagnostic d : decoded.diagnostics()) {
            sink.add(d);
            warnings.onWarning(d.toWarning());
        }

        if (plan == null) {
            plan = MdfRecordDecodePlan.compile(decoded.schema());
        }
        MdfSchemaDispatch.Kind kind = plan.kind();
        if (kind == MdfSchemaDispatch.Kind.NATIVE_MEASUREMENT) {
            List<CanonicalMeasurementSample> nativeSignals =
                    MdfNativeMeasurementResolver.resolve(decoded, nativeSequence[0]);
            if (nativeSignals.isEmpty()) {
                return false;
            }
            nativeSequence[0]++;
            signalBatch.addAll(nativeSignals);
            if (lastTs != null && !nativeSignals.isEmpty()) {
                lastTs[0] = nativeSignals.getLast().timestampNanos();
            }
            if (signalBatch.size() >= batchSize) {
                try {
                    emitSignals(nativeSignalConsumer, signalBatch);
                    signalBatch.clear();
                    if (batchEmitted != null) {
                        batchEmitted[0] = true;
                    }
                } catch (ParserException e) {
                    emitFailures.add(e);
                    throw e;
                }
            }
            return true;
        }

        if (kind != MdfSchemaDispatch.Kind.CAN_FRAME) {
            return false;
        }

        MdfCanFieldResolver.ResolvedCanFields resolved = preResolved != null
                ? preResolved
                : MdfCanFieldResolver.resolve(decoded, plan.canPlan());
        for (MdfDecodeDiagnostic d : resolved.diagnostics()) {
            sink.add(d);
            warnings.onWarning(d.toWarning());
        }
        if (!resolved.complete()) {
            return false;
        }
        FrameQuality quality = recovered ? FrameQuality.RECOVERED : FrameQuality.VALID;
        CanonicalCanFrame frame = MdfCanonicalCanFrameFactory.create(
                sequence[0], resolved, decoded.physicalOffset(), quality
        );
        sequence[0]++;
        batch.add(frame);
        if (lastTs != null) {
            lastTs[0] = frame.timestampNanos();
        }
        if (batch.size() >= batchSize) {
            try {
                emit(frameConsumer, batch);
                batch.clear();
                if (batchEmitted != null) {
                    batchEmitted[0] = true;
                }
            } catch (ParserException e) {
                emitFailures.add(e);
                throw e;
            }
        }
        return true;
    }

    private static void emit(CanFrameConsumer frameConsumer, List<CanonicalCanFrame> batch)
            throws ParserException {
        List<CanonicalCanFrame> copy = List.copyOf(batch);
        try {
            frameConsumer.onBatch(copy);
        } catch (ParserException e) {
            throw e;
        } catch (Exception e) {
            throw new ParserException("Frame consumer failed", e);
        }
    }

    private static void emitSignals(SignalBatchConsumer consumer, List<CanonicalMeasurementSample> batch)
            throws ParserException {
        List<CanonicalMeasurementSample> copy = List.copyOf(batch);
        try {
            consumer.onBatch(copy);
        } catch (ParserException e) {
            throw e;
        } catch (Exception e) {
            throw new ParserException("Native measurement consumer failed", e);
        }
    }

    private record Pipeline(MdfDynamicSchemaResult schemaResult, List<MdfDecodeDiagnostic> diagnostics) {
    }

    private record FileFingerprint(Path path, long size, long lastModifiedMillis) {
    }

    private record PreparedPipeline(FileFingerprint fingerprint, Pipeline pipeline) {
    }
}
