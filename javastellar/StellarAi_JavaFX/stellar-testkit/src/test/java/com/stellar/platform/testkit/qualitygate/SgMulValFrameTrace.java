package com.stellar.platform.testkit.qualitygate;

import com.stellar.platform.dbc.decode.DefaultDbcDecoder;
import com.stellar.platform.dbc.decode.DecodingResult;
import com.stellar.platform.dbc.decode.SignalBitExtractor;
import com.stellar.platform.dbc.model.DbcDatabase;
import com.stellar.platform.dbc.model.DbcMessage;
import com.stellar.platform.dbc.model.DbcMultiplexCondition;
import com.stellar.platform.dbc.model.DbcMultiplexRole;
import com.stellar.platform.dbc.model.DbcSignal;
import com.stellar.platform.dbc.parse.DbcParser;
import com.stellar.platform.domain.CanonicalCanFrame;
import com.stellar.platform.domain.CanonicalSignal;
import com.stellar.platform.parser.mf4.decode.MdfDynamicRecordDecoder;
import com.stellar.platform.parser.spi.ReaderContext;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Proof probe: SG_MUL_VAL_ inventory + per-frame multiplex applicability for Demo MF4.
 */
public final class SgMulValFrameTrace {
    public static void main(String[] args) throws Exception {
        var dbcPath = java.nio.file.Path.of(args.length > 0 ? args[0]
                : "C:/Users/ADMIN/Desktop/DriveTech/11_bit_OBD2 2.dbc");
        var mf4 = java.nio.file.Path.of(args.length > 1 ? args[1]
                : "C:/Users/ADMIN/Desktop/DriveTech/Devendra/Prasad Sangpal/Demo_DT_00000985_00000001-63C8CA43.MF4");

        DbcDatabase db = new DbcParser().parse(dbcPath);
        DbcMessage msg = db.messages().iterator().next();
        long totalSig = msg.signals().size();
        long withCond = msg.signals().stream().filter(DbcSignal::hasExplicitMultiplexConditions).count();
        long muxors = msg.signals().stream().filter(s -> s.multiplexRole() == DbcMultiplexRole.MULTIPLEXOR).count();
        long secondaryStyle = msg.signals().stream().filter(DbcSignal::secondaryMultiplexor).count();
        long unresolved = db.diagnostics().stream().filter(d -> d.contains("Unresolved SG_MUL_VAL_")).count();
        long unsupported = db.diagnostics().stream().filter(d -> d.contains("Unsupported extended multiplexing")).count();

        System.out.println("=== DBC MULTIPLEX INVENTORY ===");
        System.out.println("totalSignals=" + totalSig);
        System.out.println("multiplexerSignals(M)=" + muxors);
        System.out.println("mNM_secondaryStyle=" + secondaryStyle);
        System.out.println("signalsWithSgMulValConditions=" + withCond);
        System.out.println("unresolvedSgMulVal=" + unresolved);
        System.out.println("unsupportedSgMulValDiagnostics=" + unsupported);
        System.out.println("softDiagnostics=" + db.diagnostics().size());

        var decodedMf4 = new MdfDynamicRecordDecoder().decodeAll(mf4, ReaderContext.ofBatchSize(256));
        List<CanonicalCanFrame> matched = decodedMf4.frames().stream()
                .filter(f -> f.canId() == msg.numericId() && f.extended() == msg.extended())
                .toList();
        System.out.println("=== MF4 MATCH ===");
        System.out.println("framesProcessed=" + decodedMf4.frames().size());
        System.out.println("dbcMatchedFrames=" + matched.size());
        System.out.println("dbcUnmatchedFrames=" + (decodedMf4.frames().size() - matched.size()));

        if (matched.isEmpty()) {
            System.out.println("No matched frames");
            return;
        }

        CanonicalCanFrame first = matched.getFirst();
        DbcSignal root = msg.multiplexor().orElseThrow();
        var rootExtract = SignalBitExtractor.extract(
                first.data(), root.startBit(), root.bitLength(), root.byteOrder(), root.valueType());
        System.out.println("=== ONE FRAME TRACE ===");
        System.out.println("canId=0x" + Long.toHexString(first.canId()));
        System.out.println("payload=" + HexFormat.of().formatHex(first.data()));
        System.out.println("dlc=" + first.dlc());
        System.out.println("multiplexer=" + root.name()
                + " start=" + root.startBit() + " len=" + root.bitLength()
                + " order=" + root.byteOrder() + " signed=" + root.valueType()
                + " raw=" + rootExtract.rawValue());

        Map<String, Long> raw = new LinkedHashMap<>();
        raw.put(root.name(), rootExtract.rawValue());
        // Attempt secondary extract if conditions allow
        for (DbcSignal s : msg.signals()) {
            if (!s.hasExplicitMultiplexConditions()) {
                continue;
            }
            if (s.isApplicableForExplicitMultiplex(raw)) {
                var ex = SignalBitExtractor.extract(first.data(), s.startBit(), s.bitLength(), s.byteOrder(), s.valueType());
                if (ex.isOk()) {
                    raw.put(s.name(), ex.rawValue());
                }
            }
        }

        System.out.println("LEAF_APPLICABILITY (signals with SG_MUL_VAL_):");
        int shown = 0;
        for (DbcSignal s : msg.signals()) {
            if (!s.hasExplicitMultiplexConditions()) {
                continue;
            }
            boolean active = s.isApplicableForExplicitMultiplex(raw);
            String cond = s.multiplexConditions().stream()
                    .map(SgMulValFrameTrace::formatCond)
                    .collect(Collectors.joining("; "));
            Long actual = null;
            String reason;
            if (s.multiplexConditions().isEmpty()) {
                reason = "no-condition";
            } else {
                DbcMultiplexCondition c0 = s.multiplexConditions().getFirst();
                actual = raw.get(c0.multiplexorSignalName());
                if (actual == null) {
                    reason = "multiplexor '" + c0.multiplexorSignalName() + "' not decoded/active";
                } else if (active) {
                    reason = "selector " + actual + " in ranges";
                } else {
                    reason = "selector " + actual + " outside ranges";
                }
            }
            System.out.println(String.format(Locale.ROOT,
                    "  %s | cond={%s} | actualSelector=%s | active=%s | %s",
                    s.name(), cond, actual, active, reason));
            if (++shown >= 20 && !active) {
                // keep printing a few actives; otherwise cap inactive spam after first 20 of each?
            }
        }

        DefaultDbcDecoder decoder = new DefaultDbcDecoder();
        Map<Long, Agg> bySelector = new LinkedHashMap<>();
        Set<String> allEmitted = new LinkedHashSet<>();
        int selectorOnly = 0;
        int leafRows = 0;
        int totalSignals = 0;
        Set<String> selectorNames = new LinkedHashSet<>();
        selectorNames.add(root.name());
        msg.signals().stream().filter(DbcSignal::secondaryMultiplexor).map(DbcSignal::name).forEach(selectorNames::add);
        msg.signals().stream().flatMap(s -> s.multiplexConditions().stream())
                .map(DbcMultiplexCondition::multiplexorSignalName).forEach(selectorNames::add);

        System.out.println("=== ALL MATCHED FRAMES ===");
        for (CanonicalCanFrame f : matched) {
            DecodingResult r = decoder.decode(f, db);
            var rootEx = SignalBitExtractor.extract(
                    f.data(), root.startBit(), root.bitLength(), root.byteOrder(), root.valueType());
            long sel = rootEx.rawValue();
            Agg agg = bySelector.computeIfAbsent(sel, k -> new Agg());
            agg.frames++;
            Set<String> leaves = new LinkedHashSet<>();
            for (CanonicalSignal cs : r.signals()) {
                allEmitted.add(cs.signalName());
                totalSignals++;
                if (selectorNames.contains(cs.signalName())) {
                    // selector
                } else {
                    leaves.add(cs.signalName());
                    leafRows++;
                }
            }
            if (leaves.isEmpty()) {
                selectorOnly++;
            }
            agg.leafNames.addAll(leaves);
            agg.activeLeafCountSum += leaves.size();
            System.out.println("payload=" + HexFormat.of().formatHex(f.data())
                    + " selector=" + sel
                    + " emitted=" + r.signals().stream().map(CanonicalSignal::signalName).toList());
        }

        System.out.println("=== AGGREGATE BY SELECTOR ===");
        for (var e : bySelector.entrySet()) {
            Agg a = e.getValue();
            System.out.println("selector=" + e.getKey()
                    + " frames=" + a.frames
                    + " uniqueLeaves=" + a.leafNames.size()
                    + " leafNames=" + a.leafNames);
        }
        System.out.println("selectorOnlyFrames=" + selectorOnly);
        System.out.println("leafSignalRows=" + leafRows);
        System.out.println("totalCanonicalSignals=" + totalSignals);
        System.out.println("uniqueEmitted=" + allEmitted.size() + " names=" + allEmitted);
    }

    private static String formatCond(DbcMultiplexCondition c) {
        String ranges = c.ranges().stream()
                .map(r -> r.minimum() + "-" + r.maximum())
                .collect(Collectors.joining(","));
        return c.multiplexorSignalName() + " in [" + ranges + "]";
    }

    private static final class Agg {
        int frames;
        int activeLeafCountSum;
        final Set<String> leafNames = new LinkedHashSet<>();
    }
}
