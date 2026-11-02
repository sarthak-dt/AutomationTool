package com.stellar.platform.parser.mf4.report;

import java.util.Locale;
import java.util.Map;

/**
 * Human-readable summary formatter for {@link MdfConformanceReport}.
 */
public final class MdfConformanceReportFormatter {

    private MdfConformanceReportFormatter() {
    }

    public static String formatSummary(MdfConformanceReport report) {
        StringBuilder sb = new StringBuilder();
        var id = report.identification();
        sb.append("=== MF4 Conformance Report ===\n");
        sb.append("signature=").append(id.fileSignature())
                .append(" format=").append(id.formatIdentifier())
                .append(" version=").append(id.mdfVersion())
                .append('\n');
        sb.append("finalization=").append(id.finalizationStatus())
                .append(" unfinalizedFlags=").append(id.unfinalizedFlagsPresent())
                .append(" fileSize=").append(id.physicalFileSize())
                .append('\n');
        var meta = report.metadata();
        sb.append("DG=").append(meta.dgCount())
                .append(" CG=").append(meta.cgCount())
                .append(" CN=").append(meta.cnCount())
                .append(" blocks=").append(meta.blockCount())
                .append(" brokenLinks=").append(meta.brokenLinkCount())
                .append('\n');
        for (var dg : report.dataGroups()) {
            sb.append(String.format(Locale.ROOT,
                    "DG@%d recordIdSize=%d CGs=%d root=%s@%d chain=%s%n",
                    dg.dataGroupAddress(), dg.recordIdSize(), dg.channelGroupCount(),
                    dg.rootBlockType(), dg.dataSourceRoot(), dg.dataSourceChainSummary()));
            for (var seg : dg.segments()) {
                sb.append(String.format(Locale.ROOT,
                        "  SEG %s@%d payload=[%d,%d) declared=%d effective=%d recoveryDerived=%s%n",
                        seg.blockType(), seg.sourceBlockAddress(),
                        seg.physicalPayloadStart(), seg.physicalPayloadEnd(),
                        seg.declaredReadableBytes(), seg.effectiveReadableBytes(),
                        seg.recoveryDerived()));
            }
            for (var schema : dg.schemas()) {
                sb.append(String.format(Locale.ROOT,
                        "  CG@%d recordId=%d cycles=%d len=%d vlsd=%s status=%s kind=%s%n",
                        schema.channelGroupAddress(), schema.recordId(), schema.cycleCount(),
                        schema.completeRecordLength(), schema.vlsd(), schema.schemaStatus(),
                        schema.classification()));
            }
        }
        var walk = report.recordWalk();
        sb.append(String.format(Locale.ROOT,
                "WALK attempted=%d decoded=%d rejected=%d unknownId=%d truncated=%d resync=%d bytesConsumed=%d unconsumed=%d%n",
                walk.recordsAttempted(), walk.recordsDecoded(), walk.recordsRejected(),
                walk.unknownRecordIds(), walk.truncatedRecords(), walk.resyncAttempts(),
                walk.bytesConsumed(), walk.bytesLeftUnconsumed()));
        var can = report.canResult();
        sb.append(String.format(Locale.ROOT,
                "CAN frames=%d uniqueIds=%d std=%d ext=%d minTs=%s maxTs=%s%n",
                can.totalCanonicalCanFrames(), can.uniqueCanIds(),
                can.standardFrames(), can.extendedFrames(),
                can.minTimestampNanos(), can.maxTimestampNanos()));
        if (!can.dlcHistogram().isEmpty()) {
            sb.append("DLC ");
            for (Map.Entry<Integer, Long> e : can.dlcHistogram().entrySet()) {
                sb.append("dlc").append(e.getKey()).append('=').append(e.getValue()).append(' ');
            }
            sb.append('\n');
        }
        for (String note : report.stageNotes()) {
            sb.append("NOTE: ").append(note).append('\n');
        }
        return sb.toString();
    }
}
