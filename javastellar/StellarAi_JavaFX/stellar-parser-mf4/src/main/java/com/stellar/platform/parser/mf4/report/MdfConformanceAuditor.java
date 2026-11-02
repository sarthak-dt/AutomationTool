package com.stellar.platform.parser.mf4.report;

import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.parser.mf4.analysis.MdfConsistencyAnalyser;
import com.stellar.platform.parser.mf4.analysis.MdfFinalizationStatus;
import com.stellar.platform.parser.mf4.block.MdfBlockType;
import com.stellar.platform.parser.mf4.block.MdfLinkIssueKind;
import com.stellar.platform.parser.mf4.decode.MdfDecodeDiagnostic;
import com.stellar.platform.parser.mf4.decode.MdfDecodeDiagnosticCode;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.mf4.decode.MdfPayloadSegment;
import com.stellar.platform.parser.mf4.decode.MdfSchemaDispatch;
import com.stellar.platform.parser.mf4.decode.MdfStreamingRecordReader;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDataSourceNode;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDataSourceSchema;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDynamicSchemaBuilder;
import com.stellar.platform.parser.mf4.dynamicschema.MdfDynamicSchemaResult;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraph;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraphDiscovery;
import com.stellar.platform.parser.mf4.id.MdfIdentification;
import com.stellar.platform.parser.mf4.id.MdfIdentificationReader;
import com.stellar.platform.parser.mf4.io.MdfFileAccess;
import com.stellar.platform.parser.mf4.recovery.MdfRecoveryDiagnostic;
import com.stellar.platform.parser.mf4.recovery.MdfRecoveryDiagnosticCode;
import com.stellar.platform.parser.mf4.recovery.MdfRecoveryResult;
import com.stellar.platform.parser.mf4.schema.MdfMetadataSummary;
import com.stellar.platform.parser.mf4.schema.MdfSchemaExtractor;
import com.stellar.platform.parser.spi.CancellationToken;
import com.stellar.platform.parser.spi.ParserException;
import com.stellar.platform.parser.spi.ReaderContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Builds an {@link MdfConformanceReport} by tracing the production decode pipeline.
 * Does not alter parsing behaviour; aggregates diagnostics already produced by existing stages.
 */
public final class MdfConformanceAuditor {

    private final MdfDynamicSchemaBuilder schemaBuilder = new MdfDynamicSchemaBuilder();
    private final MdfDynamicRecordDecoder decoder = new MdfDynamicRecordDecoder();

    public MdfConformanceReport audit(Path path) throws ParserException {
        return audit(path, ReaderContext.ofBatchSize(256));
    }

    public MdfConformanceReport audit(Path path, ReaderContext context) throws ParserException {
        Objects.requireNonNull(path, "path");
        ReaderContext ctx = context == null ? ReaderContext.ofBatchSize(256) : context;
        CancellationToken token = ctx.cancellationToken() == null
                ? CancellationToken.NONE
                : ctx.cancellationToken();

        List<String> stageNotes = new ArrayList<>();
        stageNotes.add("Production streaming entry: Mf4FileReader → MdfDynamicRecordDecoder");
        stageNotes.add("Mf4MeasurementFileParser opens descriptors only; frame decode uses Mf4FileReader");
        stageNotes.add("Mf4Inspector is the inspection/validation path (desktop/testkit), not frame streaming");

        try (MdfFileAccess access = MdfFileAccess.open(path)) {
            MdfIdentification id = MdfIdentificationReader.read(access);
            MdfBlockGraph graph = MdfBlockGraphDiscovery.discover(access, token);
            MdfMetadataSummary metadata = MdfSchemaExtractor.extract(access, graph, token);
            MdfConsistencyAnalyser.AnalysisResult analysis =
                    MdfConsistencyAnalyser.analyse(id, graph, metadata);
            MdfDynamicSchemaResult schemaResult = schemaBuilder.build(graph, metadata, analysis);

            MdfFinalizationStatus finalization = analysis.finalization() == null
                    ? MdfFinalizationStatus.UNKNOWN
                    : analysis.finalization().status();

            MdfConformanceReport.IdentificationSection identification =
                    new MdfConformanceReport.IdentificationSection(
                            id.fileSignature() == null ? "" : id.fileSignature(),
                            id.formatIdentifier() == null ? "" : id.formatIdentifier(),
                            id.version(),
                            finalization,
                            id.hasUnfinalizedFlags(),
                            id.physicalFileSize()
                    );

            long brokenLinks = graph.linkIssues().stream()
                    .filter(i -> i.kind() == MdfLinkIssueKind.OUTSIDE_FILE
                            || i.kind() == MdfLinkIssueKind.BROKEN_UNREADABLE
                            || i.kind() == MdfLinkIssueKind.PREMATURE_EOF
                            || i.kind() == MdfLinkIssueKind.IMPOSSIBLE_LENGTH
                            || i.kind() == MdfLinkIssueKind.ARITHMETIC_OVERFLOW)
                    .count();

            MdfConformanceReport.MetadataSection metadataSection = new MdfConformanceReport.MetadataSection(
                    metadata.dataGroups().size(),
                    metadata.channelGroups().size(),
                    metadata.channels().size(),
                    graph.nodes().size(),
                    brokenLinks
            );

            Map<Long, List<MdfRecordSchema>> byDg = new LinkedHashMap<>();
            for (MdfRecordSchema schema : schemaResult.recordSchemas()) {
                byDg.computeIfAbsent(schema.dataGroupAddress(), k -> new ArrayList<>()).add(schema);
            }

            List<MdfConformanceReport.DataGroupSection> dataGroups = new ArrayList<>();
            for (Map.Entry<Long, List<MdfRecordSchema>> entry : byDg.entrySet()) {
                dataGroups.add(buildDataGroupSection(access, entry.getKey(), entry.getValue(), stageNotes));
            }

            MdfDynamicRecordDecoder.DecodeResult decoded = decoder.decodeAll(path, ctx);
            MdfConformanceReport.RecordWalkSection walk = buildWalkSection(decoded);
            MdfConformanceReport.CanResultSection can = buildCanSection(decoded.frames());

            stageNotes.add("Record schemas built via MdfDynamicSchemaBuilder → MdfDataSourceResolver");
            stageNotes.add("Normal path: MdfStreamingRecordReader.streamDataGroup → MdfRecordFieldDecoder");
            stageNotes.add("Recovery path: MdfRecoveryEngine.recoverDataGroup when requiresRecovery(schemas)");
            stageNotes.add("CAN roles: MdfCanFieldResolver.resolve → MdfCanonicalCanFrameFactory.create");

            return new MdfConformanceReport(
                    identification,
                    metadataSection,
                    dataGroups,
                    walk,
                    can,
                    stageNotes
            );
        }
    }

