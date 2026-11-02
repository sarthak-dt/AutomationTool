package com.stellar.platform.testkit.compat;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.decode.DecodingResult;
import com.stellar.platform.dbc.decode.SignalBitExtractor;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexCondition;
import com.stellar.platform.dbc.model.DbcMultiplexGraph;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.domain.FrameQuality;
import com.stellar.platform.domain.catalog.SignalCatalog;
import com.stellar.platform.domain.catalog.SignalCatalogEntry;
import com.stellar.platform.domain.catalog.SignalCatalogKey;
import com.stellar.platform.domain.catalog.SignalSourceType;
import com.stellar.platform.parser.mf4.Mf4Inspector;
import com.stellar.platform.parser.mf4.analysis.MdfConsistencyAnalyser;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.mf4.dynamicschema.MdfBusKind;
import com.stellar.platform.parser.mf4.dynamicschema.MdfCanRole;
import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelFieldSchema;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDynamicSchemaBuilder;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDynamicSchemaResult;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;
import com.stellar.platform.parser.mf4.dynamicschema.MdfSchemaStatus;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraph;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraphDiscovery;
import com.stellar.platform.parser.mf4.id.MdfIdentification;
import com.stellar.platform.parser.mf4.id.MdfIdentificationReader;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.report.MdfInspectionReport;
import com.stellar.platform.parser.mf4.schema.MdfCgMetadata;
import com.stellar.platform.parser.mf4.schema.MdfCnMetadata;
import com.stellar.platform.parser.mf4.schema.MdfDgMetadata;
import com.stellar.platform.parser.mf4.schema.MdfMetadataSummary;
import com.stellar.platform.parser.mf4.schema.MdfSchemaExtractor;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ReaderContext;
import com.stellar.platform.processing.catalog.InMemorySignalCatalogStore;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orchestrates production MF4/DBC APIs and writes bounded compatibility reports.
 * Does not modify decode behaviour.
 */
public final class CompatibilityHarnessRunner {

    private CompatibilityHarnessRunner() {
    }

    public static void run(HarnessOptions options) throws Exception {
        Objects.requireNonNull(options, "options");
        Path out = options.outputDir();
        Files.createDirectories(out);

        if (!Files.isRegularFile(options.mf4Path())) {
            throw new IllegalArgumentException("MF4 not found: " + options.mf4Path());
        }
        if (!Files.isRegularFile(options.dbcPath())) {
            throw new IllegalArgumentException("DBC not found: " + options.dbcPath());
        }

        long mf4Size = Files.size(options.mf4Path());
        long dbcSize = Files.size(options.dbcPath());

        MdfInspectionReport inspection = new Mf4Inspector().inspect(options.mf4Path());
        DbcDatabase database = new DbcParser().parse(options.dbcPath());

        MdfMetadataSummary metadata;
        MdfDynamicSchemaResult schemaResult;
        try (MdfFileAccess access = MdfFileAccess.open(options.mf4Path())) {
            MdfIdentification id = MdfIdentificationReader.read(access);
            MdfBlockGraph graph = MdfBlockGraphDiscovery.discover(access, CancellationToken.NONE);
            metadata = MdfSchemaExtractor.extract(access, graph, CancellationToken.NONE);
            MdfConsistencyAnalyser.AnalysisResult analysis =
                    MdfConsistencyAnalyser.analyse(id, graph, metadata);
            schemaResult = new MdfDynamicSchemaBuilder().build(graph, metadata, analysis);
        }

        writeMf4Metadata(out, metadata, schemaResult);
        writeRuntimeSchemas(out, schemaResult);
        writeDbcInventory(out, database);
        writeMultiplexGraph(out, database);

        StreamStats stats = streamPipeline(options, database, schemaResult);

        writeCanFrameSamples(out, stats);
        writeMatchSummary(out, database, stats);
        writeMuxTrace(out, database, stats.matchedFrameSamples);
        writeSignalSamples(out, stats.signalSamples);
        writeCatalog(out, stats.catalog.snapshot());
        writeDuplicates(out, stats, options.exactDuplicateAnalysis());
        writeNativeChannels(out, metadata, schemaResult, stats);
        DecodingParityReport.write(out, options.mf4Path(), options.dbcPath(),
                Math.min(50, options.matchedFrameSamples()), Math.min(500, options.signalSamples() * 5));
        writeSummary(out, options, mf4Size, dbcSize, inspection, metadata, schemaResult, database, stats);
    }

