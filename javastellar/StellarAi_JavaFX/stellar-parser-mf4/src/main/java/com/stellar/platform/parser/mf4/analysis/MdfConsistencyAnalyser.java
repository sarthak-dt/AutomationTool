package com.stellar.platform.parser.mf4.analysis;

import com.stellar.platform.parser.mf4.block.MdfBlockType;
import com.stellar.platform.parser.mf4.block.MdfLinkIssue;
import com.stellar.platform.parser.mf4.block.MdfLinkIssueKind;
import com.stellar.platform.parser.mf4.graph.MdfBlockGraph;
import com.stellar.platform.parser.mf4.id.MdfIdentification;
import com.stellar.platform.parser.mf4.io.MdfSafeMath;
import com.stellar.platform.parser.mf4.schema.MdfCgMetadata;
import com.stellar.platform.parser.mf4.schema.MdfDataBlockMetadata;
import com.stellar.platform.parser.mf4.schema.MdfDgMetadata;
import com.stellar.platform.parser.mf4.schema.MdfMetadataSummary;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Declared-versus-physical consistency analyser for MF4 metadata graphs.
 */
public final class MdfConsistencyAnalyser {

    private MdfConsistencyAnalyser() {
    }

    public record AnalysisResult(
            List<MdfConsistencyIssue> issues,
            MdfFinalizationAssessment finalization,
            List<MdfRecoveryCandidate> recoveryCandidates
    ) {
    }

