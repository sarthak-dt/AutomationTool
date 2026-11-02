package com.stellar.platform.parser.mf4.decode;

import com.stellar.platform.parser.mf4.dynamicschema.MdfCanRole;
import com.stellar.platform.parser.mf4.dynamicschema.MdfCanRoleCandidate;
import com.stellar.platform.parser.mf4.dynamicschema.MdfChannelFieldSchema;
import com.stellar.platform.parser.mf4.dynamicschema.MdfRecordSchema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable CAN-role routing compiled once from schema metadata (same evidence rules as runtime).
 */
public final class MdfCanExtractionPlan {

    static final double MIN_CONFIDENCE = 0.45;

    private final int[] timestampIdx;
    private final int[] canIdIdx;
    private final int[] busIdx;
    private final int[] directionIdx;
    private final int[] dlcIdx;
    private final int[] payloadIdx;
    private final int[] ideIdx;
    private final int[] canFdPreferredIdx;
    private final int[] canFdAllIdx;
    private final int ambiguousRoleMask;

    private MdfCanExtractionPlan(
            int[] timestampIdx,
            int[] canIdIdx,
            int[] busIdx,
            int[] directionIdx,
            int[] dlcIdx,
            int[] payloadIdx,
            int[] ideIdx,
            int[] canFdPreferredIdx,
            int[] canFdAllIdx,
            int ambiguousRoleMask
    ) {
        this.timestampIdx = timestampIdx;
        this.canIdIdx = canIdIdx;
        this.busIdx = busIdx;
        this.directionIdx = directionIdx;
        this.dlcIdx = dlcIdx;
        this.payloadIdx = payloadIdx;
        this.ideIdx = ideIdx;
        this.canFdPreferredIdx = canFdPreferredIdx;
        this.canFdAllIdx = canFdAllIdx;
        this.ambiguousRoleMask = ambiguousRoleMask;
    }

    public static MdfCanExtractionPlan compile(MdfRecordSchema schema) {
        List<MdfChannelFieldSchema> fields = schema.channelFields();
        Map<MdfCanRole, List<Indexed>> byRole = new EnumMap<>(MdfCanRole.class);
        List<Indexed> ide = new ArrayList<>();
        List<Indexed> fdAll = new ArrayList<>();
        List<Indexed> fdPreferred = new ArrayList<>();
        int ambiguousRoleMask = 0;

        for (int i = 0; i < fields.size(); i++) {
            MdfChannelFieldSchema field = fields.get(i);
            MdfCanRoleCandidate candidate = field.canRoleCandidate();
            String leaf = field.leafName().toLowerCase(Locale.ROOT);
            if ("ide".equals(leaf)) {
                ide.add(new Indexed(i, 1.0));
            }
            if (candidate.role() == MdfCanRole.NONE || candidate.confidence() < MIN_CONFIDENCE) {
                continue;
            }
            boolean evidenceAmbiguous = false;
            for (String e : candidate.evidence()) {
                if (e.toLowerCase(Locale.ROOT).contains("ambiguous")) {
                    evidenceAmbiguous = true;
                    break;
                }
            }
            if (evidenceAmbiguous) {
                if (candidate.role() == MdfCanRole.CAN_FD_INDICATION) {
                    Indexed ix = new Indexed(i, candidate.confidence());
                    fdAll.add(ix);
                    if (leaf.equals("edl") || leaf.equals("fdf") || leaf.equals("canfd")) {
                        fdPreferred.add(ix);
                    }
                } else {
                    ambiguousRoleMask |= 1 << candidate.role().ordinal();
                }
                continue;
            }
            Indexed ix = new Indexed(i, candidate.confidence());
            byRole.computeIfAbsent(candidate.role(), r -> new ArrayList<>()).add(ix);
            if (candidate.role() == MdfCanRole.CAN_FD_INDICATION) {
                fdAll.add(ix);
                if (leaf.equals("edl") || leaf.equals("fdf") || leaf.equals("canfd")) {
                    fdPreferred.add(ix);
                }
            }
        }

        return new MdfCanExtractionPlan(
                sorted(byRole.get(MdfCanRole.TIMESTAMP)),
                sorted(byRole.get(MdfCanRole.CAN_ID)),
                sorted(byRole.get(MdfCanRole.BUS_CHANNEL)),
                sorted(byRole.get(MdfCanRole.DIRECTION)),
                sorted(byRole.get(MdfCanRole.DLC)),
                sorted(byRole.get(MdfCanRole.PAYLOAD)),
                toIndexArray(ide),
                toIndexArray(fdPreferred),
                toIndexArray(fdAll),
                ambiguousRoleMask
        );
    }

    public int[] timestampIdx() {
        return timestampIdx;
    }

    public int[] canIdIdx() {
        return canIdIdx;
    }

    public int[] busIdx() {
        return busIdx;
    }

    public int[] directionIdx() {
        return directionIdx;
    }

    public int[] dlcIdx() {
        return dlcIdx;
    }

    public int[] payloadIdx() {
        return payloadIdx;
    }

    public int[] ideIdx() {
        return ideIdx;
    }

    public int[] canFdPreferredIdx() {
        return canFdPreferredIdx;
    }

    public int[] canFdAllIdx() {
        return canFdAllIdx;
    }

    public boolean compileTimeAmbiguous(MdfCanRole role) {
        return role != null && (ambiguousRoleMask & (1 << role.ordinal())) != 0;
    }

    private static int[] sorted(List<Indexed> list) {
        if (list == null || list.isEmpty()) {
            return new int[0];
        }
        list.sort(Comparator.comparingDouble((Indexed i) -> i.confidence).reversed());
        return toIndexArray(list);
    }

    private static int[] toIndexArray(List<Indexed> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i).index;
        }
        return out;
    }

    private record Indexed(int index, double confidence) {
    }
}