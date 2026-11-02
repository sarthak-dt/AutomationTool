package com.stellar.platform.parser.mf4.dynamicschema;

import com.stellar.platform.parser.mf4.schema.MdfCnMetadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Evidence-based CAN role classifier. Uses composition leaf names, channel/source type,
 * MIME/bus-event flags and generic naming heuristics — no vendor-specific hard-coding.
 */
public final class MdfCanChannelClassifier {

    private static final Pattern TIME = Pattern.compile(".*(time|timestamp|t_stamp|tstamp).*");
    private static final Pattern CAN_ID = Pattern.compile(".*(can[_\\s-]?id|arb(itration)?[_\\s-]?id|msg[_\\s-]?id|identifier)|^id$");
    private static final Pattern BUS = Pattern.compile(".*(buschannel|bus[_\\s-]?channel|can[_\\s-]?ch)|^bus$|^channel$|^network$");
    private static final Pattern DIR = Pattern.compile("^(dir|direction|rx|tx)$|.*(direction|transmit|receive).*");
    private static final Pattern DLC_CODE = Pattern.compile("^(dlc)$|.*(can[_\\s-]?dlc).*");
    private static final Pattern DATA_LENGTH = Pattern.compile(".*(datalength|data[_\\s-]?length).*");
    private static final Pattern PAYLOAD = Pattern.compile(
            ".*(databytes|data[_\\s-]?bytes|payload|data[_\\s-]?field)|(^|\\.)can[_\\s-]?data(\\.|$)|^data$|^bytes$"
    );
    // EDL/FDF indicate CAN FD; BRS is a separate bit-rate-switch flag (not the FD indication itself).
    private static final Pattern FD = Pattern.compile("^(edl|fdf|canfd)$|.*(can[_\\s-]?fd|fdf|fd[_\\s-]?flag|extended[_\\s-]?data[_\\s-]?length).*");
    private static final Pattern FLAGS = Pattern.compile("^(ide|esi|brs)$|.*(flag|error|bit[_\\s-]?rate[_\\s-]?switch).*");
    private static final Pattern ID_LEAF = Pattern.compile("^(id|canid|can_id|identifier)$");
    private static final Pattern BUS_LEAF = Pattern.compile("^(buschannel|bus|channel)$");
    private static final Pattern DIR_LEAF = Pattern.compile("^(dir|direction|rx|tx)$");
    private static final Pattern DLC_LEAF = Pattern.compile("^(dlc)$");
    private static final Pattern DATA_LEN_LEAF = Pattern.compile("^(datalength|length)$");
    private static final Pattern PAYLOAD_LEAF = Pattern.compile("^(databytes|data|payload|bytes)$");
    private static final Pattern FD_LEAF = Pattern.compile("^(edl|fdf|canfd)$");
    private static final Pattern BRS_LEAF = Pattern.compile("^(brs)$");
    private static final Pattern IDE_LEAF = Pattern.compile("^(ide)$");

    private MdfCanChannelClassifier() {
    }

