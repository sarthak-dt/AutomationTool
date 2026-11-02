package com.stellar.platform.parser.mf4.dynamicschema;

import com.stellar.platform.parser.mf4.analysis.MdfConsistencyAnalyser;
import com.stellar.platform.parser.mf4.analysis.MdfFinalizationAssessment;
import com.stellar.platform.parser.mf4.analysis.MdfIssueSeverity;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraph;
import com.stellar.platform.parser.mf4.io.MdfSafeMath;
import com.stellar.platform.parser.mf4.schema.MdfCgMetadata;
import com.stellar.platform.parser.mf4.schema.MdfCnMetadata;
import com.stellar.platform.parser.mf4.schema.MdfDgMetadata;
import com.stellar.platform.parser.mf4.schema.MdfMetadataSummary;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds runtime {@link MdfRecordSchema} instances from completed metadata discovery.
 * Does not decode records or load DT/DZ payloads.
 */
public final class MdfDynamicSchemaBuilder {

    public MdfDynamicSchemaResult build(
            MdfBlockGraph graph,
            MdfMetadataSummary metadata,
            MdfConsistencyAnalyser.AnalysisResult analysis
    ) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(analysis, "analysis");

        List<MdfSchemaDiagnostic> diagnostics = new ArrayList<>();
        List<MdfRecordSchema> schemas = new ArrayList<>();
        MdfFinalizationAssessment finalization = analysis.finalization();
        boolean physicalDataExists = finalization.physicalDataRegionLength() > 0
                || finalization.recoveryCandidate();

        Map<Long, List<MdfCgMetadata>> cgsByDg = new HashMap<>();
        for (MdfCgMetadata cg : metadata.channelGroups()) {
            cgsByDg.computeIfAbsent(cg.parentDgAddress(), k -> new ArrayList<>()).add(cg);
        }
        Map<Long, List<MdfCnMetadata>> cnsByCg = new HashMap<>();
        for (MdfCnMetadata cn : metadata.channels()) {
            cnsByCg.computeIfAbsent(cn.parentCgAddress(), k -> new ArrayList<>()).add(cn);
        }

        if (metadata.dataGroups().isEmpty()) {
            diagnostics.add(new MdfSchemaDiagnostic(
                    MdfSchemaDiagnosticCode.MISSING_CG_METADATA,
                    MdfIssueSeverity.FATAL,
                    "No Data Groups discovered; cannot build record schemas",
                    null, null, null
            ));
            return new MdfDynamicSchemaResult(List.of(), diagnostics);
        }

        for (MdfDgMetadata dg : metadata.dataGroups()) {
            MdfDataSourceResolver.Resolution sourceResolution =
                    MdfDataSourceResolver.resolve(dg, graph, metadata);
            diagnostics.addAll(sourceResolution.diagnostics());
            MdfDataSourceSchema dataSource = sourceResolution.schema();
            boolean dgPhysical = dataSource.effectiveReadableSize() > 0
                    || (physicalDataExists && dataSource.dataBlockRootAddress() != 0L);

            List<MdfCgMetadata> channelGroups = cgsByDg.getOrDefault(dg.address(), List.of());
            if (channelGroups.isEmpty()) {
                diagnostics.add(new MdfSchemaDiagnostic(
                        MdfSchemaDiagnosticCode.MISSING_CG_METADATA,
                        MdfIssueSeverity.WARNING,
                        "DG has no Channel Groups",
                        dg.address(), null, null
                ));
                continue;
            }

            Set<Long> seenRecordIds = new HashSet<>();
            for (MdfCgMetadata cg : channelGroups) {
                if (!seenRecordIds.add(cg.recordId())) {
                    diagnostics.add(new MdfSchemaDiagnostic(
                            MdfSchemaDiagnosticCode.DUPLICATE_RECORD_ID,
                            MdfIssueSeverity.FATAL,
                            "Duplicate recordId=" + cg.recordId() + " within DG",
                            dg.address(), cg.address(), null
                    ));
                }

                SchemaBuild built = buildForChannelGroup(dg, cg, cnsByCg.getOrDefault(cg.address(), List.of()),
                        dataSource, dgPhysical, finalization);
                diagnostics.addAll(built.diagnostics());
                schemas.add(built.schema());
            }
        }