    private static StreamStats streamPipeline(
            HarnessOptions options,
            DbcDatabase database,
            MdfDynamicSchemaResult schemaResult
    ) throws Exception {
        DefaultDbcDecoder decoder = new DefaultDbcDecoder();
        InMemorySignalCatalogStore catalog = new InMemorySignalCatalogStore(
                options.mf4Path().getFileName().toString(),
                options.dbcPath().getFileName().toString(),
                Math.max(options.signalSamples(), 256)
        );

        BoundedSampleBag<CanonicalCanFrame> frameBag =
                new BoundedSampleBag<>(options.frameSamples(), options.matchedFrameSamples());
        BoundedSampleBag<CanonicalSignal> signalBag =
                new BoundedSampleBag<>(options.signalSamples(), options.signalSamples());
        List<CanonicalCanFrame> matchedSamples = new ArrayList<>();

        Map<Long, MatchAgg> byCanId = new LinkedHashMap<>();
        Map<String, Long> frameIdentityCounts = options.exactDuplicateAnalysis()
                ? new HashMap<>() : null;
        Map<String, Long> signalIdentityCounts = options.exactDuplicateAnalysis()
                ? new HashMap<>() : null;

        long[] counters = new long[8];
        // 0 frames, 1 valid, 2 recovered, 3 std, 4 ext, 5 fdGuess, 6 matched, 7 unmatched
        long[] signalCounters = new long[3];
        // 0 dbcSignals, 1 nativeSignals, 2 uniqueNames via set below
        Set<String> uniqueDecodedNames = new LinkedHashSet<>();

        MdfDynamicRecordDecoder mf4Decoder = new MdfDynamicRecordDecoder();
        mf4Decoder.decode(
                options.mf4Path(),
                ReaderContext.ofBatchSize(256),
                batch -> {
                    for (CanonicalCanFrame frame : batch) {
                        counters[0]++;
                        if (frame.quality() == FrameQuality.OK || frame.quality() == FrameQuality.VALID) {
                            counters[1]++;
                        } else if (frame.quality() == FrameQuality.RECOVERED) {
                            counters[2]++;
                        }
                        if (frame.extended()) {
                            counters[4]++;
                        } else {
                            counters[3]++;
                        }
                        if (frame.dlc() > 8 || frame.data().length > 8) {
                            counters[5]++;
                        }

                        MatchAgg agg = byCanId.computeIfAbsent(
                                frameKey(frame.canId(), frame.extended()),
                                k -> new MatchAgg(frame.canId(), frame.extended()));
                        agg.frameCount++;

                        DecodingResult decoded = decoder.decode(frame, database);
                        boolean matched = decoded.matchedMessage() != null
                                && decoded.status() != com.stellar.platform.dbc.decode.DecodeStatus.UNMATCHED;
                        if (matched) {
                            counters[6]++;
                            agg.matched = true;
                            agg.messageName = decoded.matchedMessage().name();
                            if (matchedSamples.size() < options.matchedFrameSamples()) {
                                matchedSamples.add(frame);
                            }
                            frameBag.offer(frame, frame.quality() == FrameQuality.RECOVERED);
                        } else {
                            counters[7]++;
                            frameBag.offer(frame, frame.quality() == FrameQuality.RECOVERED);
                        }

                        if (frameIdentityCounts != null) {
                            String id = frameIdentity(frame);
                            frameIdentityCounts.merge(id, 1L, Long::sum);
                        }

                        if (!decoded.signals().isEmpty()) {
                            catalog.accept(decoded.signals());
                            for (CanonicalSignal signal : decoded.signals()) {
                                signalCounters[0]++;
                                uniqueDecodedNames.add(signal.signalName());
                                signalBag.offer(signal);
                                if (signalIdentityCounts != null) {
                                    signalIdentityCounts.merge(signalIdentity(frame, signal), 1L, Long::sum);
                                }
                            }
                        }
                    }
                },
                batch -> {
                    List<CanonicalSignal> nativeCanonical = batch.stream()
                            .map(s -> s.toCanonicalSignal(InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE))
                            .toList();
                    catalog.accept(nativeCanonical);
                    for (CanonicalSignal signal : nativeCanonical) {
                        signalCounters[1]++;
                        uniqueDecodedNames.add(signal.signalName());
                        signalBag.offer(signal);
                        if (signalIdentityCounts != null) {
                            String id = "NATIVE|" + signal.signalName() + "|"
                                    + signal.sourceReference().sourceByteOffset();
                            signalIdentityCounts.merge(id, 1L, Long::sum);
                        }
                    }
                },
                w -> {
                }
        );

        StreamStats stats = new StreamStats();
        stats.totalFrames = counters[0];
        stats.validFrames = counters[1];
        stats.recoveredFrames = counters[2];
        stats.standardFrames = counters[3];
        stats.extendedFrames = counters[4];
        stats.canFdGuessFrames = counters[5];
        stats.matchedFrames = counters[6];
        stats.unmatchedFrames = counters[7];
        stats.dbcSignalsEmitted = signalCounters[0];
        stats.nativeSignalsEmitted = signalCounters[1];
        stats.uniqueDecodedSignals = uniqueDecodedNames.size();
        stats.frameSamples = frameBag.snapshot();
        stats.signalSamples = signalBag.snapshot();
        stats.matchedFrameSamples = List.copyOf(matchedSamples);
        stats.byCanId = byCanId;
        stats.frameIdentityCounts = frameIdentityCounts;
        stats.signalIdentityCounts = signalIdentityCounts;
        stats.catalog = catalog;
        stats.schemaCount = schemaResult.recordSchemas().size();
        return stats;
    }

