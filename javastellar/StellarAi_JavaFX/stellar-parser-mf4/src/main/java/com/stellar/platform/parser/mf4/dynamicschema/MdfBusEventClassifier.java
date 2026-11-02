package com.stellar.platform.parser.mf4.dynamicschema;

import com.stellar.platform.parser.mf4.schema.MdfCgMetadata;
import com.stellar.platform.parser.mf4.schema.MdfCnMetadata;

import java.util.List;
import java.util.Locale;

/**
 * Classifies Channel Groups as CAN / LIN / error / other using SI bus type,
 * CG flags, and composition/channel evidence — not file-specific names alone.
 * <p>
 * SI path/name mentioning CAN (e.g. decoded-signal provenance) is insufficient
 * without CAN frame structure; those CGs are {@link MdfBusKind#OTHER} native measurements.
 */
public final class MdfBusEventClassifier {

    /** ASAM SI bus type: CAN. */
    public static final int BUS_CAN = 2;
    /** ASAM SI bus type: LIN. */
    public static final int BUS_LIN = 3;

    private MdfBusEventClassifier() {
    }

    public record Classification(MdfBusKind kind, String evidence) {
    }

    public static Classification classify(MdfCgMetadata cg, List<MdfCnMetadata> channels) {
        if (cg == null) {
            return new Classification(MdfBusKind.UNKNOWN, "null CG");
        }

        StructureHints hints = StructureHints.of(channels);
        int busType = cg.busType();
        String path = normalize(cg.sourcePath());
        String name = normalize(cg.sourceName());
        boolean siSuggestsCan = busType == BUS_CAN
                || path.contains("can")
                || name.equals("can")
                || name.startsWith("can");
        boolean siSuggestsLin = busType == BUS_LIN
                || path.contains("lin")
                || name.equals("lin")
                || name.startsWith("lin");

        if (hints.hasErrorHint && !hints.hasCanFrameStructure) {
            return new Classification(MdfBusKind.ERROR_STATUS, "channel names suggest error/status event");
        }
        if (hints.hasCanFrameStructure && !hints.hasLinComposition) {
            return new Classification(MdfBusKind.CAN, "CAN frame structure present");
        }
        if (hints.hasLinComposition && !hints.hasCanFrameStructure) {
            return new Classification(MdfBusKind.LIN, "LIN frame structure present");
        }
        if (siSuggestsCan && !hints.hasCanFrameStructure) {
            return new Classification(
                    MdfBusKind.OTHER,
                    "SI suggests CAN provenance without CAN frame structure (native/derived measurement)"
            );
        }
        if (siSuggestsLin && !hints.hasLinComposition) {
            return new Classification(
                    MdfBusKind.OTHER,
                    "SI suggests LIN provenance without LIN frame structure"
            );
        }
        if (cg.busEventChannelGroup() && busType < 0 && !hints.hasCanFrameStructure && !hints.hasLinComposition) {
            return new Classification(MdfBusKind.OTHER, "bus-event CG without frame structure");
        }
        if (cg.vlsdChannelGroup()) {
            return new Classification(MdfBusKind.OTHER, "VLSD channel group");
        }
        if (busType > 0) {
            return new Classification(MdfBusKind.OTHER, "SI busType=" + busType);
        }
        return new Classification(MdfBusKind.UNKNOWN, "insufficient bus evidence");
    }

    public static boolean emitsCanonicalCanFrames(MdfBusKind kind) {
        return kind == MdfBusKind.CAN;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static String leaf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot + 1 < name.length() ? name.substring(dot + 1) : name;
    }

    private record StructureHints(
            boolean hasCanFrameStructure,
            boolean hasLinComposition,
            boolean hasErrorHint
    ) {
        static StructureHints of(List<MdfCnMetadata> channels) {
            boolean hasId = false;
            boolean hasPayloadOrDlc = false;
            boolean hasCanContainer = false;
            boolean hasLinComposition = false;
            boolean hasErrorHint = false;
            if (channels != null) {
                for (MdfCnMetadata cn : channels) {
                    String cnName = normalize(cn.name());
                    String leaf = leaf(cnName);
                    if (cnName.contains("can_dataframe")
                            || cnName.contains("can_errorframe")
                            || cnName.contains("can_remoteframe")
                            || cnName.contains("can_dataframe.")
                            || leaf.equals("can_dataframe")
                            || leaf.equals("can_errorframe")
                            || leaf.equals("can_remoteframe")) {
                        hasCanContainer = true;
                    }
                    if (leaf.equals("id") || leaf.equals("ide")) {
                        hasId = true;
                    }
                    if (leaf.equals("databytes") || leaf.equals("dlc") || leaf.equals("datalength")
                            || leaf.equals("edl") || leaf.equals("brs") || leaf.equals("esi")) {
                        hasPayloadOrDlc = true;
                    }
                    // Composition members under a CAN_* container
                    if (cnName.startsWith("can_") || cnName.startsWith("can.")) {
                        hasCanContainer = true;
                    }
                    if (cnName.contains("lin_") || cnName.contains("lin.") || leaf.startsWith("lin")) {
                        hasLinComposition = true;
                    }
                    if (leaf.contains("error") || cnName.contains("error") || leaf.contains("checksumerror")
                            || leaf.contains("syncerror") || leaf.contains("receiveerror")
                            || leaf.contains("transmissionerror")) {
                        hasErrorHint = true;
                    }
                }
            }
            // ID+DataBytes alone is shared by LIN frames; require a CAN container name,
            // or ID+payload without LIN naming.
            boolean hasCanFrameStructure = hasCanContainer
                    || (hasId && hasPayloadOrDlc && !hasLinComposition);
            return new StructureHints(hasCanFrameStructure, hasLinComposition, hasErrorHint);
        }
    }
}