    public static AnalysisResult analyse(
            MdfIdentification identification,
            MdfBlockGraph graph,
            MdfMetadataSummary metadata
    ) {
        List<MdfConsistencyIssue> issues = new ArrayList<>();
        List<MdfRecoveryCandidate> recovery = new ArrayList<>();
        List<String> reasons = new ArrayList<>();

        for (MdfLinkIssue linkIssue : graph.linkIssues()) {
            issues.add(toConsistencyIssue(linkIssue));
        }

        long physicalDataLength = 0L;
        for (MdfDataBlockMetadata data : metadata.dataBlocks()) {
            if (data.blockType() == MdfBlockType.DT
                    || data.blockType() == MdfBlockType.DZ
                    || data.blockType() == MdfBlockType.SD) {
                physicalDataLength = safeAdd(physicalDataLength, data.physicallyReadableLength());
            }

            if (data.declaredPayloadLength() == 0L && data.bytesAfterBlockEnd() > 0L
                    && (data.blockType() == MdfBlockType.DT
                    || data.blockType() == MdfBlockType.DZ
                    || data.blockType() == MdfBlockType.SD)) {
                issues.add(issue(
                        "DECLARED_PAYLOAD_ZERO_WITH_PHYSICAL_BYTES",
                        MdfIssueSeverity.RECOVERABLE,
                        "Declared payload length is 0 but " + data.bytesAfterBlockEnd()
                                + " trailing physical byte(s) follow block end at " + data.address(),
                        data.address()
                ));
                recovery.add(new MdfRecoveryCandidate(
                        data.address(),
                        "Physical bytes present while declared payload is zero",
                        data.bytesAfterBlockEnd()
                ));
            }

            if (data.blockType() == MdfBlockType.DZ) {
                if (data.dzOriginalUncompressedLength() != null
                        && data.dzCompressedLength() != null
                        && data.dzCompressedLength() > 0
                        && data.dzOriginalUncompressedLength() == 0) {
                    issues.add(issue(
                            "DZ_INCONSISTENT_LENGTHS",
                            MdfIssueSeverity.WARNING,
                            "DZ compressed length > 0 but original uncompressed length is 0 at " + data.address(),
                            data.address()
                    ));
                }
                if (data.dzCompressedLength() != null
                        && data.declaredPayloadLength() > 24
                        && data.dzCompressedLength() > data.declaredPayloadLength() - 24) {
                    issues.add(issue(
                            "DZ_COMPRESSED_LENGTH_MISMATCH",
                            MdfIssueSeverity.WARNING,
                            "DZ dz_data_length exceeds remaining declared payload at " + data.address(),
                            data.address()
                    ));
                }
            }

            if (data.blockType() == MdfBlockType.DL) {
                if (data.dlCount() != null && data.dlCount() > 0) {
                    long expectedLinks = 1L + data.dlCount(); // next + data links
                    // incomplete if fewer non-zero data links than count (best-effort)
                    long dataLinks = graph.nodeAt(data.address())
                            .map(n -> n.outgoingLinks().stream().skip(1).filter(l -> l != 0L).count())
                            .orElse(0L);
                    if (dataLinks < data.dlCount()) {
                        issues.add(issue(
                                "INCOMPLETE_DL_CHAIN",
                                MdfIssueSeverity.RECOVERABLE,
                                "DL count=" + data.dlCount() + " but only " + dataLinks
                                        + " data links present at " + data.address(),
                                data.address()
                        ));
                    }
                }
            }

            if (data.blockType() == MdfBlockType.HL
                    && (data.hlFirstDlLink() == null || data.hlFirstDlLink() == 0L)) {
                issues.add(issue(
                        "INCOMPLETE_HL_CHAIN",
                        MdfIssueSeverity.WARNING,
                        "HL block has no first DL link at " + data.address(),
                        data.address()
                ));
            }
        }

        long declaredCycles = metadata.declaredCycleCountTotal();
        Long possibleRecords = null;

        for (MdfDgMetadata dg : metadata.dataGroups()) {
            if (dg.dataBlockLink() == 0L) {
                boolean hasCycles = metadata.channelGroups().stream()
                        .filter(cg -> cg.parentDgAddress() == dg.address())
                        .anyMatch(cg -> cg.cycleCount() > 0);
                if (hasCycles) {
                    issues.add(issue(
                            "MISSING_LINKED_DATA_BLOCK",
                            MdfIssueSeverity.FATAL,
                            "DG at " + dg.address() + " declares cycles via CG but has no data block link",
                            dg.address()
                    ));
                }
            } else if (graph.nodeAt(dg.dataBlockLink()).isEmpty()) {
                issues.add(issue(
                        "MISSING_LINKED_DATA_BLOCK",
                        MdfIssueSeverity.FATAL,
                        "DG data link " + dg.dataBlockLink() + " is not present in reachable graph",
                        dg.address()
                ));
            }

            Optional<MdfDataBlockMetadata> dataMeta = metadata.dataBlocks().stream()
                    .filter(d -> d.address() == dg.dataBlockLink())
                    .findFirst();

            List<MdfCgMetadata> cgs = metadata.channelGroups().stream()
                    .filter(cg -> cg.parentDgAddress() == dg.address())
                    .toList();

            for (MdfCgMetadata cg : cgs) {
                long recordSize = cg.totalBytesPerRecord() + dg.recordIdSize();
                if (cg.dataBytesPerRecord() == 0 && cg.invalidationBytesPerRecord() == 0 && cg.cycleCount() > 0) {
                    issues.add(issue(
                            "ZERO_OR_IMPOSSIBLE_RECORD_SIZE",
                            MdfIssueSeverity.WARNING,
                            "CG at " + cg.address() + " has cycle_count=" + cg.cycleCount()
                                    + " but record size fields are zero",
                            cg.address()
                    ));
                }

                if (cg.cycleCount() == 0 && dataMeta.isPresent() && dataMeta.get().physicallyReadableLength() > 0) {
                    issues.add(issue(
                            "CYCLE_COUNT_ZERO_WITH_PHYSICAL_DATA",
                            MdfIssueSeverity.RECOVERABLE,
                            "CG cycle_count=0 at " + cg.address()
                                    + " but linked physical data bytes="
                                    + dataMeta.get().physicallyReadableLength(),
                            cg.address()
                    ));
                    recovery.add(new MdfRecoveryCandidate(
                            dataMeta.get().address(),
                            "Physical data present while CG cycle_count is 0",
                            dataMeta.get().physicallyReadableLength()
                    ));
                    reasons.add("cycle_count=0 with physical data at DG " + dg.address());
                }

                if (recordSize > 0 && dataMeta.isPresent()) {
                    long payload = dataMeta.get().physicallyReadableLength();
                    if (payload > 0 && payload % recordSize != 0 && cg.cycleCount() > 0) {
                        issues.add(issue(
                                "PHYSICAL_DATA_INCOMPATIBLE_WITH_RECORD_SIZE",
                                MdfIssueSeverity.WARNING,
                                "Physical payload " + payload + " is not divisible by record size "
                                        + recordSize + " for CG " + cg.address(),
                                cg.address()
                        ));
                    }
                    if (payload > 0) {
                        long calc = payload / recordSize;
                        possibleRecords = possibleRecords == null ? calc : Math.max(possibleRecords, calc);
                    }
                    if (cg.cycleCount() > 0) {
                        try {
                            long expected = MdfSafeMath.multiplyExact(cg.cycleCount(), recordSize, "expectedPayload");
                            if (payload > 0 && payload < expected) {
                                issues.add(issue(
                                        "PHYSICAL_DATA_SHORTER_THAN_DECLARED_CYCLES",
                                        MdfIssueSeverity.RECOVERABLE,
                                        "Physical payload " + payload + " < expected "
                                                + expected + " for cycle_count at CG " + cg.address(),
                                        cg.address()
                                ));
                            }
                        } catch (ArithmeticException e) {
                            issues.add(issue(
                                    "RECORD_SIZE_OVERFLOW",
                                    MdfIssueSeverity.FATAL,
                                    "Record size * cycle_count overflow at CG " + cg.address(),
                                    cg.address()
                            ));
                        }
                    }
                }
            }
        }

        // Trailing unreachable region (best-effort): max block end vs file size
        long maxEnd = graph.nodes().stream()
                .mapToLong(n -> n.header().blockEnd())
                .max()
                .orElse(MdfIdentification.ID_BLOCK_SIZE);
        if (maxEnd > 0 && maxEnd < identification.physicalFileSize()) {
            long trailing = identification.physicalFileSize() - maxEnd;
            if (trailing >= 24) {
                issues.add(issue(
                        "UNREACHABLE_TRAILING_REGION",
                        MdfIssueSeverity.INFO,
                        "Unreachable trailing region of " + trailing + " bytes after max block end " + maxEnd,
                        maxEnd
                ));
            }
        }

        if (identification.hasUnfinalizedFileSignature()) {
            reasons.add("IDBLOCK file signature is UnFinMF (unfinalized MDF container)");
        }
        if (identification.unfinalizedFlags() != 0 || identification.customUnfinalizedFlags() != 0) {
            reasons.add("ID unfinalized flags set (0x"
                    + Integer.toHexString(identification.unfinalizedFlags()) + ")");
        }

        MdfFinalizationStatus status = classify(identification, issues, reasons, physicalDataLength, declaredCycles);
        boolean recoveryFlag = !recovery.isEmpty()
                || status == MdfFinalizationStatus.UNFINALIZED
                || status == MdfFinalizationStatus.INCONSISTENT;

        if (recoveryFlag && recovery.isEmpty() && physicalDataLength > 0 && declaredCycles == 0) {
            recovery.add(new MdfRecoveryCandidate(
                    0L,
                    "Physical data region present with declared cycle total 0",
                    physicalDataLength
            ));
        }

        if (status == MdfFinalizationStatus.FINALIZED) {
            reasons.add("No fatal/recoverable inconsistencies; declared cycles and structure consistent");
        }

        MdfFinalizationAssessment assessment = new MdfFinalizationAssessment(
                status,
                declaredCycles,
                possibleRecords,
                physicalDataLength,
                recoveryFlag,
                reasons
        );

        return new AnalysisResult(List.copyOf(issues), assessment, List.copyOf(recovery));
    }

