package com.stellar.platform.parser.mf4.report;

import com.stellar.platform.parser.mf4.analysis.MdfFinalizationStatus;
import com.stellar.platform.parser.mf4.block.MdfBlockType;
import com.stellar.platform.parser.mf4.decode.MdfSchemaDispatch;
import com.stellar.platform.parser.mf4.dynamicschema.MdfSchemaStatus;
import com.stellar.platform.parser.mf4.id.MdfVersion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic MF4 conformance diagnostic report.
 * Pipeline boundary: identification → graph → metadata → data source → schemas →
 * record walking → decoded fields → {@code CanonicalCanFrame}. DBC is out of scope.
 */
public record MdfConformanceReport(
        IdentificationSection identification,
        MetadataSection metadata,
        List<DataGroupSection> dataGroups,
        RecordWalkSection recordWalk,
        CanResultSection canResult,
        List<String> stageNotes
) {

    public MdfConformanceReport {
        Objects.requireNonNull(identification, "identification");
        Objects.requireNonNull(metadata, "metadata");
        dataGroups = List.copyOf(Objects.requireNonNull(dataGroups, "dataGroups"));
        Objects.requireNonNull(recordWalk, "recordWalk");
        Objects.requireNonNull(canResult, "canResult");
        stageNotes = List.copyOf(Objects.requireNonNull(stageNotes, "stageNotes"));
    }

    public record IdentificationSection(
            String fileSignature,
            String formatIdentifier,
            MdfVersion mdfVersion,
            MdfFinalizationStatus finalizationStatus,
            boolean unfinalizedFlagsPresent,
            long physicalFileSize
    ) {
        public IdentificationSection {
            Objects.requireNonNull(fileSignature, "fileSignature");
            Objects.requireNonNull(formatIdentifier, "formatIdentifier");
            Objects.requireNonNull(mdfVersion, "mdfVersion");
            Objects.requireNonNull(finalizationStatus, "finalizationStatus");
        }
    }

    public record MetadataSection(
            long dgCount,
            long cgCount,
            long cnCount,
            long blockCount,
            long brokenLinkCount
    ) {
    }

    public record DataGroupSection(
            long dataGroupAddress,
            int recordIdSize,
            int channelGroupCount,
            long dataSourceRoot,
            MdfBlockType rootBlockType,
            String dataSourceChainSummary,
            List<DataSourceSegmentSection> segments,
            List<ChannelGroupSchemaSection> schemas
    ) {
        public DataGroupSection {
            Objects.requireNonNull(rootBlockType, "rootBlockType");
            Objects.requireNonNull(dataSourceChainSummary, "dataSourceChainSummary");
            segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
            schemas = List.copyOf(Objects.requireNonNull(schemas, "schemas"));
        }
    }

    public record DataSourceSegmentSection(
            MdfBlockType blockType,
            long sourceBlockAddress,
            long physicalPayloadStart,
            long physicalPayloadEnd,
            long declaredReadableBytes,
            long effectiveReadableBytes,
            boolean recoveryDerived
    ) {
        public DataSourceSegmentSection {
            Objects.requireNonNull(blockType, "blockType");
        }
    }

    public record ChannelGroupSchemaSection(
            long channelGroupAddress,
            long recordId,
            long cycleCount,
            long dataBytes,
            long invalidationBytes,
            long completeRecordLength,
            boolean vlsd,
            MdfSchemaStatus schemaStatus,
            MdfSchemaDispatch.Kind classification
    ) {
        public ChannelGroupSchemaSection {
            Objects.requireNonNull(schemaStatus, "schemaStatus");
            Objects.requireNonNull(classification, "classification");
        }
    }

    public record RecordWalkSection(
            long recordsAttempted,
            long recordsDecoded,
            long recordsRejected,
            long unknownRecordIds,
            long truncatedRecords,
            long resyncAttempts,
            long bytesConsumed,
            long bytesLeftUnconsumed
    ) {
    }

    public record CanResultSection(
            long totalCanonicalCanFrames,
            long uniqueCanIds,
            long standardFrames,
            long extendedFrames,
            Map<Integer, Long> dlcHistogram,
            Long minTimestampNanos,
            Long maxTimestampNanos
    ) {
        public CanResultSection {
            dlcHistogram = Map.copyOf(Objects.requireNonNull(dlcHistogram, "dlcHistogram"));
        }
    }

    public Map<String, Object> toSerializableMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("identification", Map.of(
                "fileSignature", identification.fileSignature(),
                "formatIdentifier", identification.formatIdentifier(),
                "mdfVersion", identification.mdfVersion().toString(),
                "finalizationStatus", identification.finalizationStatus().name(),
                "unfinalizedFlagsPresent", identification.unfinalizedFlagsPresent(),
                "physicalFileSize", identification.physicalFileSize()
        ));
        map.put("metadata", Map.of(
                "dgCount", metadata.dgCount(),
                "cgCount", metadata.cgCount(),
                "cnCount", metadata.cnCount(),
                "blockCount", metadata.blockCount(),
                "brokenLinkCount", metadata.brokenLinkCount()
        ));
        map.put("dataGroups", dataGroups);
        map.put("recordWalk", Map.of(
                "recordsAttempted", recordWalk.recordsAttempted(),
                "recordsDecoded", recordWalk.recordsDecoded(),
                "recordsRejected", recordWalk.recordsRejected(),
                "unknownRecordIds", recordWalk.unknownRecordIds(),
                "truncatedRecords", recordWalk.truncatedRecords(),
                "resyncAttempts", recordWalk.resyncAttempts(),
                "bytesConsumed", recordWalk.bytesConsumed(),
                "bytesLeftUnconsumed", recordWalk.bytesLeftUnconsumed()
        ));
        Map<String, Object> canMap = new LinkedHashMap<>();
        canMap.put("totalCanonicalCanFrames", canResult.totalCanonicalCanFrames());
        canMap.put("uniqueCanIds", canResult.uniqueCanIds());
        canMap.put("standardFrames", canResult.standardFrames());
        canMap.put("extendedFrames", canResult.extendedFrames());
        canMap.put("dlcHistogram", canResult.dlcHistogram());
        canMap.put("minTimestampNanos", canResult.minTimestampNanos());
        canMap.put("maxTimestampNanos", canResult.maxTimestampNanos());
        map.put("canResult", canMap);
        map.put("stageNotes", stageNotes);
        return map;
    }
}