    private static MdfConformanceReport.DataGroupSection buildDataGroupSection(
            MdfFileAccess access,
            long dgAddress,
            List<MdfRecordSchema> schemas,
            List<String> stageNotes
    ) throws ParserException {
        MdfRecordSchema representative = schemas.getFirst();
        MdfDataSourceSchema source = representative.dataSource();
        String chain = source.orderedNodes().stream()
                .map(n -> n.blockType().name() + "@" + n.address())
                .collect(Collectors.joining(" -> "));
        if (chain.isBlank()) {
            chain = "(empty)";
        }

        List<MdfConformanceReport.DataSourceSegmentSection> segments = new ArrayList<>();
        for (MdfDataSourceNode node : source.orderedNodes()) {
            segments.addAll(describeNodeSegments(access, node, stageNotes));
        }

        List<MdfConformanceReport.ChannelGroupSchemaSection> schemaSections = new ArrayList<>();
        for (MdfRecordSchema schema : schemas) {
            schemaSections.add(new MdfConformanceReport.ChannelGroupSchemaSection(
                    schema.channelGroupAddress(),
                    schema.recordId(),
                    schema.declaredCycleCount(),
                    schema.dataBytesPerRecord(),
                    schema.invalidationBytesPerRecord(),
                    schema.completeRecordLength(),
                    schema.vlsdChannelGroup(),
                    schema.status(),
                    MdfSchemaDispatch.classify(schema)
            ));
        }

        return new MdfConformanceReport.DataGroupSection(
                dgAddress,
                representative.recordIdSize(),
                schemas.size(),
                source.dataBlockRootAddress(),
                source.rootType(),
                chain,
                segments,
                schemaSections
        );
    }