    private static MdfFinalizationStatus classify(
            MdfIdentification identification,
            List<MdfConsistencyIssue> issues,
            List<String> reasons,
            long physicalDataLength,
            long declaredCycles
    ) {
        Set<MdfIssueSeverity> severities = EnumSet.noneOf(MdfIssueSeverity.class);
        for (MdfConsistencyIssue issue : issues) {
            severities.add(issue.severity());
        }

        boolean corrupted = severities.contains(MdfIssueSeverity.FATAL)
                || issues.stream().anyMatch(i ->
                i.code().contains("OUTSIDE") || i.code().contains("PREMATURE")
                        || i.code().contains("IMPOSSIBLE") || i.code().contains("OVERFLOW")
                        || "MISSING_LINKED_DATA_BLOCK".equals(i.code()));
        // Map link issue codes
        corrupted = corrupted || issues.stream().anyMatch(i ->
                i.code().startsWith("LINK_") && (i.severity() == MdfIssueSeverity.FATAL));

        if (corrupted) {
            reasons.add("Fatal structural inconsistencies present");
            return MdfFinalizationStatus.CORRUPTED;
        }

        boolean inconsistent = issues.stream().anyMatch(i ->
                i.severity() == MdfIssueSeverity.WARNING
                        && (i.code().contains("INCOMPATIBLE")
                        || i.code().contains("INCONSISTENT")
                        || i.code().contains("MISMATCH")));
        // Use custom helper severity mapping — treat INCONSISTENT_SEVERITY as WARNING with specific codes
        inconsistent = inconsistent || issues.stream().anyMatch(i ->
                "PHYSICAL_DATA_INCOMPATIBLE_WITH_RECORD_SIZE".equals(i.code())
                        || "DZ_INCONSISTENT_LENGTHS".equals(i.code()));

        boolean unfinalizedEvidence = identification.hasUnfinalizedFlags()
                || issues.stream().anyMatch(i ->
                "CYCLE_COUNT_ZERO_WITH_PHYSICAL_DATA".equals(i.code())
                        || "DECLARED_PAYLOAD_ZERO_WITH_PHYSICAL_BYTES".equals(i.code())
                        || "PHYSICAL_DATA_SHORTER_THAN_DECLARED_CYCLES".equals(i.code())
                        || "INCOMPLETE_DL_CHAIN".equals(i.code()));

        if (inconsistent && unfinalizedEvidence) {
            reasons.add("Mixed unfinalized and inconsistent declared-vs-physical evidence");
            return MdfFinalizationStatus.INCONSISTENT;
        }
        if (inconsistent) {
            reasons.add("Declared metadata conflicts with physical layout");
            return MdfFinalizationStatus.INCONSISTENT;
        }
        if (unfinalizedEvidence || (declaredCycles == 0 && physicalDataLength > 0)) {
            if (declaredCycles == 0 && physicalDataLength > 0) {
                reasons.add("Declared cycles=0 but physical data region length=" + physicalDataLength);
            }
            return MdfFinalizationStatus.UNFINALIZED;
        }

        if (graphHasOnlyId(issues) && declaredCycles == 0 && physicalDataLength == 0) {
            return MdfFinalizationStatus.UNKNOWN;
        }

        return MdfFinalizationStatus.FINALIZED;
    }

    private static boolean graphHasOnlyId(List<MdfConsistencyIssue> issues) {
        return issues.isEmpty();
    }

    private static MdfConsistencyIssue toConsistencyIssue(MdfLinkIssue linkIssue) {
        MdfIssueSeverity severity = switch (linkIssue.kind()) {
            case OUTSIDE_FILE, PREMATURE_EOF, IMPOSSIBLE_LENGTH, ARITHMETIC_OVERFLOW, BROKEN_UNREADABLE ->
                    MdfIssueSeverity.FATAL;
            case CYCLIC_REFERENCE -> MdfIssueSeverity.WARNING;
            case DUPLICATE_REFERENCE -> MdfIssueSeverity.INFO;
            case ZERO_LINK -> MdfIssueSeverity.INFO;
        };
        return issue("LINK_" + linkIssue.kind().name(), severity, linkIssue.message(), linkIssue.targetAddress());
    }

    private static MdfConsistencyIssue issue(
            String code, MdfIssueSeverity severity, String message, Long address
    ) {
        return new MdfConsistencyIssue(code, severity, message, address);
    }

    private static long safeAdd(long a, long b) {
        try {
            return MdfSafeMath.addExact(a, b, "physicalDataLength");
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