    private static void writeSummary(
            Path out,
            HarnessOptions options,
            long mf4Size,
            long dbcSize,
            MdfInspectionReport inspection,
            MdfMetadataSummary metadata,
            MdfDynamicSchemaResult schemaResult,
            DbcDatabase database,
            StreamStats stats
    ) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("INPUT\n");
        sb.append("MF4 file=").append(options.mf4Path().toAbsolutePath()).append('\n');
        sb.append("DBC file=").append(options.dbcPath().toAbsolutePath()).append('\n');
        sb.append("MF4 sizeBytes=").append(mf4Size).append('\n');
        sb.append("DBC sizeBytes=").append(dbcSize).append('\n');
        sb.append('\n');

        sb.append("MF4\n");
        sb.append("MDF version=").append(inspection.mdfVersion()).append('\n');
        sb.append("finalization=").append(inspection.finalizationStatus()).append('\n');
        sb.append("DG=").append(inspection.dgCount())
                .append(" CG=").append(inspection.cgCount())
                .append(" CN=").append(inspection.cnCount()).append('\n');
        sb.append("CA=").append(metadata.channelArrays().size())
                .append(" CC(conversions on CN)=").append(metadata.channels().stream()
                        .filter(c -> c.conversion() != null && !c.conversion().isIdentity()).count())
                .append(" SI(source infos on CN)=").append(metadata.channels().stream()
                        .filter(c -> c.sourceLink() != 0L).count()).append('\n');
        Map<String, Long> blocks = inspection.discoveredBlockCounts();
        sb.append("DT=").append(blocks.getOrDefault("DT", 0L))
                .append(" DZ=").append(blocks.getOrDefault("DZ", 0L))
                .append(" DL=").append(blocks.getOrDefault("DL", 0L))
                .append(" HL=").append(blocks.getOrDefault("HL", 0L))
                .append(" SD=").append(blocks.getOrDefault("SD", 0L)).append('\n');
        long canCg = schemaResult.recordSchemas().stream().filter(s -> s.busKind() == MdfBusKind.CAN).count();
        long nonCan = schemaResult.recordSchemas().stream().filter(s -> s.busKind() != MdfBusKind.CAN).count();
        long nativeCg = schemaResult.recordSchemas().stream()
                .filter(s -> !s.busEventChannelGroup() && s.busKind() != MdfBusKind.CAN).count();
        sb.append("CAN data CGs(runtime)=").append(canCg)
                .append(" non-CAN CGs=").append(nonCan)
                .append(" native measurement CGs(approx)=").append(nativeCg).append('\n');
        sb.append('\n');

        sb.append("RUNTIME\n");
        long usable = schemaResult.usableSchemas().size();
        long recovery = schemaResult.recordSchemas().stream()
                .filter(s -> s.status() == MdfSchemaStatus.RECOVERY_CANDIDATE).count();
        long unusable = schemaResult.recordSchemas().stream()
                .filter(s -> s.status() == MdfSchemaStatus.UNUSABLE).count();
        sb.append("runtime schemas=").append(schemaResult.recordSchemas().size()).append('\n');
        sb.append("usable schemas=").append(usable).append('\n');
        sb.append("recovery candidate schemas=").append(recovery).append('\n');
        sb.append("unsupported/unusable schemas=").append(unusable).append('\n');
        sb.append('\n');

        sb.append("CAN\n");
        sb.append("CanonicalCanFrames=").append(stats.totalFrames).append('\n');
        sb.append("VALID/OK frames=").append(stats.validFrames).append('\n');
        sb.append("RECOVERED frames=").append(stats.recoveredFrames).append('\n');
        sb.append("standard frames=").append(stats.standardFrames).append('\n');
        sb.append("extended frames=").append(stats.extendedFrames).append('\n');
        sb.append("CAN FD heuristic (DLC/payload>8)=").append(stats.canFdGuessFrames).append('\n');
        sb.append('\n');

        long muxor = database.messages().stream().flatMap(m -> m.signals().stream())
                .filter(s -> s.multiplexRole() == DbcMultiplexRole.MULTIPLEXOR).count();
        long muxed = database.messages().stream().flatMap(m -> m.signals().stream())
                .filter(s -> s.multiplexRole() == DbcMultiplexRole.MULTIPLEXED).count();
        long extendedMul = database.messages().stream().flatMap(m -> m.signals().stream())
                .filter(DbcSignal::hasExplicitMultiplexConditions).count();
        long sigCount = database.messages().stream().mapToLong(m -> m.signals().size()).sum();
        sb.append("DBC\n");
        sb.append("messages=").append(database.messages().size()).append('\n');
        sb.append("signals=").append(sigCount).append('\n');
        sb.append("multiplexor signals=").append(muxor).append('\n');
        sb.append("multiplexed signals=").append(muxed).append('\n');
        sb.append("extended multiplex relationships(SG_MUL_VAL_ signals)=").append(extendedMul).append('\n');
        sb.append('\n');

        sb.append("MATCHING\n");
        sb.append("matched frames=").append(stats.matchedFrames).append('\n');
        sb.append("unmatched frames=").append(stats.unmatchedFrames).append('\n');
        sb.append('\n');