        return new MdfDynamicSchemaResult(schemas, diagnostics);
    }

    private static SchemaBuild buildForChannelGroup(
            MdfDgMetadata dg,
            MdfCgMetadata cg,
            List<MdfCnMetadata> channels,
            MdfDataSourceSchema dataSource,
            boolean physicalDataExists,
            MdfFinalizationAssessment finalization
    ) {
        List<MdfSchemaDiagnostic> diagnostics = new ArrayList<>();
        long recordIdSize = dg.recordIdSize();
        long dataBytes = cg.dataBytesPerRecord();
        long invalBytes = cg.invalidationBytesPerRecord();
        boolean vlsd = cg.vlsdChannelGroup();
        boolean busEvent = cg.busEventChannelGroup();

        long completeLength;
        boolean lengthOk = true;
        try {
            if (recordIdSize < 0 || dataBytes < 0 || invalBytes < 0) {
                lengthOk = false;
                diagnostics.add(new MdfSchemaDiagnostic(
                        MdfSchemaDiagnosticCode.NEGATIVE_LENGTH,
                        MdfIssueSeverity.FATAL,
                        "Negative length component in record layout",
                        dg.address(), cg.address(), null
                ));
                completeLength = -1L;
            } else if (vlsd) {
                // VLSD records are length-prefixed at read time; fixed complete length is not defined.
                completeLength = recordIdSize;
            } else {
                completeLength = MdfSafeMath.addExact(
                        MdfSafeMath.addExact(recordIdSize, dataBytes, "recordId+data"),
                        invalBytes,
                        "completeRecordLength"
                );
            }
        } catch (ArithmeticException e) {
            lengthOk = false;
            completeLength = -1L;
            diagnostics.add(new MdfSchemaDiagnostic(
                    MdfSchemaDiagnosticCode.ARITHMETIC_OVERFLOW,
                    MdfIssueSeverity.FATAL,
                    "Record length overflow: " + e.getMessage(),
                    dg.address(), cg.address(), null
            ));
        }

        if (lengthOk && !vlsd && completeLength == 0L && physicalDataExists) {
            diagnostics.add(new MdfSchemaDiagnostic(
                    MdfSchemaDiagnosticCode.ZERO_RECORD_LENGTH_WITH_PHYSICAL_DATA,
                    MdfIssueSeverity.RECOVERABLE,
                    "Complete record length is 0 while physical data exists for DG",
                    dg.address(), cg.address(), null
            ));
            lengthOk = false;
        }

        if (lengthOk && completeLength < 0) {
            diagnostics.add(new MdfSchemaDiagnostic(
                    MdfSchemaDiagnosticCode.IMPOSSIBLE_RECORD_SIZE,
                    MdfIssueSeverity.FATAL,
                    "Impossible complete record length",
                    dg.address(), cg.address(), null
            ));
            lengthOk = false;
        }

        if (invalBytes < 0) {
            diagnostics.add(new MdfSchemaDiagnostic(
                    MdfSchemaDiagnosticCode.INVALID_INVALIDATION_REGION,
                    MdfIssueSeverity.FATAL,
                    "Invalidation bytes negative",
                    dg.address(), cg.address(), null
            ));
        }

        List<MdfChannelFieldSchema> fields = new ArrayList<>();
        boolean hasSupportedChannel = false;
        boolean hasBoundaryIssue = false;
        boolean hasCompositionChildren = channels.stream().anyMatch(MdfCnMetadata::compositionMember);

        if (channels.isEmpty() && !vlsd) {
            diagnostics.add(new MdfSchemaDiagnostic(
                    MdfSchemaDiagnosticCode.MISSING_CN_CHAIN,
                    MdfIssueSeverity.WARNING,
                    "CG has no channels",
                    dg.address(), cg.address(), null
            ));
        }
        if (hasCompositionChildren) {
            diagnostics.add(new MdfSchemaDiagnostic(
                    MdfSchemaDiagnosticCode.COMPOSITION_EXPANDED,
                    MdfIssueSeverity.INFO,
                    "Expanded CN composition children into runtime field schemas",
                    dg.address(), cg.address(), null
            ));
        }

        long dataRegionBits = Math.max(0L, dataBytes) * 8L;
        long invalBits = Math.max(0L, invalBytes) * 8L;

        for (MdfCnMetadata cn : channels) {
            boolean compositionContainer = cn.hasCompositionChildren() && !cn.compositionMember();
            // Prefer composition children for decode; keep container only as structural marker.
            if (compositionContainer) {
                fields.add(containerField(cn));
                continue;
            }

            MdfChannelDataTypeKind kind = resolveDataTypeKind(cn);
            boolean supported = kind.supportedForSafeDecode();
            if (!supported) {
                diagnostics.add(new MdfSchemaDiagnostic(
                        MdfSchemaDiagnosticCode.UNSUPPORTED_MDF_DATA_TYPE,
                        MdfIssueSeverity.WARNING,
                        "Unsupported cn_data_type=" + cn.dataType() + " for channel '" + cn.name() + "'",
                        dg.address(), cg.address(), cn.address()
                ));
            } else {
                hasSupportedChannel = true;
            }

            boolean layoutOk = true;
            if (cn.byteOffset() < 0 || cn.bitOffset() < 0 || cn.bitOffset() > 7 || cn.bitCount() <= 0) {
                layoutOk = false;
                diagnostics.add(new MdfSchemaDiagnostic(
                        MdfSchemaDiagnosticCode.CHANNEL_OUTSIDE_RECORD_BOUNDARY,
                        MdfIssueSeverity.FATAL,
                        "Invalid byte/bit offset or bit length for '" + cn.name() + "'",
                        dg.address(), cg.address(), cn.address()
                ));
            } else if (lengthOk && dataBytes >= 0 && !vlsd) {
                try {
                    long startBit = MdfSafeMath.addExact(
                            MdfSafeMath.multiplyExact(cn.byteOffset(), 8L, "byteOffset*8"),
                            cn.bitOffset(),
                            "startBit"
                    );
                    long endBit = MdfSafeMath.addExact(startBit, cn.bitCount(), "endBit");
                    if (endBit > dataRegionBits) {
                        layoutOk = false;
                        hasBoundaryIssue = true;
                        diagnostics.add(new MdfSchemaDiagnostic(
                                MdfSchemaDiagnosticCode.CHANNEL_OUTSIDE_RECORD_BOUNDARY,
                                MdfIssueSeverity.FATAL,
                                "Channel '" + cn.name() + "' bits [" + startBit + "," + endBit
                                        + ") exceed data region bits " + dataRegionBits,
                                dg.address(), cg.address(), cn.address()
                        ));
                    }
                } catch (ArithmeticException e) {
                    layoutOk = false;
                    hasBoundaryIssue = true;
                    diagnostics.add(new MdfSchemaDiagnostic(
                            MdfSchemaDiagnosticCode.ARITHMETIC_OVERFLOW,
                            MdfIssueSeverity.FATAL,
                            "Channel bit-range overflow for '" + cn.name() + "'",
                            dg.address(), cg.address(), cn.address()
                    ));
                }
            }

            if (cn.invalidationBitPosition() > 0 && invalBits > 0
                    && cn.invalidationBitPosition() >= invalBits) {
                diagnostics.add(new MdfSchemaDiagnostic(
                        MdfSchemaDiagnosticCode.INVALID_INVALIDATION_REGION,
                        MdfIssueSeverity.WARNING,
                        "Invalidation bit position " + cn.invalidationBitPosition()
                                + " outside invalidation region (" + invalBits + " bits)",
                        dg.address(), cg.address(), cn.address()
                ));
            }

            MdfCanRoleCandidate role = MdfCanChannelClassifier.classify(cn);
            if (role.role() != MdfCanRole.NONE && role.evidence().stream().anyMatch(e -> e.startsWith("ambiguous"))) {
                diagnostics.add(new MdfSchemaDiagnostic(
                        MdfSchemaDiagnosticCode.AMBIGUOUS_CAN_CHANNEL_CLASSIFICATION,
                        MdfIssueSeverity.INFO,
                        "Ambiguous CAN role for '" + cn.name() + "': " + role.role()
                                + " conf=" + role.confidence(),
                        dg.address(), cg.address(), cn.address()
                ));
            }

            fields.add(new MdfChannelFieldSchema(
                    cn.address(),
                    cn.name(),
                    cn.channelType(),
                    cn.syncType(),
                    cn.dataType(),
                    kind,
                    supported && layoutOk,
                    cn.byteOffset(),
                    cn.bitOffset(),
                    cn.bitCount(),
                    kind.byteOrder(),
                    kind.signedness(),
                    cn.invalidationBitPosition(),
                    cn.flags(),
                    cn.conversionLink(),
                    cn.sourceLink(),
                    cn.dataLink(),
                    cn.unit(),
                    role,
                    cn.parentChannelAddress(),
                    cn.compositionMember(),
                    cn.hasCompositionChildren(),
                    cn.conversion()
            ));
        }

        MdfSchemaStatus status = decideStatus(
                lengthOk,
                hasSupportedChannel,
                hasBoundaryIssue,
                channels.isEmpty(),
                cg.cycleCount(),
                physicalDataExists,
                dataSource.chainHealthy(),
                finalization,
                vlsd
        );

        if (status == MdfSchemaStatus.RECOVERY_CANDIDATE) {
            diagnostics.add(new MdfSchemaDiagnostic(
                    MdfSchemaDiagnosticCode.RECOVERY_CANDIDATE,
                    MdfIssueSeverity.RECOVERABLE,
                    "Structurally usable schema with declaredCycleCount=0 while physical data exists"
                            + " — must NOT be interpreted as zero records",
                    dg.address(), cg.address(), null
            ));
        }

        MdfBusEventClassifier.Classification bus =
                MdfBusEventClassifier.classify(cg, channels);
        if (bus.kind() == MdfBusKind.LIN || bus.kind() == MdfBusKind.ERROR_STATUS) {
            diagnostics.add(new MdfSchemaDiagnostic(
                    MdfSchemaDiagnosticCode.NON_CAN_BUS_GROUP,
                    MdfIssueSeverity.INFO,
                    "Channel Group classified as " + bus.kind() + " (" + bus.evidence()
                            + "); will not emit CanonicalCanFrame",
                    dg.address(), cg.address(), null
            ));
        }

        long safeComplete = completeLength < 0 ? 0L : completeLength;
        MdfRecordSchema schema = new MdfRecordSchema(
                dg.address(),
                cg.address(),
                cg.recordId(),
                dg.recordIdSize(),
                dataBytes,
                invalBytes,
                safeComplete,
                cg.cycleCount(),
                status,
                dataSource,
                fields,
                physicalDataExists,
                vlsd,
                busEvent,
                bus.kind()
        );
        return new SchemaBuild(schema, diagnostics);
    }

    /**
     * MIME sample/stream members with fixed bit geometry in the CG record decode as byte arrays.
     */
    private static MdfChannelDataTypeKind resolveDataTypeKind(MdfCnMetadata cn) {
        MdfChannelDataTypeKind kind = MdfChannelDataTypeKind.fromCode(cn.dataType());
        if ((kind == MdfChannelDataTypeKind.MIME_SAMPLE || kind == MdfChannelDataTypeKind.MIME_STREAM)
                && cn.bitCount() > 0
                && (cn.compositionMember() || cn.busEventFlag())) {
            return MdfChannelDataTypeKind.BYTE_ARRAY;
        }
        return kind;
    }

    private static MdfChannelFieldSchema containerField(MdfCnMetadata cn) {
        MdfChannelDataTypeKind kind = MdfChannelDataTypeKind.fromCode(cn.dataType());
        return new MdfChannelFieldSchema(
                cn.address(),
                cn.name(),
                cn.channelType(),
                cn.syncType(),
                cn.dataType(),
                kind,
                false,
                cn.byteOffset(),
                cn.bitOffset(),
                cn.bitCount(),
                kind.byteOrder(),
                kind.signedness(),
                cn.invalidationBitPosition(),
                cn.flags(),
                cn.conversionLink(),
                cn.sourceLink(),
                cn.dataLink(),
                cn.unit(),
                MdfCanRoleCandidate.none(),
                cn.parentChannelAddress(),
                false,
                true,
                cn.conversion()
        );
    }

    private static MdfSchemaStatus decideStatus(
            boolean lengthOk,
            boolean hasSupportedChannel,
            boolean hasBoundaryIssue,
            boolean noChannels,
            long declaredCycleCount,
            boolean physicalDataExists,
            boolean chainHealthy,
            MdfFinalizationAssessment finalization,
            boolean vlsd
    ) {
        if (vlsd) {
            // VLSD CGs participate in stream positioning; they do not emit CAN frames.
            return MdfSchemaStatus.NORMAL;
        }
        if (!lengthOk && !hasSupportedChannel) {
            return MdfSchemaStatus.UNUSABLE;
        }
        if (!lengthOk || (hasBoundaryIssue && !hasSupportedChannel) || noChannels && !physicalDataExists) {
            if (!lengthOk || noChannels) {
                return MdfSchemaStatus.UNUSABLE;
            }
        }
        if (!lengthOk) {
            return MdfSchemaStatus.UNUSABLE;
        }

        // Mandatory no-silent-zero / unfinalized support:
        // only when cycle count is zero (do not force RECOVERY when cycles are declared).
        if (declaredCycleCount == 0 && physicalDataExists && lengthOk && (hasSupportedChannel || !noChannels)) {
            return MdfSchemaStatus.RECOVERY_CANDIDATE;
        }
        if (finalization.recoveryCandidate()
                && declaredCycleCount == 0
                && physicalDataExists
                && lengthOk) {
            return MdfSchemaStatus.RECOVERY_CANDIDATE;
        }

        if (!chainHealthy || hasBoundaryIssue || !hasSupportedChannel) {
            return hasSupportedChannel || lengthOk ? MdfSchemaStatus.PARTIAL : MdfSchemaStatus.UNUSABLE;
        }
        return MdfSchemaStatus.NORMAL;
    }

    private record SchemaBuild(MdfRecordSchema schema, List<MdfSchemaDiagnostic> diagnostics) {
    }
}
