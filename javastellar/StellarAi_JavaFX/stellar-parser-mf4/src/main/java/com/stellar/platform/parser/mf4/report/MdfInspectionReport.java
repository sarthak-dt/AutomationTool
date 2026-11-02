package com.stellar.platform.parser.mf4.report;

import com.stellar.platform.parser.mf4.analysis.MdfConsistencyIssue;
import com.stellar.platform.parser.mf4.analysis.MdfFinalizationStatus;
import com.stellar.platform.parser.mf4.analysis.MdfRecoveryCandidate;
import com.stellar.platform.parser.mf4.block.MdfLinkIssue;
import com.stellar.platform.parser.mf4.id.MdfVersion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JSON-serialisable (via standard records/collections) MF4 inspection report.
 * No third-party JSON dependency is required.
 */
public record MdfInspectionReport(
        String fileName,
        String absolutePath,
        long physicalSize,
        MdfVersion mdfVersion,
        MdfFinalizationStatus finalizationStatus,
        Map<String, Long> discoveredBlockCounts,
        long dgCount,
        long cgCount,
        long cnCount,
        long dtCount,
        long dzCount,
        long dlCount,
        long declaredCycleCountTotal,
        Long possibleRecordCount,
        long physicalDataRegionLength,
        boolean physicalDataDetected,
        boolean recoveryCandidate,
        List<String> unknownBlocks,
        List<MdfLinkIssue> brokenLinks,
        List<MdfConsistencyIssue> consistencyIssues,
        List<MdfRecoveryCandidate> recoveryCandidates,
        List<String> unsupportedStructures,
        List<String> finalizationReasons,
        boolean cancelled
) {

    public MdfInspectionReport {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(absolutePath, "absolutePath");
        Objects.requireNonNull(mdfVersion, "mdfVersion");
        Objects.requireNonNull(finalizationStatus, "finalizationStatus");
        discoveredBlockCounts = Map.copyOf(Objects.requireNonNull(discoveredBlockCounts, "discoveredBlockCounts"));
        unknownBlocks = List.copyOf(Objects.requireNonNull(unknownBlocks, "unknownBlocks"));
        brokenLinks = List.copyOf(Objects.requireNonNull(brokenLinks, "brokenLinks"));
        consistencyIssues = List.copyOf(Objects.requireNonNull(consistencyIssues, "consistencyIssues"));
        recoveryCandidates = List.copyOf(Objects.requireNonNull(recoveryCandidates, "recoveryCandidates"));
        unsupportedStructures = List.copyOf(Objects.requireNonNull(unsupportedStructures, "unsupportedStructures"));
        finalizationReasons = List.copyOf(Objects.requireNonNull(finalizationReasons, "finalizationReasons"));
    }

    /**
     * Stable map snapshot suitable for simple JSON encoders.
     */
    public Map<String, Object> toSerializableMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("fileName", fileName);
        map.put("absolutePath", absolutePath);
        map.put("physicalSize", physicalSize);
        map.put("mdfVersion", mdfVersion.toString());
        map.put("finalizationStatus", finalizationStatus.name());
        map.put("discoveredBlockCounts", discoveredBlockCounts);
        map.put("dgCount", dgCount);
        map.put("cgCount", cgCount);
        map.put("cnCount", cnCount);
        map.put("dtCount", dtCount);
        map.put("dzCount", dzCount);
        map.put("dlCount", dlCount);
        map.put("declaredCycleCountTotal", declaredCycleCountTotal);
        map.put("possibleRecordCount", possibleRecordCount);
        map.put("physicalDataRegionLength", physicalDataRegionLength);
        map.put("physicalDataDetected", physicalDataDetected);
        map.put("recoveryCandidate", recoveryCandidate);
        map.put("unknownBlocks", unknownBlocks);
        map.put("brokenLinks", brokenLinks.stream().map(MdfLinkIssue::message).toList());
        map.put("consistencyIssues", consistencyIssues.stream()
                .map(i -> Map.of("code", i.code(), "severity", i.severity().name(), "message", i.message()))
                .toList());
        map.put("recoveryCandidates", recoveryCandidates.stream()
                .map(r -> Map.of("address", r.address(), "reason", r.reason(), "physicalBytes", r.physicalBytes()))
                .toList());
        map.put("unsupportedStructures", unsupportedStructures);
        map.put("finalizationReasons", finalizationReasons);
        map.put("cancelled", cancelled);
        return map;
    }
}