        SignalCatalog catalog = stats.catalog.snapshot();
        long nativeEntries = catalog.entries().stream()
                .filter(e -> e.sourceType() == SignalSourceType.MF4_NATIVE).count();
        sb.append("SIGNALS\n");
        sb.append("CanonicalSignals emitted (DBC)=").append(stats.dbcSignalsEmitted).append('\n');
        sb.append("CanonicalSignals emitted (native)=").append(stats.nativeSignalsEmitted).append('\n');
        sb.append("unique decoded signal names=").append(stats.uniqueDecodedSignals).append('\n');
        sb.append("native MF4 catalog entries=").append(nativeEntries).append('\n');
        sb.append("SignalCatalog entries=").append(catalog.entries().size()).append('\n');
        sb.append('\n');

        sb.append("DUPLICATES\n");
        if (stats.frameIdentityCounts == null) {
            sb.append("exact duplicate analysis disabled\n");
        } else {
            long dupFrames = stats.frameIdentityCounts.values().stream().filter(v -> v > 1).count();
            long dupSignals = stats.signalIdentityCounts.values().stream().filter(v -> v > 1).count();
            sb.append("duplicate frame identities=").append(dupFrames).append('\n');
            sb.append("duplicate signal sample identities=").append(dupSignals).append('\n');
        }
        ReportIo.writeText(out, "00-summary.txt", sb.toString());
    }

    private static void writeMf4Metadata(
            Path out,
            MdfMetadataSummary metadata,
            MdfDynamicSchemaResult schemaResult
    ) throws IOException {
        StringBuilder sb = new StringBuilder();
        Map<Long, MdfRecordSchema> schemaByCg = new HashMap<>();
        for (MdfRecordSchema schema : schemaResult.recordSchemas()) {
            schemaByCg.put(schema.channelGroupAddress(), schema);
        }
        for (MdfDgMetadata dg : metadata.dataGroups()) {
            sb.append("DG address=0x").append(Long.toHexString(dg.address())).append('\n');
            List<MdfCgMetadata> cgs = metadata.channelGroups().stream()
                    .filter(cg -> cg.parentDgAddress() == dg.address()).toList();
            for (MdfCgMetadata cg : cgs) {
                MdfRecordSchema schema = schemaByCg.get(cg.address());
                sb.append("  CG address=0x").append(Long.toHexString(cg.address()))
                        .append(" recordId=").append(cg.recordId())
                        .append(" cycleCount=").append(cg.cycleCount())
                        .append(" dataBytes=").append(cg.dataBytesPerRecord())
                        .append(" invalBytes=").append(cg.invalidationBytesPerRecord())
                        .append(" busEvent=").append(cg.busEventChannelGroup())
                        .append(" vlsd=").append(cg.vlsdChannelGroup())
                        .append(" source=").append(cg.sourceName());
                if (schema != null) {
                    sb.append(" runtimeStatus=").append(schema.status())
                            .append(" busKind=").append(schema.busKind())
                            .append(" dataSource=").append(schema.dataSource().rootType());
                }
                sb.append('\n');
                List<MdfCnMetadata> cns = metadata.channels().stream()
                        .filter(cn -> cn.parentCgAddress() == cg.address())
                        .limit(80)
                        .toList();
                for (MdfCnMetadata cn : cns) {
                    sb.append("    CN name=").append(cn.name())
                            .append(" type=").append(cn.channelType())
                            .append(" dataType=").append(cn.dataType())
                            .append(" byteOff=").append(cn.byteOffset())
                            .append(" bitOff=").append(cn.bitOffset())
                            .append(" bitLen=").append(cn.bitCount())
                            .append(" unit=").append(cn.unit())
                            .append(" source=").append(cn.sourceName())
                            .append(" parentCn=0x").append(Long.toHexString(cn.parentChannelAddress()))
                            .append('\n');
                }
                long more = metadata.channels().stream()
                        .filter(cn -> cn.parentCgAddress() == cg.address()).count() - cns.size();
                if (more > 0) {
                    sb.append("    ... ").append(more).append(" additional CNs omitted\n");
                }
            }
        }
        ReportIo.writeText(out, "01-mf4-metadata.txt", sb.toString());
    }

    private static void writeRuntimeSchemas(Path out, MdfDynamicSchemaResult schemaResult) throws IOException {
        try (BufferedWriter w = ReportIo.writer(out, "02-runtime-schemas.csv")) {
            ReportIo.writeln(w, ReportIo.csv(
                    "DG", "CG", "schemaStatus", "recordId", "recordLength", "declaredCycleCount",
                    "dataSource", "busKind", "busEvent", "channelName", "channelRole",
                    "byteOffset", "bitOffset", "bitLength", "dataType", "semanticClassification",
                    "hasTimestamp", "hasCanId", "hasChannel", "hasDirection", "hasDlc",
                    "hasPayload", "hasCanFdIndication"));
            for (MdfRecordSchema schema : schemaResult.recordSchemas()) {
                boolean hasTs = false, hasId = false, hasCh = false, hasDir = false, hasDlc = false,
                        hasPayload = false, hasFd = false;
                for (MdfChannelFieldSchema field : schema.channelFields()) {
                    MdfCanRole role = field.canRoleCandidate().role();
                    hasTs |= role == MdfCanRole.TIMESTAMP;
                    hasId |= role == MdfCanRole.CAN_ID;
                    hasCh |= role == MdfCanRole.BUS_CHANNEL;
                    hasDir |= role == MdfCanRole.DIRECTION;
                    hasDlc |= role == MdfCanRole.DLC;
                    hasPayload |= role == MdfCanRole.PAYLOAD;
                    hasFd |= role == MdfCanRole.CAN_FD_INDICATION;
                }
                if (schema.channelFields().isEmpty()) {
                    ReportIo.writeln(w, ReportIo.csv(
                            hexAddr(schema.dataGroupAddress()), hexAddr(schema.channelGroupAddress()),
                            schema.status(), schema.recordId(), schema.completeRecordLength(),
                            schema.declaredCycleCount(), schema.dataSource().rootType(), schema.busKind(),
                            schema.busEventChannelGroup(), "", "", "", "", "", "", "",
                            hasTs, hasId, hasCh, hasDir, hasDlc, hasPayload, hasFd));
                    continue;
                }
                for (MdfChannelFieldSchema field : schema.channelFields()) {
                    ReportIo.writeln(w, ReportIo.csv(
                            hexAddr(schema.dataGroupAddress()), hexAddr(schema.channelGroupAddress()),
                            schema.status(), schema.recordId(), schema.completeRecordLength(),
                            schema.declaredCycleCount(), schema.dataSource().rootType(), schema.busKind(),
                            schema.busEventChannelGroup(), field.name(), field.canRoleCandidate().role(),
                            field.byteOffset(), field.bitOffset(), field.bitLength(),
                            field.dataTypeKind(), field.canRoleCandidate().role(),
                            hasTs, hasId, hasCh, hasDir, hasDlc, hasPayload, hasFd));
                }
            }
        }
    }

    private static void writeCanFrameSamples(Path out, StreamStats stats) throws IOException {
        try (BufferedWriter w = ReportIo.writer(out, "03-can-frame-samples.csv")) {
            ReportIo.writeln(w, ReportIo.csv(
                    "sequence", "quality", "timestampNanos", "timestampSeconds", "channel",
                    "canId", "extended", "canFdHeuristic", "direction", "dlc", "payloadHex",
                    "sourceLine", "sourceByteOffset"));
            for (CanonicalCanFrame frame : stats.frameSamples) {
                boolean fd = frame.dlc() > 8 || frame.data().length > 8;
                ReportIo.writeln(w, ReportIo.csv(
                        frame.sequenceNumber(), frame.quality(), frame.timestampNanos(),
                        frame.timestampNanos() / 1_000_000_000.0, frame.channel(),
                        "0x" + Long.toHexString(frame.canId()), frame.extended(), fd,
                        frame.direction(), frame.dlc(), ReportIo.hex(frame.data()),
                        frame.source().sourceLineNumber(), frame.source().sourceByteOffset()));
            }
        }
    }

    private static void writeDbcInventory(Path out, DbcDatabase database) throws IOException {
        try (BufferedWriter w = ReportIo.writer(out, "04-dbc-inventory.csv")) {
            ReportIo.writeln(w, ReportIo.csv(
                    "message", "canId", "standardOrExtended", "fullSignalName", "startBit", "bitLength",
                    "byteOrder", "signed", "factor", "offset", "minimum", "maximum", "unit",
                    "multiplexRole", "multiplexCondition"));
            for (DbcMessage message : database.messages()) {
                String idForm = message.extended() ? "extended" : "standard";
                for (DbcSignal signal : message.signals()) {
                    String cond = signal.multiplexConditions().stream()
                            .map(CompatibilityHarnessRunner::formatCondition)
                            .collect(Collectors.joining("; "));
                    if (cond.isBlank() && signal.multiplexValue() != null) {
                        cond = "legacyMuxValue=" + signal.multiplexValue()
                                + (signal.secondaryMultiplexor() ? ";secondaryMultiplexor" : "");
                    }
                    ReportIo.writeln(w, ReportIo.csv(
                            message.name(),
                            "0x" + Long.toHexString(message.numericId()),
                            idForm,
                            signal.name(),
                            signal.startBit(),
                            signal.bitLength(),
                            signal.byteOrder(),
                            signal.valueType(),
                            signal.factor(),
                            signal.offset(),
                            signal.minimum(),
                            signal.maximum(),
                            signal.unit(),
                            signal.multiplexRole(),
                            cond));
                }
            }
        }
    }

    private static void writeMultiplexGraph(Path out, DbcDatabase database) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (DbcMessage message : database.messages()) {
            DbcMultiplexGraph graph = message.multiplexGraph();
            sb.append(graph.renderTree()).append('\n');
            sb.append("muxLevels=").append(graph.muxLevelCount()).append('\n');
            long simple = message.signals().stream()
                    .filter(s -> s.multiplexRole() == DbcMultiplexRole.MULTIPLEXED
                            && !s.hasExplicitMultiplexConditions()).count();
            long extended = message.signals().stream()
                    .filter(DbcSignal::hasExplicitMultiplexConditions).count();
            sb.append("simpleMultiplexSignals=").append(simple).append('\n');
            sb.append("sgMulValSignals=").append(extended).append('\n');
            sb.append("unresolvedOrConflicts=").append(graph.diagnostics()).append('\n');
            sb.append('\n');
        }
        ReportIo.writeText(out, "05-dbc-multiplex-graph.txt", sb.toString());
    }

    private static void writeMatchSummary(Path out, DbcDatabase database, StreamStats stats)
            throws IOException {
        Set<Long> dbcIds = database.messages().stream()
                .map(m -> frameKey(m.numericId(), m.extended()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> mf4Ids = new LinkedHashSet<>(stats.byCanId.keySet());
        Set<Long> intersection = new LinkedHashSet<>(mf4Ids);
        intersection.retainAll(dbcIds);

        try (BufferedWriter w = ReportIo.writer(out, "06-dbc-match-summary.csv")) {
            ReportIo.writeln(w, ReportIo.csv(
                    "canId", "extended", "frameCount", "dbcMessageFound", "dbcMessageName"));
            for (MatchAgg agg : stats.byCanId.values()) {
                ReportIo.writeln(w, ReportIo.csv(
                        "0x" + Long.toHexString(agg.canId),
                        agg.extended,
                        agg.frameCount,
                        agg.matched,
                        agg.messageName == null ? "" : agg.messageName));
            }
            ReportIo.writeln(w, "");
            ReportIo.writeln(w, ReportIo.csv("metric", "value"));
            ReportIo.writeln(w, ReportIo.csv("uniqueMf4CanIds", mf4Ids.size()));
            ReportIo.writeln(w, ReportIo.csv("uniqueDbcCanIds", dbcIds.size()));
            ReportIo.writeln(w, ReportIo.csv("intersection", intersection.size()));
            ReportIo.writeln(w, ReportIo.csv("matchedFrameCount", stats.matchedFrames));
            ReportIo.writeln(w, ReportIo.csv("unmatchedFrameCount", stats.unmatchedFrames));
        }
    }

    private static void writeMuxTrace(Path out, DbcDatabase database, List<CanonicalCanFrame> matched)
            throws IOException {
        DefaultDbcDecoder decoder = new DefaultDbcDecoder();
        try (BufferedWriter w = ReportIo.writer(out, "07-mux-trace.csv")) {
            ReportIo.writeln(w, ReportIo.csv(
                    "frameSequence", "timestampNanos", "canId", "payloadHex",
                    "multiplexorSignal", "multiplexorRaw", "multiplexLevel",
                    "candidateLeafSignal", "requiredValueRange", "active", "reason"));
            for (CanonicalCanFrame frame : matched) {
                DecodingResult result = decoder.decode(frame, database);
                if (result.matchedMessage() == null) {
                    continue;
                }
                DbcMessage message = result.matchedMessage();
                DbcMultiplexGraph graph = message.multiplexGraph();
                Map<String, Long> rawByName = new LinkedHashMap<>();
                Map<String, DbcSignal> byName = message.signals().stream()
                        .collect(Collectors.toMap(DbcSignal::name, s -> s, (a, b) -> a, LinkedHashMap::new));

                List<String> roots = graph.rootMultiplexorNames();
                if (roots.isEmpty()) {
                    ReportIo.writeln(w, ReportIo.csv(
                            frame.sequenceNumber(), frame.timestampNanos(),
                            "0x" + Long.toHexString(frame.canId()), ReportIo.hex(frame.data()),
                            "", "", 0, "", "", true, "no multiplexor"));
                    continue;
                }

                int level = 0;
                List<String> frontier = new ArrayList<>(roots);
                Set<String> visited = new HashSet<>();
                while (!frontier.isEmpty()) {
                    List<String> nextFrontier = new ArrayList<>();
                    for (String muxName : frontier) {
                        if (!visited.add(muxName)) {
                            continue;
                        }
                        DbcSignal mux = byName.get(muxName);
                        if (mux == null) {
                            continue;
                        }
                        if (mux.hasExplicitMultiplexConditions()
                                && !mux.isApplicableForExplicitMultiplex(rawByName)) {
                            ReportIo.writeln(w, ReportIo.csv(
                                    frame.sequenceNumber(), frame.timestampNanos(),
                                    "0x" + Long.toHexString(frame.canId()), ReportIo.hex(frame.data()),
                                    muxName, "", level, "", "", false, "parent gate failed"));
                            continue;
                        }
                        var extract = SignalBitExtractor.extract(
                                frame.data(), mux.startBit(), mux.bitLength(),
                                mux.byteOrder(), mux.valueType());
                        if (!extract.isOk()) {
                            ReportIo.writeln(w, ReportIo.csv(
                                    frame.sequenceNumber(), frame.timestampNanos(),
                                    "0x" + Long.toHexString(frame.canId()), ReportIo.hex(frame.data()),
                                    muxName, "", level, "", "", false, "extract failed"));
                            continue;
                        }
                        long raw = extract.rawValue();
                        rawByName.put(muxName, raw);
                        var node = graph.node(muxName).orElse(null);
                        if (node == null) {
                            continue;
                        }
                        boolean anyChild = false;
                        for (DbcMultiplexGraph.Edge edge : node.outboundEdges()) {
                            anyChild = true;
                            boolean active = edge.matches(raw);
                            String ranges = edge.ranges().stream()
                                    .map(r -> r.minimum() + "-" + r.maximum())
                                    .collect(Collectors.joining(","));
                            var childNode = graph.node(edge.childSignalName()).orElse(null);
                            boolean childIsMux = childNode != null
                                    && (childNode.kind() == DbcMultiplexGraph.NodeKind.MULTIPLEXOR
                                    || childNode.kind() == DbcMultiplexGraph.NodeKind.MULTIPLEXED_MULTIPLEXOR);
                            ReportIo.writeln(w, ReportIo.csv(
                                    frame.sequenceNumber(), frame.timestampNanos(),
                                    "0x" + Long.toHexString(frame.canId()), ReportIo.hex(frame.data()),
                                    muxName, raw, level, edge.childSignalName(), ranges, active,
                                    active ? (childIsMux ? "enter nested mux" : "leaf active")
                                            : "selector outside ranges"));
                            if (active && childIsMux) {
                                nextFrontier.add(edge.childSignalName());
                            }
                        }
                        if (!anyChild) {
                            ReportIo.writeln(w, ReportIo.csv(
                                    frame.sequenceNumber(), frame.timestampNanos(),
                                    "0x" + Long.toHexString(frame.canId()), ReportIo.hex(frame.data()),
                                    muxName, raw, level, "", "", true, "multiplexor has no children"));
                        }
                    }
                    frontier = nextFrontier;
                    level++;
                    if (level > 8) {
                        break;
                    }
                }
            }
        }
    }

    private static void writeSignalSamples(Path out, List<CanonicalSignal> samples) throws IOException {
        try (BufferedWriter w = ReportIo.writer(out, "08-canonical-signal-samples.csv")) {
            ReportIo.writeln(w, ReportIo.csv(
                    "timestampNanos", "message", "canId", "fullSignalName", "rawValue", "physicalValue",
                    "unit", "textValue", "sourceType", "sourceLine", "sourceByteOffset"));
            for (CanonicalSignal signal : samples) {
                SignalSourceType type = InMemorySignalCatalogStore.MF4_MEASUREMENT_MESSAGE
                        .equals(signal.messageName())
                        ? SignalSourceType.MF4_NATIVE
                        : SignalSourceType.DBC_CAN;
                ReportIo.writeln(w, ReportIo.csv(
                        signal.timestampNanos(), signal.messageName(),
                        "0x" + Long.toHexString(signal.canId()), signal.signalName(),
                        signal.rawValue(), signal.physicalValue(), signal.unit(),
                        signal.textualValue() == null ? "" : signal.textualValue(),
                        type,
                        signal.sourceReference().sourceLineNumber(),
                        signal.sourceReference().sourceByteOffset()));
            }
        }
    }

    private static void writeCatalog(Path out, SignalCatalog catalog) throws IOException {
        Map<String, List<SignalCatalogKey>> byName = new LinkedHashMap<>();
        try (BufferedWriter w = ReportIo.writer(out, "09-signal-catalog.csv")) {
            ReportIo.writeln(w, ReportIo.csv(
                    "catalogKey", "fullSignalName", "sourceType", "message", "canId", "unit",
                    "sampleCount", "firstTimestamp", "lastTimestamp", "sourceFile", "sourceDatabase"));
            for (SignalCatalogEntry entry : catalog.entries()) {
                SignalCatalogKey key = entry.key();
                byName.computeIfAbsent(key.signalName(), n -> new ArrayList<>()).add(key);
                String keyText = key.signalName() + "|" + key.sourceType() + "|" + key.messageName()
                        + "|0x" + Long.toHexString(key.canId()) + "|" + key.sourceFile()
                        + "|" + key.sourceDatabase();
                ReportIo.writeln(w, ReportIo.csv(
                        keyText, key.signalName(), key.sourceType(), key.messageName(),
                        "0x" + Long.toHexString(key.canId()), entry.unit(), entry.sampleCount(),
                        entry.firstTimestampNanos(), entry.lastTimestampNanos(),
                        entry.sourceFile(), entry.sourceDatabase()));
            }
            ReportIo.writeln(w, "");
            ReportIo.writeln(w, ReportIo.csv("collisionCheck", "detail"));
            boolean any = false;
            for (Map.Entry<String, List<SignalCatalogKey>> e : byName.entrySet()) {
                if (e.getValue().size() > 1) {
                    any = true;
                    ReportIo.writeln(w, ReportIo.csv(
                            "sameNameDistinctKeys",
                            e.getKey() + " -> " + e.getValue().size() + " keys (OK if metadata differs)"));
                }
            }
            if (!any) {
                ReportIo.writeln(w, ReportIo.csv("sameNameDistinctKeys", "none"));
            }
        }
    }

    private static void writeDuplicates(Path out, StreamStats stats, boolean exact) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("exactDuplicateAnalysis=").append(exact).append('\n');
        sb.append("totalEmittedFrames=").append(stats.totalFrames).append('\n');
        if (!exact || stats.frameIdentityCounts == null) {
            sb.append("Exact duplicate map disabled for this run.\n");
            ReportIo.writeText(out, "10-duplicate-analysis.txt", sb.toString());
            return;
        }
        sb.append("uniqueFrameSourceIdentities=").append(stats.frameIdentityCounts.size()).append('\n');
        List<Map.Entry<String, Long>> frameDups = stats.frameIdentityCounts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(20)
                .toList();
        sb.append("duplicateFrameIdentities=").append(stats.frameIdentityCounts.values().stream()
                .filter(v -> v > 1).count()).append('\n');
        sb.append("first20FrameDuplicates:\n");
        for (Map.Entry<String, Long> e : frameDups) {
            sb.append("  count=").append(e.getValue()).append(" id=").append(e.getKey()).append('\n');
        }
        sb.append("uniqueSignalSampleIdentities=").append(stats.signalIdentityCounts.size()).append('\n');
        sb.append("duplicateSignalSampleIdentities=").append(stats.signalIdentityCounts.values().stream()
                .filter(v -> v > 1).count()).append('\n');
        List<Map.Entry<String, Long>> sigDups = stats.signalIdentityCounts.entrySet().stream()
                .filter(e -> e.getValue() > 1)
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(20)
                .toList();
        sb.append("first20SignalDuplicates:\n");
        for (Map.Entry<String, Long> e : sigDups) {
            sb.append("  count=").append(e.getValue()).append(" id=").append(e.getKey()).append('\n');
        }
        ReportIo.writeText(out, "10-duplicate-analysis.txt", sb.toString());
    }

    private static void writeNativeChannels(
            Path out,
            MdfMetadataSummary metadata,
            MdfDynamicSchemaResult schemaResult,
            StreamStats stats
    ) throws IOException {
        Set<String> decodedNative = stats.catalog.snapshot().entries().stream()
                .filter(e -> e.sourceType() == SignalSourceType.MF4_NATIVE)
                .map(SignalCatalogEntry::signalName)
                .collect(Collectors.toSet());
        Map<Long, MdfRecordSchema> byCg = new HashMap<>();
        for (MdfRecordSchema schema : schemaResult.recordSchemas()) {
            byCg.put(schema.channelGroupAddress(), schema);
        }
        try (BufferedWriter w = ReportIo.writer(out, "11-native-mf4-channels.csv")) {
            ReportIo.writeln(w, ReportIo.csv(
                    "DG", "CG", "CN", "channelName", "unit", "conversionType",
                    "masterOrTimeAssociation", "currentlyDecodedByStellarAI"));
            for (MdfCnMetadata cn : metadata.channels()) {
                MdfRecordSchema schema = byCg.get(cn.parentCgAddress());
                boolean bus = schema != null && (schema.busEventChannelGroup()
                        || schema.busKind() == MdfBusKind.CAN);
                if (bus) {
                    continue;
                }
                boolean timeLike = cn.channelType() == 2 || cn.syncType() == 1
                        || cn.name().toLowerCase(Locale.ROOT).contains("time");
                boolean decoded = decodedNative.contains(cn.name());
                ReportIo.writeln(w, ReportIo.csv(
                        schema == null ? "" : hexAddr(schema.dataGroupAddress()),
                        hexAddr(cn.parentCgAddress()),
                        hexAddr(cn.address()),
                        cn.name(),
                        cn.unit(),
                        cn.conversion() == null ? "" : cn.conversion().type(),
                        timeLike ? "time/master-candidate" : "measurement",
                        decoded));
            }
        }
    }

    private static String formatCondition(DbcMultiplexCondition condition) {
        String ranges = condition.ranges().stream()
                .map(r -> r.minimum() + "-" + r.maximum())
                .collect(Collectors.joining(","));
        return condition.multiplexorSignalName() + " in [" + ranges + "]";
    }

    private static String hexAddr(long address) {
        return "0x" + Long.toHexString(address);
    }

    private static long frameKey(long canId, boolean extended) {
        return extended ? (canId | (1L << 63)) : canId;
    }

    private static String frameIdentity(CanonicalCanFrame frame) {
        return frame.quality() + "|" + frame.source().sourceLineNumber() + "|"
                + frame.source().sourceByteOffset() + "|0x" + Long.toHexString(frame.canId());
    }

    private static String signalIdentity(CanonicalCanFrame frame, CanonicalSignal signal) {
        return frameIdentity(frame) + "|" + signal.signalName();
    }

    private static final class MatchAgg {
        final long canId;
        final boolean extended;
        long frameCount;
        boolean matched;
        String messageName;

        MatchAgg(long canId, boolean extended) {
            this.canId = canId;
            this.extended = extended;
        }
    }

    private static final class StreamStats {
        long totalFrames;
        long validFrames;
        long recoveredFrames;
        long standardFrames;
        long extendedFrames;
        long canFdGuessFrames;
        long matchedFrames;
        long unmatchedFrames;
        long dbcSignalsEmitted;
        long nativeSignalsEmitted;
        int uniqueDecodedSignals;
        int schemaCount;
        List<CanonicalCanFrame> frameSamples = List.of();
        List<CanonicalSignal> signalSamples = List.of();
        List<CanonicalCanFrame> matchedFrameSamples = List.of();
        Map<Long, MatchAgg> byCanId = Map.of();
        Map<String, Long> frameIdentityCounts;
        Map<String, Long> signalIdentityCounts;
        InMemorySignalCatalogStore catalog;
    }
}