    public static MdfCanRoleCandidate classify(MdfCnMetadata channel) {
        String name = normalize(channel.name());
        String leaf = leaf(name);
        boolean dotted = name.contains(".");
        String unit = normalize(channel.unit());
        String sourcePath = normalize(channel.sourcePath());
        String sourceName = normalize(channel.sourceName());
        List<Scored> scores = new ArrayList<>();

        // Composite containers themselves are not CAN scalar fields.
        if (channel.hasCompositionChildren() && !channel.compositionMember()) {
            return MdfCanRoleCandidate.none();
        }

        // Prefer leaf tokens for dotted composition names to avoid parent-prefix false positives
        // (e.g. "can_dataframe.buschannel" must not match payload via "can_data...").
        String nameForMatch = dotted ? leaf : name;

        score(scores, MdfCanRole.TIMESTAMP, match(TIME, nameForMatch) || match(TIME, leaf), 0.55, "name~time");
        if (channel.syncType() != 0 || channel.channelType() == 2 || channel.channelType() == 3) {
            score(scores, MdfCanRole.TIMESTAMP, true, 0.35, "sync/master channel type");
        }
        if (unit.contains("s") || unit.contains("sec") || unit.contains("ns")) {
            score(scores, MdfCanRole.TIMESTAMP, true, 0.15, "unit suggests time");
        }

        score(scores, MdfCanRole.CAN_ID, match(CAN_ID, nameForMatch) || match(ID_LEAF, leaf), 0.72, "name/leaf~can id");
        if (channel.bitCount() == 11 || channel.bitCount() == 29) {
            score(scores, MdfCanRole.CAN_ID, match(ID_LEAF, leaf) || match(CAN_ID, nameForMatch), 0.2, "bitLength 11/29");
        }

        score(scores, MdfCanRole.BUS_CHANNEL, match(BUS, nameForMatch) || match(BUS_LEAF, leaf), 0.7, "name/leaf~bus");
        score(scores, MdfCanRole.DIRECTION, match(DIR, nameForMatch) || match(DIR_LEAF, leaf), 0.7, "name/leaf~direction");

        // Prefer actual byte length over 4-bit DLC code when both exist.
        score(scores, MdfCanRole.DLC, match(DATA_LENGTH, nameForMatch) || match(DATA_LEN_LEAF, leaf), 0.8, "name/leaf~data length");
        score(scores, MdfCanRole.DLC, match(DLC_CODE, nameForMatch) || match(DLC_LEAF, leaf), 0.62, "name/leaf~dlc");
        if (channel.bitCount() > 0 && channel.bitCount() <= 4 && match(DLC_LEAF, leaf)) {
            score(scores, MdfCanRole.DLC, true, 0.1, "small bit length dlc code");
        }

        score(scores, MdfCanRole.PAYLOAD, match(PAYLOAD, nameForMatch) || match(PAYLOAD_LEAF, leaf), 0.75, "name/leaf~payload");
        if ((channel.dataType() == 9 || channel.dataType() == 10 || channel.bitCount() >= 64)
                && (match(PAYLOAD_LEAF, leaf) || match(PAYLOAD, nameForMatch))) {
            score(scores, MdfCanRole.PAYLOAD, true, 0.2, "byte/mime payload evidence");
        }

        score(scores, MdfCanRole.CAN_FD_INDICATION, match(FD, nameForMatch) || match(FD_LEAF, leaf), 0.75, "name/leaf~can fd");
        if (match(BRS_LEAF, leaf)) {
            score(scores, MdfCanRole.FLAGS, true, 0.7, "leaf~brs (bit rate switch flag)");
        }
        if (match(IDE_LEAF, leaf) || match(FLAGS, nameForMatch)) {
            score(scores, MdfCanRole.FLAGS, true, 0.55, "name/leaf~flags");
        }

        if (channel.busEventFlag() || channel.compositionMember()) {
            boostExisting(scores, 0.05, "bus-event/composition context");
        }
        if (channel.busType() == 2 || sourcePath.contains("can") || sourceName.contains("can")) {
            boostExisting(scores, 0.05, "SI busType/path suggests CAN");
        }
        if (channel.mimeSampleOrStream() && channel.compositionMember() && match(PAYLOAD_LEAF, leaf)) {
            score(scores, MdfCanRole.PAYLOAD, true, 0.15, "MIME composition member payload");
        }

        if (scores.isEmpty()) {
            return MdfCanRoleCandidate.none();
        }
        scores.sort(Comparator.comparingDouble(Scored::confidence).reversed());
        Scored best = scores.getFirst();
        if (best.confidence() < 0.4) {
            return MdfCanRoleCandidate.none();
        }
        if (scores.size() > 1 && Math.abs(scores.get(0).confidence() - scores.get(1).confidence()) < 0.05
                && scores.get(0).role() != scores.get(1).role()) {
            List<String> evidence = new ArrayList<>(best.evidence());
            evidence.add("ambiguous with " + scores.get(1).role());
            return new MdfCanRoleCandidate(best.role(), Math.min(1.0, best.confidence()), evidence);
        }
        return new MdfCanRoleCandidate(best.role(), Math.min(1.0, best.confidence()), best.evidence());
    }

    private static void boostExisting(List<Scored> scores, double delta, String evidence) {
        for (int i = 0; i < scores.size(); i++) {
            Scored s = scores.get(i);
            List<String> ev = new ArrayList<>(s.evidence());
            ev.add(evidence);
            scores.set(i, new Scored(s.role(), Math.min(1.0, s.confidence() + delta), ev));
        }
    }

    private static void score(
            List<Scored> scores, MdfCanRole role, boolean applies, double weight, String evidence
    ) {
        if (!applies) {
            return;
        }
        for (int i = 0; i < scores.size(); i++) {
            if (scores.get(i).role() == role) {
                Scored existing = scores.get(i);
                List<String> ev = new ArrayList<>(existing.evidence());
                ev.add(evidence);
                scores.set(i, new Scored(role, Math.min(1.0, existing.confidence() + weight), ev));
                return;
            }
        }
        scores.add(new Scored(role, weight, List.of(evidence)));
    }

    private static boolean match(Pattern pattern, String text) {
        return text != null && !text.isBlank() && pattern.matcher(text).matches();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static String leaf(String normalizedName) {
        int dot = normalizedName.lastIndexOf('.');
        return dot >= 0 && dot + 1 < normalizedName.length()
                ? normalizedName.substring(dot + 1)
                : normalizedName;
    }

    private record Scored(MdfCanRole role, double confidence, List<String> evidence) {
    }
}