    private static List<MdfConformanceReport.DataSourceSegmentSection> describeNodeSegments(
            MdfFileAccess access,
            MdfDataSourceNode node,
            List<String> stageNotes
    ) throws ParserException {
        List<MdfConformanceReport.DataSourceSegmentSection> out = new ArrayList<>();
        if (node.blockType() == MdfBlockType.DL || node.blockType() == MdfBlockType.HL) {
            out.add(new MdfConformanceReport.DataSourceSegmentSection(
                    node.blockType(),
                    node.address(),
                    0L,
                    0L,
                    node.declaredPayloadLength(),
                    node.physicallyReadableLength(),
                    false
            ));
            return out;
        }
        if (node.blockType() == MdfBlockType.DZ) {
            out.add(new MdfConformanceReport.DataSourceSegmentSection(
                    node.blockType(),
                    node.address(),
                    0L,
                    Math.max(0L, node.physicallyReadableLength()),
                    node.declaredPayloadLength(),
                    node.physicallyReadableLength(),
                    false
            ));
            return out;
        }
        if (node.blockType() != MdfBlockType.DT && node.blockType() != MdfBlockType.SD) {
            out.add(new MdfConformanceReport.DataSourceSegmentSection(
                    node.blockType(),
                    node.address(),
                    0L,
                    0L,
                    node.declaredPayloadLength(),
                    node.physicallyReadableLength(),
                    false
            ));
            return out;
        }

        Optional<MdfPayloadSegment> declared = MdfStreamingRecordReader.dtSegment(access, node, false);
        Optional<MdfPayloadSegment> effective = MdfStreamingRecordReader.dtSegment(access, node, true);
        long declaredStart = declared.map(MdfPayloadSegment::offset).orElse(0L);
        long declaredEnd = declared.map(MdfPayloadSegment::endExclusive).orElse(0L);
        long declaredBytes = Math.max(0L, declaredEnd - declaredStart);
        long effStart = effective.map(MdfPayloadSegment::offset).orElse(declaredStart);
        long effEnd = effective.map(MdfPayloadSegment::endExclusive).orElse(declaredEnd);
        long effBytes = Math.max(0L, effEnd - effStart);
        boolean recoveryDerived = effective.isPresent()
                && (declared.isEmpty() || effBytes > declaredBytes || effStart != declaredStart);
        if (recoveryDerived) {
            stageNotes.add("DG data node @" + node.address()
                    + ": effective segment from MdfStreamingRecordReader.dtSegment(includeTrailing=true)"
                    + " end determined by MdfUnfinalizedDtBoundaryResolver (structurally validated next block or EOF)");
        }
        out.add(new MdfConformanceReport.DataSourceSegmentSection(
                node.blockType(),
                node.address(),
                effStart,
                effEnd,
                declaredBytes,
                effBytes,
                recoveryDerived
        ));
        return out;
    }

    private static MdfConformanceReport.RecordWalkSection buildWalkSection(
            MdfDynamicRecordDecoder.DecodeResult decoded
    ) {
        long attempted = 0;
        long rejected = 0;
        long bytesConsumed = 0;
        long bytesSpan = 0;
        long unknown = 0;
        long truncated = 0;
        long resync = 0;

        for (MdfRecoveryResult recovery : decoded.recoveryResults()) {
            attempted += recovery.recordsInspected();
            rejected += recovery.recordsRejected();
            bytesConsumed += recovery.bytesInspected();
            if (recovery.effectiveDataEnd() > recovery.effectiveDataStart()) {
                bytesSpan += recovery.effectiveDataEnd() - recovery.effectiveDataStart();
            }
            for (MdfRecoveryDiagnostic d : recovery.diagnostics()) {
                if (d.code() == MdfRecoveryDiagnosticCode.UNKNOWN_RECORD_ID) {
                    unknown++;
                } else if (d.code() == MdfRecoveryDiagnosticCode.TRUNCATED_FINAL_RECORD) {
                    truncated++;
                } else if (d.code() == MdfRecoveryDiagnosticCode.RESYNC_FAILED
                        || d.reason() != null && d.reason().toLowerCase().contains("resync")) {
                    resync++;
                }
            }
        }

        for (MdfDecodeDiagnostic d : decoded.diagnostics()) {
            if (d.code() == MdfDecodeDiagnosticCode.UNKNOWN_RECORD_ID) {
                unknown++;
            } else if (d.code() == MdfDecodeDiagnosticCode.TRUNCATED_RECORD) {
                truncated++;
            }
        }

        // Frames + native samples are successful decode outcomes on the shared dispatch path.
        long decodedCount = decoded.frames().size() + decoded.nativeSignals().size();
        if (attempted == 0) {
            // Normal (non-recovery) path does not currently expose per-record counters;
            // use emitted frames/samples as a lower bound for records decoded.
            attempted = decodedCount;
        }
        long unconsumed = Math.max(0L, bytesSpan - bytesConsumed);
        if (bytesSpan == 0L && bytesConsumed == 0L) {
            unconsumed = 0L;
        }

        return new MdfConformanceReport.RecordWalkSection(
                attempted,
                decodedCount,
                rejected,
                unknown,
                truncated,
                resync,
                bytesConsumed,
                unconsumed
        );
    }

    private static MdfConformanceReport.CanResultSection buildCanSection(List<CanonicalCanFrame> frames) {
        Set<Long> ids = new HashSet<>();
        long standard = 0;
        long extended = 0;
        Map<Integer, Long> dlc = new TreeMap<>();
        Long minTs = null;
        Long maxTs = null;
        for (CanonicalCanFrame f : frames) {
            ids.add(f.canId());
            if (f.extended()) {
                extended++;
            } else {
                standard++;
            }
            dlc.merge(f.dlc(), 1L, Long::sum);
            long ts = f.timestampNanos();
            if (minTs == null || ts < minTs) {
                minTs = ts;
            }
            if (maxTs == null || ts > maxTs) {
                maxTs = ts;
            }
        }
        return new MdfConformanceReport.CanResultSection(
                frames.size(),
                ids.size(),
                standard,
                extended,
                dlc,
                minTs,
                maxTs
        );
    }
}
